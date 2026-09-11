package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期清理已到期的隐藏自动记忆。 */
@Slf4j
@Component
public class UserMemoryExpiryScheduler {

    private final UserMemoryExpiryService expiryService;
    private final UserMemoryProperties memoryProperties;
    private final ImplicitMemoryProperties implicitProperties;
    private final UserMemoryMetrics metrics;

    public UserMemoryExpiryScheduler(
            UserMemoryExpiryService expiryService,
            UserMemoryProperties memoryProperties,
            ImplicitMemoryProperties implicitProperties,
            UserMemoryMetrics metrics
    ) {
        this.expiryService = expiryService;
        this.memoryProperties = memoryProperties;
        this.implicitProperties = implicitProperties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${agent.memory.auto-extract.expiry.poll-interval}")
    public void expire() {
        if (!memoryProperties.enabled()) {
            return;
        }
        int expired = expiryService.expireBatch(implicitProperties.expiry().batchSize());
        metrics.success("expiry", expired);
        if (expired > 0) {
            log.info("implicit_memory_expiry expired={}", expired);
        }
    }
}
