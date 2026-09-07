package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.domain.summary.ChatSummaryTriggerReason;
import com.xjjk.agent.chat.persistence.mapper.AgentSummaryTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryTaskSchedulerTest {

    @Mock
    private AgentSummaryTaskMapper taskMapper;

    @Test
    void shouldRegisterStableHistoryWithoutForcingGeneration() {
        when(taskMapper.upsertRequestedTarget(
                eq(1L), eq(10567L), eq("conversation-1"),
                eq(8L), eq(16L), eq(false), isNull(),
                any(String.class), any(LocalDateTime.class)
        )).thenReturn(1);
        ChatSummaryTaskScheduler scheduler =
                new ChatSummaryTaskScheduler(taskMapper);

        scheduler.requestStableHistory(
                1L, 10567L, "conversation-1", 8L, 16L
        );

        verify(taskMapper).upsertRequestedTarget(
                eq(1L), eq(10567L), eq("conversation-1"),
                eq(8L), eq(16L), eq(false), isNull(),
                any(String.class), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldForceGenerationAfterContextPressure() {
        when(taskMapper.upsertRequestedTarget(
                eq(1L), eq(10567L), eq("conversation-1"),
                eq(8L), eq(16L), eq(true),
                eq(ChatSummaryTriggerReason.RAW_CONTEXT_PRESSURE.name()),
                any(String.class), any(LocalDateTime.class)
        )).thenReturn(1);
        ChatSummaryTaskScheduler scheduler =
                new ChatSummaryTaskScheduler(taskMapper);

        scheduler.requestContextPressure(
                1L, 10567L, "conversation-1", 8L, 16L
        );

        verify(taskMapper).upsertRequestedTarget(
                eq(1L), eq(10567L), eq("conversation-1"),
                eq(8L), eq(16L), eq(true),
                eq(ChatSummaryTriggerReason.RAW_CONTEXT_PRESSURE.name()),
                any(String.class), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldRejectTargetNotOwnedByConversation() {
        when(taskMapper.upsertRequestedTarget(
                eq(1L), eq(10567L), eq("conversation-1"),
                eq(8L), eq(16L), eq(false), isNull(),
                any(String.class), any(LocalDateTime.class)
        )).thenReturn(0);
        ChatSummaryTaskScheduler scheduler =
                new ChatSummaryTaskScheduler(taskMapper);

        assertThatIllegalStateException().isThrownBy(() ->
                scheduler.requestStableHistory(
                        1L, 10567L, "conversation-1", 8L, 16L
                ));
    }
}
