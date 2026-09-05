package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 稳定历史变更的提交后监听器。
 *
 * 提交线程只负责投递任务，不执行数据库查询或 Redis 写入。
 */
@Slf4j
@Component
public class ChatHistoryCacheWarmListener {

    private final ThreadPoolTaskExecutor executor;
    private final ChatHistoryCacheWarmService warmService;
    private final ChatHistoryCacheMetrics metrics;

    public ChatHistoryCacheWarmListener(
            @Qualifier("chatHistoryCacheExecutor")
            ThreadPoolTaskExecutor executor,
            ChatHistoryCacheWarmService warmService,
            ChatHistoryCacheMetrics metrics
    ) {
        this.executor = executor;
        this.warmService = warmService;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(ChatHistoryChangedEvent event) {
        try {
            executor.execute(() -> warmService.warm(event));
        } catch (TaskRejectedException exception) {
            metrics.warmRejected();
            log.warn(
                    "chat_history_cache_warm_rejected "
                            + "conversationId={}, memoryVersion={}",
                    event.conversationId(),
                    event.memoryVersion()
            );
        } catch (RuntimeException exception) {
            metrics.warmError();
            log.warn(
                    "chat_history_cache_warm_dispatch_failed "
                            + "conversationId={}, memoryVersion={}, "
                            + "exceptionType={}",
                    event.conversationId(),
                    event.memoryVersion(),
                    exception.getClass().getSimpleName()
            );
        }
    }
}
