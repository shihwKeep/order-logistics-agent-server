package com.xjjk.agent.chat.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.result.AgentMessageResultEntity;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AfterSaleMessageResultQueryServiceTest {
    @Test
    void restoresAfterSaleListAndDetailFromMessageHistory() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessageResultMapper resultMapper = mock(AgentMessageResultMapper.class);
        AgentMessageEntity assistant = new AgentMessageEntity();
        assistant.setMessageId("assistant-1");
        assistant.setRequestId("request-1");
        assistant.setMessageSequence(2L);
        assistant.setConversationId("conversation-1");
        assistant.setTenantId(1L);
        assistant.setUserId(10567L);
        assistant.setRole("ASSISTANT");
        assistant.setContent("已查询售后");
        assistant.setStatus("SUCCESS");
        assistant.setCreatedAt(LocalDateTime.of(2026, 9, 8, 10, 0));
        when(messageMapper.selectList(any())).thenReturn(List.of(assistant));
        when(resultMapper.selectByMessageIds(
                1L, 10567L, "conversation-1", List.of("assistant-1")))
                .thenReturn(List.of(
                        result(1, "after-sale-list", "{\"items\":[]}"),
                        result(2, "after-sale-detail", "{\"afterSaleCode\":\"AS001\"}")));
        AgentMessageQueryService service = new AgentMessageQueryService(
                conversationService, messageMapper, resultMapper,
                new ObjectMapper().findAndRegisterModules());

        var page = service.queryPage(
                "conversation-1",
                new AgentIdentity(10567L, "account", "name", 23L, 1L),
                null, 20);

        assertThat(page.items()).singleElement().satisfies(message ->
                assertThat(message.results()).extracting(result -> result.kind())
                        .containsExactly("after-sale-list", "after-sale-detail"));
    }

    private AgentMessageResultEntity result(int sequence, String kind, String json) {
        AgentMessageResultEntity entity = new AgentMessageResultEntity();
        entity.setMessageId("assistant-1");
        entity.setResultSequence(sequence);
        entity.setKind(kind);
        entity.setSchemaVersion(1);
        entity.setPayloadJson(json);
        entity.setQueriedAt(LocalDateTime.of(2026, 9, 8, 10, 0));
        return entity;
    }
}
