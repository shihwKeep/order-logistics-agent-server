package com.xjjk.agent.memory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class MemoryIndexWorkerConfiguration {

    @Bean(name = "memoryIndexWorkerExecutor")
    public ThreadPoolTaskExecutor memoryIndexWorkerExecutor(
            MemoryIndexWorkerProperties properties) {
        MemoryIndexWorkerProperties.Executor settings = properties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.poolSize());
        executor.setMaxPoolSize(settings.poolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix("memory-index-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
