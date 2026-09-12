package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.action.ChatActionDispatcher;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.routing.BusinessQueryPlanner;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.service.ExplicitMemoryCommandService;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatTurnRunnerExplicitMemoryTest {

    @Test
    void handledMemoryCommandBypassesBusinessPlanningHistoryAndChatModel() throws Exception {
        ChatTurnPreparationService preparation = mock(ChatTurnPreparationService.class);
        ChatContextPreparationService context = mock(ChatContextPreparationService.class);
        AiChatService ai = mock(AiChatService.class);
        ChatTurnFinalizer finalizer = mock(ChatTurnFinalizer.class);
        BusinessQueryPlanner planner = mock(BusinessQueryPlanner.class);
        ExplicitMemoryCommandService memoryService = mock(ExplicitMemoryCommandService.class);
        DeterministicUserMemoryAnswerService directMemoryService =
                mock(DeterministicUserMemoryAnswerService.class);
        ChatSseSession session = mock(ChatSseSession.class);
        AgentIdentity identity = new AgentIdentity(2L, "account", "name", 3L, 1L);
        ChatTurnContext turn = new ChatTurnContext(1L, 2L, "conversation", "request",
                "user-message", "assistant-message", "prompt-v1");
        String message = "请记住以后回答简短一些";
        when(preparation.prepare(null, identity, message)).thenReturn(turn);
        when(memoryService.handle(turn, message)).thenReturn(new ExplicitMemoryCommandResult(
                true, true, "好的，已记住：用户偏好简洁回答", "memory-1"));

        ChatTurnRunner runner = new ChatTurnRunner(
                preparation, context, ai, finalizer,
                mock(ChatToolResultRecorder.class), mock(ChatActionDispatcher.class),
                planner, new FreshBusinessResultGate(), memoryService,
                directMemoryService);
        ChatStreamControl control = new ChatStreamControl();
        runner.run(new ChatStreamRequest(null, message, null), identity, control, session, "fallback");

        verify(session).generating();
        verify(session).delta("好的，已记住：用户偏好简洁回答");
        verifyNoInteractions(planner, context, ai, directMemoryService);
        ArgumentCaptor<ChatTurnExecution> captured = ArgumentCaptor.forClass(ChatTurnExecution.class);
        verify(finalizer).finish(captured.capture(), org.mockito.ArgumentMatchers.eq(control),
                org.mockito.ArgumentMatchers.eq(session));
        assertThat(captured.getValue().status).isEqualTo(MessageStatus.SUCCESS);
        assertThat(captured.getValue().finishReason).isEqualTo("MEMORY_SAVED");
        assertThat(captured.getValue().content.toString())
                .isEqualTo("好的，已记住：用户偏好简洁回答");
    }

    @Test
    void clarificationNeverEmitsSavedAcknowledgement() throws Exception {
        ChatTurnPreparationService preparation = mock(ChatTurnPreparationService.class);
        ChatContextPreparationService context = mock(ChatContextPreparationService.class);
        AiChatService ai = mock(AiChatService.class);
        ChatTurnFinalizer finalizer = mock(ChatTurnFinalizer.class);
        BusinessQueryPlanner planner = mock(BusinessQueryPlanner.class);
        ExplicitMemoryCommandService memoryService = mock(ExplicitMemoryCommandService.class);
        DeterministicUserMemoryAnswerService directMemoryService =
                mock(DeterministicUserMemoryAnswerService.class);
        ChatSseSession session = mock(ChatSseSession.class);
        AgentIdentity identity = new AgentIdentity(2L, "account", "name", 3L, 1L);
        ChatTurnContext turn = new ChatTurnContext(1L, 2L, "conversation", "request",
                "user-message", "assistant-message", "prompt-v1");
        String message = "以后这样就行";
        String clarification = "你希望我记住什么？请把需要长期记住的内容说清楚。";
        when(preparation.prepare(null, identity, message)).thenReturn(turn);
        when(memoryService.handle(turn, message)).thenReturn(new ExplicitMemoryCommandResult(
                true, false, clarification, null));

        ChatTurnRunner runner = new ChatTurnRunner(
                preparation, context, ai, finalizer,
                mock(ChatToolResultRecorder.class), mock(ChatActionDispatcher.class),
                planner, new FreshBusinessResultGate(), memoryService, directMemoryService);
        ChatStreamControl control = new ChatStreamControl();
        runner.run(new ChatStreamRequest(null, message, null), identity, control, session, "fallback");

        verify(session).delta(clarification);
        assertThat(clarification).doesNotContain("已记住");
        verifyNoInteractions(planner, context, ai, directMemoryService);
    }

    @Test
    void persistenceFailureNeverEmitsSavedAcknowledgement() throws Exception {
        ChatTurnPreparationService preparation = mock(ChatTurnPreparationService.class);
        ChatTurnFinalizer finalizer = mock(ChatTurnFinalizer.class);
        ExplicitMemoryCommandService memoryService = mock(ExplicitMemoryCommandService.class);
        ChatSseSession session = mock(ChatSseSession.class);
        AgentIdentity identity = new AgentIdentity(2L, "account", "name", 3L, 1L);
        ChatTurnContext turn = new ChatTurnContext(1L, 2L, "conversation", "request",
                "user-message", "assistant-message", "prompt-v1");
        String message = "你以后都叫我石海文";
        when(preparation.prepare(null, identity, message)).thenReturn(turn);
        when(memoryService.handle(turn, message))
                .thenThrow(new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED));

        ChatTurnRunner runner = new ChatTurnRunner(
                preparation, mock(ChatContextPreparationService.class), mock(AiChatService.class),
                finalizer, mock(ChatToolResultRecorder.class), mock(ChatActionDispatcher.class),
                mock(BusinessQueryPlanner.class), new FreshBusinessResultGate(), memoryService,
                mock(DeterministicUserMemoryAnswerService.class));
        ChatStreamControl control = new ChatStreamControl();
        runner.run(new ChatStreamRequest(null, message, null), identity, control, session, "fallback");

        verify(session, never()).generating();
        verify(session, never()).delta(org.mockito.ArgumentMatchers.anyString());
        ArgumentCaptor<ChatTurnExecution> captured = ArgumentCaptor.forClass(ChatTurnExecution.class);
        verify(finalizer).finish(captured.capture(), org.mockito.ArgumentMatchers.eq(control),
                org.mockito.ArgumentMatchers.eq(session));
        assertThat(captured.getValue().status).isEqualTo(MessageStatus.FAILED);
        assertThat(captured.getValue().content).isEmpty();
    }
}
