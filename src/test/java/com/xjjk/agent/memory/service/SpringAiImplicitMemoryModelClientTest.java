package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryCategory;
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
    void parsesBoundedCandidateArray() {
        SpringAiImplicitMemoryModelClient client = clientReturning("""
                {"candidates":[
                  {"category":"WORK_COMMON_SCOPE","canonicalKey":"work.common_scope","content":"用户常用工作范围是Java开发","evidenceText":"Java开发","confidence":0.93},
                  {"category":"PREFERENCE_ANSWER_STYLE","canonicalKey":"preference.answer_style","content":"用户偏好简洁回答","evidenceText":"回答简短一些","confidence":0.91},
                  {"category":"PREFERENCE_LANGUAGE","canonicalKey":"preference.language","content":"用户偏好使用中文交流","evidenceText":"中文交流","confidence":0.90}
                ]}
                """, properties(Duration.ofSeconds(1), 2), executor());

        var result = client.extract(new ImplicitMemoryModelClient.Request(
                "request-1", "我是Java开发，希望回答简短一些", null));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().category()).isEqualTo(MemoryCategory.WORK_COMMON_SCOPE);
    }

    @Test
    void definesStableWorkScopeWithValidatorCompatibleExample() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec =
                mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"candidates\":[]}");
        SpringAiImplicitMemoryModelClient client = new SpringAiImplicitMemoryModelClient(
                chatClient, properties(Duration.ofSeconds(1), 3), executor(), new ObjectMapper());

        client.extract(new ImplicitMemoryModelClient.Request(
                "request-1", "我平时主要做 Java 开发。", null));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue())
                .contains("WORK_COMMON_SCOPE 表示用户直接明确表达、可长期复用的职业方向、常用技术栈或稳定业务范围")
                .contains("我平时主要做 Java 开发。")
                .contains("\"category\":\"WORK_COMMON_SCOPE\"")
                .contains("\"canonicalKey\":\"work.common_scope\"")
                .contains("\"content\":\"用户常用工作范围是Java开发\"")
                .contains("禁止账号凭据、身份信息、健康信息、订单、退款、物流、支付、客户资料、企业制度、临时任务")
                .contains("只输出 candidates JSON 数组");
    }

    @Test
    void mapsMalformedAndTimeoutToStableCodes() {
        assertCode("not-json", properties(Duration.ofSeconds(1), 3),
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR);

        ChatClient slow = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(slow.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    Thread.sleep(500);
                    return "{\"candidates\":[]}";
                });
        SpringAiImplicitMemoryModelClient client = new SpringAiImplicitMemoryModelClient(
                slow, properties(Duration.ofMillis(20), 3), executor(), new ObjectMapper());
        assertThatThrownBy(() -> extract(client))
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

        assertThatThrownBy(() -> extract(client))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class, error -> {
                    assertThat(error.code())
                            .isEqualTo(ImplicitMemoryExtractionException.Code.MODEL_CALL_FAILED);
                    assertThat(error.getMessage()).doesNotContain("provider secret");
                });
    }

    private void assertCode(String output, ImplicitMemoryProperties properties,
                            ImplicitMemoryExtractionException.Code code) {
        SpringAiImplicitMemoryModelClient client = clientReturning(output, properties, executor());
        assertThatThrownBy(() -> extract(client))
                .isInstanceOfSatisfying(ImplicitMemoryExtractionException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private void extract(SpringAiImplicitMemoryModelClient client) {
        client.extract(new ImplicitMemoryModelClient.Request("request-1", "测试", null));
    }

    private SpringAiImplicitMemoryModelClient clientReturning(
            String output, ImplicitMemoryProperties properties, ExecutorService executor) {
        return new SpringAiImplicitMemoryModelClient(
                chatClientReturning(output), properties, executor, new ObjectMapper());
    }

    private ChatClient chatClientReturning(String output) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(output);
        return client;
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private static ImplicitMemoryProperties properties(Duration timeout, int maxCandidates) {
        return new ImplicitMemoryProperties(
                0.85, 180, maxCandidates, "memory-auto-v1", "qwen-plus", 0.0,
                timeout, new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
