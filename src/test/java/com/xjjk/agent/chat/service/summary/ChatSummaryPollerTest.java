package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTaskClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryPollerTest {

    @Mock
    private ChatSummaryCommitService commitService;
    @Mock
    private ChatSummaryTaskWorker worker;
    @Mock
    private ThreadPoolTaskExecutor executor;

    @Test
    void shouldNotTouchDatabaseWhenSummaryDisabled() {
        ChatSummaryProperties disabled = disabledProperties();
        new ChatSummaryPoller(commitService, worker, executor, disabled)
                .poll();

        verify(commitService, never()).claimAvailable(
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldReleaseClaimToRetryWhenExecutorRejects() {
        ChatSummaryTaskClaim claim = ChatSummaryCommitServiceTest.claimForTests();
        when(commitService.claimAvailable(2)).thenReturn(List.of(claim));
        doThrow(new TaskRejectedException("full"))
                .when(executor).execute(any(Runnable.class));

        new ChatSummaryPoller(
                commitService, worker, executor,
                ChatSummaryGeneratorTest.propertiesForSummaryTests()
        ).poll();

        verify(commitService).scheduleRetry(claim, "EXECUTOR_REJECTED");
    }

    private static ChatSummaryProperties disabledProperties() {
        ChatSummaryProperties source =
                ChatSummaryGeneratorTest.propertiesForSummaryTests();
        return new ChatSummaryProperties(
                false, source.shadowMode(), false, source.schemaVersion(),
                source.triggerTokens(), source.triggerTurns(),
                source.retainRecentTurns(), source.rawTailMaxTokens(),
                source.maxBatchMessages(), source.maxBatchBytes(),
                source.maxBatchTokens(), source.targetOutputTokens(),
                source.maxOutputTokens(), source.contextMaxTokens(),
                source.promptVersion(), source.model(), source.temperature(),
                source.timeout(), source.worker(), source.retry()
        );
    }
}
