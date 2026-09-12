package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryDecision;
import com.xjjk.agent.memory.domain.MemoryExplicitness;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiImplicitMemoryModelClientTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void parsesBoundedGeneralSemanticDecision() {
        SpringAiImplicitMemoryModelClient client = clientReturning("""
                {
                  "decision":"LONG_TERM",
                  "explicitness":"IMPLICIT",
                  "candidates":[
                    {"memoryType":"WORK_CONTEXT","predicate":"primary_programming_language","value":"Java","valueEvidence":"Java","evidenceText":"我平时用 Java 语言进行开发","stability":"STABLE","confidence":0.96},
                    {"memoryType":"RESPONSE_PREFERENCE","predicate":"answer_style","value":"简洁","valueEvidence":"简洁","evidenceText":"我喜欢简洁回答","stability":"STABLE","confidence":0.93},
                    {"memoryType":"WORK_CONTEXT","predicate":"occupation","value":"后端开发","valueEvidence":"后端开发","evidenceText":"我是一名后端开发","stability":"STABLE","confidence":0.91}
                  ]
                }
                """, properties(Duration.ofSeconds(1), 2), executor());

        var result = client.analyze(new ImplicitMemoryModelClient.Request(
                "request-1", "我平时用 Java 语言进行开发，也喜欢简洁回答", null));

        assertThat(result.decision()).isEqualTo(MemoryDecision.LONG_TERM);
        assertThat(result.explicitness()).isEqualTo(MemoryExplicitness.IMPLICIT);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().getFirst().memoryType())
                .isEqualTo(MemoryType.WORK_CONTEXT);
        assertThat(result.candidates().getFirst().stability())
                .isEqualTo(MemoryStability.STABLE);
    }

    @Test
    void parsesIgnoreAndSessionOnlyWithoutCandidates() {
        assertThat(clientReturning(
                "{\"decision\":\"IGNORE\",\"candidates\":[]}",
                properties(Duration.ofSeconds(1), 3), executor())
                .analyze(request()).decision()).isEqualTo(MemoryDecision.IGNORE);
        assertThat(clientReturning(
                "{\"decision\":\"SESSION_ONLY\",\"candidates\":[]}",
                properties(Duration.ofSeconds(1), 3), executor())
                .analyze(request()).decision()).isEqualTo(MemoryDecision.SESSION_ONLY);
    }

    @Test
    void promptDefinesSemanticLifecycleInsteadOfFixedPhrases() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec =
                mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"decision\":\"IGNORE\",\"candidates\":[]}");
        SpringAiImplicitMemoryModelClient client = new SpringAiImplicitMemoryModelClient(
                chatClient, properties(Duration.ofSeconds(1), 3), executor(),
                new ObjectMapper());

        client.analyze(new ImplicitMemoryModelClient.Request(
                "request-1", "我平时用 Java 语言进行开发", null));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue())
                .contains("IGNORE、SESSION_ONLY、LONG_TERM")
                .contains("EXPLICIT", "IMPLICIT")
                .contains("memoryType", "predicate", "value", "valueEvidence")
                .contains("evidenceText", "stability", "confidence")
                .contains("一条候选只表达一个原子事实")
                .contains("不要求出现固定触发词")
                .contains("禁止账号凭据、身份信息、健康信息、订单、退款、物流、支付、客户资料、企业制度")
                .doesNotContain("category 只能是 PROFILE_PREFERRED_NAME");
    }

    @Test
    void mapsMalformedUnknownEnumsAndTimeoutToStableCodes() {
        assertCode("not-json", properties(Duration.ofSeconds(1), 3),
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);
        assertCode("{\"decision\":\"FOREVER\",\"candidates\":[]}",
                properties(Duration.ofSeconds(1), 3),
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);

        ChatClient slow = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(slow.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    Thread.sleep(500);
                    return "{\"decision\":\"IGNORE\",\"candidates\":[]}";
                });
        SpringAiImplicitMemoryModelClient client = new SpringAiImplicitMemoryModelClient(
                slow, properties(Duration.ofMillis(20), 3), executor(),
                new ObjectMapper());
        assertThatThrownBy(() -> client.analyze(request()))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(ImplicitMemoryExtractionException.Code.MODEL_TIMEOUT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsSaturatedExecutorWithoutSupplierDetails() {
        ExecutorService rejected = mock(ExecutorService.class);
        when(rejected.submit(any(Callable.class)))
                .thenThrow(new RejectedExecutionException("provider secret"));
        SpringAiImplicitMemoryModelClient client = new SpringAiImplicitMemoryModelClient(
                chatClientReturning("{}"), properties(Duration.ofSeconds(1), 3),
                rejected, new ObjectMapper());

        assertThatThrownBy(() -> client.analyze(request()))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class, error -> {
                    assertThat(error.code())
                            .isEqualTo(ImplicitMemoryExtractionException.Code.MODEL_CALL_FAILED);
                    assertThat(error.getMessage()).doesNotContain("provider secret");
                });
    }

    private void assertCode(
            String output,
            ImplicitMemoryProperties properties,
            ImplicitMemoryExtractionException.Code code) {
        SpringAiImplicitMemoryModelClient client =
                clientReturning(output, properties, executor());
        assertThatThrownBy(() -> client.analyze(request()))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private ImplicitMemoryModelClient.Request request() {
        return new ImplicitMemoryModelClient.Request("request-1", "测试", null);
    }

    private SpringAiImplicitMemoryModelClient clientReturning(
            String output,
            ImplicitMemoryProperties properties,
            ExecutorService executor) {
        return new SpringAiImplicitMemoryModelClient(
                chatClientReturning(output), properties, executor, new ObjectMapper());
    }

    private ChatClient chatClientReturning(String output) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(output);
        return client;
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private static ImplicitMemoryProperties properties(
            Duration timeout,
            int maxCandidates) {
        return new ImplicitMemoryProperties(
                0.85, 180, maxCandidates, "memory-semantic-v2", "qwen-plus", 0.0,
                timeout, new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
