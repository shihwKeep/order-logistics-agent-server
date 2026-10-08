package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStability;

import java.util.Objects;

/**
 * 显式记忆候选的最终服务端校验器。
 *
 * <p>快速路径与语义模型都属于不可信输入；本类统一验证证据包含关系、保存期限、
 * 敏感策略、时间证据和服务端 Schema，校验通过后才允许进入事务写入。</p>
 */
public class ExplicitMemoryCandidateValidator {

    private final MemorySensitiveContentPolicy sensitiveContentPolicy;
    private final MemoryCategoryContentPolicy categoryContentPolicy;
    private final MemorySchemaRegistry schemaRegistry;
    private final MemoryTemporalEvidencePolicy temporalEvidencePolicy;
    private final int maxContentCodePoints;
    private final int maxEvidenceCodePoints;

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this(sensitiveContentPolicy, new MemoryCategoryContentPolicy(),
                null, new MemoryTemporalEvidencePolicy(),
                maxContentCodePoints, maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            MemorySchemaRegistry schemaRegistry,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this(sensitiveContentPolicy, categoryContentPolicy, schemaRegistry,
                new MemoryTemporalEvidencePolicy(),
                maxContentCodePoints, maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidateValidator(
            MemorySensitiveContentPolicy sensitiveContentPolicy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            MemorySchemaRegistry schemaRegistry,
            MemoryTemporalEvidencePolicy temporalEvidencePolicy,
            int maxContentCodePoints,
            int maxEvidenceCodePoints
    ) {
        this.sensitiveContentPolicy = Objects.requireNonNull(sensitiveContentPolicy, "sensitiveContentPolicy");
        this.categoryContentPolicy = Objects.requireNonNull(categoryContentPolicy, "categoryContentPolicy");
        this.schemaRegistry = schemaRegistry;
        this.temporalEvidencePolicy = Objects.requireNonNull(
                temporalEvidencePolicy, "temporalEvidencePolicy");
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
                new MemoryTemporalEvidencePolicy(),
                maxContentCodePoints, maxEvidenceCodePoints);
    }

    public ExplicitMemoryCandidate validate(
            ExplicitMemoryCandidate candidate,
            String originalMessage,
            boolean permanentCommand
    ) {
        Objects.requireNonNull(candidate, "candidate");
        // 语义候选包含结构化事实，使用 Schema v3 校验；否则走旧快速路径的封闭类别校验。
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
        // 快速路径的 key 必须位于当前类别命名空间，证据必须能在用户原文中直接找到。
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
        // retention 由用户原文的确定性命令决定，禁止候选自行升级为永久保存。
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
        // 长期显式事实只接受稳定或随时间变化但仍有复用价值的事实。
        if (schemaRegistry == null || candidate.retentionType() == null
                || (candidate.semanticFact().stability() != MemoryStability.STABLE
                && candidate.semanticFact().stability() != MemoryStability.TIME_BOUND)) {
            throw invalid();
        }
        String original = ExplicitMemoryCommandDetector.normalizeWhitespace(
                originalMessage == null ? "" : originalMessage);
        String evidence = normalize(candidate.semanticFact().evidenceText());
        String valueEvidence = normalize(candidate.semanticFact().valueEvidence());
        // evidence 必须来自本轮原文，valueEvidence 又必须包含在 evidence 中；
        // 时间范围也必须有原文依据，阻止模型根据常识补全用户没有表达的事实。
        if (!MemoryEvidenceTextMatcher.contains(original, evidence)
                || !MemoryEvidenceTextMatcher.contains(evidence, valueEvidence)
                || !temporalEvidencePolicy.isSupported(
                         candidate.semanticFact(), evidence)) {
            throw invalid();
        }
        requireLength(evidence, maxEvidenceCodePoints);
        requireLength(valueEvidence, maxContentCodePoints);
        MemoryRetentionType expected = permanentCommand
                ? MemoryRetentionType.PERMANENT : MemoryRetentionType.NORMAL;
        // 原文、证据、值和最终规范化正文分别执行敏感检查，避免改写绕过前置策略。
        if (candidate.retentionType() != expected
                || !sensitiveContentPolicy.isAllowed(original)
                || !sensitiveContentPolicy.isAllowed(evidence)
                || !sensitiveContentPolicy.isAllowed(valueEvidence)
                || !sensitiveContentPolicy.isAllowed(candidate.semanticFact().value())) {
            throw invalid();
        }
        var normalizedFact = schemaRegistry.normalizeCandidate(candidate.semanticFact());
        // canonicalKey、规范正文和兼容类别全部由服务端 Schema 生成，不采信模型自由字段。
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
