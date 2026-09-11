package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.MemorySuppressionEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryManagementServiceTest {

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
                properties(), Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC));
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryGeneration(7L);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);
        when(memoryMapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        when(memoryMapper.supersedeOwnedActive(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(1);
        when(suppressionMapper.insertOwned(any(MemorySuppressionEntity.class))).thenReturn(1);
    }

    @Test
    void editCreatesNewVersionAndUpsertOutbox() {
        UserMemoryEntity current = explicit("memory-1", "旧内容", 3L);
        when(memoryMapper.selectOwnedVisibleExplicitForUpdate(1L, 2L, 7L, "memory-1"))
                .thenReturn(current);

        var result = service.edit(identity, "memory-1", "用户偏好详细回答", MemoryRetentionType.PERMANENT);

        ArgumentCaptor<UserMemoryEntity> inserted = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getVersion()).isEqualTo(4L);
        assertThat(inserted.getValue().getContent()).isEqualTo("用户偏好详细回答");
        assertThat(inserted.getValue().getExpiresAt()).isNull();
        assertThat(result.memoryId()).isEqualTo(inserted.getValue().getMemoryId());
        ArgumentCaptor<MemoryOutboxEntity> outbox = ArgumentCaptor.forClass(MemoryOutboxEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().getOperation()).isEqualTo("UPSERT");
    }

    @Test
    void wrongOwnerOrNonVisibleMemoryIsIndistinguishableFromMissing() {
        when(memoryMapper.selectOwnedVisibleExplicitForUpdate(1L, 2L, 7L, "missing"))
                .thenReturn(null);
        assertThatThrownBy(() -> service.delete(identity, "missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MEMORY_NOT_FOUND));
    }

    @Test
    void deleteCreatesSuppressionAndDeleteOutbox() {
        UserMemoryEntity current = explicit("memory-1", "用户偏好简洁回答", 3L);
        when(memoryMapper.selectOwnedVisibleExplicitForUpdate(1L, 2L, 7L, "memory-1"))
                .thenReturn(current);
        when(memoryMapper.softDeleteOwned(any(Long.class), any(Long.class), any(Long.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        assertThat(service.delete(identity, "memory-1").affectedCount()).isEqualTo(1);

        ArgumentCaptor<MemorySuppressionEntity> suppression = ArgumentCaptor.forClass(MemorySuppressionEntity.class);
        verify(suppressionMapper).insertOwned(suppression.capture());
        assertThat(suppression.getValue().getCanonicalKey()).isEqualTo("preference.answer_style");
        ArgumentCaptor<MemoryOutboxEntity> outbox = ArgumentCaptor.forClass(MemoryOutboxEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().getOperation()).isEqualTo("DELETE");
    }

    @Test
    void explicitClearSuppressesVisibleKeysAndEmitsOneScopeOutbox() {
        when(memoryMapper.selectOwnedVisibleExplicitForUpdate(1L, 2L, 7L))
                .thenReturn(List.of(explicit("m1", "a", 1L), explicit("m2", "b", 1L)));
        when(memoryMapper.clearOwnedExplicit(1L, 2L, 7L,
                LocalDateTime.parse("2026-09-11T08:00:00"))).thenReturn(2);

        assertThat(service.clearExplicit(identity).affectedCount()).isEqualTo(2);

        verify(suppressionMapper, org.mockito.Mockito.times(2))
                .insertOwned(any(MemorySuppressionEntity.class));
        ArgumentCaptor<MemoryOutboxEntity> outbox = ArgumentCaptor.forClass(MemoryOutboxEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().getOperation()).isEqualTo("DELETE_EXPLICIT_SCOPE");
        assertThat(outbox.getValue().getMemoryId()).isNull();
    }

    private UserMemoryEntity explicit(String memoryId, String content, long version) {
        UserMemoryEntity entity = new UserMemoryEntity();
        entity.setMemoryId(memoryId);
        entity.setTenantId(1L);
        entity.setUserId(2L);
        entity.setMemoryGeneration(7L);
        entity.setSourceType("USER_EXPLICIT");
        entity.setVisibility("VISIBLE");
        entity.setStatus("ACTIVE");
        entity.setCategory("PREFERENCE_ANSWER_STYLE");
        entity.setCanonicalKey("preference.answer_style");
        entity.setContent(content);
        entity.setContentHash(MemoryHashing.sha256(content));
        entity.setRetentionType("NORMAL");
        entity.setSourceConversationId("conversation");
        entity.setSourceMessageSequence(1L);
        entity.setEvidenceText(content);
        entity.setVersion(version);
        return entity;
    }

    private UserMemoryProperties properties() {
        return new UserMemoryProperties(true, true, 256, 512, 512, 50, 365,
                "memory-test-v1", "qwen-plus", 0.1, Duration.ofSeconds(1), 1, 10);
    }
}
