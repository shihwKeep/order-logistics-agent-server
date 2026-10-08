package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LangGraph4j 复合查询图的可序列化状态。
 *
 * <p>节点读取当前 State，并返回 {@code Map<String, Object>} 状态增量；LangGraph4j
 * 负责把增量合并后传递给下一个节点。这里主要保存流程推进和恢复所需的安全数据，
 * 完整订单、物流、商品和知识对象保存在 {@code InvocationContext}，不进入 checkpoint。</p>
 *
 * <p>认证身份也不保存在 State 中，而是由当前请求创建图时通过节点闭包传递，防止恢复
 * checkpoint 时错误复用旧身份或让状态数据覆盖服务端认证结果。</p>
 */
public final class CompositeQueryState extends AgentState {
    // 请求定位：requestId 标识一轮图执行，conversationId 标识所属会话。
    static final String REQUEST_ID = "requestId";
    static final String CONVERSATION_ID = "conversationId";
    static final String USER_MESSAGE = "userMessage";

    // 计划与结果契约：声明本轮要取得什么，以及输入和最终结果是否完整。
    static final String PLAN = "plan";
    static final String REQUIRED_RESULT_KINDS = "requiredResultKinds";
    static final String MISSING_INPUTS = "missingInputs";
    static final String RESULTS = "results";
    static final String KNOWLEDGE = "knowledge";
    static final String ANSWER_CONTEXT = "answerContext";
    static final String NODE_STATUSES = "nodeStatuses";
    static final String FAILURES = "failures";
    static final String FINAL_STATUS = "finalStatus";
    static final String RESULT_COUNT = "resultCount";
    static final String KNOWLEDGE_COUNT = "knowledgeCount";

    // 分支与 checkpoint 恢复：保存脱敏摘要、完成情况和下一执行位置，避免重复调用下游。
    static final String BRANCH_SNAPSHOTS = "branchSnapshots";
    static final String COMPLETED_BRANCHES = "completedBranches";
    static final String PENDING_BRANCHES = "pendingBranches";
    static final String RETRY_COUNTS = "retryCounts";
    static final String PLAN_HASH = "planHash";
    static final String CHECKPOINT_VERSION = "checkpointVersion";
    static final String NEXT_NODE = "nextNode";

    // 依赖解析：支持“客户订单 -> 最近订单号 -> 物流”这类不能从用户原文直接取值的分支。
    static final String RESOLVED_ORDER_CODE = "resolvedOrderCode";
    static final String RESOLVED_ORDER_AT = "resolvedOrderAt";
    static final String DEPENDENCY_STATUSES = "dependencyStatuses";

    // 已发布结果类型：恢复或重试时用于识别已经对外产生的结构化结果。
    static final String PUBLISHED_RESULT_KINDS = "publishedResultKinds";

    public CompositeQueryState(Map<String, Object> data) {
        super(data);
    }

    /**
     * 创建图首次执行时的完整默认状态。
     *
     * <p>所有集合都初始化为空、最终状态为 PENDING、下一节点为 input.validate。
     * {@code ignoredIdentity} 仅保留方法调用兼容性，身份有意不写入 data。</p>
     */
    public static CompositeQueryState initial(
            String requestId,
            String conversationId,
            String userMessage,
            AgentIdentity ignoredIdentity,
            CompositeQueryPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(REQUEST_ID, requestId);
        data.put(CONVERSATION_ID, conversationId);
        data.put(USER_MESSAGE, userMessage);
        data.put(PLAN, plan);
        data.put(REQUIRED_RESULT_KINDS, plan.requiredResultKinds());
        data.put(MISSING_INPUTS, List.of());
        data.put(RESULTS, List.of());
        data.put(KNOWLEDGE, List.of());
        data.put(ANSWER_CONTEXT, "");
        data.put(NODE_STATUSES, Map.of());
        data.put(FAILURES, List.of());
        data.put(FINAL_STATUS, "PENDING");
        data.put(RESULT_COUNT, 0);
        data.put(KNOWLEDGE_COUNT, 0);
        data.put(BRANCH_SNAPSHOTS, List.of());
        data.put(COMPLETED_BRANCHES, List.of());
        data.put(PENDING_BRANCHES, List.of());
        data.put(RETRY_COUNTS, Map.of());
        data.put(PLAN_HASH, plan.planHash());
        data.put(CHECKPOINT_VERSION, "v2");
        data.put(NEXT_NODE, "input.validate");
        data.put(RESOLVED_ORDER_CODE, "");
        data.put(RESOLVED_ORDER_AT, "");
        data.put(DEPENDENCY_STATUSES, Map.of());
        data.put(PUBLISHED_RESULT_KINDS, List.of());
        return new CompositeQueryState(data);
    }

    public String requestId() {
        return this.<String>value(REQUEST_ID).orElse("");
    }

    public String conversationId() {
        return this.<String>value(CONVERSATION_ID).orElse("");
    }

    public String userMessage() {
        return this.<String>value(USER_MESSAGE).orElse("");
    }

    public CompositeQueryPlan plan() {
        return this.<CompositeQueryPlan>value(PLAN).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public Set<String> requiredResultKinds() {
        return this.<Set<String>>value(REQUIRED_RESULT_KINDS).orElseGet(Set::of);
    }

    @SuppressWarnings("unchecked")
    public List<String> missingInputs() {
        return this.<List<String>>value(MISSING_INPUTS).orElseGet(List::of);
    }

    @SuppressWarnings("unchecked")
    public List<com.xjjk.agent.tool.ToolUiResult> results() {
        return this.<List<com.xjjk.agent.tool.ToolUiResult>>value(RESULTS)
                .orElseGet(List::of);
    }

    @SuppressWarnings("unchecked")
    public List<com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult> knowledge() {
        return this.<List<com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult>>value(KNOWLEDGE)
                .orElseGet(List::of);
    }

    public String answerContext() {
        return this.<String>value(ANSWER_CONTEXT).orElse("");
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> nodeStatuses() {
        return this.<Map<String, String>>value(NODE_STATUSES).orElseGet(Map::of);
    }

    @SuppressWarnings("unchecked")
    public List<String> failures() {
        return this.<List<String>>value(FAILURES).orElseGet(List::of);
    }

    public String finalStatus() {
        return this.<String>value(FINAL_STATUS).orElse("PENDING");
    }

    public String resolvedOrderCode() {
        return this.<String>value(RESOLVED_ORDER_CODE).orElse("");
    }

    public String resolvedOrderAt() {
        return this.<String>value(RESOLVED_ORDER_AT).orElse("");
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> dependencyStatuses() {
        Object raw = value(DEPENDENCY_STATUSES).orElse(Map.of());
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return Map.copyOf(result);
    }

    public Set<String> publishedResultKinds() {
        Object raw = value(PUBLISHED_RESULT_KINDS).orElse(List.of());
        if (!(raw instanceof Iterable<?> values)) return Set.of();
        Set<String> result = new java.util.LinkedHashSet<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                result.add(String.valueOf(value));
            }
        }
        return Set.copyOf(result);
    }

    public List<CompositeQueryBranchSnapshot> branchSnapshots() {
        Object raw = value(BRANCH_SNAPSHOTS).orElse(List.of());
        if (!(raw instanceof List<?> list)) return List.of();
        List<CompositeQueryBranchSnapshot> snapshots = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof CompositeQueryBranchSnapshot snapshot) {
                snapshots.add(snapshot);
            } else if (item instanceof Map<?, ?> map) {
                snapshots.add(new CompositeQueryBranchSnapshot(
                        mapText(map, "branchName"),
                        mapText(map, "resultKind"),
                        mapText(map, "status"),
                        mapText(map, "safeSummary"),
                        mapText(map, "failureSummary"),
                        number(map.get("retryCount"))));
            }
        }
        return List.copyOf(snapshots);
    }

    @SuppressWarnings("unchecked")
    public List<String> completedBranches() {
        return this.<List<String>>value(COMPLETED_BRANCHES).orElseGet(List::of);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String mapText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 生成可写日志的安全状态视图，只包含流程定位和结果状态，不输出用户原文或完整业务对象。
     */
    public Map<String, Object> toSafeLogData() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put(REQUEST_ID, requestId());
        safe.put(CONVERSATION_ID, conversationId());
        safe.put(REQUIRED_RESULT_KINDS, requiredResultKinds());
        safe.put(MISSING_INPUTS, missingInputs());
        safe.put(NODE_STATUSES, nodeStatuses());
        safe.put(FAILURES, failures());
        safe.put(FINAL_STATUS, finalStatus());
        safe.put(COMPLETED_BRANCHES, this.<List<String>>value(COMPLETED_BRANCHES)
                .orElseGet(List::of));
        safe.put(PENDING_BRANCHES, this.<List<String>>value(PENDING_BRANCHES)
                .orElseGet(List::of));
        safe.put(RESOLVED_ORDER_CODE, resolvedOrderCode());
        safe.put(RESOLVED_ORDER_AT, resolvedOrderAt());
        safe.put(DEPENDENCY_STATUSES, dependencyStatuses());
        safe.put(PUBLISHED_RESULT_KINDS, publishedResultKinds());
        return safe;
    }

    /**
     * 用同名分支的最新快照替换旧快照，并重新计算成功完成的分支集合。
     * 分支快照只保存安全摘要，供 checkpoint 恢复和降级回答使用。
     */
    static CompositeQueryState withBranchSnapshot(
            CompositeQueryState state, CompositeQueryBranchSnapshot snapshot) {
        List<CompositeQueryBranchSnapshot> snapshots = new ArrayList<>(state.branchSnapshots());
        snapshots.removeIf(value -> value.branchName().equals(snapshot.branchName()));
        snapshots.add(snapshot);
        List<String> completed = snapshots.stream()
                .filter(value -> "SUCCESS".equals(value.status()))
                .map(CompositeQueryBranchSnapshot::branchName)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        data.put(BRANCH_SNAPSHOTS, List.copyOf(snapshots));
        data.put(COMPLETED_BRANCHES, completed);
        return new CompositeQueryState(data);
    }

    /**
     * 构造一个节点执行后的状态增量，而不是重新返回完整 State。
     *
     * <p>调用方提供本节点要写入的业务字段，本方法再统一更新 nodeStatuses；返回的 Map
     * 由 LangGraph4j 合并进当前状态后传递给后续节点。</p>
     */
    static Map<String, Object> update(
            CompositeQueryState state,
            String node,
            String status,
            Map<String, Object> values) {
        Map<String, String> nodeStatuses = new LinkedHashMap<>(state.nodeStatuses());
        nodeStatuses.put(node, status);
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put(NODE_STATUSES, nodeStatuses);
        return result;
    }
}
