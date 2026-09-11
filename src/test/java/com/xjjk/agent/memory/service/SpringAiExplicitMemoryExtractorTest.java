package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
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
    void mapsStrictJsonAndAssignsRetentionFromCommand() {
        ChatClient client = clientReturning("""
                {"category":"PREFERENCE_ANSWER_STYLE",
                 "canonicalKey":"preference.answer_style",
                 "content":"用户偏好简洁回答",
                 "evidenceText":"以后回答简短一些"}
                """);
        SpringAiExplicitMemoryExtractor extractor = extractor(client, properties(Duration.ofSeconds(1)), executor());

        ExplicitMemoryCandidate result = extractor.extract(
                new ExplicitMemoryCommandDetector.CommandText("叫我石老师", true),
                "请永久记住叫我石老师"
        );

        assertThat(result).isEqualTo(new ExplicitMemoryCandidate(
                MemoryCategory.PREFERENCE_ANSWER_STYLE,
                "preference.answer_style",
                "用户偏好简洁回答",
                "以后回答简短一些",
                MemoryRetentionType.PERMANENT
        ));
    }

    @Test
    void rejectsBlankMalformedAndUnknownCategoryWithStableCode() {
        assertCode(clientReturning(" "), ExplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);
        assertCode(clientReturning("not-json"), ExplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);
        assertCode(clientReturning("""
                {"category":"SECRET","canonicalKey":"x","content":"x","evidenceText":"x"}
                """), ExplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);
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

        assertThatThrownBy(() -> extract(extractor))
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

        assertThatThrownBy(() -> extract(extractor))
                .isInstanceOfSatisfying(ExplicitMemoryExtractionException.class, error -> {
                    assertThat(error.code()).isEqualTo(ExplicitMemoryExtractionException.Code.MODEL_CALL_FAILED);
                    assertThat(error.getMessage()).doesNotContain("secret supplier details");
                });
    }

    private void assertCode(ChatClient client, ExplicitMemoryExtractionException.Code code) {
        SpringAiExplicitMemoryExtractor extractor = extractor(client, properties(Duration.ofSeconds(1)), executor());
        assertThatThrownBy(() -> extract(extractor))
                .isInstanceOfSatisfying(ExplicitMemoryExtractionException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private void extract(SpringAiExplicitMemoryExtractor extractor) {
        extractor.extract(new ExplicitMemoryCommandDetector.CommandText("回答简短", false), "请记住回答简短");
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
                timeout, 1, 10
        );
    }
}
