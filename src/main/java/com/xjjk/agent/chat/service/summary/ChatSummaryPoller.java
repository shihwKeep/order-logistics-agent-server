package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTaskClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 周期领取持久化摘要任务，并投递到独立有界 Worker 线程池。 */
@Slf4j
@Component
public class ChatSummaryPoller {

    private final ChatSummaryCommitService commitService;
    private final ChatSummaryTaskWorker worker;
    private final ThreadPoolTaskExecutor executor;
    private final ChatSummaryProperties properties;

    public ChatSummaryPoller(
            ChatSummaryCommitService commitService,
            ChatSummaryTaskWorker worker,
            @Qualifier("chatSummaryExecutor")
            ThreadPoolTaskExecutor executor,
            ChatSummaryProperties properties
    ) {
        this.commitService = commitService;
        this.worker = worker;
        this.executor = executor;
        this.properties = properties;
    }

    /** disabled 时不访问数据库，便于通过 Nacos 安全关闭整条异步链。 */
    @Scheduled(fixedDelayString =
            "${agent.chat.summary.worker.poll-interval}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        List<ChatSummaryTaskClaim> claims = commitService.claimAvailable(
                properties.worker().claimBatchSize()
        );
        for (ChatSummaryTaskClaim claim : claims) {
            try {
                executor.execute(() -> worker.process(claim));
            } catch (TaskRejectedException rejected) {
                // 队列已满时立即释放租约，不能让任务一直 PROCESSING 到租约自然过期。
                try {
                    commitService.scheduleRetry(
                            claim,
                            "EXECUTOR_REJECTED"
                    );
                } catch (ChatSummaryStaleWorkException stale) {
                    log.info("chat_summary_dispatch taskId={}, result=STALE",
                            claim.taskId());
                }
            }
        }
    }
}
