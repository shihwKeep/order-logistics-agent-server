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

/**
 * 复合查询的服务端状态图；不启动 LangGraph4j 自带的 ReAct 工具循环。
 *
 * <p>这条链路的入口关系是：</p>
 * <pre>
 * BusinessQueryPlanner
 *   -> CompositeQueryService
 *   -> CompositeQueryWorkflow
 *   -> LangGraph4j graph.invoke()
 *   -> CompositeQueryResult
 *   -> AiChatService 的无工具生成阶段
 * </pre>
 *
 * <p>LangGraph4j 在这里负责确定性的流程编排、分支并行、状态合并和 checkpoint；
 * Spring AI 只在图已经取得并校验业务事实、知识证据后负责组织自然语言。这样可以避免
 * 模型自行改变订单、物流、商品和知识查询的执行顺序。</p>
 */
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

    /**
     * 执行一轮已经由 Planner 生成的复合查询计划。
     *
     * <p>本方法是 LangGraph4j 工作流的运行入口，代码按照“准备上下文 -> 构图 ->
     * 准备状态 -> 恢复 checkpoint -> invoke -> 转换结果”的顺序组织。</p>
     */
    CompositeQueryService.CompositeQueryResult execute(
            CompositeQueryPlan plan,
            String message,
            AgentIdentity identity,
            String requestId,
            String conversationId) {
        try {
            // 步骤 1：创建本轮 JVM 运行上下文。完整业务对象和知识证据只保存在这里，
            // 不直接进入可序列化 State，避免 checkpoint 保存敏感信息或大对象。
            InvocationContext context = new InvocationContext(plan);

            // 步骤 2：根据计划是否需要业务门禁，构建本轮对应的串行或并行图拓扑。
            // identity、requestId 和 context 由节点闭包捕获，不作为模型可修改的数据。
            CompiledGraph<CompositeQueryState> graph = graph(identity, requestId, context);

            // 步骤 3：创建包含请求定位、结果契约、节点状态和恢复元数据的初始 State。
            CompositeQueryState initial = CompositeQueryState.initial(
                    requestId, conversationId, message, identity, plan);
            Map<String, Object> graphInput = new LinkedHashMap<>(initial.data());
            // 业务对象和知识证据由本次调用上下文持有，不进入 LangGraph4j 的可序列化状态。
            graphInput.remove(CompositeQueryState.PLAN);
            graphInput.remove(CompositeQueryState.RESULTS);
            graphInput.remove(CompositeQueryState.KNOWLEDGE);

            // 步骤 4：requestId 同时作为 LangGraph4j threadId，用于定位本轮 checkpoint。
            // 在 input.validate 和 branch.dispatch 的分叉点注册有界线程池，允许独立后继并行。
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(requestId)
                    .addParallelNodeExecutor("input.validate", parallelExecutor)
                    .addParallelNodeExecutor("branch.dispatch", parallelExecutor)
                    .build();

            // 步骤 5：若启用 checkpoint，先加载同一 threadId 的安全状态并覆盖初始默认值。
            // 找不到是正常的首次执行；读取异常则安全失败，不能伪装成“未命中”。
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

            // 步骤 6：从 START 开始执行图。每个节点返回状态增量，LangGraph4j 合并后再
            // 传给下一节点；遇到分叉时使用上面配置的 parallelExecutor 调度独立分支。
            CompositeQueryState finalState = graph.invoke(graphInput, runnableConfig).orElseThrow();
            if (metrics != null) {
                metrics.graph(finalState.finalStatus());
            }

            // 步骤 7：将图中的流程状态与 InvocationContext 中的完整结果合并为应用层结果。
            // 后续 ChatTurnRunner 会先发布结构化卡片，再把 verifiedAnswerContext 交给无工具模型。
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

    /**
     * 为本轮计划创建并编译状态图。
     *
     * <p>需要业务事实门禁时使用串行拓扑：</p>
     * <pre>
     * START -> input.validate -> branch.dispatch -> business.query
     *       -> knowledge.query -> result.validate -> answer.compose -> END
     * </pre>
     *
     * <p>业务与知识互不依赖时使用并行拓扑：</p>
     * <pre>
     *                                      +-> business.query  -+
     * START -> input.validate -> branch.dispatch                 +-> result.validate
     *                                      +-> knowledge.query -+   -> answer.compose -> END
     * </pre>
     *
     * <p>START、END 是 LangGraph4j 虚拟节点；node_async 把节点函数包装成可调度动作，
     * edge_async 根据合并后的 State 选择条件边。</p>
     */
    private CompiledGraph<CompositeQueryState> graph(
            AgentIdentity identity,
            String requestId,
            InvocationContext context) throws Exception {
        StateGraph<CompositeQueryState> graph = new StateGraph<>(CompositeQueryState::new)
                // 节点 1：校验所有直接来自用户输入的业务标识是否齐全。
                .addNode("input.validate", node_async(state -> observeNode(
                        "input.validate", state,
                        () -> validateInput(state, context))))
                // 节点 2：稳定的分发锚点。本身不查数据，用于从同一点启动后续分支。
                .addNode("branch.dispatch", node_async(state -> observeNode(
                        "branch.dispatch", state,
                        () -> CompositeQueryState.update(state, "branch.dispatch", "SUCCESS", Map.of()))))
                // 节点 3：执行订单、物流、客户、商品、售后等实时业务查询及其依赖查询。
                .addNode("business.query", node_async(state -> observeNode(
                        "business.query", state,
                        () -> queryBusiness(state, context, identity, requestId))))
                // 节点 4：在业务门禁允许后检索企业知识；无业务匹配时在这里确定性跳过。
                .addNode("knowledge.query", node_async(state -> observeNode(
                        "knowledge.query", state,
                        () -> queryKnowledge(state, context, identity, requestId))))
                // 节点 5：汇总必需结果、失败分支和知识可靠性，计算最终业务状态。
                .addNode("result.validate", node_async(state -> observeNode(
                        "result.validate", state,
                        () -> validateResults(state, context))))
                // 节点 6：只把已经核验的事实和证据组装成模型可使用的受限上下文。
                .addNode("answer.compose", node_async(state -> observeNode(
                        "answer.compose", state,
                        () -> composeAnswer(state, context))));
        // 固定入口：任何计划都先做参数完整性校验。
        graph.addEdge(START, "input.validate");
        // 条件边：缺参时直接到 END，由上层返回澄清/安全文案；完整时进入分发锚点。
        graph.addConditionalEdges(
                "input.validate", edge_async(state -> state.missingInputs().isEmpty()
                        ? "RUN" : "WAITING_INPUT"), Map.of(
                        "WAITING_INPUT", END,
                        "RUN", "branch.dispatch"));
        // 业务分支始终存在；知识分支是否从这里并行启动由计划的业务门禁决定。
        graph.addEdge("branch.dispatch", "business.query");
        if (context.plan.knowledgeRequiresBusinessGate()) {
            // 商品是否存在、订单是否存在或最近订单解析等结果会决定规则是否适用，
            // 因此先完成 business.query，再执行 knowledge.query。
            graph.addEdge("business.query", "knowledge.query");
            graph.addEdge("knowledge.query", "result.validate");
        } else {
            // 两个信息源互不依赖时从 branch.dispatch 同时启动；result.validate 是汇合点。
            graph.addEdge("branch.dispatch", "knowledge.query");
            graph.addEdge("business.query", "result.validate");
            graph.addEdge("knowledge.query", "result.validate");
        }
        graph.addEdge("result.validate", "answer.compose");
        graph.addEdge("answer.compose", END);
        if (checkpointSaver == null) {
            // 未启用 checkpoint 时只编译可执行图，不注册持久化回调。
            return graph.compile();
        }
        // 启用 checkpoint 后，LangGraph4j 会在节点推进时通过 saver 保存安全状态。
        // releaseThread(false) 保留完成后的 thread 状态，真正清理由 Redis TTL 或显式 release 负责。
        return graph.compile(CompileConfig.builder()
                .graphId("composite-query-v2")
                .checkpointSaver(checkpointSaver)
                .releaseThread(false)
                .build());
    }

    /**
     * 校验必须由用户直接提供的业务标识。
     *
     * <p>依赖前置结果解析的标识（例如“客户最近一笔订单”的订单号）允许初始为空；
     * 其他业务 Intent 缺值时写入 missingInputs，并将图状态置为 WAITING_INPUT。</p>
     */
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

    /**
     * 执行基础业务分支，并在同一节点内继续完成依赖业务查询。
     *
     * <p>执行顺序为：恢复分支快照 -> 执行用户直接给出标识的 Intent -> 解析最近订单
     * -> 执行使用已解析订单号的 Intent。completedBranches 既用于本轮去重，也用于
     * checkpoint 恢复后跳过已经成功的下游调用。</p>
     */
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
            if (skipLogisticsAfterMissingOrder(intent, context)) {
                String branch = branchName(intent);
                context.completedBranches.add(branch);
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, intent.resultKind(), "SKIPPED",
                        "未找到订单，未执行物流查询", "", 0));
                continue;
            }
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

    /**
     * 当前计划已经明确查不到订单时，短路后续物流查询。
     * 这是业务结果门禁，不依赖模型判断，也不会把“没有订单”误报为物流服务失败。
     */
    private boolean skipLogisticsAfterMissingOrder(
            CompositeQueryIntent intent,
            InvocationContext context) {
        if (!"logistics-timeline".equals(intent.resultKind())) {
            return false;
        }
        if (context.businessFailures.contains("order-list")) {
            return true;
        }
        return context.results.stream()
                .filter(result -> "order-list".equals(result.kind()))
                .map(ToolUiResult::data)
                .anyMatch(data -> {
                    if (data instanceof OrderSearchResult orders) {
                        return orders.total() == 0;
                    }
                    if (data instanceof CustomerOrderQueryResult customerOrders) {
                        return customerOrders.orders() == null
                                || customerOrders.orders().total() == 0;
                    }
                    return false;
                });
    }

    /**
     * 从客户订单结果中解析最近一笔可查询订单，并把公开订单号写入 State。
     *
     * <p>解析成功后 dependent Intent 状态变为 RUNNABLE；没有匹配订单或前置查询失败时，
     * 只记录可解释的依赖状态，不调用物流服务。</p>
     */
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

    /**
     * 执行依赖已解析业务标识的查询，目前主要是“客户最近一笔订单 -> 物流”。
     * 恢复时若 resolvedOrderCode 和该分支成功快照已经存在，会跳过重复调用。
     */
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

    /**
     * 把受控 resultKind 映射到具体业务 Gateway。
     *
     * <p>这里是图节点与订单、物流、客户、商品、售后服务的适配边界；Intent 中只允许
     * 公开查询标识，认证身份始终使用当前请求闭包中的 AgentIdentity。</p>
     */
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

    /**
     * 执行企业知识查询。
     *
     * <p>若计划要求业务门禁且业务分支没有匹配对象，则将知识分支标记为 SKIPPED；
     * 这样“不存在的订单/SKU”不会继续返回与当前对象无关的规则引用。其余场景调用
     * KnowledgeQueryGateway，并把可靠证据保存在 InvocationContext 中。</p>
     */
    private Map<String, Object> queryKnowledge(
            CompositeQueryState state,
            InvocationContext context,
            AgentIdentity identity,
            String requestId) {
        restoreCheckpointContext(state, context);
        if (context.plan.knowledgeRequiresBusinessGate()
                && hasMissingBusinessMatch(context)) {
            if (metrics != null) {
                metrics.knowledgeSkip("NO_BUSINESS_MATCH");
            }
            for (CompositeQueryIntent intent : context.plan.intents()) {
                if (intent.source() != CompositeQueryIntent.Source.KNOWLEDGE) {
                    continue;
                }
                String branch = branchName(intent);
                context.completedBranches.add(branch);
                context.branchSnapshots.add(new CompositeQueryBranchSnapshot(
                        branch, "knowledge-citations", "SKIPPED",
                        "未找到可适用的业务对象，未执行知识查询", "", 0));
            }
            return CompositeQueryState.update(state, "knowledge.query", "SKIPPED",
                    Map.of(CompositeQueryState.KNOWLEDGE_COUNT, 0,
                            CompositeQueryState.FAILURES, List.copyOf(context.failures),
                            CompositeQueryState.BRANCH_SNAPSHOTS,
                            List.copyOf(context.branchSnapshots),
                            CompositeQueryState.COMPLETED_BRANCHES,
                            List.copyOf(context.completedBranches)));
        }
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

    /**
     * 汇合各分支后执行完整性与知识可靠性校验。
     *
     * <p>本节点根据 requiredResultKinds、实际结果、失败分支以及知识证据是否可回答，
     * 计算 SUCCESS、PARTIAL_SUCCESS、FAILED、MISSING_RESULT 或
     * NO_RELIABLE_KNOWLEDGE。知识因无业务对象而被确定性跳过时，不再把它视为缺失结果。</p>
     */
    private Map<String, Object> validateResults(
            CompositeQueryState state, InvocationContext context) {
        Set<String> actual = new LinkedHashSet<>();
        context.results.forEach(result -> actual.add(result.kind()));
        state.branchSnapshots().stream()
                .filter(snapshot -> "SUCCESS".equals(snapshot.status()))
                .map(CompositeQueryBranchSnapshot::resultKind)
                .forEach(actual::add);
        boolean knowledgeSkipped = context.plan.knowledgeRequiresBusinessGate()
                && hasMissingBusinessMatch(context);
        Set<String> requiredResultKinds = new LinkedHashSet<>(
                context.plan.requiredResultKinds());
        if (knowledgeSkipped) {
            requiredResultKinds.remove("knowledge-citations");
        }
        boolean knowledgeRequested = context.plan.hasSource(CompositeQueryIntent.Source.KNOWLEDGE)
                && !knowledgeSkipped;
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
        boolean baseFactsReady = requiredResultKinds.stream()
                .filter(kind -> !"general-analysis".equals(kind)
                        && !(knowledgeSkipped && "knowledge-citations".equals(kind)))
                .allMatch(actual::contains);
        if (analysisRequested && baseFactsReady && knowledgeOk) {
            actual.add("general-analysis");
            if (metrics != null) metrics.result("general-analysis", "SUCCESS");
        }
        boolean complete = actual.containsAll(requiredResultKinds)
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
                requiredResultKinds))) {
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

    /**
     * 将已经核验的业务事实和企业知识组装为受限回答上下文。
     *
     * <p>这里不调用模型，只生成 verifiedAnswerContext；ChatTurnRunner 随后通过
     * streamGroundedComposite 发起无工具模型请求，模型不能在生成阶段再次查询业务系统。</p>
     */
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
        if (!context.knowledge.isEmpty()) {
            answer.append("企业知识依据：\n");
            context.knowledge.forEach(result -> result.evidences().forEach(evidence ->
                    answer.append("《").append(evidence.documentTitle()).append("》")
                            .append("：").append(evidence.content()).append("\n")));
        }
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
        return hasMissingBusinessMatch(context);
    }

    private boolean hasMissingBusinessMatch(InvocationContext context) {
        if (context.businessFailures.contains("order-list")
                || context.businessFailures.contains("product-list")) {
            return true;
        }
        return context.results.stream()
                .filter(result -> "order-list".equals(result.kind())
                        || "product-list".equals(result.kind()))
                .map(ToolUiResult::data)
                .anyMatch(data -> {
                    if (data instanceof CustomerOrderQueryResult result) {
                        return result.orders() == null || result.orders().total() == 0;
                    }
                    if (data instanceof OrderSearchResult result) {
                        return result.total() == 0;
                    }
                    if (data instanceof ProductSearchResult result) {
                        return result.total() == 0;
                    }
                    return false;
                });
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

    /**
     * 把 checkpoint 中的安全快照恢复到当前 JVM 的 InvocationContext。
     *
     * <p>checkpoint 不保存完整 ToolUiResult 和知识正文，因此这里只恢复分支状态、
     * 安全摘要、失败类型和依赖状态，用于跳过已经完成的调用并生成可解释的降级结果。</p>
     */
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
            StringBuilder summary = new StringBuilder("商品数量=").append(products.total());
            products.items().stream().limit(10).forEach(item -> {
                summary.append("；商品=").append(safeText(item.goodsName()))
                        .append("，SKU=").append(safeText(item.skuCode()))
                        .append("，规格=").append(safeText(item.goodsModel()))
                        .append("，当前标价=").append(amountInYuan(item.priceInFen())).append("元")
                        .append("，库存=").append(item.availableStock())
                        .append("，状态=").append(safeText(item.listingStatusText()));
            });
            return summary.toString();
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

    private String safeText(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
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

    /** 为节点统一添加耗时、结果和 requestId 观测；未注入指标组件时直接执行动作。 */
    private Map<String, Object> observeNode(
            String node,
            CompositeQueryState state,
            Supplier<Map<String, Object>> action) {
        return metrics == null
                ? action.get()
                : metrics.node(node, state.requestId(), action);
    }

    /**
     * 单次 graph.invoke() 的进程内可变上下文。
     *
     * <p>这些集合可能由并行业务/知识分支同时写入，因此使用同步集合；对象生命周期只限
     * 本轮调用，不进入 Redis checkpoint，也不会跨请求复用。</p>
     */
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
