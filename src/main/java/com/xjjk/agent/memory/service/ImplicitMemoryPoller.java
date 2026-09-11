package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ImplicitMemoryPoller {

    private final ImplicitMemoryTaskCommitService taskState;
    private final ImplicitMemoryTaskWorker worker;
    private final ThreadPoolTaskExecutor executor;
    private final UserMemoryProperties memoryProperties;
    private final ImplicitMemoryProperties properties;

    public ImplicitMemoryPoller(
            ImplicitMemoryTaskCommitService taskState,
            ImplicitMemoryTaskWorker worker,
            @Qualifier("implicitMemoryWorkerExecutor") ThreadPoolTaskExecutor executor,
            UserMemoryProperties memoryProperties,
            ImplicitMemoryProperties properties
    ) {
        this.taskState = taskState;
        this.worker = worker;
        this.executor = executor;
        this.memoryProperties = memoryProperties;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${agent.memory.auto-extract.worker.poll-interval}")
    public void poll() {
        if (!memoryProperties.enabled()) {
            return;
        }
        List<MemoryExtractionTaskClaim> claims = taskState.claimAvailable(
                properties.worker().claimBatchSize());
        for (MemoryExtractionTaskClaim claim : claims) {
            try {
                executor.execute(() -> worker.process(claim));
            } catch (TaskRejectedException rejected) {
                taskState.scheduleRetry(claim, "EXECUTOR_REJECTED");
            }
        }
    }
}
