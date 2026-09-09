package com.xjjk.agent.customer.tool;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchItem;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerOrderQueryToolsTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "测试坐席", 23L, 1L);

    @Test
    void publishesOrderCardsWithoutExposingInternalCustomerIdToModel() {
        CustomerSearchResult customer = new CustomerSearchResult(
                CustomerMatchType.CUSTOMER_CODE, 1, false, OffsetDateTime.now(),
                List.of(new CustomerSearchItem(80001L, "C001", "张*",
                        "金卡", "自有", "普通客户")));
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 0, false,
                OffsetDateTime.parse("2026-09-08T10:00:00+08:00"), List.of());
        CustomerOrderQueryService service = new CustomerOrderQueryService(
                (keyword, type, identity, requestId) -> customer,
                new CustomerOrdersGateway(orders));
        AtomicReference<ToolUiResult> published = new AtomicReference<>();

        String modelText = new CustomerOrderQueryTools(service).listCustomerOrders(
                " C001 ", context(published));

        assertThat(published.get().toolName()).isEqualTo("list_customer_orders");
        assertThat(published.get().kind()).isEqualTo("order-list");
        assertThat(published.get().data()).isSameAs(orders);
        assertThat(modelText).contains("C001", "张*", "未查询到订单")
                .doesNotContain("80001", "customerId");
    }

    @Test
    void tellsTheModelExactTotalAndCurrentDisplayedCardCountSeparately() {
        CustomerSearchResult customer = new CustomerSearchResult(
                CustomerMatchType.CUSTOMER_CODE, 1, false, OffsetDateTime.now(),
                List.of(new CustomerSearchItem(80001L, "C001", "张*",
                        "金卡", "自有", "普通客户")));
        List<OrderCard> cards = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> new OrderCard(
                        "O-" + index, null, 10, "待发货", "2026-09-08 10:00:00",
                        "张*", 1000L, index, List.of(), null, List.of()))
                .toList();
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 38, true,
                OffsetDateTime.parse("2026-09-08T10:00:00+08:00"), cards);
        CustomerOrderQueryService service = new CustomerOrderQueryService(
                (keyword, type, identity, requestId) -> customer,
                new CustomerOrdersGateway(orders));

        String modelText = new CustomerOrderQueryTools(service).listCustomerOrders(
                "C001", context(new AtomicReference<>()));

        assertThat(modelText)
                .contains("共38笔订单", "当前展示最近5笔订单卡片", "其余33笔未展示")
                .doesNotContain("匹配总数=38");
    }

    private ToolContext context(AtomicReference<ToolUiResult> published) {
        AgentToolRequestContext context = new AgentToolRequestContext(
                "request-1", IDENTITY, published::set, new ToolCallGuard(3));
        return new ToolContext(Map.of(AgentToolRequestContext.CONTEXT_KEY, context));
    }

    private record CustomerOrdersGateway(OrderSearchResult orders) implements OrderQueryGateway {
        @Override
        public OrderSearchResult search(String identifier, OrderIdentifierType identifierType,
                                        AgentIdentity identity, String requestId) {
            throw new AssertionError("不应按业务编号查询");
        }

        @Override
        public OrderSearchResult searchByCustomerId(long customerId, AgentIdentity identity,
                                                    String requestId) {
            return orders;
        }

        @Override
        public OrderLogisticsResult logistics(String identifier, OrderIdentifierType identifierType,
                                              AgentIdentity identity, String requestId) {
            throw new AssertionError("不应查询物流");
        }
    }
}
