package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiExplicitMemoryExtractorTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void mapsSaveActionAndModelSuppliedRetention() {
        SpringAiExplicitMemoryExtractor extractor = extractor(clientReturning("""
                {"action":"SAVE","category":"PROFILE_PREFERRED_NAME",
                 "canonicalKey":"profile.preferred_name","content":"用户希望被称为石海文",
                 "evidenceText":"你以后都叫我石海文","retention":"NORMAL","confidence":0.98}
                """), properties(Duration.ofSeconds(1)), executor());

        ExplicitMemoryResolution result = extractor.resolve("你以后都叫我石海文");

        assertThat(result).isEqualTo(ExplicitMemoryResolution.save(
                new ExplicitMemoryCandidate(
                        MemoryCategory.PROFILE_PREFERRED_NAME,
                        "profile.preferred_name",
                        "用户希望被称为石海文",
                        "你以后都叫我石海文",
                        MemoryRetentionType.NORMAL),
                ExplicitMemoryResolution.Path.SEMANTIC_PATH,
                0.98));
    }

    @Test
    void mapsClarifyWithoutConstructingCandidate() {
        SpringAiExplicitMemoryExtractor extractor = extractor(
                clientReturning("{\"action\":\"CLARIFY\",\"confidence\":0.72}"),
                properties(Duration.ofSeconds(1)), executor());

        assertThat(extractor.resolve("以后这样就行"))
                .isEqualTo(ExplicitMemoryResolution.clarify(
                        ExplicitMemoryResolution.Path.SEMANTIC_PATH));
    }

    @Test
    void mapsNoneWithoutConstructingCandidate() {
        SpringAiExplicitMemoryExtractor extractor = extractor(
                clientReturning("{\"action\":\"NONE\",\"confidence\":0.99}"),
                properties(Duration.ofSeconds(1)), executor());

        assertThat(extractor.resolve("帮我查询订单"))
                .isEqualTo(ExplicitMemoryResolution.none());
    }

    @Test
    void rejectsMalformedOrInvalidStructuredOutputWithStableCode() {
        List<String> outputs = List.of(
                " ",
                "secret malformed model output",
                "{\"action\":\"MAYBE\",\"confidence\":0.9}",
                "{\"action\":\"SAVE\",\"category\":\"SECRET\",\"canonicalKey\":\"x\",\"content\":\"x\",\"evidenceText\":\"x\",\"retention\":\"NORMAL\",\"confidence\":0.9}",
                "{\"action\":\"SAVE\",\"category\":\"PROFILE_PREFERRED_NAME\",\"canonicalKey\":\"profile.preferred_name\",\"content\":\"用户希望被称为石海文\",\"retention\":\"NORMAL\",\"confidence\":0.9}",
                "{\"action\":\"SAVE\",\"category\":\"PROFILE_PREFERRED_NAME\",\"canonicalKey\":\"profile.preferred_name\",\"content\":\"用户希望被称为石海文\",\"evidenceText\":\"你以后都叫我石海文\",\"retention\":\"FOREVER\",\"confidence\":0.9}",
                "{\"action\":\"SAVE\",\"category\":\"PROFILE_PREFERRED_NAME\",\"canonicalKey\":\"profile.preferred_name\",\"content\":\"用户希望被称为石海文\",\"evidenceText\":\"你以后都叫我石海文\",\"retention\":\"NORMAL\",\"confidence\":1.1}"
        );

        for (String output : outputs) {
            assertProtocolFailure(output);
        }
    }

    @Test
    void timesOutAndCancelsSlowModelCall() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    Thread.sleep(500);
                    return "{}";
                });
        SpringAiExplicitMemoryExtractor extractor = extractor(
                client, properties(Duration.ofMillis(20)), executor());

        assertThatThrownBy(() -> extractor.resolve("你以后都叫我石海文"))
                .isInstanceOfSatisfying(ExplicitMemoryExtractionException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(ExplicitMemoryExtractionException.Code.MODEL_TIMEOUT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsSaturatedExecutorWithoutLeakingSupplierMessage() {
        ExecutorService rejected = mock(ExecutorService.class);
        when(rejected.submit(any(Callable.class)))
                .thenThrow(new RejectedExecutionException("secret supplier details"));
        SpringAiExplicitMemoryExtractor extractor = extractor(
                clientReturning("{}"), properties(Duration.ofSeconds(1)), rejected);

        assertThatThrownBy(() -> extractor.resolve("你以后都叫我石海文"))
                .isInstanceOfSatisfying(ExplicitMemoryExtractionException.class, error -> {
                    assertThat(error.code()).isEqualTo(
                            ExplicitMemoryExtractionException.Code.MODEL_CALL_FAILED);
                    assertThat(error.getMessage()).doesNotContain("secret supplier details");
                });
    }

    private void assertProtocolFailure(String output) {
        SpringAiExplicitMemoryExtractor extractor = extractor(
                clientReturning(output), properties(Duration.ofSeconds(1)), executor());
        assertThatThrownBy(() -> extractor.resolve("你以后都叫我石海文"))
                .isInstanceOfSatisfying(ExplicitMemoryExtractionException.class, error -> {
                    assertThat(error.code()).isEqualTo(
                            ExplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);
                    assertThat(error.getMessage()).isEqualTo("显式记忆抽取响应无效");
                    if (!output.isBlank()) {
                        assertThat(error.getMessage()).doesNotContain(output.strip());
                    }
                });
    }

    private ChatClient clientReturning(String output) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(output);
        return client;
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private SpringAiExplicitMemoryExtractor extractor(
            ChatClient client, UserMemoryProperties properties, ExecutorService executor) {
        return new SpringAiExplicitMemoryExtractor(client, properties, executor, new ObjectMapper());
    }

    private UserMemoryProperties properties(Duration timeout) {
        return new UserMemoryProperties(
                true, true, 256, 512, 512, 50, 365,
                "memory-explicit-test-v1", "qwen-plus", 0.1,
                timeout, 1, 10);
    }
}
