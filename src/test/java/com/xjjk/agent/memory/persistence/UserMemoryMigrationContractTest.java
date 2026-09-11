package com.xjjk.agent.memory.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryMigrationContractTest {

    @Test
    void createsOwnerScopedVersionedMemoryTables() throws IOException {
        String sql = readMigration("/db/migration/V10__create_user_memory_foundation.sql");
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

    @Test
    void addsDefaultOnUserMemoryMasterSwitch() throws IOException {
        String sql = readMigration("/db/migration/V11__add_user_memory_master_switch.sql");
        assertThat(sql)
                .contains("ALTER TABLE agent_user_memory_setting")
                .contains("memory_enabled TINYINT(1) NOT NULL DEFAULT 1")
                .contains("AFTER memory_generation");
    }

    @Test
    void createsDurableImplicitMemoryExtractionTasks() throws IOException {
        String sql = readMigration("/db/migration/V12__create_memory_extraction_task.sql");
        assertThat(sql)
                .contains("CREATE TABLE agent_memory_extraction_task")
                .contains("user_message_id CHAR(36)")
                .contains("user_message_sequence BIGINT NOT NULL")
                .contains("memory_generation BIGINT NOT NULL")
                .contains("UNIQUE KEY uk_memory_extraction_request")
                .contains("KEY idx_memory_extraction_claim")
                .contains("KEY idx_memory_extraction_lease")
                .contains("CONSTRAINT chk_memory_extraction_status")
                .doesNotContain("message_content", "candidate_content", "evidence_text");
    }

    @Test
    void addsPrivacySafeImplicitExtractionResultFields() throws IOException {
        String sql = readMigration(
                "/db/migration/V13__add_memory_extraction_observability.sql");
        assertThat(sql)
                .contains("ADD COLUMN result_code VARCHAR(40)")
                .contains("ADD COLUMN model_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0")
                .contains("ADD COLUMN accepted_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0")
                .contains("ADD COLUMN saved_memory_count SMALLINT UNSIGNED NOT NULL DEFAULT 0")
                .contains("CONSTRAINT chk_memory_extraction_result_code")
                .contains("CONSTRAINT chk_memory_extraction_result_counts")
                .doesNotContain("model_output", "candidate_content", "evidence_text");
    }

    private String readMigration(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }
}
