package com.xjjk.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Agent 端候选终审上限；Knowledge 端自行控制索引召回规模。 */
@Validated
@ConfigurationProperties(prefix = "agent.memory.retrieval")
public record MemoryRetrievalProperties(
        int maxCandidates,
        int maxSelected,
        int globalExplicitLimit) {

    public MemoryRetrievalProperties {
        if (maxCandidates <= 0 || maxCandidates > 100
                || maxSelected <= 0 || maxSelected > 10 || maxSelected > maxCandidates
                || globalExplicitLimit <= 0 || globalExplicitLimit > 10) {
            throw new IllegalArgumentException("用户记忆召回配置不合法");
        }
    }
}
