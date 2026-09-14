package com.xjjk.agent.chat.api.dto;

import com.xjjk.agent.chat.replay.ChatReplaySnapshot;

import java.time.Instant;

/** 客户端断线后查询本轮任务状态所需的安全字段。 */
public record ChatStreamStatusResponse(
        String conversationId,
        String requestId,
        String state,
        Instant createdAt,
        Instant expiresAt,
        long lastSequence,
        String terminalCode,
        String terminalMessageId
) {
    public static ChatStreamStatusResponse from(ChatReplaySnapshot snapshot) {
        return new ChatStreamStatusResponse(
                snapshot.conversationId(),
                snapshot.requestId(),
                snapshot.state().name(),
                snapshot.createdAt(),
                snapshot.expiresAt(),
                snapshot.lastSequence(),
                snapshot.terminalCode(),
                snapshot.terminalMessageId());
    }
}
