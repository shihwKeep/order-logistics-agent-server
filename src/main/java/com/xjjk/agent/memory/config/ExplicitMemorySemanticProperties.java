package com.xjjk.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agent.memory.semantic")
public record ExplicitMemorySemanticProperties(double confidenceThreshold) {

    public ExplicitMemorySemanticProperties {
        if (!Double.isFinite(confidenceThreshold)
                || confidenceThreshold <= 0.0
                || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException("显式记忆语义置信度阈值必须位于(0,1]");
        }
    }
}
