package com.xjjk.agent.memory.recall;

import java.util.List;

public record MemoryRecallGatewayResult(
        boolean available,
        List<MemoryRecallCandidateSignal> candidates,
        String strategyVersion,
        String degradationMode,
        String resultCode) {

    public MemoryRecallGatewayResult {
        candidates = List.copyOf(candidates);
    }

    public static MemoryRecallGatewayResult unavailable() {
        return new MemoryRecallGatewayResult(
                false, List.of(), "UNAVAILABLE", "ALL_RECALL_UNAVAILABLE", "UNAVAILABLE");
    }
}
