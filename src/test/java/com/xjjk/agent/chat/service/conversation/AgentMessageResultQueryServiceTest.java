package com.xjjk.agent.chat.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatMessagePageResponse;
import com.xjjk.agent.chat.api.dto.ChatMessageResponse;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.result.AgentMessageResultEntity;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMessageResultQueryServiceTest {

    @Mock
    private AgentConversationService conversationService;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentMessageResultMapper resultMapper;

    @Test
    void batchLoadsResultsAndSkipsOnlyInvalidOrUnsupportedRows() {
        AgentIdentity identity = new AgentIdentity(
                10567, "account", "name", 3673, 1);
        AgentMessageEntity assistant = message(
                "assistant-1", 2, "ASSISTANT", "已查询");
        AgentMessageEntity user = message(
                "user-1", 1, "USER", "查询订单");
        when(messageMapper.selectList(any()))
                .thenReturn(List.of(assistant, user));
        when(resultMapper.selectByMessageIds(
                1, 10567, "conversation-1", List.of("user-1", "assistant-1")))
                .thenReturn(List.of(
                        result("assistant-1", 1, "order-list", 1,
                                "{\"items\":[{\"orderCode\":\"O123\","
                                        + "\"amount\":{\"goodsTotalInFen\":6000},"
                                        + "\"recipient\":{\"nameMasked\":\"石**\"},"
                                        + "\"goods\":[{\"subtotalInFen\":6000}],"
                                        + "\"shipments\":[{\"deliveryTime\":"
                                        + "\"2026-09-07 12:10:00\"}]}]}"),
                        result("assistant-1", 2, "order-list", 1, "{"),
                        result("assistant-1", 4, "customer-list", 1,
                                "{\"items\":[]}"),
                        result("assistant-1", 5, "knowledge-citations", 1,
                                "{\"answerable\":true,\"evidences\":[{"
                                        + "\"documentId\":12,\"documentVersionId\":3,"
                                        + "\"chunkId\":99,\"documentTitle\":\"退款规则\","
                                        + "\"titlePath\":\"售后/退款\",\"text\":\"签收后七天内\","
                                        + "\"score\":0.91,\"sources\":[\"VECTOR\"]}],"
                                        + "\"retrievalVersion\":\"hybrid-v1\","
                                        + "\"decision\":\"ANSWERABLE\","
                                        + "\"reasonCode\":\"ENOUGH_EVIDENCE\","
                                        + "\"queriedAt\":\"2026-09-09T12:00:00+08:00\"}"),
                        result("assistant-1", 3, "future-card", 2, "{}")));
        AgentMessageQueryService service = new AgentMessageQueryService(
                conversationService, messageMapper, resultMapper,
                new ObjectMapper().findAndRegisterModules());

        ChatMessagePageResponse page = service.queryPage(
                "conversation-1", identity, null, 20);

        assertThat(page.items()).extracting(ChatMessageResponse::messageId)
                .containsExactly("user-1", "assistant-1");
        assertThat(page.items().get(0).results()).isEmpty();
        assertThat(page.items().get(1).results()).hasSize(3);
        assertThat(page.items().get(1).results().get(0)).satisfies(item -> {
            assertThat(item.resultSequence()).isEqualTo(1);
            assertThat(item.kind()).isEqualTo("order-list");
            assertThat(item.schemaVersion()).isEqualTo(1);
            assertThat(item.data().path("items").isArray()).isTrue();
            assertThat(item.data().path("items").get(0)
                    .path("amount").path("goodsTotalInFen").asLong()).isEqualTo(6000L);
            assertThat(item.data().path("items").get(0)
                    .path("recipient").path("nameMasked").asText()).isEqualTo("石**");
            assertThat(item.data().path("items").get(0)
                    .path("shipments").get(0).path("deliveryTime").asText())
                    .isEqualTo("2026-09-07 12:10:00");
        });
        assertThat(page.items().get(1).results().get(1).kind())
                .isEqualTo("customer-list");
        assertThat(page.items().get(1).results().get(2).kind())
                .isEqualTo("knowledge-citations");
        verify(resultMapper).selectByMessageIds(
                1, 10567, "conversation-1", List.of("user-1", "assistant-1"));
    }

    private AgentMessageEntity message(
            String messageId, long sequence, String role, String content) {
        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setMessageId(messageId);
        entity.setConversationId("conversation-1");
        entity.setTenantId(1L);
        entity.setUserId(10567L);
        entity.setRequestId("request-" + sequence);
        entity.setMessageSequence(sequence);
        entity.setRole(role);
        entity.setContent(content);
        entity.setStatus("SUCCESS");
        entity.setCreatedAt(LocalDateTime.of(2026, 9, 7, 10, 15));
        return entity;
    }

    private AgentMessageResultEntity result(
            String messageId,
            int sequence,
            String kind,
            int schemaVersion,
            String payloadJson) {
        AgentMessageResultEntity entity = new AgentMessageResultEntity();
        entity.setMessageId(messageId);
        entity.setResultSequence(sequence);
        entity.setKind(kind);
        entity.setSchemaVersion(schemaVersion);
        entity.setPayloadJson(payloadJson);
        entity.setQueriedAt(LocalDateTime.of(2026, 9, 7, 10, 15, 30));
        return entity;
    }
}
