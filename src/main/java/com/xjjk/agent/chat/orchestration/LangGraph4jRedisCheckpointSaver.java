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

/** 将 LangGraph4j checkpoint 映射为可恢复、脱敏的 Redis 状态。 */
public final class LangGraph4jRedisCheckpointSaver implements BaseCheckpointSaver {

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
            CompositeQueryState.NEXT_NODE);

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

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        return get(config).map(List::of).orElseGet(List::of);
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = requireThreadId(config);
        return store.load(threadId).map(this::toLangGraphCheckpoint);
    }

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

    @Override
    public Tag release(RunnableConfig config) {
        String threadId = requireThreadId(config);
        store.delete(threadId);
        return new Tag(threadId, List.of());
    }

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
