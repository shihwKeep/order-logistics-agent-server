package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryPollerTest {

    @Test
    void dispatchesClaimsAndReleasesRejectedWork() {
        ImplicitMemoryTaskCommitService state = mock(ImplicitMemoryTaskCommitService.class);
        ImplicitMemoryTaskWorker worker = mock(ImplicitMemoryTaskWorker.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        MemoryExtractionTaskClaim claim = claim();
        when(state.claimAvailable(10)).thenReturn(List.of(claim));
        org.mockito.Mockito.doThrow(new TaskRejectedException("full"))
                .when(executor).execute(any(Runnable.class));

        poller(state, worker, executor, true).poll();

        verify(state).scheduleRetry(claim, "EXECUTOR_REJECTED");
        verify(worker, never()).process(any());
    }

    @Test
    void globalDisableDoesNotClaim() {
        ImplicitMemoryTaskCommitService state = mock(ImplicitMemoryTaskCommitService.class);

        poller(state, mock(ImplicitMemoryTaskWorker.class),
                mock(ThreadPoolTaskExecutor.class), false).poll();

        verify(state, never()).claimAvailable(10);
    }

    private static ImplicitMemoryPoller poller(
            ImplicitMemoryTaskCommitService state,
            ImplicitMemoryTaskWorker worker,
            ThreadPoolTaskExecutor executor,
            boolean enabled
    ) {
        return new ImplicitMemoryPoller(state, worker, executor, userProperties(enabled), properties());
    }

    private static MemoryExtractionTaskClaim claim() {
        return new MemoryExtractionTaskClaim(
                10L, "task-1", 1L, 2L, "conversation-1", "request-1", "user-1",
                17L, 4L, 0, "lease-1", "node-1",
                LocalDateTime.parse("2026-09-11T14:01:00"));
    }

    private static UserMemoryProperties userProperties(boolean enabled) {
        return new UserMemoryProperties(enabled, true, 256, 512, 512, 50, 365,
                "memory-v1", "qwen-plus", 0.1, Duration.ofSeconds(10), 1, 10);
    }

    private static ImplicitMemoryProperties properties() {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
