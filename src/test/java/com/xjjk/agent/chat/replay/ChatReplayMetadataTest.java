package com.xjjk.agent.chat.replay;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatReplayMetadataTest {

    @Test
    void rejectsAnExpiryThatDoesNotFollowCreation() {
        Instant now = Instant.parse("2026-09-14T08:00:00Z");

        assertThatThrownBy(() -> new ChatReplayMetadata(
                1L, 2L, 3L, "conversation", "request", now, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("截止时间");
    }
}
