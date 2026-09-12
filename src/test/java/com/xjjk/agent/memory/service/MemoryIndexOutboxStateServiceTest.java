package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.MemoryIndexWorkerProperties;
import com.xjjk.agent.memory.domain.MemoryOutboxClaim;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
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

class MemoryIndexOutboxStateServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-09-12T08:00:00");

    @Test
    void claimsInAShortLeaseAndCompletesWithCas() {
        MemoryOutboxMapper mapper = mock(MemoryOutboxMapper.class);
        MemoryOutboxEntity entity = entity(0);
        when(mapper.selectClaimableForUpdate(NOW, 10)).thenReturn(List.of(entity));
        when(mapper.markClaimed(eq(5L), anyString(), eq("instance-1"),
                eq(NOW.plusSeconds(30)), eq(NOW))).thenReturn(1);
        MemoryIndexOutboxStateService state = service(mapper, 5);

        MemoryOutboxClaim claim = state.claimAvailable().getFirst();

        assertThat(claim.eventId()).isEqualTo("event-1");
        assertThat(claim.lockedBy()).isEqualTo("instance-1");
        when(mapper.completeLease(5L, claim.leaseToken(), "instance-1", NOW)).thenReturn(1);
        state.complete(claim);
        verify(mapper).completeLease(5L, claim.leaseToken(), "instance-1", NOW);
    }

    @Test
    void retriesWithExponentialBackoffThenMovesToDead() {
        MemoryOutboxMapper mapper = mock(MemoryOutboxMapper.class);
        MemoryIndexOutboxStateService state = service(mapper, 3);
        MemoryOutboxClaim retry = claim(1);
        when(mapper.failLease(5L, "lease-1", "instance-1", "RETRY", 2,
                NOW.plusSeconds(4), "INDEX_UNAVAILABLE", NOW)).thenReturn(1);

        state.scheduleRetry(retry, "INDEX_UNAVAILABLE");

        verify(mapper).failLease(5L, "lease-1", "instance-1", "RETRY", 2,
                NOW.plusSeconds(4), "INDEX_UNAVAILABLE", NOW);

        MemoryOutboxClaim finalAttempt = claim(2);
        when(mapper.failLease(5L, "lease-1", "instance-1", "DEAD", 3,
                NOW, "WORKER_FAILED", NOW)).thenReturn(1);
        state.scheduleRetry(finalAttempt, "unsafe details");
        verify(mapper).failLease(5L, "lease-1", "instance-1", "DEAD", 3,
                NOW, "WORKER_FAILED", NOW);
    }

    @Test
    void recoversExpiredLeasesWithConfiguredBound() {
        MemoryOutboxMapper mapper = mock(MemoryOutboxMapper.class);
        when(mapper.recoverExpiredLeases(NOW, 5, 10)).thenReturn(2);

        assertThat(service(mapper, 5).recoverExpiredLeases()).isEqualTo(2);
    }

    private MemoryIndexOutboxStateService service(MemoryOutboxMapper mapper, int maxAttempts) {
        return new MemoryIndexOutboxStateService(
                mapper, properties(maxAttempts),
                Clock.fixed(Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC),
                "instance-1");
    }

    private MemoryIndexWorkerProperties properties(int maxAttempts) {
        return new MemoryIndexWorkerProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                Duration.ofSeconds(30), maxAttempts,
                Duration.ofSeconds(2), Duration.ofMinutes(1),
                new MemoryIndexWorkerProperties.Executor(2, 32));
    }

    private MemoryOutboxEntity entity(int retryCount) {
        MemoryOutboxEntity entity = new MemoryOutboxEntity();
        entity.setId(5L);
        entity.setEventId("event-1");
        entity.setMemoryId("memory-1");
        entity.setTenantId(7L);
        entity.setUserId(9L);
        entity.setMemoryGeneration(3L);
        entity.setMemoryVersion(2L);
        entity.setOperation("UPSERT");
        entity.setRetryCount(retryCount);
        return entity;
    }

    private MemoryOutboxClaim claim(int retryCount) {
        return new MemoryOutboxClaim(
                5L, "event-1", "memory-1", 7L, 9L, 3L, 2L,
                com.xjjk.agent.memory.domain.MemoryOutboxOperation.UPSERT, retryCount,
                "lease-1", "instance-1", NOW.plusSeconds(30));
    }
}
