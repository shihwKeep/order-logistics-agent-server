package com.xjjk.agent.chat.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.tool.ToolUiResult;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamBusinessResultContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesCompleteResultMetadataAndKeepsEventSequenceIncreasing() throws Exception {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");
        OrderSearchResult result = new OrderSearchResult(
                OrderIdentifierType.AUTO, 0, false, queriedAt, List.of());
        CapturingEmitter emitter = new CapturingEmitter();
        ChatSseSession session = new ChatSseSession(emitter);

        session.session("conversation-1", "request-1");
        session.result(new ToolUiResult(
                "search_orders", "order-list", 1, queriedAt, result));

        assertThat(emitter.events).hasSize(2);
        JsonNode sessionJson = objectMapper.valueToTree(emitter.events.get(0));
        JsonNode resultJson = objectMapper.valueToTree(emitter.events.get(1));
        assertThat(sessionJson.path("sequence").asLong()).isEqualTo(1L);
        assertThat(resultJson.path("sequence").asLong()).isEqualTo(2L);
        assertThat(resultJson.path("type").asText()).isEqualTo("result");
        assertThat(resultJson.path("payload").path("kind").asText()).isEqualTo("order-list");
        assertThat(resultJson.path("payload").path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(resultJson.path("payload").path("queriedAt").asText())
                .isEqualTo("2026-09-07T10:15:30+08:00");
        assertThat(resultJson.path("payload").path("data").path("matchedBy").asText())
                .isEqualTo("AUTO");
        assertThat(resultJson.path("payload").has("toolName")).isFalse();
        assertThat(resultJson.toString()).doesNotContain("search_orders");
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<Object> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof ChatStreamEvent<?> event) {
                    events.add(event);
                }
            }
        }
    }
}
