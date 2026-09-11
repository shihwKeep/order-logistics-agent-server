package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.UserMemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryRecoverySchedulerTest {

    @Test
    void skipsRecoveryWhenMemoryFeatureIsDisabled() {
        ImplicitMemoryTaskCommitService service = mock(ImplicitMemoryTaskCommitService.class);
        ImplicitMemoryRecoveryScheduler scheduler = new ImplicitMemoryRecoveryScheduler(
                service, properties(false));

        scheduler.recover();

        verify(service, never()).recover();
    }

    @Test
    void recoversWhenMemoryFeatureIsEnabled() {
        ImplicitMemoryTaskCommitService service = mock(ImplicitMemoryTaskCommitService.class);
        when(service.recover()).thenReturn(
                new ImplicitMemoryTaskCommitService.RecoveryResult(0, 0));
        ImplicitMemoryRecoveryScheduler scheduler = new ImplicitMemoryRecoveryScheduler(
                service, properties(true));

        scheduler.recover();

        verify(service).recover();
    }

    private static UserMemoryProperties properties(boolean enabled) {
        return new UserMemoryProperties(
                enabled, true, 1200, 256, 256, 50, 3650,
                "memory-explicit-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), 2, 16);
    }
}
