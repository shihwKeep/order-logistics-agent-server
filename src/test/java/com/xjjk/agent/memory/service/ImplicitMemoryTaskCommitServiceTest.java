package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.persistence.entity.MemoryExtractionTaskEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryExtractionTaskMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryTaskCommitServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-09-11T15:00:00");

    @Test
    void claimsAvailableTaskWithBoundedLease() {
        MemoryExtractionTaskMapper mapper = mock(MemoryExtractionTaskMapper.class);
        MemoryExtractionTaskEntity task = task(0);
        when(mapper.selectClaimableForUpdate(NOW, 20)).thenReturn(List.of(task));
        when(mapper.markClaimed(eq(9L), anyString(), eq("instance-1"),
                eq(NOW.plusSeconds(30)), eq(NOW))).thenReturn(1);
        ImplicitMemoryTaskCommitService service = service(mapper, 5);

        List<MemoryExtractionTaskClaim> claims = service.claimAvailable(20);

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.id()).isEqualTo(9L);
            assertThat(claim.taskId()).isEqualTo("task-1");
            assertThat(claim.lockedBy()).isEqualTo("instance-1");
            assertThat(claim.lockedUntil()).isEqualTo(NOW.plusSeconds(30));
        });
    }

    @Test
    void schedulesRetryWithSafeErrorCodeAndExponentialBackoff() {
        MemoryExtractionTaskMapper mapper = mock(MemoryExtractionTaskMapper.class);
        when(mapper.failLease(eq(9L), eq("lease-1"), eq("instance-1"), eq("RETRY"),
                eq(2), eq(NOW.plusSeconds(4)), eq("WORKER_FAILED"), eq(NOW))).thenReturn(1);
        ImplicitMemoryTaskCommitService service = service(mapper, 5);

        service.scheduleRetry(claim(1), "unsafe error detail");

        verify(mapper).failLease(9L, "lease-1", "instance-1", "RETRY",
                2, NOW.plusSeconds(4), "WORKER_FAILED", NOW);
    }

    @Test
    void marksTaskDeadAtMaximumAttempts() {
        MemoryExtractionTaskMapper mapper = mock(MemoryExtractionTaskMapper.class);
        when(mapper.failLease(eq(9L), eq("lease-1"), eq("instance-1"), eq("DEAD"),
                eq(3), eq(NOW), eq("MODEL_TIMEOUT"), eq(NOW))).thenReturn(1);
        ImplicitMemoryTaskCommitService service = service(mapper, 3);

        service.scheduleRetry(claim(2), "MODEL_TIMEOUT");

        verify(mapper).failLease(9L, "lease-1", "instance-1", "DEAD",
                3, NOW, "MODEL_TIMEOUT", NOW);
    }

    @Test
    void recoversExpiredLeasesAndCancelsInvalidTasks() {
        MemoryExtractionTaskMapper mapper = mock(MemoryExtractionTaskMapper.class);
        when(mapper.recoverExpiredLeases(NOW, 5, 20)).thenReturn(2);
        when(mapper.cancelInvalidUnclaimed(NOW, 20)).thenReturn(3);
        ImplicitMemoryTaskCommitService service = service(mapper, 5);

        ImplicitMemoryTaskCommitService.RecoveryResult result = service.recover();

        assertThat(result.recoveredLeases()).isEqualTo(2);
        assertThat(result.cancelledTasks()).isEqualTo(3);
    }

    private static ImplicitMemoryTaskCommitService service(
            MemoryExtractionTaskMapper mapper, int maxAttempts) {
        return new ImplicitMemoryTaskCommitService(
                mapper, properties(maxAttempts),
                Clock.fixed(Instant.parse("2026-09-11T15:00:00Z"), ZoneOffset.UTC),
                "instance-1");
    }

    private static ImplicitMemoryProperties properties(int maxAttempts) {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10),
                new ImplicitMemoryProperties.Executor(2, 16),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofMinutes(1), 20,
                        Duration.ofSeconds(30), maxAttempts, Duration.ofSeconds(2),
                        Duration.ofMinutes(1),
                        new ImplicitMemoryProperties.Executor(4, 64)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(5), 100));
    }

    private static MemoryExtractionTaskEntity task(int retryCount) {
        MemoryExtractionTaskEntity task = new MemoryExtractionTaskEntity();
        task.setId(9L);
        task.setTaskId("task-1");
        task.setTenantId(1L);
        task.setUserId(2L);
        task.setConversationId("conversation-1");
        task.setRequestId("request-1");
        task.setUserMessageId("message-1");
        task.setUserMessageSequence(7L);
        task.setMemoryGeneration(4L);
        task.setRetryCount(retryCount);
        return task;
    }

    private static MemoryExtractionTaskClaim claim(int retryCount) {
        return new MemoryExtractionTaskClaim(
                9L, "task-1", 1L, 2L, "conversation-1", "request-1",
                "message-1", 7L, 4L, retryCount,
                "lease-1", "instance-1", NOW.plusSeconds(30));
    }
}
