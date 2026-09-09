package com.xjjk.agent.order.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderAmount;
import com.xjjk.agent.order.domain.OrderGoodsSummary;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderRecipient;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.OrderShipmentSummary;
import com.xjjk.agent.order.domain.ShipmentTimeline;
import com.xjjk.agent.order.domain.TrackNode;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

class OrderQueryToolsTest {

    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "测试坐席", 23L, 1L);

    @Test
    void searchPublishesFullResultButReturnsBoundedSafeModelText() {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");
        List<OrderGoodsSummary> goods = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> new OrderGoodsSummary(
                        "测试商品" + index, "SENSITIVE-SKU-" + index, "红色",
                        995L, 1, 995L, false))
                .toList();
        List<OrderShipmentSummary> shipments = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> new OrderShipmentSummary(
                        "顺丰", "SENSITIVE-WAYBILL-" + index, "2026-09-07 09:00:00"))
                .toList();
        OrderSearchResult result = new OrderSearchResult(
                OrderIdentifierType.ORDER_CODE,
                1,
                false,
                queriedAt,
                List.of(new OrderCard(
                        "ORDER-1001",
                        "OUTER-SECRET",
                        30,
                        "已发货",
                        "2026-09-07 09:00:00",
                        "不应进入模型的客户名",
                        19900L,
                        20,
                        goods,
                        "顺丰",
                        List.of("SENSITIVE-WAYBILL-1"),
                        194L,
                        "款到发货",
                        new OrderAmount(19900L, 0L, 0L, 0L, 19900L),
                        new OrderRecipient(
                                "不应进入模型的客户名", "138****1234", "湖南省 常德市 鼎城区"),
                        20,
                        false,
                        10,
                        false,
                        shipments)));
        AtomicReference<ToolUiResult> published = new AtomicReference<>();
        RecordingGateway gateway = new RecordingGateway(result, null);
        OrderQueryTools tools = new OrderQueryTools(gateway);

        String modelText = tools.searchOrders(
                "  ORDER-1001  ",
                " order_code ",
                context(published::set, new ToolCallGuard(3)));

        assertThat(gateway.lastIdentifier).isEqualTo("ORDER-1001");
        assertThat(gateway.lastType).isEqualTo(OrderIdentifierType.ORDER_CODE);
        assertThat(gateway.lastIdentity).isSameAs(IDENTITY);
        assertThat(gateway.lastRequestId).isEqualTo("request-1");
        assertThat(published.get()).isEqualTo(new ToolUiResult(
                "search_orders", "order-list", 1, queriedAt, result));
        assertThat(modelText).contains(
                "查询时间", "ORDER-1001", "已发货", "商品数量=20", "前端已展示");
        assertThat(modelText)
                .doesNotContain(
                        "不应进入模型的客户名", "138****1234", "湖南省 常德市 鼎城区",
                        "SENSITIVE-SKU", "SENSITIVE-WAYBILL", "19900",
                        "OUTER-SECRET", "|---", "Markdown")
                .hasSizeLessThan(2000);
    }

    @Test
    void emptySearchStillPublishesResultAndReturnsShortNotFoundText() {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");
        OrderSearchResult result = new OrderSearchResult(
                OrderIdentifierType.AUTO, 0, false, queriedAt, List.of());
        AtomicReference<ToolUiResult> published = new AtomicReference<>();
        OrderQueryTools tools = new OrderQueryTools(new RecordingGateway(result, null));

        String modelText = tools.searchOrders(
                "NO-ORDER", null, context(published::set, new ToolCallGuard(3)));

        assertThat(published.get().data()).isSameAs(result);
        assertThat(published.get().queriedAt()).isEqualTo(queriedAt);
        assertThat(modelText).contains("未查询到", "查询时间").hasSizeLessThan(300);
    }

    @Test
    void logisticsPublishesFullTimelineButReturnsOnlyLatestTracePerShipment() {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");
        List<TrackNode> traces = new ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            traces.add(new TrackNode(
                    "2026-09-07 10:" + index,
                    "节点" + index,
                    "完整轨迹正文-" + index));
        }
        OrderLogisticsResult result = new OrderLogisticsResult(
                new OrderLogisticsResult.OrderSummary("ORDER-1001", 30, "已发货"),
                queriedAt,
                true,
                List.of(new ShipmentTimeline(
                        "SF1001", "顺丰", "SUCCESS", "运输中",
                        "最新轨迹仅此一条", traces)));
        AtomicReference<ToolUiResult> published = new AtomicReference<>();
        OrderQueryTools tools = new OrderQueryTools(new RecordingGateway(null, result));

        String modelText = tools.getOrderLogistics(
                " SF1001 ", "LOGISTICS_CODE",
                context(published::set, new ToolCallGuard(3)));

        assertThat(published.get()).isEqualTo(new ToolUiResult(
                "get_order_logistics", "logistics-timeline", 1, queriedAt, result));
        assertThat(((OrderLogisticsResult) published.get().data()).shipments().get(0).traces())
                .hasSize(50);
        assertThat(modelText).contains(
                "查询时间", "ORDER-1001", "SF1001", "运输中", "最新轨迹仅此一条", "部分");
        assertThat(modelText)
                .doesNotContain("完整轨迹正文-1", "完整轨迹正文-50")
                .hasSizeLessThan(2000);
    }

    @Test
    void rejectsUnknownTypeControlCharactersAndOverlongIdentifiersBeforeGatewayOrGuard() {
        RecordingGateway gateway = new RecordingGateway(null, null);
        OrderQueryTools tools = new OrderQueryTools(gateway);
        ToolCallGuard guard = new ToolCallGuard(1);
        ToolContext context = context(ignored -> { }, guard);

        assertThat(tools.searchOrders("ORDER-1", "FUZZY", context))
                .contains("匹配类型");
        assertThat(tools.searchOrders("ORDER-1", "CUSTOMER", context))
                .contains("匹配类型");
        assertThat(tools.searchOrders("ORDER\n1", "AUTO", context))
                .contains("编号");
        assertThat(tools.getOrderLogistics("X".repeat(129), null, context))
                .contains("编号");
        assertThat(tools.searchOrders("ORDER-2", "AUTO", context))
                .contains("未查询到");
        assertThat(gateway.searchCalls).hasValue(1);
        assertThat(gateway.logisticsCalls).hasValue(0);
    }

    @Test
    void canonicalizesSuccessfulCallsAndPublishesOnlyOnce() {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");
        OrderSearchResult result = new OrderSearchResult(
                OrderIdentifierType.AUTO, 0, false, queriedAt, List.of());
        AtomicInteger publications = new AtomicInteger();
        RecordingGateway gateway = new RecordingGateway(result, null);
        OrderQueryTools tools = new OrderQueryTools(gateway);
        ToolContext context = context(ignored -> publications.incrementAndGet(), new ToolCallGuard(3));

        String first = tools.searchOrders(" ORDER-1 ", null, context);
        String duplicate = tools.searchOrders("ORDER-1", " auto ", context);

        assertThat(duplicate).isEqualTo(first);
        assertThat(gateway.searchCalls).hasValue(1);
        assertThat(publications).hasValue(1);
    }

    @Test
    void failedCallIsRemovedFromGuardAndCanRetrySameArguments() {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-07T10:15:30+08:00");
        OrderLogisticsResult recovered = new OrderLogisticsResult(
                new OrderLogisticsResult.OrderSummary("ORDER-1", 30, "已发货"),
                queriedAt, false, List.of());
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        OrderQueryGateway gateway = new OrderQueryGateway() {
            @Override
            public OrderSearchResult search(String identifier, OrderIdentifierType identifierType,
                                            AgentIdentity identity, String requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OrderLogisticsResult logistics(String identifier, OrderIdentifierType identifierType,
                                                  AgentIdentity identity, String requestId) {
                if (calls.incrementAndGet() == 1) {
                    throw new OrderServiceUnavailableException("temporary");
                }
                return recovered;
            }
        };
        OrderQueryTools tools = new OrderQueryTools(gateway);
        ToolContext context = context(ignored -> publications.incrementAndGet(), new ToolCallGuard(1));

        String failed = tools.getOrderLogistics("ORDER-1", "AUTO", context);
        String retry = tools.getOrderLogistics("ORDER-1", "AUTO", context);

        assertThat(failed).contains("暂时不可用", "稍后重试");
        assertThat(retry).contains("ORDER-1");
        assertThat(calls).hasValue(2);
        assertThat(publications).hasValue(1);
    }

    private ToolContext context(
            com.xjjk.agent.tool.ToolOutputPublisher publisher,
            ToolCallGuard guard) {
        AgentToolRequestContext requestContext = new AgentToolRequestContext(
                "request-1", IDENTITY, publisher, guard);
        return new ToolContext(java.util.Map.of(
                AgentToolRequestContext.CONTEXT_KEY, requestContext));
    }

    private static final class RecordingGateway implements OrderQueryGateway {
        private final OrderSearchResult searchResult;
        private final OrderLogisticsResult logisticsResult;
        private final AtomicInteger searchCalls = new AtomicInteger();
        private final AtomicInteger logisticsCalls = new AtomicInteger();
        private String lastIdentifier;
        private OrderIdentifierType lastType;
        private AgentIdentity lastIdentity;
        private String lastRequestId;

        private RecordingGateway(
                OrderSearchResult searchResult,
                OrderLogisticsResult logisticsResult) {
            this.searchResult = searchResult;
            this.logisticsResult = logisticsResult;
        }

        @Override
        public OrderSearchResult search(String identifier, OrderIdentifierType identifierType,
                                        AgentIdentity identity, String requestId) {
            searchCalls.incrementAndGet();
            record(identifier, identifierType, identity, requestId);
            return searchResult == null
                    ? new OrderSearchResult(identifierType, 0, false,
                    OffsetDateTime.parse("2026-09-07T10:15:30+08:00"), List.of())
                    : searchResult;
        }

        @Override
        public OrderLogisticsResult logistics(String identifier, OrderIdentifierType identifierType,
                                              AgentIdentity identity, String requestId) {
            logisticsCalls.incrementAndGet();
            record(identifier, identifierType, identity, requestId);
            return logisticsResult;
        }

        private void record(String identifier, OrderIdentifierType identifierType,
                            AgentIdentity identity, String requestId) {
            this.lastIdentifier = identifier;
            this.lastType = identifierType;
            this.lastIdentity = identity;
            this.lastRequestId = requestId;
        }
    }
}
