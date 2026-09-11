package com.xjjk.agent.memory.persistence;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.service.UserMemoryManagementService;
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
}
