package com.xjjk.agent.chat.orchestration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 已经过安全路由的复合查询计划。 */
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

    public Set<String> requiredResultKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        intents.stream().filter(CompositeQueryIntent::required)
                .forEach(intent -> kinds.add(intent.resultKind()));
        return Set.copyOf(kinds);
    }

    public Set<String> resultKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        intents.forEach(intent -> kinds.add(intent.resultKind()));
        return Set.copyOf(kinds);
    }

    public String planHash() {
        return Integer.toHexString(intents.toString().hashCode())
                + (requiresExternalSource ? ":external" : ":internal");
    }

    public boolean hasSource(CompositeQueryIntent.Source source) {
        return intents.stream().anyMatch(intent -> intent.source() == source);
    }

    /**
     * 业务事实决定后续知识是否有适用对象时，知识查询必须在业务查询之后执行。
     * 独立的业务+知识复合问题仍由图并行执行。
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
        return hasDependency || hasOrderExistenceCheck;
    }
}
