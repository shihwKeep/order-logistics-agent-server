package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class SpringAiExplicitMemoryExtractor implements ExplicitMemoryExtractor {

    private static final String SYSTEM_PROMPT = """
            你是企业坐席系统的显式用户记忆意图分类器。只根据用户本轮原文输出一个 JSON 对象。
            action 只能是 SAVE、CLARIFY、NONE。用户在语义上明确要求系统以后保存或持续遵守稳定个人事实/偏好时用 SAVE，不要求固定触发词；
            意图像记忆但关键信息不完整时用 CLARIFY；普通问答、业务查询或并未要求长期保存时用 NONE。
            SAVE 时必须输出 memoryType、predicate、value、valueEvidence、evidenceText、stability、retention、confidence；其他动作只需 action 和 confidence。
            memoryType 只能是 PROFILE、COMMUNICATION_PREFERENCE、RESPONSE_PREFERENCE、WORK_CONTEXT、STABLE_PREFERENCE、STABLE_USER_FACT。
            常用 predicate 包括 preferred_name、answer_language、answer_style、occupation、current_employer、primary_programming_language、technology_stack、common_scope；其他安全稳定事实可给简短 snake_case predicate。
            retention 只能是 NORMAL 或 PERMANENT；只有用户明确要求永久保存时才用 PERMANENT。stability 必须是 STABLE。
            valueEvidence 必须逐字来自 evidenceText，evidenceText 必须逐字取自用户原文，value 只能规范化 valueEvidence。
            canonicalKey、category 和最终 content 均由服务端生成，模型不得输出或决定。
            禁止抽取账号、凭据、身份证、银行卡、手机号、健康诊断、订单、退款、物流等业务记录；禁止推断用户没有明确说出的事实。
            confidence 必须是 0 到 1 的数字。只输出 JSON，不要解释，不要 Markdown。
            """;

    private final ChatClient chatClient;
    private final UserMemoryProperties properties;
    private final ExecutorService modelExecutor;
    private final ObjectMapper objectMapper;

    public SpringAiExplicitMemoryExtractor(
            @Qualifier("memoryChatClient") ChatClient chatClient,
            UserMemoryProperties properties,
            @Qualifier("memoryExtractionModelExecutor") ExecutorService modelExecutor,
            ObjectMapper objectMapper
    ) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelExecutor = Objects.requireNonNull(modelExecutor, "modelExecutor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ExplicitMemoryResolution resolve(String originalMessage) {
        Objects.requireNonNull(originalMessage, "originalMessage");
        Future<ExplicitMemoryResolution> future;
        try {
            future = modelExecutor.submit(() -> invoke(originalMessage));
        } catch (RejectedExecutionException exception) {
            throw failure(CodeAlias.CALL);
        }
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ExplicitMemoryExtractionException(
                    ExplicitMemoryExtractionException.Code.MODEL_TIMEOUT,
                    "显式记忆抽取超时");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(CodeAlias.CALL);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ExplicitMemoryExtractionException typed) {
                throw typed;
            }
            throw failure(CodeAlias.CALL);
        }
    }

    private ExplicitMemoryResolution invoke(String originalMessage) {
        final String output;
        try {
            output = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("协议版本：" + properties.promptVersion()
                            + "\n用户原文：" + originalMessage)
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            throw failure(CodeAlias.CALL);
        }
        if (!StringUtils.hasText(output)) {
            throw failure(CodeAlias.PROTOCOL);
        }
        try {
            ModelResult result = objectMapper.readValue(output, ModelResult.class);
            ExplicitMemoryResolution.Action action = ExplicitMemoryResolution.Action.valueOf(
                    requireText(result.action()));
            double confidence = requireConfidence(result.confidence());
            return switch (action) {
                case NONE -> ExplicitMemoryResolution.none();
                case CLARIFY -> ExplicitMemoryResolution.clarify(
                        ExplicitMemoryResolution.Path.SEMANTIC_PATH);
                case SAVE -> ExplicitMemoryResolution.save(
                        ExplicitMemoryCandidate.semantic(
                                new MemoryFactCandidate(
                                        MemoryType.valueOf(requireText(result.memoryType())),
                                        requireText(result.predicate()),
                                        requireText(result.value()),
                                        requireText(result.valueEvidence()),
                                        requireText(result.evidenceText()),
                                        MemoryStability.valueOf(requireText(result.stability())),
                                        confidence),
                                MemoryRetentionType.valueOf(requireText(result.retention()))),
                        ExplicitMemoryResolution.Path.SEMANTIC_PATH,
                        confidence);
            };
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw failure(CodeAlias.PROTOCOL);
        }
    }

    private static double requireConfidence(Double confidence) {
        if (confidence == null || !Double.isFinite(confidence)
                || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("invalid confidence");
        }
        return confidence;
    }

    private static String requireText(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("blank");
        }
        return value;
    }

    private static ExplicitMemoryExtractionException failure(CodeAlias alias) {
        if (alias == CodeAlias.PROTOCOL) {
            return new ExplicitMemoryExtractionException(
                    ExplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR,
                    "显式记忆抽取响应无效");
        }
        return new ExplicitMemoryExtractionException(
                ExplicitMemoryExtractionException.Code.MODEL_CALL_FAILED,
                "显式记忆抽取调用失败");
    }

    private enum CodeAlias { CALL, PROTOCOL }

    private record ModelResult(
            String action,
            String memoryType,
            String predicate,
            String value,
            String valueEvidence,
            String evidenceText,
            String stability,
            String retention,
            Double confidence
    ) {
    }
}
