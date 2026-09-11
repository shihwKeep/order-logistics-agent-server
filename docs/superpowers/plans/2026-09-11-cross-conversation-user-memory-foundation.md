# Cross-Conversation User Memory Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在 Agent Server 中交付可独立验证的用户记忆权威存储、显式记忆命令、用户管理 API、删除抑制与索引 Outbox，为隐式抽取和 ES/Milvus 召回提供稳定契约。

**Architecture:** MySQL 是唯一权威数据源；显式记忆由聊天中的明确命令同步写入，记忆变更与 Outbox 在同一事务提交。所有读写从 AgentIdentity 获取租户和用户，前端不能指定归属；删除先在 MySQL 生效并创建抑制记录，索引同步由独立 Knowledge Service 计划实现。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AI 1.1、MyBatis-Plus、Flyway、MySQL 8、JUnit 5、Mockito、AssertJ、Maven Wrapper。

---

## Scope

本计划只修改 D:\GitCode\order-logistics-agent-server，覆盖设计文档第一个实施阶段：

- 四张基础表：设置、记忆、删除抑制、Outbox。
- 显式记忆命令识别、结构化抽取、确定性校验和同步写入。
- 显式记忆分页、编辑、单条删除、清空显式、清空全部、设置 API。
- memory_generation、所有者范围、抑制和 Outbox 幂等契约。
- 显式记忆聊天命令的固定成功或拒绝回答。

本计划不实现隐式抽取 Worker、ES/Milvus 写入 Worker、跨会话召回或 Electron 面板。Outbox 在本阶段可靠保留 PENDING 事件，不会被假装消费。

## File map

- Create: src/main/resources/db/migration/V10__create_user_memory_foundation.sql
- Create: src/main/java/com/xjjk/agent/memory/domain/*
- Create: src/main/java/com/xjjk/agent/memory/config/*
- Create: src/main/java/com/xjjk/agent/memory/persistence/entity/*
- Create: src/main/java/com/xjjk/agent/memory/persistence/mapper/*
- Create: src/main/java/com/xjjk/agent/memory/service/*
- Create: src/main/java/com/xjjk/agent/memory/api/*
- Modify: src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java
- Modify: src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java
- Modify: src/test/resources/application.properties
- Create: docs/runbook/user-memory-foundation-verification.md

### Task 1: Create the Flyway memory foundation

**Files:**
- Create: src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java
- Create: src/main/resources/db/migration/V10__create_user_memory_foundation.sql

- [ ] **Step 1: Write the failing migration contract**

~~~java
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
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("CREATE TABLE agent_user_memory_setting")
                    .contains("CREATE TABLE agent_user_memory")
                    .contains("CREATE TABLE agent_memory_suppression")
                    .contains("CREATE TABLE agent_memory_outbox")
                    .contains("memory_generation BIGINT NOT NULL")
                    .contains("UNIQUE KEY uk_memory_id (memory_id)")
                    .contains("KEY idx_memory_owner_status")
                    .contains("UNIQUE KEY uk_memory_outbox_event (event_id)");
        }
    }
}
~~~

- [ ] **Step 2: Run the contract and verify RED**

Run:

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryMigrationContractTest test
~~~

Expected: FAIL because the V10 migration does not exist.

- [ ] **Step 3: Add the complete V10 migration**

~~~sql
CREATE TABLE agent_user_memory_setting (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    memory_generation BIGINT NOT NULL DEFAULT 1,
    auto_extract_enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_setting_owner (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='用户记忆设置与世代';

CREATE TABLE agent_user_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    memory_generation BIGINT NOT NULL,
    source_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(512) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retention_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_message_sequence BIGINT NULL,
    evidence_text VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_id (memory_id),
    KEY idx_memory_owner_status
        (tenant_id, user_id, memory_generation, status, updated_at, id),
    KEY idx_memory_owner_key
        (tenant_id, user_id, memory_generation, canonical_key, status),
    KEY idx_memory_expiry (status, expires_at, id),
    CONSTRAINT fk_memory_conversation FOREIGN KEY (source_conversation_id)
        REFERENCES agent_conversation (conversation_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_memory_confidence CHECK
        (confidence >= 0.0000 AND confidence <= 1.0000),
    CONSTRAINT chk_memory_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='跨会话用户记忆权威表';

CREATE TABLE agent_memory_suppression (
    id BIGINT NOT NULL AUTO_INCREMENT,
    suppression_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    memory_generation BIGINT NOT NULL,
    canonical_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_suppression_id (suppression_id),
    KEY idx_suppression_owner_key
        (tenant_id, user_id, memory_generation, canonical_key, status)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='用户记忆删除抑制';

CREATE TABLE agent_memory_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    memory_generation BIGINT NOT NULL,
    memory_version BIGINT NOT NULL,
    operation VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_run_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    locked_by VARCHAR(128) NULL,
    locked_until DATETIME(3) NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_outbox_event (event_id),
    KEY idx_memory_outbox_claim (status, next_run_at, id),
    KEY idx_memory_outbox_owner
        (tenant_id, user_id, memory_generation, id),
    CONSTRAINT chk_memory_outbox_retry CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='用户记忆索引Outbox';
~~~

- [ ] **Step 4: Run migration tests**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryMigrationContractTest,OrderLogisticsAgentServerApplicationTests test
~~~

Expected: PASS and Flyway reaches version 10 in the test context.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/resources/db/migration/V10__create_user_memory_foundation.sql src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java
git commit -m "feat: create user memory foundation tables"
~~~

### Task 2: Define memory domain and validated configuration

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/domain/MemoryCategory.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemorySourceType.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemoryRetentionType.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemoryStatus.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemoryVisibility.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemoryOutboxOperation.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemoryOutboxStatus.java
- Create: src/main/java/com/xjjk/agent/memory/domain/MemorySuppressionStatus.java
- Create: src/main/java/com/xjjk/agent/memory/domain/ExplicitMemoryCandidate.java
- Create: src/main/java/com/xjjk/agent/memory/domain/ExplicitMemoryCommandResult.java
- Create: src/main/java/com/xjjk/agent/memory/config/UserMemoryProperties.java
- Test: src/test/java/com/xjjk/agent/memory/domain/UserMemoryDomainTest.java
- Test: src/test/java/com/xjjk/agent/memory/config/UserMemoryPropertiesTest.java
- Modify: src/test/resources/application.properties

- [ ] **Step 1: Write failing domain tests**

~~~java
@Test
void exposesOnlyApprovedCategories() {
    assertThat(MemoryCategory.values()).containsExactly(
            MemoryCategory.PROFILE_PREFERRED_NAME,
            MemoryCategory.PREFERENCE_LANGUAGE,
            MemoryCategory.PREFERENCE_ANSWER_STYLE,
            MemoryCategory.WORK_COMMON_SCOPE);
    assertThat(MemoryCategory.PROFILE_PREFERRED_NAME.keyPrefix())
            .isEqualTo("profile.preferred_name");
}

@Test
void rejectsZeroContextBudget() {
    assertThatIllegalArgumentException().isThrownBy(() ->
            validPropertiesWithContextTokens(0));
}
~~~

- [ ] **Step 2: Run tests and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryDomainTest,UserMemoryPropertiesTest test
~~~

Expected: compilation fails because the domain types do not exist.

- [ ] **Step 3: Implement the exact domain shapes**

~~~java
public enum MemoryCategory {
    PROFILE_PREFERRED_NAME("profile.preferred_name"),
    PREFERENCE_LANGUAGE("preference.language"),
    PREFERENCE_ANSWER_STYLE("preference.answer_style"),
    WORK_COMMON_SCOPE("work.common_scope");

    private final String keyPrefix;
    MemoryCategory(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String keyPrefix() { return keyPrefix; }
}

public enum MemorySourceType { USER_EXPLICIT, AUTO_EXTRACT }
public enum MemoryRetentionType { NORMAL, PERMANENT }
public enum MemoryStatus { ACTIVE, SUPERSEDED, DELETED, EXPIRED }
public enum MemoryVisibility { VISIBLE, HIDDEN }
public enum MemoryOutboxOperation {
    UPSERT, DELETE, DELETE_EXPLICIT_SCOPE, CLEAR_GENERATION
}
public enum MemoryOutboxStatus { PENDING, PROCESSING, RETRY, DONE, DEAD }
public enum MemorySuppressionStatus { ACTIVE, LIFTED }

public record ExplicitMemoryCandidate(
        MemoryCategory category,
        String canonicalKey,
        String content,
        String evidenceText,
        MemoryRetentionType retentionType) {}

public record ExplicitMemoryCommandResult(
        boolean handled,
        boolean saved,
        String assistantText,
        String memoryId) {
    public static ExplicitMemoryCommandResult notHandled() {
        return new ExplicitMemoryCommandResult(false, false, null, null);
    }
}
~~~

Create a validated UserMemoryProperties record with prefix agent.memory and fields:

~~~java
boolean enabled
boolean autoExtractDefaultEnabled
int contextMaxTokens
int maxContentLength
int maxEvidenceLength
int pageSizeMax
int explicitExpireDays
String promptVersion
String model
double temperature
Duration timeout
int modelPoolSize
int modelQueueCapacity
~~~

The compact constructor requires positive bounds; content/evidence lengths are 1..512, page size is 1..100, temperature is 0..2, prompt version is at most 64 characters, model is at most 128 characters, and timeout/pool/queue are positive.

- [ ] **Step 4: Add test properties and run GREEN**

~~~properties
agent.memory.enabled=false
agent.memory.auto-extract-default-enabled=true
agent.memory.context-max-tokens=256
agent.memory.max-content-length=512
agent.memory.max-evidence-length=512
agent.memory.page-size-max=50
agent.memory.explicit-expire-days=365
agent.memory.prompt-version=memory-explicit-test-v1
agent.memory.model=qwen-plus
agent.memory.temperature=0.1
agent.memory.timeout=10s
agent.memory.model-pool-size=1
agent.memory.model-queue-capacity=10
~~~

Run:

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryDomainTest,UserMemoryPropertiesTest test
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/com/xjjk/agent/memory/domain src/main/java/com/xjjk/agent/memory/config/UserMemoryProperties.java src/test/java/com/xjjk/agent/memory/domain src/test/java/com/xjjk/agent/memory/config src/test/resources/application.properties
git commit -m "feat: define user memory domain"
~~~

### Task 3: Add owner-scoped persistence adapters

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemoryEntity.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemorySettingEntity.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/entity/MemorySuppressionEntity.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/entity/MemoryOutboxEntity.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/projection/UserMemoryListRow.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemorySettingMapper.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/mapper/MemorySuppressionMapper.java
- Create: src/main/java/com/xjjk/agent/memory/persistence/mapper/MemoryOutboxMapper.java
- Test: src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMapperContractTest.java

- [ ] **Step 1: Write the failing mapper contract**

Reflect over UserMemoryMapper and require tenantId and userId parameters on:

~~~java
List.of(
    "selectActiveByKeyForUpdate",
    "selectVisiblePage",
    "softDeleteOwned",
    "clearOwnedExplicit",
    "clearOwnedGeneration")
~~~

Use MyBatis Param annotations and assert both owner names are present for every method.

- [ ] **Step 2: Run the contract and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryMapperContractTest test
~~~

Expected: compilation fails because the mappers do not exist.

- [ ] **Step 3: Implement entities and mapper signatures**

Each entity maps every V10 column using TableName, TableId and TableField. UserMemoryMapper exposes:

~~~java
UserMemoryEntity selectActiveByKeyForUpdate(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("generation") long generation,
        @Param("canonicalKey") String canonicalKey);

List<UserMemoryListRow> selectVisiblePage(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("generation") long generation,
        @Param("beforeUpdatedAt") LocalDateTime beforeUpdatedAt,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

int supersedeOwnedActive(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("generation") long generation,
        @Param("canonicalKey") String canonicalKey,
        @Param("updatedAt") LocalDateTime updatedAt);

int softDeleteOwned(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("generation") long generation,
        @Param("memoryId") String memoryId,
        @Param("updatedAt") LocalDateTime updatedAt);
~~~

Add clearOwnedExplicit and clearOwnedGeneration with the same owner fields. Annotation SQL must enforce USER_EXPLICIT, VISIBLE and ACTIVE for visible listing, order by updated_at DESC, id DESC, and use keyset boundaries rather than OFFSET.

UserMemorySettingMapper implements insert-if-absent, normal owner read, owner read FOR UPDATE, toggle update and compare-and-increment generation. MemorySuppressionMapper implements owner-scoped lift and insert. MemoryOutboxMapper extends BaseMapper.

- [ ] **Step 4: Run persistence contracts**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryMigrationContractTest,UserMemoryMapperContractTest test
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/com/xjjk/agent/memory/persistence src/test/java/com/xjjk/agent/memory/persistence
git commit -m "feat: add owner scoped memory persistence"
~~~

### Task 4: Detect and validate explicit memory commands

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandDetector.java
- Create: src/main/java/com/xjjk/agent/memory/service/MemorySensitiveContentPolicy.java
- Create: src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java
- Test: src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandDetectorTest.java
- Test: src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidatorTest.java

- [ ] **Step 1: Write failing tests**

~~~java
assertThat(detector.detect("请记住以后回答简短一些"))
        .hasValue(new CommandText("以后回答简短一些", false));
assertThat(detector.detect("请永久记住叫我老师"))
        .hasValue(new CommandText("叫我老师", true));
assertThat(detector.detect("我觉得你应该记住这个问题吗？")).isEmpty();
assertThat(detector.detect("请记住回答简短，顺便帮我查订单")).isEmpty();
~~~

Validator tests accept a matching answer-style candidate and reject a key outside its category prefix, absent evidence, 513-character content, Bearer token, Chinese identity card and phone number.

- [ ] **Step 2: Run tests and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=ExplicitMemoryCommandDetectorTest,ExplicitMemoryCandidateValidatorTest test
~~~

Expected: compilation fails.

- [ ] **Step 3: Implement anchored command detection**

Permanent prefixes are 请永久记住 and 永久记住. Normal prefixes are 请记住, 帮我记住, 记住, 以后请 and 以后都. Normalize Unicode whitespace, strip outer punctuation, reject empty or over-512-code-point payloads, and reject obvious mixed-intent suffixes 顺便, 另外帮我 and 同时帮我. Mixed requests continue through normal chat without memory writes.

- [ ] **Step 4: Implement fail-closed validation**

MemorySensitiveContentPolicy rejects credentials covered by SensitiveContentSanitizer plus identity cards, bank cards, phone numbers, health diagnosis terms, concrete order identifiers, refund records and logistics records.

ExplicitMemoryCandidateValidator must require:

~~~text
category belongs to the enum
canonicalKey equals category.keyPrefix or starts with prefix plus dot
evidence is a normalized substring of the original user message
content and evidence satisfy code-point limits
permanent retention only comes from a permanent command
content, evidence and original message all pass sensitive policy
~~~

Return a new normalized candidate and never persist a redacted forbidden value.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\mvnw.cmd -Dtest=ExplicitMemoryCommandDetectorTest,ExplicitMemoryCandidateValidatorTest test
git add src/main/java/com/xjjk/agent/memory/service src/test/java/com/xjjk/agent/memory/service
git commit -m "feat: validate explicit memory commands"
~~~

Expected: PASS.

### Task 5: Add the structured model extractor

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryExtractor.java
- Create: src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryExtractionException.java
- Create: src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java
- Create: src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java
- Test: src/test/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractorTest.java

- [ ] **Step 1: Write a failing adapter test**

Mock the dedicated ChatClient and return:

~~~json
{
  "category": "PREFERENCE_ANSWER_STYLE",
  "canonicalKey": "preference.answer_style",
  "content": "用户偏好简洁回答",
  "evidenceText": "以后回答简短一些"
}
~~~

Assert it maps to ExplicitMemoryCandidate and retention is assigned from CommandText. Blank output, malformed JSON, unknown category, timeout and executor rejection must throw ExplicitMemoryExtractionException with stable codes and without supplier error text.

- [ ] **Step 2: Run the test and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=SpringAiExplicitMemoryExtractorTest test
~~~

Expected: compilation fails.

- [ ] **Step 3: Implement the extractor**

~~~java
public interface ExplicitMemoryExtractor {
    ExplicitMemoryCandidate extract(
            ExplicitMemoryCommandDetector.CommandText command,
            String originalMessage);
}
~~~

Use Jackson ObjectMapper, a bounded executor named memoryExtractionModelExecutor, and Future.get with the configured timeout. The system prompt lists exactly four categories, asks for one JSON object, prohibits secrets/business records/inference, and requires evidenceText copied from the user message. Ignore model-supplied owner, visibility and retention fields.

- [ ] **Step 4: Configure an isolated ChatClient**

~~~java
OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model(properties.model())
        .temperature(properties.temperature())
        .maxTokens(256)
        .responseFormat(ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_OBJECT).build())
        .extraBody(Map.of("enable_thinking", false))
        .build();
~~~

Create a fixed-size bounded ThreadPoolExecutor. Do not reuse the summary executor or streaming client.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\mvnw.cmd -Dtest=SpringAiExplicitMemoryExtractorTest,UserMemoryPropertiesTest test
git add src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryExtractor.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryExtractionException.java src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java src/test/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractorTest.java
git commit -m "feat: extract explicit memory candidates"
~~~

### Task 6: Persist explicit memory and Outbox atomically

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/service/MemoryHashing.java
- Create: src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java
- Test: src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteServiceTest.java

- [ ] **Step 1: Write failing transaction tests**

Verify insert-if-absent plus FOR UPDATE settings read, existing-key supersession, new USER_EXPLICIT/VISIBLE/confidence 1 memory, suppression lift and matching UPSERT Outbox. Any insert count other than one throws BusinessException with MEMORY_WRITE_FAILED.

- [ ] **Step 2: Run the test and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=ExplicitMemoryWriteServiceTest test
~~~

Expected: compilation fails.

- [ ] **Step 3: Implement hashing and the transaction**

MemoryHashing.sha256 hashes UTF-8 normalized content and returns lowercase 64-character hex.

ExplicitMemoryWriteService.save(ChatTurnContext, ExplicitMemoryCandidate) is transactional and:

~~~text
insert default settings if absent
read owner setting FOR UPDATE
supersede current active canonical key
lift suppression for the same key
insert UUID memory at previous version plus one
use UTC millisecond time
set NORMAL expiry from explicitExpireDays; PERMANENT expiry is null
insert PENDING UPSERT Outbox with nextRunAt equal to now
return memory ID and normalized content
~~~

Every lookup and update includes tenant, user and generation. Do not query ES or Milvus.

- [ ] **Step 4: Run GREEN**

~~~powershell
.\mvnw.cmd -Dtest=ExplicitMemoryWriteServiceTest test
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/com/xjjk/agent/memory/service/MemoryHashing.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteServiceTest.java
git commit -m "feat: persist explicit user memories"
~~~

### Task 7: Integrate pure memory commands into chat

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java
- Modify: src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java
- Modify: src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java
- Test: src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java
- Test: src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerExplicitMemoryTest.java
- Modify: src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java
- Modify: src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java

- [ ] **Step 1: Write failing orchestration tests**

~~~java
assertThat(service.handle(turn, "今天下雨吗").handled()).isFalse();
assertThat(service.handle(turn, "请记住以后回答简短一些"))
        .extracting(ExplicitMemoryCommandResult::handled,
                    ExplicitMemoryCommandResult::saved,
                    ExplicitMemoryCommandResult::assistantText)
        .containsExactly(true, true, "好的，已记住：用户偏好简洁回答");
~~~

Policy rejection returns handled=true, saved=false and fixed text 这类内容不适合作为长期记忆保存。 The runner test proves a handled command never calls BusinessQueryPlanner, ChatContextPreparationService or AiChatService, but the normal finalizer persists a SUCCESS assistant message with finish reason MEMORY_SAVED or MEMORY_REJECTED.

Update every existing direct ChatTurnRunner constructor in ChatActionDispatcherTest and ChatTurnRunnerBusinessQueryTest to pass a mocked ExplicitMemoryCommandService that returns ExplicitMemoryCommandResult.notHandled(). This preserves all existing action and fresh-business-query expectations.

- [ ] **Step 2: Run tests and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=ExplicitMemoryCommandServiceTest,ChatTurnRunnerExplicitMemoryTest test
~~~

Expected: failure because there is no memory command branch.

- [ ] **Step 3: Implement orchestration**

Return notHandled when disabled or unmatched; otherwise extract, validate and save. Convert policy rejection to the fixed non-saved reply. Convert extraction/storage unavailability to BusinessException(MEMORY_WRITE_FAILED). Never log original messages, evidence or memory content.

- [ ] **Step 4: Add the runner branch**

Immediately after session.session and before card actions:

~~~java
ExplicitMemoryCommandResult memory = explicitMemoryCommandService.handle(
        execution.turn, request.message());
if (memory.handled()) {
    session.generating();
    execution.content.append(memory.assistantText());
    session.delta(memory.assistantText());
    execution.metrics.markFirstDeltaSent();
    execution.finishReason = memory.saved()
            ? "MEMORY_SAVED" : "MEMORY_REJECTED";
    execution.status = MessageStatus.SUCCESS;
    execution.error = null;
    return;
}
~~~

Add MEMORY_WRITE_FAILED to ApiErrorCode with a safe Chinese message that does not claim success.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\mvnw.cmd -Dtest=ExplicitMemoryCommandServiceTest,ChatTurnRunnerExplicitMemoryTest,ChatTurnRunnerBusinessQueryTest,ChatActionDispatcherTest test
git add src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerExplicitMemoryTest.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java
git commit -m "feat: handle explicit memory chat commands"
~~~

### Task 8: Add visible-memory pagination and settings API

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/service/UserMemoryPageCursor.java
- Create: src/main/java/com/xjjk/agent/memory/service/UserMemoryPageCursorCodec.java
- Create: src/main/java/com/xjjk/agent/memory/service/UserMemoryQueryService.java
- Create: src/main/java/com/xjjk/agent/memory/api/dto/UserMemoryResponse.java
- Create: src/main/java/com/xjjk/agent/memory/api/dto/UserMemoryPageResponse.java
- Create: src/main/java/com/xjjk/agent/memory/api/dto/UserMemorySettingResponse.java
- Create: src/main/java/com/xjjk/agent/memory/api/dto/UpdateUserMemorySettingRequest.java
- Create: src/main/java/com/xjjk/agent/memory/api/UserMemoryController.java
- Test: src/test/java/com/xjjk/agent/memory/service/UserMemoryQueryServiceTest.java
- Test: src/test/java/com/xjjk/agent/memory/api/UserMemoryControllerTest.java

- [ ] **Step 1: Write failing query and controller tests**

Request 10 items, verify mapper limit 11 and next cursor from the tenth row. Reject page sizes 0 and 51 before mapper access. Controller tests cover:

~~~text
GET /api/v1/me/memories?limit=10
GET /api/v1/me/memory-settings
PUT /api/v1/me/memory-settings with autoExtractEnabled=false
~~~

Every service call receives the injected AgentIdentity; no endpoint accepts tenantId or userId.

- [ ] **Step 2: Run tests and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryQueryServiceTest,UserMemoryControllerTest test
~~~

Expected: compilation fails.

- [ ] **Step 3: Implement opaque keyset pagination**

Encode a versioned HMAC-SHA256 signed cursor containing updatedAt, id, tenantId, userId and memoryGeneration. Enforce millisecond precision and maximum encoded length 256, reject tampering and owner/generation mismatch. Query limit+1 and return:

~~~java
public record UserMemoryPageResponse(
        List<UserMemoryResponse> items,
        String nextCursor,
        boolean hasMore) {}
~~~

UserMemoryResponse exposes memory ID, category, content, retention type, version and Shanghai-zone updatedAt. It never exposes tenant, user, evidence, source conversation or content hash.

- [ ] **Step 4: Implement settings endpoints**

GET returns configured default without creating a row. PUT accepts one non-null boolean, ensures the row exists, locks it and updates only auto_extract_enabled. It must not change generation or existing memories.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryQueryServiceTest,UserMemoryControllerTest test
git add src/main/java/com/xjjk/agent/memory/api src/main/java/com/xjjk/agent/memory/service/UserMemoryPageCursor.java src/main/java/com/xjjk/agent/memory/service/UserMemoryPageCursorCodec.java src/main/java/com/xjjk/agent/memory/service/UserMemoryQueryService.java src/test/java/com/xjjk/agent/memory/api src/test/java/com/xjjk/agent/memory/service/UserMemoryQueryServiceTest.java
git commit -m "feat: expose owned user memories"
~~~

### Task 9: Implement edit, delete and explicit clear

**Files:**
- Create: src/main/java/com/xjjk/agent/memory/api/dto/UpdateUserMemoryRequest.java
- Create: src/main/java/com/xjjk/agent/memory/service/UserMemoryManagementService.java
- Modify: src/main/java/com/xjjk/agent/memory/api/UserMemoryController.java
- Modify: src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java
- Test: src/test/java/com/xjjk/agent/memory/service/UserMemoryManagementServiceTest.java

- [ ] **Step 1: Write failing management tests**

Prove:

~~~text
edit supersedes an owned visible explicit memory and inserts a new version plus UPSERT
implicit, hidden, deleted and wrong-owner IDs all return MEMORY_NOT_FOUND
single delete creates suppression plus DELETE Outbox
explicit clear locks settings, suppresses each active explicit key, deletes only explicit rows
explicit clear emits one DELETE_EXPLICIT_SCOPE Outbox
~~~

- [ ] **Step 2: Run and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryManagementServiceTest test
~~~

Expected: compilation fails.

- [ ] **Step 3: Implement endpoints and transactions**

Add:

~~~text
PUT    /api/v1/me/memories/{memoryId}
DELETE /api/v1/me/memories/{memoryId}
DELETE /api/v1/me/memories?scope=explicit
~~~

UpdateUserMemoryRequest contains NotBlank/Size(max=512) content and NotNull retentionType. Re-run sensitive policy. Preserve category and canonical key; edit creates a new memory ID with version+1. Delete and clear commit status, suppression and Outbox together. Return affected counts, not deleted text.

- [ ] **Step 4: Run GREEN**

Add MEMORY_NOT_FOUND, MEMORY_CONTENT_REJECTED and MEMORY_CLEAR_FAILED safe error codes, then run:

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryManagementServiceTest,UserMemoryControllerTest test
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/com/xjjk/agent/memory src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java src/test/java/com/xjjk/agent/memory
git commit -m "feat: manage explicit user memories"
~~~

### Task 10: Implement clear-all generation invalidation

**Files:**
- Modify: src/main/java/com/xjjk/agent/memory/service/UserMemoryManagementService.java
- Modify: src/main/java/com/xjjk/agent/memory/api/UserMemoryController.java
- Modify: src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemorySettingMapper.java
- Modify: src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java
- Modify: src/main/java/com/xjjk/agent/memory/persistence/mapper/MemorySuppressionMapper.java
- Test: src/test/java/com/xjjk/agent/memory/service/UserMemoryClearAllTest.java

- [ ] **Step 1: Write the failing ordered-interaction test**

~~~text
lock settings generation 7
mark generation 7 memories deleted
lift generation 7 suppressions
compare-and-set generation 7 to 8
insert CLEAR_GENERATION Outbox for generation 7
return generation 8
~~~

A compare-and-set count of zero throws MEMORY_CLEAR_FAILED.

- [ ] **Step 2: Run and verify RED**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryClearAllTest test
~~~

Expected: FAIL because scope=all does not exist.

- [ ] **Step 3: Implement generation invalidation**

Add DELETE /api/v1/me/memories?scope=all. Create settings if absent, lock them, affect only the locked generation, increment with Math.incrementExact, compare-and-set exactly once, and write a scope-delete Outbox for the old generation. Keep rows physically present.

- [ ] **Step 4: Run management tests**

~~~powershell
.\mvnw.cmd -Dtest=UserMemoryClearAllTest,UserMemoryManagementServiceTest,UserMemoryControllerTest test
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/com/xjjk/agent/memory src/test/java/com/xjjk/agent/memory
git commit -m "feat: clear all user memory generations"
~~~

### Task 11: Verify the phase

**Files:**
- Create: docs/runbook/user-memory-foundation-verification.md

- [ ] **Step 1: Write the runbook**

Document these checks without real credentials:

~~~text
start MySQL and Agent Server with memory enabled in Nacos
authenticate as test account 74680 and create a conversation
send 请记住以后回答简短一些 and expect fixed success text
list visible memories and verify one explicit record
edit it and verify version increases
delete it and verify list empty plus suppression
save 请永久记住叫我老师 and verify null expiry
clear explicit, then create records and clear all
verify memory_generation increments
use another user and verify the original memory ID is inaccessible
verify pending Outbox rows contain no password or token text
~~~

- [ ] **Step 2: Run focused tests**

~~~powershell
.\mvnw.cmd -Dtest="com.xjjk.agent.memory.**" test
~~~

Expected: all memory tests PASS.

- [ ] **Step 3: Run the full backend suite**

~~~powershell
.\mvnw.cmd test
~~~

Expected: BUILD SUCCESS with zero failures and errors.

- [ ] **Step 4: Check the diff**

~~~powershell
git diff --check
git status --short
~~~

Expected: no diff-check output and only the new runbook remains before commit.

- [ ] **Step 5: Commit**

~~~powershell
git add docs/runbook/user-memory-foundation-verification.md
git commit -m "docs: add user memory verification runbook"
~~~

## Phase completion gate

Do not start implicit extraction until:

- Flyway V10 applies to an empty MySQL 8 schema and an upgraded schema.
- Full Maven tests pass.
- Explicit commands never query ES or Milvus synchronously.
- Every API derives owner scope from AgentIdentity.
- Delete and clear are logically immediate in MySQL.
- Outbox events remain durable and pending.
- Account 74680 manual verification passes without memory text in logs.

The remaining independent plans are:

1. Agent Server implicit extraction, expiry and suppression.
2. Knowledge Service ES/Milvus memory indexing and hybrid search API.
3. Agent Server recall, MySQL hydration and context injection.
4. Electron memory panel and packaged end-to-end verification.
