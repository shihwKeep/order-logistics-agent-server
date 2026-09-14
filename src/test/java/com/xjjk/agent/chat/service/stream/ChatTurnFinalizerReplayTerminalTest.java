package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.chat.replay.ReplayChatEventPublisher;
import com.xjjk.agent.chat.service.turn.ChatTurnFinishService;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnFinalizerReplayTerminalTest {

    @Test
    void cancellationIsPersistedAsATerminalReplayEvent() {
        String requestId = "6f899318-0af5-4f2b-a593-84f6dac9dd1c";
        AgentIdentity identity = new AgentIdentity(
                2L, "agent", "坐席", 3L, 1L);
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        ReplayChatEventPublisher publisher = new ReplayChatEventPublisher(
                repository, identity, requestId);
        ChatTurnFinishService finishService = mock(ChatTurnFinishService.class);
        when(finishService.finish(any(), eq(MessageStatus.CANCELLED),
                any(), any(), eq("CHAT_CANCELLED"), any())).thenReturn(true);
        ChatTurnExecution execution = new ChatTurnExecution(requestId);
        execution.prepared(new ChatTurnContext(
                1L, 2L, "conversation-1", requestId,
                "user-message", "assistant-message", "prompt-v1"));
        ChatStreamControl control = new ChatStreamControl();
        control.requestStop(MessageStatus.CANCELLED);

        new ChatTurnFinalizer(finishService).finish(execution, control, publisher);

        verify(repository).append(
                eq(identity), eq(requestId), eq("error"), any());
    }
}
