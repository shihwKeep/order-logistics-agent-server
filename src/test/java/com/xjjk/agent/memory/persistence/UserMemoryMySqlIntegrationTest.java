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
                .target(MigrationVersion.fromVersion("14"))
                .load()
                .migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_user_memory (
                        memory_id, tenant_id, user_id, memory_generation, source_type, category,
                        schema_version, memory_type, predicate_name, value_json, stability,
                        verification_method, canonical_key, content, content_hash, confidence,
                        visibility, retention_type, status, source_conversation_id,
                        source_message_sequence, evidence_text, version, expires_at,
                        created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000101', 1, 2, 7, 'USER_EXPLICIT',
                        'WORK_COMMON_SCOPE', 2, 'WORK_CONTEXT', 'primary_programming_language',
                        JSON_OBJECT('value', 'Java'), 'STABLE', 'DETERMINISTIC',
                        'work.primary_programming_language', '用户主要使用 Java 进行开发',
                        REPEAT('c', 64), 0.9600, 'VISIBLE', 'PERMANENT', 'ACTIVE', NULL, NULL,
                        '我平时用 Java 语言进行开发', 1, NULL,
                        '2026-09-01 01:02:03.456', '2026-09-01 01:02:03.456'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_user_memory (
                        memory_id, tenant_id, user_id, memory_generation, source_type, category,
                        schema_version, memory_type, predicate_name, value_json, stability,
                        verification_method, canonical_key, content, content_hash, confidence,
                        visibility, retention_type, status, source_conversation_id,
                        source_message_sequence, evidence_text, version, expires_at,
                        created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000103', 1, 2, 7, 'AUTO_EXTRACT',
                        'WORK_COMMON_SCOPE', 2, NULL, 'incomplete_v2_fact',
                        JSON_OBJECT('value', '保留'), 'STABLE', 'DETERMINISTIC',
                        'work.incomplete_v2_fact', '需要保留的残缺 v2 事实内容',
                        REPEAT('f', 64), 0.8800, 'HIDDEN', 'NORMAL', 'ACTIVE', NULL, NULL,
                        '需要保留的残缺 v2 事实证据', 1, NULL,
                        '2026-09-01 03:04:05.678', '2026-09-01 03:04:05.678'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_user_memory (
                        memory_id, tenant_id, user_id, memory_generation, source_type, category,
                        canonical_key, content, content_hash, confidence, visibility, retention_type,
                        status, source_conversation_id, source_message_sequence, evidence_text,
                        version, expires_at, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000102', 1, 2, 7, 'USER_EXPLICIT',
                        'PREFERENCE_ANSWER_STYLE', 'preference.answer_style', '用户偏好简洁回答',
                        REPEAT('d', 64), 1.0000, 'VISIBLE', 'PERMANENT', 'ACTIVE', NULL, NULL,
                        '用户偏好简洁回答', 1, NULL,
                        '2026-09-01 02:03:04.567', '2026-09-01 02:03:04.567'
                    )
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备V14升级数据", exception);
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
    void upgradesStructuredFactsAndKeepsLegacyRowsCompatible() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "information_schema.columns",
                    "table_schema = DATABASE() AND table_name = 'agent_user_memory'"
                            + " AND column_name IN ('observed_at', 'valid_from', 'valid_to',"
                            + " 'temporal_scope')"))
                    .isEqualTo(4);

            try (ResultSet result = statement.executeQuery("""
                    SELECT schema_version, observed_at, valid_from, valid_to,
                           temporal_scope, created_at
                    FROM agent_user_memory
                    WHERE memory_id = '00000000-0000-0000-0000-000000000101'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("schema_version")).isEqualTo(3);
                assertThat(result.getTimestamp("observed_at"))
                        .isEqualTo(result.getTimestamp("created_at"));
                assertThat(result.getTimestamp("valid_from"))
                        .isEqualTo(result.getTimestamp("created_at"));
                assertThat(result.getTimestamp("valid_to")).isNull();
                assertThat(result.getString("temporal_scope")).isEqualTo("CURRENT");
            }

            try (ResultSet result = statement.executeQuery("""
                    SELECT schema_version, observed_at, valid_from, valid_to, temporal_scope
                    FROM agent_user_memory
                    WHERE memory_id = '00000000-0000-0000-0000-000000000102'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("schema_version")).isNull();
                assertThat(result.getTimestamp("observed_at")).isNull();
                assertThat(result.getTimestamp("valid_from")).isNull();
                assertThat(result.getTimestamp("valid_to")).isNull();
                assertThat(result.getString("temporal_scope")).isNull();
            }

            try (ResultSet result = statement.executeQuery("""
                    SELECT schema_version, memory_type, predicate_name, value_json, stability,
                           verification_method, observed_at, valid_from, valid_to, temporal_scope,
                           category, content, source_type, status
                    FROM agent_user_memory
                    WHERE memory_id = '00000000-0000-0000-0000-000000000103'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("schema_version")).isNull();
                assertThat(result.getString("memory_type")).isNull();
                assertThat(result.getString("predicate_name")).isNull();
                assertThat(result.getString("value_json")).isNull();
                assertThat(result.getString("stability")).isNull();
                assertThat(result.getString("verification_method")).isNull();
                assertThat(result.getTimestamp("observed_at")).isNull();
                assertThat(result.getTimestamp("valid_from")).isNull();
                assertThat(result.getTimestamp("valid_to")).isNull();
                assertThat(result.getString("temporal_scope")).isNull();
                assertThat(result.getString("category")).isEqualTo("WORK_COMMON_SCOPE");
                assertThat(result.getString("content"))
                        .isEqualTo("需要保留的残缺 v2 事实内容");
                assertThat(result.getString("source_type")).isEqualTo("AUTO_EXTRACT");
                assertThat(result.getString("status")).isEqualTo("ACTIVE");
            }
        }
    }

    @Test
    void acceptsSupportedTemporalFactEnums() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            insertTemporalFact(statement, "00000000-0000-0000-0000-000000000111",
                    "'WORK_CONTEXT'", "'STABLE'", "'DETERMINISTIC'", "'CURRENT'");
            insertTemporalFact(statement, "00000000-0000-0000-0000-000000000112",
                    "'WORK_CONTEXT'", "'TIME_BOUND'", "'SEMANTIC_MODEL'", "'HISTORICAL'");

            assertThat(count(statement, "agent_user_memory",
                    "memory_id IN ('00000000-0000-0000-0000-000000000111',"
                            + " '00000000-0000-0000-0000-000000000112')"))
                    .isEqualTo(2);
        }
    }

    @Test
    void acceptsEqualValidityBoundaryAndRejectsInvertedRange() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            insertTemporalFact(statement, "00000000-0000-0000-0000-000000000113",
                    "'WORK_CONTEXT'", "'TIME_BOUND'", "'SEMANTIC_MODEL'", "'HISTORICAL'",
                    "'2026-09-02 01:02:03.456'");

            assertThat(count(statement, "agent_user_memory",
                    "memory_id = '00000000-0000-0000-0000-000000000113'"))
                    .isEqualTo(1);
            assertThatThrownBy(() -> insertTemporalFact(
                    statement, "00000000-0000-0000-0000-000000000129",
                    "'WORK_CONTEXT'", "'TIME_BOUND'", "'SEMANTIC_MODEL'", "'HISTORICAL'",
                    "'2026-09-02 01:02:03.455'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void allowsUnknownHistoricalStartButRequiresCurrentStart() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            insertTemporalFact(statement, "00000000-0000-0000-0000-000000000130",
                    "'WORK_CONTEXT'", "'TIME_BOUND'", "'SEMANTIC_MODEL'", "'HISTORICAL'",
                    "NULL", "NULL");
            assertThat(count(statement, "agent_user_memory",
                    "memory_id = '00000000-0000-0000-0000-000000000130'"))
                    .isEqualTo(1);

            assertThatThrownBy(() -> insertTemporalFact(
                    statement, "00000000-0000-0000-0000-000000000131",
                    "'WORK_CONTEXT'", "'TIME_BOUND'", "'SEMANTIC_MODEL'", "'CURRENT'",
                    "NULL", "NULL"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void rejectsNullAndUnsupportedTemporalFactEnums() throws Exception {
        String[][] rejectedValues = {
                {"00000000-0000-0000-0000-000000000121", "NULL", "'STABLE'", "'DETERMINISTIC'", "'CURRENT'"},
                {"00000000-0000-0000-0000-000000000122", "'WORK_CONTEXT'", "NULL", "'DETERMINISTIC'", "'CURRENT'"},
                {"00000000-0000-0000-0000-000000000123", "'WORK_CONTEXT'", "'STABLE'", "NULL", "'CURRENT'"},
                {"00000000-0000-0000-0000-000000000124", "'WORK_CONTEXT'", "'STABLE'", "'DETERMINISTIC'", "NULL"},
                {"00000000-0000-0000-0000-000000000125", "'UNSUPPORTED'", "'STABLE'", "'DETERMINISTIC'", "'CURRENT'"},
                {"00000000-0000-0000-0000-000000000126", "'WORK_CONTEXT'", "'UNSUPPORTED'", "'DETERMINISTIC'", "'CURRENT'"},
                {"00000000-0000-0000-0000-000000000127", "'WORK_CONTEXT'", "'STABLE'", "'UNSUPPORTED'", "'CURRENT'"},
                {"00000000-0000-0000-0000-000000000128", "'WORK_CONTEXT'", "'STABLE'", "'DETERMINISTIC'", "'UNSUPPORTED'"}
        };

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String[] values : rejectedValues) {
                assertThatThrownBy(() -> insertTemporalFact(
                        statement, values[0], values[1], values[2], values[3], values[4]))
                        .isInstanceOf(SQLException.class);
            }
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

    private static void insertTemporalFact(
            Statement statement,
            String memoryId,
            String memoryType,
            String stability,
            String verificationMethod,
            String temporalScope) throws SQLException {
        insertTemporalFact(statement, memoryId, memoryType, stability,
                verificationMethod, temporalScope, "NULL");
    }

    private static void insertTemporalFact(
            Statement statement,
            String memoryId,
            String memoryType,
            String stability,
            String verificationMethod,
            String temporalScope,
            String validTo) throws SQLException {
        insertTemporalFact(statement, memoryId, memoryType, stability,
                verificationMethod, temporalScope,
                "'2026-09-02 01:02:03.456'", validTo);
    }

    private static void insertTemporalFact(
            Statement statement,
            String memoryId,
            String memoryType,
            String stability,
            String verificationMethod,
            String temporalScope,
            String validFrom,
            String validTo) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO agent_user_memory (
                    memory_id, tenant_id, user_id, memory_generation, source_type, category,
                    schema_version, memory_type, predicate_name, value_json, stability,
                    observed_at, valid_from, valid_to, temporal_scope, verification_method,
                    canonical_key, content, content_hash, confidence, visibility, retention_type,
                    status, source_conversation_id, source_message_sequence, evidence_text,
                    version, expires_at, created_at, updated_at
                ) VALUES (
                    '%s', 1, 2, 7, 'USER_EXPLICIT', 'WORK_COMMON_SCOPE',
                    3, %s, 'primary_programming_language', JSON_OBJECT('value', 'Java'), %s,
                    '2026-09-02 01:02:03.456', %s, %s, %s, %s,
                    'work.primary_programming_language', '用户主要使用 Java 进行开发',
                    REPEAT('e', 64), 0.9600, 'VISIBLE', 'PERMANENT', 'ACTIVE', NULL, NULL,
                    '我平时用 Java 语言进行开发', 1, NULL,
                    '2026-09-02 01:02:03.456', '2026-09-02 01:02:03.456'
                )
                """.formatted(memoryId, memoryType, stability, validFrom, validTo,
                        temporalScope, verificationMethod));
    }

    private static long count(Statement statement, String table, String predicate) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getLong(1);
        }
    }
}
