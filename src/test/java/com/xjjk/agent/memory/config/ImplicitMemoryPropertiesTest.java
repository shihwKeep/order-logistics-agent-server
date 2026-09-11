package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ImplicitMemoryPropertiesTest {

    @Test
    void acceptsProductionShape() {
        ImplicitMemoryProperties properties = valid();

        assertThat(properties.confidenceThreshold()).isEqualTo(0.85);
        assertThat(properties.expireDays()).isEqualTo(180);
        assertThat(properties.worker().maxAttempts()).isEqualTo(5);
        assertThat(properties.expiry().batchSize()).isEqualTo(100);
    }

    @Test
    void rejectsUnsafeExtractionBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ImplicitMemoryProperties(
                1.01, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(2, 100),
                worker(), new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ImplicitMemoryProperties(
                0.85, 0, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(2, 100),
                worker(), new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100)));
    }

    @Test
    void rejectsInvalidWorkerAndBackoffSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(2, 100),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofMinutes(6),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(2, 100)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100)));
    }

    private static ImplicitMemoryProperties valid() {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(2, 100),
                worker(), new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }

    private static ImplicitMemoryProperties.Worker worker() {
        return new ImplicitMemoryProperties.Worker(
                Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(2, 100));
    }
}
