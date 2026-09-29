package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.chat.observation.CompositeQueryMetrics;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** 复合查询的服务端状态图；不启动 LangGraph4j 自带的 ReAct 工具循环。 */
@Slf4j
@Component
public class CompositeQueryWorkflow {

    private final OrderQueryGateway orderGateway;
    private final CustomerOrderQueryService customerOrderQueryService;
    private final ProductSearchGateway productSearchGateway;
    private final AfterSaleQueryGateway afterSaleQueryGateway;
    private final KnowledgeQueryGateway knowledgeQueryGateway;
    private CompositeQueryMetrics metrics;

    public CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            ProductSearchGateway productSearchGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway) {
        this.orderGateway = orderGateway;
        this.customerOrderQueryService = customerOrderQueryService;
        this.productSearchGateway = productSearchGateway;
        this.afterSaleQueryGateway = afterSaleQueryGateway;
        this.knowledgeQueryGateway = knowledgeQueryGateway;
    }

    CompositeQueryService.CompositeQueryResult execute(
            CompositeQueryPlan plan,
            String message,
            AgentIdentity identity,
            String requestId,
            String conversationId) {
        try {
            InvocationContext context = new InvocationContext(plan);
            CompiledGraph<CompositeQueryState> graph = graph(identity, requestId, context);
            CompositeQueryState initial = CompositeQueryState.initial(
                    requestId, conversationId, message, identity, plan);
            Map<String, Object> graphInput = new LinkedHashMap<>(initial.data());
            // 业务对象和知识证据由本次调用上下文持有，不进入 LangGraph4j 的可序列化状态。
            graphInput.remove(CompositeQueryState.PLAN);
            graphInput.remove(CompositeQueryState.RESULTS);
            graphInput.remove(CompositeQueryState.KNOWLEDGE);
            CompositeQueryState finalState = graph.invoke(graphInput).orElseThrow();
            if (metrics != null) {
                metrics.graph(finalState.finalStatus());
            }
            return CompositeQueryService.CompositeQueryResult.from(
                    finalState, context.results, context.knowledge);
        } catch (Exception exception) {
            if (metrics != null) {
                metrics.graph("FAILED");
            }
            log.warn("composite_query_workflow_failed requestId={}, exceptionType={}",
                    requestId, exception.getClass().getSimpleName());
            return CompositeQueryService.CompositeQueryResult.failed();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setMetrics(CompositeQueryMetrics metrics) {
        this.metrics = metrics;
    }

    private CompiledGraph<CompositeQueryState> graph(
            AgentIdentity identity,
            String requestId,
            InvocationContext context) throws Exception {
        StateGraph<CompositeQueryState> graph = new StateGraph<>(CompositeQueryState::new)
                .addNode("input.validate", node_async(state -> observeNode(
                        "input.validate", state,
                        () -> validateInput(state, context))))
                .addNode("business.query", node_async(state -> observeNode(
                        "business.query", state,
                        () -> queryBusiness(state, context, identity, requestId))))
                .addNode("knowledge.query", node_async(state -> observeNode(
                        "knowledge.query", state,
                        () -> queryKnowledge(state, context, identity, requestId))))
                .addNode("result.validate", node_async(state -> observeNode(
                        "result.validate", state,
                        () -> validateResults(state, context))))
                .addNode("answer.compose", node_async(state -> observeNode(
                        "answer.compose", state,
                        () -> composeAnswer(state, context))));
        graph.addEdge(START, "input.validate");
        graph.addConditionalEdges(
                "input.validate", edge_async(state -> routeAfterValidation(state, context)), Map.of(
                        "WAITING_INPUT", END,
                        "BUSINESS", "business.query",
                        "KNOWLEDGE", "knowledge.query",
                        "BOTH", "business.query",
                        "VALIDATE", "result.validate"));
        graph.addConditionalEdges(
                "business.query", edge_async(state -> routeAfterBusiness(state, context)), Map.of(
                        "KNOWLEDGE", "knowledge.query",
                        "VALIDATE", "result.validate",
                        "FAILED", END));
        graph.addEdge("knowledge.query", "result.validate");
        graph.addEdge("result.validate", "answer.compose");
        graph.addEdge("answer.compose", END);
        return graph.compile();
    }

    private Map<String, Object> validateInput(
            CompositeQueryState state, InvocationContext context) {
        List<String> missing = new ArrayList<>();
        for (CompositeQueryIntent intent : context.plan.intents()) {
            if (intent.source() == CompositeQueryIntent.Source.BUSINESS
                    && intent.value().isBlank()) {
                missing.add(intent.resultKind());
            }
        }
        if (!missing.isEmpty()) {
            return CompositeQueryState.update(state, "input.validate", "WAITING_INPUT",
                    Map.of(CompositeQueryState.MISSING_INPUTS, List.copyOf(missing),
                            CompositeQueryState.FINAL_STATUS, "WAITING_INPUT"));
        }
        return CompositeQueryState.update(state, "input.validate", "SUCCESS", Map.of());
    }

    private String routeAfterValidation(
            CompositeQueryState state, InvocationContext context) {
        if (!state.missingInputs().isEmpty()) return "WAITING_INPUT";
        boolean business = context.plan.hasSource(CompositeQueryIntent.Source.BUSINESS);
        boolean knowledge = context.plan.hasSource(CompositeQueryIntent.Source.KNOWLEDGE);
        if (business && knowledge) return "BOTH";
        if (business) return "BUSINESS";
        if (knowledge) return "KNOWLEDGE";
        return "VALIDATE";
    }

    private Map<String, Object> queryBusiness(
            CompositeQueryState state,
            InvocationContext context,
            AgentIdentity identity,
            String requestId) {
        for (CompositeQueryIntent intent : context.plan.intents()) {
            if (intent.source() != CompositeQueryIntent.Source.BUSINESS) continue;
            try {
                ToolUiResult result = queryBusinessIntent(intent, identity, requestId);
                context.results.add(result);
                if (metrics != null) {
                    metrics.result(result.kind(), "SUCCESS");
                }
            } catch (RuntimeException exception) {
                context.failures.add(intent.resultKind());
                if (metrics != null) {
                    metrics.result(intent.resultKind(), "FAILURE");
                }
                log.warn("composite_business_node_failed requestId={}, kind={}, exceptionType={}",
                        requestId, intent.resultKind(), exception.getClass().getSimpleName());
            }
        }
        return CompositeQueryState.update(state, "business.query",
                context.failures.isEmpty() ? "SUCCESS" : "FAILED",
                Map.of(CompositeQueryState.RESULT_COUNT, context.results.size(),
                        CompositeQueryState.FAILURES, List.copyOf(context.failures)));
    }

    private ToolUiResult queryBusinessIntent(
            CompositeQueryIntent intent,
            AgentIdentity identity,
            String requestId) {
        OffsetDateTime now = OffsetDateTime.now();
        return switch (intent.resultKind()) {
            case "logistics-timeline" -> {
                OrderLogisticsResult result = orderGateway.logistics(
                        intent.value(), OrderIdentifierType.ORDER_CODE, identity, requestId);
                yield new ToolUiResult("get_order_logistics", "logistics-timeline", 1,
                        result.queriedAt(), result);
            }
            case "order-list" -> {
                if (intent.value().toUpperCase(java.util.Locale.ROOT).startsWith("C")) {
                    CustomerOrderQueryResult result = customerOrderQueryService.query(
                            intent.value(), identity, requestId);
                    OffsetDateTime queriedAt = result.orders() == null
                            ? now : result.orders().queriedAt();
                    yield new ToolUiResult("list_customer_orders", "order-list", 1,
                            queriedAt, result);
                }
                OrderSearchResult result = orderGateway.search(
                        intent.value(), OrderIdentifierType.AUTO, identity, requestId);
                yield new ToolUiResult("search_orders", "order-list", 1,
                        result.queriedAt(), result);
            }
            case "product-list" -> {
                ProductSearchResult result = productSearchGateway.search(
                        ProductSearchQuery.of(intent.value(), 1, 10));
                yield new ToolUiResult("search_products", "product-list", 1,
                        now, result);
            }
            case "after-sale-detail" -> {
                AfterSaleDetailResult result = afterSaleQueryGateway.detail(
                        intent.value(), identity, requestId);
                yield new ToolUiResult("get_after_sale_detail", "after-sale-detail", 1,
                        result.queriedAt(), result);
            }
            default -> throw new IllegalArgumentException(
                    "不支持的复合业务结果类型: " + intent.resultKind());
        };
    }

    private String routeAfterBusiness(CompositeQueryState state, InvocationContext context) {
        if (!context.failures.isEmpty()) return "FAILED";
        return context.plan.hasSource(CompositeQueryIntent.Source.KNOWLEDGE)
                ? "KNOWLEDGE" : "VALIDATE";
    }

    private Map<String, Object> queryKnowledge(
            CompositeQueryState state,
            InvocationContext context,
            AgentIdentity identity,
            String requestId) {
        for (CompositeQueryIntent intent : context.plan.intents()) {
            if (intent.source() != CompositeQueryIntent.Source.KNOWLEDGE) continue;
            try {
                context.knowledge.add(knowledgeQueryGateway.retrieve(
                        intent.value(), List.of(), identity, requestId));
                if (metrics != null) {
                    metrics.result("knowledge-citations", "SUCCESS");
                }
            } catch (RuntimeException exception) {
                context.failures.add("knowledge-citations");
                if (metrics != null) {
                    metrics.result("knowledge-citations", "FAILURE");
                }
                log.warn("composite_knowledge_node_failed requestId={}, exceptionType={}",
                        requestId, exception.getClass().getSimpleName());
            }
        }
        return CompositeQueryState.update(state, "knowledge.query",
                context.failures.isEmpty() ? "SUCCESS" : "FAILED",
                Map.of(CompositeQueryState.KNOWLEDGE_COUNT, context.knowledge.size(),
                        CompositeQueryState.FAILURES, List.copyOf(context.failures)));
    }

    private Map<String, Object> validateResults(
            CompositeQueryState state, InvocationContext context) {
        Set<String> actual = new LinkedHashSet<>();
        context.results.forEach(result -> actual.add(result.kind()));
        boolean knowledgeRequested = context.plan.hasSource(CompositeQueryIntent.Source.KNOWLEDGE);
        boolean knowledgeOk = !knowledgeRequested
                || (context.failures.stream().noneMatch("knowledge-citations"::equals)
                && !context.knowledge.isEmpty()
                && context.knowledge.stream().allMatch(
                result -> result.answerable() && !result.evidences().isEmpty()));
        if (knowledgeOk && !context.knowledge.isEmpty()) {
            actual.add("knowledge-citations");
        }
        boolean complete = actual.containsAll(context.plan.requiredResultKinds())
                && knowledgeOk;
        String status = complete ? "SUCCESS"
                : !knowledgeOk && knowledgeRequested
                ? "NO_RELIABLE_KNOWLEDGE" : "MISSING_RESULT";
        return CompositeQueryState.update(state, "result.validate", status,
                Map.of(CompositeQueryState.FINAL_STATUS, status));
    }

    private Map<String, Object> composeAnswer(
            CompositeQueryState state, InvocationContext context) {
        if (!"SUCCESS".equals(state.finalStatus())) {
            return CompositeQueryState.update(state, "answer.compose", "SKIPPED", Map.of());
        }
        StringBuilder answer = new StringBuilder();
        answer.append("业务事实：\n");
        context.results.forEach(result -> answer.append(result.kind())
                .append("：").append(safeResultText(result.data())).append("\n"));
        answer.append("企业知识依据：\n");
        context.knowledge.forEach(result -> result.evidences().forEach(evidence ->
                answer.append("《").append(evidence.documentTitle()).append("》")
                        .append("：").append(evidence.content()).append("\n")));
        return CompositeQueryState.update(state, "answer.compose", "SUCCESS",
                Map.of(CompositeQueryState.ANSWER_CONTEXT, answer.toString()));
    }

    private String safeResultText(Object data) {
        if (data instanceof OrderLogisticsResult logistics) {
            return logistics.order().orderCode() + "，" + logistics.order().statusText();
        }
        if (data instanceof OrderSearchResult orders) {
            return "订单数量=" + orders.total();
        }
        if (data instanceof CustomerOrderQueryResult customerOrders) {
            return customerOrders.orders() == null
                    ? "客户订单查询状态=" + customerOrders.resolution()
                    : "客户订单数量=" + customerOrders.orders().total();
        }
        if (data instanceof ProductSearchResult products) {
            return "商品数量=" + products.total();
        }
        if (data instanceof AfterSaleDetailResult afterSale) {
            return afterSale.afterSaleCode() + "，" + afterSale.statusText();
        }
        return "已取得结构化结果";
    }

    private Map<String, Object> observeNode(
            String node,
            CompositeQueryState state,
            Supplier<Map<String, Object>> action) {
        return metrics == null
                ? action.get()
                : metrics.node(node, state.requestId(), action);
    }

    private static final class InvocationContext {
        private final CompositeQueryPlan plan;
        private final List<ToolUiResult> results = new ArrayList<>();
        private final List<KnowledgeRetrievalResult> knowledge = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        private InvocationContext(CompositeQueryPlan plan) {
            this.plan = plan;
        }
    }
}
