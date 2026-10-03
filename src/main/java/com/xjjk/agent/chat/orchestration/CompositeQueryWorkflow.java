package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerQueryGateway;
import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.chat.observation.CompositeQueryMetrics;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.LogisticsStagnationAssessment;
import com.xjjk.agent.order.domain.LogisticsStagnationEvaluator;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** 复合查询的服务端状态图；不启动 LangGraph4j 自带的 ReAct 工具循环。 */
@Slf4j
@Component
public class CompositeQueryWorkflow {

    private static final ExecutorService PARALLEL_EXECUTOR =
            Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "composite-query");
                thread.setDaemon(true);
                return thread;
            });

    private final OrderQueryGateway orderGateway;
    private final CustomerOrderQueryService customerOrderQueryService;
    private final CustomerQueryGateway customerQueryGateway;
    private final ProductSearchGateway productSearchGateway;
    private final AfterSaleQueryGateway afterSaleQueryGateway;
    private final KnowledgeQueryGateway knowledgeQueryGateway;
    private final Clock clock;
    private final LogisticsStagnationEvaluator stagnationEvaluator;
    private final BaseCheckpointSaver checkpointSaver;
    private final Executor parallelExecutor;
    private CompositeQueryMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            CustomerQueryGateway customerQueryGateway,
            ProductSearchGateway productSearchGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway,
            CompositeQueryCheckpointStore checkpointStore,
            CompositeQueryCheckpointProperties checkpointProperties,
            CompositeQueryParallelExecutor parallelExecutor) {
        this(orderGateway, customerOrderQueryService, productSearchGateway,
                customerQueryGateway,
                afterSaleQueryGateway, knowledgeQueryGateway, Clock.systemDefaultZone(),
                checkpointStore, checkpointProperties, parallelExecutor.executor(), true);
    }

    public CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            ProductSearchGateway productSearchGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway) {
        this(orderGateway, customerOrderQueryService, productSearchGateway,
                null, afterSaleQueryGateway, knowledgeQueryGateway,
                Clock.systemDefaultZone(), null, null, PARALLEL_EXECUTOR, true);
    }

    CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            ProductSearchGateway productSearchGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway,
            Clock clock) {
        this(orderGateway, customerOrderQueryService, productSearchGateway,
                null, afterSaleQueryGateway, knowledgeQueryGateway,
                clock, null, null, PARALLEL_EXECUTOR, true);
    }

    CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            ProductSearchGateway productSearchGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway,
            Clock clock,
            CompositeQueryCheckpointStore checkpointStore,
            CompositeQueryCheckpointProperties checkpointProperties) {
        this(orderGateway, customerOrderQueryService, productSearchGateway,
                null, afterSaleQueryGateway, knowledgeQueryGateway, clock,
                checkpointStore, checkpointProperties, PARALLEL_EXECUTOR, true);
    }

    CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            CustomerQueryGateway customerQueryGateway,
            ProductSearchGateway productSearchGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway,
            Clock clock,
            CompositeQueryCheckpointStore checkpointStore,
            CompositeQueryCheckpointProperties checkpointProperties) {
        this(orderGateway, customerOrderQueryService, productSearchGateway,
                customerQueryGateway, afterSaleQueryGateway, knowledgeQueryGateway,
                clock, checkpointStore, checkpointProperties, PARALLEL_EXECUTOR, true);
    }

    private CompositeQueryWorkflow(
            OrderQueryGateway orderGateway,
            CustomerOrderQueryService customerOrderQueryService,
            ProductSearchGateway productSearchGateway,
            CustomerQueryGateway customerQueryGateway,
            AfterSaleQueryGateway afterSaleQueryGateway,
            KnowledgeQueryGateway knowledgeQueryGateway,
            Clock clock,
            CompositeQueryCheckpointStore checkpointStore,
            CompositeQueryCheckpointProperties checkpointProperties,
            Executor parallelExecutor,
            boolean ignored) {
        this.orderGateway = orderGateway;
        this.customerOrderQueryService = customerOrderQueryService;
        this.customerQueryGateway = customerQueryGateway;
        this.productSearchGateway = productSearchGateway;
        this.afterSaleQueryGateway = afterSaleQueryGateway;
        this.knowledgeQueryGateway = knowledgeQueryGateway;
        this.clock = clock;
        this.stagnationEvaluator = new LogisticsStagnationEvaluator(clock);
        this.parallelExecutor = parallelExecutor;
        this.checkpointSaver = checkpointStore != null && checkpointProperties != null
                && checkpointProperties.enabled()
                ? new LangGraph4jRedisCheckpointSaver(
                checkpointStore, checkpointProperties.graphVersion(), clock)
                : null;
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
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(requestId)
                    .addParallelNodeExecutor("input.validate", parallelExecutor)
                    .addParallelNodeExecutor("branch.dispatch", parallelExecutor)
                    .build();
            if (checkpointSaver != null) {
                try {
                    var checkpoint = checkpointSaver.get(runnableConfig);
                    if (metrics != null) {
                        metrics.checkpoint("load", checkpoint.isPresent() ? "RESTORED" : "MISS");
                    }
                    checkpoint.ifPresent(value -> graphInput.putAll(value.getState()));
                } catch (RuntimeException exception) {
                    if (metrics != null) metrics.checkpoint("load", "ERROR");
                    throw exception;
                }
            }
            CompositeQueryState finalState = graph.invoke(graphInput, runnableConfig).orElseThrow();
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
                .addNode("branch.dispatch", node_async(state -> observeNode(
                        "branch.dispatch", state,
                        () -> CompositeQueryState.update(state, "branch.dispatch", "SUCCESS", Map.of()))))
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
                "input.validate", edge_async(state -> state.missingInputs().isEmpty()
                        ? "RUN" : "WAITING_INPUT"), Map.of(
                        "WAITING_INPUT", END,
                        "RUN", "branch.dispatch"));
        // 同一入口同时启动业务与知识分支；未请求的分支在节点内部立即 no-op。
        graph.addEdge("branch.dispatch", "business.query");
        graph.addEdge("branch.dispatch", "knowledge.query");
        graph.addEdge("business.query", "result.validate");
        graph.addEdge("knowledge.query", "result.validate");
        graph.addEdge("result.validate", "answer.compose");
        graph.addEdge("answer.compose", END);
        if (checkpointSaver == null) {
            return graph.compile();
        }
        return graph.compile(CompileConfig.builder()
                .graphId("composite-query-v2")
                .checkpointSaver(checkpointSaver)
                .releaseThread(false)
                .build());
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
        restoreCheckpointContext(state, context);
        for (CompositeQueryIntent intent : context.plan.intents()) {
            if (intent.source() != CompositeQueryIntent.Source.BUSINESS) continue;
            if (state.completedBranches().contains(branchName(intent))
                    || context.completedBranches.contains(branchName(intent))) continue;
            try {
                ToolUiResult result = queryBusinessIntent(intent, identity, requestId);
                context.results.add(result);
                String branch = branchName(intent);
                context.completedBranches.add(branch);
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, result.kind(), "SUCCESS", safeResultText(result.data()),
                        "", 0));
                if (metrics != null) {
                    metrics.result(result.kind(), "SUCCESS");
                    metrics.branch(branch, "SUCCESS");
                }
            } catch (RuntimeException exception) {
                context.failures.add(intent.resultKind());
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branchName(intent), intent.resultKind(), "FAILED", "",
                        exception.getClass().getSimpleName(), 0));
                if (metrics != null) {
                    metrics.result(intent.resultKind(), "FAILURE");
                    metrics.branch(branchName(intent), "FAILED");
                }
                log.warn("composite_business_node_failed requestId={}, kind={}, exceptionType={}",
                        requestId, intent.resultKind(), exception.getClass().getSimpleName());
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(CompositeQueryState.RESULT_COUNT, context.results.size());
        values.put(CompositeQueryState.FAILURES, List.copyOf(context.failures));
        values.put(CompositeQueryState.BRANCH_SNAPSHOTS, List.copyOf(context.branchSnapshots));
        values.put(CompositeQueryState.COMPLETED_BRANCHES, List.copyOf(context.completedBranches));
        // 业务节点失败后会直接结束图，必须把终态写入状态，避免外层拿到 PENDING。
        if (!context.failures.isEmpty()) {
            values.put(CompositeQueryState.FINAL_STATUS, "FAILED");
        }
        return CompositeQueryState.update(state, "business.query",
                context.failures.isEmpty() ? "SUCCESS" : "FAILED", values);
    }

    private ToolUiResult queryBusinessIntent(
            CompositeQueryIntent intent,
            AgentIdentity identity,
            String requestId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
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
            case "customer-list" -> {
                if (customerQueryGateway == null) {
                    throw new IllegalStateException("客户查询服务未配置");
                }
                CustomerSearchResult result = customerQueryGateway.search(
                        intent.value(), CustomerMatchType.AUTO, identity, requestId);
                yield new ToolUiResult("search_customers", "customer-list", 1,
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
        restoreCheckpointContext(state, context);
        for (CompositeQueryIntent intent : context.plan.intents()) {
            if (intent.source() != CompositeQueryIntent.Source.KNOWLEDGE) continue;
            if (state.completedBranches().contains(branchName(intent))
                    || context.completedBranches.contains(branchName(intent))) continue;
            try {
                KnowledgeRetrievalResult retrieval = knowledgeQueryGateway.retrieve(
                        intent.value(), List.of(), identity, requestId);
                if (retrieval == null) {
                    throw new IllegalStateException("知识库未返回可靠结果");
                }
                context.knowledge.add(retrieval);
                String branch = branchName(intent);
                context.completedBranches.add(branch);
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, "knowledge-citations", "SUCCESS",
                        "证据数=" + retrieval.evidences().size(), "", 0));
                if (metrics != null) {
                    metrics.result("knowledge-citations", "SUCCESS");
                    metrics.branch(branch, "SUCCESS");
                }
            } catch (RuntimeException exception) {
                context.failures.add("knowledge-citations");
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branchName(intent), "knowledge-citations", "FAILED", "",
                        exception.getClass().getSimpleName(), 0));
                if (metrics != null) {
                    metrics.result("knowledge-citations", "FAILURE");
                    metrics.branch(branchName(intent), "FAILED");
                }
                log.warn("composite_knowledge_node_failed requestId={}, exceptionType={}",
                        requestId, exception.getClass().getSimpleName());
            }
        }
        return CompositeQueryState.update(state, "knowledge.query",
                context.failures.isEmpty() ? "SUCCESS" : "FAILED",
                Map.of(CompositeQueryState.KNOWLEDGE_COUNT, context.knowledge.size(),
                        CompositeQueryState.FAILURES, List.copyOf(context.failures),
                        CompositeQueryState.BRANCH_SNAPSHOTS, List.copyOf(context.branchSnapshots),
                        CompositeQueryState.COMPLETED_BRANCHES, List.copyOf(context.completedBranches)));
    }

    private Map<String, Object> validateResults(
            CompositeQueryState state, InvocationContext context) {
        Set<String> actual = new LinkedHashSet<>();
        context.results.forEach(result -> actual.add(result.kind()));
        state.branchSnapshots().stream()
                .filter(snapshot -> "SUCCESS".equals(snapshot.status()))
                .map(CompositeQueryBranchSnapshot::resultKind)
                .forEach(actual::add);
        boolean knowledgeRequested = context.plan.hasSource(CompositeQueryIntent.Source.KNOWLEDGE);
        boolean knowledgeOk = !knowledgeRequested
                || (context.failures.stream().noneMatch("knowledge-citations"::equals)
                && ((!context.knowledge.isEmpty()
                && context.knowledge.stream().allMatch(
                result -> result.answerable() && !result.evidences().isEmpty()))
                || state.branchSnapshots().stream().anyMatch(snapshot ->
                "knowledge-citations".equals(snapshot.resultKind())
                        && "SUCCESS".equals(snapshot.status()))));
        if (knowledgeOk && !context.knowledge.isEmpty()) {
            actual.add("knowledge-citations");
        }
        boolean analysisRequested = context.plan.intents().stream()
                .anyMatch(intent -> "general-analysis".equals(intent.resultKind()));
        boolean baseFactsReady = context.plan.requiredResultKinds().stream()
                .filter(kind -> !"general-analysis".equals(kind))
                .allMatch(actual::contains);
        if (analysisRequested && baseFactsReady && knowledgeOk) {
            actual.add("general-analysis");
            if (metrics != null) metrics.result("general-analysis", "SUCCESS");
        }
        boolean complete = actual.containsAll(context.plan.requiredResultKinds())
                && knowledgeOk;
        String status = !context.failures.isEmpty() ? "FAILED"
                : complete ? "SUCCESS"
                : !knowledgeOk && knowledgeRequested
                ? "NO_RELIABLE_KNOWLEDGE" : "MISSING_RESULT";
        return CompositeQueryState.update(state, "result.validate", status,
                Map.of(CompositeQueryState.FINAL_STATUS, status,
                        CompositeQueryState.FAILURES, List.copyOf(context.failures),
                        CompositeQueryState.BRANCH_SNAPSHOTS,
                        List.copyOf(context.branchSnapshots),
                        CompositeQueryState.COMPLETED_BRANCHES,
                        List.copyOf(context.completedBranches)));
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
        if (context.results.isEmpty() && context.knowledge.isEmpty()) {
            List<CompositeQueryBranchSnapshot> snapshots = state.branchSnapshots().isEmpty()
                    ? List.copyOf(context.branchSnapshots) : state.branchSnapshots();
            snapshots.stream()
                    .filter(snapshot -> "SUCCESS".equals(snapshot.status()))
                    .forEach(snapshot -> answer.append(snapshot.resultKind())
                            .append("：").append(snapshot.safeSummary()).append("\n"));
        }
        if (context.plan.intents().stream()
                .anyMatch(intent -> "general-analysis".equals(intent.resultKind()))) {
            answer.append("综合分析：以上结论仅基于本次已核验的业务事实与企业知识，未引入外部市场数据；请按企业规则和实际授权范围处理。\n");
        }
        if (context.plan.requiresExternalSource()) {
            answer.append("外部数据边界：当前未接入外部市场数据，无法确认普遍价格区间或据此给出市场定价结论。\n");
        }
        return CompositeQueryState.update(state, "answer.compose", "SUCCESS",
                Map.of(CompositeQueryState.ANSWER_CONTEXT, answer.toString()));
    }

    private String branchName(CompositeQueryIntent intent) {
        return intent.source().name().toLowerCase(java.util.Locale.ROOT)
                + ":" + intent.resultKind();
    }

    private void restoreCheckpointContext(
            CompositeQueryState state, InvocationContext context) {
        if (context.branchSnapshots.isEmpty()) {
            context.branchSnapshots.addAll(state.branchSnapshots());
        }
        context.completedBranches.addAll(state.completedBranches());
        context.failures.addAll(state.failures());
    }

    private String safeResultText(Object data) {
        if (data instanceof OrderLogisticsResult logistics) {
            StringBuilder summary = new StringBuilder()
                    .append(logistics.order().orderCode())
                    .append("，订单状态=")
                    .append(logistics.order().statusText());
            if (logistics.shipments().isEmpty()) {
                return summary.append("，未查询到运单轨迹").toString();
            }
            for (var shipment : logistics.shipments()) {
                LogisticsStagnationAssessment assessment = stagnationEvaluator.evaluate(
                        shipment, logistics.order().statusText());
                summary.append("；运单号=")
                        .append(shipment.logisticsCode())
                        .append("，运单状态=")
                        .append(shipment.resultStatus())
                        .append("，最新状态=")
                        .append(shipment.latestStatusText())
                        .append("，最新轨迹时间=")
                        .append(latestTraceTime(shipment))
                        .append("，最新轨迹=")
                        .append(shipment.latestTrace())
                        .append("，停滞评估状态=")
                        .append(assessment.status())
                        .append("，适用环节=")
                        .append(assessment.stage())
                        .append("，适用阈值小时=")
                        .append(assessment.thresholdHours())
                        .append("，距最新轨迹小时=")
                        .append(assessment.elapsedHours())
                        .append("，评估时间=")
                        .append(assessment.evaluatedAt())
                        .append("，评估依据=")
                        .append(assessment.reason());
            }
            return summary.toString();
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

    /** 物流服务按最新优先返回轨迹，取首个有效时间供复合回答使用。 */
    private String latestTraceTime(com.xjjk.agent.order.domain.ShipmentTimeline shipment) {
        return shipment.traces().stream()
                .map(com.xjjk.agent.order.domain.TrackNode::time)
                .filter(time -> time != null && !time.isBlank())
                .findFirst()
                .orElse("未提供");
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
        private final List<ToolUiResult> results = Collections.synchronizedList(new ArrayList<>());
        private final List<KnowledgeRetrievalResult> knowledge =
                Collections.synchronizedList(new ArrayList<>());
        private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> completedBranches =
                Collections.synchronizedSet(new LinkedHashSet<>());
        private final List<CompositeQueryBranchSnapshot> branchSnapshots =
                Collections.synchronizedList(new ArrayList<>());

        private InvocationContext(CompositeQueryPlan plan) {
            this.plan = plan;
        }
    }
}
