package com.xjjk.agent.memory.recall;

import java.util.List;

public record UserMemoryRecallResult(
        List<RecalledMemory> memories,
        boolean semanticAttempted,
        String semanticResultCode) {

    public UserMemoryRecallResult {
        memories = List.copyOf(memories);
    }

    public static UserMemoryRecallResult empty() {
        return new UserMemoryRecallResult(List.of(), false, "SKIPPED");
    }
}
