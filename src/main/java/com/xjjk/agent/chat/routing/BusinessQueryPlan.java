package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;

import java.util.Objects;
import java.util.Set;

/**
 * 当前用户消息的业务查询执行计划，不包含历史消息或可信身份。
 *
 * @param mode 本轮执行模式
 * @param directAction 高置信直接查询时执行的白名单动作，其余模式为空
 * @param acceptedResultKinds 本轮允许产生的前端结构化结果类型
 */
public record BusinessQueryPlan(
        BusinessQueryMode mode,
        ChatActionRequest directAction,
        Set<String> acceptedResultKinds) {

    public BusinessQueryPlan {
        // 在领域对象构造时一次性固定三种模式的不变量，调用链后续无需反复防御空组合。
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

    /** 构造不要求实时业务结果的普通问答计划。 */
    public static BusinessQueryPlan general() {
        return new BusinessQueryPlan(BusinessQueryMode.GENERAL, null, Set.of());
    }

    /** 构造可直接执行且只接受一种结果卡片的确定性查询计划。 */
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

    /** 构造由模型选择工具、但必须通过新鲜结果门禁的查询计划。 */
    public static BusinessQueryPlan modelRequired(Set<String> acceptedResultKinds) {
        return new BusinessQueryPlan(
                BusinessQueryMode.MODEL_REQUIRED, null, acceptedResultKinds);
    }

    /** 只有模型实时业务查询需要先缓冲回答正文和工具结果。 */
    public boolean buffersModelOutput() {
        return mode == BusinessQueryMode.MODEL_REQUIRED;
    }
}
