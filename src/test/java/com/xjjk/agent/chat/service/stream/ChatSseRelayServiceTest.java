package com.xjjk.agent.chat.service.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatStreamStatusResponse;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.replay.ChatReplayEvent;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.chat.replay.ChatReplaySnapshot;
import com.xjjk.agent.chat.replay.ChatReplayState;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSseRelayServiceTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void resumeReadsStrictlyAfterTheClientConfirmedSequence() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        ChatReplaySnapshot snapshot = runningSnapshot();
        when(repository.status(IDENTITY, REQUEST_ID)).thenReturn(Optional.of(snapshot));
        ChatReplayEvent done = new ChatReplayEvent(
                8L, "done", Instant.now(),
                new ObjectMapper().createObjectNode().put("messageId", "message-1"));
        when(repository.readAfter(
                IDENTITY, REQUEST_ID, 7L, Duration.ofSeconds(5)))
                .thenReturn(List.of(done));
        ChatSseRelayService service = new ChatSseRelayService(
                repository,
                new ChatStreamProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)),
                Runnable::run);

        service.resume(IDENTITY, REQUEST_ID, 7L);

        verify(repository).readAfter(
                IDENTITY, REQUEST_ID, 7L, Duration.ofSeconds(5));
    }

    @Test
    void unknownOrForeignRequestUsesTheSameNotFoundError() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        when(repository.status(IDENTITY, REQUEST_ID)).thenReturn(Optional.empty());
        ChatSseRelayService service = new ChatSseRelayService(
                repository,
                new ChatStreamProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)),
                Runnable::run);

        assertThatThrownBy(() -> service.status(IDENTITY, REQUEST_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.CHAT_STREAM_NOT_FOUND));
    }

    @Test
    void exposesOnlySafeReplayStatusFields() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        when(repository.status(IDENTITY, REQUEST_ID))
                .thenReturn(Optional.of(runningSnapshot()));
        ChatSseRelayService service = new ChatSseRelayService(
                repository,
                new ChatStreamProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)),
                Runnable::run);

        ChatStreamStatusResponse response = service.status(IDENTITY, REQUEST_ID);

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.state()).isEqualTo("RUNNING");
        assertThat(response.lastSequence()).isEqualTo(7L);
    }

    private ChatReplaySnapshot runningSnapshot() {
        Instant created = Instant.now();
        return new ChatReplaySnapshot(
                "conversation-1", REQUEST_ID, ChatReplayState.RUNNING,
                created, created.plusSeconds(30), 7L, null, null);
    }
}
