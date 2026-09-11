package com.xjjk.agent.memory.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class UserMemoryMySqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agent_memory_test")
            .withUsername("agent")
            .withPassword("agent-test-password");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_conversation (
                        conversation_id, tenant_id, user_id, org_id, title,
                        last_message_sequence, memory_until_sequence, created_at, updated_at
                    ) VALUES (
                        '20000000-0000-0000-0000-000000000001', 1, 2, 3, '已有会话',
                        0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                    )
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备V1-V9升级数据", exception);
        }
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_user_memory_setting (
                        tenant_id, user_id, memory_generation, auto_extract_enabled,
                        created_at, updated_at
                    ) VALUES (1, 2, 7, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备V10升级数据", exception);
        }
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void resetMemorySetting() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE agent_user_memory_setting
                    SET memory_enabled = 1, auto_extract_enabled = 1, memory_generation = 7
                    WHERE tenant_id = 1 AND user_id = 2
                    """);
        }
    }

    @Test
    void upgradesExistingSettingWithMemoryEnabledByDefault() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT memory_enabled
                     FROM agent_user_memory_setting
                     WHERE tenant_id = 1 AND user_id = 2
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean("memory_enabled")).isTrue();
        }
    }

    @Test
    void appliesMigrationAndAllowsTruthfulApiEditProvenance() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_user_memory (
                        memory_id, tenant_id, user_id, memory_generation, source_type, category,
                        canonical_key, content, content_hash, confidence, visibility, retention_type,
                        status, source_conversation_id, source_message_sequence, evidence_text,
                        version, expires_at, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000001', 1, 2, 1, 'USER_EXPLICIT',
                        'PREFERENCE_ANSWER_STYLE', 'preference.answer_style', '用户偏好简洁回答',
                        REPEAT('a', 64), 1.0000, 'VISIBLE', 'PERMANENT', 'ACTIVE', NULL, NULL,
                        '用户偏好简洁回答', 2, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                    )
                    """);
            try (ResultSet result = statement.executeQuery("""
                    SELECT source_conversation_id, source_message_sequence
                    FROM agent_user_memory
                    WHERE memory_id = '00000000-0000-0000-0000-000000000001'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
                assertThat(result.getObject(2)).isNull();
            }
        }
    }

    @Test
    void rollsBackMemoryAndOutboxTogether() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO agent_user_memory (
                            memory_id, tenant_id, user_id, memory_generation, source_type, category,
                            canonical_key, content, content_hash, confidence, visibility, retention_type,
                            status, source_conversation_id, source_message_sequence, evidence_text,
                            version, expires_at, created_at, updated_at
                        ) VALUES (
                            '00000000-0000-0000-0000-000000000002', 1, 2, 1, 'USER_EXPLICIT',
                            'PREFERENCE_LANGUAGE', 'preference.language', '用户偏好中文回答',
                            REPEAT('b', 64), 1.0000, 'VISIBLE', 'NORMAL', 'ACTIVE', NULL, NULL,
                            '用户偏好中文回答', 1, UTC_TIMESTAMP(3) + INTERVAL 365 DAY,
                            UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                        )
                        """);
                statement.executeUpdate("""
                        INSERT INTO agent_memory_outbox (
                            event_id, memory_id, tenant_id, user_id, memory_generation, memory_version,
                            operation, status, retry_count, next_run_at, created_at, updated_at
                        ) VALUES (
                            '10000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000002', 1, 2, 1, 1,
                            'UPSERT', 'PENDING', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                        )
                        """);
            }
            connection.rollback();
        }

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "agent_user_memory",
                    "memory_id = '00000000-0000-0000-0000-000000000002'" )).isZero();
            assertThat(count(statement, "agent_memory_outbox",
                    "event_id = '10000000-0000-0000-0000-000000000002'" )).isZero();
        }
    }

    @Test
    void appliesExtractionTaskMigrationAndRejectsDuplicateOwnedRequest() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_message (
                        message_id, conversation_id, tenant_id, user_id, request_id,
                        message_sequence, role, content, status, created_at, updated_at
                    ) VALUES (
                        '30000000-0000-0000-0000-000000000001',
                        '20000000-0000-0000-0000-000000000001', 1, 2,
                        '40000000-0000-0000-0000-000000000001', 1,
                        'USER', '测试消息', 'SUCCESS', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                    )
                    """);
            String insert = """
                    INSERT INTO agent_memory_extraction_task (
                        task_id, tenant_id, user_id, conversation_id, request_id,
                        user_message_id, user_message_sequence, memory_generation,
                        status, retry_count, next_run_at, created_at, updated_at
                    ) VALUES (
                        '%s', 1, 2, '20000000-0000-0000-0000-000000000001',
                        '40000000-0000-0000-0000-000000000001',
                        '30000000-0000-0000-0000-000000000001', 1, 7,
                        'PENDING', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                    )
                    """;
            statement.executeUpdate(insert.formatted(
                    "50000000-0000-0000-0000-000000000001"));
            assertThatThrownBy(() -> statement.executeUpdate(insert.formatted(
                    "50000000-0000-0000-0000-000000000002")))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void cancelsInvalidUnclaimedExtractionTasksWithBoundedMySqlUpdate() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE agent_user_memory_setting
                    SET memory_enabled = 0
                    WHERE tenant_id = 1 AND user_id = 2
                    """);
            statement.executeUpdate("""
                    INSERT IGNORE INTO agent_message (
                        message_id, conversation_id, tenant_id, user_id, request_id,
                        message_sequence, role, content, status, created_at, updated_at
                    ) VALUES (
                        '30000000-0000-0000-0000-000000000003',
                        '20000000-0000-0000-0000-000000000001', 1, 2,
                        '40000000-0000-0000-0000-000000000003', 3,
                        'USER', '待取消消息', 'SUCCESS', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_memory_extraction_task (
                        task_id, tenant_id, user_id, conversation_id, request_id,
                        user_message_id, user_message_sequence, memory_generation,
                        status, retry_count, next_run_at, created_at, updated_at
                    ) VALUES (
                        '50000000-0000-0000-0000-000000000003', 1, 2,
                        '20000000-0000-0000-0000-000000000001',
                        '40000000-0000-0000-0000-000000000003',
                        '30000000-0000-0000-0000-000000000003', 3, 7,
                        'PENDING', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                    )
                    """);

            int updated = statement.executeUpdate("""
                    UPDATE agent_memory_extraction_task t
                    SET t.status = 'CANCELLED', t.last_error_code = 'SETTING_DISABLED',
                        t.updated_at = UTC_TIMESTAMP(3)
                    WHERE t.id IN (
                        SELECT id FROM (
                            SELECT candidate.id
                            FROM agent_memory_extraction_task candidate
                            LEFT JOIN agent_user_memory_setting setting
                              ON setting.tenant_id = candidate.tenant_id
                             AND setting.user_id = candidate.user_id
                            WHERE candidate.status IN ('PENDING', 'RETRY')
                              AND (setting.id IS NULL OR setting.memory_enabled = 0
                                   OR setting.auto_extract_enabled = 0
                                   OR setting.memory_generation <> candidate.memory_generation)
                            ORDER BY candidate.id
                            LIMIT 20
                        ) invalid_tasks
                    )
                    """);

            assertThat(updated).isGreaterThanOrEqualTo(1);
            assertThat(count(statement, "agent_memory_extraction_task",
                    "task_id = '50000000-0000-0000-0000-000000000003'"
                            + " AND status = 'CANCELLED' AND last_error_code = 'SETTING_DISABLED'"))
                    .isEqualTo(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long count(Statement statement, String table, String predicate) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getLong(1);
        }
    }
}
