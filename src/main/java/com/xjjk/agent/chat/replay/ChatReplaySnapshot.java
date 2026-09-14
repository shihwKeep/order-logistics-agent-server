package com.xjjk.agent.chat.replay;

import java.time.Instant;

/** 状态接口返回所需的 Redis 可恢复任务快照。 */
public record ChatReplaySnapshot(
        String conversationId,
        String requestId,
        ChatReplayState state,
        Instant createdAt,
        Instant expiresAt,
        long lastSequence,
        String terminalCode,
        String terminalMessageId
) {
}
