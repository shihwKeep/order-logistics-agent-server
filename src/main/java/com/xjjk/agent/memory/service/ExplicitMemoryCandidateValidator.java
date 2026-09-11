package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryRetentionType;

import java.util.Objects;

public class ExplicitMemoryCandidateValidator {

    private final MemorySensitiveContentPolicy sensitiveContentPolicy;
    private final MemoryCategoryContentPolicy categoryContentPolicy;
    private final int maxContentCodePoints;
    private final int maxEvidenceCodePoints;

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this(sensitiveContentPolicy, new MemoryCategoryContentPolicy(),
                maxContentCodePoints, maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this.sensitiveContentPolicy = Objects.requireNonNull(sensitiveContentPolicy, "sensitiveContentPolicy");
        this.categoryContentPolicy = Objects.requireNonNull(categoryContentPolicy, "categoryContentPolicy");
        this.maxContentCodePoints = requirePositive(maxContentCodePoints);
        this.maxEvidenceCodePoints = requirePositive(maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidate validate(
            ExplicitMemoryCandidate candidate,
            String originalMessage,
            boolean permanentCommand
    ) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.category() == null || candidate.retentionType() == null) {
            throw invalid();
        }
        String key = normalize(candidate.canonicalKey());
        String content = normalize(candidate.content());
        String evidence = normalize(candidate.evidenceText());
        String original = ExplicitMemoryCommandDetector.normalizeWhitespace(originalMessage == null ? "" : originalMessage);
        String prefix = candidate.category().keyPrefix();
        if (!(key.equals(prefix) || key.startsWith(prefix + "."))) {
            throw invalid();
        }
        if (!original.contains(evidence)) {
            throw invalid();
        }
        requireLength(content, maxContentCodePoints);
        requireLength(evidence, maxEvidenceCodePoints);
        MemoryRetentionType expected = permanentCommand
                ? MemoryRetentionType.PERMANENT
                : MemoryRetentionType.NORMAL;
        if (candidate.retentionType() != expected) {
            throw invalid();
        }
        if (!sensitiveContentPolicy.isAllowed(content)
                || !sensitiveContentPolicy.isAllowed(evidence)
                || !sensitiveContentPolicy.isAllowed(original)
                || !categoryContentPolicy.isAllowed(candidate.category(), evidence, content)) {
            throw invalid();
        }
        String canonicalContent = categoryContentPolicy.canonicalize(candidate.category(), evidence)
                .orElseThrow(ExplicitMemoryCandidateValidator::invalid);
        return new ExplicitMemoryCandidate(candidate.category(), key, canonicalContent, evidence, expected);
    }

    private static String normalize(String value) {
        if (value == null) {
            throw invalid();
        }
        String normalized = ExplicitMemoryCommandDetector.normalizeWhitespace(value);
        if (normalized.isBlank()) {
            throw invalid();
        }
        return normalized;
    }

    private static void requireLength(String value, int maximum) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw invalid();
        }
    }

    private static int requirePositive(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("MEMORY_CONTENT_REJECTED");
    }
}
