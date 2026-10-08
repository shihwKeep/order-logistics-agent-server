package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** 聊天 SSE 通道的总生命周期与保活间隔。 */
@ConfigurationProperties(prefix = "agent.chat.stream")
public record ChatStreamProperties(
        Duration timeout,
        Duration heartbeatInterval,
        Replay replay,
        Reconnect reconnect,
        ModelRetry modelRetry,
        long testDelayMs
) {

    @ConstructorBinding
    public ChatStreamProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("聊天流超时时间必须大于零");
        }
        if (heartbeatInterval == null
                || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("聊天流心跳间隔必须大于零且小于总超时");
        }
        if (replay == null) {
            throw new IllegalArgumentException("聊天流补发配置不能为空");
        }
        if (reconnect == null) {
            throw new IllegalArgumentException("聊天流重连配置不能为空");
        }
        if (modelRetry == null) {
            modelRetry = ModelRetry.defaults();
        }
        if (testDelayMs < 0) {
            throw new IllegalArgumentException("聊天流测试延迟不能为负数");
        }
    }

    /** 兼容已有单元测试和直连调用，生产配置仍由顶层字段完整绑定。 */
    public ChatStreamProperties(Duration timeout, Duration heartbeatInterval) {
        this(timeout, heartbeatInterval, Replay.defaults(), Reconnect.defaults(),
                ModelRetry.defaults(), 0);
    }

    /** 兼容只显式构造回放/重连配置的测试和内部调用。 */
    public ChatStreamProperties(
            Duration timeout,
            Duration heartbeatInterval,
            Replay replay,
            Reconnect reconnect
    ) {
        this(timeout, heartbeatInterval, replay, reconnect, ModelRetry.defaults(), 0);
    }

    public record Replay(
            boolean enabled,
            String keyPrefix,
            Duration ttl,
            int maxEvents,
            int maxEventBytes,
            int maxStreamBytes,
            Duration readBlockTimeout
    ) {

        public Replay {
            if (!StringUtils.hasText(keyPrefix)) {
                throw new IllegalArgumentException("聊天流补发 Key 前缀不能为空");
            }
            requirePositive(ttl, "聊天流补发 TTL 必须大于零");
            requirePositive(readBlockTimeout, "聊天流补发阻塞读取时间必须大于零");
            if (maxEvents <= 0) {
                throw new IllegalArgumentException("聊天流补发事件数量必须大于零");
            }
            if (maxEventBytes <= 0) {
                throw new IllegalArgumentException("聊天流单事件字节上限必须大于零");
            }
            if (maxStreamBytes < maxEventBytes) {
                throw new IllegalArgumentException("聊天流总字节上限不能小于单事件上限");
            }
        }

        public static Replay defaults() {
            return new Replay(
                    true,
                    "agent:chat:stream:v1",
                    Duration.ofMinutes(2),
                    512,
                    65_536,
                    1_048_576,
                    Duration.ofSeconds(5));
        }
    }

    public record Reconnect(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            double jitterRatio
    ) {

        public Reconnect {
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("聊天流最大重连次数必须大于零");
            }
            requirePositive(initialBackoff, "聊天流初始退避必须大于零");
            requirePositive(maxBackoff, "聊天流最大退避必须大于零");
            if (initialBackoff.compareTo(maxBackoff) > 0) {
                throw new IllegalArgumentException("聊天流初始退避不能大于最大退避");
            }
            if (!Double.isFinite(jitterRatio)
                    || jitterRatio < 0.0
                    || jitterRatio >= 1.0) {
                throw new IllegalArgumentException("聊天流随机抖动比例必须在 [0, 1) 范围内");
            }
        }

        public static Reconnect defaults() {
            return new Reconnect(
                    5,
                    Duration.ofMillis(500),
                    Duration.ofSeconds(8),
                    0.2);
        }
    }

    /** 上游模型流的有限重试配置，与浏览器 SSE 断点重连相互独立。 */
    public record ModelRetry(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            double jitterRatio
    ) {

        public ModelRetry {
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("模型流最大尝试次数必须大于零");
            }
            requirePositiveOrZero(initialBackoff, "模型流初始退避不能为负数");
            requirePositiveOrZero(maxBackoff, "模型流最大退避不能为负数");
            if (initialBackoff.compareTo(maxBackoff) > 0) {
                throw new IllegalArgumentException("模型流初始退避不能大于最大退避");
            }
            if (!Double.isFinite(jitterRatio)
                    || jitterRatio < 0.0
                    || jitterRatio >= 1.0) {
                throw new IllegalArgumentException("模型流随机抖动比例必须在 [0, 1) 范围内");
            }
        }

        public static ModelRetry defaults() {
            return new ModelRetry(
                    3,
                    Duration.ofMillis(500),
                    Duration.ofSeconds(2),
                    0.2);
        }
    }

    private static void requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requirePositiveOrZero(Duration value, String message) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
    }
}
