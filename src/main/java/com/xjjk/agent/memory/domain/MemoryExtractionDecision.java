package com.xjjk.agent.memory.domain;

import java.util.List;
import java.util.Objects;

/** 一次用户消息的通用语义记忆决策。 */
public record MemoryExtractionDecision(
        MemoryDecision decision,
        MemoryExplicitness explicitness,
        List<MemoryFactCandidate> candidates
) {
    public MemoryExtractionDecision {
        Objects.requireNonNull(decision, "decision");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (decision == MemoryDecision.LONG_TERM) {
            Objects.requireNonNull(explicitness, "explicitness");
            if (candidates.isEmpty()
                    || candidates.stream().anyMatch(candidate ->
                    candidate == null || !supportsLongTerm(candidate.stability()))) {
                throw new IllegalArgumentException(
                        "long-term decision requires stable or time-bound candidates");
            }
        } else if (explicitness != null || !candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "non-long-term decision cannot carry candidates");
        }
    }

    public static MemoryExtractionDecision ignore() {
        return new MemoryExtractionDecision(MemoryDecision.IGNORE, null, List.of());
    }

    public static MemoryExtractionDecision sessionOnly() {
        return new MemoryExtractionDecision(MemoryDecision.SESSION_ONLY, null, List.of());
    }

    public static MemoryExtractionDecision longTerm(
            MemoryExplicitness explicitness,
            List<MemoryFactCandidate> candidates) {
        return new MemoryExtractionDecision(
                MemoryDecision.LONG_TERM, explicitness, candidates);
    }

    private static boolean supportsLongTerm(MemoryStability stability) {
        return stability == MemoryStability.STABLE
                || stability == MemoryStability.TIME_BOUND;
    }
}
