package com.xjjk.agent.chat.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatResultProperties;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatToolResultRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preparesImmutablePersistenceSnapshotBeforePublication() {
        ChatToolResultRecorder recorder = recorder(1024);
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");

        PendingMessageResult pending = recorder.prepare(new ToolUiResult(
                "search_orders", "order-list", 1, queriedAt,
                Map.of("items", java.util.List.of(Map.of("orderCode", "O1001")))), 1);

        assertThat(pending.resultSequence()).isEqualTo(1);
        assertThat(pending.toolName()).isEqualTo("search_orders");
        assertThat(pending.kind()).isEqualTo("order-list");
        assertThat(pending.schemaVersion()).isEqualTo(1);
        assertThat(pending.queriedAt()).isEqualTo(queriedAt);
        assertThat(pending.payloadJson()).contains("\"orderCode\":\"O1001\"");
        assertThat(pending.payloadBytes())
                .isEqualTo(pending.payloadJson().getBytes(StandardCharsets.UTF_8).length);
        assertThat(pending.toString()).doesNotContain("O1001", "payloadJson");
    }

    @Test
    void rejectsOversizedResultUsingUtf8BytesRatherThanCharacterCount() {
        String unicodePayload = "中".repeat(400);
        ToolUiResult result = new ToolUiResult(
                "get_order_logistics", "logistics-timeline", 1,
                OffsetDateTime.parse("2026-09-07T10:15:30+08:00"),
                Map.of("trace", unicodePayload));

        assertThatThrownBy(() -> recorder(1024).prepare(result, 1))
                .isInstanceOf(ToolResultTooLargeException.class)
                .hasMessageContaining("1024");
    }

    @Test
    void rejectsDisabledRecorderAndInvalidMetadataBeforeSerialization() {
        ChatToolResultRecorder disabled = new ChatToolResultRecorder(
                objectMapper, new ChatResultProperties(false, 1024));
        ToolUiResult valid = new ToolUiResult(
                "search_orders", "order-list", 1,
                OffsetDateTime.parse("2026-09-07T10:15:30+08:00"), Map.of());

        assertThatThrownBy(() -> disabled.prepare(valid, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未启用");
        assertThatThrownBy(() -> recorder(1024).prepare(valid, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder(1024).prepare(
                new ToolUiResult("bad tool", "order-list", 1,
                        valid.queriedAt(), Map.of()), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder(1024).prepare(
                new ToolUiResult("search_orders", "order-list", 0,
                        valid.queriedAt(), Map.of()), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder(1024).prepare(
                new ToolUiResult("search_orders", "order-list", 1,
                        null, Map.of()), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder(1024).prepare(
                new ToolUiResult("search_orders", "order-list", 1,
                        valid.queriedAt(), null), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesConfigurationAgainstDatabaseColumnCapacity() {
        assertThatThrownBy(() -> new ChatResultProperties(true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatResultProperties(true, 16_777_216))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsSerializationFailuresToSafeDomainException() {
        ToolUiResult result = new ToolUiResult(
                "search_orders", "order-list", 1,
                OffsetDateTime.parse("2026-09-07T10:15:30+08:00"),
                Map.of("bad", new SelfReference()));

        assertThatThrownBy(() -> recorder(1024).prepare(result, 1))
                .isInstanceOf(ToolResultSerializationException.class)
                .hasMessage("结构化工具结果序列化失败")
                .hasNoCause();
    }

    private ChatToolResultRecorder recorder(int maxPayloadBytes) {
        return new ChatToolResultRecorder(
                objectMapper, new ChatResultProperties(true, maxPayloadBytes));
    }

    private static final class SelfReference {
        public SelfReference getSelf() {
            return this;
        }
    }
}
