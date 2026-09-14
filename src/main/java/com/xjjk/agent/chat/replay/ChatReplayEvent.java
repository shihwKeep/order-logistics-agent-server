package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** 从 Redis Stream 恢复出的可信聊天事件。 */
public record ChatReplayEvent(
        long sequence,
        String type,
        Instant timestamp,
        JsonNode payload
) {
}
