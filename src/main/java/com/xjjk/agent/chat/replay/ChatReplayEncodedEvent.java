package com.xjjk.agent.chat.replay;

/** 写入 Redis Stream 前完成白名单校验和 JSON 序列化的事件。 */
public record ChatReplayEncodedEvent(
        String type,
        String timestamp,
        String payloadJson,
        int byteLength
) {
}
