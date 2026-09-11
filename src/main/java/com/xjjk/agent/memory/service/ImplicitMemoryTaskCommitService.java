package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskStatus;
import com.xjjk.agent.memory.persistence.entity.MemoryExtractionTaskEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryExtractionTaskMapper;
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

/** 隐式记忆任务租约和重试状态的短事务边界。 */
@Service
public class ImplicitMemoryTaskCommitService {

    private final MemoryExtractionTaskMapper mapper;
    private final ImplicitMemoryProperties properties;
    private final Clock clock;
    private final String instanceId;

    @Autowired
    public ImplicitMemoryTaskCommitService(
            MemoryExtractionTaskMapper mapper,
            ImplicitMemoryProperties properties
    ) {
        this(mapper, properties, Clock.systemUTC(), UUID.randomUUID().toString());
    }

    ImplicitMemoryTaskCommitService(
            MemoryExtractionTaskMapper mapper,
            ImplicitMemoryProperties properties,
            Clock clock,
            String instanceId
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<MemoryExtractionTaskClaim> claimAvailable(int limit) {
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("claim limit is invalid");
        }
        LocalDateTime now = now();
        List<MemoryExtractionTaskEntity> tasks = mapper.selectClaimableForUpdate(now, limit);
        List<MemoryExtractionTaskClaim> claims = new ArrayList<>(tasks.size());
        for (MemoryExtractionTaskEntity task : tasks) {
            String leaseToken = UUID.randomUUID().toString();
            LocalDateTime lockedUntil = now.plus(properties.worker().leaseDuration());
            if (mapper.markClaimed(task.getId(), leaseToken, instanceId, lockedUntil, now) == 1) {
                claims.add(toClaim(task, leaseToken, lockedUntil));
            }
        }
        return List.copyOf(claims);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void scheduleRetry(MemoryExtractionTaskClaim claim, String errorCode) {
        Objects.requireNonNull(claim, "claim");
        String safeCode = safeCode(errorCode);
        int nextAttempt = Math.addExact(claim.retryCount(), 1);
        boolean dead = nextAttempt >= properties.worker().maxAttempts();
        LocalDateTime now = now();
        LocalDateTime nextRunAt = dead ? now : now.plus(backoff(nextAttempt));
        String status = dead
                ? MemoryExtractionTaskStatus.DEAD.name()
                : MemoryExtractionTaskStatus.RETRY.name();
        if (mapper.failLease(
                claim.id(), claim.leaseToken(), claim.lockedBy(), status,
                nextAttempt, nextRunAt, safeCode, now) != 1) {
            throw new IllegalStateException("隐式记忆任务重试状态更新失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void cancel(MemoryExtractionTaskClaim claim, String errorCode) {
        if (mapper.cancelLease(
                claim.id(), claim.leaseToken(), claim.lockedBy(), safeCode(errorCode), now()) != 1) {
            throw new IllegalStateException("隐式记忆任务取消失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public RecoveryResult recover() {
        LocalDateTime now = now();
        int limit = properties.worker().claimBatchSize();
        int leases = mapper.recoverExpiredLeases(
                now, properties.worker().maxAttempts(), limit);
        int cancelled = mapper.cancelInvalidUnclaimed(now, limit);
        return new RecoveryResult(leases, cancelled);
    }

    private MemoryExtractionTaskClaim toClaim(
            MemoryExtractionTaskEntity task, String leaseToken, LocalDateTime lockedUntil) {
        return new MemoryExtractionTaskClaim(
                task.getId(), task.getTaskId(), task.getTenantId(), task.getUserId(),
                task.getConversationId(), task.getRequestId(), task.getUserMessageId(),
                task.getUserMessageSequence(), task.getMemoryGeneration(), task.getRetryCount(),
                leaseToken, instanceId, lockedUntil);
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 20);
        Duration calculated;
        try {
            calculated = properties.worker().initialBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return properties.worker().maxBackoff();
        }
        return calculated.compareTo(properties.worker().maxBackoff()) > 0
                ? properties.worker().maxBackoff() : calculated;
    }

    private static String safeCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")) {
            return "WORKER_FAILED";
        }
        return errorCode;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    public record RecoveryResult(int recoveredLeases, int cancelledTasks) {
    }
}
