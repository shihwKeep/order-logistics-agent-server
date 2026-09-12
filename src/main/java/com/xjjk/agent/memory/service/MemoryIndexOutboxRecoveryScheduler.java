package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.UserMemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MemoryIndexOutboxRecoveryScheduler {
    private final MemoryIndexOutboxStateService state;
    private final UserMemoryProperties memoryProperties;

    public MemoryIndexOutboxRecoveryScheduler(
            MemoryIndexOutboxStateService state,
            UserMemoryProperties memoryProperties) {
        this.state = state;
        this.memoryProperties = memoryProperties;
    }

    @Scheduled(fixedDelayString = "${agent.memory.index-worker.recovery-interval}")
    public void recover() {
        if (!memoryProperties.enabled()) {
            return;
        }
        int recovered = state.recoverExpiredLeases();
        if (recovered > 0) {
            log.info("memory_index_outbox_recovery recoveredLeases={}", recovered);
        }
    }
}
