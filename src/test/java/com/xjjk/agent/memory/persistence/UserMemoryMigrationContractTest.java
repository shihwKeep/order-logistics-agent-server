package com.xjjk.agent.memory.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryMigrationContractTest {

    @Test
    void createsOwnerScopedVersionedMemoryTables() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V10__create_user_memory_foundation.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
            assertThat(sql)
                    .contains("CREATE TABLE agent_user_memory_setting")
                    .contains("CREATE TABLE agent_user_memory")
                    .contains("CREATE TABLE agent_memory_suppression")
                    .contains("CREATE TABLE agent_memory_outbox")
                    .contains("memory_generation BIGINT NOT NULL")
                    .contains("source_conversation_id CHAR(36)\n        CHARACTER SET ascii COLLATE ascii_bin NULL")
                    .contains("source_message_sequence BIGINT NULL")
                    .contains("UNIQUE KEY uk_memory_id (memory_id)")
                    .contains("KEY idx_memory_owner_status")
                    .contains("UNIQUE KEY uk_memory_outbox_event (event_id)");
        }
    }
}
