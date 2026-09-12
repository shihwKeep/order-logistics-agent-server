package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryOutboxClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class MemoryIndexOutboxPoller {
    private final MemoryIndexOutboxStateService state;
    private final MemoryIndexOutboxWorker worker;
    private final ThreadPoolTaskExecutor executor;
    private final UserMemoryProperties memoryProperties;

    public MemoryIndexOutboxPoller(
            MemoryIndexOutboxStateService state,
            MemoryIndexOutboxWorker worker,
            @Qualifier("memoryIndexWorkerExecutor") ThreadPoolTaskExecutor executor,
            UserMemoryProperties memoryProperties) {
        this.state = state;
        this.worker = worker;
        this.executor = executor;
        this.memoryProperties = memoryProperties;
    }

    @Scheduled(fixedDelayString = "${agent.memory.index-worker.poll-interval}")
    public void poll() {
        if (!memoryProperties.enabled()) {
            return;
        }
        List<MemoryOutboxClaim> claims = state.claimAvailable();
        for (MemoryOutboxClaim claim : claims) {
            try {
                executor.execute(() -> worker.process(claim));
            } catch (TaskRejectedException rejected) {
                log.warn("memory_index_executor_rejected eventId={}", claim.eventId());
                state.scheduleRetry(claim, "EXECUTOR_REJECTED");
            }
        }
    }
}
