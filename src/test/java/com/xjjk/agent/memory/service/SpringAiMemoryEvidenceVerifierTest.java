package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import com.xjjk.agent.memory.domain.ValidatedMemoryFact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.xjjk.agent.prompt.PromptCatalogTestFixture.catalog;

class SpringAiMemoryEvidenceVerifierTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void returnsBoundedOutcomesWithoutRewritingCandidates() {
        SpringAiMemoryEvidenceVerifier verifier = verifierReturning("""
                {"results":[
                  {"candidateId":"candidate-0","outcome":"SUPPORTED"},
                  {"candidateId":"candidate-1","outcome":"CONTRADICTED"},
                  {"candidateId":"candidate-2","outcome":"UNCERTAIN"}
                ]}
                """);
        List<MemoryEvidenceVerifier.Candidate> candidates = List.of(
                item("candidate-0", "做后端开发"),
                item("candidate-1", "喜欢表格"),
                item("candidate-2", "经常出差"));

        List<MemoryEvidenceVerifier.Result> results = verifier.verify(
                new MemoryEvidenceVerifier.Request("request-1", "我长期做后端开发，也喜欢表格", candidates));

        assertThat(results).extracting(MemoryEvidenceVerifier.Result::outcome)
                .containsExactly(
                        MemoryEvidenceVerifier.Outcome.SUPPORTED,
                        MemoryEvidenceVerifier.Outcome.CONTRADICTED,
                        MemoryEvidenceVerifier.Outcome.UNCERTAIN);
    }

    @Test
    void promptRequiresIndependentEntailmentAndExactIds() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"results\":[{\"candidateId\":\"candidate-0\",\"outcome\":\"SUPPORTED\"}]}");
        SpringAiMemoryEvidenceVerifier verifier = new SpringAiMemoryEvidenceVerifier(
                client, properties(Duration.ofSeconds(1)), executor(), new ObjectMapper(),
                catalog(), new com.xjjk.agent.prompt.StrictPromptTemplateRenderer());

        verifier.verify(new MemoryEvidenceVerifier.Request(
                "request-1", "我以前做后端开发", List.of(item(
                        "candidate-0", "做后端开发",
                        MemoryStability.TIME_BOUND, MemoryTemporalScope.HISTORICAL))));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userInput = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(prompt.capture());
        verify(requestSpec).user(userInput.capture());
        assertThat(prompt.getValue()).isEqualTo("EVIDENCE_SYSTEM");
        assertThat(userInput.getValue())
                .startsWith("input=")
                .contains("\"stability\":\"TIME_BOUND\"")
                .contains("\"temporalScope\":\"HISTORICAL\"");
    }

    @Test
    void rejectsMissingDuplicateAndUnknownIdsAsProtocolErrors() {
        assertProtocol("{\"results\":[]}");
        assertProtocol("{\"results\":["
                + "{\"candidateId\":\"candidate-0\",\"outcome\":\"SUPPORTED\"},"
                + "{\"candidateId\":\"candidate-0\",\"outcome\":\"SUPPORTED\"}]}");
        assertProtocol("{\"results\":[{\"candidateId\":\"other\",\"outcome\":\"SUPPORTED\"}]}");
        assertProtocol("{\"results\":[{\"candidateId\":\"candidate-0\",\"outcome\":\"MAYBE\"}]}");
    }

    @Test
    void mapsTimeoutToRetryableModelTimeout() {
        ChatClient slow = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(slow.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    Thread.sleep(500);
                    return "{\"results\":[]}";
                });
        SpringAiMemoryEvidenceVerifier verifier = new SpringAiMemoryEvidenceVerifier(
                slow, properties(Duration.ofMillis(20)), executor(), new ObjectMapper(),
                catalog(), new com.xjjk.agent.prompt.StrictPromptTemplateRenderer());

        assertThatThrownBy(() -> verifier.verify(new MemoryEvidenceVerifier.Request(
                "request-1", "我长期做后端开发", List.of(item("candidate-0", "做后端开发")))))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                ImplicitMemoryExtractionException.Code.MODEL_TIMEOUT));
    }

    private void assertProtocol(String output) {
        assertThatThrownBy(() -> verifierReturning(output).verify(
                new MemoryEvidenceVerifier.Request(
                        "request-1", "我长期做后端开发",
                        List.of(item("candidate-0", "做后端开发")))))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR));
    }

    private SpringAiMemoryEvidenceVerifier verifierReturning(String output) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(output);
        return new SpringAiMemoryEvidenceVerifier(
                client, properties(Duration.ofSeconds(1)), executor(), new ObjectMapper(),
                catalog(), new com.xjjk.agent.prompt.StrictPromptTemplateRenderer());
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private static MemoryEvidenceVerifier.Candidate item(String id, String value) {
        return item(id, value, MemoryStability.STABLE, MemoryTemporalScope.CURRENT);
    }

    private static MemoryEvidenceVerifier.Candidate item(
            String id,
            String value,
            MemoryStability stability,
            MemoryTemporalScope temporalScope) {
        MemoryFactCandidate candidate = new MemoryFactCandidate(
                MemoryType.STABLE_USER_FACT, "user_fact", value, value, value,
                stability, temporalScope, 0.93);
        return new MemoryEvidenceVerifier.Candidate(id, new ValidatedMemoryFact(
                candidate, "fact.stable_user_fact." + id, "用户的稳定信息是" + value,
                "\"" + value + "\"", "STABLE_USER_FACT", "SEMANTIC_REQUIRED"));
    }

    private static ImplicitMemoryProperties properties(Duration timeout) {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-semantic-v2", "qwen-plus", 0.0,
                timeout, new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
