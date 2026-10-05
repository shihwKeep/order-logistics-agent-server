package com.xjjk.agent.evaluation;

import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.chat.orchestration.CompositeQueryCheckpointProperties;
import com.xjjk.agent.chat.orchestration.CompositeQueryCheckpoint;
import com.xjjk.agent.chat.orchestration.CompositeQueryCheckpointStore;
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent;
import com.xjjk.agent.chat.orchestration.CompositeQueryParallelExecutor;
import com.xjjk.agent.chat.orchestration.CompositeQueryParallelProperties;
import com.xjjk.agent.chat.orchestration.CompositeQueryPlan;
import com.xjjk.agent.chat.orchestration.CompositeQueryService;
import com.xjjk.agent.chat.orchestration.CompositeQueryWorkflow;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerQueryGateway;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.product.service.ProductSearchGateway;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class GoldenCaseExecution {

    private GoldenCaseExecution() {
    }

    static Execution run(GoldenEvaluationCase evaluationCase) {
        OrderQueryGateway orderGateway = mock(OrderQueryGateway.class);
        CustomerOrderQueryService customerOrders = mock(CustomerOrderQueryService.class);
        CustomerQueryGateway customerGateway = mock(CustomerQueryGateway.class);
        ProductSearchGateway productGateway = mock(ProductSearchGateway.class);
        AfterSaleQueryGateway afterSaleGateway = mock(AfterSaleQueryGateway.class);
        KnowledgeQueryGateway knowledgeGateway = mock(KnowledgeQueryGateway.class);

        configureFixtures(evaluationCase, orderGateway, customerOrders,
                productGateway, knowledgeGateway);

        CompositeQueryCheckpointStore checkpointStore = mock(CompositeQueryCheckpointStore.class);
        CompositeQueryParallelExecutor parallelExecutor = new CompositeQueryParallelExecutor(
                new CompositeQueryParallelProperties(2, 2, 8));
        CompositeQueryCheckpointProperties checkpointProperties =
                new CompositeQueryCheckpointProperties(
                        "checkpoint-resume-001".equals(evaluationCase.caseId()),
                        "golden:composite", "v2", Duration.ofMinutes(10));
        CompositeQueryWorkflow workflow = new CompositeQueryWorkflow(
                orderGateway, customerOrders, customerGateway, productGateway,
                afterSaleGateway, knowledgeGateway,
                checkpointStore, checkpointProperties, parallelExecutor);

        CompositeQueryService.CompositeQueryResult result =
                new CompositeQueryService(workflow).execute(
                        planFor(evaluationCase), evaluationCase.input(),
                        GoldenFixtureFactory.identity(), evaluationCase.caseId());
        return new Execution(result, orderGateway, customerOrders, productGateway,
                knowledgeGateway, checkpointStore, parallelExecutor);
    }

    static CheckpointExecution runCheckpointTwice() {
        OrderQueryGateway orderGateway = mock(OrderQueryGateway.class);
        CustomerOrderQueryService customerOrders = mock(CustomerOrderQueryService.class);
        CustomerQueryGateway customerGateway = mock(CustomerQueryGateway.class);
        ProductSearchGateway productGateway = mock(ProductSearchGateway.class);
        AfterSaleQueryGateway afterSaleGateway = mock(AfterSaleQueryGateway.class);
        KnowledgeQueryGateway knowledgeGateway = mock(KnowledgeQueryGateway.class);
        when(orderGateway.search(anyString(), any(), any(), anyString()))
                .thenReturn(GoldenFixtureFactory.orderNormal());
        when(productGateway.search(any()))
                .thenReturn(GoldenFixtureFactory.productNormal());

        AtomicReference<CompositeQueryCheckpoint> saved = new AtomicReference<>();
        CompositeQueryCheckpointStore checkpointStore = new CompositeQueryCheckpointStore() {
            @Override
            public Optional<CompositeQueryCheckpoint> load(String threadId) {
                return Optional.ofNullable(saved.get());
            }

            @Override
            public void save(CompositeQueryCheckpoint checkpoint) {
                saved.set(checkpoint);
            }

            @Override
            public void delete(String threadId) {
                saved.set(null);
            }
        };
        CompositeQueryParallelExecutor parallelExecutor = new CompositeQueryParallelExecutor(
                new CompositeQueryParallelProperties(2, 2, 8));
        CompositeQueryWorkflow workflow = new CompositeQueryWorkflow(
                orderGateway, customerOrders, customerGateway, productGateway,
                afterSaleGateway, knowledgeGateway, checkpointStore,
                new CompositeQueryCheckpointProperties(
                        true, "golden:composite", "v2", Duration.ofMinutes(10)),
                parallelExecutor);
        CompositeQueryService service = new CompositeQueryService(workflow);
        CompositeQueryPlan plan = CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.order("ORDER_FIXTURE_001"),
                CompositeQueryIntent.product("PRODUCT_FIXTURE_001")));
        CompositeQueryService.CompositeQueryResult first = service.execute(
                plan, "checkpoint恢复查询", GoldenFixtureFactory.identity(),
                "checkpoint-resume-golden-001");
        CompositeQueryService.CompositeQueryResult resumed = service.execute(
                plan, "checkpoint恢复查询", GoldenFixtureFactory.identity(),
                "checkpoint-resume-golden-001");
        return new CheckpointExecution(first, resumed, orderGateway, productGateway);
    }

    static CompositeQueryPlan planFor(GoldenEvaluationCase evaluationCase) {
        return switch (evaluationCase.category()) {
            case "BUSINESS", "FAILURE", "TOOL_REUSE" ->
                    CompositeQueryPlan.of(List.of(
                            CompositeQueryIntent.logistics("ORDER_FIXTURE_001"),
                            CompositeQueryIntent.externalUnavailable("离线固定夹具")));
            case "BUSINESS_KNOWLEDGE_COMPOSITE" ->
                    CompositeQueryPlan.of(List.of(
                            CompositeQueryIntent.logistics("ORDER_FIXTURE_001"),
                            CompositeQueryIntent.knowledge("物流规则")));
            case "CUSTOMER_ORDER_KNOWLEDGE" ->
                    CompositeQueryPlan.of(List.of(
                            CompositeQueryIntent.customerOrders("CUSTOMER_FIXTURE_001"),
                            CompositeQueryIntent.knowledge("售后规则")));
            case "PRODUCT_KNOWLEDGE" ->
                    CompositeQueryPlan.of(List.of(
                            CompositeQueryIntent.product("PRODUCT_FIXTURE_001"),
                            CompositeQueryIntent.knowledge("定价规则")));
            case "NOT_FOUND" -> notFoundPlan(evaluationCase);
            case "EMPTY_BUSINESS_GATE" -> CompositeQueryPlan.of(List.of(
                    CompositeQueryIntent.order("ORDER_NOT_FOUND"),
                    CompositeQueryIntent.logistics("ORDER_NOT_FOUND"),
                    CompositeQueryIntent.knowledge("物流规则")));
            case "PARALLEL", "CHECKPOINT" -> CompositeQueryPlan.of(List.of(
                    CompositeQueryIntent.order("ORDER_FIXTURE_001"),
                    CompositeQueryIntent.product("PRODUCT_FIXTURE_001")));
            default -> throw new IllegalArgumentException(
                    "未定义评测类别: " + evaluationCase.category());
        };
    }

    private static CompositeQueryPlan notFoundPlan(GoldenEvaluationCase evaluationCase) {
        return switch (evaluationCase.caseId()) {
            case "customer-not-found-001" -> CompositeQueryPlan.of(List.of(
                    CompositeQueryIntent.customerOrders("CUSTOMER_NOT_FOUND"),
                    CompositeQueryIntent.externalUnavailable("离线固定夹具")));
            case "sku-not-found-001" -> CompositeQueryPlan.of(List.of(
                    CompositeQueryIntent.product("PRODUCT_NOT_FOUND"),
                    CompositeQueryIntent.externalUnavailable("离线固定夹具")));
            default -> CompositeQueryPlan.of(List.of(
                    CompositeQueryIntent.logistics("ORDER_NOT_FOUND"),
                    CompositeQueryIntent.externalUnavailable("离线固定夹具")));
        };
    }

    private static void configureFixtures(
            GoldenEvaluationCase evaluationCase,
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrders,
            ProductSearchGateway productGateway,
            KnowledgeQueryGateway knowledgeGateway) {
        if (evaluationCase.fixtures().contains("order.unavailable")) {
            when(orderGateway.logistics(anyString(), any(), any(), anyString()))
                    .thenThrow(new OrderServiceUnavailableException("固定下游异常"));
        } else if (evaluationCase.fixtures().contains("order.not-found")) {
            when(orderGateway.logistics(anyString(), any(), any(), anyString()))
                    .thenReturn(new com.xjjk.agent.order.domain.OrderLogisticsResult(
                            new com.xjjk.agent.order.domain.OrderLogisticsResult.OrderSummary(
                                    "ORDER_NOT_FOUND", 0, "NOT_FOUND"),
                            GoldenFixtureFactory.fixedNow(), false, List.of()));
        } else if (evaluationCase.fixtures().contains("order.logistics.normal")) {
            when(orderGateway.logistics(anyString(), any(), any(), anyString()))
                    .thenReturn(GoldenFixtureFactory.logisticsNormal());
        }
        when(orderGateway.search(anyString(), any(), any(), anyString()))
                .thenReturn(evaluationCase.fixtures().contains("order.empty")
                        ? GoldenFixtureFactory.orderEmpty() : GoldenFixtureFactory.orderNormal());
        when(customerOrders.query(anyString(), any(), anyString()))
                .thenReturn(evaluationCase.fixtures().contains("customer.not-found")
                        ? GoldenFixtureFactory.customerOrderNotFound()
                        : GoldenFixtureFactory.customerOrderNormal());
        when(productGateway.search(any()))
                .thenReturn(evaluationCase.fixtures().contains("product.not-found")
                        ? GoldenFixtureFactory.productNotFound()
                        : GoldenFixtureFactory.productNormal());
        when(knowledgeGateway.retrieve(anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> GoldenFixtureFactory.knowledgePolicy(
                        invocation.getArgument(0, String.class)));
    }

    record Execution(
            CompositeQueryService.CompositeQueryResult result,
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrders,
            ProductSearchGateway productGateway,
            KnowledgeQueryGateway knowledgeGateway,
            CompositeQueryCheckpointStore checkpointStore,
            CompositeQueryParallelExecutor parallelExecutor) {
    }

    record CheckpointExecution(
            CompositeQueryService.CompositeQueryResult first,
            CompositeQueryService.CompositeQueryResult resumed,
            OrderQueryGateway orderGateway,
            ProductSearchGateway productGateway) {
    }
}
