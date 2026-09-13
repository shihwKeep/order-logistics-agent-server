package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
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
            action 只能是 SAVE、CLARIFY、NONE。用户在语义上明确要求系统以后保存或持续遵守可跨会话复用的低风险个人事实/偏好时用 SAVE，不要求固定触发词，也不要依赖固定句式或技术词表；
            意图像记忆但关键信息不完整时用 CLARIFY；普通问答、业务查询或并未要求长期保存时用 NONE。
            可抽取年龄、职业、工作单位、技能、技术栈、沟通/回答偏好、称呼，以及其他稳定的个人偏好或事实。普通自述的年龄、职业和偏好可以进入候选。
            SAVE 时必须输出 memoryType、predicate、value、valueEvidence、evidenceText、stability、temporalScope、retention、confidence；其他动作只需 action 和 confidence。
            memoryType 只能是 PROFILE、COMMUNICATION_PREFERENCE、RESPONSE_PREFERENCE、WORK_CONTEXT、STABLE_PREFERENCE、STABLE_USER_FACT。
            常用 predicate 包括 preferred_name、answer_language、answer_style、occupation、current_employer、primary_programming_language、technology_stack、common_scope；它们不是封闭词表，其他低风险画像可提出简短 snake_case predicate，由服务端模式、证据与安全策略决定是否接受。
            stability 只能是 STABLE、TIME_BOUND、TEMPORARY、UNKNOWN。STABLE 表示长期稳定；TIME_BOUND 表示会随时间变化但其当前或历史状态仍有用，例如年龄、当前职业和工作单位。SAVE 只允许 STABLE 或 TIME_BOUND；TEMPORARY、UNKNOWN 不得 SAVE。
            temporalScope 必填且只能是 CURRENT 或 HISTORICAL。现在、目前、今年，或没有历史提示且陈述当前状态时用 CURRENT；以前、曾经、过去时用 HISTORICAL；无法判断时态则不要 SAVE。
            retention 只能是 NORMAL 或 PERMANENT；只有用户明确要求永久保存时才用 PERMANENT。temporalScope 与 retention 正交，历史事实也不自动表示永久保存。
            valueEvidence 必须是直接支持 value 的用户原文连续片段，evidenceText 必须是用户原文连续片段并包含 valueEvidence，value 只能规范化 valueEvidence。禁止推断出生年、年龄递增后的当前年龄、隐含职业或其他原文未明说的事实。
            canonicalKey、category 和最终 content 均由服务端生成，模型不得输出或决定。
            禁止抽取高风险身份凭证、联系方式、账户或账号凭据、健康信息、精确地址、客户业务记录（含订单、退款、物流、支付）、客户资料和企业制度；最终服务端敏感策略不可绕过。
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
                                        MemoryTemporalScope.valueOf(
                                                requireText(result.temporalScope())),
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
            String temporalScope,
            String retention,
            Double confidence
    ) {
    }
}
