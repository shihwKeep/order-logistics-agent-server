package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Redis Stream 字段的严格编解码器。 */
public final class ChatReplayEventCodec {

    private static final Set<String> EVENT_TYPES = Set.of(
            "session", "heartbeat", "status", "delta", "result", "done", "error");

    private final ObjectMapper objectMapper;

    public ChatReplayEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 编解码器不能为空");
    }

    public ChatReplayEncodedEvent encode(String type, Instant timestamp, Object payload) {
        requireType(type);
        Objects.requireNonNull(timestamp, "事件时间不能为空");
        Objects.requireNonNull(payload, "事件负载不能为空");
        try {
            String timestampText = timestamp.toString();
            String payloadJson = objectMapper.writeValueAsString(payload);
            int byteLength = Math.addExact(
                    type.getBytes(StandardCharsets.UTF_8).length,
                    Math.addExact(
                            timestampText.getBytes(StandardCharsets.UTF_8).length,
                            payloadJson.getBytes(StandardCharsets.UTF_8).length));
            return new ChatReplayEncodedEvent(type, timestampText, payloadJson, byteLength);
        } catch (JsonProcessingException | ArithmeticException exception) {
            throw new ChatReplayUnavailableException("聊天事件序列化失败", exception);
        }
    }

    public ChatReplayEvent decode(long sequence, Map<?, ?> fields) {
        if (sequence <= 0 || fields == null) {
            throw new ChatReplayUnavailableException("Redis 聊天事件不合法");
        }
        try {
            String type = stringField(fields, "type");
            requireType(type);
            Instant timestamp = Instant.parse(stringField(fields, "timestamp"));
            JsonNode payload = objectMapper.readTree(stringField(fields, "payload"));
            if (payload == null || !payload.isObject()) {
                throw new ChatReplayUnavailableException("Redis 聊天事件负载不合法");
            }
            return new ChatReplayEvent(sequence, type, timestamp, payload);
        } catch (JsonProcessingException | DateTimeParseException | IllegalArgumentException exception) {
            if (exception instanceof ChatReplayUnavailableException unavailable) {
                throw unavailable;
            }
            throw new ChatReplayUnavailableException("Redis 聊天事件解析失败", exception);
        }
    }

    private static String stringField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new ChatReplayUnavailableException("Redis 聊天事件缺少字段: " + name);
        }
        return text;
    }

    private static void requireType(String type) {
        if (!EVENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("聊天事件类型不合法");
        }
    }
}
