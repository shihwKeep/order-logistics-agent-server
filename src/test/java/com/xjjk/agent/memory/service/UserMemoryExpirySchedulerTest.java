package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserMemoryExpirySchedulerTest {

    @Test
    void skipsExpiryWhenMemoryFeatureIsDisabled() {
        UserMemoryExpiryService service = mock(UserMemoryExpiryService.class);
        UserMemoryExpiryScheduler scheduler = new UserMemoryExpiryScheduler(
                service, memoryProperties(false), implicitProperties());

        scheduler.expire();

        verify(service, never()).expireBatch(100);
    }

    @Test
    void expiresConfiguredBatchWhenMemoryFeatureIsEnabled() {
        UserMemoryExpiryService service = mock(UserMemoryExpiryService.class);
        UserMemoryExpiryScheduler scheduler = new UserMemoryExpiryScheduler(
                service, memoryProperties(true), implicitProperties());

        scheduler.expire();

        verify(service).expireBatch(100);
    }

    private static UserMemoryProperties memoryProperties(boolean enabled) {
        return new UserMemoryProperties(
                enabled, true, 1200, 256, 256, 50, 3650,
                "memory-explicit-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), 2, 16);
    }

    private static ImplicitMemoryProperties implicitProperties() {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10),
                new ImplicitMemoryProperties.Executor(2, 16),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofMinutes(1), 20,
                        Duration.ofSeconds(30), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(1),
                        new ImplicitMemoryProperties.Executor(4, 64)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(5), 100));
    }
}
