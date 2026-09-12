package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryRuntimeConfigurationContractTest {

    @Test
    void baseConfigurationKeepsMemoryDisabledAndDocumentsEveryWorkerBound() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }

        assertThat(properties.getProperty("agent.memory.enabled")).isEqualTo("false");
        for (String key : List.of(
                "agent.memory.context-max-tokens",
                "agent.memory.index-worker.poll-interval",
                "agent.memory.index-worker.recovery-interval",
                "agent.memory.index-worker.claim-batch-size",
                "agent.memory.index-worker.lease-duration",
                "agent.memory.index-worker.max-attempts",
                "agent.memory.index-worker.initial-backoff",
                "agent.memory.index-worker.max-backoff",
                "agent.memory.index-worker.executor.pool-size",
                "agent.memory.index-worker.executor.queue-capacity",
                "agent.memory.retrieval.max-candidates",
                "agent.memory.retrieval.max-selected",
                "agent.memory.retrieval.global-explicit-limit")) {
            assertThat(properties).as(key).containsKey(key);
        }
    }
}
