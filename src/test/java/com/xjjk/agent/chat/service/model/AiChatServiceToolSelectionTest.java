package com.xjjk.agent.chat.service.model;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.tool.OrderQueryTools;
import com.xjjk.agent.order.tool.OrderToolAvailability;
import com.xjjk.agent.product.tool.ProductQueryTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiChatServiceToolSelectionTest {

    private static final AgentIdentity ORG_23 =
            new AgentIdentity(10567L, "10567", "测试坐席", 23L, 1L);

    @Test
    void alwaysRegistersProductAndSelectsOrderAndLogisticsIndependently() {
        assertThat(toolNames(service(
                capability(false, "ALL", Set.of(23L)),
                capability(false, "ALL", Set.of(23L)))))
                .containsExactly("search_products");

        assertThat(toolNames(service(
                capability(true, "ALLOWLIST", Set.of(23L)),
                capability(true, "ALLOWLIST", Set.of(99L)))))
                .containsExactly("search_products", "search_orders");

        assertThat(toolNames(service(
                capability(true, "OFF", Set.of(23L)),
                capability(true, "ALL", Set.of()))))
                .containsExactly("search_products", "get_order_logistics");

        assertThat(toolNames(service(
                capability(true, "ALL", Set.of()),
                capability(true, "ALL", Set.of()))))
                .containsExactly(
                        "search_products", "search_orders", "get_order_logistics");
    }

    private AiChatService service(
            OrderToolAvailability.Capability order,
            OrderToolAvailability.Capability logistics) {
        ProductQueryTools productTools = new ProductQueryTools(query -> {
            throw new AssertionError("工具选择测试不应执行商品查询");
        });
        OrderQueryTools orderTools = new OrderQueryTools(new UnusedOrderGateway());
        return new AiChatService(
                mock(ChatClient.class),
                productTools,
                orderTools,
                new OrderToolAvailability(order, logistics));
    }

    private OrderToolAvailability.Capability capability(
            boolean enabled,
            String rolloutMode,
            Set<Long> allowedOrgIds) {
        return new OrderToolAvailability.Capability(
                enabled, rolloutMode, allowedOrgIds);
    }

    private List<String> toolNames(AiChatService service) {
        return service.selectToolCallbacks(ORG_23).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

    private static final class UnusedOrderGateway implements OrderQueryGateway {
        @Override
        public OrderSearchResult search(
                String identifier,
                OrderIdentifierType identifierType,
                AgentIdentity identity,
                String requestId) {
            throw new AssertionError("工具选择测试不应执行订单查询");
        }

        @Override
        public OrderLogisticsResult logistics(
                String identifier,
                OrderIdentifierType identifierType,
                AgentIdentity identity,
                String requestId) {
            throw new AssertionError("工具选择测试不应执行物流查询");
        }
    }
}
