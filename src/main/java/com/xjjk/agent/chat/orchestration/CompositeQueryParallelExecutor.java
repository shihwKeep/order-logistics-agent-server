package com.xjjk.agent.chat.orchestration;

import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 复合查询专用有界线程池，避免并行分支无界创建线程。 */
@Component
public final class CompositeQueryParallelExecutor {

    private final ThreadPoolExecutor executor;

    public CompositeQueryParallelExecutor(CompositeQueryParallelProperties properties) {
        this.executor = new ThreadPoolExecutor(
                properties.corePoolSize(), properties.maxPoolSize(),
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "composite-query");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public Executor executor() {
        return executor;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
