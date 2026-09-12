package com.xjjk.agent.memory.recall;

import java.util.List;
import java.util.Objects;

public record UserMemoryRecallResult(
        List<RecalledMemory> memories,
        boolean semanticAttempted,
        String semanticResultCode,
        UserMemoryRecallStatus status) {

    public UserMemoryRecallResult {
        memories = List.copyOf(memories);
        Objects.requireNonNull(semanticResultCode, "语义召回结果码不能为空");
        Objects.requireNonNull(status, "召回状态不能为空");
    }

    public UserMemoryRecallResult(
            List<RecalledMemory> memories,
            boolean semanticAttempted,
            String semanticResultCode) {
        this(memories, semanticAttempted, semanticResultCode,
                UserMemoryRecallStatus.AVAILABLE);
    }

    public static UserMemoryRecallResult disabled() {
        return empty(UserMemoryRecallStatus.DISABLED);
    }

    public static UserMemoryRecallResult notInitialized() {
        return empty(UserMemoryRecallStatus.NOT_INITIALIZED);
    }

    public static UserMemoryRecallResult invalidRequest() {
        return empty(UserMemoryRecallStatus.INVALID_REQUEST);
    }

    public static UserMemoryRecallResult unavailable() {
        return empty(UserMemoryRecallStatus.UNAVAILABLE);
    }

    private static UserMemoryRecallResult empty(UserMemoryRecallStatus status) {
        return new UserMemoryRecallResult(List.of(), false, "SKIPPED", status);
    }
}
