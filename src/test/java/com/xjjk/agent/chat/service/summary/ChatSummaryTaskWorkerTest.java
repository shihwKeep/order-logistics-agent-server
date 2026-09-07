package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryDraft;
import com.xjjk.agent.chat.domain.summary.ChatSummaryGenerationException;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTaskClaim;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTrigger;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTriggerReason;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryTaskWorkerTest {

    @Mock
    private ChatSummaryCandidateLoader candidateLoader;
    @Mock
    private ChatSummaryTriggerPolicy triggerPolicy;
    @Mock
    private ChatSummaryGenerator generator;
    @Mock
    private ChatSummaryCommitService commitService;
    @Mock
    private AgentConversationSummaryMapper summaryMapper;

    private ChatSummaryTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ChatSummaryTaskWorker(
                new ObjectMapper(), summaryMapper, candidateLoader,
                triggerPolicy, generator, commitService,
                ChatSummaryGeneratorTest.propertiesForSummaryTests()
        );
    }

    @Test
    void shouldCompleteEvaluationWithoutCallingModelWhenThresholdNotMet() {
        ChatSummaryTaskClaim claim = ChatSummaryCommitServiceTest.claimForTests();
        ChatSummaryCandidateBatch batch = ChatSummaryCommitServiceTest.batchForTests();
        when(summaryMapper.selectOwned(1, 10567, "conversation-1"))
                .thenReturn(null);
        when(candidateLoader.load(1, 10567, "conversation-1", 0, 1, 2))
                .thenReturn(batch);
        when(triggerPolicy.evaluate(batch, false, false))
                .thenReturn(ChatSummaryTrigger.none());

        worker.process(claim);

        verify(commitService).completeWithoutGeneration(claim);
        verify(generator, never()).generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void shouldGenerateAndCommitTriggeredSummary() {
        ChatSummaryTaskClaim claim = ChatSummaryCommitServiceTest.claimForTests();
        ChatSummaryCandidateBatch batch = ChatSummaryCommitServiceTest.batchForTests();
        ChatSummaryDraft draft = ChatSummaryCommitServiceTest.draftForTests();
        when(summaryMapper.selectOwned(1, 10567, "conversation-1"))
                .thenReturn(null);
        when(candidateLoader.load(1, 10567, "conversation-1", 0, 1, 2))
                .thenReturn(batch);
        when(triggerPolicy.evaluate(batch, false, false))
                .thenReturn(ChatSummaryTrigger.generate(
                        ChatSummaryTriggerReason.TURN_THRESHOLD));
        when(generator.generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(batch)
        )).thenReturn(draft);

        worker.process(claim);

        verify(commitService).commitSummary(claim, batch, draft);
    }

    @Test
    void shouldScheduleSafeRetryAfterGenerationFailure() {
        ChatSummaryTaskClaim claim = ChatSummaryCommitServiceTest.claimForTests();
        ChatSummaryCandidateBatch batch = ChatSummaryCommitServiceTest.batchForTests();
        when(summaryMapper.selectOwned(1, 10567, "conversation-1"))
                .thenReturn(null);
        when(candidateLoader.load(1, 10567, "conversation-1", 0, 1, 2))
                .thenReturn(batch);
        when(triggerPolicy.evaluate(batch, false, false))
                .thenReturn(ChatSummaryTrigger.generate(
                        ChatSummaryTriggerReason.TURN_THRESHOLD));
        when(generator.generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(batch)
        )).thenThrow(new ChatSummaryGenerationException(
                ChatSummaryGenerationException.Code.MODEL_TIMEOUT,
                "safe"
        ));

        worker.process(claim);

        verify(commitService).scheduleRetry(claim, "MODEL_TIMEOUT");
        verify(commitService, never()).completeWithoutGeneration(claim);
    }

    @Test
    void shouldDeadLetterOversizedFirstTurnWithoutAdvancingEvaluation() {
        ChatSummaryTaskClaim claim = ChatSummaryCommitServiceTest.claimForTests();
        ChatSummaryCandidateBatch blocked = new ChatSummaryCandidateBatch(
                1, 10567, "conversation-1",
                0, 1, 2,
                java.util.List.of(), 0, 0,
                0, 0,
                false, true, false, true
        );
        when(summaryMapper.selectOwned(1, 10567, "conversation-1"))
                .thenReturn(null);
        when(candidateLoader.load(1, 10567, "conversation-1", 0, 1, 2))
                .thenReturn(blocked);

        worker.process(claim);

        verify(commitService).markDead(
                claim, "SUMMARY_CANDIDATE_TOO_LARGE"
        );
        verify(commitService, never()).completeWithoutGeneration(claim);
        verify(generator, never()).generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
