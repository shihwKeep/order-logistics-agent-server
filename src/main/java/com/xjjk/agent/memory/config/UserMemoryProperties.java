package com.xjjk.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 用户跨会话记忆运行配置，生产值由 Nacos 提供。 */
@Validated
@ConfigurationProperties(prefix = "agent.memory")
public record UserMemoryProperties(
        boolean enabled,
        boolean autoExtractDefaultEnabled,
        int contextMaxTokens,
        int maxContentLength,
        int maxEvidenceLength,
        int pageSizeMax,
        int explicitExpireDays,
        String promptVersion,
        String model,
        double temperature,
        Duration timeout,
        int modelPoolSize,
        int modelQueueCapacity
) {

    public UserMemoryProperties {
        if (contextMaxTokens <= 0
                || maxContentLength <= 0 || maxContentLength > 512
                || maxEvidenceLength <= 0 || maxEvidenceLength > 512
                || pageSizeMax <= 0 || pageSizeMax > 100
                || explicitExpireDays <= 0
                || modelPoolSize <= 0 || modelQueueCapacity <= 0) {
            throw new IllegalArgumentException("用户记忆数值配置不合法");
        }
        if (!StringUtils.hasText(promptVersion)
                || promptVersion.length() > 64
                || !StringUtils.hasText(model)
                || model.length() > 128) {
            throw new IllegalArgumentException("用户记忆模型配置不合法");
        }
        if (!Double.isFinite(temperature)
                || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("用户记忆模型温度不合法");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("用户记忆模型超时必须大于零");
        }
    }
}
