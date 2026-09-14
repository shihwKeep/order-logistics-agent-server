package com.xjjk.agent.chat.service.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.replay.ChatReplayEvent;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSseRelayTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void relaysOnlyEventsAfterTheConfirmedSequenceAndStopsAtTerminal() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        Duration blockTimeout = Duration.ofSeconds(5);
        ChatReplayEvent event = new ChatReplayEvent(
                8L,
                "done",
                Instant.parse("2026-09-14T08:00:10Z"),
                new ObjectMapper().createObjectNode().put("messageId", "message-1"));
        when(repository.readAfter(IDENTITY, REQUEST_ID, 7L, blockTimeout))
                .thenReturn(List.of(event));
        RecordingEmitter emitter = new RecordingEmitter();
        ChatSseRelay relay = new ChatSseRelay(
                repository, IDENTITY, REQUEST_ID, 7L,
                "connection-1", blockTimeout, emitter);

        relay.run();

        verify(repository).readAfter(IDENTITY, REQUEST_ID, 7L, blockTimeout);
        verify(repository, never()).requestCancel(IDENTITY, REQUEST_ID);
        assertThat(emitter.eventIds()).containsExactly("8");
        assertThat(emitter.eventNames()).containsExactly("done");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void disconnectOnlyStopsTheRelayAndNeverCancelsTheProducer() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        ChatSseRelay relay = new ChatSseRelay(
                repository, IDENTITY, REQUEST_ID, 0L,
                "connection-1", Duration.ofSeconds(5), new RecordingEmitter());

        relay.disconnect();
        relay.run();

        verify(repository, never()).requestCancel(IDENTITY, REQUEST_ID);
        verify(repository, never()).readAfter(
                IDENTITY, REQUEST_ID, 0L, Duration.ofSeconds(5));
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<SseEventBuilder> events = new ArrayList<>();
        private boolean completed;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            events.add(builder);
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        private List<String> eventNames() {
            return fields("event:");
        }

        private List<String> eventIds() {
            return fields("id:");
        }

        private List<String> fields(String prefix) {
            return events.stream()
                    .flatMap(builder -> builder.build().stream())
                    .map(item -> item.getData().toString())
                    .flatMap(value -> value.lines())
                    .filter(value -> value.startsWith(prefix))
                    .map(value -> value.substring(prefix.length()).trim())
                    .toList();
        }
    }
}
