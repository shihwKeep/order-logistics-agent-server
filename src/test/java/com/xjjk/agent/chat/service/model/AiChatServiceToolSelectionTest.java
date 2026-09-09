package com.xjjk.agent.chat.service.model;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.domain.AfterSaleSearchResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.aftersale.tool.AfterSaleQueryTools;
import com.xjjk.agent.aftersale.tool.AfterSaleToolAvailability;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.tool.CustomerOrderQueryTools;
import com.xjjk.agent.customer.tool.CustomerQueryTools;
import com.xjjk.agent.customer.tool.CustomerToolAvailability;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.tool.OrderQueryTools;
import com.xjjk.agent.order.tool.OrderToolAvailability;
import com.xjjk.agent.product.tool.ProductQueryTools;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.tool.KnowledgeQueryTools;
import com.xjjk.agent.knowledge.tool.KnowledgeToolAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;
import java.time.OffsetDateTime;

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

    @Test
    void selectsCustomerAndCustomerOrderToolsIndependently() {
        assertThat(toolNames(service(
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                capability(true, "ALL", Set.of()),
                customerCapability(false, "OFF", Set.of()))))
                .containsExactly("search_products", "list_customer_orders");

        assertThat(toolNames(service(
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                customerCapability(true, "ALLOWLIST", Set.of(23L)))))
                .containsExactly("search_products", "search_customers");
    }

    @Test
    void selectsAfterSaleSearchAndDetailIndependently() {
        assertThat(toolNames(service(
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                customerCapability(false, "OFF", Set.of()),
                afterSaleCapability(true, "ALLOWLIST", Set.of(23L)),
                afterSaleCapability(false, "ALL", Set.of()))))
                .containsExactly("search_products", "search_after_sales");

        assertThat(toolNames(service(
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                customerCapability(false, "OFF", Set.of()),
                afterSaleCapability(false, "OFF", Set.of()),
                afterSaleCapability(true, "ALL", Set.of()))))
                .containsExactly("search_products", "get_after_sale_detail");
    }

    @Test
    void registersKnowledgeOnlyForEnabledOrganizations() {
        KnowledgeToolAvailability enabled = new KnowledgeToolAvailability(
                true, "ALLOWLIST", Set.of(23L));
        AiChatService withKnowledge = service(
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                customerCapability(false, "OFF", Set.of()),
                afterSaleCapability(false, "OFF", Set.of()),
                afterSaleCapability(false, "OFF", Set.of()),
                enabled);

        assertThat(toolNames(withKnowledge)).contains("search_knowledge");
    }

    private AiChatService service(
            OrderToolAvailability.Capability order,
            OrderToolAvailability.Capability logistics) {
        return service(order, logistics, capability(false, "OFF", Set.of()),
                customerCapability(false, "OFF", Set.of()),
                afterSaleCapability(false, "OFF", Set.of()),
                afterSaleCapability(false, "OFF", Set.of()));
    }

    private AiChatService service(
            OrderToolAvailability.Capability order,
            OrderToolAvailability.Capability logistics,
            OrderToolAvailability.Capability customerOrder,
            CustomerToolAvailability.Capability customer) {
        return service(order, logistics, customerOrder, customer,
                afterSaleCapability(false, "OFF", Set.of()),
                afterSaleCapability(false, "OFF", Set.of()));
    }

    private AiChatService service(
            OrderToolAvailability.Capability order,
            OrderToolAvailability.Capability logistics,
            OrderToolAvailability.Capability customerOrder,
            CustomerToolAvailability.Capability customer,
            AfterSaleToolAvailability.Capability afterSaleSearch,
            AfterSaleToolAvailability.Capability afterSaleDetail) {
        return service(order, logistics, customerOrder, customer,
                afterSaleSearch, afterSaleDetail,
                new KnowledgeToolAvailability(false, "OFF", Set.of()));
    }

    private AiChatService service(
            OrderToolAvailability.Capability order,
            OrderToolAvailability.Capability logistics,
            OrderToolAvailability.Capability customerOrder,
            CustomerToolAvailability.Capability customer,
            AfterSaleToolAvailability.Capability afterSaleSearch,
            AfterSaleToolAvailability.Capability afterSaleDetail,
            KnowledgeToolAvailability knowledgeAvailability) {
        ProductQueryTools productTools = new ProductQueryTools(query -> {
            throw new AssertionError("工具选择测试不应执行商品查询");
        });
        UnusedOrderGateway orderGateway = new UnusedOrderGateway();
        OrderQueryTools orderTools = new OrderQueryTools(orderGateway);
        CustomerQueryTools customerTools = new CustomerQueryTools(
                (keyword, type, identity, requestId) -> new CustomerSearchResult(
                        CustomerMatchType.CUSTOMER_CODE, 0, false,
                        OffsetDateTime.now(), List.of()));
        CustomerOrderQueryTools customerOrderTools = new CustomerOrderQueryTools(
                new CustomerOrderQueryService(
                        (keyword, type, identity, requestId) -> new CustomerSearchResult(
                                CustomerMatchType.CUSTOMER_CODE, 0, false,
                                OffsetDateTime.now(), List.of()),
                        orderGateway));
        AfterSaleToolAvailability afterSaleAvailability =
                new AfterSaleToolAvailability(afterSaleSearch, afterSaleDetail);
        AfterSaleQueryTools afterSaleTools = new AfterSaleQueryTools(
                new UnusedAfterSaleGateway(), afterSaleAvailability);
        KnowledgeQueryTools knowledgeTools = new KnowledgeQueryTools(
                (question, ids, identity, requestId) -> new KnowledgeRetrievalResult(
                        false, List.of(), "v1", "NONE", "EMPTY", OffsetDateTime.now()),
                knowledgeAvailability);
        return new AiChatService(
                mock(ChatClient.class),
                productTools,
                orderTools,
                new OrderToolAvailability(order, logistics, customerOrder),
                customerTools,
                customerOrderTools,
                new CustomerToolAvailability(customer),
                afterSaleTools,
                afterSaleAvailability,
                knowledgeTools,
                knowledgeAvailability);
    }

    private CustomerToolAvailability.Capability customerCapability(
            boolean enabled,
            String rolloutMode,
            Set<Long> allowedOrgIds) {
        return new CustomerToolAvailability.Capability(
                enabled, rolloutMode, allowedOrgIds);
    }

    private OrderToolAvailability.Capability capability(
            boolean enabled,
            String rolloutMode,
            Set<Long> allowedOrgIds) {
        return new OrderToolAvailability.Capability(
                enabled, rolloutMode, allowedOrgIds);
    }

    private AfterSaleToolAvailability.Capability afterSaleCapability(
            boolean enabled,
            String rolloutMode,
            Set<Long> allowedOrgIds) {
        return new AfterSaleToolAvailability.Capability(
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

    private static final class UnusedAfterSaleGateway implements AfterSaleQueryGateway {
        @Override
        public AfterSaleSearchResult search(
                AfterSaleIdentifierType type,
                String identifier,
                OffsetDateTime startTime,
                OffsetDateTime endTime,
                AgentIdentity identity,
                String requestId) {
            throw new AssertionError("工具选择测试不应执行售后搜索");
        }

        @Override
        public AfterSaleDetailResult detail(
                String afterSaleCode,
                AgentIdentity identity,
                String requestId) {
            throw new AssertionError("工具选择测试不应执行售后详情查询");
        }
    }
}
