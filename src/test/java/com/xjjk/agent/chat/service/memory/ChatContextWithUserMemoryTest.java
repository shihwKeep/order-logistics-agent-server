package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.memory.ChatContextBudget;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.service.model.AiModelRuntimeSettings;
import com.xjjk.agent.chat.service.summary.ChatSummaryContextRenderer;
import com.xjjk.agent.chat.service.summary.ChatSummaryProvider;
import com.xjjk.agent.chat.service.summary.ChatSummaryTaskScheduler;
import com.xjjk.agent.chat.config.AiPromptProperties;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.memory.recall.RecalledMemory;
import com.xjjk.agent.memory.recall.UserMemoryContextRenderer;
import com.xjjk.agent.memory.recall.UserMemoryRecallResult;
import com.xjjk.agent.memory.recall.UserMemoryRecallService;
import com.xjjk.agent.memory.recall.UserMemorySystemPromptPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ChatContextWithUserMemoryTest {
    private QwenChatTokenEstimator estimator;
    private AiModelRuntimeSettings runtimeSettings;
    private ChatContextSelector selector;
    private ChatHistorySnapshot history;
    private ChatSummarySnapshot emptySummary;

    @BeforeEach
    void setUp() {
        estimator = mock(QwenChatTokenEstimator.class);
        runtimeSettings = mock(AiModelRuntimeSettings.class);
        selector = new ChatContextSelector(
                estimator, runtimeSettings,
                mock(ChatSummaryContextRenderer.class), mock(ChatSummaryProperties.class));
        history = new ChatHistorySnapshot(
                1L, 10567L, "conversation-1", 0L, 0L, 1L,
                List.of(), false, false);
        emptySummary = ChatSummarySnapshot.empty(new ChatHistoryCursor(
                1L, 10567L, "conversation-1", 0L, 0L, 1L));
        when(estimator.strategyVersion()).thenReturn("test-v1");
        when(estimator.estimate("enhanced-system", List.of(), "current"))
                .thenReturn(20L);
    }

    @Test
    void includesMemoryOnlyFromRemainingBudgetAndKeepsEffectiveSystemPrompt() {
        when(runtimeSettings.calculateBudget(20L))
                .thenReturn(new ChatContextBudget(90L, 20L, 70L));
        when(estimator.estimate(
                "enhanced-system", null, null, "memory-context", List.of(), "current"))
                .thenReturn(50L);

        ChatContextSelection selection = selector.select(
                "enhanced-system", "current", history, emptySummary,
                null, "memory-context");

        assertThat(selection.selectedUserMemoryContext()).isEqualTo("memory-context");
        assertThat(selection.userMemoryEstimatedTokens()).isEqualTo(30L);
        assertThat(selection.effectiveSystemPrompt()).isEqualTo("enhanced-system");
        assertThat(selection.estimatedInputTokens()).isEqualTo(50L);
    }

    @Test
    void dropsMemoryInsteadOfCrossingBudget() {
        when(runtimeSettings.calculateBudget(20L))
                .thenReturn(new ChatContextBudget(50L, 20L, 30L));
        when(estimator.estimate(
                "enhanced-system", null, null, "memory-context", List.of(), "current"))
                .thenReturn(60L);

        ChatContextSelection selection = selector.select(
                "enhanced-system", "current", history, emptySummary,
                null, "memory-context");

        assertThat(selection.selectedUserMemoryContext()).isNull();
        assertThat(selection.userMemoryBudgetTruncated()).isTrue();
        assertThat(selection.estimatedInputTokens()).isEqualTo(20L);
    }

    @Test
    void requestMemoryOrdersSummaryBusinessMemoryThenRawHistory() {
        ChatContextSelection selection = mock(ChatContextSelection.class);
        ChatHistoryTurn turn = new ChatHistoryTurn(
                "request-old", 1L, 2L, "历史问题", "历史回答");
        when(selection.source()).thenReturn(history);
        when(selection.selectedSummary()).thenReturn("会话摘要");
        when(selection.selectedBusinessReference()).thenReturn("订单引用");
        when(selection.selectedUserMemoryContext()).thenReturn("跨会话记忆");
        when(selection.selectedTurns()).thenReturn(List.of(turn));

        assertThat(new RequestChatMemory(selection).get("conversation-1"))
                .satisfiesExactly(
                        message -> assertThat(((UserMessage) message).getText()).isEqualTo("会话摘要"),
                        message -> assertThat(((UserMessage) message).getText()).isEqualTo("订单引用"),
                        message -> assertThat(((UserMessage) message).getText()).isEqualTo("跨会话记忆"),
                        message -> assertThat(((UserMessage) message).getText()).isEqualTo("历史问题"),
                        message -> assertThat(((AssistantMessage) message).getText()).isEqualTo("历史回答"));
    }

    @Test
    void preparationLoadsOwnedMemoryAndUsesEnhancedPromptForSelection() {
        ChatHistorySnapshotProvider snapshots = mock(ChatHistorySnapshotProvider.class);
        ChatSummaryProvider summaries = mock(ChatSummaryProvider.class);
        ChatContextSelector contextSelector = mock(ChatContextSelector.class);
        ChatSummaryTaskScheduler scheduler = mock(ChatSummaryTaskScheduler.class);
        UserMemoryRecallService recall = mock(UserMemoryRecallService.class);
        UserMemoryContextRenderer renderer = mock(UserMemoryContextRenderer.class);
        UserMemorySystemPromptPolicy policy = new UserMemorySystemPromptPolicy();
        ChatTurnContext turn = new ChatTurnContext(
                1L, 10567L, "conversation-1", "request-1",
                "user-message", "assistant-message", "prompt-v1");
        when(snapshots.load(turn)).thenReturn(history);
        when(summaries.load(org.mockito.ArgumentMatchers.any())).thenReturn(emptySummary);
        RecalledMemory remembered = new RecalledMemory(
                "memory-1", 1L, "AUTO_EXTRACT", "WORK_COMMON_SCOPE",
                "work.common_scope.java", "用户主要从事 Java 开发",
                java.math.BigDecimal.ONE, java.time.LocalDateTime.now());
        when(recall.recall(1L, 10567L, "我主要使用什么编程语言？"))
                .thenReturn(new UserMemoryRecallResult(List.of(remembered), true, "OK"));
        when(renderer.render(List.of(remembered))).thenReturn("memory-context");
        String enhanced = policy.enhance("base-system");
        ChatContextSelection selected = mock(ChatContextSelection.class);
        when(selected.hasContextGap()).thenReturn(false);
        when(selected.strategyVersion()).thenReturn("test-v1");
        when(selected.selectedTurns()).thenReturn(List.of());
        when(selected.estimatedInputTokens()).thenReturn(50L);
        when(contextSelector.select(
                enhanced, "我主要使用什么编程语言？", history, emptySummary,
                null, "memory-context")).thenReturn(selected);
        ChatContextPreparationService preparation = new ChatContextPreparationService(
                snapshots, summaries, contextSelector, scheduler,
                new AiPromptProperties("prompt-v1", "base-system"),
                null, null, recall, renderer, policy);

        assertThat(preparation.prepare(
                turn, "我主要使用什么编程语言？", new ChatStreamControl()))
                .isSameAs(selected);
        verify(recall).recall(1L, 10567L, "我主要使用什么编程语言？");
        verify(contextSelector).select(
                enhanced, "我主要使用什么编程语言？", history, emptySummary,
                null, "memory-context");
    }
}
