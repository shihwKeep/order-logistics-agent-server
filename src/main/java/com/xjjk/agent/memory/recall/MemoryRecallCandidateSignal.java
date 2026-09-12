package com.xjjk.agent.memory.recall;

import java.util.Set;

/** 来自索引层的非权威候选信号，不包含记忆正文。 */
public record MemoryRecallCandidateSignal(
        String memoryId,
        long memoryVersion,
        double score,
        int rank,
        Set<String> sources) {

    public MemoryRecallCandidateSignal {
        sources = Set.copyOf(sources);
    }
}
