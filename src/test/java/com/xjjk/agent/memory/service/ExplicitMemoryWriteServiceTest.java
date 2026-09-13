package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                messageMapper, properties(), new UserMemoryPolicyService(settingMapper),
                new MemorySchemaRegistry(new ObjectMapper()), Clock.fixed(
                        Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC));
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryGeneration(7L);
        setting.setMemoryEnabled(true);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);
        when(messageMapper.selectOwnedUserMessageSequence(1L, 2L, "conversation", "user-message"))
                .thenReturn(11L);
        when(messageMapper.selectOwnedUserMessageCreatedAt(
                1L, 2L, "conversation", "user-message"))
                .thenReturn(LocalDateTime.parse("2026-09-11T07:55:00"));
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
        existing.setSchemaVersion(3);
        existing.setTemporalScope("CURRENT");
        existing.setValidFrom(LocalDateTime.parse("2026-09-10T08:00:00"));
        when(memoryMapper.closeOwnedCurrentFact(eq(1L), eq(2L), eq(7L),
                eq("preference.answer_style"), eq("old-memory"), eq(3L), any(), any()))
                .thenReturn(1);

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
        assertThat(memory.getValue().getSchemaVersion()).isEqualTo(3);
        assertThat(memory.getValue().getMemoryType()).isEqualTo("RESPONSE_PREFERENCE");
        assertThat(memory.getValue().getPredicateName()).isEqualTo("answer_style");
        assertThat(memory.getValue().getValueJson()).isEqualTo("\"简洁\"");
        assertThat(memory.getValue().getStability()).isEqualTo("STABLE");
        assertThat(memory.getValue().getVerificationMethod())
                .isEqualTo("EXPLICIT_DETERMINISTIC");
        assertThat(memory.getValue().getObservedAt()).isEqualTo("2026-09-11T07:55:00");
        assertThat(memory.getValue().getValidFrom()).isEqualTo("2026-09-11T07:55:00");
        assertThat(memory.getValue().getValidTo()).isNull();
        assertThat(memory.getValue().getTemporalScope()).isEqualTo("CURRENT");

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
        verify(memoryMapper).closeOwnedCurrentFact(eq(1L), eq(2L), eq(7L),
                eq("preference.answer_style"), eq("old-memory"), eq(3L),
                eq(LocalDateTime.parse("2026-09-11T07:55:00")), any());
        verify(suppressionMapper).liftOwnedActive(eq(1L), eq(2L), eq(7L),
                eq("preference.answer_style"), any());
    }

    @Test
    void appendsHistoricalExplicitFactWithoutClosingCurrentFact() {
        ExplicitMemoryCandidate historical = ExplicitMemoryCandidate.semantic(
                new MemoryFactCandidate(
                        MemoryType.WORK_CONTEXT, "occupation", "Java开发", "Java开发",
                        "我以前是Java开发", MemoryStability.TIME_BOUND,
                        MemoryTemporalScope.HISTORICAL, 0.98),
                MemoryRetentionType.PERMANENT);
        when(memoryMapper.selectActiveByKeyForUpdate(
                eq(1L), eq(2L), eq(7L), org.mockito.ArgumentMatchers.contains(".history.")))
                .thenReturn(null);

        service.save(turn(), historical);

        ArgumentCaptor<UserMemoryEntity> inserted =
                ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getCanonicalKey()).contains(".history.");
        assertThat(inserted.getValue().getTemporalScope()).isEqualTo("HISTORICAL");
        assertThat(inserted.getValue().getObservedAt()).isEqualTo("2026-09-11T07:55:00");
        assertThat(inserted.getValue().getValidFrom()).isNull();
        verify(memoryMapper, never()).closeOwnedCurrentFact(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void ignoresAnOlderCurrentExplicitReplay() {
        UserMemoryEntity newer = new UserMemoryEntity();
        newer.setMemoryId("newer-memory");
        newer.setContent("用户偏好详细回答");
        newer.setVersion(2L);
        newer.setSchemaVersion(3);
        newer.setTemporalScope("CURRENT");
        newer.setObservedAt(LocalDateTime.parse("2026-09-11T07:56:00"));
        when(memoryMapper.selectActiveByKeyForUpdate(
                1L, 2L, 7L, "preference.answer_style")).thenReturn(newer);

        ExplicitMemoryWriteService.SaveResult result = service.save(turn(), candidate());

        assertThat(result).isEqualTo(new ExplicitMemoryWriteService.SaveResult(
                "newer-memory", "用户偏好详细回答"));
        verify(memoryMapper, never()).insert(any(UserMemoryEntity.class));
        verify(outboxMapper, never()).insert(any(MemoryOutboxEntity.class));
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
