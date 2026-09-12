package com.xjjk.agent.memory.recall;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 通过所有者、世代、状态、版本、抑制与冲突治理后的权威记忆。 */
public record RecalledMemory(
        String memoryId,
        long memoryVersion,
        String sourceType,
        String category,
        String canonicalKey,
        String content,
        BigDecimal confidence,
        LocalDateTime updatedAt,
        Integer schemaVersion,
        String memoryType,
        String predicateName,
        String valueJson,
        String stability,
        String verificationMethod) {

    public RecalledMemory(
            String memoryId,
            long memoryVersion,
            String sourceType,
            String category,
            String canonicalKey,
            String content,
            BigDecimal confidence,
            LocalDateTime updatedAt) {
        this(memoryId, memoryVersion, sourceType, category, canonicalKey, content,
                confidence, updatedAt, null, null, null, null, null, null);
    }
}
