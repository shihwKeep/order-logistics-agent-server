package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.customer.service.CustomerQueryGateway;
import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderAmount;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderGoodsSummary;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderRecipient;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.ShipmentTimeline;
import com.xjjk.agent.order.domain.TrackNode;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CompositeQueryWorkflowTest {

    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "account", "name", 3673L, 1L);

    @Mock
    private OrderQueryGateway orderGateway;
    @Mock
    private CustomerOrderQueryService customerOrderQueryService;
    @Mock
    private CustomerQueryGateway customerQueryGateway;
    @Mock
    private ProductSearchGateway productSearchGateway;
    @Mock
    private AfterSaleQueryGateway afterSaleQueryGateway;
    @Mock
    private KnowledgeQueryGateway knowledgeQueryGateway;

    @Mock
    private CompositeQueryCheckpointStore checkpointStore;

    @Test
    void executesBusinessAndKnowledgeNodesAndBuildsVerifiedContext() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        when(orderGateway.logistics(eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-1")))
                .thenReturn(new OrderLogisticsResult(
                        new OrderLogisticsResult.OrderSummary(
                                "XJ202609290001", 20, "运输中"),
                        now, false, List.of(new ShipmentTimeline(
                                "SF1001", "顺丰", "SUCCESS", "在途",
                                "到达南京转运场",
                                List.of(new TrackNode(
                                        "2026-09-29 15:58:06", "南京市南京转运场", "到达南京转运场"))))));
        when(knowledgeQueryGateway.retrieve(eq("查询物流规则"), any(), eq(IDENTITY),
                eq("request-1"))).thenReturn(new KnowledgeRetrievalResult(
                        true, List.of(new KnowledgeRetrievalResult.Evidence(
                                1L, 2L, 3L, "chunk-1", "物流规则", "规则",
                                "规则正文", "{}", 0.9, Set.of("kb"))),
                        "v1", "NONE", "SUCCESS", now));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("查询物流规则")));
        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway,
                        Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"),
                                ZoneId.of("Asia/Shanghai"))))
                .execute(plan, "查询订单物流并根据规则分析", IDENTITY, "request-1");

        assertThat(result.success()).isTrue();
        assertThat(result.actualResultKinds())
                .containsExactlyInAnyOrder("logistics-timeline", "knowledge-citations");
        assertThat(result.verifiedAnswerContext()).contains("物流规则");
        assertThat(result.verifiedAnswerContext())
                .contains("最新轨迹时间=2026-09-29 15:58:06", "到达南京转运场",
                        "停滞评估状态=EXCEEDED", "适用阈值小时=24");
        verify(orderGateway).logistics("XJ202609290001", OrderIdentifierType.ORDER_CODE,
                IDENTITY, "request-1");
        verify(knowledgeQueryGateway).retrieve("查询物流规则", List.of(), IDENTITY, "request-1");
    }

    @Test
    void completesInternalAnalysisOnlyAfterBusinessAndKnowledgeFactsAreReady() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        when(orderGateway.logistics(eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-analysis")))
                .thenReturn(new OrderLogisticsResult(
                        new OrderLogisticsResult.OrderSummary(
                                "XJ202609290001", 20, "运输中"),
                        now, false, List.of()));
        when(knowledgeQueryGateway.retrieve(eq("查询物流规则"), any(), eq(IDENTITY),
                eq("request-analysis"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "物流规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", now));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("查询物流规则"),
                CompositeQueryIntent.general("内部分析")));
        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询物流并结合规则分析", IDENTITY, "request-analysis");

        assertThat(result.success()).isTrue();
        assertThat(result.actualResultKinds()).contains("general-analysis");
        assertThat(result.verifiedAnswerContext()).contains("综合分析");
    }

    @Test
    void supportsCustomerLookupAsACompositeBusinessBranch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        when(customerQueryGateway.search("C24101816040001", CustomerMatchType.AUTO,
                IDENTITY, "request-customer-lookup"))
                .thenReturn(new CustomerSearchResult(
                        CustomerMatchType.CUSTOMER_CODE, 1, false, now, List.of()));
        when(knowledgeQueryGateway.retrieve(eq("客户规则"), any(), eq(IDENTITY),
                eq("request-customer-lookup"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "客户规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", now));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customer("C24101816040001"),
                CompositeQueryIntent.knowledge("客户规则")));
        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        customerQueryGateway, productSearchGateway, afterSaleQueryGateway,
                        knowledgeQueryGateway,
                        Clock.systemDefaultZone(), null, null))
                .execute(plan, "查询客户并结合客户规则", IDENTITY, "request-customer-lookup");

        assertThat(result.success()).isTrue();
        assertThat(result.actualResultKinds()).contains("customer-list", "knowledge-citations");
        verify(customerQueryGateway).search("C24101816040001", CustomerMatchType.AUTO,
                IDENTITY, "request-customer-lookup");
    }

    @Test
    void missingIdentifierStopsBeforeCallingAnyGateway() {
        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                new CompositeQueryIntent(
                        CompositeQueryIntent.Source.BUSINESS, "", "logistics-timeline"),
                CompositeQueryIntent.knowledge("物流规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询物流并按规则分析", IDENTITY, "request-2");

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("WAITING_INPUT");
    }

    @Test
    void distinguishesBusinessQueryFailureFromMissingIdentifier() {
        when(orderGateway.logistics(eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-failed")))
                .thenThrow(new OrderServiceUnavailableException("订单服务调用失败"));
        when(knowledgeQueryGateway.retrieve(eq("物流规则"), any(), eq(IDENTITY),
                eq("request-failed"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "物流规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", OffsetDateTime.now()));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("物流规则")));
        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询订单物流并根据规则分析", IDENTITY, "request-failed");

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.safeMessage())
                .isEqualTo(CompositeQueryService.BUSINESS_QUERY_FAILED_MESSAGE);
        verify(knowledgeQueryGateway).retrieve("物流规则", List.of(), IDENTITY, "request-failed");
    }

    @Test
    void executesCustomerOrdersAndKnowledgeWithoutSendingCustomerCodeToOrderSearch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 2, false, now, List.of());
        when(customerOrderQueryService.query(
                "C24101816040001", IDENTITY, "request-customer"))
                .thenReturn(new CustomerOrderQueryResult(
                        CustomerOrderResolution.FOUND,
                        "C24101816040001", "张*", orders));
        when(knowledgeQueryGateway.retrieve(eq("售后规则"), any(), eq(IDENTITY),
                eq("request-customer"))).thenReturn(new KnowledgeRetrievalResult(
                        true, List.of(new KnowledgeRetrievalResult.Evidence(
                        1L, 2L, 3L, "chunk-1", "售后规则", "规则",
                        "规则正文", "{}", 0.9, Set.of("kb"))),
                        "v1", "NONE", "SUCCESS", now));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C24101816040001"),
                CompositeQueryIntent.knowledge("售后规则")));
        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "客户订单是否符合售后规则", IDENTITY, "request-customer");

        assertThat(result.success()).isTrue();
        assertThat(result.actualResultKinds())
                .containsExactlyInAnyOrder("order-list", "knowledge-citations");
        verify(customerOrderQueryService).query(
                "C24101816040001", IDENTITY, "request-customer");
        verify(orderGateway, org.mockito.Mockito.never()).search(
                any(), any(), any(), any());
    }

    @Test
    void resolvesLatestCustomerOrderBeforeQueryingItsLogistics() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 2, false, now,
                List.of(
                        new OrderCard("XJ001", "", 20, "在途",
                                "2026-08-20 10:00:00", "张*", 0L, 0,
                                List.of(), "德邦", List.of()),
                        new OrderCard("XJ002", "", 20, "在途",
                                "2026-08-19 10:00:00", "张*", 0L, 0,
                                List.of(), "德邦", List.of())));
        when(customerOrderQueryService.query(
                "C24101816040001", IDENTITY, "request-dependent"))
                .thenReturn(new CustomerOrderQueryResult(
                        CustomerOrderResolution.FOUND,
                        "C24101816040001", "张*", orders));
        when(orderGateway.logistics(eq("XJ001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-dependent")))
                .thenReturn(new OrderLogisticsResult(
                        new OrderLogisticsResult.OrderSummary("XJ001", 20, "在途"),
                        now, false, List.of()));
        when(knowledgeQueryGateway.retrieve(eq("查询售后规则"), any(), eq(IDENTITY),
                eq("request-dependent"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "售后规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", now));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C24101816040001"),
                CompositeQueryIntent.latestOrderLogistics(),
                CompositeQueryIntent.knowledge("查询售后规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询客户最近一笔订单的物流状态，并结合售后规则判断",
                        IDENTITY, "request-dependent");

        assertThat(result.success()).isTrue();
        assertThat(result.actualResultKinds())
                .containsExactlyInAnyOrder("order-list", "logistics-timeline",
                        "knowledge-citations");
        assertThat(result.verifiedAnswerContext())
                .contains("本轮未查询售后工单，无法确认是否存在售后申请或工单状态");
        verify(orderGateway).logistics("XJ001", OrderIdentifierType.ORDER_CODE,
                IDENTITY, "request-dependent");
        verify(knowledgeQueryGateway).retrieve(eq("查询售后规则"), any(), eq(IDENTITY),
                eq("request-dependent"));
    }

    @Test
    void explainsMissingCustomerOrderAndSkipsDependentLogistics() {
        when(customerOrderQueryService.query(
                "C99999999999999", IDENTITY, "request-no-customer-order"))
                .thenReturn(new CustomerOrderQueryResult(
                        CustomerOrderResolution.NOT_FOUND,
                        "C99999999999999", null, null));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C99999999999999"),
                CompositeQueryIntent.latestOrderLogistics(),
                CompositeQueryIntent.knowledge("查询售后规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询客户最近一笔订单的物流状态，并结合售后规则判断",
                        IDENTITY, "request-no-customer-order");

        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.safeMessage())
                .contains("未找到客户订单", "未执行物流查询");
        assertThat(result.actualResultKinds()).doesNotContain("knowledge-citations");
        assertThat(result.verifiedAnswerContext()).doesNotContain("企业知识依据");
        verify(orderGateway, never()).logistics(any(), any(), any(), any());
        verifyNoInteractions(knowledgeQueryGateway);
    }

    @Test
    void explainsMissingOrderAndSkipsLogisticsAfterEmptyOrderLookup() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        when(orderGateway.search(eq("XJTS99999999999999"), eq(OrderIdentifierType.AUTO),
                eq(IDENTITY), eq("request-no-order")))
                .thenReturn(new OrderSearchResult(
                        OrderIdentifierType.ORDER_CODE, 0, false, now, List.of()));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.order("XJTS99999999999999"),
                CompositeQueryIntent.logistics("XJTS99999999999999"),
                CompositeQueryIntent.knowledge("物流规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "请查询订单 XJTS99999999999999，并结合物流规则判断如何处理",
                        IDENTITY, "request-no-order");

        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.safeMessage())
                .contains("未找到该订单", "未执行物流查询");
        assertThat(result.actualResultKinds()).doesNotContain("knowledge-citations");
        assertThat(result.verifiedAnswerContext()).doesNotContain("企业知识依据");
        verify(orderGateway, never()).logistics(any(), any(), any(), any());
        verifyNoInteractions(knowledgeQueryGateway);
    }

    @Test
    void returnsPartialSuccessWithoutInferringLogisticsWhenDependentBranchFails() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 1, false, now,
                List.of(new OrderCard("XJ001", "", 20, "在途",
                        "2026-08-20 10:00:00", "张*", 0L, 0,
                        List.of(), "德邦", List.of())));
        when(customerOrderQueryService.query(
                "C24101816040001", IDENTITY, "request-partial"))
                .thenReturn(new CustomerOrderQueryResult(
                        CustomerOrderResolution.FOUND,
                        "C24101816040001", "张*", orders));
        when(orderGateway.logistics(eq("XJ001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-partial")))
                .thenThrow(new OrderServiceUnavailableException("物流服务不可用"));
        when(knowledgeQueryGateway.retrieve(eq("售后规则"), any(), eq(IDENTITY),
                eq("request-partial"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "售后规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", now));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C24101816040001"),
                CompositeQueryIntent.latestOrderLogistics(),
                CompositeQueryIntent.knowledge("售后规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询客户最近一笔订单物流并结合售后规则判断",
                        IDENTITY, "request-partial");

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.actualResultKinds())
                .contains("order-list", "knowledge-citations")
                .doesNotContain("logistics-timeline");
        assertThat(result.verifiedAnswerContext())
                .contains("订单数量=1", "物流查询未完成")
                .doesNotContain("停滞评估状态");
        assertThat(result.safeMessage()).contains("部分实时业务查询已完成");
    }

    @Test
    void skipsDependentLogisticsWhenCustomerHasNoOrders() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        when(customerOrderQueryService.query(
                "C24101816040001", IDENTITY, "request-no-order"))
                .thenReturn(new CustomerOrderQueryResult(
                        CustomerOrderResolution.FOUND,
                        "C24101816040001", "张*",
                        new OrderSearchResult(OrderIdentifierType.CUSTOMER, 0,
                                false, now, List.of())));

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C24101816040001"),
                CompositeQueryIntent.latestOrderLogistics(),
                CompositeQueryIntent.knowledge("售后规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询客户最近一笔订单物流并结合售后规则判断",
                        IDENTITY, "request-no-order");

        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.verifiedAnswerContext()).contains("没有找到可用于物流查询的订单");
        assertThat(result.actualResultKinds()).doesNotContain("knowledge-citations");
        assertThat(result.verifiedAnswerContext()).doesNotContain("企业知识依据");
        verify(orderGateway, never()).logistics(any(), any(), any(), any());
        verifyNoInteractions(knowledgeQueryGateway);
    }

    @Test
    void includesOrderGoodsFactsWithoutIndependentProductSearch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-04T06:00:00+08:00");
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.ORDER_CODE,
                1,
                false,
                now,
                List.of(new OrderCard(
                        "XJTS0120260820000011",
                        "2608204002100615",
                        80,
                        "在途",
                        "2026-08-20 15:40:21",
                        "曹**",
                        0L,
                        6,
                        List.of(new OrderGoodsSummary(
                                "老炊五香牛肉粒", "1020300801", "50g/袋",
                                1000L, 6, 6000L, false)),
                        "德邦",
                        List.of("DPK365068298955"),
                        194L,
                        "款到发货",
                        new OrderAmount(6000L, 0L, 6000L, 0L, 0L),
                        new OrderRecipient("曹**", "1********833", "湖南省 常德市 鼎城区"),
                        1,
                        false,
                        1,
                        false,
                        List.of())));
        when(orderGateway.search("XJTS0120260820000011", OrderIdentifierType.AUTO,
                IDENTITY, "request-order-product"))
                .thenReturn(orders);

        CompositeQueryPlan plan = CompositeQueryPlan.withExternalSource(List.of(
                CompositeQueryIntent.order("XJTS0120260820000011"),
                CompositeQueryIntent.externalUnavailable("当前市场价格区间")), true);

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询订单中的商品并分析当前市场价格区间",
                        IDENTITY, "request-order-product");

        assertThat(result.verifiedAnswerContext())
                .contains(
                        "订单号=XJTS0120260820000011",
                        "商品名称=老炊五香牛肉粒",
                        "商品总数量=6",
                        "SKU=1020300801",
                        "规格=50g/袋",
                        "数量=6",
                        "订单成交单价=10.00元",
                        "当前未接入外部市场数据")
                .doesNotContain("联系电话", "收货地址");
        verify(productSearchGateway, never()).search(any());
    }

    @Test
    void executesIndependentBusinessAndKnowledgeBranchesInParallel() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        CountDownLatch businessStarted = new CountDownLatch(1);
        CountDownLatch knowledgeStarted = new CountDownLatch(1);
        when(orderGateway.logistics(eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-parallel"))).thenAnswer(invocation -> {
            businessStarted.countDown();
            if (!knowledgeStarted.await(500, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("knowledge branch did not start in parallel");
            }
            return new OrderLogisticsResult(
                    new OrderLogisticsResult.OrderSummary(
                            "XJ202609290001", 20, "运输中"),
                    now, false, List.of());
        });
        when(knowledgeQueryGateway.retrieve(eq("查询物流规则"), any(), eq(IDENTITY),
                eq("request-parallel"))).thenAnswer(invocation -> {
            knowledgeStarted.countDown();
            if (!businessStarted.await(500, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("business branch did not start in parallel");
            }
            return new KnowledgeRetrievalResult(
                    true, List.of(new KnowledgeRetrievalResult.Evidence(
                    1L, 2L, 3L, "chunk-1", "物流规则", "规则",
                    "规则正文", "{}", 0.9, Set.of("kb"))),
                    "v1", "NONE", "SUCCESS", now);
        });

        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("查询物流规则")));

        CompositeQueryService.CompositeQueryResult result = new CompositeQueryService(
                new CompositeQueryWorkflow(orderGateway, customerOrderQueryService,
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询订单物流并根据规则分析", IDENTITY, "request-parallel");

        assertThat(result.success()).isTrue();
    }

    @Test
    void resumesFromCheckpointWithoutRepeatingCompletedBusinessBranch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        when(orderGateway.logistics(eq("XJ202609290001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-resume")))
                .thenReturn(new OrderLogisticsResult(
                        new OrderLogisticsResult.OrderSummary(
                                "XJ202609290001", 20, "运输中"),
                        now, false, List.of()));
        when(knowledgeQueryGateway.retrieve(eq("查询物流规则"), any(), eq(IDENTITY),
                eq("request-resume"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "物流规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", now));
        AtomicReference<CompositeQueryCheckpoint> saved = new AtomicReference<>();
        when(checkpointStore.load("request-resume"))
                .thenAnswer(invocation -> java.util.Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(checkpointStore).save(any(CompositeQueryCheckpoint.class));

        CompositeQueryWorkflow workflow = new CompositeQueryWorkflow(
                orderGateway, customerOrderQueryService, productSearchGateway,
                afterSaleQueryGateway, knowledgeQueryGateway,
                Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"), ZoneId.of("Asia/Shanghai")),
                checkpointStore,
                new CompositeQueryCheckpointProperties(
                        true, "agent:composite:checkpoint:v2", "v2", Duration.ofMinutes(10)));
        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("查询物流规则")));

        assertThat(new CompositeQueryService(workflow)
                .execute(plan, "查询物流", IDENTITY, "request-resume").success()).isTrue();
        assertThat(saved).isNotNull();
        assertThat(saved.get().stateJson()).containsKey(CompositeQueryState.COMPLETED_BRANCHES);
        assertThat(saved.get().completedNodes()).isNotEmpty();
        CompositeQueryService.CompositeQueryResult resumed = new CompositeQueryService(workflow)
                .execute(plan, "查询物流", IDENTITY, "request-resume");
        assertThat(resumed.success()).isTrue();

        verify(orderGateway).logistics("XJ202609290001", OrderIdentifierType.ORDER_CODE,
                IDENTITY, "request-resume");
    }

    @Test
    void resumesDependentQueryWithResolvedOrderWithoutRepeatingBaseOrLogisticsCalls() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T04:00:00+08:00");
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 1, false, now,
                List.of(new OrderCard("XJ001", "", 20, "在途",
                        "2026-08-20 10:00:00", "张*", 0L, 0,
                        List.of(), "德邦", List.of())));
        when(customerOrderQueryService.query(
                "C24101816040001", IDENTITY, "request-dependent-resume"))
                .thenReturn(new CustomerOrderQueryResult(
                        CustomerOrderResolution.FOUND,
                        "C24101816040001", "张*", orders));
        when(orderGateway.logistics(eq("XJ001"), eq(OrderIdentifierType.ORDER_CODE),
                eq(IDENTITY), eq("request-dependent-resume")))
                .thenReturn(new OrderLogisticsResult(
                        new OrderLogisticsResult.OrderSummary("XJ001", 20, "在途"),
                        now, false, List.of()));
        when(knowledgeQueryGateway.retrieve(eq("售后规则"), any(), eq(IDENTITY),
                eq("request-dependent-resume"))).thenReturn(new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                1L, 2L, 3L, "chunk-1", "售后规则", "规则",
                "规则正文", "{}", 0.9, Set.of("kb"))),
                "v1", "NONE", "SUCCESS", now));

        AtomicReference<CompositeQueryCheckpoint> saved = new AtomicReference<>();
        when(checkpointStore.load("request-dependent-resume"))
                .thenAnswer(invocation -> java.util.Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(checkpointStore).save(any(CompositeQueryCheckpoint.class));

        CompositeQueryWorkflow workflow = new CompositeQueryWorkflow(
                orderGateway, customerOrderQueryService, productSearchGateway,
                afterSaleQueryGateway, knowledgeQueryGateway,
                Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"), ZoneId.of("Asia/Shanghai")),
                checkpointStore,
                new CompositeQueryCheckpointProperties(
                        true, "agent:composite:checkpoint:v2", "v2", Duration.ofMinutes(10)));
        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.customerOrders("C24101816040001"),
                CompositeQueryIntent.latestOrderLogistics(),
                CompositeQueryIntent.knowledge("售后规则")));

        CompositeQueryService service = new CompositeQueryService(workflow);
        assertThat(service.execute(plan, "查询客户最近一笔订单物流并结合售后规则判断",
                IDENTITY, "request-dependent-resume").success()).isTrue();
        assertThat(saved).isNotNull();
        assertThat(saved.get().stateJson()).containsEntry(
                CompositeQueryState.RESOLVED_ORDER_CODE, "XJ001");

        assertThat(service.execute(plan, "查询客户最近一笔订单物流并结合售后规则判断",
                IDENTITY, "request-dependent-resume").success()).isTrue();

        verify(customerOrderQueryService).query(
                "C24101816040001", IDENTITY, "request-dependent-resume");
        verify(orderGateway).logistics(
                "XJ001", OrderIdentifierType.ORDER_CODE, IDENTITY, "request-dependent-resume");
    }
}
