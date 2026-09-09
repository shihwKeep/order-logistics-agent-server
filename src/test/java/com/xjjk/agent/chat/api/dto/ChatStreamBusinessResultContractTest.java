package com.xjjk.agent.chat.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.order.domain.OrderAmount;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderGoodsSummary;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderRecipient;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.OrderShipmentSummary;
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
                OrderIdentifierType.AUTO, 1, false, queriedAt, List.of(new OrderCard(
                "O123", "OUT123", 80, "在途", "2026-09-07 12:00:00",
                "石**", 6000L, 6,
                List.of(new OrderGoodsSummary(
                        "商品名称", "SKU001", "50g/袋", 1000L, 6, 6000L, false)),
                "德邦", List.of("MASKED-WAYBILL"),
                194L, "款到发货",
                new OrderAmount(6000L, 0L, 0L, 0L, 6000L),
                new OrderRecipient("石**", "138****1234", "湖南省 常德市 鼎城区"),
                1, false, 1, false,
                List.of(new OrderShipmentSummary(
                        "德邦", "MASKED-WAYBILL", "2026-09-07 12:10:00")))));
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
        JsonNode card = resultJson.path("payload").path("data").path("items").get(0);
        assertThat(card.path("amount").path("goodsTotalInFen").asLong()).isEqualTo(6000L);
        assertThat(card.path("recipient").path("nameMasked").asText()).isEqualTo("石**");
        assertThat(card.path("goods").get(0).path("subtotalInFen").asLong()).isEqualTo(6000L);
        assertThat(card.path("shipments").get(0).path("deliveryTime").asText())
                .isEqualTo("2026-09-07 12:10:00");
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
