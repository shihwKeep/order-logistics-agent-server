package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.domain.ImplicitMemoryExtractionBatch;
import com.xjjk.agent.memory.domain.MemoryDecision;
import com.xjjk.agent.memory.domain.MemoryExplicitness;
import com.xjjk.agent.memory.domain.MemoryExtractionDecision;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import com.xjjk.agent.memory.domain.ValidatedMemoryFact;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryTaskWorkerTest {

    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final ImplicitMemoryModelClient modelClient = mock(ImplicitMemoryModelClient.class);
    private final ImplicitMemoryCandidateValidator validator = mock(ImplicitMemoryCandidateValidator.class);
    private final MemoryEvidenceVerifier evidenceVerifier = mock(MemoryEvidenceVerifier.class);
    private final ImplicitMemoryCommitService commitService = mock(ImplicitMemoryCommitService.class);
    private final ImplicitMemoryTaskCommitService taskState = mock(ImplicitMemoryTaskCommitService.class);
    private final UserMemoryMetrics metrics = mock(UserMemoryMetrics.class);
    private final MemoryExtractionTaskClaim claim = new MemoryExtractionTaskClaim(
            10L, "task-1", 1L, 2L, "conversation-1", "request-1", "user-1",
            17L, 4L, 0, "lease-1", "node-1",
            LocalDateTime.parse("2026-09-11T14:01:00"));

    @Test
    void commitsDeterministicallyGroundedRegisteredFactWithoutSecondModelCall() {
        String content = "我平时主要用 Java 语言进行开发";
        prepareSource(content);
        MemoryFactCandidate raw = fact(MemoryType.WORK_CONTEXT,
                "primary_programming_language", "Java", content);
        ValidatedMemoryFact validated = validated(raw,
                "work.primary_programming_language", "用户主要使用 Java 进行开发",
                "WORK_COMMON_SCOPE", "DETERMINISTIC");
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(raw)));
        when(validator.validate(raw, content)).thenReturn(validated);
        ImplicitMemoryExtractionBatch batch = ImplicitMemoryExtractionBatch.observed(
                1, List.of(validated));
        when(commitService.commit(claim, batch)).thenReturn(1);

        worker().process(claim);

        verify(evidenceVerifier, never()).verify(any());
        verify(commitService).commit(claim, batch);
        verify(metrics).success("auto_extract", 1);
    }

    @Test
    void savesOpenFactOnlyWhenIndependentVerifierSupportsIt() {
        String content = "我长期从事供应链系统建设，也喜欢登山";
        prepareSource(content);
        MemoryFactCandidate supportedRaw = fact(
                MemoryType.WORK_CONTEXT, "occupation", "供应链系统建设", content);
        MemoryFactCandidate contradictedRaw = fact(
                MemoryType.STABLE_PREFERENCE, "outdoor_activity", "登山", content);
        ValidatedMemoryFact supported = validated(supportedRaw, "work.occupation",
                "用户的职业是供应链系统建设", "WORK_COMMON_SCOPE", "SEMANTIC_REQUIRED");
        ValidatedMemoryFact contradicted = validated(contradictedRaw,
                "fact.stable_preference.2", "用户的稳定偏好是登山",
                "STABLE_PREFERENCE", "SEMANTIC_REQUIRED");
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(supportedRaw, contradictedRaw)));
        when(validator.validate(supportedRaw, content)).thenReturn(supported);
        when(validator.validate(contradictedRaw, content)).thenReturn(contradicted);
        when(evidenceVerifier.verify(any())).thenReturn(List.of(
                new MemoryEvidenceVerifier.Result("candidate-0",
                        MemoryEvidenceVerifier.Outcome.SUPPORTED),
                new MemoryEvidenceVerifier.Result("candidate-1",
                        MemoryEvidenceVerifier.Outcome.CONTRADICTED)));
        ImplicitMemoryExtractionBatch batch = ImplicitMemoryExtractionBatch.observed(
                2, List.of(validated(supportedRaw, "work.occupation",
                        "用户的职业是供应链系统建设", "WORK_COMMON_SCOPE",
                        "SEMANTIC_MODEL")));
        when(commitService.commit(claim, batch)).thenReturn(1);

        worker().process(claim);

        verify(commitService).commit(claim, batch);
        verify(metrics).success("auto_extract", 1);
    }

    @Test
    void keepsHistoricalScopeWhenIndependentVerifierSupportsFact() {
        String content = "我以前长期从事供应链系统建设";
        prepareSource(content);
        MemoryFactCandidate raw = new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT,
                "occupation",
                "供应链系统建设",
                "供应链系统建设",
                content,
                MemoryStability.STABLE,
                MemoryTemporalScope.HISTORICAL,
                0.93);
        ValidatedMemoryFact pending = new ValidatedMemoryFact(
                raw,
                "work.occupation",
                "用户以前的职业是供应链系统建设",
                "\"供应链系统建设\"",
                "WORK_COMMON_SCOPE",
                "SEMANTIC_REQUIRED",
                MemoryTemporalScope.HISTORICAL);
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(raw)));
        when(validator.validate(raw, content)).thenReturn(pending);
        when(evidenceVerifier.verify(any())).thenReturn(List.of(
                new MemoryEvidenceVerifier.Result("candidate-0",
                        MemoryEvidenceVerifier.Outcome.SUPPORTED)));
        ValidatedMemoryFact supported = new ValidatedMemoryFact(
                raw,
                "work.occupation",
                "用户以前的职业是供应链系统建设",
                "\"供应链系统建设\"",
                "WORK_COMMON_SCOPE",
                "SEMANTIC_MODEL",
                MemoryTemporalScope.HISTORICAL);
        ImplicitMemoryExtractionBatch batch = ImplicitMemoryExtractionBatch.observed(
                1, List.of(supported));
        when(commitService.commit(claim, batch)).thenReturn(1);

        worker().process(claim);

        verify(commitService).commit(claim, batch);
    }

    @Test
    void ignoreAndSessionOnlyCompleteWithoutMemoryOrVerification() {
        prepareSource("帮我查一下今天的订单");
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.ignore());
        ImplicitMemoryExtractionBatch ignore =
                ImplicitMemoryExtractionBatch.decision(MemoryDecision.IGNORE);
        when(commitService.commit(claim, ignore)).thenReturn(0);

        worker().process(claim);

        verify(commitService).commit(claim, ignore);
        verify(validator, never()).validate(any(MemoryFactCandidate.class), any());
        verify(evidenceVerifier, never()).verify(any());

        prepareSource("这次回答简短一点");
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.sessionOnly());
        ImplicitMemoryExtractionBatch session =
                ImplicitMemoryExtractionBatch.decision(MemoryDecision.SESSION_ONLY);
        when(commitService.commit(claim, session)).thenReturn(0);
        worker().process(claim);
        verify(commitService).commit(claim, session);
    }

    @Test
    void rejectedCandidatesCompleteWithoutMemory() {
        String content = "我平时主要用 Java 语言进行开发";
        prepareSource(content);
        MemoryFactCandidate raw = fact(MemoryType.WORK_CONTEXT,
                "primary_programming_language", "Java", content);
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(raw)));
        when(validator.validate(raw, content)).thenThrow(new MemoryCandidateValidationException(
                MemoryCandidateValidationException.Reason.EVIDENCE));
        ImplicitMemoryExtractionBatch rejected = ImplicitMemoryExtractionBatch.rejected(
                1, com.xjjk.agent.memory.domain.MemoryExtractionResultCode.REJECTED_EVIDENCE);
        when(commitService.commit(claim, rejected)).thenReturn(0);

        worker().process(claim);

        verify(commitService).commit(claim, rejected);
        verify(evidenceVerifier, never()).verify(any());
    }

    @Test
    void retriesTransientVerifierFailure() {
        String content = "我长期从事供应链系统建设";
        prepareSource(content);
        MemoryFactCandidate raw = fact(
                MemoryType.WORK_CONTEXT, "occupation", "供应链系统建设", content);
        ValidatedMemoryFact validated = validated(raw, "work.occupation",
                "用户的职业是供应链系统建设", "WORK_COMMON_SCOPE", "SEMANTIC_REQUIRED");
        when(modelClient.analyze(any())).thenReturn(MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(raw)));
        when(validator.validate(raw, content)).thenReturn(validated);
        when(evidenceVerifier.verify(any())).thenThrow(new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_TIMEOUT, "safe"));

        worker().process(claim);

        verify(taskState).scheduleRetry(claim, "MODEL_TIMEOUT");
        verify(commitService, never()).commit(any(), any());
    }

    @Test
    void protocolFailureCompletesAsRejectedWithoutRetry() {
        prepareSource("我是后端开发");
        when(modelClient.analyze(any())).thenThrow(new ImplicitMemoryExtractionException(
                ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR, "safe"));

        worker().process(claim);

        verify(commitService).commit(claim, ImplicitMemoryExtractionBatch.protocolRejected());
        verify(taskState, never()).scheduleRetry(any(), any());
    }

    @Test
    void cancelsWhenAuthoritativeSourceNoLongerExists() {
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(null);

        worker().process(claim);

        verify(taskState).cancel(claim, "SOURCE_NOT_AVAILABLE");
        verify(modelClient, never()).analyze(any());
    }

    private ImplicitMemoryTaskWorker worker() {
        return new ImplicitMemoryTaskWorker(messageMapper, modelClient, validator,
                evidenceVerifier, commitService, taskState, metrics);
    }

    private void prepareSource(String content) {
        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setContent(content);
        when(messageMapper.selectOwnedSuccessfulUserMessage(
                1L, 2L, "conversation-1", "request-1", "user-1", 17L))
                .thenReturn(entity);
        when(messageMapper.selectPreviousSuccessfulUserContent(
                1L, 2L, "conversation-1", 17L)).thenReturn("上一条用户消息");
    }

    private static MemoryFactCandidate fact(
            MemoryType type, String predicate, String value, String evidence) {
        return new MemoryFactCandidate(type, predicate, value, value, evidence,
                MemoryStability.STABLE, 0.93);
    }

    private static ValidatedMemoryFact validated(
            MemoryFactCandidate raw, String key, String content,
            String category, String verificationMethod) {
        return new ValidatedMemoryFact(raw, key, content,
                "\"" + raw.value() + "\"", category, verificationMethod);
    }

}
