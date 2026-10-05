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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final LatestOrderResolver latestOrderResolver;
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
        this.latestOrderResolver = new LatestOrderResolver();
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
                        if (checkpoint.isPresent()) {
                            metrics.checkpointResume("SUCCESS");
                        }
                    }
                    checkpoint.ifPresent(value -> graphInput.putAll(value.getState()));
                } catch (RuntimeException exception) {
                    if (metrics != null) metrics.checkpoint("load", "ERROR");
                    if (metrics != null) metrics.checkpointResume("FAILED");
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
                    requestId, exception.getClass().getSimpleName(), exception);
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
                    && intent.value().isBlank()
                    && intent.identifierSource()
                    != CompositeQueryIntent.IdentifierSource.RESOLVED_ORDER) {
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
            if (intent.identifierSource() == CompositeQueryIntent.IdentifierSource.RESOLVED_ORDER) {
                continue;
            }
            if (state.completedBranches().contains(branchName(intent))
                    || context.completedBranches.contains(branchName(intent))) continue;
            try {
                ToolUiResult result = queryBusinessIntent(
                        intent, intent.value(), identity, requestId);
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
                context.businessFailures.add(intent.resultKind());
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
        values.put(CompositeQueryState.DEPENDENCY_STATUSES,
                mergeDependencyStatuses(state, context));
        CompositeQueryState baseState = new CompositeQueryState(
                new LinkedHashMap<>(state.data()));
        Map<String, Object> baseData = new LinkedHashMap<>(baseState.data());
        baseData.putAll(values);
        CompositeQueryState stateAfterBase = new CompositeQueryState(baseData);
        Map<String, Object> resolvedValues = resolveLatestOrder(stateAfterBase, context);
        baseData.putAll(resolvedValues);
        CompositeQueryState stateAfterResolve = new CompositeQueryState(baseData);
        Map<String, Object> dependentValues = queryDependentBusiness(
                stateAfterResolve, context, identity, requestId);
        baseData.putAll(dependentValues);
        baseData.remove(CompositeQueryState.NODE_STATUSES);
        baseData.put(CompositeQueryState.RESULT_COUNT, context.results.size());
        baseData.put(CompositeQueryState.FAILURES, List.copyOf(context.failures));
        baseData.put(CompositeQueryState.BRANCH_SNAPSHOTS, List.copyOf(context.branchSnapshots));
        baseData.put(CompositeQueryState.COMPLETED_BRANCHES, List.copyOf(context.completedBranches));
        baseData.put(CompositeQueryState.DEPENDENCY_STATUSES,
                mergeDependencyStatuses(stateAfterResolve, context));
        baseData.put(CompositeQueryState.PUBLISHED_RESULT_KINDS,
                context.results.stream().map(ToolUiResult::kind).distinct().toList());
        return CompositeQueryState.update(state, "business.query",
                context.failures.isEmpty() ? "SUCCESS" : "FAILED", baseData);
    }

    private Map<String, Object> resolveLatestOrder(
            CompositeQueryState state,
            InvocationContext context) {
        restoreCheckpointContext(state, context);
        List<CompositeQueryIntent> dependentIntents = context.plan.intents().stream()
                .filter(intent -> intent.dependencyMode()
                        == CompositeQueryIntent.DependencyMode.LATEST_ORDER)
                .toList();
        if (dependentIntents.isEmpty()) {
            if (metrics != null) metrics.dependency("LATEST_ORDER", "SKIPPED");
            return CompositeQueryState.update(state, "resolve.latest.order", "SKIPPED",
                    Map.of());
        }
        String existingCode = state.resolvedOrderCode();
        String existingAt = state.resolvedOrderAt();
        if (!existingCode.isBlank()) {
            Map<String, String> statuses = mergeDependencyStatuses(state, context);
            dependentIntents.forEach(intent -> statuses.put(branchName(intent), "SUCCESS"));
            return CompositeQueryState.update(state, "resolve.latest.order", "SUCCESS",
                    Map.of(CompositeQueryState.RESOLVED_ORDER_CODE, existingCode,
                            CompositeQueryState.RESOLVED_ORDER_AT, existingAt,
                            CompositeQueryState.DEPENDENCY_STATUSES, statuses));
        }
        if (context.businessFailures.stream().anyMatch(kind -> "order-list".equals(kind))) {
            if (metrics != null) metrics.dependency("LATEST_ORDER", "FAILED");
            Map<String, String> statuses = mergeDependencyStatuses(state, context);
            dependentIntents.forEach(intent -> statuses.put(
                    branchName(intent), "SKIPPED_DEPENDENCY_FAILED"));
            return CompositeQueryState.update(state, "resolve.latest.order", "SKIPPED",
                    Map.of(CompositeQueryState.DEPENDENCY_STATUSES, statuses));
        }
        Optional<LatestOrderResolver.ResolvedOrder> resolved = context.results.stream()
                .filter(result -> "order-list".equals(result.kind()))
                .map(ToolUiResult::data)
                .filter(CustomerOrderQueryResult.class::isInstance)
                .map(CustomerOrderQueryResult.class::cast)
                .map(latestOrderResolver::resolve)
                .flatMap(Optional::stream)
                .findFirst();
        if (resolved.isEmpty()) {
            if (metrics != null) metrics.dependency("LATEST_ORDER", "SKIPPED");
            Map<String, String> statuses = mergeDependencyStatuses(state, context);
            dependentIntents.forEach(intent -> statuses.put(
                    branchName(intent), "SKIPPED_NO_MATCHING_ORDER"));
            return CompositeQueryState.update(state, "resolve.latest.order", "SKIPPED",
                    Map.of(CompositeQueryState.DEPENDENCY_STATUSES, statuses));
        }
        LatestOrderResolver.ResolvedOrder value = resolved.get();
        Map<String, String> statuses = mergeDependencyStatuses(state, context);
        dependentIntents.forEach(intent -> statuses.put(branchName(intent), "RUNNABLE"));
        if (metrics != null) metrics.dependency("LATEST_ORDER", "RESOLVED");
        return CompositeQueryState.update(state, "resolve.latest.order", "SUCCESS",
                Map.of(CompositeQueryState.RESOLVED_ORDER_CODE, value.orderCode(),
                        CompositeQueryState.RESOLVED_ORDER_AT, value.orderTime(),
                        CompositeQueryState.DEPENDENCY_STATUSES, statuses));
    }

    private Map<String, Object> queryDependentBusiness(
            CompositeQueryState state,
            InvocationContext context,
            AgentIdentity identity,
            String requestId) {
        restoreCheckpointContext(state, context);
        String resolvedOrderCode = state.resolvedOrderCode();
        for (CompositeQueryIntent intent : context.plan.intents()) {
            if (intent.source() != CompositeQueryIntent.Source.BUSINESS
                    || intent.identifierSource()
                    != CompositeQueryIntent.IdentifierSource.RESOLVED_ORDER) {
                continue;
            }
            String branch = branchName(intent);
            if (state.completedBranches().contains(branch)
                    || context.completedBranches.contains(branch)) {
                continue;
            }
            if (resolvedOrderCode.isBlank()) {
                String status = state.dependencyStatuses().getOrDefault(
                        branch, "SKIPPED_DEPENDENCY_FAILED");
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, intent.resultKind(), "SKIPPED", status, "", 0));
                continue;
            }
            try {
                ToolUiResult result = queryBusinessIntent(
                        intent, resolvedOrderCode, identity, requestId);
                context.results.add(result);
                context.completedBranches.add(branch);
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, result.kind(), "SUCCESS", safeResultText(result.data()), "", 0));
                if (metrics != null) {
                    metrics.result(result.kind(), "SUCCESS");
                    metrics.branch(branch, "SUCCESS");
                }
                Map<String, String> statuses = mergeDependencyStatuses(state, context);
                statuses.put(branch, "SUCCESS");
                context.dependencyStatuses.clear();
                context.dependencyStatuses.putAll(statuses);
            } catch (RuntimeException exception) {
                context.failures.add(intent.resultKind());
                context.businessFailures.add(intent.resultKind());
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, intent.resultKind(), "FAILED", "",
                        exception.getClass().getSimpleName(), 0));
                if (metrics != null) {
                    metrics.result(intent.resultKind(), "FAILURE");
                    metrics.branch(branch, "FAILED");
                }
                log.warn("composite_dependent_business_node_failed requestId={}, kind={}, exceptionType={}",
                        requestId, intent.resultKind(), exception.getClass().getSimpleName());
            }
        }
        return CompositeQueryState.update(state, "business.dependent.query",
                context.businessFailures.isEmpty() ? "SUCCESS" : "FAILED",
                Map.of(CompositeQueryState.RESULT_COUNT, context.results.size(),
                        CompositeQueryState.FAILURES, List.copyOf(context.failures),
                        CompositeQueryState.BRANCH_SNAPSHOTS, List.copyOf(context.branchSnapshots),
                        CompositeQueryState.COMPLETED_BRANCHES, List.copyOf(context.completedBranches),
                        CompositeQueryState.DEPENDENCY_STATUSES,
                        mergeDependencyStatuses(state, context),
                        CompositeQueryState.PUBLISHED_RESULT_KINDS,
                        context.results.stream().map(ToolUiResult::kind).distinct().toList()));
    }

    private ToolUiResult queryBusinessIntent(
            CompositeQueryIntent intent,
            String queryValue,
            AgentIdentity identity,
            String requestId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return switch (intent.resultKind()) {
            case "logistics-timeline" -> {
                OrderLogisticsResult result = orderGateway.logistics(
                        queryValue, OrderIdentifierType.ORDER_CODE, identity, requestId);
                yield new ToolUiResult("get_order_logistics", "logistics-timeline", 1,
                        result.queriedAt(), result);
            }
            case "order-list" -> {
                if (queryValue.toUpperCase(java.util.Locale.ROOT).startsWith("C")) {
                    CustomerOrderQueryResult result = customerOrderQueryService.query(
                            queryValue, identity, requestId);
                    OffsetDateTime queriedAt = result.orders() == null
                            ? now : result.orders().queriedAt();
                    yield new ToolUiResult("list_customer_orders", "order-list", 1,
                            queriedAt, result);
                }
                OrderSearchResult result = orderGateway.search(
                        queryValue, OrderIdentifierType.AUTO, identity, requestId);
                yield new ToolUiResult("search_orders", "order-list", 1,
                        result.queriedAt(), result);
            }
            case "customer-list" -> {
                if (customerQueryGateway == null) {
                    throw new IllegalStateException("客户查询服务未配置");
                }
                CustomerSearchResult result = customerQueryGateway.search(
                        queryValue, CustomerMatchType.AUTO, identity, requestId);
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
                        queryValue, identity, requestId);
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
                context.knowledgeFailures.add("knowledge-citations");
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
                context.knowledgeFailures.isEmpty() ? "SUCCESS" : "FAILED",
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
                || (context.knowledgeFailures.isEmpty()
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
        boolean hasSuccessfulBusiness = context.results.stream()
                .anyMatch(result -> !"knowledge-citations".equals(result.kind()));
        boolean hasBusinessFailure = !context.businessFailures.isEmpty()
                || state.branchSnapshots().stream().anyMatch(snapshot ->
                "FAILED".equals(snapshot.status())
                        && !"knowledge-citations".equals(snapshot.resultKind()));
        String status;
        if (!knowledgeOk && knowledgeRequested) {
            status = "NO_RELIABLE_KNOWLEDGE";
        } else if (complete) {
            status = "SUCCESS";
        } else if (hasSuccessfulBusiness && (hasBusinessFailure || !actual.containsAll(
                context.plan.requiredResultKinds()))) {
            status = "PARTIAL_SUCCESS";
        } else if (hasBusinessFailure) {
            status = "FAILED";
        } else {
            status = "MISSING_RESULT";
        }
        if (!hasSuccessfulBusiness && context.results.isEmpty()
                && !context.businessFailures.isEmpty()) {
            status = "FAILED";
        }
        if (!hasSuccessfulBusiness && context.results.isEmpty()
                && state.branchSnapshots().stream().anyMatch(snapshot ->
                "SUCCESS".equals(snapshot.status())
                        && !"knowledge-citations".equals(snapshot.resultKind()))) {
            hasSuccessfulBusiness = true;
        }
        if ("PARTIAL_SUCCESS".equals(status) && metrics != null) {
            metrics.partialSuccess();
        }
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
        if (!"SUCCESS".equals(state.finalStatus())
                && !"PARTIAL_SUCCESS".equals(state.finalStatus())) {
            return CompositeQueryState.update(state, "answer.compose", "SKIPPED", Map.of());
        }
        StringBuilder answer = new StringBuilder();
        answer.append("业务事实：\n");
        if (!context.results.isEmpty()) {
            context.results.forEach(result -> answer.append(result.kind())
                    .append("：").append(safeResultText(result.data())).append("\n"));
        } else {
            state.branchSnapshots().stream()
                    .filter(snapshot -> "SUCCESS".equals(snapshot.status()))
                    .forEach(snapshot -> answer.append(snapshot.resultKind())
                            .append("：").append(snapshot.safeSummary()).append("\n"));
        }
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
        boolean afterSaleNotQueried = context.plan.intents().stream()
                .noneMatch(intent -> "after-sale-detail".equals(intent.resultKind()))
                && context.plan.intents().stream().anyMatch(intent ->
                intent.source() == CompositeQueryIntent.Source.KNOWLEDGE
                        && containsAfterSaleTopic(intent.value()));
        if (afterSaleNotQueried) {
            answer.append("本轮未查询售后工单，无法确认是否存在售后申请或工单状态。\n");
        }
        if ("PARTIAL_SUCCESS".equals(state.finalStatus())) {
            boolean logisticsMissing = context.plan.intents().stream()
                    .anyMatch(intent -> "logistics-timeline".equals(intent.resultKind()))
                    && context.results.stream().noneMatch(
                    result -> "logistics-timeline".equals(result.kind()));
            if (logisticsMissing) {
                answer.append("物流查询未完成，因此无法确认当前物流状态、最新轨迹时间或停滞阈值；以上订单状态不等同于物流状态。\n");
            }
            if (hasNoMatchingOrder(context)) {
                answer.append("没有找到可用于物流查询的订单，因此未执行物流查询。\n");
            }
        }
        return CompositeQueryState.update(state, "answer.compose", "SUCCESS",
                Map.of(CompositeQueryState.ANSWER_CONTEXT, answer.toString()));
    }

    private boolean containsAfterSaleTopic(String value) {
        return value != null && (value.contains("售后") || value.contains("退货")
                || value.contains("换货") || value.contains("退款"));
    }

    private boolean hasNoMatchingOrder(InvocationContext context) {
        return context.results.stream()
                .filter(result -> "order-list".equals(result.kind()))
                .map(ToolUiResult::data)
                .filter(CustomerOrderQueryResult.class::isInstance)
                .map(CustomerOrderQueryResult.class::cast)
                .anyMatch(result -> result.orders() == null || result.orders().total() == 0);
    }

    private String branchName(CompositeQueryIntent intent) {
        String base = intent.source().name().toLowerCase(java.util.Locale.ROOT)
                + ":" + intent.resultKind();
        return intent.dependencyMode() == CompositeQueryIntent.DependencyMode.NONE
                ? base
                : base + ":" + intent.dependencyMode().name().toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, String> mergeDependencyStatuses(
            CompositeQueryState state,
            InvocationContext context) {
        Map<String, String> statuses = new LinkedHashMap<>(state.dependencyStatuses());
        statuses.putAll(context.dependencyStatuses);
        return statuses;
    }

    private void restoreCheckpointContext(
            CompositeQueryState state, InvocationContext context) {
        if (context.branchSnapshots.isEmpty()) {
            context.branchSnapshots.addAll(state.branchSnapshots());
        }
        context.completedBranches.addAll(state.completedBranches());
        context.failures.addAll(state.failures());
        context.dependencyStatuses.putAll(state.dependencyStatuses());
        state.branchSnapshots().stream()
                .filter(snapshot -> "FAILED".equals(snapshot.status())
                        && !"knowledge-citations".equals(snapshot.resultKind()))
                .map(CompositeQueryBranchSnapshot::resultKind)
                .forEach(context.businessFailures::add);
        state.branchSnapshots().stream()
                .filter(snapshot -> "FAILED".equals(snapshot.status())
                        && "knowledge-citations".equals(snapshot.resultKind()))
                .forEach(snapshot -> context.knowledgeFailures.add("knowledge-citations"));
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
            return safeOrderSearchText(orders);
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

    private String safeOrderSearchText(OrderSearchResult orders) {
        StringBuilder summary = new StringBuilder("订单数量=").append(orders.total());
        for (var card : orders.items()) {
            summary.append("；订单号=").append(card.orderCode());
            appendField(summary, "订单状态", card.statusText());
            summary.append("，商品行数=").append(card.goods().size());
            summary.append("，商品总数量=").append(card.goodsTotalCount());
            if (card.goods().isEmpty()) {
                summary.append("，未取得订单商品明细");
                continue;
            }
            for (var goods : card.goods()) {
                appendField(summary, "商品名称", goods.goodsName());
                appendField(summary, "SKU", goods.skuCode());
                appendField(summary, "规格", goods.specification());
                summary.append("，数量=").append(goods.quantity());
                appendField(summary, "订单成交单价", amountInYuan(goods.unitPriceInFen()));
                appendField(summary, "订单商品小计", amountInYuan(goods.subtotalInFen()));
            }
        }
        return summary.toString();
    }

    private void appendField(StringBuilder target, String label, String value) {
        if (value != null && !value.isBlank()) {
            target.append("，").append(label).append("=").append(value);
        }
    }

    private String amountInYuan(Long amountInFen) {
        return amountInFen == null
                ? null
                : BigDecimal.valueOf(amountInFen, 2).toPlainString() + "元";
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
        private final Set<String> businessFailures =
                Collections.synchronizedSet(new LinkedHashSet<>());
        private final Set<String> knowledgeFailures =
                Collections.synchronizedSet(new LinkedHashSet<>());
        private final Map<String, String> dependencyStatuses =
                Collections.synchronizedMap(new LinkedHashMap<>());

        private InvocationContext(CompositeQueryPlan plan) {
            this.plan = plan;
        }
    }
}
