package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ValidatedMemoryFact;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;

import java.util.List;
import java.util.Objects;

/** 对需要开放语义判断的候选执行独立、批量、只读证据核验。 */
public interface MemoryEvidenceVerifier {

    List<Result> verify(Request request);

    enum Outcome {
        SUPPORTED,
        CONTRADICTED,
        UNCERTAIN
    }

    record Candidate(String candidateId, ValidatedMemoryFact fact) {
        public Candidate {
            if (candidateId == null || candidateId.isBlank()) {
                throw new IllegalArgumentException("candidateId must not be blank");
            }
            Objects.requireNonNull(fact, "fact");
        }

        public MemoryStability stability() {
            return fact.candidate().stability();
        }

        public MemoryTemporalScope temporalScope() {
            return fact.temporalScope();
        }
    }

    record Request(String requestId, String sourceMessage, List<Candidate> candidates) {
        public Request {
            if (requestId == null || requestId.isBlank()
                    || sourceMessage == null || sourceMessage.isBlank()) {
                throw new IllegalArgumentException("verification request is invalid");
            }
            Objects.requireNonNull(candidates, "candidates");
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("verification candidates must not be empty");
            }
        }
    }

    record Result(String candidateId, Outcome outcome) {
        public Result {
            if (candidateId == null || candidateId.isBlank()) {
                throw new IllegalArgumentException("candidateId must not be blank");
            }
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
