package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 会话长期摘要运行配置，配置值统一由 Nacos 提供。
 *
 * 构造阶段完成组合校验，使阈值、模型输出和任务租约配置错误在启动时暴露。
 */
@Validated
@ConfigurationProperties(prefix = "agent.chat.summary")
public record ChatSummaryProperties(
        boolean enabled,
        boolean shadowMode,
        boolean contextEnabled,
        int schemaVersion,
        long triggerTokens,
        int triggerTurns,
        int retainRecentTurns,
        long rawTailMaxTokens,
        int maxBatchMessages,
        long maxBatchBytes,
        long maxBatchTokens,
        long targetOutputTokens,
        long maxOutputTokens,
        long contextMaxTokens,
        String promptVersion,
        String model,
        double temperature,
        Duration timeout,
        Worker worker,
        Retry retry
) {

    public ChatSummaryProperties {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("当前仅支持摘要结构版本 1");
        }
        if (triggerTokens <= 0 || triggerTurns <= 0
                || retainRecentTurns < 1 || rawTailMaxTokens <= 0
                || maxBatchMessages < 2 || maxBatchBytes <= 0
                || maxBatchTokens <= 0 || targetOutputTokens <= 0
                || maxOutputTokens <= 0 || contextMaxTokens <= 0) {
            throw new IllegalArgumentException("摘要阈值和预算必须为正数");
        }
        if (targetOutputTokens > maxOutputTokens) {
            throw new IllegalArgumentException("摘要目标输出不能超过最大输出 Token");
        }
        if (contextMaxTokens > rawTailMaxTokens) {
            throw new IllegalArgumentException("摘要上下文预算不能超过近期原文预算");
        }
        if (!StringUtils.hasText(promptVersion)
                || promptVersion.length() > 64
                || !StringUtils.hasText(model)
                || model.length() > 128) {
            throw new IllegalArgumentException("摘要提示词版本或模型名称不合法");
        }
        if (!Double.isFinite(temperature)
                || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("摘要模型 temperature 不合法");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("摘要模型超时时间必须大于零");
        }
        if (worker == null || retry == null) {
            throw new IllegalArgumentException("摘要 Worker 和重试配置不能为空");
        }
        // 一次任务最多包含首次生成和一次格式纠偏，两次调用各自受 timeout 限制；
        // 额外五秒留给候选读取、JSON 校验和数据库 CAS 提交。
        Duration minimumLease = timeout.multipliedBy(2).plusSeconds(5);
        if (worker.leaseDuration().compareTo(minimumLease) < 0) {
            throw new IllegalArgumentException(
                    "任务租约不足以覆盖两次模型调用和提交余量"
            );
        }
        if (contextEnabled && (!enabled || shadowMode)) {
            throw new IllegalArgumentException("上下文注入只能在正式摘要生成启用后开启");
        }
    }

    /** 摘要任务线程池、轮询和租约配置。 */
    public record Worker(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int claimBatchSize,
            Duration pollInterval,
            Duration recoveryInterval,
            Duration leaseDuration,
            String instanceId
    ) {

        public Worker {
            if (corePoolSize <= 0 || maxPoolSize < corePoolSize
                    || queueCapacity <= 0 || claimBatchSize <= 0) {
                throw new IllegalArgumentException("摘要 Worker 线程池配置不合法");
            }
            if (pollInterval == null || pollInterval.isZero()
                    || pollInterval.isNegative()
                    || recoveryInterval == null
                    || recoveryInterval.isZero()
                    || recoveryInterval.isNegative()
                    || leaseDuration == null || leaseDuration.isZero()
                    || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("摘要轮询和租约时间必须大于零");
            }
            if (!StringUtils.hasText(instanceId)
                    || instanceId.length() > 128) {
                throw new IllegalArgumentException("摘要 Worker 实例标识不合法");
            }
        }
    }

    /** 摘要失败后的有限次指数退避配置。 */
    public record Retry(
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay,
            Duration jitter
    ) {

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("摘要最大尝试次数必须大于零");
            }
            if (initialDelay == null || initialDelay.isZero()
                    || initialDelay.isNegative()
                    || maxDelay == null || maxDelay.isZero()
                    || maxDelay.isNegative()
                    || jitter == null || jitter.isNegative()) {
                throw new IllegalArgumentException("摘要重试时间配置不合法");
            }
            if (initialDelay.compareTo(maxDelay) > 0
                    || jitter.compareTo(maxDelay) > 0) {
                throw new IllegalArgumentException("摘要重试初始延迟或抖动超过最大延迟");
            }
        }
    }
}
