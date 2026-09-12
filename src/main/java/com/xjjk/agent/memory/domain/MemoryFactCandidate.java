package com.xjjk.agent.memory.domain;

import java.util.Objects;

/** 模型提出的原子用户事实；在验证完成前不得用于持久化或回答。 */
public record MemoryFactCandidate(
        MemoryType memoryType,
        String predicate,
        String value,
        String valueEvidence,
        String evidenceText,
        MemoryStability stability,
        double confidence
) {
    public MemoryFactCandidate {
        Objects.requireNonNull(memoryType, "memoryType");
        Objects.requireNonNull(stability, "stability");
        predicate = requireText(predicate, "predicate");
        value = requireText(value, "value");
        valueEvidence = requireText(valueEvidence, "valueEvidence");
        evidenceText = requireText(evidenceText, "evidenceText");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0,1]");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
