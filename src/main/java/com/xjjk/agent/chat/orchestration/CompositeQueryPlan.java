package com.xjjk.agent.chat.orchestration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已经过 {@code BusinessQueryPlanner} 安全路由的复合查询计划。
 *
 * <p>Plan 是自然语言路由与 LangGraph4j 工作流之间的结构化契约：intents 描述本轮
 * 需要哪些业务、知识或分析结果，requiresExternalSource 记录用户是否要求当前系统尚未
 * 接入的外部市场数据。计划只包含公开查询标识，不包含认证身份和完整业务结果。</p>
 */
public record CompositeQueryPlan(
        List<CompositeQueryIntent> intents,
        boolean requiresExternalSource) implements java.io.Serializable {

    public CompositeQueryPlan {
        Objects.requireNonNull(intents, "复合意图不能为空");
        intents = List.copyOf(intents);
        if (intents.size() < 2) {
            throw new IllegalArgumentException("复合查询至少需要两个信息源");
        }
        if (intents.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("复合意图不能包含空值");
        }
    }

    public static CompositeQueryPlan of(List<CompositeQueryIntent> intents) {
        return new CompositeQueryPlan(intents, false);
    }

    public static CompositeQueryPlan withExternalSource(
            List<CompositeQueryIntent> intents, boolean requiresExternalSource) {
        return new CompositeQueryPlan(intents, requiresExternalSource);
    }

    /**
     * 返回决定本轮是否可以完整成功的结果类型。
     * 标记为非必需的外部数据边界不会因为当前无法取得而拖垮核心业务查询。
     */
    public Set<String> requiredResultKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        intents.stream().filter(CompositeQueryIntent::required)
                .forEach(intent -> kinds.add(intent.resultKind()));
        return Set.copyOf(kinds);
    }

    /** 返回本计划声明的全部结果类型，用作上层结构化结果白名单。 */
    public Set<String> resultKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        intents.forEach(intent -> kinds.add(intent.resultKind()));
        return Set.copyOf(kinds);
    }

    /**
     * 生成 checkpoint 使用的计划一致性标识。
     * 这是用于识别计划变化的轻量标识，不是密码学哈希，也不承担认证或防篡改职责。
     */
    public String planHash() {
        return Integer.toHexString(intents.toString().hashCode())
                + (requiresExternalSource ? ":external" : ":internal");
    }

    /** 判断本计划是否包含指定信息源，用于构建图的串行或并行拓扑。 */
    public boolean hasSource(CompositeQueryIntent.Source source) {
        return intents.stream().anyMatch(intent -> intent.source() == source);
    }

    /**
     * 判断知识查询是否必须等待业务结果门禁。
     *
     * <p>存在以下任一条件时，图使用 {@code business.query -> knowledge.query} 串行边：</p>
     * <ul>
     *     <li>后续查询依赖前置结果解析出的标识，例如客户最近订单号；</li>
     *     <li>同时查询订单和物流，需要先确认订单存在；</li>
     *     <li>查询商品和定价规则，需要先确认 SKU/商品存在。</li>
     * </ul>
     *
     * <p>不存在这些依赖时，业务和知识分支可以从 branch.dispatch 并行启动。</p>
     */
    public boolean knowledgeRequiresBusinessGate() {
        if (!hasSource(CompositeQueryIntent.Source.KNOWLEDGE)
                || !hasSource(CompositeQueryIntent.Source.BUSINESS)) {
            return false;
        }
        boolean hasDependency = intents.stream().anyMatch(intent ->
                intent.dependencyMode() != CompositeQueryIntent.DependencyMode.NONE);
        boolean hasOrderExistenceCheck = intents.stream().anyMatch(intent ->
                "order-list".equals(intent.resultKind()))
                && intents.stream().anyMatch(intent ->
                "logistics-timeline".equals(intent.resultKind()));
        boolean hasProductExistenceCheck = intents.stream().anyMatch(intent ->
                "product-list".equals(intent.resultKind()));
        return hasDependency || hasOrderExistenceCheck || hasProductExistenceCheck;
    }
}
