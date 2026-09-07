package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationMapper;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryRecoverySchedulerTest {

    @Mock
    private AgentSummaryTaskMapper taskMapper;
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private ChatSummaryTaskScheduler taskScheduler;

    @Test
    void shouldRecoverLeaseThenRepairMissingTarget() {
        AgentConversationEntity conversation = new AgentConversationEntity();
        conversation.setTenantId(1L);
        conversation.setUserId(10567L);
        conversation.setConversationId("conversation-1");
        conversation.setMemoryVersion(3L);
        conversation.setMemoryUntilSequence(6L);
        when(conversationMapper.selectSummaryRepairCandidates(10))
                .thenReturn(List.of(conversation));

        ChatSummaryRecoveryScheduler scheduler = scheduler(true);
        scheduler.recover();

        verify(taskMapper).recoverExpiredLeases(
                any(),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(10)
        );
        verify(taskScheduler).repairStableHistory(
                1, 10567, "conversation-1", 3, 6
        );
    }

    @Test
    void shouldDoNothingWhenDisabled() {
        scheduler(false).recover();

        verify(taskMapper, never()).recoverExpiredLeases(
                any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(conversationMapper, never())
                .selectSummaryRepairCandidates(
                        org.mockito.ArgumentMatchers.anyInt());
    }

    private ChatSummaryRecoveryScheduler scheduler(boolean enabled) {
        var source = ChatSummaryGeneratorTest.propertiesForSummaryTests();
        var properties = enabled ? source
                : new com.xjjk.agent.chat.config.ChatSummaryProperties(
                false, source.shadowMode(), false, source.schemaVersion(),
                source.triggerTokens(), source.triggerTurns(),
                source.retainRecentTurns(), source.rawTailMaxTokens(),
                source.maxBatchMessages(), source.maxBatchBytes(),
                source.maxBatchTokens(), source.targetOutputTokens(),
                source.maxOutputTokens(), source.contextMaxTokens(),
                source.promptVersion(), source.model(), source.temperature(),
                source.timeout(), source.worker(), source.retry()
        );
        return new ChatSummaryRecoveryScheduler(
                taskMapper, conversationMapper, taskScheduler, properties,
                Clock.fixed(
                        Instant.parse("2026-09-06T10:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }
}
