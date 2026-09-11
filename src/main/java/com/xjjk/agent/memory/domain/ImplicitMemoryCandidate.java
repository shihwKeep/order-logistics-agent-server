package com.xjjk.agent.memory.domain;

/** 模型产生并等待 Java 校验的隐式记忆候选。 */
public record ImplicitMemoryCandidate(
        MemoryCategory category,
        String canonicalKey,
        String content,
        String evidenceText,
        double confidence
) {
}
