package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class UserMemoryReindexService {
    private static final String RUN_NAMESPACE = "BAILIAN_V2_REINDEX";
    private final UserMemoryMapper memories;
    private final MemoryOutboxMapper outbox;
    private final Clock clock;

    @Autowired
    public UserMemoryReindexService(UserMemoryMapper memories, MemoryOutboxMapper outbox) {
        this(memories, outbox, Clock.systemUTC());
    }

    UserMemoryReindexService(UserMemoryMapper memories, MemoryOutboxMapper outbox, Clock clock) {
        this.memories = Objects.requireNonNull(memories);
        this.outbox = Objects.requireNonNull(outbox);
        this.clock = Objects.requireNonNull(clock);
    }

    public UserMemoryReindexResult enqueueAll(int batchSize) {
        if (batchSize < 1 || batchSize > 5_000) {
            throw new IllegalArgumentException("记忆重建批量必须在 1 到 5000 之间");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long afterId = 0L;
        long scanned = 0L;
        long enqueued = 0L;
        while (true) {
            List<UserMemoryEntity> page = memories.selectActiveForReindex(afterId, now, batchSize);
            if (page.isEmpty()) break;
            for (UserMemoryEntity memory : page) {
                validate(memory);
                scanned++;
                enqueued += outbox.insertReindexEventIfAbsent(eventId(memory), memory.getMemoryId(),
                        memory.getTenantId(), memory.getUserId(), memory.getMemoryGeneration(),
                        memory.getVersion(), now);
                afterId = memory.getId();
            }
            if (page.size() < batchSize) break;
        }
        return new UserMemoryReindexResult(scanned, enqueued);
    }

    static String eventId(UserMemoryEntity memory) {
        String seed = RUN_NAMESPACE + "|" + memory.getMemoryId() + "|" + memory.getVersion();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void validate(UserMemoryEntity memory) {
        if (memory == null || memory.getId() == null || memory.getId() <= 0
                || memory.getMemoryId() == null || memory.getMemoryId().isBlank()
                || memory.getTenantId() == null || memory.getTenantId() <= 0
                || memory.getUserId() == null || memory.getUserId() <= 0
                || memory.getMemoryGeneration() == null || memory.getMemoryGeneration() < 0
                || memory.getVersion() == null || memory.getVersion() < 0) {
            throw new IllegalStateException("活动记忆缺少重建所需身份或版本");
        }
    }
}
