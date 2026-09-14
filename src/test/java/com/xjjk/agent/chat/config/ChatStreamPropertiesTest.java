package com.xjjk.agent.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamPropertiesTest {

    @Test
    void bindsTheCompleteConfigurationInsideSpring() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "agent.chat.stream.timeout=30s",
                        "agent.chat.stream.heartbeat-interval=10s",
                        "agent.chat.stream.replay.enabled=true",
                        "agent.chat.stream.replay.key-prefix=agent:chat:stream:v1",
                        "agent.chat.stream.replay.ttl=2m",
                        "agent.chat.stream.replay.max-events=512",
                        "agent.chat.stream.replay.max-event-bytes=65536",
                        "agent.chat.stream.replay.max-stream-bytes=1048576",
                        "agent.chat.stream.replay.read-block-timeout=5s",
                        "agent.chat.stream.reconnect.max-attempts=5",
                        "agent.chat.stream.reconnect.initial-backoff=500ms",
                        "agent.chat.stream.reconnect.max-backoff=8s",
                        "agent.chat.stream.reconnect.jitter-ratio=0.2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatStreamProperties.class).replay().maxEvents())
                            .isEqualTo(512);
                });
    }

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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatStreamProperties.class)
    static class BindingConfiguration {
    }
}
