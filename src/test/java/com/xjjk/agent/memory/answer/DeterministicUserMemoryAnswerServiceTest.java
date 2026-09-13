package com.xjjk.agent.memory.answer;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.domain.MemoryCategory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
        when(recallService.recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(new UserMemoryRecallResult(
                        List.of(javaMemory), false, "MYSQL_CATEGORY",
                        UserMemoryRecallStatus.AVAILABLE));
        when(renderer.render(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE, javaMemory))
                .thenReturn(Optional.of(
                        "根据您之前提供的信息，您平时主要使用 Java。"));

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-1");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.ANSWERED);
        assertThat(result.assistantText()).contains("Java");
        verify(metrics).directAnswer("PROGRAMMING_LANGUAGE", "ANSWERED");
        verify(recallService).recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE);
        verify(recallService, never()).recall(any(), anyString());
    }

    @Test
    void answersCurrentEmployerUsingOnlyTheEmployerPredicate() {
        String query = "我在哪里工作？";
        RecalledMemory employer = new RecalledMemory(
                "memory-employer", 1L, "USER_EXPLICIT", "WORK_COMMON_SCOPE",
                "work.current_employer", "用户当前工作单位是享佳",
                new BigDecimal("0.98"), LocalDateTime.parse("2026-09-13T08:00:00"),
                2, "WORK_CONTEXT", "current_employer", "\"享佳\"",
                "STABLE", "EXPLICIT_SEMANTIC");
        when(classifier.classify(query))
                .thenReturn(Optional.of(DirectMemoryQuestionType.CURRENT_EMPLOYER));
        when(recallService.recallByPredicates(
                IDENTITY, List.of("current_employer"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(new UserMemoryRecallResult(
                        List.of(employer), false, "MYSQL_CATEGORY",
                        UserMemoryRecallStatus.AVAILABLE));
        when(renderer.render(DirectMemoryQuestionType.CURRENT_EMPLOYER, employer))
                .thenReturn(Optional.of(
                        "根据您之前提供的信息，您当前工作单位是享佳。"));

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, query, "request-employer");

        assertThat(result.outcome())
                .isEqualTo(DeterministicUserMemoryAnswerResult.Outcome.ANSWERED);
        assertThat(result.assistantText()).contains("享佳");
        verify(metrics).directAnswer("CURRENT_EMPLOYER", "ANSWERED");
        verify(recallService).recallByPredicates(
                IDENTITY, List.of("current_employer"),
                MemoryCategory.WORK_COMMON_SCOPE);
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
    void fallsThroughWhenLongTermMemoryIsOff() {
        classified();
        when(recallService.recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(UserMemoryRecallResult.disabled());

        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-disabled");

        assertThat(result).isEqualTo(DeterministicUserMemoryAnswerResult.notHandled());
        verify(metrics).directAnswer("PROGRAMMING_LANGUAGE", "FALLTHROUGH");
        verifyNoInteractions(renderer);
    }

    @Test
    void fallsThroughAfterSuccessfulEmptyRecall() {
        classified();
        when(recallService.recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(new UserMemoryRecallResult(
                        List.of(), false, "MYSQL_CATEGORY",
                        UserMemoryRecallStatus.AVAILABLE));
        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-empty");

        assertThat(result).isEqualTo(DeterministicUserMemoryAnswerResult.notHandled());
        verify(metrics).directAnswer("PROGRAMMING_LANGUAGE", "FALLTHROUGH");
        verifyNoInteractions(renderer);
    }

    @Test
    void fallsThroughWhenMemoryHasNeverBeenInitialized() {
        classified();
        when(recallService.recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(UserMemoryRecallResult.notInitialized());
        DeterministicUserMemoryAnswerResult result = service.answer(
                IDENTITY, QUERY, "request-new-user");

        assertThat(result).isEqualTo(DeterministicUserMemoryAnswerResult.notHandled());
        verify(metrics).directAnswer("PROGRAMMING_LANGUAGE", "FALLTHROUGH");
        verifyNoInteractions(renderer);
    }

    @Test
    void reportsUnavailableForMysqlFailure() {
        classified();
        when(recallService.recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(UserMemoryRecallResult.unavailable());

        assertThat(service.answer(IDENTITY, QUERY, "request-mysql").assistantText())
                .isEqualTo("记忆服务暂时不可用，请稍后重试。");
        verify(metrics)
                .directAnswer("PROGRAMMING_LANGUAGE", "UNAVAILABLE");
    }

    @Test
    void fallsThroughWhenRecalledCategoryCannotRenderAnAnswer() {
        RecalledMemory broadScope = memory(
                "WORK_COMMON_SCOPE", "用户常用工作范围是后端开发");
        classified();
        when(recallService.recallByPredicates(
                IDENTITY, List.of("primary_programming_language"),
                MemoryCategory.WORK_COMMON_SCOPE))
                .thenReturn(new UserMemoryRecallResult(
                        List.of(broadScope), false, "MYSQL_CATEGORY",
                        UserMemoryRecallStatus.AVAILABLE));
        when(renderer.render(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE, broadScope))
                .thenReturn(Optional.empty());
        assertThat(service.answer(IDENTITY, QUERY, "request-broad"))
                .isEqualTo(DeterministicUserMemoryAnswerResult.notHandled());
        verify(metrics).directAnswer("PROGRAMMING_LANGUAGE", "FALLTHROUGH");
    }

    private void classified() {
        when(classifier.classify(QUERY))
                .thenReturn(Optional.of(DirectMemoryQuestionType.PROGRAMMING_LANGUAGE));
    }

    private RecalledMemory memory(String category, String content) {
        return new RecalledMemory(
                "memory-1", 1L, "AUTO_EXTRACT", category,
                "work.common_scope", content, new BigDecimal("0.95"),
                LocalDateTime.parse("2026-09-12T08:00:00"));
    }
}
