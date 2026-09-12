package com.xjjk.agent.memory.domain;

import java.util.Objects;

/** 经服务端模式、证据和安全策略验证后允许持久化的用户事实。 */
public record ValidatedMemoryFact(
        MemoryFactCandidate candidate,
        String canonicalKey,
        String canonicalContent,
        String valueJson,
        String legacyCategory,
        String verificationMethod
) {
    public ValidatedMemoryFact {
        Objects.requireNonNull(candidate, "candidate");
        canonicalKey = requireText(canonicalKey, "canonicalKey");
        canonicalContent = requireText(canonicalContent, "canonicalContent");
        valueJson = requireText(valueJson, "valueJson");
        legacyCategory = requireText(legacyCategory, "legacyCategory");
        verificationMethod = requireText(verificationMethod, "verificationMethod");
    }

    public double confidence() {
        return candidate.confidence();
    }

    public String evidenceText() {
        return candidate.evidenceText();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
