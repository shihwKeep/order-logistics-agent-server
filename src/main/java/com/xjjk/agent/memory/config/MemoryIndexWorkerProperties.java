package com.xjjk.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 用户记忆索引 Outbox 投递配置，生产值由 Nacos 覆盖。 */
@Validated
@ConfigurationProperties(prefix = "agent.memory.index-worker")
public record MemoryIndexWorkerProperties(
        Duration pollInterval,
        Duration recoveryInterval,
        int claimBatchSize,
        Duration leaseDuration,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Executor executor) {

    public MemoryIndexWorkerProperties {
        if (!positive(pollInterval) || !positive(recoveryInterval)
                || claimBatchSize <= 0 || claimBatchSize > 100
                || !positive(leaseDuration) || maxAttempts <= 0 || maxAttempts > 20
                || !positive(initialBackoff) || !positive(maxBackoff)
                || initialBackoff.compareTo(maxBackoff) > 0 || executor == null) {
            throw new IllegalArgumentException("用户记忆索引任务配置不合法");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    public record Executor(int poolSize, int queueCapacity) {
        public Executor {
            if (poolSize <= 0 || poolSize > 32 || queueCapacity <= 0
                    || queueCapacity > 10_000) {
                throw new IllegalArgumentException("用户记忆索引执行器配置不合法");
            }
        }
    }
}
