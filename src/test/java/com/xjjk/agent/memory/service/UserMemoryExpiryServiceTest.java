package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryExpiryServiceTest {

    @Test
    void expiresClaimedAutomaticMemoryAndWritesDeleteOutbox() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        MemoryOutboxMapper outboxMapper = mock(MemoryOutboxMapper.class);
        LocalDateTime now = LocalDateTime.parse("2026-09-11T15:00:00");
        UserMemoryEntity memory = expiredMemory();
        when(memoryMapper.selectExpiredAutomaticForUpdate(now, 100)).thenReturn(List.of(memory));
        when(memoryMapper.expireOwnedAutomatic(
                9L, 1L, 2L, 4L, "memory-1", 3L, now)).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(1);
        UserMemoryExpiryService service = new UserMemoryExpiryService(
                memoryMapper, outboxMapper,
                Clock.fixed(Instant.parse("2026-09-11T15:00:00Z"), ZoneOffset.UTC));

        assertThat(service.expireBatch(100)).isEqualTo(1);

        ArgumentCaptor<MemoryOutboxEntity> outbox = ArgumentCaptor.forClass(MemoryOutboxEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue()).satisfies(event -> {
            assertThat(event.getOperation()).isEqualTo("DELETE");
            assertThat(event.getMemoryId()).isEqualTo("memory-1");
            assertThat(event.getMemoryVersion()).isEqualTo(3L);
            assertThat(event.getMemoryGeneration()).isEqualTo(4L);
        });
    }

    private static UserMemoryEntity expiredMemory() {
        UserMemoryEntity memory = new UserMemoryEntity();
        memory.setId(9L);
        memory.setMemoryId("memory-1");
        memory.setTenantId(1L);
        memory.setUserId(2L);
        memory.setMemoryGeneration(4L);
        memory.setVersion(3L);
        return memory;
    }
}
