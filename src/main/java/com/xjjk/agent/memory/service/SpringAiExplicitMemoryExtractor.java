package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import com.xjjk.agent.prompt.StrictPromptTemplateRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Spring AI 的显式记忆语义兜底实现。
 *
 * <p>模型只输出受限 JSON 分类和事实候选，不负责生成 canonicalKey、最终正文或授权写入；
 * 调用被放入独立有界线程池并受超时控制，避免拖住聊天生产线程。</p>
 */
@Component
public class SpringAiExplicitMemoryExtractor implements ExplicitMemoryExtractor {

    private final ChatClient chatClient;
    private final UserMemoryProperties properties;
    private final ExecutorService modelExecutor;
    private final ObjectReader responseReader;
    private final AgentPromptCatalogProperties promptCatalog;
    private final StrictPromptTemplateRenderer promptRenderer;

    public SpringAiExplicitMemoryExtractor(
            @Qualifier("memoryChatClient") ChatClient chatClient,
            UserMemoryProperties properties,
            @Qualifier("memoryExtractionModelExecutor") ExecutorService modelExecutor,
            ObjectMapper objectMapper,
            AgentPromptCatalogProperties promptCatalog,
            StrictPromptTemplateRenderer promptRenderer
    ) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelExecutor = Objects.requireNonNull(modelExecutor, "modelExecutor");
        this.responseReader = Objects.requireNonNull(objectMapper, "objectMapper")
                .readerFor(ModelResult.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "promptCatalog");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
    }

    @Override
    public ExplicitMemoryResolution resolve(String originalMessage) {
        Objects.requireNonNull(originalMessage, "originalMessage");
        Future<ExplicitMemoryResolution> future;
        try {
            // 第一步：将模型调用提交到记忆专用有界线程池，与普通聊天模型资源隔离。
            future = modelExecutor.submit(() -> invoke(originalMessage));
        } catch (RejectedExecutionException exception) {
            // 队列已满时快速失败，不允许在调用线程中退化执行并放大系统压力。
            throw failure(CodeAlias.CALL);
        }
        try {
            // 第二步：使用独立超时上限等待结果，避免显式记忆识别无限阻塞本轮回答。
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            // 超时后中断任务并返回受控错误，不能把未完成结果当作 NONE 静默放行。
            future.cancel(true);
            throw new ExplicitMemoryExtractionException(
                    ExplicitMemoryExtractionException.Code.MODEL_TIMEOUT,
                    "显式记忆抽取超时");
        } catch (InterruptedException exception) {
            // 保留线程中断标志，使上层取消和应用关闭语义能够继续传播。
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
            // 第三步：只发送协议版本和本轮用户原文；历史消息不能替用户产生保存意图。
            output = chatClient.prompt()
                    .system(promptCatalog.memory().explicit().system())
                    .user(promptRenderer.render(
                            "agent.ai.prompt.catalog.memory.explicit.user-template",
                            promptCatalog.memory().explicit().userTemplate(),
                            Map.of("promptVersion", properties.promptVersion(),
                                    "sourceMessage", originalMessage)))
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            throw failure(CodeAlias.CALL);
        }
        if (!StringUtils.hasText(output)) {
            throw failure(CodeAlias.PROTOCOL);
        }
        try {
            // 第四步：严格反序列化单个 JSON 对象，拒绝未知字段和尾随内容，
            // 防止模型解释文字或协议漂移被误当作合法事实。
            ModelResult result = responseReader.readValue(output);
            ExplicitMemoryResolution.Action action = ExplicitMemoryResolution.Action.valueOf(
                    requireText(result.action()));
            double confidence = requireConfidence(result.confidence());
            // 第五步：NONE/CLARIFY 不构造事实；SAVE 只构造原始语义候选，
            // 后续 Validator 还会重新校验证据、时间、敏感内容和服务端 Schema。
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
        // 这里只验证模型分数的协议范围；真正的接受阈值由混合解析器统一执行。
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
        // 区分模型调用故障与响应协议故障，便于指标和排障，但不向用户暴露供应商细节。
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
