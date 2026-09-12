package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ImplicitMemoryExtractionBatch;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryDecision;
import com.xjjk.agent.memory.domain.MemoryExtractionDecision;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.domain.ValidatedMemoryFact;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class ImplicitMemoryTaskWorker {

    private final AgentMessageMapper messageMapper;
    private final ImplicitMemoryModelClient modelClient;
    private final ImplicitMemoryCandidateValidator validator;
    private final MemoryEvidenceVerifier evidenceVerifier;
    private final ImplicitMemoryCommitService commitService;
    private final ImplicitMemoryTaskCommitService taskState;
    private final UserMemoryMetrics metrics;

    public ImplicitMemoryTaskWorker(
            AgentMessageMapper messageMapper,
            ImplicitMemoryModelClient modelClient,
            ImplicitMemoryCandidateValidator validator,
            MemoryEvidenceVerifier evidenceVerifier,
            ImplicitMemoryCommitService commitService,
            ImplicitMemoryTaskCommitService taskState,
            UserMemoryMetrics metrics
    ) {
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier, "evidenceVerifier");
        this.commitService = Objects.requireNonNull(commitService, "commitService");
        this.taskState = Objects.requireNonNull(taskState, "taskState");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void process(MemoryExtractionTaskClaim claim) {
        AgentMessageEntity source = messageMapper.selectOwnedSuccessfulUserMessage(
                claim.tenantId(), claim.userId(), claim.conversationId(), claim.requestId(),
                claim.userMessageId(), claim.userMessageSequence());
        if (source == null || source.getContent() == null || source.getContent().isBlank()) {
            taskState.cancel(claim, "SOURCE_NOT_AVAILABLE");
            return;
        }
        String prior = messageMapper.selectPreviousSuccessfulUserContent(
                claim.tenantId(), claim.userId(), claim.conversationId(),
                claim.userMessageSequence());
        try {
            MemoryExtractionDecision decision = modelClient.analyze(
                    new ImplicitMemoryModelClient.Request(
                            claim.requestId(), source.getContent(), prior));
            if (decision.decision() != MemoryDecision.LONG_TERM) {
                complete(claim, ImplicitMemoryExtractionBatch.observed(0, List.of()));
                return;
            }
            List<ValidatedMemoryFact> accepted = new ArrayList<>();
            for (var candidate : decision.candidates()) {
                try {
                    accepted.add(validator.validate(candidate, source.getContent()));
                } catch (MemoryCandidateValidationException rejected) {
                    // 单个候选按有界原因丢弃，不记录正文、证据或模型输出。
                }
            }
            List<ValidatedMemoryFact> verified = verifyOpenCandidates(
                    claim.requestId(), source.getContent(), accepted);
            List<ImplicitMemoryCandidate> persisted = verified.stream()
                    .map(ImplicitMemoryTaskWorker::legacyCandidate)
                    .toList();
            ImplicitMemoryExtractionBatch batch =
                    ImplicitMemoryExtractionBatch.observed(
                            decision.candidates().size(), persisted);
            complete(claim, batch);
        } catch (ImplicitMemoryExtractionException error) {
            if (error.code() == ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR) {
                int saved = commitService.commit(
                        claim, ImplicitMemoryExtractionBatch.protocolRejected());
                metrics.success("auto_extract", saved);
            } else {
                taskState.scheduleRetry(claim, error.code().name());
            }
        } catch (RuntimeException error) {
            log.warn("implicit_memory_task taskId={}, conversationId={}, result=RETRY, errorCode=WORKER_FAILED",
                    claim.taskId(), claim.conversationId());
            taskState.scheduleRetry(claim, "WORKER_FAILED");
        }
    }

    private List<ValidatedMemoryFact> verifyOpenCandidates(
            String requestId,
            String sourceMessage,
            List<ValidatedMemoryFact> accepted) {
        List<ValidatedMemoryFact> finalFacts = new ArrayList<>();
        List<MemoryEvidenceVerifier.Candidate> pending = new ArrayList<>();
        Map<String, ValidatedMemoryFact> byId = new LinkedHashMap<>();
        for (ValidatedMemoryFact fact : accepted) {
            if (!"SEMANTIC_REQUIRED".equals(fact.verificationMethod())) {
                finalFacts.add(fact);
                continue;
            }
            String id = "candidate-" + pending.size();
            pending.add(new MemoryEvidenceVerifier.Candidate(id, fact));
            byId.put(id, fact);
        }
        if (pending.isEmpty()) {
            return List.copyOf(finalFacts);
        }
        List<MemoryEvidenceVerifier.Result> results = evidenceVerifier.verify(
                new MemoryEvidenceVerifier.Request(requestId, sourceMessage, pending));
        for (MemoryEvidenceVerifier.Result result : results) {
            if (result.outcome() == MemoryEvidenceVerifier.Outcome.SUPPORTED) {
                ValidatedMemoryFact fact = byId.get(result.candidateId());
                if (fact == null) {
                    throw new ImplicitMemoryExtractionException(
                            ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR,
                            "记忆证据核验响应无效");
                }
                finalFacts.add(withVerification(fact, "SEMANTIC_MODEL"));
            }
        }
        return List.copyOf(finalFacts);
    }

    private void complete(MemoryExtractionTaskClaim claim, ImplicitMemoryExtractionBatch batch) {
        int saved = commitService.commit(claim, batch);
        metrics.success("auto_extract", saved);
    }

    private static ValidatedMemoryFact withVerification(
            ValidatedMemoryFact fact,
            String verificationMethod) {
        return new ValidatedMemoryFact(
                fact.candidate(), fact.canonicalKey(), fact.canonicalContent(),
                fact.valueJson(), fact.legacyCategory(), verificationMethod);
    }

    private static ImplicitMemoryCandidate legacyCandidate(ValidatedMemoryFact fact) {
        return new ImplicitMemoryCandidate(
                MemoryCategory.valueOf(fact.legacyCategory()),
                fact.canonicalKey(), fact.canonicalContent(),
                fact.evidenceText(), fact.confidence());
    }
}
