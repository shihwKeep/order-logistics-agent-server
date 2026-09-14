package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatReplayEventCodecTest {

    private final ChatReplayEventCodec codec = new ChatReplayEventCodec(new ObjectMapper());

    @Test
    void encodesAndDecodesAnEventWithoutLosingItsPayload() {
        Instant timestamp = Instant.parse("2026-09-14T08:00:00Z");
        ChatReplayEncodedEvent encoded = codec.encode(
                "delta", timestamp, new ChatStreamPayloads.Delta("回答"));

        ChatReplayEvent event = codec.decode(7L, Map.of(
                "type", encoded.type(),
                "timestamp", encoded.timestamp(),
                "payload", encoded.payloadJson()));

        assertThat(event.sequence()).isEqualTo(7L);
        assertThat(event.type()).isEqualTo("delta");
        assertThat(event.timestamp()).isEqualTo(timestamp);
        assertThat(event.payload().get("text").asText()).isEqualTo("回答");
        assertThat(encoded.byteLength()).isPositive();
    }

    @Test
    void rejectsUnknownEventTypesBeforeWritingRedis() {
        assertThatThrownBy(() -> codec.encode("arbitrary", Instant.now(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件类型");
    }

    @Test
    void rejectsMalformedRedisEntries() {
        assertThatThrownBy(() -> codec.decode(1L, Map.of(
                "type", "delta",
                "timestamp", "bad-time",
                "payload", "{}")))
                .isInstanceOf(ChatReplayUnavailableException.class);
    }
}
