package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryIndexWorkerPropertiesTest {

    @Test
    void rejectsUnboundedOrInvalidWorkerSettings() {
        assertThatThrownBy(() -> new MemoryIndexWorkerProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 101,
                Duration.ofSeconds(30), 5, Duration.ofSeconds(1),
                Duration.ofMinutes(1), new MemoryIndexWorkerProperties.Executor(2, 32)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryIndexWorkerProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 10,
                Duration.ofSeconds(30), 5, Duration.ofMinutes(2),
                Duration.ofMinutes(1), new MemoryIndexWorkerProperties.Executor(2, 32)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
