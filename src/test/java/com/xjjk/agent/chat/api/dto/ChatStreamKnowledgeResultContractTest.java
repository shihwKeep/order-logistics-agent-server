package com.xjjk.agent.chat.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamKnowledgeResultContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void emitsServerOwnedCitationMetadataWithoutTheInternalToolName() throws Exception {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-09T12:00:00+08:00");
        KnowledgeRetrievalResult data = new KnowledgeRetrievalResult(
                true,
                List.of(new KnowledgeRetrievalResult.Evidence(
                        1L, 2L, 3L, "2:3:0", "售后退款规则", "退款 / 时限",
                        "签收后七日内符合条件可申请退款。", "{\"pageNumber\":3}",
                        0.91D, Set.of("VECTOR", "KEYWORD"))),
                "hybrid-v1", "NONE", "OK", queriedAt);
        CapturingEmitter emitter = new CapturingEmitter();

        new ChatSseSession(emitter).result(new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1, queriedAt, data));

        JsonNode event = objectMapper.valueToTree(emitter.events.getFirst());
        assertThat(event.path("type").asText()).isEqualTo("result");
        assertThat(event.path("payload").path("kind").asText())
                .isEqualTo("knowledge-citations");
        assertThat(event.path("payload").path("data").path("evidences").get(0)
                .path("documentTitle").asText()).isEqualTo("售后退款规则");
        assertThat(event.toString()).doesNotContain("search_knowledge");
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<Object> events = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof ChatStreamEvent<?> event) events.add(event);
            }
        }
    }
}
