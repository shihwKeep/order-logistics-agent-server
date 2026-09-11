package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryClearAllTest {

    private final UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
    private final UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
    private final MemorySuppressionMapper suppressionMapper = mock(MemorySuppressionMapper.class);
    private final MemoryOutboxMapper outboxMapper = mock(MemoryOutboxMapper.class);
    private final AgentIdentity identity = new AgentIdentity(2L, "account", "name", 3L, 1L);
    private UserMemoryManagementService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryManagementService(settingMapper, memoryMapper,
                suppressionMapper, outboxMapper,
                new MemorySensitiveContentPolicy(new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                new MemoryCategoryContentPolicy(),
                properties(), new UserMemoryMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC));
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryGeneration(7L);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);
        when(memoryMapper.clearOwnedGeneration(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"))).thenReturn(4);
        when(settingMapper.compareAndIncrementGeneration(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"))).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(1);
    }

    @Test
    void invalidatesOldGenerationInStrictOrder() {
        var result = service.clearAll(identity);

        assertThat(result.affectedCount()).isEqualTo(4);
        assertThat(result.generation()).isEqualTo(8L);
        InOrder ordered = inOrder(settingMapper, memoryMapper, suppressionMapper, outboxMapper);
        ordered.verify(settingMapper).selectOwnedForUpdate(1L, 2L);
        ordered.verify(memoryMapper).clearOwnedGeneration(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"));
        ordered.verify(suppressionMapper).liftOwnedGeneration(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"));
        ordered.verify(settingMapper).compareAndIncrementGeneration(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"));
        ArgumentCaptor<MemoryOutboxEntity> event = ArgumentCaptor.forClass(MemoryOutboxEntity.class);
        ordered.verify(outboxMapper).insert(event.capture());
        assertThat(event.getValue().getOperation()).isEqualTo("CLEAR_GENERATION");
        assertThat(event.getValue().getMemoryGeneration()).isEqualTo(7L);
    }

    @Test
    void casFailureRollsBackWithStableError() {
        when(settingMapper.compareAndIncrementGeneration(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"))).thenReturn(0);
        assertThatThrownBy(() -> service.clearAll(identity))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MEMORY_CLEAR_FAILED));
    }

    private UserMemoryProperties properties() {
        return new UserMemoryProperties(true, true, 256, 512, 512, 50, 365,
                "memory-test-v1", "qwen-plus", 0.1, Duration.ofSeconds(1), 1, 10);
    }
}
