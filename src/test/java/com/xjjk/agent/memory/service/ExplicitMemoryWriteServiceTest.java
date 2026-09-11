package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExplicitMemoryWriteServiceTest {

    private final UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
    private final UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
    private final MemorySuppressionMapper suppressionMapper = mock(MemorySuppressionMapper.class);
    private final MemoryOutboxMapper outboxMapper = mock(MemoryOutboxMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private ExplicitMemoryWriteService service;

    @BeforeEach
    void setUp() {
        service = new ExplicitMemoryWriteService(
                settingMapper, memoryMapper, suppressionMapper, outboxMapper,
                messageMapper, properties(), new UserMemoryPolicyService(settingMapper), Clock.fixed(
                        Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC));
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryGeneration(7L);
        setting.setMemoryEnabled(true);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);
        when(messageMapper.selectOwnedUserMessageSequence(1L, 2L, "conversation", "user-message"))
                .thenReturn(11L);
        when(memoryMapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(1);
    }

    @Test
    void savesExplicitMemoryAndOutboxInTheSameTransactionBoundary() {
        UserMemoryEntity existing = new UserMemoryEntity();
        existing.setMemoryId("old-memory");
        existing.setVersion(3L);
        when(memoryMapper.selectActiveByKeyForUpdate(1L, 2L, 7L, "preference.answer_style"))
                .thenReturn(existing);
        when(memoryMapper.supersedeOwnedActive(eq(1L), eq(2L), eq(7L),
                eq("preference.answer_style"), any())).thenReturn(1);

        ExplicitMemoryWriteService.SaveResult result = service.save(turn(), candidate());

        ArgumentCaptor<UserMemoryEntity> memory = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryMapper).insert(memory.capture());
        assertThat(memory.getValue().getTenantId()).isEqualTo(1L);
        assertThat(memory.getValue().getUserId()).isEqualTo(2L);
        assertThat(memory.getValue().getMemoryGeneration()).isEqualTo(7L);
        assertThat(memory.getValue().getSourceType()).isEqualTo("USER_EXPLICIT");
        assertThat(memory.getValue().getVisibility()).isEqualTo("VISIBLE");
        assertThat(memory.getValue().getConfidence()).isEqualByComparingTo("1.0000");
        assertThat(memory.getValue().getVersion()).isEqualTo(4L);
        assertThat(memory.getValue().getSourceMessageSequence()).isEqualTo(11L);
        assertThat(memory.getValue().getExpiresAt()).isEqualTo("2027-09-11T08:00:00");
        assertThat(memory.getValue().getContentHash()).hasSize(64);

        ArgumentCaptor<MemoryOutboxEntity> outbox = ArgumentCaptor.forClass(MemoryOutboxEntity.class);
        verify(outboxMapper, org.mockito.Mockito.times(2)).insert(outbox.capture());
        List<MemoryOutboxEntity> events = outbox.getAllValues();
        assertThat(events).extracting(MemoryOutboxEntity::getOperation)
                .containsExactly("DELETE", "UPSERT");
        assertThat(events.get(0).getMemoryId()).isEqualTo("old-memory");
        assertThat(events.get(0).getMemoryVersion()).isEqualTo(3L);
        assertThat(events.get(1).getMemoryId()).isEqualTo(memory.getValue().getMemoryId());
        assertThat(events.get(1).getMemoryVersion()).isEqualTo(4L);
        assertThat(events.get(1).getStatus()).isEqualTo("PENDING");
        assertThat(result.memoryId()).isEqualTo(memory.getValue().getMemoryId());
        assertThat(result.content()).isEqualTo("用户偏好简洁回答");

        verify(settingMapper).insertIfAbsent(eq(1L), eq(2L), eq(true), eq(true), any());
        verify(memoryMapper).supersedeOwnedActive(eq(1L), eq(2L), eq(7L),
                eq("preference.answer_style"), any());
        verify(suppressionMapper).liftOwnedActive(eq(1L), eq(2L), eq(7L),
                eq("preference.answer_style"), any());
    }

    @Test
    void failsClosedWhenMemoryOrOutboxInsertDoesNotAffectExactlyOneRow() {
        when(memoryMapper.insert(any(UserMemoryEntity.class))).thenReturn(0);
        assertWriteFailed();

        when(memoryMapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(0);
        assertWriteFailed();
    }

    @Test
    void rejectsAfterLockWhenTheMasterSwitchWasClosedAfterThePrecheck() {
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryGeneration(7L);
        setting.setMemoryEnabled(false);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);

        assertThatThrownBy(() -> service.save(turn(), candidate()))
                .isInstanceOf(UserMemoryDisabledException.class);
        verifyNoInteractions(memoryMapper, suppressionMapper, outboxMapper, messageMapper);
    }

    private void assertWriteFailed() {
        assertThatThrownBy(() -> service.save(turn(), candidate()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MEMORY_WRITE_FAILED));
    }

    private ChatTurnContext turn() {
        return new ChatTurnContext(1L, 2L, "conversation", "request",
                "user-message", "assistant-message", "prompt-v1");
    }

    private ExplicitMemoryCandidate candidate() {
        return new ExplicitMemoryCandidate(
                MemoryCategory.PREFERENCE_ANSWER_STYLE,
                "preference.answer_style",
                "用户偏好简洁回答",
                "以后回答简短一些",
                MemoryRetentionType.NORMAL);
    }

    private UserMemoryProperties properties() {
        return new UserMemoryProperties(true, true, 256, 512, 512, 50, 365,
                "memory-test-v1", "qwen-plus", 0.1, Duration.ofSeconds(1), 1, 10);
    }
}
