package com.xjjk.agent.chat.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.chat.composite.parallel")
public record CompositeQueryParallelProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity) {

    public CompositeQueryParallelProperties {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1) {
            throw new IllegalArgumentException("复合查询并行线程池配置无效");
        }
    }
}
