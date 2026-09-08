package com.xjjk.agent.chat.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSaleBusinessResultContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesAfterSaleDetailAsVersionedBusinessResult() throws Exception {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-08T18:00:00+08:00");
        AfterSaleDetailResult detail = new AfterSaleDetailResult(
                "AS001", 1, "处理中", queriedAt, false, null,
                "张*", "C001", "XJTS01", null, null, null,
                List.of(), List.of(),
                new AfterSaleDetailResult.RefundSummary(0L, 0L, 0L, 0L, 0L),
                false, false, queriedAt);
        CapturingEmitter emitter = new CapturingEmitter();
        ChatSseSession session = new ChatSseSession(emitter);

        session.result(new ToolUiResult(
                "get_after_sale_detail", "after-sale-detail", 1, queriedAt, detail));

        JsonNode event = objectMapper.valueToTree(emitter.events.getFirst());
        assertThat(event.path("type").asText()).isEqualTo("result");
        assertThat(event.path("payload").path("kind").asText())
                .isEqualTo("after-sale-detail");
        assertThat(event.path("payload").path("data").path("afterSaleCode").asText())
                .isEqualTo("AS001");
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
