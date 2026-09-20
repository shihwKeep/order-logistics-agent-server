package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateTurn;
import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryDraft;
import com.xjjk.agent.chat.domain.summary.ChatSummaryGenerationException;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.domain.summary.ChatSummarySourceType;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生成结构化滚动摘要，并在离开本边界前完成脱敏、反序列化和来源校验。
 *
 * 本类不启动事务，也不写数据库；Worker 必须在数据库事务之外调用它。
 */
@Service
@RequiredArgsConstructor
public class ChatSummaryGenerator {

    private final ObjectMapper objectMapper;
    private final ChatSummaryModelClient modelClient;
    private final SensitiveContentSanitizer sanitizer;
    private final ChatSummaryValidator validator;
    private final ChatSummaryProperties properties;
    private final AgentPromptCatalogProperties promptCatalog;

    /**
     * 将“上一版摘要 + 本批新增问答”滚动生成下一版结构化摘要。
     *
     * 本方法负责输入隔离、模型调用、JSON 反序列化、敏感信息清理和来源校验；
     * 最多允许一次纯格式纠正重试，避免格式错误导致无限消耗模型配额。
     */
    public ChatSummaryDraft generate(
            ChatSummarySnapshot previous,
            ChatSummaryCandidateBatch batch
    ) {
        Objects.requireNonNull(previous, "上一版摘要不能为空");
        Objects.requireNonNull(batch, "摘要候选批次不能为空");
        // 第一步：确认旧摘要与候选批次属于同一租户、用户、会话和连续覆盖边界。
        // 这项校验在调用模型之前完成，防止不同会话的数据被合并进同一份摘要。
        validateOwnership(previous, batch);
        if (batch.turns().isEmpty()) {
            throw new IllegalArgumentException("空候选批次不能调用摘要模型");
        }

        // 第二步：把旧摘要和新增原始轮次编码成 JSON 数据区。
        // 用户正文只能作为 JSON 字段值出现，不能直接改变系统提示词或消息结构。
        String inputJson = buildInput(previous, batch);

        // 第三步：建立本次模型输出允许引用的消息序号白名单。
        // 后续 Validator 据此拒绝模型编造来源序号或把 USER/ASSISTANT 角色标错。
        AllowedSources allowedSources = buildAllowedSources(previous, batch);
        UsageAccumulator usage = new UsageAccumulator();
        long startedAt = System.nanoTime();

        for (int attempt = 1; attempt <= 2; attempt++) {
            // 第四步：首次请求生成摘要；第二次请求只纠正同一输入的 JSON 格式，
            // 不补充新正文、不扩大来源范围，也不允许无限重试。
            ChatSummaryModelClient.Response response = modelClient.generate(
                    new ChatSummaryModelClient.Request(
                            promptCatalog.summary().system(),
                            inputJson,
                            attempt == 2
                    )
            );
            // 两次模型调用的 Token 用量需要累计，便于记录一次摘要任务的完整成本。
            usage.add(response);
            if (!properties.model().equals(response.modelName())) {
                throw new ChatSummaryGenerationException(
                        ChatSummaryGenerationException.Code.MODEL_PROTOCOL_ERROR,
                        "摘要模型响应名称与配置不一致"
                );
            }
            try {
                // 第五步：先按强类型结构解析，再清理敏感内容，最后校验长度、数量和来源白名单。
                // 只有整个链路通过后才能形成 ChatSummaryDraft，模型原始 JSON 不会直接落库。
                ChatSummaryContent parsed = objectMapper.readValue(
                        response.rawJson(),
                        ChatSummaryContent.class
                );
                ChatSummaryContent sanitized = sanitizer.sanitize(parsed);
                ChatSummaryContent validated = validator.validate(
                        sanitized,
                        allowedSources.typedSources(),
                        allowedSources.allSequences()
                );
                // 草稿仍不是数据库记录；Worker 会把它交给提交服务做租约和摘要版本 CAS 校验。
                return new ChatSummaryDraft(
                        validated,
                        response.modelName(),
                        properties.promptVersion(),
                        usage.inputTokens(),
                        usage.outputTokens(),
                        elapsedMillis(startedAt),
                        attempt
                );
            } catch (JsonProcessingException error) {
                // 第一次解析或结构映射失败时进入第二次格式纠正；第二次失败才转成可重试业务错误。
                if (attempt == 2) {
                    throw new ChatSummaryGenerationException(
                            error instanceof JsonParseException
                                    ? ChatSummaryGenerationException.Code
                                    .INVALID_JSON_RESPONSE
                                    : ChatSummaryGenerationException.Code
                                    .INVALID_SUMMARY_STRUCTURE,
                            error instanceof JsonParseException
                                    ? "摘要模型连续返回无法解析的 JSON"
                                    : "摘要模型连续返回不符合约定的摘要结构",
                            error
                    );
                }
            } catch (IllegalArgumentException error) {
                // 清理或业务结构校验失败也只允许纠正一次，不能接受部分有效的模型结果。
                if (attempt == 2) {
                    throw new ChatSummaryGenerationException(
                            ChatSummaryGenerationException.Code
                                    .INVALID_SUMMARY_STRUCTURE,
                            "摘要模型连续返回不符合约定的摘要结构",
                            error
                    );
                }
            }
        }
        throw new IllegalStateException("摘要生成重试状态异常");
    }

    /** 使用 JSON 编码承载不可信正文，避免正文伪造模型请求结构。 */
    private String buildInput(
            ChatSummarySnapshot previous,
            ChatSummaryCandidateBatch batch
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", properties.schemaVersion());
        root.put("previousCoveredUntilSequence",
                previous.coveredUntilSequence());
        if (previous.present()) {
            root.set("previousSummary", objectMapper.valueToTree(
                    sanitizer.sanitize(previous.content())
            ));
        } else {
            root.putNull("previousSummary");
        }

        ArrayNode messages = root.putArray("messages");
        for (ChatSummaryCandidateTurn turn : batch.turns()) {
            addMessage(
                    messages,
                    "USER_MESSAGE#" + turn.userSequence(),
                    turn.userSequence(),
                    sanitizer.sanitize(turn.userContent()),
                    MessageStatus.SUCCESS.name()
            );
            if (turn.assistantStatus() == MessageStatus.SUCCESS) {
                addMessage(
                        messages,
                        "ASSISTANT_MESSAGE#" + turn.assistantSequence(),
                        turn.assistantSequence(),
                        sanitizer.sanitize(turn.assistantContent()),
                        turn.assistantStatus().name()
                );
            } else {
                ObjectNode status = messages.addObject();
                status.put("label", "ASSISTANT_STATUS#"
                        + turn.assistantSequence());
                status.put("sourceSequence", turn.assistantSequence());
                status.put("status", turn.assistantStatus().name());
            }
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            throw new ChatSummaryGenerationException(
                    ChatSummaryGenerationException.Code.INPUT_SERIALIZATION_FAILED,
                    "摘要模型输入编码失败",
                    error
            );
        }
    }

    /** 将单条可信来源标签、序号、终态和正文写入模型输入的数据区。 */
    private static void addMessage(
            ArrayNode messages,
            String label,
            long sequence,
            String content,
            String status
    ) {
        ObjectNode message = messages.addObject();
        message.put("label", label);
        message.put("sourceSequence", sequence);
        message.put("status", status);
        message.put("content", content);
    }

    /**
     * 汇总旧摘要已保留的来源和本批新增消息来源，构成新摘要唯一允许引用的证据集合。
     * 事实与实体必须同时匹配序号和角色；决定与待办只要求序号存在于连续来源中。
     */
    private static AllowedSources buildAllowedSources(
            ChatSummarySnapshot previous,
            ChatSummaryCandidateBatch batch
    ) {
        Map<Long, ChatSummarySourceType> typedSources = new HashMap<>();
        Set<Long> allSequences = new HashSet<>();
        if (previous.content() != null) {
            previous.content().conversationFacts().forEach(fact -> {
                typedSources.put(fact.sourceSequence(), fact.sourceType());
                allSequences.add(fact.sourceSequence());
            });
            previous.content().importantEntities().forEach(entity -> {
                typedSources.put(entity.sourceSequence(), entity.sourceType());
                allSequences.add(entity.sourceSequence());
            });
            previous.content().decisions().forEach(item ->
                    allSequences.add(item.sourceSequence()));
            previous.content().openQuestions().forEach(item ->
                    allSequences.add(item.sourceSequence()));
        }
        for (ChatSummaryCandidateTurn turn : batch.turns()) {
            typedSources.put(turn.userSequence(),
                    ChatSummarySourceType.USER_MESSAGE);
            typedSources.put(turn.assistantSequence(),
                    ChatSummarySourceType.ASSISTANT_MESSAGE);
            allSequences.add(turn.userSequence());
            allSequences.add(turn.assistantSequence());
        }
        return new AllowedSources(
                Map.copyOf(typedSources), Set.copyOf(allSequences)
        );
    }

    /** 带角色来源用于事实/实体校验；无角色集合允许旧决定和待办继续保留。 */
    private record AllowedSources(
            Map<Long, ChatSummarySourceType> typedSources,
            Set<Long> allSequences
    ) {
    }

    /** 在生成前校验旧摘要和新批次的归属及衔接边界，禁止跨会话或跳序合并。 */
    private static void validateOwnership(
            ChatSummarySnapshot previous,
            ChatSummaryCandidateBatch batch
    ) {
        if (previous.tenantId() != batch.tenantId()
                || previous.userId() != batch.userId()
                || !previous.conversationId().equals(batch.conversationId())
                || previous.coveredUntilSequence()
                != batch.previousCoveredUntilSequence()) {
            throw new IllegalArgumentException("摘要快照与候选批次不属于同一游标");
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L,
                (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static final class UsageAccumulator {
        private long input;
        private long output;
        private boolean inputPresent;
        private boolean outputPresent;

        void add(ChatSummaryModelClient.Response response) {
            if (response.inputTokens() != null) {
                input = Math.addExact(input, response.inputTokens());
                inputPresent = true;
            }
            if (response.outputTokens() != null) {
                output = Math.addExact(output, response.outputTokens());
                outputPresent = true;
            }
        }

        Long inputTokens() {
            return inputPresent ? input : null;
        }

        Long outputTokens() {
            return outputPresent ? output : null;
        }
    }
}
