package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;

import java.util.Objects;

public class ExplicitMemoryCandidateValidator {

    private final MemorySensitiveContentPolicy sensitiveContentPolicy;
    private final MemoryCategoryContentPolicy categoryContentPolicy;
    private final MemorySchemaRegistry schemaRegistry;
    private final int maxContentCodePoints;
    private final int maxEvidenceCodePoints;

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this(sensitiveContentPolicy, new MemoryCategoryContentPolicy(),
                null, maxContentCodePoints, maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            MemorySchemaRegistry schemaRegistry,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this.sensitiveContentPolicy = Objects.requireNonNull(sensitiveContentPolicy, "sensitiveContentPolicy");
        this.categoryContentPolicy = Objects.requireNonNull(categoryContentPolicy, "categoryContentPolicy");
        this.schemaRegistry = schemaRegistry;
        this.maxContentCodePoints = requirePositive(maxContentCodePoints);
        this.maxEvidenceCodePoints = requirePositive(maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            MemorySchemaRegistry schemaRegistry,
            int maxContentCodePoints,
            int maxEvidenceCodePoints) {
        this(sensitiveContentPolicy, new MemoryCategoryContentPolicy(),
                Objects.requireNonNull(schemaRegistry, "schemaRegistry"),
                maxContentCodePoints, maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidate validate(
            ExplicitMemoryCandidate candidate,
            String originalMessage,
            boolean permanentCommand
    ) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.semanticFact() != null) {
            return validateSemantic(candidate, originalMessage, permanentCommand);
        }
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
                || !categoryContentPolicy.supportsCandidate(
                        candidate.category(), evidence, content)) {
            throw invalid();
        }
        return new ExplicitMemoryCandidate(candidate.category(), key, content, evidence, expected);
    }

    private ExplicitMemoryCandidate validateSemantic(
            ExplicitMemoryCandidate candidate,
            String originalMessage,
            boolean permanentCommand) {
        if (schemaRegistry == null || candidate.retentionType() == null
                || candidate.semanticFact().stability() != MemoryStability.STABLE) {
            throw invalid();
        }
        String original = ExplicitMemoryCommandDetector.normalizeWhitespace(
                originalMessage == null ? "" : originalMessage);
        String evidence = normalize(candidate.semanticFact().evidenceText());
        String valueEvidence = normalize(candidate.semanticFact().valueEvidence());
        if (!original.contains(evidence) || !evidence.contains(valueEvidence)) {
            throw invalid();
        }
        requireLength(evidence, maxEvidenceCodePoints);
        requireLength(valueEvidence, maxContentCodePoints);
        MemoryRetentionType expected = permanentCommand
                ? MemoryRetentionType.PERMANENT : MemoryRetentionType.NORMAL;
        if (candidate.retentionType() != expected
                || !sensitiveContentPolicy.isAllowed(original)
                || !sensitiveContentPolicy.isAllowed(evidence)
                || !sensitiveContentPolicy.isAllowed(valueEvidence)
                || !sensitiveContentPolicy.isAllowed(candidate.semanticFact().value())) {
            throw invalid();
        }
        var normalizedFact = schemaRegistry.normalizeCandidate(candidate.semanticFact());
        MemorySchemaRegistry.SchemaResolution resolution =
                schemaRegistry.resolve(normalizedFact);
        requireLength(resolution.canonicalContent(), maxContentCodePoints);
        if (!sensitiveContentPolicy.isAllowed(resolution.canonicalContent())) {
            throw invalid();
        }
        return new ExplicitMemoryCandidate(
                MemoryCategory.valueOf(resolution.legacyCategory()),
                resolution.canonicalKey(), resolution.canonicalContent(),
                evidence, expected, normalizedFact);
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
