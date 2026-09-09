package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;

import java.util.Objects;
import java.util.Set;

/** 当前用户消息的业务查询执行计划，不包含历史消息或可信身份。 */
public record BusinessQueryPlan(
        BusinessQueryMode mode,
        ChatActionRequest directAction,
        Set<String> acceptedResultKinds) {

    public BusinessQueryPlan {
        Objects.requireNonNull(mode, "查询计划类型不能为空");
        acceptedResultKinds = Set.copyOf(
                Objects.requireNonNull(acceptedResultKinds, "结果类型集合不能为空"));
        if (mode == BusinessQueryMode.GENERAL
                && (directAction != null || !acceptedResultKinds.isEmpty())) {
            throw new IllegalArgumentException("普通问答不能携带业务执行计划");
        }
        if (mode == BusinessQueryMode.DIRECT
                && (directAction == null || acceptedResultKinds.size() != 1)) {
            throw new IllegalArgumentException("确定性查询必须携带动作和唯一结果类型");
        }
        if (mode == BusinessQueryMode.MODEL_REQUIRED
                && (directAction != null || acceptedResultKinds.isEmpty())) {
            throw new IllegalArgumentException("模型业务查询必须携带允许结果类型");
        }
        if (acceptedResultKinds.stream().anyMatch(
                value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("结果类型不能为空");
        }
    }

    public static BusinessQueryPlan general() {
        return new BusinessQueryPlan(BusinessQueryMode.GENERAL, null, Set.of());
    }

    public static BusinessQueryPlan direct(
            ChatActionRequest action, String acceptedResultKind) {
        if (acceptedResultKind == null || acceptedResultKind.isBlank()) {
            throw new IllegalArgumentException("结果类型不能为空");
        }
        return new BusinessQueryPlan(
                BusinessQueryMode.DIRECT,
                Objects.requireNonNull(action, "确定性动作不能为空"),
                Set.of(acceptedResultKind));
    }

    public static BusinessQueryPlan modelRequired(Set<String> acceptedResultKinds) {
        return new BusinessQueryPlan(
                BusinessQueryMode.MODEL_REQUIRED, null, acceptedResultKinds);
    }

    public boolean buffersModelOutput() {
        return mode == BusinessQueryMode.MODEL_REQUIRED;
    }
}
