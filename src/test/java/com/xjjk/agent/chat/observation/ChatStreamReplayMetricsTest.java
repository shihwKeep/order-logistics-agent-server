package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamReplayMetricsTest {

    @Test
    void recordsOnlyBoundedOperationalLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatStreamReplayMetrics metrics = new ChatStreamReplayMetrics(registry);

        metrics.mode("resumable");
        metrics.resume("success");
        metrics.replayEvent("delta", 12);
        metrics.failure("redis");
        metrics.cancel("accepted");
        AutoCloseable relay = metrics.relayConnection();
        assertThat(registry.get("agent.chat.stream.relay.active").gauge().value())
                .isEqualTo(1.0);
        org.assertj.core.api.Assertions.assertThatCode(relay::close)
                .doesNotThrowAnyException();

        assertThat(registry.get("agent.chat.stream.mode")
                .tag("mode", "resumable").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.chat.stream.replay.events")
                .tag("type", "delta").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.chat.stream.replay.bytes").summary().totalAmount())
                .isEqualTo(12.0);
        assertThat(registry.get("agent.chat.stream.relay.active").gauge().value())
                .isZero();
    }
}
