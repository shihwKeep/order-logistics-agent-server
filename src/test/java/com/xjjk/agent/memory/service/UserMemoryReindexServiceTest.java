package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryReindexServiceTest {
    @Test
    void pagesActiveMemoriesAndEnqueuesIdempotentUpserts() {
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemoryOutboxMapper outbox = mock(MemoryOutboxMapper.class);
        LocalDateTime now = LocalDateTime.of(2026, 9, 14, 12, 0);
        UserMemoryEntity first = memory(10L, "memory-a", 2L);
        UserMemoryEntity second = memory(20L, "memory-b", 4L);
        when(memories.selectActiveForReindex(0L, now, 1)).thenReturn(List.of(first));
        when(memories.selectActiveForReindex(10L, now, 1)).thenReturn(List.of(second));
        when(memories.selectActiveForReindex(20L, now, 1)).thenReturn(List.of());
        when(outbox.insertReindexEventIfAbsent(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), eq(now)))
                .thenReturn(1);
        UserMemoryReindexService service = new UserMemoryReindexService(memories, outbox,
                Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC));

        UserMemoryReindexResult result = service.enqueueAll(1);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.enqueued()).isEqualTo(2);
        verify(outbox).insertReindexEventIfAbsent(
                UserMemoryReindexService.eventId(first), "memory-a", 1L, 2L, 3L, 2L, now);
        verify(outbox).insertReindexEventIfAbsent(
                UserMemoryReindexService.eventId(second), "memory-b", 1L, 2L, 3L, 4L, now);
    }

    private UserMemoryEntity memory(long id, String memoryId, long version) {
        UserMemoryEntity value = new UserMemoryEntity();
        value.setId(id);
        value.setMemoryId(memoryId);
        value.setTenantId(1L);
        value.setUserId(2L);
        value.setMemoryGeneration(3L);
        value.setVersion(version);
        return value;
    }
}
