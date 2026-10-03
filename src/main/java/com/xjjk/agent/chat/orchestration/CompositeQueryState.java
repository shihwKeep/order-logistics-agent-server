package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** LangGraph4j 图状态；认证身份只在节点闭包中传递，不进入状态。 */
public final class CompositeQueryState extends AgentState {
    static final String REQUEST_ID = "requestId";
    static final String CONVERSATION_ID = "conversationId";
    static final String USER_MESSAGE = "userMessage";
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
    static final String BRANCH_SNAPSHOTS = "branchSnapshots";
    static final String COMPLETED_BRANCHES = "completedBranches";
    static final String PENDING_BRANCHES = "pendingBranches";
    static final String RETRY_COUNTS = "retryCounts";
    static final String PLAN_HASH = "planHash";
    static final String CHECKPOINT_VERSION = "checkpointVersion";
    static final String NEXT_NODE = "nextNode";

    public CompositeQueryState(Map<String, Object> data) {
        super(data);
    }

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

    @SuppressWarnings("unchecked")
    public List<CompositeQueryBranchSnapshot> branchSnapshots() {
        return this.<List<CompositeQueryBranchSnapshot>>value(BRANCH_SNAPSHOTS)
                .orElseGet(List::of);
    }

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
        return safe;
    }

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
