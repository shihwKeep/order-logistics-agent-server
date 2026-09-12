package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.action.ChatActionDispatcher;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.routing.BusinessQueryPlan;
import com.xjjk.agent.chat.routing.BusinessQueryPlanner;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerResult;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.service.ExplicitMemoryCommandService;
import com.xjjk.agent.tool.AgentToolRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnRunnerDeterministicMemoryTest {
    private static final AgentIdentity IDENTITY = new AgentIdentity(
            10567L, "account", "name", 3673L, 1L);

    @Mock
    private ChatTurnPreparationService preparation;
    @Mock
    private ChatContextPreparationService context;
    @Mock
    private AiChatService ai;
    @Mock
    private ChatTurnFinalizer finalizer;
    @Mock
    private ChatToolResultRecorder resultRecorder;
    @Mock
    private ChatActionDispatcher actionDispatcher;
    @Mock
    private BusinessQueryPlanner planner;
    @Mock
    private ExplicitMemoryCommandService explicitMemory;
    @Mock
    private DeterministicUserMemoryAnswerService directMemory;
    @Mock
    private ChatSseSession session;

    @Test
    void generalDirectMemoryQuestionBypassesContextAndModel() throws Exception {
        String message = "我平时主要使用什么编程语言？";
        ChatTurnContext turn = turn();
        ChatStreamControl control = new ChatStreamControl();
        when(preparation.prepare(null, IDENTITY, message)).thenReturn(turn);
        when(explicitMemory.handle(turn, message))
                .thenReturn(ExplicitMemoryCommandResult.notHandled());
        when(planner.plan(message)).thenReturn(BusinessQueryPlan.general());
        when(directMemory.answer(IDENTITY, message, "request"))
                .thenReturn(new DeterministicUserMemoryAnswerResult(
                        DeterministicUserMemoryAnswerResult.Outcome.ANSWERED,
                        "根据您之前提供的信息，您平时主要使用 Java。"));

        runner().run(new ChatStreamRequest(null, message, null), IDENTITY,
                control, session, "fallback");

        verify(session).generating();
        verify(session).delta("根据您之前提供的信息，您平时主要使用 Java。");
        verifyNoInteractions(context, ai);
        ArgumentCaptor<ChatTurnExecution> captured =
                ArgumentCaptor.forClass(ChatTurnExecution.class);
        verify(finalizer).finish(captured.capture(), eq(control), eq(session));
        assertThat(captured.getValue().status).isEqualTo(MessageStatus.SUCCESS);
        assertThat(captured.getValue().finishReason).isEqualTo("MEMORY_RECALLED");
        assertThat(captured.getValue().content.toString())
                .isEqualTo("根据您之前提供的信息，您平时主要使用 Java。");
    }

    @Test
    void notHandledMemoryQuestionContinuesToExistingModelPath() throws Exception {
        String message = "你怎么称呼我？";
        ChatTurnContext turn = turn();
        ChatStreamControl control = new ChatStreamControl();
        ChatContextSelection selection = org.mockito.Mockito.mock(ChatContextSelection.class);
        when(preparation.prepare(null, IDENTITY, message)).thenReturn(turn);
        when(explicitMemory.handle(turn, message))
                .thenReturn(ExplicitMemoryCommandResult.notHandled());
        when(planner.plan(message)).thenReturn(BusinessQueryPlan.general());
        when(directMemory.answer(IDENTITY, message, "request"))
                .thenReturn(DeterministicUserMemoryAnswerResult.notHandled());
        when(context.prepare(turn, message, control)).thenReturn(selection);
        when(ai.stream(eq(message), eq(selection), any(AgentToolRequestContext.class)))
                .thenReturn(Flux.just(response("根据当前会话，我称呼您为石海文。")));

        runner().run(new ChatStreamRequest(null, message, null), IDENTITY,
                control, session, "fallback");

        verify(context).prepare(turn, message, control);
        verify(ai).stream(eq(message), eq(selection), any(AgentToolRequestContext.class));
        verify(session).delta("根据当前会话，我称呼您为石海文。");
    }

    @Test
    void modelRequiredBusinessPlanNeverCallsDirectMemoryService() throws Exception {
        String message = "签收后多久可以退款？";
        ChatTurnContext turn = turn();
        ChatStreamControl control = new ChatStreamControl();
        ChatContextSelection selection = org.mockito.Mockito.mock(ChatContextSelection.class);
        when(preparation.prepare(null, IDENTITY, message)).thenReturn(turn);
        when(explicitMemory.handle(turn, message))
                .thenReturn(ExplicitMemoryCommandResult.notHandled());
        when(planner.plan(message)).thenReturn(
                BusinessQueryPlan.modelRequired(Set.of("knowledge-citations")));
        when(context.prepare(turn, message, control)).thenReturn(selection);
        when(ai.stream(eq(message), eq(selection), any(AgentToolRequestContext.class)))
                .thenReturn(Flux.just(response("知识回答")));

        runner().run(new ChatStreamRequest(null, message, null), IDENTITY,
                control, session, "fallback");

        verifyNoInteractions(directMemory);
        verify(ai).stream(eq(message), eq(selection), any(AgentToolRequestContext.class));
    }

    @Test
    void stopRequestedDuringRecallDoesNotPublishDirectAnswer() throws Exception {
        String message = "我平时主要使用什么编程语言？";
        ChatTurnContext turn = turn();
        ChatStreamControl control = new ChatStreamControl();
        when(preparation.prepare(null, IDENTITY, message)).thenReturn(turn);
        when(explicitMemory.handle(turn, message))
                .thenReturn(ExplicitMemoryCommandResult.notHandled());
        when(planner.plan(message)).thenReturn(BusinessQueryPlan.general());
        when(directMemory.answer(IDENTITY, message, "request")).thenAnswer(invocation -> {
            control.requestStop(MessageStatus.CANCELLED);
            return new DeterministicUserMemoryAnswerResult(
                    DeterministicUserMemoryAnswerResult.Outcome.ANSWERED,
                    "根据您之前提供的信息，您平时主要使用 Java。");
        });

        runner().run(new ChatStreamRequest(null, message, null), IDENTITY,
                control, session, "fallback");

        verify(session, never()).generating();
        verify(session, never()).delta(anyString());
        verifyNoInteractions(context, ai);
    }

    private ChatTurnRunner runner() {
        return new ChatTurnRunner(
                preparation, context, ai, finalizer, resultRecorder,
                actionDispatcher, planner, new FreshBusinessResultGate(),
                explicitMemory, directMemory);
    }

    private ChatTurnContext turn() {
        return new ChatTurnContext(
                1L, 10567L, "conversation", "request",
                "user", "assistant", "prompt-v1");
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
    }
}
