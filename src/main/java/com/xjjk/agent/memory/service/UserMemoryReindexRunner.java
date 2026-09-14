package com.xjjk.agent.memory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "agent.memory.maintenance",
        name = "reindex-on-startup", havingValue = "true")
public class UserMemoryReindexRunner implements ApplicationRunner {
    public static final String CONFIRMATION = "REINDEX_BAILIAN_V2_USER_MEMORY";
    private static final Logger log = LoggerFactory.getLogger(UserMemoryReindexRunner.class);
    private final UserMemoryReindexService service;
    private final String confirmation;
    private final int batchSize;

    public UserMemoryReindexRunner(UserMemoryReindexService service,
            @Value("${agent.memory.maintenance.reindex-confirmation:}") String confirmation,
            @Value("${agent.memory.maintenance.batch-size:500}") int batchSize) {
        this.service = service;
        this.confirmation = confirmation;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("用户记忆重建确认串不匹配");
        }
        UserMemoryReindexResult result = service.enqueueAll(batchSize);
        log.info("user_memory_reindex completed=true scanned={}, enqueued={}",
                result.scanned(), result.enqueued());
    }
}
