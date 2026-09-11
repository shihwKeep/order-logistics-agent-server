package com.xjjk.agent.memory.domain;

import java.time.LocalDateTime;

/** 已领取的隐式记忆任务，不携带消息正文。 */
public record MemoryExtractionTaskClaim(
        long id,
        String taskId,
        long tenantId,
        long userId,
        String conversationId,
        String requestId,
        String userMessageId,
        long userMessageSequence,
        long memoryGeneration,
        int retryCount,
        String leaseToken,
        String lockedBy,
        LocalDateTime lockedUntil
) {
}
