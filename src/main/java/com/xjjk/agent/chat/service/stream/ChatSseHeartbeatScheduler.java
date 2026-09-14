package com.xjjk.agent.chat.service.stream;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 仅供聊天 SSE 心跳使用的调度器。
 *
 * 不实现 Spring 的 TaskScheduler 或 ScheduledExecutorService，避免被
 * {@code @Scheduled} 基础设施选作全局调度器并与其他后台任务串用线程池。
 */
@Component
public class ChatSseHeartbeatScheduler {

    private final ScheduledThreadPoolExecutor executor;

    public ChatSseHeartbeatScheduler() {
        executor = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("chat-sse-heartbeat-", 1)
                        .factory()
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration interval) {
        Objects.requireNonNull(task, "心跳任务不能为空");
        Objects.requireNonNull(interval, "心跳间隔不能为空");
        long intervalMillis = interval.toMillis();
        return executor.scheduleAtFixedRate(
                task,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "延迟任务不能为空");
        Objects.requireNonNull(delay, "延迟时间不能为空");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("延迟时间不能小于零");
        }
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
