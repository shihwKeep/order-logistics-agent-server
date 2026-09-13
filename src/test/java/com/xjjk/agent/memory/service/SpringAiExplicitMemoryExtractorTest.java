package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
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

class SpringAiExplicitMemoryExtractorTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void mapsGenericSemanticSaveActionAndModelSuppliedRetention() {
        SpringAiExplicitMemoryExtractor extractor = extractor(clientReturning("""
                {"action":"SAVE","memoryType":"WORK_CONTEXT",
                 "predicate":"technology_stack","value":"Spring AI",
                 "valueEvidence":"Spring AI","evidenceText":"以后记着我长期使用 Spring AI",
                 "stability":"STABLE","temporalScope":"CURRENT","retention":"NORMAL","confidence":0.98}
                """), properties(Duration.ofSeconds(1)), executor());

        ExplicitMemoryResolution result = extractor.resolve("以后记着我长期使用 Spring AI");

        assertThat(result.action()).isEqualTo(ExplicitMemoryResolution.Action.SAVE);
        assertThat(result.candidate().retentionType()).isEqualTo(MemoryRetentionType.NORMAL);
        assertThat(result.candidate().semanticFact()).isEqualTo(new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT, "technology_stack", "Spring AI", "Spring AI",
                "以后记着我长期使用 Spring AI", MemoryStability.STABLE, 0.98));
    }

    @Test
    void mapsExplicitCurrentAgeAsTimeBoundSave() {
        SpringAiExplicitMemoryExtractor extractor = extractor(clientReturning("""
                {"action":"SAVE","memoryType":"PROFILE","predicate":"age","value":"32",
                 "valueEvidence":"32岁","evidenceText":"请记住我今年32岁",
                 "stability":"TIME_BOUND","temporalScope":"CURRENT","retention":"NORMAL","confidence":0.98}
                """), properties(Duration.ofSeconds(1)), executor());

        MemoryFactCandidate fact = extractor.resolve("请记住我今年32岁")
                .candidate().semanticFact();

        assertThat(fact.predicate()).isEqualTo("age");
        assertThat(fact.stability()).isEqualTo(MemoryStability.TIME_BOUND);
        assertThat(fact.temporalScope()).isEqualTo(MemoryTemporalScope.CURRENT);
    }

    @Test
    void mapsExplicitHistoricalOccupationIndependentlyFromRetention() {
        SpringAiExplicitMemoryExtractor extractor = extractor(clientReturning("""
                {"action":"SAVE","memoryType":"WORK_CONTEXT","predicate":"occupation","value":"Java开发",
                 "valueEvidence":"Java开发","evidenceText":"请记住我以前是Java开发",
                 "stability":"TIME_BOUND","temporalScope":"HISTORICAL","retention":"NORMAL","confidence":0.98}
                """), properties(Duration.ofSeconds(1)), executor());

        ExplicitMemoryResolution result = extractor.resolve("请记住我以前是Java开发");

        assertThat(result.candidate().retentionType()).isEqualTo(MemoryRetentionType.NORMAL);
        assertThat(result.candidate().semanticFact().temporalScope())
                .isEqualTo(MemoryTemporalScope.HISTORICAL);
    }

    @Test
    void rejectsSaveWithMissingOrInvalidTemporalScope() {
        assertProtocolFailure("""
                {"action":"SAVE","memoryType":"PROFILE","predicate":"age","value":"32",
                 "valueEvidence":"32岁","evidenceText":"请记住我今年32岁",
                 "stability":"TIME_BOUND","retention":"NORMAL","confidence":0.98}
                """);
        assertProtocolFailure("""
                {"action":"SAVE","memoryType":"PROFILE","predicate":"age","value":"32",
                 "valueEvidence":"32岁","evidenceText":"请记住我今年32岁",
                 "stability":"TIME_BOUND","temporalScope":"RECENT","retention":"NORMAL","confidence":0.98}
                """);
    }

    @Test
    void rejectsTrailingProseAndSecondJsonValue() {
        assertProtocolFailure("{\"action\":\"NONE\",\"confidence\":0.99} trailing prose");
        assertProtocolFailure(
                "{\"action\":\"NONE\",\"confidence\":0.99} {\"ignored\":true}");
    }

    @Test
    void promptUsesGeneralFactSchemaAndDoesNotTrustModelCanonicalContent() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"action\":\"NONE\",\"confidence\":0.99}");

        extractor(client, properties(Duration.ofSeconds(1)), executor()).resolve("测试");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(prompt.capture());
        assertThat(prompt.getValue())
                .contains("memoryType", "predicate", "value", "valueEvidence", "stability", "temporalScope")
                .contains("不要求固定触发词")
                .contains("年龄", "职业", "工作单位", "技能", "技术栈", "沟通", "回答偏好", "称呼")
                .contains("STABLE", "TIME_BOUND", "TEMPORARY", "UNKNOWN")
                .contains("CURRENT", "HISTORICAL")
                .contains("只有用户明确要求永久保存时才用 PERMANENT")
                .contains("高风险身份凭证", "联系方式", "账户", "健康", "精确地址", "客户业务记录")
                .contains("普通自述的年龄、职业和偏好可以进入候选")
                .doesNotContain("禁止身份信息")
                .contains("服务端生成")
                .doesNotContain("category 只能是");
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
