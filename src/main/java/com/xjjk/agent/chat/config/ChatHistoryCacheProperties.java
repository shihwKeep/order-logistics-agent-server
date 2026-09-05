package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * 会话历史缓存配置。
 *
 * @param enabled 是否启用 Redis 历史缓存
 * @param keyPrefix 缓存 Key 前缀及结构版本
 * @param ttl 基础过期时间
 * @param ttlJitter 过期时间随机抖动上限
 * @param warmCorePoolSize 预热线程池核心线程数
 * @param warmMaxPoolSize 预热线程池最大线程数
 * @param warmQueueCapacity 预热任务队列容量
 */
@Validated
@ConfigurationProperties(prefix = "agent.chat.history.cache")
public record ChatHistoryCacheProperties(
        boolean enabled,
        String keyPrefix,
        Duration ttl,
        Duration ttlJitter,
        int warmCorePoolSize,
        int warmMaxPoolSize,
        int warmQueueCapacity
) {

    private static final Duration MAX_TTL = Duration.ofDays(365);

    public ChatHistoryCacheProperties {
        if (!StringUtils.hasText(keyPrefix)
                || !keyPrefix.matches("[A-Za-z0-9:_-]+")
                || keyPrefix.endsWith(":")) {
            throw new IllegalArgumentException("历史缓存 Key 前缀不合法");
        }

        Objects.requireNonNull(ttl, "历史缓存 TTL 不能为空");
        Objects.requireNonNull(ttlJitter, "历史缓存 TTL 抖动不能为空");

        if (ttl.isZero()
                || ttl.isNegative()
                || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "历史缓存 TTL 必须大于零且不能超过 365 天"
            );
        }

        if (ttlJitter.isNegative()
                || ttlJitter.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException(
                    "历史缓存 TTL 抖动必须大于等于零且小于 TTL"
            );
        }

        if (warmCorePoolSize <= 0
                || warmMaxPoolSize < warmCorePoolSize
                || warmQueueCapacity <= 0) {
            throw new IllegalArgumentException("历史缓存预热线程池配置不合法");
        }
    }
}
