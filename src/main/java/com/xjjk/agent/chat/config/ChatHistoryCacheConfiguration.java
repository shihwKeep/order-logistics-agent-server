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
        // 预热使用独立线程池，Redis/MySQL 短暂变慢时不会占满聊天模型调用线程。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.warmCorePoolSize());
        executor.setMaxPoolSize(properties.warmMaxPoolSize());
        // 有界队列提供背压；积压超过容量时宁可放弃预热，也不能无限占用 JVM 内存。
        executor.setQueueCapacity(properties.warmQueueCapacity());
        executor.setThreadNamePrefix("chat-history-warm-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // 关闭应用时不因非关键缓存任务长时间阻塞，MySQL 中的业务结果不会受影响。
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
