package com.xjjk.agent.memory.answer;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import com.xjjk.agent.memory.recall.RecalledMemory;
import com.xjjk.agent.memory.recall.UserMemoryRecallResult;
import com.xjjk.agent.memory.recall.UserMemoryRecallService;
import com.xjjk.agent.memory.recall.UserMemoryRecallStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeterministicUserMemoryAnswerServiceTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(9L, "account", "name", 2L, 7L);
    private static final String QUERY = "我平时主要使用什么编程语言？";

    @Mock
    private DirectMemoryQuestionClassifier classifier;
    @Mock
    private UserMemoryRecallService recallService;
    @Mock
    private DeterministicMemoryAnswerRenderer renderer;
    @Mock
    private UserMemoryMetrics metrics;
    @InjectMocks
    private DeterministicUserMemoryAnswerService service;

    @Test
    void answersFromValidatedMatchingMemory() {
        RecalledMemory javaMemory = memory(
                "WORK_COMMON_SCOPE", "用户常用工作范围是Java开发");
        when(classifier.classify(QUERY))
                .thenReturn(Optional.of(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE));
        when(recallService.recall(IDENTITY, QUERY)).thenReturn(new UserMemoryRecallResult(
                List.of(javaMemory), true, "OK", UserMemoryRecallStatus.AVAILABLE));
        when(renderer.render(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE, javaMemory))
                .thenReturn(Optional.of(
                        "根据您之前提供的信息，您平时主要使用 Java。"));

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-1");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.ANSWERED);
        assertThat(result.assistantText()).contains("Java");
        verify(metrics).directAnswer("PROGRAMMING_LANGUAGE", "ANSWERED");
    }

    @Test
    void bypassesRecallWhenClassifierDoesNotMatch() {
        when(classifier.classify("你好")).thenReturn(Optional.empty());

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, "你好", "request-bypass");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.NOT_HANDLED);
        verifyNoInteractions(recallService, renderer, metrics);
    }

    @Test
    void reportsDisabledWhenLongTermMemoryIsOff() {
        classified();
        when(recallService.recall(IDENTITY, QUERY))
                .thenReturn(UserMemoryRecallResult.disabled());

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-disabled");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.DISABLED);
        assertThat(result.assistantText()).isEqualTo("长期记忆已关闭，暂时无法回答。");
    }

    @Test
    void reportsNotRememberedOnlyAfterSuccessfulEmptyRecall() {
        classified();
        when(recallService.recall(IDENTITY, QUERY)).thenReturn(new UserMemoryRecallResult(
                List.of(), true, "NO_CANDIDATE", UserMemoryRecallStatus.AVAILABLE));
        notRemembered();

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-empty");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.NOT_REMEMBERED);
        assertThat(result.assistantText())
                .isEqualTo("我还没有记住您常用的编程语言。");
    }

    @Test
    void reportsNotRememberedWhenMemoryHasNeverBeenInitialized() {
        classified();
        when(recallService.recall(IDENTITY, QUERY))
                .thenReturn(UserMemoryRecallResult.notInitialized());
        notRemembered();

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-new-user");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.NOT_REMEMBERED);
    }

    @Test
    void reportsUnavailableForMysqlOrSemanticRecallFailureOrDisabledIndex() {
        classified();
        when(recallService.recall(IDENTITY, QUERY))
                .thenReturn(UserMemoryRecallResult.unavailable())
                .thenReturn(new UserMemoryRecallResult(
                        List.of(), true, "UNAVAILABLE", UserMemoryRecallStatus.AVAILABLE))
                .thenReturn(new UserMemoryRecallResult(
                        List.of(), true, "DISABLED", UserMemoryRecallStatus.AVAILABLE));

        assertThat(service.answer(IDENTITY, QUERY, "request-mysql").assistantText())
                .isEqualTo("记忆服务暂时不可用，请稍后重试。");
        assertThat(service.answer(IDENTITY, QUERY, "request-index").assistantText())
                .isEqualTo("记忆服务暂时不可用，请稍后重试。");
        assertThat(service.answer(IDENTITY, QUERY, "request-index-disabled").assistantText())
                .isEqualTo("记忆服务暂时不可用，请稍后重试。");
        verify(metrics, times(3))
                .directAnswer("PROGRAMMING_LANGUAGE", "UNAVAILABLE");
    }

    @Test
    void rejectsUnrenderableCategoryInsteadOfInferringAnAnswer() {
        RecalledMemory broadScope = memory(
                "WORK_COMMON_SCOPE", "用户常用工作范围是后端开发");
        classified();
        when(recallService.recall(IDENTITY, QUERY)).thenReturn(new UserMemoryRecallResult(
                List.of(broadScope), true, "OK", UserMemoryRecallStatus.AVAILABLE));
        when(renderer.render(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE, broadScope))
                .thenReturn(Optional.empty());
        notRemembered();

        assertThat(service.answer(IDENTITY, QUERY, "request-broad").outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.NOT_REMEMBERED);
    }

    @Test
    void reportsUnavailableWhenRecallGateUnexpectedlySkipsDirectQuestion() {
        classified();
        when(recallService.recall(IDENTITY, QUERY)).thenReturn(new UserMemoryRecallResult(
                List.of(), false, "SKIPPED", UserMemoryRecallStatus.AVAILABLE));

        assertThat(service.answer(IDENTITY, QUERY, "request-skipped").outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.UNAVAILABLE);
    }

    private void classified() {
        when(classifier.classify(QUERY))
                .thenReturn(Optional.of(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE));
    }

    private void notRemembered() {
        when(renderer.notRemembered(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE))
                .thenReturn("我还没有记住您常用的编程语言。");
    }

    private RecalledMemory memory(String category, String content) {
        return new RecalledMemory(
                "memory-1", 1L, "AUTO_EXTRACT", category,
                "work.common_scope", content, new BigDecimal("0.95"),
                LocalDateTime.parse("2026-09-12T08:00:00"));
    }
}
