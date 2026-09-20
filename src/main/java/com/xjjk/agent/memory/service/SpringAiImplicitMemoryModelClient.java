package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryDecision;
import com.xjjk.agent.memory.domain.MemoryExplicitness;
import com.xjjk.agent.memory.domain.MemoryExtractionDecision;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import com.xjjk.agent.prompt.StrictPromptTemplateRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class SpringAiImplicitMemoryModelClient implements ImplicitMemoryModelClient {

    private static final int MAX_RESPONSE_CANDIDATE_MULTIPLIER = 4;

    private final ChatClient chatClient;
    private final ImplicitMemoryProperties properties;
    private final ExecutorService modelExecutor;
    private final ObjectReader responseReader;
    private final AgentPromptCatalogProperties promptCatalog;
    private final StrictPromptTemplateRenderer promptRenderer;

    public SpringAiImplicitMemoryModelClient(
            @Qualifier("implicitMemoryChatClient") ChatClient chatClient,
            ImplicitMemoryProperties properties,
            @Qualifier("implicitMemoryModelExecutor") ExecutorService modelExecutor,
            ObjectMapper objectMapper,
            AgentPromptCatalogProperties promptCatalog,
            StrictPromptTemplateRenderer promptRenderer
    ) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelExecutor = Objects.requireNonNull(modelExecutor, "modelExecutor");
        this.responseReader = Objects.requireNonNull(objectMapper, "objectMapper")
                .readerFor(SemanticModelResponse.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "promptCatalog");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
    }

    @Override
    public MemoryExtractionDecision analyze(Request request) {
        Objects.requireNonNull(request, "request");
        if (!StringUtils.hasText(request.requestId())
                || !StringUtils.hasText(request.userMessage())) {
            throw protocolFailure();
        }
        Future<MemoryExtractionDecision> future;
        try {
            future = modelExecutor.submit(() -> invokeSemantic(request));
        } catch (RejectedExecutionException exception) {
            throw callFailure();
        }
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ImplicitMemoryExtractionException(
                    ImplicitMemoryExtractionException.Code.MODEL_TIMEOUT,
                    "隐式记忆抽取超时");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw callFailure();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ImplicitMemoryExtractionException typed) {
                throw typed;
            }
            throw callFailure();
        }
    }

    private MemoryExtractionDecision invokeSemantic(Request request) {
        String prior = StringUtils.hasText(request.priorUserMessage())
                ? request.priorUserMessage() : "（无）";
        final String output;
        try {
            output = chatClient.prompt()
                    .system(promptCatalog.memory().implicit().system())
                    .user(promptRenderer.render(
                            "agent.ai.prompt.catalog.memory.implicit.user-template",
                            promptCatalog.memory().implicit().userTemplate(),
                            Map.of("promptVersion", properties.promptVersion(),
                                    "previousMessage", prior,
                                    "currentMessage", request.userMessage())))
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            throw callFailure();
        }
        if (!StringUtils.hasText(output)) {
            throw protocolFailure();
        }
        try {
            SemanticModelResponse response = responseReader.readValue(output);
            MemoryDecision decision = MemoryDecision.valueOf(
                    requireText(response.decision()));
            if (response.candidates() == null) {
                throw protocolFailure();
            }
            if (response.candidates().size()
                    > properties.maxCandidates() * MAX_RESPONSE_CANDIDATE_MULTIPLIER) {
                throw protocolFailure();
            }
            List<MemoryFactCandidate> candidates = new ArrayList<>(
                    response.candidates().size());
            for (SemanticModelCandidate item : response.candidates()) {
                if (item == null) {
                    throw protocolFailure();
                }
                MemoryFactCandidate candidate = new MemoryFactCandidate(
                        MemoryType.valueOf(requireText(item.memoryType())),
                        requireText(item.predicate()),
                        requireText(item.value()),
                        requireText(item.valueEvidence()),
                        requireText(item.evidenceText()),
                        MemoryStability.valueOf(requireText(item.stability())),
                        MemoryTemporalScope.valueOf(requireText(item.temporalScope())),
                        requireConfidence(item.confidence()));
                candidates.add(candidate);
            }
            MemoryExplicitness explicitness = response.explicitness() == null
                    ? defaultExplicitness(decision)
                    : MemoryExplicitness.valueOf(requireText(response.explicitness()));
            MemoryExtractionDecision validatedDecision =
                    new MemoryExtractionDecision(decision, explicitness, candidates);
            if (candidates.size() <= properties.maxCandidates()) {
                return validatedDecision;
            }
            return new MemoryExtractionDecision(
                    decision,
                    explicitness,
                    candidates.subList(0, properties.maxCandidates()));
        } catch (JsonProcessingException | IllegalArgumentException
                 | NullPointerException exception) {
            throw protocolFailure();
        }
    }

    private static String requireText(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("blank");
        }
        return value;
    }

    private static double requireConfidence(Double confidence) {
        if (confidence == null || !Double.isFinite(confidence)
                || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("invalid confidence");
        }
        return confidence;
    }

    private static MemoryExplicitness defaultExplicitness(MemoryDecision decision) {
        return decision == MemoryDecision.LONG_TERM
                ? MemoryExplicitness.IMPLICIT
                : null;
    }

    private static ImplicitMemoryExtractionException callFailure() {
        return new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_CALL_FAILED,
                "隐式记忆抽取调用失败");
    }

    private static ImplicitMemoryExtractionException protocolFailure() {
        return new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR,
                "隐式记忆抽取响应无效");
    }

    private record SemanticModelResponse(
            String decision,
            String explicitness,
            List<SemanticModelCandidate> candidates) {
    }

    private record SemanticModelCandidate(
            String memoryType,
            String predicate,
            String value,
            String valueEvidence,
            String evidenceText,
            String stability,
            String temporalScope,
            Double confidence) {
    }
}
