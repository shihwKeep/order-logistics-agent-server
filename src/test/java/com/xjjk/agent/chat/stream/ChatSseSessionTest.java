package com.xjjk.agent.chat.stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSseSessionTest {

    @Test
    void heartbeatStartsOnlyAfterSessionAndStopsAfterDone() throws Exception {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ChatSseSession session = new ChatSseSession(emitter);

        assertThat(session.heartbeat()).isFalse();
        session.session("conversation-1", "request-1");
        assertThat(session.heartbeat()).isTrue();
        session.done("message-1");
        assertThat(session.heartbeat()).isFalse();

        assertThat(emitter.eventNames()).containsExactly("session", "heartbeat", "done");
        assertThat(emitter.eventIds()).containsExactly("1", "2", "3");
    }

    @Test
    void errorStopsSubsequentHeartbeat() throws Exception {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ChatSseSession session = new ChatSseSession(emitter);
        session.session("conversation-1", "request-1");

        session.error(new ChatStreamError("FAILED", "失败"), "request-1");

        assertThat(session.heartbeat()).isFalse();
        assertThat(emitter.eventNames()).containsExactly("session", "error");
    }

    private static final class RecordingSseEmitter extends SseEmitter {

        private final List<SseEventBuilder> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(builder);
        }

        List<String> eventNames() {
            return fields("event:");
        }

        List<String> eventIds() {
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
