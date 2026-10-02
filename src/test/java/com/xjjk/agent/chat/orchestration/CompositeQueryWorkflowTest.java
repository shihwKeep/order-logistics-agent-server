package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeQueryWorkflowTest {

    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "account", "name", 3673L, 1L);

    @Mock
    private OrderQueryGateway orderGateway;
    @Mock
    private CustomerOrderQueryService customerOrderQueryService;
    @Mock
    private ProductSearchGateway productSearchGateway;
    @Mock
    private AfterSaleQueryGateway afterSaleQueryGateway;
    @Mock
    private KnowledgeQueryGateway knowledgeQueryGateway;

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
                        productSearchGateway, afterSaleQueryGateway, knowledgeQueryGateway))
                .execute(plan, "查询订单物流并根据规则分析", IDENTITY, "request-1");

        assertThat(result.success()).isTrue();
        assertThat(result.actualResultKinds())
                .containsExactlyInAnyOrder("logistics-timeline", "knowledge-citations");
        assertThat(result.verifiedAnswerContext()).contains("物流规则");
        assertThat(result.verifiedAnswerContext())
                .contains("最新轨迹时间=2026-09-29 15:58:06", "到达南京转运场");
        verify(orderGateway).logistics("XJ202609290001", OrderIdentifierType.ORDER_CODE,
                IDENTITY, "request-1");
        verify(knowledgeQueryGateway).retrieve("查询物流规则", List.of(), IDENTITY, "request-1");
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
        verify(knowledgeQueryGateway, org.mockito.Mockito.never())
                .retrieve(any(), any(), any(), any());
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
}
