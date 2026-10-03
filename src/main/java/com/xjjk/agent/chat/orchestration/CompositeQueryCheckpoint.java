package com.xjjk.agent.chat.orchestration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redis 中保存的 JSON-safe 复合查询 checkpoint。 */
public record CompositeQueryCheckpoint(
        String graphVersion,
        String threadId,
        String requestId,
        String conversationId,
        String planHash,
        Map<String, Object> stateJson,
        List<String> completedNodes,
        List<String> pendingNodes,
        Map<String, Integer> retryCounts,
        String nextNode,
        String status,
        String updatedAt) implements java.io.Serializable {

    public CompositeQueryCheckpoint {
        graphVersion = requireText(graphVersion, "图版本不能为空");
        threadId = requireText(threadId, "线程 ID 不能为空");
        requestId = requireText(requestId, "请求 ID 不能为空");
        conversationId = conversationId == null ? "" : conversationId.strip();
        planHash = requireText(planHash, "计划摘要不能为空");
        stateJson = Map.copyOf(Objects.requireNonNull(stateJson, "状态不能为空"));
        completedNodes = List.copyOf(Objects.requireNonNull(completedNodes, "已完成节点不能为空"));
        pendingNodes = List.copyOf(Objects.requireNonNull(pendingNodes, "待执行节点不能为空"));
        retryCounts = Map.copyOf(Objects.requireNonNull(retryCounts, "重试次数不能为空"));
        nextNode = nextNode == null ? "" : nextNode.strip();
        status = requireText(status, "工作流状态不能为空");
        updatedAt = requireText(updatedAt, "更新时间不能为空");
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePoints()
                .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
