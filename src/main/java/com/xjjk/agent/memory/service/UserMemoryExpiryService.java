package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.domain.MemoryOutboxStatus;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 批量失效已到期的隐藏自动记忆，并在同一事务内登记索引删除事件。 */
@Service
public class UserMemoryExpiryService {

    private final UserMemoryMapper memoryMapper;
    private final MemoryOutboxMapper outboxMapper;
    private final Clock clock;

    @Autowired
    public UserMemoryExpiryService(
            UserMemoryMapper memoryMapper,
            MemoryOutboxMapper outboxMapper
    ) {
        this(memoryMapper, outboxMapper, Clock.systemUTC());
    }

    UserMemoryExpiryService(
            UserMemoryMapper memoryMapper,
            MemoryOutboxMapper outboxMapper,
            Clock clock
    ) {
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int expireBatch(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("隐式记忆过期批量大小不合法");
        }
        LocalDateTime now = now();
        List<UserMemoryEntity> memories = memoryMapper.selectExpiredAutomaticForUpdate(now, limit);
        int expired = 0;
        for (UserMemoryEntity memory : memories) {
            if (memoryMapper.expireOwnedAutomatic(
                    required(memory.getId(), "id"),
                    required(memory.getTenantId(), "tenantId"),
                    required(memory.getUserId(), "userId"),
                    required(memory.getMemoryGeneration(), "memoryGeneration"),
                    Objects.requireNonNull(memory.getMemoryId(), "memoryId"),
                    required(memory.getVersion(), "version"),
                    now) != 1) {
                throw new IllegalStateException("隐式记忆过期状态更新失败");
            }
            insertDeleteOutbox(memory, now);
            expired++;
        }
        return expired;
    }

    private void insertDeleteOutbox(UserMemoryEntity memory, LocalDateTime now) {
        MemoryOutboxEntity outbox = new MemoryOutboxEntity();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setMemoryId(memory.getMemoryId());
        outbox.setTenantId(memory.getTenantId());
        outbox.setUserId(memory.getUserId());
        outbox.setMemoryGeneration(memory.getMemoryGeneration());
        outbox.setMemoryVersion(memory.getVersion());
        outbox.setOperation(MemoryOutboxOperation.DELETE.name());
        outbox.setStatus(MemoryOutboxStatus.PENDING.name());
        outbox.setRetryCount(0);
        outbox.setNextRunAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        if (outboxMapper.insert(outbox) != 1) {
            throw new IllegalStateException("隐式记忆过期索引事件写入失败");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static long required(Long value, String field) {
        return Objects.requireNonNull(value, field);
    }
}
