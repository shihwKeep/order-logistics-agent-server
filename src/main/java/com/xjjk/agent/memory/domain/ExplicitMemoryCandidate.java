package com.xjjk.agent.memory.domain;

/** 模型输出经过持久化前校验的显式记忆候选。 */
public record ExplicitMemoryCandidate(
        MemoryCategory category,
        String canonicalKey,
        String content,
        String evidenceText,
        MemoryRetentionType retentionType,
        MemoryFactCandidate semanticFact
) {
    public ExplicitMemoryCandidate(
            MemoryCategory category,
            String canonicalKey,
            String content,
            String evidenceText,
            MemoryRetentionType retentionType) {
        this(category, canonicalKey, content, evidenceText, retentionType, null);
    }

    public static ExplicitMemoryCandidate semantic(
            MemoryFactCandidate fact,
            MemoryRetentionType retentionType) {
        return new ExplicitMemoryCandidate(
                null, null, null, fact.evidenceText(), retentionType, fact);
    }
}
