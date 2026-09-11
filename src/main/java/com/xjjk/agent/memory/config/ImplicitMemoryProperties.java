package com.xjjk.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 隐式记忆抽取、任务执行与过期清理配置，生产值由 Nacos 提供。 */
@Validated
@ConfigurationProperties(prefix = "agent.memory.auto-extract")
public record ImplicitMemoryProperties(
        double confidenceThreshold,
        int expireDays,
        int maxCandidates,
        String promptVersion,
        String model,
        double temperature,
        Duration timeout,
        Executor modelExecutor,
        Worker worker,
        Expiry expiry
) {

    public ImplicitMemoryProperties {
        if (!Double.isFinite(confidenceThreshold)
                || confidenceThreshold < 0.0 || confidenceThreshold > 1.0
                || expireDays <= 0 || maxCandidates <= 0 || maxCandidates > 10) {
            throw new IllegalArgumentException("隐式记忆抽取数值配置不合法");
        }
        if (!StringUtils.hasText(promptVersion) || promptVersion.length() > 64
                || !StringUtils.hasText(model) || model.length() > 128
                || !Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0
                || !positive(timeout)) {
            throw new IllegalArgumentException("隐式记忆模型配置不合法");
        }
        require(modelExecutor, "隐式记忆模型执行器配置不能为空");
        require(worker, "隐式记忆任务执行器配置不能为空");
        require(expiry, "隐式记忆过期配置不能为空");
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public record Executor(int poolSize, int queueCapacity) {
        public Executor {
            if (poolSize <= 0 || queueCapacity <= 0) {
                throw new IllegalArgumentException("隐式记忆执行器配置不合法");
            }
        }
    }

    public record Worker(
            Duration pollInterval,
            Duration recoveryInterval,
            int claimBatchSize,
            Duration leaseDuration,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Executor executor
    ) {
        public Worker {
            if (!positive(pollInterval) || !positive(recoveryInterval)
                    || claimBatchSize <= 0 || claimBatchSize > 100
                    || !positive(leaseDuration) || maxAttempts <= 0 || maxAttempts > 20
                    || !positive(initialBackoff) || !positive(maxBackoff)
                    || initialBackoff.compareTo(maxBackoff) > 0 || executor == null) {
                throw new IllegalArgumentException("隐式记忆任务配置不合法");
            }
        }
    }

    public record Expiry(Duration pollInterval, int batchSize) {
        public Expiry {
            if (!positive(pollInterval) || batchSize <= 0 || batchSize > 1000) {
                throw new IllegalArgumentException("隐式记忆过期配置不合法");
            }
        }
    }
}
