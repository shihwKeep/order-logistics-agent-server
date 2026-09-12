package com.xjjk.agent.memory.persistence;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ImplicitMemoryExtractionBatch;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.domain.MemoryOutboxClaim;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.service.UserMemoryManagementService;
import com.xjjk.agent.memory.service.ImplicitMemoryCommitService;
import com.xjjk.agent.memory.service.UserMemoryExpiryService;
import com.xjjk.agent.memory.service.MemoryIndexOutboxStateService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "integration.customer.base-url=http://127.0.0.1:1",
        "integration.customer.internal-token=test-internal-token"
})
@Testcontainers(disabledWithoutDocker = true)
class UserMemorySpringTransactionIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agent_memory_tx_test")
            .withUsername("agent")
            .withPassword("agent-test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private UserMemoryManagementService service;

    @Autowired
    private ImplicitMemoryCommitService implicitMemoryCommitService;

    @Autowired
    private UserMemoryExpiryService expiryService;

    @Autowired
    private MemoryIndexOutboxStateService outboxState;

    @Autowired
    private UserMemoryMapper memoryMapper;

    @Autowired
    private MemorySuppressionMapper suppressionMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private MemoryOutboxMapper outboxMapper;

    @BeforeEach
    void seedOwnedMemory() {
        jdbc.update("DELETE FROM agent_memory_outbox");
        jdbc.update("DELETE FROM agent_memory_suppression");
        jdbc.update("DELETE FROM agent_user_memory");
        jdbc.update("DELETE FROM agent_memory_extraction_task");
        jdbc.update("DELETE FROM agent_message");
        jdbc.update("DELETE FROM agent_conversation");
        jdbc.update("DELETE FROM agent_user_memory_setting");
        jdbc.update("""
                INSERT INTO agent_user_memory_setting (
                    tenant_id, user_id, memory_generation, auto_extract_enabled, created_at, updated_at
                ) VALUES (1, 2, 7, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO agent_user_memory (
                    memory_id, tenant_id, user_id, memory_generation, source_type, category,
                    canonical_key, content, content_hash, confidence, visibility, retention_type,
                    status, source_conversation_id, source_message_sequence, evidence_text,
                    version, expires_at, created_at, updated_at
                ) VALUES (
                    '00000000-0000-0000-0000-000000000010', 1, 2, 7, 'USER_EXPLICIT',
                    'PREFERENCE_ANSWER_STYLE', 'preference.answer_style', '用户偏好简洁回答',
                    REPEAT('c', 64), 1.0000, 'VISIBLE', 'NORMAL', 'ACTIVE', NULL, NULL,
                    '用户偏好简洁回答', 1, UTC_TIMESTAMP(3) + INTERVAL 365 DAY,
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);
    }

    @Test
    void commitsHiddenAutomaticMemoryAndOutboxWithRealSpringTransaction() {
        seedProcessingTask();
        MemoryExtractionTaskClaim claim = new MemoryExtractionTaskClaim(
                taskRowId(), "50000000-0000-0000-0000-000000000010",
                1L, 2L, "20000000-0000-0000-0000-000000000010",
                "40000000-0000-0000-0000-000000000010",
                "30000000-0000-0000-0000-000000000010", 1L, 7L, 0,
                "60000000-0000-0000-0000-000000000010", "integration-node",
                LocalDateTime.now().plusMinutes(1));

        int saved = implicitMemoryCommitService.commit(claim,
                ImplicitMemoryExtractionBatch.observed(1, List.of(
                        new ImplicitMemoryCandidate(
                        MemoryCategory.PREFERENCE_LANGUAGE,
                        "preference.language", "用户偏好中文回答",
                        "希望使用中文回答", 0.95))));

        assertThat(saved).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_user_memory
                WHERE tenant_id = 1 AND user_id = 2 AND memory_generation = 7
                  AND source_type = 'AUTO_EXTRACT' AND visibility = 'HIDDEN'
                  AND retention_type = 'NORMAL' AND status = 'ACTIVE'
                """, Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_memory_outbox
                WHERE tenant_id = 1 AND user_id = 2 AND memory_generation = 7
                  AND operation = 'UPSERT' AND status = 'PENDING'
                """, Long.class)).isEqualTo(1);
        Map<String, Object> task = jdbc.queryForMap("""
                SELECT status, result_code, model_candidate_count,
                       accepted_candidate_count, saved_memory_count
                FROM agent_memory_extraction_task
                WHERE task_id = '50000000-0000-0000-0000-000000000010'
                """);
        assertThat(task.get("status")).isEqualTo("DONE");
        assertThat(task.get("result_code")).isEqualTo("SAVED");
        assertThat(((Number) task.get("model_candidate_count")).intValue()).isEqualTo(1);
        assertThat(((Number) task.get("accepted_candidate_count")).intValue()).isEqualTo(1);
        assertThat(((Number) task.get("saved_memory_count")).intValue()).isEqualTo(1);
    }

    @Test
    void expiresOnlyHiddenAutomaticMemoryAndWritesDeleteOutboxAtomically() {
        jdbc.update("DELETE FROM agent_user_memory");
        jdbc.update("""
                INSERT INTO agent_user_memory (
                    memory_id, tenant_id, user_id, memory_generation, source_type, category,
                    canonical_key, content, content_hash, confidence, visibility, retention_type,
                    status, source_conversation_id, source_message_sequence, evidence_text,
                    version, expires_at, created_at, updated_at
                ) VALUES (
                    '00000000-0000-0000-0000-000000000020', 1, 2, 7, 'AUTO_EXTRACT',
                    'PREFERENCE_LANGUAGE', 'preference.language', '用户偏好中文回答',
                    REPEAT('d', 64), 0.9500, 'HIDDEN', 'NORMAL', 'ACTIVE', NULL, NULL,
                    '偏好中文', 1, UTC_TIMESTAMP(3) - INTERVAL 1 SECOND,
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);

        assertThat(expiryService.expireBatch(100)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT status FROM agent_user_memory
                WHERE memory_id = '00000000-0000-0000-0000-000000000020'
                """, String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_memory_outbox
                WHERE memory_id = '00000000-0000-0000-0000-000000000020'
                  AND operation = 'DELETE' AND status = 'PENDING'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackTheRealSpringMyBatisServiceTransaction() {
        doReturn(0).when(outboxMapper).insert(any(MemoryOutboxEntity.class));

        assertThatThrownBy(() -> service.edit(
                new AgentIdentity(2L, "account", "name", 3L, 1L),
                "00000000-0000-0000-0000-000000000010",
                "用户偏好详细回答",
                MemoryRetentionType.PERMANENT))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MEMORY_WRITE_FAILED));

        assertThat(count("status = 'ACTIVE' AND version = 1")).isEqualTo(1);
        assertThat(count("version = 2")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_outbox", Long.class)).isZero();
    }

    @Test
    void claimsAndCompletesOutboxWithRealMySqlLeaseCas() {
        jdbc.update("""
                INSERT INTO agent_memory_outbox (
                    event_id, memory_id, tenant_id, user_id, memory_generation, memory_version,
                    operation, status, retry_count, next_run_at, created_at, updated_at
                ) VALUES (
                    '10000000-0000-0000-0000-000000000099',
                    '00000000-0000-0000-0000-000000000010', 1, 2, 7, 1,
                    'UPSERT', 'PENDING', 0,
                    UTC_TIMESTAMP(3) - INTERVAL 1 SECOND, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);

        MemoryOutboxClaim claim = outboxState.claimAvailable().getFirst();

        assertThat(claim.eventId()).isEqualTo("10000000-0000-0000-0000-000000000099");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM agent_memory_outbox WHERE event_id = ?
                """, String.class, claim.eventId())).isEqualTo("PROCESSING");

        outboxState.complete(claim);

        assertThat(jdbc.queryForObject("""
                SELECT status FROM agent_memory_outbox WHERE event_id = ?
                """, String.class, claim.eventId())).isEqualTo("DONE");
    }

    @Test
    void recallQueriesAreOwnerScopedAndExecutableOnRealMySql() {
        LocalDateTime now = LocalDateTime.now();

        assertThat(memoryMapper.selectGlobalExplicit(1L, 2L, 7L, now, 3))
                .extracting(com.xjjk.agent.memory.persistence.entity.UserMemoryEntity::getMemoryId)
                .containsExactly("00000000-0000-0000-0000-000000000010");
        assertThat(memoryMapper.selectActiveCandidates(
                1L, 2L, 7L,
                List.of("00000000-0000-0000-0000-000000000010"), now))
                .hasSize(1);
        assertThat(memoryMapper.selectActiveCandidates(
                1L, 99L, 7L,
                List.of("00000000-0000-0000-0000-000000000010"), now))
                .isEmpty();

        jdbc.update("""
                INSERT INTO agent_memory_suppression (
                    suppression_id, tenant_id, user_id, memory_generation,
                    canonical_key, content_hash, status, created_at, updated_at
                ) VALUES (
                    '70000000-0000-0000-0000-000000000099', 1, 2, 7,
                    'preference.answer_style', REPEAT('f', 64), 'ACTIVE',
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);
        assertThat(suppressionMapper.selectActiveKeys(
                1L, 2L, 7L, List.of("preference.answer_style"), 20))
                .containsExactly("preference.answer_style");
    }

    @Test
    void outerRollbackDoesNotRecordSuccessAndUsesClearFailureCode() {
        double successBefore = operationCount("clear_explicit", "success", "NONE");
        double failureBefore = operationCount("clear_explicit", "failure", "MEMORY_CLEAR_FAILED");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.clearExplicit(new AgentIdentity(2L, "account", "name", 3L, 1L));
            throw new IllegalStateException("force outer rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(operationCount("clear_explicit", "success", "NONE") - successBefore).isZero();
        assertThat(operationCount("clear_explicit", "failure", "MEMORY_CLEAR_FAILED") - failureBefore)
                .isEqualTo(1.0);
        assertThat(count("status = 'ACTIVE' AND version = 1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_suppression", Long.class)).isZero();
    }

    private double operationCount(String operation, String outcome, String code) {
        var counter = meterRegistry.find("agent.user.memory.operation")
                .tags("operation", operation, "outcome", outcome, "code", code)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long count(String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_user_memory WHERE " + predicate, Long.class);
    }

    private void seedProcessingTask() {
        jdbc.update("""
                INSERT INTO agent_conversation (
                    conversation_id, tenant_id, user_id, org_id, title,
                    last_message_sequence, memory_until_sequence, created_at, updated_at
                ) VALUES (
                    '20000000-0000-0000-0000-000000000010', 1, 2, 3, '集成测试',
                    1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);
        jdbc.update("""
                INSERT INTO agent_message (
                    message_id, conversation_id, tenant_id, user_id, request_id,
                    message_sequence, role, content, status, created_at, updated_at
                ) VALUES (
                    '30000000-0000-0000-0000-000000000010',
                    '20000000-0000-0000-0000-000000000010', 1, 2,
                    '40000000-0000-0000-0000-000000000010', 1,
                    'USER', '我是 Java 开发，希望回答简洁一些', 'SUCCESS',
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);
        jdbc.update("""
                INSERT INTO agent_memory_extraction_task (
                    task_id, tenant_id, user_id, conversation_id, request_id,
                    user_message_id, user_message_sequence, memory_generation,
                    status, retry_count, next_run_at, lease_token, locked_by, locked_until,
                    created_at, updated_at
                ) VALUES (
                    '50000000-0000-0000-0000-000000000010', 1, 2,
                    '20000000-0000-0000-0000-000000000010',
                    '40000000-0000-0000-0000-000000000010',
                    '30000000-0000-0000-0000-000000000010', 1, 7,
                    'PROCESSING', 0, UTC_TIMESTAMP(3),
                    '60000000-0000-0000-0000-000000000010', 'integration-node',
                    UTC_TIMESTAMP(3) + INTERVAL 1 MINUTE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
                )
                """);
    }

    private long taskRowId() {
        return jdbc.queryForObject("""
                SELECT id FROM agent_memory_extraction_task
                WHERE task_id = '50000000-0000-0000-0000-000000000010'
                """, Long.class);
    }
}
