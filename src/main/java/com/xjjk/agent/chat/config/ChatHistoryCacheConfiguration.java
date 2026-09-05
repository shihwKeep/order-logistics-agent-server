package com.xjjk.agent.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 会话历史缓存基础设施配置。
 */
@Configuration(proxyBeanMethods = false)
public class ChatHistoryCacheConfiguration {

    @Bean("chatHistoryCacheExecutor")
    public ThreadPoolTaskExecutor chatHistoryCacheExecutor(
            ChatHistoryCacheProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.warmCorePoolSize());
        executor.setMaxPoolSize(properties.warmMaxPoolSize());
        executor.setQueueCapacity(properties.warmQueueCapacity());
        executor.setThreadNamePrefix("chat-history-warm-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
