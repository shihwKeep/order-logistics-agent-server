package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import com.xjjk.agent.prompt.StrictPromptTemplateRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 使用独立模型调用核验开放候选是否被当前用户原文直接蕴含。 */
@Component
public class SpringAiMemoryEvidenceVerifier implements MemoryEvidenceVerifier {

    private final ChatClient chatClient;
    private final ImplicitMemoryProperties properties;
    private final ExecutorService modelExecutor;
    private final ObjectMapper objectMapper;
    private final AgentPromptCatalogProperties promptCatalog;
    private final StrictPromptTemplateRenderer promptRenderer;

    public SpringAiMemoryEvidenceVerifier(
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
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.promptCatalog = Objects.requireNonNull(promptCatalog, "promptCatalog");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
    }

    @Override
    public List<Result> verify(Request request) {
        Objects.requireNonNull(request, "request");
        Future<List<Result>> future;
        try {
            future = modelExecutor.submit(() -> invoke(request));
        } catch (RejectedExecutionException exception) {
            throw callFailure();
        }
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ImplicitMemoryExtractionException(
                    ImplicitMemoryExtractionException.Code.MODEL_TIMEOUT,
                    "记忆证据核验超时");
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

    private List<Result> invoke(Request request) {
        String input;
        try {
            input = objectMapper.writeValueAsString(new VerificationInput(
                    properties.promptVersion() + "-evidence-v1",
                    request.sourceMessage(), request.candidates().stream()
                    .map(SpringAiMemoryEvidenceVerifier::toInput)
                    .toList()));
        } catch (JsonProcessingException exception) {
            throw protocolFailure();
        }
        final String output;
        try {
            output = chatClient.prompt()
                    .system(promptCatalog.memory().evidence().system())
                    .user(promptRenderer.render(
                            "agent.ai.prompt.catalog.memory.evidence.user-template",
                            promptCatalog.memory().evidence().userTemplate(),
                            Map.of("inputJson", input)))
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            throw callFailure();
        }
        if (!StringUtils.hasText(output)) {
            throw protocolFailure();
        }
        try {
            VerificationResponse response = objectMapper.readValue(
                    output, VerificationResponse.class);
            if (response.results() == null
                    || response.results().size() != request.candidates().size()) {
                throw protocolFailure();
            }
            Set<String> expected = request.candidates().stream()
                    .map(Candidate::candidateId).collect(java.util.stream.Collectors.toSet());
            Set<String> seen = new HashSet<>();
            List<Result> results = new ArrayList<>();
            for (VerificationResult item : response.results()) {
                if (item == null || !StringUtils.hasText(item.candidateId())
                        || !expected.contains(item.candidateId())
                        || !seen.add(item.candidateId())) {
                    throw protocolFailure();
                }
                results.add(new Result(item.candidateId(),
                        Outcome.valueOf(requireText(item.outcome()))));
            }
            return List.copyOf(results);
        } catch (JsonProcessingException | IllegalArgumentException
                 | NullPointerException exception) {
            throw protocolFailure();
        }
    }

    private static VerificationCandidate toInput(Candidate item) {
        MemoryFactCandidate candidate = item.fact().candidate();
        return new VerificationCandidate(item.candidateId(),
                candidate.memoryType().name(), candidate.predicate(), candidate.value(),
                candidate.valueEvidence(), candidate.evidenceText(),
                item.stability().name(), item.temporalScope().name());
    }

    private static String requireText(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("blank");
        }
        return value;
    }

    private static ImplicitMemoryExtractionException callFailure() {
        return new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_CALL_FAILED,
                "记忆证据核验调用失败");
    }

    private static ImplicitMemoryExtractionException protocolFailure() {
        return new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR,
                "记忆证据核验响应无效");
    }

    private record VerificationInput(
            String promptVersion,
            String sourceMessage,
            List<VerificationCandidate> candidates) {
    }

    private record VerificationCandidate(
            String candidateId,
            String memoryType,
            String predicate,
            String value,
            String valueEvidence,
            String evidenceText,
            String stability,
            String temporalScope) {
    }

    private record VerificationResponse(List<VerificationResult> results) {
    }

    private record VerificationResult(String candidateId, String outcome) {
    }
}
