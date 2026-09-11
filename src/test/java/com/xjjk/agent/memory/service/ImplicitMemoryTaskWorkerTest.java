package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryTaskWorkerTest {

    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final ImplicitMemoryModelClient modelClient = mock(ImplicitMemoryModelClient.class);
    private final ImplicitMemoryCandidateValidator validator = mock(ImplicitMemoryCandidateValidator.class);
    private final ImplicitMemoryCommitService commitService = mock(ImplicitMemoryCommitService.class);
    private final ImplicitMemoryTaskCommitService taskState = mock(ImplicitMemoryTaskCommitService.class);
    private final UserMemoryMetrics metrics = mock(UserMemoryMetrics.class);
    private final MemoryExtractionTaskClaim claim = new MemoryExtractionTaskClaim(
            10L, "task-1", 1L, 2L, "conversation-1", "request-1", "user-1",
            17L, 4L, 0, "lease-1", "node-1",
            LocalDateTime.parse("2026-09-11T14:01:00"));

    @Test
    void loadsAuthoritativeMessageAndCommitsOnlyValidatedCandidates() {
        AgentMessageEntity source = source("我是Java开发，希望回答简短一些");
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(source);
        when(messageMapper.selectPreviousSuccessfulUserContent(
                1L, 2L, "conversation-1", 17L)).thenReturn("上一条用户消息");
        ImplicitMemoryCandidate safe = candidate("work.common_scope", 0.93);
        ImplicitMemoryCandidate rejected = candidate("preference.answer_style", 0.40);
        when(modelClient.extract(any())).thenReturn(List.of(safe, rejected));
        when(validator.validate(safe, source.getContent())).thenReturn(safe);
        when(validator.validate(rejected, source.getContent()))
                .thenThrow(new IllegalArgumentException("MEMORY_CONTENT_REJECTED"));
        when(commitService.commit(claim, List.of(safe))).thenReturn(1);

        worker().process(claim);

        verify(commitService).commit(claim, List.of(safe));
        verify(metrics).success("auto_extract", 1);
        verify(taskState, never()).scheduleRetry(any(), any());
    }

    @Test
    void cancelsWhenAuthoritativeSourceNoLongerExists() {
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(null);

        worker().process(claim);

        verify(taskState).cancel(claim, "SOURCE_NOT_AVAILABLE");
        verify(modelClient, never()).extract(any());
    }

    @Test
    void retriesTransientModelFailureWithStableCode() {
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(source("我是Java开发"));
        when(modelClient.extract(any())).thenThrow(new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_TIMEOUT, "safe"));

        worker().process(claim);

        verify(taskState).scheduleRetry(claim, "MODEL_TIMEOUT");
        verify(commitService, never()).commit(any(), any());
    }

    @Test
    void malformedModelOutputCompletesWithoutMemory() {
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(source("我是Java开发"));
        when(modelClient.extract(any())).thenThrow(new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR, "safe"));

        worker().process(claim);

        verify(commitService).commit(claim, List.of());
        verify(metrics).success("auto_extract", 0);
        verify(taskState, never()).scheduleRetry(any(), any());
    }

    private ImplicitMemoryTaskWorker worker() {
        return new ImplicitMemoryTaskWorker(
                messageMapper, modelClient, validator, commitService, taskState, metrics);
    }

    private AgentMessageEntity source(String content) {
        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setContent(content);
        return entity;
    }

    private static ImplicitMemoryCandidate candidate(String key, double confidence) {
        return new ImplicitMemoryCandidate(
                key.startsWith("work") ? MemoryCategory.WORK_COMMON_SCOPE
                        : MemoryCategory.PREFERENCE_ANSWER_STYLE,
                key, "内容", "证据", confidence);
    }
}
