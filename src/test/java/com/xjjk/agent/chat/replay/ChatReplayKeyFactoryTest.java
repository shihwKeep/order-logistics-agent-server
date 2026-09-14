package com.xjjk.agent.chat.replay;

import com.xjjk.agent.chat.config.ChatStreamProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatReplayKeyFactoryTest {

    private final ChatReplayKeyFactory factory = new ChatReplayKeyFactory(
            ChatStreamProperties.Replay.defaults());

    @Test
    void placesAllKeysForOneRequestInTheSameRedisClusterSlot() {
        assertThat(factory.meta(1L, 2L, "request-1"))
                .isEqualTo("agent:chat:stream:v1:{1:2:request-1}:meta");
        assertThat(factory.events(1L, 2L, "request-1"))
                .isEqualTo("agent:chat:stream:v1:{1:2:request-1}:events");
        assertThat(factory.control(1L, 2L, "request-1"))
                .isEqualTo("agent:chat:stream:v1:{1:2:request-1}:control");
    }

    @Test
    void separatesTenantAndUserIdentity() {
        assertThat(factory.events(1L, 2L, "request-1"))
                .isNotEqualTo(factory.events(1L, 3L, "request-1"))
                .isNotEqualTo(factory.events(4L, 2L, "request-1"));
    }

    @Test
    void rejectsUnsafeRequestIdBeforeBuildingAKey() {
        assertThatThrownBy(() -> factory.meta(1L, 2L, "request}:other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请求 ID");
    }
}
