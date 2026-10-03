package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.chat.orchestration.CompositeQueryPlan;

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
        Set<String> acceptedResultKinds,
        CompositeQueryPlan compositePlan,
        String clarificationMessage) {

    /** 兼容现有调用方；非澄清计划不携带澄清正文。 */
    public BusinessQueryPlan(
            BusinessQueryMode mode,
            ChatActionRequest directAction,
            Set<String> acceptedResultKinds,
            CompositeQueryPlan compositePlan) {
        this(mode, directAction, acceptedResultKinds, compositePlan, null);
    }

    public BusinessQueryPlan {
        // 在领域对象构造时一次性固定三种模式的不变量，调用链后续无需反复防御空组合。
        Objects.requireNonNull(mode, "查询计划类型不能为空");
        acceptedResultKinds = Set.copyOf(
                Objects.requireNonNull(acceptedResultKinds, "结果类型集合不能为空"));
        if (mode == BusinessQueryMode.GENERAL
                && (directAction != null || !acceptedResultKinds.isEmpty()
                || compositePlan != null || clarificationMessage != null)) {
            throw new IllegalArgumentException("普通问答不能携带业务执行计划");
        }
        if (mode == BusinessQueryMode.CLARIFICATION
                && (directAction != null || !acceptedResultKinds.isEmpty()
                || compositePlan != null || clarificationMessage == null
                || clarificationMessage.isBlank())) {
            throw new IllegalArgumentException("澄清计划必须携带澄清正文且不能携带业务执行计划");
        }
        if (mode == BusinessQueryMode.DIRECT
                && (directAction == null || acceptedResultKinds.size() != 1
                || compositePlan != null || clarificationMessage != null)) {
            throw new IllegalArgumentException("确定性查询必须携带动作和唯一结果类型");
        }
        if (mode == BusinessQueryMode.MODEL_REQUIRED
                && (directAction != null || acceptedResultKinds.isEmpty()
                || compositePlan != null || clarificationMessage != null)) {
            throw new IllegalArgumentException("模型业务查询必须携带允许结果类型");
        }
        if (mode == BusinessQueryMode.COMPOSITE
                && (directAction != null || compositePlan == null
                || compositePlan.intents().size() < 2
                || acceptedResultKinds.isEmpty() || clarificationMessage != null)) {
            throw new IllegalArgumentException(
                    "复合查询必须携带至少两个信息源且不能携带直接动作");
        }
        if (acceptedResultKinds.stream().anyMatch(
                value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("结果类型不能为空");
        }
    }

    /** 构造不要求实时业务结果的普通问答计划。 */
    public static BusinessQueryPlan general() {
        return new BusinessQueryPlan(BusinessQueryMode.GENERAL, null, Set.of(), null, null);
    }

    /** 构造缺少必要业务标识时的确定性澄清计划。 */
    public static BusinessQueryPlan clarification(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("澄清正文不能为空");
        }
        return new BusinessQueryPlan(
                BusinessQueryMode.CLARIFICATION, null, Set.of(), null, message);
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
                Set.of(acceptedResultKind),
                null,
                null);
    }

    /** 构造由模型选择工具、但必须通过新鲜结果门禁的查询计划。 */
    public static BusinessQueryPlan modelRequired(Set<String> acceptedResultKinds) {
        return new BusinessQueryPlan(
                BusinessQueryMode.MODEL_REQUIRED, null, acceptedResultKinds, null, null);
    }

    /** 构造由 LangGraph4j 编排的多来源查询计划。 */
    public static BusinessQueryPlan composite(CompositeQueryPlan compositePlan) {
        Objects.requireNonNull(compositePlan, "复合查询计划不能为空");
        return new BusinessQueryPlan(
                BusinessQueryMode.COMPOSITE,
                null,
                compositePlan.resultKinds(),
                compositePlan,
                null);
    }

    /** 只有模型实时业务查询需要先缓冲回答正文和工具结果。 */
    public boolean buffersModelOutput() {
        return mode == BusinessQueryMode.MODEL_REQUIRED
                || mode == BusinessQueryMode.COMPOSITE;
    }

    public boolean requiresExternalSource() {
        return compositePlan != null && compositePlan.requiresExternalSource();
    }
}
