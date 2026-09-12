package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.MemoryOutboxClaim;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.index.MemoryIndexCommand;
import com.xjjk.agent.memory.index.MemoryIndexGateway;
import com.xjjk.agent.memory.index.MemoryIndexUnavailableException;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 在数据库事务之外投递单个 Outbox 事件。 */
@Slf4j
@Service
public class MemoryIndexOutboxWorker {
    private final UserMemoryMapper memoryMapper;
    private final MemoryIndexGateway gateway;
    private final MemoryIndexOutboxStateService state;
    private final Clock clock;
    private final UserMemoryMetrics metrics;

    @Autowired
    public MemoryIndexOutboxWorker(
            UserMemoryMapper memoryMapper,
            MemoryIndexGateway gateway,
            MemoryIndexOutboxStateService state,
            UserMemoryMetrics metrics) {
        this(memoryMapper, gateway, state, Clock.systemUTC(), metrics);
    }

    MemoryIndexOutboxWorker(
            UserMemoryMapper memoryMapper,
            MemoryIndexGateway gateway,
            MemoryIndexOutboxStateService state,
            Clock clock) {
        this(memoryMapper, gateway, state, clock, null);
    }

    MemoryIndexOutboxWorker(
            UserMemoryMapper memoryMapper,
            MemoryIndexGateway gateway,
            MemoryIndexOutboxStateService state,
            Clock clock,
            UserMemoryMetrics metrics) {
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.state = Objects.requireNonNull(state, "state");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = metrics;
    }

    public void process(MemoryOutboxClaim claim) {
        try {
            gateway.apply(toCommand(claim));
        } catch (MemoryIndexUnavailableException unavailable) {
            recordIndex(claim, "FAILURE");
            state.scheduleRetry(claim, "INDEX_UNAVAILABLE");
            return;
        } catch (RuntimeException failure) {
            recordIndex(claim, "FAILURE");
            log.warn("memory_index_outbox eventId={}, operation={}, result=RETRY, errorCode=WORKER_FAILED",
                    claim.eventId(), claim.operation());
            state.scheduleRetry(claim, "WORKER_FAILED");
            return;
        }
        state.complete(claim);
        recordIndex(claim, "SUCCESS");
    }

    private MemoryIndexCommand toCommand(MemoryOutboxClaim claim) {
        if (claim.operation() != MemoryOutboxOperation.UPSERT) {
            return deleteCommand(claim, claim.operation());
        }
        UserMemoryEntity current = memoryMapper.selectActiveIndexable(
                claim.tenantId(), claim.userId(), claim.memoryGeneration(),
                claim.memoryId(), now());
        if (current == null) {
            return deleteCommand(claim, MemoryOutboxOperation.DELETE);
        }
        Instant expiresAt = current.getExpiresAt() == null ? null
                : current.getExpiresAt().toInstant(ZoneOffset.UTC);
        return new MemoryIndexCommand(
                claim.tenantId(), claim.userId(), claim.eventId(),
                MemoryOutboxOperation.UPSERT, claim.memoryGeneration(), current.getMemoryId(),
                current.getVersion(), current.getSourceType(), current.getCategory(),
                current.getCanonicalKey(), current.getContent(),
                current.getConfidence().doubleValue(), expiresAt);
    }

    private MemoryIndexCommand deleteCommand(
            MemoryOutboxClaim claim, MemoryOutboxOperation operation) {
        return new MemoryIndexCommand(
                claim.tenantId(), claim.userId(), claim.eventId(), operation,
                claim.memoryGeneration(), claim.memoryId(), claim.memoryVersion(),
                null, null, null, null, 0D, null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private void recordIndex(MemoryOutboxClaim claim, String outcome) {
        if (metrics != null) {
            metrics.indexOperation(claim.operation().name(), outcome);
        }
    }
}
