package com.xjjk.agent.chat.stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DirectChatEventPublisherTest {

    @Test
    void preservesSequenceAndStopsPublishingAfterTerminalEvent() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        ChatEventPublisher publisher = new DirectChatEventPublisher(emitter);

        publisher.session(
                "conversation-1",
                "request-1",
                Instant.parse("2026-09-14T08:00:30Z"),
                false);
        publisher.generating();
        publisher.delta("回答");
        publisher.done("message-1");

        assertThat(publisher.heartbeat()).isFalse();
        assertThat(emitter.eventIds())
                .containsExactly("1", "2", "3", "4");
        assertThat(emitter.eventNames())
                .containsExactly("session", "status", "delta", "done");
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<SseEventBuilder> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            events.add(builder);
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
