package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.MemoryIndexWorkerProperties;
import com.xjjk.agent.memory.domain.MemoryOutboxClaim;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.domain.MemoryOutboxStatus;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Outbox 租约、完成、重试及回收的短事务边界。 */
@Service
public class MemoryIndexOutboxStateService {
    private final MemoryOutboxMapper mapper;
    private final MemoryIndexWorkerProperties properties;
    private final Clock clock;
    private final String instanceId;
    private final UserMemoryMetrics metrics;

    @Autowired
    public MemoryIndexOutboxStateService(
            MemoryOutboxMapper mapper, MemoryIndexWorkerProperties properties,
            UserMemoryMetrics metrics) {
        this(mapper, properties, Clock.systemUTC(), UUID.randomUUID().toString(), metrics);
    }

    MemoryIndexOutboxStateService(
            MemoryOutboxMapper mapper,
            MemoryIndexWorkerProperties properties,
            Clock clock,
            String instanceId) {
        this(mapper, properties, clock, instanceId, null);
    }

    MemoryIndexOutboxStateService(
            MemoryOutboxMapper mapper,
            MemoryIndexWorkerProperties properties,
            Clock clock,
            String instanceId,
            UserMemoryMetrics metrics) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<MemoryOutboxClaim> claimAvailable() {
        LocalDateTime now = now();
        List<MemoryOutboxEntity> events = mapper.selectClaimableForUpdate(
                now, properties.claimBatchSize());
        List<MemoryOutboxClaim> claims = new ArrayList<>(events.size());
        for (MemoryOutboxEntity event : events) {
            String leaseToken = UUID.randomUUID().toString();
            LocalDateTime lockedUntil = now.plus(properties.leaseDuration());
            if (mapper.markClaimed(
                    event.getId(), leaseToken, instanceId, lockedUntil, now) == 1) {
                claims.add(toClaim(event, leaseToken, lockedUntil));
            }
        }
        return List.copyOf(claims);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void complete(MemoryOutboxClaim claim) {
        Objects.requireNonNull(claim, "claim");
        if (mapper.completeLease(
                claim.id(), claim.leaseToken(), claim.lockedBy(), now()) != 1) {
            throw new IllegalStateException("记忆索引任务完成状态更新失败");
        }
        if (metrics != null) metrics.outboxTransition("DONE");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void scheduleRetry(MemoryOutboxClaim claim, String errorCode) {
        Objects.requireNonNull(claim, "claim");
        int nextAttempt = Math.addExact(claim.retryCount(), 1);
        boolean dead = nextAttempt >= properties.maxAttempts();
        LocalDateTime now = now();
        String status = dead ? MemoryOutboxStatus.DEAD.name() : MemoryOutboxStatus.RETRY.name();
        LocalDateTime nextRunAt = dead ? now : now.plus(backoff(nextAttempt));
        if (mapper.failLease(
                claim.id(), claim.leaseToken(), claim.lockedBy(), status,
                nextAttempt, nextRunAt, safeCode(errorCode), now) != 1) {
            throw new IllegalStateException("记忆索引任务重试状态更新失败");
        }
        if (metrics != null) metrics.outboxTransition(status);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recoverExpiredLeases() {
        int recovered = mapper.recoverExpiredLeases(
                now(), properties.maxAttempts(), properties.claimBatchSize());
        if (metrics != null && recovered > 0) metrics.outboxTransition("RECOVERED");
        return recovered;
    }

    private MemoryOutboxClaim toClaim(
            MemoryOutboxEntity event, String leaseToken, LocalDateTime lockedUntil) {
        return new MemoryOutboxClaim(
                event.getId(), event.getEventId(), event.getMemoryId(),
                event.getTenantId(), event.getUserId(), event.getMemoryGeneration(),
                event.getMemoryVersion(), MemoryOutboxOperation.valueOf(event.getOperation()),
                event.getRetryCount(), leaseToken, instanceId, lockedUntil);
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 20);
        try {
            Duration calculated = properties.initialBackoff().multipliedBy(multiplier);
            return calculated.compareTo(properties.maxBackoff()) > 0
                    ? properties.maxBackoff() : calculated;
        } catch (ArithmeticException overflow) {
            return properties.maxBackoff();
        }
    }

    private String safeCode(String errorCode) {
        return errorCode != null && errorCode.matches("[A-Z0-9_]{1,64}")
                ? errorCode : "WORKER_FAILED";
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
