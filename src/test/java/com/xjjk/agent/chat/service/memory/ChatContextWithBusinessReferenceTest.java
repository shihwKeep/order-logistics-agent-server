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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ChatContextWithBusinessReferenceTest {

    @Mock
    private QwenChatTokenEstimator estimator;
    @Mock
    private AiModelRuntimeSettings runtimeSettings;
    @Mock
    private ChatSummaryContextRenderer summaryRenderer;
    @Mock
    private ChatSummaryProperties summaryProperties;

    private ChatContextSelector selector;
    private ChatHistorySnapshot history;
    private ChatSummarySnapshot emptySummary;

    @BeforeEach
    void setUp() {
        selector = new ChatContextSelector(
                estimator, runtimeSettings, summaryRenderer, summaryProperties);
        history = new ChatHistorySnapshot(
                1L, 10567L, "conversation-1", 0L, 0L, 1L,
                List.of(), false, false);
        emptySummary = ChatSummarySnapshot.empty(new ChatHistoryCursor(
                1L, 10567L, "conversation-1", 0L, 0L, 1L));
    }

    @Test
    void includesReferenceOnlyWhenCompleteInputStillFitsBudget() {
        stubFixedEstimate();
        when(runtimeSettings.calculateBudget(20L))
                .thenReturn(new ChatContextBudget(90L, 20L, 70L));
        when(estimator.estimate(
                "system", null, "business-reference", List.of(), "它到哪了"))
                .thenReturn(55L);

        ChatContextSelection selection = selector.select(
                "system", "它到哪了", history, emptySummary, "business-reference");

        assertThat(selection.hasBusinessReference()).isTrue();
        assertThat(selection.selectedBusinessReference()).isEqualTo("business-reference");
        assertThat(selection.estimatedInputTokens()).isEqualTo(55L);
        assertThat(new RequestChatMemory(selection).get("conversation-1"))
                .singleElement()
                .isInstanceOf(UserMessage.class)
                .extracting(message -> ((UserMessage) message).getText())
                .isEqualTo("business-reference");
    }

    @Test
    void dropsReferenceInsteadOfCrossingInputBudget() {
        stubFixedEstimate();
        when(runtimeSettings.calculateBudget(20L))
                .thenReturn(new ChatContextBudget(50L, 20L, 30L));
        when(estimator.estimate(
                "system", null, "business-reference", List.of(), "它到哪了"))
                .thenReturn(55L);

        ChatContextSelection selection = selector.select(
                "system", "它到哪了", history, emptySummary, "business-reference");

        assertThat(selection.hasBusinessReference()).isFalse();
        assertThat(selection.selectedBusinessReference()).isNull();
        assertThat(selection.businessReferenceBudgetTruncated()).isTrue();
        assertThat(selection.estimatedInputTokens()).isEqualTo(20L);
    }

    @Test
    void requestMemoryKeepsSummaryReferenceAndRawHistoryInFixedOrder() {
        ChatContextSelection selection = mock(ChatContextSelection.class);
        ChatHistoryTurn turn = new ChatHistoryTurn(
                "request-old", 1L, 2L, "历史问题", "历史回答");
        when(selection.source()).thenReturn(history);
        when(selection.selectedSummary()).thenReturn("会话摘要");
        when(selection.selectedBusinessReference()).thenReturn("订单引用");
        when(selection.selectedTurns()).thenReturn(List.of(turn));

        assertThat(new RequestChatMemory(selection).get("conversation-1"))
                .satisfiesExactly(
                        message -> assertThat(message).isInstanceOf(UserMessage.class)
                                .extracting(item -> ((UserMessage) item).getText())
                                .isEqualTo("会话摘要"),
                        message -> assertThat(message).isInstanceOf(UserMessage.class)
                                .extracting(item -> ((UserMessage) item).getText())
                                .isEqualTo("订单引用"),
                        message -> assertThat(message).isInstanceOf(UserMessage.class)
                                .extracting(item -> ((UserMessage) item).getText())
                                .isEqualTo("历史问题"),
                        message -> assertThat(message).isInstanceOf(AssistantMessage.class)
                                .extracting(item -> ((AssistantMessage) item).getText())
                                .isEqualTo("历史回答")
                );
    }

    private void stubFixedEstimate() {
        when(estimator.strategyVersion()).thenReturn("test-v1");
        when(estimator.estimate("system", List.of(), "它到哪了"))
                .thenReturn(20L);
    }
}
