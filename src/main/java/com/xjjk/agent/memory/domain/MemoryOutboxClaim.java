package com.xjjk.agent.memory.domain;

import java.time.LocalDateTime;

/** 已由当前实例持有的 Outbox 任务租约。 */
public record MemoryOutboxClaim(
        long id,
        String eventId,
        String memoryId,
        long tenantId,
        long userId,
        long memoryGeneration,
        long memoryVersion,
        MemoryOutboxOperation operation,
        int retryCount,
        String leaseToken,
        String lockedBy,
        LocalDateTime lockedUntil) {

    public MemoryOutboxClaim {
        if (id <= 0 || eventId == null || eventId.isBlank()
                || tenantId <= 0 || userId <= 0 || memoryGeneration <= 0
                || memoryVersion < 0 || operation == null || retryCount < 0
                || leaseToken == null || leaseToken.isBlank()
                || lockedBy == null || lockedBy.isBlank() || lockedUntil == null) {
            throw new IllegalArgumentException("Outbox 租约不合法");
        }
    }
}
