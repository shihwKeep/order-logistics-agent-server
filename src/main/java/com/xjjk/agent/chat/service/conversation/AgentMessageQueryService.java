package com.xjjk.agent.chat.service.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatMessagePageResponse;
import com.xjjk.agent.chat.api.dto.ChatMessageResultResponse;
import com.xjjk.agent.chat.api.dto.ChatMessageResponse;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.result.AgentMessageResultEntity;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 历史消息查询服务。
 *
 * 先校验会话归属，再按照消息序号向前分页。
 * 返回所有状态的消息，不仅限于成功消息。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentMessageQueryService {

    /** 单次查询允许返回的最大消息条数。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 接口展示时间使用的时区。 */
    private static final ZoneId DISPLAY_ZONE =
            ZoneId.of("Asia/Shanghai");

    /** 第一版前端明确支持的结构化结果协议，未知类型或版本必须失败关闭。 */
    private static final Map<String, Set<Integer>> SUPPORTED_RESULT_SCHEMAS = Map.of(
            "product-list", Set.of(1),
            "customer-list", Set.of(1),
            "order-list", Set.of(1),
            "logistics-timeline", Set.of(1),
            "after-sale-list", Set.of(1),
            "after-sale-detail", Set.of(1));

    /** 会话归属校验服务。 */
    private final AgentConversationService conversationService;

    /** 消息数据访问接口。 */
    private final AgentMessageMapper messageMapper;

    /** 结构化结果数据访问接口，用于对当前消息页执行一次批量读取。 */
    private final AgentMessageResultMapper resultMapper;

    /** 仅把已经通过协议白名单的 JSON 快照解析为响应数据。 */
    private final ObjectMapper objectMapper;

    /**
     * 分页查询当前用户的历史消息。
     *
     * @param conversationId 会话 ID
     * @param identity 后端认证得到的用户身份
     * @param beforeSequence 只查询此序号之前的消息；为空时查询最新一页
     * @param pageSize 本页最多返回的消息条数，范围为 1～100
     */
    public ChatMessagePageResponse queryPage(
            String conversationId,
            AgentIdentity identity,
            Long beforeSequence,
            int pageSize
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        if (beforeSequence != null && beforeSequence < 1) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        // 即使会话没有消息，也必须先校验归属。
        // 不存在的会话与无权访问的会话，对外使用相同错误。
        conversationService.requireOwned(conversationId, identity);

        // 多查询一条，仅用于判断是否还有更早的消息。
        int queryLimit = pageSize + 1;

        List<AgentMessageEntity> rows = messageMapper.selectList(
                Wrappers.<AgentMessageEntity>lambdaQuery()
                        .eq(
                                AgentMessageEntity::getTenantId,
                                identity.tenantId()
                        )
                        .eq(
                                AgentMessageEntity::getUserId,
                                identity.userId()
                        )
                        .eq(
                                AgentMessageEntity::getConversationId,
                                conversationId
                        )
                        .lt(
                                beforeSequence != null,
                                AgentMessageEntity::getMessageSequence,
                                beforeSequence
                        )
                        .orderByDesc(
                                AgentMessageEntity::getMessageSequence
                        )
                        // 这里只拼接经过范围校验的整数，
                        // 不允许拼接前端传入的 SQL 片段。
                        .last("LIMIT " + queryLimit)
        );

        boolean hasMore = rows.size() > pageSize;

        // 去掉用于探测下一页的额外消息，并复制为可修改列表。
        List<AgentMessageEntity> page = new ArrayList<>(
                rows.subList(0, Math.min(rows.size(), pageSize))
        );

        // 当前仍是倒序，最后一条就是本页最小序号。
        // 下一页使用本页保留的消息作为边界，不能使用额外探测行。
        Long nextBeforeSequence = hasMore
                ? page.get(page.size() - 1).getMessageSequence()
                : null;

        // 查询时优先取最新消息，返回时恢复为从旧到新的阅读顺序。
        Collections.reverse(page);

        Map<String, List<ChatMessageResultResponse>> resultsByMessageId =
                loadResults(page, identity, conversationId);

        List<ChatMessageResponse> items = page.stream()
                .map(message -> toResponse(
                        message,
                        resultsByMessageId.getOrDefault(
                                message.getMessageId(), List.of())))
                .toList();

        return new ChatMessagePageResponse(
                items,
                nextBeforeSequence,
                hasMore
        );
    }

    /**
     * 将数据库消息转换为接口响应。
     */
    private ChatMessageResponse toResponse(
            AgentMessageEntity message,
            List<ChatMessageResultResponse> results) {
        return new ChatMessageResponse(
                message.getMessageId(),
                message.getRequestId(),
                message.getMessageSequence(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getFinishReason(),
                message.getErrorCode(),
                // 数据库存储的是 UTC 时间，展示时转换为上海时间。
                message.getCreatedAt()
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(DISPLAY_ZONE)
                        .toOffsetDateTime(),
                results
        );
    }

    private Map<String, List<ChatMessageResultResponse>> loadResults(
            List<AgentMessageEntity> messages,
            AgentIdentity identity,
            String conversationId) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        List<String> messageIds = messages.stream()
                .map(AgentMessageEntity::getMessageId)
                .toList();
        List<AgentMessageResultEntity> rows = resultMapper.selectByMessageIds(
                identity.tenantId(), identity.userId(), conversationId, messageIds);
        Map<String, List<ChatMessageResultResponse>> grouped = new HashMap<>();
        for (AgentMessageResultEntity row : rows) {
            ChatMessageResultResponse response = toResultResponse(row);
            if (response != null) {
                grouped.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>())
                        .add(response);
            }
        }
        grouped.replaceAll((ignored, values) -> values.stream()
                .sorted((left, right) -> Integer.compare(
                        left.resultSequence(), right.resultSequence()))
                .toList());
        return Map.copyOf(grouped);
    }

    /**
     * 单条损坏快照不能拖垮整页历史：协议未知、字段缺失或 JSON 损坏时跳过，
     * 日志只记录结果元数据和安全异常类型，不输出 payload_json 正文。
     */
    private ChatMessageResultResponse toResultResponse(
            AgentMessageResultEntity row) {
        try {
            if (row == null
                    || row.getMessageId() == null
                    || row.getResultSequence() == null
                    || row.getResultSequence() <= 0
                    || row.getKind() == null
                    || row.getSchemaVersion() == null
                    || !SUPPORTED_RESULT_SCHEMAS.getOrDefault(
                            row.getKind(), Set.of()).contains(row.getSchemaVersion())
                    || row.getQueriedAt() == null) {
                warnSkipped(row, "UNSUPPORTED_OR_INVALID", null);
                return null;
            }
            JsonNode data = objectMapper.readTree(row.getPayloadJson());
            if (data == null || data.isNull()) {
                warnSkipped(row, "INVALID_JSON", null);
                return null;
            }
            return new ChatMessageResultResponse(
                    row.getResultSequence(),
                    row.getKind(),
                    row.getSchemaVersion(),
                    row.getQueriedAt()
                            .atOffset(ZoneOffset.UTC)
                            .atZoneSameInstant(DISPLAY_ZONE)
                            .toOffsetDateTime(),
                    data);
        } catch (JsonProcessingException | RuntimeException exception) {
            warnSkipped(row, "INVALID_JSON", exception);
            return null;
        }
    }

    private void warnSkipped(
            AgentMessageResultEntity row,
            String reason,
            Throwable exception) {
        log.warn(
                "chat_message_result_skipped messageId={}, resultSequence={}, kind={}, schemaVersion={}, reason={}, exceptionType={}",
                row == null ? null : row.getMessageId(),
                row == null ? null : row.getResultSequence(),
                row == null ? null : row.getKind(),
                row == null ? null : row.getSchemaVersion(),
                reason,
                exception == null ? null : exception.getClass().getSimpleName());
    }
}
