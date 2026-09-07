package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateTurn;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.persistence.projection.ChatSummaryMessageMetadata;
import com.xjjk.agent.chat.service.memory.QwenTextTokenEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 从 MySQL 正序加载可进入长期摘要的连续稳定轮次。
 *
 * 短期历史从最新消息向后选择；长期摘要必须反向处理，从上次摘要覆盖边界的
 * 下一条消息开始向前推进。两阶段查询先筛元信息，再读取必要正文。
 */
@Service
@RequiredArgsConstructor
public class ChatSummaryCandidateLoader {

    private final AgentMessageMapper messageMapper;
    private final ChatSummaryProperties properties;
    private final QwenTextTokenEstimator tokenEstimator;

    /**
     * 加载一次摘要候选批次。
     *
     * @param previousCoveredUntilSequence 上一版摘要连续覆盖边界；无摘要时为 0
     * @param capturedMemoryVersion Worker 领取任务时捕获的稳定历史版本
     * @param capturedUntilSequence 捕获版本对应的稳定消息边界
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            timeout = 5,
            rollbackFor = Exception.class
    )
    public ChatSummaryCandidateBatch load(
            long tenantId,
            long userId,
            String conversationId,
            long previousCoveredUntilSequence,
            long capturedMemoryVersion,
            long capturedUntilSequence
    ) {
        validateCursor(
                tenantId,
                userId,
                conversationId,
                previousCoveredUntilSequence,
                capturedMemoryVersion,
                capturedUntilSequence
        );

        long eligibleEndSequence = findEligibleEndSequence(
                tenantId,
                userId,
                conversationId,
                previousCoveredUntilSequence,
                capturedUntilSequence
        );
        if (eligibleEndSequence <= previousCoveredUntilSequence) {
            return emptyBatch(
                    tenantId,
                    userId,
                    conversationId,
                    previousCoveredUntilSequence,
                    capturedMemoryVersion,
                    capturedUntilSequence
            );
        }

        int maxMessages = properties.maxBatchMessages();
        // 多查一条只用于探测消息数上限之后是否仍有候选，不会把探测行送入正文查询。
        List<ChatSummaryMessageMetadata> queried =
                messageMapper.selectSummaryMetadata(
                        tenantId,
                        userId,
                        conversationId,
                        previousCoveredUntilSequence,
                        eligibleEndSequence,
                        Math.addExact(maxMessages, 1)
                );
        boolean scanLimitReached = queried.size() > maxMessages;
        List<ChatSummaryMessageMetadata> metadata = queried.subList(
                0,
                Math.min(queried.size(), maxMessages)
        );
        if (!scanLimitReached
                && (metadata.isEmpty()
                || metadata.get(metadata.size() - 1)
                .getMessageSequence() == null
                || metadata.get(metadata.size() - 1)
                .getMessageSequence() != eligibleEndSequence)) {
            // 未到扫描上限却没有读到计算出的末端，说明稳定历史中存在缺口，必须拒绝推进摘要游标。
            throw new IllegalStateException("摘要候选范围未完整覆盖稳定边界");
        }

        // 第一阶段只读取轻量元信息，在不加载大正文的情况下完成连续性和字节预算裁剪。
        MetadataSelection selection = selectMetadata(
                metadata,
                previousCoveredUntilSequence,
                scanLimitReached
        );
        if (selection.rows().isEmpty()) {
            return new ChatSummaryCandidateBatch(
                    tenantId, userId, conversationId,
                    previousCoveredUntilSequence,
                    capturedMemoryVersion, capturedUntilSequence,
                    List.of(), 0, previousCoveredUntilSequence,
                    0, 0, scanLimitReached,
                    selection.readBudgetTruncated(), false,
                    scanLimitReached || selection.readBudgetTruncated()
            );
        }

        // 第二阶段只读取已选连续范围的正文，再使用真实正文执行 Token 预算裁剪。
        List<AgentMessageEntity> bodies =
                messageMapper.selectSummaryBodies(
                        tenantId,
                        userId,
                        conversationId,
                        selection.rows().get(0).getMessageSequence(),
                        selection.rows().get(
                                selection.rows().size() - 1
                        ).getMessageSequence()
                );

        BodySelection bodySelection = buildTurns(
                selection.rows(),
                bodies
        );
        long selectedUntil = bodySelection.turns().isEmpty()
                ? previousCoveredUntilSequence
                : bodySelection.turns().get(
                        bodySelection.turns().size() - 1
                ).assistantSequence();
        // 只有已确认因扫描、字节或 Token 上限截断时才标记仍有积压；
        // 不能仅凭游标差值推断，避免把消息缺口误认为可继续处理的历史。
        boolean hasMore = selectedUntil < eligibleEndSequence
                && (scanLimitReached
                || selection.readBudgetTruncated()
                || bodySelection.tokenBudgetTruncated());

        return new ChatSummaryCandidateBatch(
                tenantId,
                userId,
                conversationId,
                previousCoveredUntilSequence,
                capturedMemoryVersion,
                capturedUntilSequence,
                bodySelection.turns(),
                bodySelection.turns().isEmpty()
                        ? 0
                        : bodySelection.turns().get(0).userSequence(),
                selectedUntil,
                bodySelection.contentBytes(),
                bodySelection.estimatedTokens(),
                scanLimitReached,
                selection.readBudgetTruncated(),
                bodySelection.tokenBudgetTruncated(),
                hasMore
        );
    }

    /**
     * 用最新真实消息对确定保留窗口，不能用 sequence 简单减八代替完整轮次验证。
     */
    private long findEligibleEndSequence(
            long tenantId,
            long userId,
            String conversationId,
            long previousCoveredUntilSequence,
            long capturedUntilSequence
    ) {
        int retainedMessageCount = Math.multiplyExact(
                properties.retainRecentTurns(),
                2
        );
        List<ChatSummaryMessageMetadata> recent = new ArrayList<>(
                messageMapper.selectRecentSummaryMetadata(
                        tenantId,
                        userId,
                        conversationId,
                        capturedUntilSequence,
                        retainedMessageCount
                )
        );
        // 查询结果必须以 Worker 领取任务时捕获的稳定边界开头；否则不能据此计算保留窗口。
        if (recent.isEmpty()
                || recent.get(0).getMessageSequence() == null
                || recent.get(0).getMessageSequence()
                != capturedUntilSequence) {
            throw new IllegalStateException("摘要最近消息未覆盖捕获边界");
        }
        Collections.reverse(recent);
        validatePairs(recent, recent.get(0).getMessageSequence() - 1, false);
        for (int index = 1; index < recent.size(); index += 2) {
            if (parseStatus(recent.get(index).getStatus())
                    == MessageStatus.GENERATING) {
                throw new IllegalStateException("摘要最近保留窗口包含非终态助手消息");
            }
        }
        if (recent.size() < retainedMessageCount) {
            return previousCoveredUntilSequence;
        }
        return recent.get(0).getMessageSequence() - 1;
    }

    /**
     * 从旧摘要边界之后按顺序选择完整问答对，并在元信息阶段执行正文字节预算。
     * 遇到非终态助手消息或预算边界立即停止，保证摘要覆盖游标只能连续推进。
     */
    private MetadataSelection selectMetadata(
            List<ChatSummaryMessageMetadata> rows,
            long previousCoveredUntilSequence,
            boolean scanLimitReached
    ) {
        if (rows.isEmpty()) {
            return new MetadataSelection(List.of(), false);
        }
        validatePairs(rows, previousCoveredUntilSequence, scanLimitReached);

        List<ChatSummaryMessageMetadata> selected = new ArrayList<>();
        long selectedBytes = 0;
        boolean readBudgetTruncated = false;
        for (int index = 0; index + 1 < rows.size(); index += 2) {
            ChatSummaryMessageMetadata assistant = rows.get(index + 1);
            if (MessageStatus.GENERATING.name().equals(assistant.getStatus())) {
                break;
            }
            long pairBytes = Math.addExact(
                    requireBytes(rows.get(index)),
                    requireBytes(assistant)
            );
            if (Math.addExact(selectedBytes, pairBytes)
                    > properties.maxBatchBytes()) {
                readBudgetTruncated = true;
                break;
            }
            selected.add(rows.get(index));
            selected.add(assistant);
            selectedBytes += pairBytes;
        }
        return new MetadataSelection(List.copyOf(selected), readBudgetTruncated);
    }

    /** 验证消息严格按 USER、ASSISTANT 成对连续排列。 */
    private static void validatePairs(
            List<ChatSummaryMessageMetadata> rows,
            long previousSequence,
            boolean finalRowMayBeProbeBoundary
    ) {
        long expectedSequence = Math.addExact(previousSequence, 1L);
        for (int index = 0; index < rows.size(); index++) {
            ChatSummaryMessageMetadata row = rows.get(index);
            if (row.getMessageSequence() == null
                    || row.getMessageSequence() != expectedSequence
                    || !StringUtils.hasText(row.getMessageId())
                    || !StringUtils.hasText(row.getRequestId())
                    || row.getContentBytes() == null
                    || row.getContentBytes() < 0) {
                throw new IllegalStateException("摘要候选消息元信息不连续");
            }
            expectedSequence = Math.addExact(expectedSequence, 1L);

            boolean userPosition = index % 2 == 0;
            String expectedRole = userPosition
                    ? MessageRole.USER.name()
                    : MessageRole.ASSISTANT.name();
            if (!expectedRole.equals(row.getRole())) {
                throw new IllegalStateException("摘要候选消息角色顺序异常");
            }
            if (userPosition
                    && !MessageStatus.SUCCESS.name().equals(row.getStatus())) {
                throw new IllegalStateException("摘要候选用户消息状态异常");
            }
            if (!userPosition) {
                ChatSummaryMessageMetadata user = rows.get(index - 1);
                if (!Objects.equals(user.getRequestId(), row.getRequestId())) {
                    throw new IllegalStateException("摘要候选问答请求不匹配");
                }
                parseStatus(row.getStatus());
            }
        }
        if (rows.size() % 2 != 0 && !finalRowMayBeProbeBoundary) {
            throw new IllegalStateException("摘要候选消息缺少配对消息");
        }
    }

    /**
     * 校验正文与第一阶段元信息完全一致，并按完整问答轮次执行 Token 预算。
     * 单轮不允许拆分或截断；放不下时保留该轮给后续任务或交由超大候选保护处理。
     */
    private BodySelection buildTurns(
            List<ChatSummaryMessageMetadata> metadata,
            List<AgentMessageEntity> bodies
    ) {
        if (bodies.size() != metadata.size()) {
            throw new IllegalStateException("摘要候选正文数量与元信息不一致");
        }
        List<ChatSummaryCandidateTurn> turns = new ArrayList<>();
        long totalBytes = 0;
        long totalTokens = 0;
        boolean tokenBudgetTruncated = false;

        for (int index = 0; index < bodies.size(); index += 2) {
            AgentMessageEntity user = bodies.get(index);
            AgentMessageEntity assistant = bodies.get(index + 1);
            verifyBody(metadata.get(index), user);
            verifyBody(metadata.get(index + 1), assistant);

            MessageStatus assistantStatus = parseStatus(assistant.getStatus());
            if (!StringUtils.hasText(user.getContent())) {
                throw new IllegalStateException("摘要候选用户正文为空");
            }
            // 失败或中断的助手消息只保留终态，不把可能不完整的正文写入长期摘要。
            String assistantContent = null;
            if (assistantStatus == MessageStatus.SUCCESS) {
                if (!StringUtils.hasText(assistant.getContent())) {
                    throw new IllegalStateException("成功助手摘要候选正文为空");
                }
                assistantContent = assistant.getContent();
            }

            long pairBytes = Math.addExact(
                    utf8Bytes(user.getContent()),
                    utf8Bytes(assistant.getContent())
            );
            long pairTokens = tokenEstimator.estimate(
                    renderForEstimation(
                            user.getContent(),
                            assistantContent,
                            assistantStatus
                    )
            );
            if (Math.addExact(totalTokens, pairTokens)
                    > properties.maxBatchTokens()) {
                tokenBudgetTruncated = true;
                break;
            }

            turns.add(new ChatSummaryCandidateTurn(
                    user.getRequestId(),
                    user.getMessageSequence(),
                    assistant.getMessageSequence(),
                    user.getContent(),
                    assistantContent,
                    assistantStatus,
                    pairBytes,
                    pairTokens
            ));
            totalBytes += pairBytes;
            totalTokens += pairTokens;
        }
        return new BodySelection(
                List.copyOf(turns),
                totalBytes,
                totalTokens,
                tokenBudgetTruncated
        );
    }

    private static void verifyBody(
            ChatSummaryMessageMetadata metadata,
            AgentMessageEntity body
    ) {
        if (!Objects.equals(metadata.getMessageId(), body.getMessageId())
                || !Objects.equals(metadata.getRequestId(), body.getRequestId())
                || !Objects.equals(metadata.getMessageSequence(),
                body.getMessageSequence())
                || !Objects.equals(metadata.getRole(), body.getRole())
                || !Objects.equals(metadata.getStatus(), body.getStatus())
                || metadata.getContentBytes() != utf8Bytes(body.getContent())) {
            throw new IllegalStateException("摘要候选正文与元信息不一致");
        }
    }

    private static String renderForEstimation(
            String userContent,
            String assistantContent,
            MessageStatus assistantStatus
    ) {
        return "USER:\n" + userContent
                + "\nASSISTANT_STATUS:\n" + assistantStatus.name()
                + (assistantContent == null
                ? ""
                : "\nASSISTANT:\n" + assistantContent);
    }

    private static long requireBytes(ChatSummaryMessageMetadata row) {
        if (row.getContentBytes() == null || row.getContentBytes() < 0) {
            throw new IllegalStateException("摘要候选正文字节数异常");
        }
        return row.getContentBytes();
    }

    private static long utf8Bytes(String content) {
        return content == null
                ? 0
                : content.getBytes(StandardCharsets.UTF_8).length;
    }

    private static MessageStatus parseStatus(String status) {
        try {
            return MessageStatus.valueOf(status);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("摘要候选消息状态未知", exception);
        }
    }

    private static void validateCursor(
            long tenantId,
            long userId,
            String conversationId,
            long previousCoveredUntilSequence,
            long capturedMemoryVersion,
            long capturedUntilSequence
    ) {
        if (tenantId <= 0 || userId <= 0
                || !StringUtils.hasText(conversationId)
                || previousCoveredUntilSequence < 0
                || capturedMemoryVersion < 1
                || capturedUntilSequence < previousCoveredUntilSequence) {
            throw new IllegalArgumentException("摘要候选读取游标不合法");
        }
    }

    private static ChatSummaryCandidateBatch emptyBatch(
            long tenantId,
            long userId,
            String conversationId,
            long previousCoveredUntilSequence,
            long capturedMemoryVersion,
            long capturedUntilSequence
    ) {
        return new ChatSummaryCandidateBatch(
                tenantId, userId, conversationId,
                previousCoveredUntilSequence,
                capturedMemoryVersion, capturedUntilSequence,
                List.of(), 0, previousCoveredUntilSequence,
                0, 0, false, false, false, false
        );
    }

    private record MetadataSelection(
            List<ChatSummaryMessageMetadata> rows,
            boolean readBudgetTruncated
    ) {
    }

    private record BodySelection(
            List<ChatSummaryCandidateTurn> turns,
            long contentBytes,
            long estimatedTokens,
            boolean tokenBudgetTruncated
    ) {
    }
}
