package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.ImplicitMemoryExtractionBatch;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import com.xjjk.agent.memory.domain.ValidatedMemoryFact;
import com.xjjk.agent.memory.persistence.entity.MemoryExtractionTaskEntity;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryExtractionTaskMapper;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryCommitServiceTest {

    private final MemoryExtractionTaskMapper taskMapper = mock(MemoryExtractionTaskMapper.class);
    private final UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
    private final UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
    private final MemorySuppressionMapper suppressionMapper = mock(MemorySuppressionMapper.class);
    private final MemoryOutboxMapper outboxMapper = mock(MemoryOutboxMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T14:00:00Z"), ZoneOffset.UTC);
    private final MemoryExtractionTaskClaim claim = new MemoryExtractionTaskClaim(
            10L, "task-1", 1L, 2L, "conversation-1", "request-1", "user-1",
            17L, 4L, 0, "lease-1", "node-1",
            LocalDateTime.parse("2026-09-11T14:01:00"));

    @Test
    void writesHiddenExpiringMemoryAndOutboxThenCompletesLease() {
        prepareEnabledLease();
        when(suppressionMapper.existsOwnedActive(1L, 2L, 4L, "work.common_scope"))
                .thenReturn(false);
        when(memoryMapper.selectActiveByKeyForUpdate(1L, 2L, 4L, "work.common_scope"))
                .thenReturn(null);
        when(memoryMapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(1);
        when(taskMapper.completeLease(
                eq(10L), eq("lease-1"), eq("node-1"),
                eq("SAVED"), eq(1), eq(1), eq(1), any())).thenReturn(1);

        int saved = service().commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(candidate(0.91))));

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<UserMemoryEntity> memory = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryMapper).insert(memory.capture());
        assertThat(memory.getValue()).satisfies(row -> {
            assertThat(row.getSourceType()).isEqualTo("AUTO_EXTRACT");
            assertThat(row.getVisibility()).isEqualTo("HIDDEN");
            assertThat(row.getRetentionType()).isEqualTo("NORMAL");
            assertThat(row.getConfidence()).isEqualByComparingTo("0.9100");
            assertThat(row.getExpiresAt()).isEqualTo(LocalDateTime.parse("2027-03-10T14:00:00"));
            assertThat(row.getSourceConversationId()).isEqualTo("conversation-1");
            assertThat(row.getSourceMessageSequence()).isEqualTo(17L);
            assertThat(row.getSchemaVersion()).isEqualTo(3);
            assertThat(row.getMemoryType()).isEqualTo("WORK_CONTEXT");
            assertThat(row.getPredicateName()).isEqualTo("primary_programming_language");
            assertThat(row.getValueJson()).isEqualTo("\"Java\"");
            assertThat(row.getStability()).isEqualTo("STABLE");
            assertThat(row.getVerificationMethod()).isEqualTo("DETERMINISTIC");
            assertThat(row.getObservedAt()).isEqualTo("2026-09-11T13:55:00");
            assertThat(row.getValidFrom()).isEqualTo("2026-09-11T13:55:00");
            assertThat(row.getValidTo()).isNull();
            assertThat(row.getTemporalScope()).isEqualTo("CURRENT");
        });
        verify(taskMapper).completeLease(
                eq(10L), eq("lease-1"), eq("node-1"),
                eq("SAVED"), eq(1), eq(1), eq(1), any());
    }

    @Test
    void appendsHistoricalFactWithoutClosingCurrentSlot() {
        prepareEnabledLease();
        ValidatedMemoryFact historical = candidate(
                "work.occupation.history.0123456789abcdef0123456789abcdef",
                MemoryTemporalScope.HISTORICAL);
        when(suppressionMapper.existsOwnedActive(
                1L, 2L, 4L, historical.canonicalKey())).thenReturn(false);
        when(memoryMapper.selectActiveByKeyForUpdate(
                1L, 2L, 4L, historical.canonicalKey())).thenReturn(null);
        when(memoryMapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        when(outboxMapper.insert(any(MemoryOutboxEntity.class))).thenReturn(1);
        when(taskMapper.completeLease(eq(10L), eq("lease-1"), eq("node-1"),
                eq("SAVED"), eq(1), eq(1), eq(1), any())).thenReturn(1);

        service().commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(historical)));

        ArgumentCaptor<UserMemoryEntity> inserted =
                ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTemporalScope()).isEqualTo("HISTORICAL");
        assertThat(inserted.getValue().getObservedAt()).isEqualTo("2026-09-11T13:55:00");
        assertThat(inserted.getValue().getValidFrom()).isNull();
        verify(memoryMapper, never()).closeOwnedCurrentFact(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void ignoresAnOlderCurrentFactThatFinishesAfterANewerOne() {
        prepareEnabledLease();
        UserMemoryEntity newer = new UserMemoryEntity();
        newer.setMemoryId("newer-memory");
        newer.setVersion(2L);
        newer.setSourceType("AUTO_EXTRACT");
        newer.setSchemaVersion(3);
        newer.setTemporalScope("CURRENT");
        newer.setObservedAt(LocalDateTime.parse("2026-09-11T13:56:00"));
        when(suppressionMapper.existsOwnedActive(1L, 2L, 4L, "work.common_scope"))
                .thenReturn(false);
        when(memoryMapper.selectActiveByKeyForUpdate(1L, 2L, 4L, "work.common_scope"))
                .thenReturn(newer);
        when(taskMapper.completeLease(eq(10L), eq("lease-1"), eq("node-1"),
                eq("NO_CHANGE"), eq(1), eq(1), eq(0), any())).thenReturn(1);

        assertThat(service().commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(candidate(0.91)))))
                .isZero();

        verify(memoryMapper, never()).insert(any(UserMemoryEntity.class));
        verify(memoryMapper, never()).closeOwnedCurrentFact(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any());
        verify(outboxMapper, never()).insert(any(MemoryOutboxEntity.class));
    }

    @Test
    void explicitMemoryAndSuppressionBothPreventAutomaticReplacement() {
        prepareEnabledLease();
        UserMemoryEntity explicit = new UserMemoryEntity();
        explicit.setSourceType("USER_EXPLICIT");
        when(suppressionMapper.existsOwnedActive(1L, 2L, 4L, "work.common_scope"))
                .thenReturn(false, true);
        when(memoryMapper.selectActiveByKeyForUpdate(1L, 2L, 4L, "work.common_scope"))
                .thenReturn(explicit);
        when(taskMapper.completeLease(
                eq(10L), eq("lease-1"), eq("node-1"),
                eq("NO_CHANGE"), eq(1), eq(1), eq(0), any())).thenReturn(1);

        assertThat(service().commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(candidate(0.91))))).isZero();
        assertThat(service().commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(candidate(0.91))))).isZero();

        verify(memoryMapper, never()).insert(any(UserMemoryEntity.class));
        verify(outboxMapper, never()).insert(any(MemoryOutboxEntity.class));
        verify(taskMapper, times(2)).completeLease(
                eq(10L), eq("lease-1"), eq("node-1"),
                eq("NO_CHANGE"), eq(1), eq(1), eq(0), any());
    }

    @Test
    void staleGenerationCancelsLeaseWithoutWriting() {
        when(taskMapper.selectLeaseForUpdate(10L, "lease-1", "node-1"))
                .thenReturn(taskEntity());
        UserMemorySettingEntity setting = enabledSetting();
        setting.setMemoryGeneration(5L);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);
        when(taskMapper.cancelLease(eq(10L), eq("lease-1"), eq("node-1"),
                eq("STALE_GENERATION"), any()))
                .thenReturn(1);

        assertThat(service().commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(candidate(0.91))))).isZero();

        verify(memoryMapper, never()).insert(any(UserMemoryEntity.class));
        verify(taskMapper).cancelLease(
                eq(10L), eq("lease-1"), eq("node-1"), eq("STALE_GENERATION"), any());
    }

    private void prepareEnabledLease() {
        when(taskMapper.selectLeaseForUpdate(10L, "lease-1", "node-1"))
                .thenReturn(taskEntity());
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(enabledSetting());
        AgentMessageEntity source = new AgentMessageEntity();
        source.setCreatedAt(LocalDateTime.parse("2026-09-11T13:55:00"));
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(source);
    }

    private ImplicitMemoryCommitService service() {
        return new ImplicitMemoryCommitService(
                taskMapper, settingMapper, memoryMapper, suppressionMapper, outboxMapper,
                messageMapper,
                properties(), clock);
    }

    private static ValidatedMemoryFact candidate(double confidence) {
        MemoryFactCandidate raw = new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Java", "Java", "我平时用 Java 开发", MemoryStability.STABLE, confidence);
        return new ValidatedMemoryFact(raw, "work.common_scope",
                "用户主要使用 Java 进行开发", "\"Java\"", "WORK_COMMON_SCOPE",
                "DETERMINISTIC");
    }

    private static ValidatedMemoryFact candidate(
            String canonicalKey, MemoryTemporalScope scope) {
        MemoryFactCandidate raw = new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT, "occupation", "Java开发", "Java开发",
                "我以前是Java开发", MemoryStability.TIME_BOUND, scope, 0.95);
        return new ValidatedMemoryFact(raw, canonicalKey,
                "用户过去的职业是Java开发", "\"Java开发\"", "WORK_COMMON_SCOPE",
                "SEMANTIC_MODEL", scope);
    }

    private MemoryExtractionTaskEntity taskEntity() {
        MemoryExtractionTaskEntity task = new MemoryExtractionTaskEntity();
        task.setId(claim.id());
        task.setTaskId(claim.taskId());
        task.setTenantId(claim.tenantId());
        task.setUserId(claim.userId());
        task.setConversationId(claim.conversationId());
        task.setRequestId(claim.requestId());
        task.setUserMessageId(claim.userMessageId());
        task.setUserMessageSequence(claim.userMessageSequence());
        task.setMemoryGeneration(claim.memoryGeneration());
        task.setRetryCount(claim.retryCount());
        return task;
    }

    private static UserMemorySettingEntity enabledSetting() {
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryEnabled(true);
        setting.setAutoExtractEnabled(true);
        setting.setMemoryGeneration(4L);
        return setting;
    }

    private static ImplicitMemoryProperties properties() {
        return new ImplicitMemoryProperties(
                0.85, 180, 3, "memory-auto-v1", "qwen-plus", 0.0,
                Duration.ofSeconds(10), new ImplicitMemoryProperties.Executor(1, 10),
                new ImplicitMemoryProperties.Worker(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), 10,
                        Duration.ofSeconds(60), 5, Duration.ofSeconds(2),
                        Duration.ofMinutes(5), new ImplicitMemoryProperties.Executor(1, 10)),
                new ImplicitMemoryProperties.Expiry(Duration.ofMinutes(10), 100));
    }
}
