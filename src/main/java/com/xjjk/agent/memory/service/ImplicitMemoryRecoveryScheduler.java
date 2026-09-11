package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.UserMemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ImplicitMemoryRecoveryScheduler {

    private final ImplicitMemoryTaskCommitService taskState;
    private final UserMemoryProperties memoryProperties;

    public ImplicitMemoryRecoveryScheduler(
            ImplicitMemoryTaskCommitService taskState,
            UserMemoryProperties memoryProperties
    ) {
        this.taskState = taskState;
        this.memoryProperties = memoryProperties;
    }

    @Scheduled(fixedDelayString = "${agent.memory.auto-extract.worker.recovery-interval}")
    public void recover() {
        if (!memoryProperties.enabled()) {
            return;
        }
        ImplicitMemoryTaskCommitService.RecoveryResult result = taskState.recover();
        if (result.recoveredLeases() > 0 || result.cancelledTasks() > 0) {
            log.info("implicit_memory_recovery recoveredLeases={}, cancelledTasks={}",
                    result.recoveredLeases(), result.cancelledTasks());
        }
    }
}
