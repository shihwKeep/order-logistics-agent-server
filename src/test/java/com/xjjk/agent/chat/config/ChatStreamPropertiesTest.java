package com.xjjk.agent.chat.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatStreamPropertiesTest {

    @Test
    void rejectsReplayWithoutEventCapacity() {
        assertThatThrownBy(() -> new ChatStreamProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                new ChatStreamProperties.Replay(
                        true,
                        "agent:chat:stream:v1",
                        Duration.ofMinutes(2),
                        0,
                        65_536,
                        1_048_576,
                        Duration.ofSeconds(5)),
                reconnect()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件数量");
    }

    @Test
    void rejectsInitialBackoffGreaterThanMaximum() {
        assertThatThrownBy(() -> new ChatStreamProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                replay(),
                new ChatStreamProperties.Reconnect(
                        5,
                        Duration.ofSeconds(9),
                        Duration.ofSeconds(8),
                        0.2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("初始退避");
    }

    @Test
    void rejectsJitterOutsideUnitInterval() {
        assertThatThrownBy(() -> new ChatStreamProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                replay(),
                new ChatStreamProperties.Reconnect(
                        5,
                        Duration.ofMillis(500),
                        Duration.ofSeconds(8),
                        1.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("随机抖动");
    }

    private ChatStreamProperties.Replay replay() {
        return new ChatStreamProperties.Replay(
                true,
                "agent:chat:stream:v1",
                Duration.ofMinutes(2),
                512,
                65_536,
                1_048_576,
                Duration.ofSeconds(5));
    }

    private ChatStreamProperties.Reconnect reconnect() {
        return new ChatStreamProperties.Reconnect(
                5,
                Duration.ofMillis(500),
                Duration.ofSeconds(8),
                0.2);
    }
}
