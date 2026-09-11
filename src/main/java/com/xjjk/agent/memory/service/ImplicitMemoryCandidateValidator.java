package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;

import java.util.Objects;

/** 不信任模型输出，只允许由用户原文直接支持的封闭类别事实。 */
public class ImplicitMemoryCandidateValidator {

    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final MemoryCategoryContentPolicy categoryPolicy;
    private final ImplicitMemoryProperties properties;
    private final int maxContentCodePoints;
    private final int maxEvidenceCodePoints;

    public ImplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitivePolicy,
            MemoryCategoryContentPolicy categoryPolicy,
            ImplicitMemoryProperties properties,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy, "sensitivePolicy");
        this.categoryPolicy = Objects.requireNonNull(categoryPolicy, "categoryPolicy");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.maxContentCodePoints = requirePositive(maxContentCodePoints);
        this.maxEvidenceCodePoints = requirePositive(maxEvidenceCodePoints);
    }

    public ImplicitMemoryCandidate validate(ImplicitMemoryCandidate candidate, String sourceMessage) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.category() == null || !Double.isFinite(candidate.confidence())
                || candidate.confidence() < properties.confidenceThreshold()
                || candidate.confidence() > 1.0) {
            throw rejected();
        }
        String key = normalize(candidate.canonicalKey());
        String content = normalize(candidate.content());
        String evidence = normalize(candidate.evidenceText());
        String source = ExplicitMemoryCommandDetector.normalizeWhitespace(
                sourceMessage == null ? "" : sourceMessage);
        if (!key.equals(candidate.category().keyPrefix()) || !source.contains(evidence)) {
            throw rejected();
        }
        requireLength(content, maxContentCodePoints);
        requireLength(evidence, maxEvidenceCodePoints);
        if (!sensitivePolicy.isAllowed(source)
                || !sensitivePolicy.isAllowed(evidence)
                || !sensitivePolicy.isAllowed(content)
                || !categoryPolicy.isAllowed(candidate.category(), evidence, content)) {
            throw rejected();
        }
        String canonicalContent = categoryPolicy.canonicalize(candidate.category(), evidence)
                .orElseThrow(ImplicitMemoryCandidateValidator::rejected);
        return new ImplicitMemoryCandidate(
                candidate.category(), key, canonicalContent, evidence, candidate.confidence());
    }

    private static String normalize(String value) {
        String normalized = ExplicitMemoryCommandDetector.normalizeWhitespace(value == null ? "" : value);
        if (normalized.isBlank()) {
            throw rejected();
        }
        return normalized;
    }

    private static void requireLength(String value, int maximum) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw rejected();
        }
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return value;
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("MEMORY_CONTENT_REJECTED");
    }
}
