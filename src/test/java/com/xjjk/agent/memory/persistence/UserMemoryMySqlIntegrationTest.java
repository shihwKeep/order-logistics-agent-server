package com.xjjk.agent.memory.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

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
                .load()
                .migrate();
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
