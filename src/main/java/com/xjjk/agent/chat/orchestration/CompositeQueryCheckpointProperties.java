package com.xjjk.agent.chat.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.chat.composite.checkpoint")
public record CompositeQueryCheckpointProperties(
        boolean enabled,
        String keyPrefix,
        String graphVersion,
        Duration ttl) {

    public CompositeQueryCheckpointProperties {
        if (!StringUtils.hasText(keyPrefix)) {
            throw new IllegalArgumentException("复合查询 checkpoint 前缀不能为空");
        }
        if (!StringUtils.hasText(graphVersion)) {
            throw new IllegalArgumentException("复合查询 checkpoint 版本不能为空");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("复合查询 checkpoint TTL 必须大于零");
        }
    }
}
