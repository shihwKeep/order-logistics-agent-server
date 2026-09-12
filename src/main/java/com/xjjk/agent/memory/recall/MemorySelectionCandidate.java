package com.xjjk.agent.memory.recall;

import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;

/** 已从 MySQL 回表的候选及其非权威索引分数。 */
public record MemorySelectionCandidate(
        UserMemoryEntity memory,
        double relevanceScore,
        int rank,
        boolean directGlobal) {
}
