package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.ValidatedMemoryFact;

import java.util.Objects;

/** 不信任模型输出，只允许由用户原文直接支持的封闭类别事实。 */
public class ImplicitMemoryCandidateValidator {

    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final MemoryCategoryContentPolicy categoryPolicy;
    private final MemorySchemaRegistry schemaRegistry;
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
        this.schemaRegistry = null;
        this.properties = Objects.requireNonNull(properties, "properties");
        this.maxContentCodePoints = requirePositive(maxContentCodePoints);
        this.maxEvidenceCodePoints = requirePositive(maxEvidenceCodePoints);
    }

    public ImplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitivePolicy,
            MemorySchemaRegistry schemaRegistry,
            ImplicitMemoryProperties properties,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy, "sensitivePolicy");
        this.categoryPolicy = null;
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.maxContentCodePoints = requirePositive(maxContentCodePoints);
        this.maxEvidenceCodePoints = requirePositive(maxEvidenceCodePoints);
    }

    public ValidatedMemoryFact validate(
            MemoryFactCandidate candidate,
            String sourceMessage) {
        Objects.requireNonNull(candidate, "candidate");
        if (!Double.isFinite(candidate.confidence())
                || candidate.confidence() < properties.confidenceThreshold()
                || candidate.confidence() > 1.0) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.CONFIDENCE);
        }
        if (candidate.stability() != MemoryStability.STABLE) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.STABILITY);
        }
        String source = ExplicitMemoryCommandDetector.normalizeWhitespace(
                sourceMessage == null ? "" : sourceMessage);
        String evidence = normalizeSemantic(candidate.evidenceText());
        String valueEvidence = normalizeSemantic(candidate.valueEvidence());
        if (!source.contains(evidence) || !evidence.contains(valueEvidence)) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.EVIDENCE);
        }
        requireSemanticLength(evidence, maxEvidenceCodePoints);
        requireSemanticLength(valueEvidence, maxContentCodePoints);
        if (!sensitivePolicy.isAllowed(source)
                || !sensitivePolicy.isAllowed(evidence)
                || !sensitivePolicy.isAllowed(valueEvidence)
                || !sensitivePolicy.isAllowed(candidate.value())) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.SENSITIVE);
        }
        MemoryFactCandidate normalizedCandidate = schemaRegistry.normalizeCandidate(candidate);
        MemorySchemaRegistry.SchemaResolution resolution =
                schemaRegistry.resolve(normalizedCandidate);
        requireSemanticLength(resolution.canonicalContent(), maxContentCodePoints);
        if (!sensitivePolicy.isAllowed(resolution.canonicalContent())) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.SENSITIVE);
        }
        return new ValidatedMemoryFact(
                normalizedCandidate,
                resolution.canonicalKey(),
                resolution.canonicalContent(),
                resolution.valueJson(),
                resolution.legacyCategory(),
                resolution.requiresSemanticVerification()
                        ? "SEMANTIC_REQUIRED" : "DETERMINISTIC");
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

    private static String normalizeSemantic(String value) {
        String normalized = ExplicitMemoryCommandDetector.normalizeWhitespace(
                value == null ? "" : value);
        if (normalized.isBlank()) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.EVIDENCE);
        }
        return normalized;
    }

    private static void requireLength(String value, int maximum) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw rejected();
        }
    }

    private static void requireSemanticLength(String value, int maximum) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw semanticRejected(MemoryCandidateValidationException.Reason.SCHEMA);
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

    private static MemoryCandidateValidationException semanticRejected(
            MemoryCandidateValidationException.Reason reason) {
        return new MemoryCandidateValidationException(reason);
    }
}
