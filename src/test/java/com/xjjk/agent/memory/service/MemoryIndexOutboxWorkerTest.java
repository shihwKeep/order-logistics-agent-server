package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.MemoryOutboxClaim;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.index.MemoryIndexCommand;
import com.xjjk.agent.memory.index.MemoryIndexGateway;
import com.xjjk.agent.memory.index.MemoryIndexUnavailableException;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexOutboxWorkerTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-09-12T08:00:00");

    @Test
    void reloadsAndSendsTheLatestAuthoritativeMemoryVersion() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        MemoryIndexGateway gateway = mock(MemoryIndexGateway.class);
        MemoryIndexOutboxStateService state = mock(MemoryIndexOutboxStateService.class);
        when(memoryMapper.selectActiveIndexable(
                7L, 9L, 3L, "memory-1", NOW)).thenReturn(memory(4L));
        MemoryIndexOutboxWorker worker = worker(memoryMapper, gateway, state);
        MemoryOutboxClaim claim = claim(MemoryOutboxOperation.UPSERT);

        worker.process(claim);

        ArgumentCaptor<MemoryIndexCommand> sent =
                ArgumentCaptor.forClass(MemoryIndexCommand.class);
        verify(gateway).apply(sent.capture());
        assertThat(sent.getValue().memoryVersion()).isEqualTo(4L);
        assertThat(sent.getValue().content()).isEqualTo("主要使用 Java");
        verify(state).complete(claim);
    }

    @Test
    void convertsAStaleUpsertToDeleteWhenTruthIsNoLongerActive() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        MemoryIndexGateway gateway = mock(MemoryIndexGateway.class);
        MemoryIndexOutboxStateService state = mock(MemoryIndexOutboxStateService.class);
        MemoryIndexOutboxWorker worker = worker(memoryMapper, gateway, state);
        MemoryOutboxClaim claim = claim(MemoryOutboxOperation.UPSERT);

        worker.process(claim);

        ArgumentCaptor<MemoryIndexCommand> sent =
                ArgumentCaptor.forClass(MemoryIndexCommand.class);
        verify(gateway).apply(sent.capture());
        assertThat(sent.getValue().operation()).isEqualTo(MemoryOutboxOperation.DELETE);
        assertThat(sent.getValue().content()).isNull();
        verify(state).complete(claim);
    }

    @Test
    void schedulesRetryWhenRemoteIndexingFails() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        MemoryIndexGateway gateway = mock(MemoryIndexGateway.class);
        MemoryIndexOutboxStateService state = mock(MemoryIndexOutboxStateService.class);
        MemoryIndexOutboxWorker worker = worker(memoryMapper, gateway, state);
        MemoryOutboxClaim claim = claim(MemoryOutboxOperation.CLEAR_GENERATION);
        doThrow(new MemoryIndexUnavailableException()).when(gateway)
                .apply(org.mockito.ArgumentMatchers.any());

        worker.process(claim);

        verify(state).scheduleRetry(claim, "INDEX_UNAVAILABLE");
    }

    private MemoryIndexOutboxWorker worker(
            UserMemoryMapper mapper, MemoryIndexGateway gateway,
            MemoryIndexOutboxStateService state) {
        return new MemoryIndexOutboxWorker(
                mapper, gateway, state,
                Clock.fixed(Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC));
    }

    private MemoryOutboxClaim claim(MemoryOutboxOperation operation) {
        return new MemoryOutboxClaim(
                5L, "event-1",
                operation == MemoryOutboxOperation.CLEAR_GENERATION ? null : "memory-1",
                7L, 9L, 3L,
                operation == MemoryOutboxOperation.CLEAR_GENERATION ? 0L : 2L,
                operation, 0, "lease-1", "instance-1", NOW.plusSeconds(30));
    }

    private UserMemoryEntity memory(long version) {
        UserMemoryEntity memory = new UserMemoryEntity();
        memory.setMemoryId("memory-1");
        memory.setTenantId(7L);
        memory.setUserId(9L);
        memory.setMemoryGeneration(3L);
        memory.setVersion(version);
        memory.setSourceType("AUTO_EXTRACT");
        memory.setCategory("PROFILE");
        memory.setCanonicalKey("primary_language");
        memory.setContent("主要使用 Java");
        memory.setConfidence(new BigDecimal("0.91"));
        memory.setExpiresAt(LocalDateTime.parse("2027-09-12T08:00:00"));
        return memory;
    }
}
