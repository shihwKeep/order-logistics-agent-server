package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
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
                        now, false, List.of()));
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
}
