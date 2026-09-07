package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryDraft;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTaskClaim;
import com.xjjk.agent.chat.persistence.entity.AgentSummaryTaskEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryCommitServiceTest {

    @Mock
    private AgentConversationSummaryMapper summaryMapper;
    @Mock
    private AgentSummaryTaskMapper taskMapper;

    private ChatSummaryCommitService service;

    @BeforeEach
    void setUp() {
        service = new ChatSummaryCommitService(
                new ObjectMapper(), summaryMapper, taskMapper,
                properties(), Clock.fixed(
                Instant.parse("2026-09-06T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void shouldInsertFirstSummaryAndCompleteCapturedTarget() {
        when(taskMapper.selectLeaseForUpdate(7, "lease-1", "agent-1"))
                .thenReturn(leasedTask(1, false));
        when(summaryMapper.selectOwned(1, 10567, "conversation-1"))
                .thenReturn(null);
        when(summaryMapper.insert(any(
                com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity.class
        ))).thenReturn(1);
        when(taskMapper.completeLease(
                7, "lease-1", "agent-1", 1, "IDLE", null,
                Instant.parse("2026-09-06T10:00:00Z")
                        .atOffset(ZoneOffset.UTC).toLocalDateTime()
        )).thenReturn(1);

        service.commitSummary(claimForTests(), batchForTests(), draftForTests());

        ArgumentCaptor<com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity>
                inserted = ArgumentCaptor.forClass(
                com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity.class
        );
        verify(summaryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSummaryVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getCoveredUntilSequence()).isEqualTo(2);
        assertThat(inserted.getValue().getContentJson()).contains("商品咨询");
    }

    @Test
    void shouldPreserveNewerTargetAsPending() {
        when(taskMapper.selectLeaseForUpdate(7, "lease-1", "agent-1"))
                .thenReturn(leasedTask(2, false));
        when(summaryMapper.selectOwned(1, 10567, "conversation-1"))
                .thenReturn(null);
        when(summaryMapper.insert(any(
                com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity.class
        ))).thenReturn(1);
        when(taskMapper.completeLease(
                7, "lease-1", "agent-1", 1, "PENDING", null,
                Instant.parse("2026-09-06T10:00:00Z")
                        .atOffset(ZoneOffset.UTC).toLocalDateTime()
        )).thenReturn(1);

        service.commitSummary(claimForTests(), batchForTests(), draftForTests());

        verify(taskMapper).completeLease(
                7, "lease-1", "agent-1", 1, "PENDING", null,
                Instant.parse("2026-09-06T10:00:00Z")
                        .atOffset(ZoneOffset.UTC).toLocalDateTime()
        );
    }

    @Test
    void shouldRejectStaleLeaseBeforeWritingSummary() {
        when(taskMapper.selectLeaseForUpdate(7, "lease-1", "agent-1"))
                .thenReturn(null);

        assertThatExceptionOfType(ChatSummaryStaleWorkException.class)
                .isThrownBy(() -> service.commitSummary(
                        claimForTests(), batchForTests(), draftForTests()
                ));
        verifyNoInteractions(summaryMapper);
    }

    @Test
    void shouldRejectExpiredLeaseBeforeWritingSummary() {
        AgentSummaryTaskEntity expired = leasedTask(1, false);
        expired.setLockedUntil(LocalDateTime.of(
                2026, 9, 6, 9, 59, 59
        ));
        when(taskMapper.selectLeaseForUpdate(7, "lease-1", "agent-1"))
                .thenReturn(expired);

        assertThatExceptionOfType(ChatSummaryStaleWorkException.class)
                .isThrownBy(() -> service.commitSummary(
                        claimForTests(), batchForTests(), draftForTests()
                ));
        verifyNoInteractions(summaryMapper);
    }

    @Test
    void shouldUseCurrentRetryCountWhenNewTargetResetItDuringProcessing() {
        ChatSummaryTaskClaim oldAttempt = claimWithRetryCount(2);
        when(taskMapper.selectLeaseForUpdate(7, "lease-1", "agent-1"))
                .thenReturn(leasedTask(2, false));
        when(taskMapper.failLease(
                7, "lease-1", "agent-1",
                "RETRY", 1,
                LocalDateTime.of(2026, 9, 6, 10, 0, 1),
                "MODEL_TIMEOUT",
                LocalDateTime.of(2026, 9, 6, 10, 0)
        )).thenReturn(1);

        service.scheduleRetry(oldAttempt, "MODEL_TIMEOUT");

        verify(taskMapper).failLease(
                7, "lease-1", "agent-1",
                "RETRY", 1,
                LocalDateTime.of(2026, 9, 6, 10, 0, 1),
                "MODEL_TIMEOUT",
                LocalDateTime.of(2026, 9, 6, 10, 0)
        );
    }

    @Test
    void shouldMoveRetryableFailureToDeadAtCurrentAttemptLimit() {
        AgentSummaryTaskEntity leased = leasedTask(1, false);
        leased.setRetryCount(2);
        when(taskMapper.selectLeaseForUpdate(7, "lease-1", "agent-1"))
                .thenReturn(leased);
        when(taskMapper.failLease(
                7, "lease-1", "agent-1",
                "DEAD", 3,
                LocalDateTime.of(2026, 9, 6, 10, 0),
                "MODEL_TIMEOUT",
                LocalDateTime.of(2026, 9, 6, 10, 0)
        )).thenReturn(1);

        service.scheduleRetry(claimForTests(), "MODEL_TIMEOUT");

        verify(taskMapper).failLease(
                7, "lease-1", "agent-1",
                "DEAD", 3,
                LocalDateTime.of(2026, 9, 6, 10, 0),
                "MODEL_TIMEOUT",
                LocalDateTime.of(2026, 9, 6, 10, 0)
        );
    }

    static ChatSummaryTaskClaim claimForTests() {
        return claimWithRetryCount(0);
    }

    private static ChatSummaryTaskClaim claimWithRetryCount(int retryCount) {
        return new ChatSummaryTaskClaim(
                7, "task-1", 1, 10567, "conversation-1",
                1, 2, 0,
                false, null, retryCount,
                "lease-1", "agent-1",
                0, 0
        );
    }

    private static AgentSummaryTaskEntity leasedTask(
            long requestedVersion,
            boolean force
    ) {
        AgentSummaryTaskEntity task = new AgentSummaryTaskEntity();
        task.setId(7L);
        task.setRequestedMemoryVersion(requestedVersion);
        task.setRequestedUntilSequence(requestedVersion * 2);
        task.setForceGeneration(force);
        task.setRetryCount(0);
        task.setLockedUntil(LocalDateTime.of(
                2026, 9, 6, 10, 1
        ));
        return task;
    }

    static ChatSummaryCandidateBatch batchForTests() {
        return new ChatSummaryCandidateBatch(
                1, 10567, "conversation-1",
                0, 1, 2,
                List.of(new com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateTurn(
                        "r1", 1, 2, "问题", "回答",
                        com.xjjk.agent.chat.domain.MessageStatus.SUCCESS,
                        12, 8
                )),
                1, 2, 12, 8,
                false, false, false, false
        );
    }

    static ChatSummaryDraft draftForTests() {
        return new ChatSummaryDraft(
                new ChatSummaryContent(
                        1, "商品咨询", "等待查询",
                        List.of(), List.of(), List.of(), List.of()
                ),
                "qwen-plus", "summary-v1",
                10L, 5L, 100, 1
        );
    }

    private static ChatSummaryProperties properties() {
        return ChatSummaryGeneratorTest.propertiesForSummaryTests();
    }
}
