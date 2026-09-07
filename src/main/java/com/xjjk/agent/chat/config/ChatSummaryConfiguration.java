package com.xjjk.agent.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 会话长期摘要基础设施配置。
 *
 * 摘要任务使用独立有界线程池，避免模型摘要积压占用 SSE 或 Redis 预热线程。
 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class ChatSummaryConfiguration {

    public ChatSummaryConfiguration(
            ChatSummaryProperties summaryProperties,
            ChatContextProperties contextProperties
    ) {
        validateContextBudget(summaryProperties, contextProperties);
    }

    @Bean("chatSummaryExecutor")
    public ThreadPoolTaskExecutor chatSummaryExecutor(
            ChatSummaryProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.worker().corePoolSize());
        executor.setMaxPoolSize(properties.worker().maxPoolSize());
        executor.setQueueCapacity(properties.worker().queueCapacity());
        executor.setThreadNamePrefix("chat-summary-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 长期摘要和近期原文共享应用输入预算，不能分别按全额预算配置。
     */
    static void validateContextBudget(
            ChatSummaryProperties summary,
            ChatContextProperties context
    ) {
        long usableInputTokens = (long) context.maxInputTokens()
                - context.safetyMarginTokens();
        long reservedMemoryTokens = Math.addExact(
                summary.rawTailMaxTokens(),
                summary.contextMaxTokens()
        );
        if (summary.rawTailMaxTokens() >= usableInputTokens
                || reservedMemoryTokens > usableInputTokens) {
            throw new IllegalArgumentException("摘要和近期原文预算超过应用可用输入预算");
        }
    }
}
