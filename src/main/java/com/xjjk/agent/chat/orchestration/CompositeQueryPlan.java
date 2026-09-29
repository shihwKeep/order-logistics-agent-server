package com.xjjk.agent.chat.orchestration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 已经过安全路由的复合查询计划。 */
public record CompositeQueryPlan(
        List<CompositeQueryIntent> intents,
        boolean requiresExternalSource) {

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

    public Set<String> requiredResultKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        intents.forEach(intent -> kinds.add(intent.resultKind()));
        return Set.copyOf(kinds);
    }

    public boolean hasSource(CompositeQueryIntent.Source source) {
        return intents.stream().anyMatch(intent -> intent.source() == source);
    }
}
