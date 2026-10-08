package com.xjjk.agent.chat.orchestration;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * LangGraph4j {@link Checkpoint} 与项目 Redis checkpoint 之间的适配器。
 *
 * <p>工作流节点仍使用 LangGraph4j 的 {@link BaseCheckpointSaver} 接口，本类负责把框架
 * Checkpoint 转换成只含安全字段的 {@link CompositeQueryCheckpoint}，再交给 Store 持久化；
 * 恢复时执行相反转换。完整业务对象、用户原文、知识正文和认证身份都不会进入 Redis。</p>
 *
 * <pre>
 * put: LangGraph4j Checkpoint -> 安全字段白名单 -> CompositeQueryCheckpoint -> Store
 * get: Store -> CompositeQueryCheckpoint -> LangGraph4j Checkpoint
 * release: threadId -> 删除 Store 中对应状态
 * </pre>
 */
public final class LangGraph4jRedisCheckpointSaver implements BaseCheckpointSaver {

    // checkpoint 采用显式白名单：新增 State 字段不会自动被持久化，必须先评估安全性和恢复价值。
    private static final Set<String> SAFE_STATE_KEYS = Set.of(
            CompositeQueryState.REQUEST_ID,
            CompositeQueryState.CONVERSATION_ID,
            CompositeQueryState.REQUIRED_RESULT_KINDS,
            CompositeQueryState.MISSING_INPUTS,
            CompositeQueryState.NODE_STATUSES,
            CompositeQueryState.FAILURES,
            CompositeQueryState.FINAL_STATUS,
            CompositeQueryState.BRANCH_SNAPSHOTS,
            CompositeQueryState.COMPLETED_BRANCHES,
            CompositeQueryState.PENDING_BRANCHES,
            CompositeQueryState.RETRY_COUNTS,
            CompositeQueryState.PLAN_HASH,
            CompositeQueryState.CHECKPOINT_VERSION,
            CompositeQueryState.NEXT_NODE,
            CompositeQueryState.RESOLVED_ORDER_CODE,
            CompositeQueryState.RESOLVED_ORDER_AT,
            CompositeQueryState.DEPENDENCY_STATUSES,
            CompositeQueryState.PUBLISHED_RESULT_KINDS);

    private final CompositeQueryCheckpointStore store;
    private final String graphVersion;
    private final Clock clock;

    public LangGraph4jRedisCheckpointSaver(
            CompositeQueryCheckpointStore store,
            String graphVersion,
            Clock clock) {
        this.store = java.util.Objects.requireNonNull(store, "checkpoint store 不能为空");
        this.graphVersion = requireText(graphVersion, "图版本不能为空");
        this.clock = java.util.Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** LangGraph4j 的列表接口；当前每个 requestId/threadId 只保留最新一份可恢复状态。 */
    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        return get(config).map(List::of).orElseGet(List::of);
    }

    /** 使用 RunnableConfig.threadId（本项目即 requestId）读取并转换最新 checkpoint。 */
    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = requireThreadId(config);
        return store.load(threadId).map(this::toLangGraphCheckpoint);
    }

    /**
     * 保存 LangGraph4j 推进到当前节点后的状态。
     *
     * <p>步骤：提取白名单状态 -> 解析请求及计划元数据 -> 推导已完成/待执行节点
     * -> 生成业务 checkpoint -> 交给 Redis Store，并把框架 checkpointId 写回配置。</p>
     */
    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        String threadId = requireThreadId(config);
        Map<String, Object> safeState = safeState(checkpoint.getState());
        String requestId = textOrDefault(safeState.get(CompositeQueryState.REQUEST_ID), threadId);
        String conversationId = textOrDefault(
                safeState.get(CompositeQueryState.CONVERSATION_ID), "");
        String planHash = textOrDefault(safeState.get(CompositeQueryState.PLAN_HASH), "unknown");
        String status = textOrDefault(safeState.get(CompositeQueryState.FINAL_STATUS), "PENDING");
        List<String> completedNodes = completedNodes(checkpoint, safeState);
        List<String> pendingNodes = checkpoint.getNextNodeId() == null
                ? List.of() : List.of(checkpoint.getNextNodeId());
        Map<String, Integer> retryCounts = intMap(safeState.get(CompositeQueryState.RETRY_COUNTS));
        String nextNode = checkpoint.getNextNodeId() == null ? "" : checkpoint.getNextNodeId();
        String updatedAt = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)).toString();
        store.save(new CompositeQueryCheckpoint(
                graphVersion, threadId, requestId, conversationId, planHash,
                safeState, completedNodes, pendingNodes, retryCounts,
                nextNode, status, updatedAt));
        return config.withCheckPointId(checkpoint.getId());
    }

    /** 显式释放当前 threadId 的持久化状态；正常过期仍由 Redis TTL 兜底。 */
    @Override
    public Tag release(RunnableConfig config) {
        String threadId = requireThreadId(config);
        store.delete(threadId);
        return new Tag(threadId, List.of());
    }

    /**
     * 把项目 checkpoint 还原成 LangGraph4j 可继续调度的 Checkpoint。
     * nodeId 表示最近完成节点，nextNodeId 表示恢复后应继续调度的位置。
     */
    private Checkpoint toLangGraphCheckpoint(CompositeQueryCheckpoint checkpoint) {
        String nodeId = checkpoint.completedNodes().isEmpty()
                ? "" : checkpoint.completedNodes().get(checkpoint.completedNodes().size() - 1);
        return Checkpoint.builder()
                .id("redis:" + checkpoint.updatedAt())
                .state(checkpoint.stateJson())
                .nodeId(nodeId)
                .nextNodeId(checkpoint.nextNode())
                .build();
    }

    /** 仅复制显式允许持久化的状态键，未知字段和空值一律丢弃。 */
    private Map<String, Object> safeState(Map<String, Object> state) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (state == null) return safe;
        state.forEach((key, value) -> {
            if (SAFE_STATE_KEYS.contains(key) && value != null) {
                safe.put(key, value);
            }
        });
        return safe;
    }

    /**
     * 优先根据 nodeStatuses 中的 SUCCESS 推导全部已完成节点；旧状态缺少该字段时，
     * 退化为框架提供的最近节点，保证版本演进时仍能读取已有 checkpoint。
     */
    @SuppressWarnings("unchecked")
    private List<String> completedNodes(Checkpoint checkpoint, Map<String, Object> state) {
        Object statuses = state.get(CompositeQueryState.NODE_STATUSES);
        if (statuses instanceof Map<?, ?> map) {
            List<String> nodes = new ArrayList<>();
            map.forEach((key, value) -> {
                if ("SUCCESS".equals(String.valueOf(value))) {
                    nodes.add(String.valueOf(key));
                }
            });
            if (!nodes.isEmpty()) return List.copyOf(nodes);
        }
        return checkpoint.getNodeId() == null || checkpoint.getNodeId().isBlank()
                ? List.of() : List.of(checkpoint.getNodeId());
    }

    private Map<String, Integer> intMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item instanceof Number number) {
                result.put(String.valueOf(key), number.intValue());
            }
        });
        return result;
    }

    /** checkpoint 必须绑定 threadId，否则无法安全定位属于哪一轮请求。 */
    private String requireThreadId(RunnableConfig config) {
        return config.threadId().orElseThrow(
                () -> new IllegalArgumentException("LangGraph4j checkpoint 缺少 threadId"));
    }

    private String textOrDefault(Object value, String defaultValue) {
        return value == null || String.valueOf(value).isBlank()
                ? defaultValue : String.valueOf(value);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }
}
