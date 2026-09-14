package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.chat.replay.ReplayChatEventPublisher;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReplayChatEventPublisherTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void publishesEveryBusinessEventToReplayStorage() throws Exception {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        ReplayChatEventPublisher publisher = new ReplayChatEventPublisher(
                repository, IDENTITY, REQUEST_ID);
        Instant expiresAt = Instant.parse("2026-09-14T08:00:30Z");

        publisher.session("conversation-1", REQUEST_ID, expiresAt, true);
        publisher.generating();
        publisher.delta("回答");
        publisher.done("message-1");

        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(repository, times(4)).append(
                eq(IDENTITY), eq(REQUEST_ID), types.capture(), any());
        assertThat(types.getAllValues())
                .containsExactly("session", "status", "delta", "done");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(repository).append(
                eq(IDENTITY), eq(REQUEST_ID), eq("session"), payload.capture());
        assertThat(payload.getValue())
                .isEqualTo(new ChatStreamPayloads.Session(
                        "conversation-1", REQUEST_ID, expiresAt, true));
        assertThat(publisher.heartbeat()).isFalse();

        InOrder bindingBeforeEvents = inOrder(repository);
        bindingBeforeEvents.verify(repository).bindConversation(
                IDENTITY, REQUEST_ID, "conversation-1");
        bindingBeforeEvents.verify(repository).append(
                eq(IDENTITY), eq(REQUEST_ID), eq("session"), any());
    }

    @Test
    void completeOnlyClosesTheProducerAndDoesNotCreateAnExtraRedisEvent() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        ReplayChatEventPublisher publisher = new ReplayChatEventPublisher(
                repository, IDENTITY, REQUEST_ID);

        publisher.complete();

        verify(repository, never()).append(any(), any(), any(), any());
    }

    @Test
    void usesTheTaskDeadlineWhenRunnerUsesTheCompatibilitySessionMethod() throws Exception {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        Instant expiresAt = Instant.parse("2026-09-14T08:00:30Z");
        ReplayChatEventPublisher publisher = new ReplayChatEventPublisher(
                repository, IDENTITY, REQUEST_ID, expiresAt);

        publisher.session("conversation-1", REQUEST_ID);

        verify(repository).append(
                IDENTITY,
                REQUEST_ID,
                "session",
                new ChatStreamPayloads.Session(
                        "conversation-1", REQUEST_ID, expiresAt, true));
    }
}
