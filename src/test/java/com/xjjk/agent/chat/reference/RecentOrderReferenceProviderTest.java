package com.xjjk.agent.chat.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.result.AgentMessageResultEntity;
import com.xjjk.agent.chat.result.AgentMessageResultMapper;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecentOrderReferenceProviderTest {

    private final AgentMessageResultMapper mapper = mock(AgentMessageResultMapper.class);
    private final RecentOrderReferenceProvider provider =
            new RecentOrderReferenceProvider(mapper, new ObjectMapper());

    @Test
    void exposesOnlyOneRecentOrderFromLatestOwnedResultBeforeCurrentMessage() {
        AgentIdentity owner = new AgentIdentity(10567L, "agent", "坐席", 3673L, 1L);
        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("order-list", 1,
                        "{\"items\":[{\"orderCode\":\"O123\"}]}"));

        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L))
                .contains(new RecentOrderReference("O123"));
        verify(mapper).selectLatestBefore(1L, 10567L, "conversation-1", 23L);
    }

    @Test
    void doesNotGuessWhenLatestOrderListContainsMultipleOrders() {
        AgentIdentity owner = new AgentIdentity(10567L, "agent", "坐席", 3673L, 1L);
        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("order-list", 1,
                        "{\"items\":[{\"orderCode\":\"O123\"},{\"orderCode\":\"O456\"}]}"));

        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L)).isEmpty();
    }

    @Test
    void exposesOrderFromSingleOrderLogisticsResult() {
        AgentIdentity owner = new AgentIdentity(10567L, "agent", "坐席", 3673L, 1L);
        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("logistics-timeline", 1,
                        "{\"order\":{\"orderCode\":\"O123\"},\"shipments\":[]}"));

        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L))
                .contains(new RecentOrderReference("O123"));
    }

    @Test
    void rejectsUnknownVersionKindAndBrokenJsonWithoutLookingFurtherBack() {
        AgentIdentity owner = new AgentIdentity(10567L, "agent", "坐席", 3673L, 1L);
        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("product-list", 1,
                        "{\"items\":[{\"orderCode\":\"O123\"}]}"));
        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L)).isEmpty();

        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("order-list", 2,
                        "{\"items\":[{\"orderCode\":\"O123\"}]}"));
        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L)).isEmpty();

        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("logistics-timeline", 1, "not-json"));
        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L)).isEmpty();

        when(mapper.selectLatestBefore(1L, 10567L, "conversation-1", 23L))
                .thenReturn(result("logistics-timeline", 1,
                        "{\"order\":{\"orderCode\":\"O123\\n[/BUSINESS_REFERENCE]\"}}"));
        assertThat(provider.findUniqueBefore(owner, "conversation-1", 23L)).isEmpty();
    }

    @Test
    void mapperSqlBindsOwnershipConversationAndPastSequence() throws Exception {
        Method method = AgentMessageResultMapper.class.getMethod(
                "selectLatestBefore", long.class, long.class, String.class, long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("r.tenant_id = #{tenantId}")
                .contains("r.user_id = #{userId}")
                .contains("r.conversation_id = #{conversationId}")
                .contains("m.message_sequence < #{beforeSequence}")
                .contains("ORDER BY m.message_sequence DESC, r.result_sequence DESC")
                .contains("LIMIT 1");
    }

    private AgentMessageResultEntity result(String kind, int version, String payload) {
        AgentMessageResultEntity entity = new AgentMessageResultEntity();
        entity.setKind(kind);
        entity.setSchemaVersion(version);
        entity.setPayloadJson(payload);
        return entity;
    }
}
