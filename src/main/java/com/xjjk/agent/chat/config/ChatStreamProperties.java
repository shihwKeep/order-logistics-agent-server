package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 聊天 SSE 通道的总生命周期与保活间隔。 */
@ConfigurationProperties(prefix = "agent.chat.stream")
public record ChatStreamProperties(
        Duration timeout,
        Duration heartbeatInterval
) {

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
    }
}
