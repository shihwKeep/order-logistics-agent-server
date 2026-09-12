# MySQL-First Direct Memory Answers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the first strict self-memory question after restart answer from authoritative MySQL data without depending on Elasticsearch, Milvus, embedding warm-up, reranking, or the chat model.

**Architecture:** Add an owner-, generation-, category-, status-, visibility-, and expiry-scoped MyBatis query. Expose it through `UserMemoryRecallService.recallByCategory`, reuse the existing candidate selector and suppression rules, and route the deterministic answer service through this method. General semantic recall remains unchanged.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis Plus, MySQL 8.4, JUnit 5, AssertJ, Mockito, Testcontainers, Maven Wrapper.

---

## File Map

- Modify `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`: add the authoritative exact-category SQL query.
- Modify `src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java`: prove the SQL is executable, isolated, ordered, and expiry-safe on MySQL 8.4.
- Modify `src/main/java/com/xjjk/agent/memory/recall/UserMemoryRecallService.java`: add the MySQL-only category recall entry point and reuse final validation/suppression/selection.
- Modify `src/main/java/com/xjjk/agent/memory/recall/MemoryCandidateSelector.java`: expose an explicit authoritative-row selection entry point without duplicating conflict rules.
- Modify `src/test/java/com/xjjk/agent/memory/recall/UserMemoryRecallServiceTest.java`: prove category recall never calls the semantic gateway and preserves availability states.
- Modify `src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java`: replace semantic recall with exact-category MySQL recall.
- Modify `src/test/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerServiceTest.java`: reproduce the first-request regression and verify no semantic call.
- Modify `docs/runbook/user-memory-index-recall-verification.md`: add first-request-after-restart acceptance criteria.

### Task 1: Add the Authoritative Category Query

**Files:**
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`

- [ ] **Step 1: Write the failing MySQL integration test**

Extend `recallQueriesAreOwnerScopedAndExecutableOnRealMySql` by inserting current and non-current `WORK_COMMON_SCOPE` rows, then call the wished-for Mapper method:

```java
jdbc.update("""
        INSERT INTO agent_user_memory (
            memory_id, tenant_id, user_id, memory_generation, source_type, category,
            canonical_key, content, content_hash, confidence, visibility, retention_type,
            status, evidence_text, version, expires_at, created_at, updated_at
        ) VALUES
        ('00000000-0000-0000-0000-000000000031', 1, 2, 7, 'AUTO_EXTRACT',
         'WORK_COMMON_SCOPE', 'work.common_scope.java', '用户常用工作范围是Java开发',
         REPEAT('e', 64), 0.9500, 'HIDDEN', 'NORMAL', 'ACTIVE', 'Java开发', 1,
         UTC_TIMESTAMP(3) + INTERVAL 365 DAY, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
        ('00000000-0000-0000-0000-000000000032', 1, 2, 7, 'USER_EXPLICIT',
         'WORK_COMMON_SCOPE', 'work.common_scope.python', '用户常用工作范围是Python开发',
         REPEAT('f', 64), 1.0000, 'VISIBLE', 'PERMANENT', 'ACTIVE', 'Python开发', 1,
         NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
        ('00000000-0000-0000-0000-000000000033', 1, 99, 7, 'AUTO_EXTRACT',
         'WORK_COMMON_SCOPE', 'work.common_scope.other', '用户常用工作范围是Java开发',
         REPEAT('a', 64), 0.9900, 'HIDDEN', 'NORMAL', 'ACTIVE', 'Java开发', 1,
         NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
        ('00000000-0000-0000-0000-000000000034', 1, 2, 7, 'AUTO_EXTRACT',
         'WORK_COMMON_SCOPE', 'work.common_scope.expired', '用户常用工作范围是Java开发',
         REPEAT('b', 64), 0.9900, 'HIDDEN', 'NORMAL', 'ACTIVE', 'Java开发', 1,
         UTC_TIMESTAMP(3) - INTERVAL 1 SECOND, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
        """);

assertThat(memoryMapper.selectActiveByCategory(
        1L, 2L, 7L, "WORK_COMMON_SCOPE", now, 10))
        .extracting(UserMemoryEntity::getMemoryId)
        .containsExactly(
                "00000000-0000-0000-0000-000000000032",
                "00000000-0000-0000-0000-000000000031");
```

- [ ] **Step 2: Run the integration test and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemorySpringTransactionIntegrationTest#recallQueriesAreOwnerScopedAndExecutableOnRealMySql" test
```

Expected: compilation fails because `selectActiveByCategory` does not exist.

- [ ] **Step 3: Add the bounded exact-category Mapper query**

Add to `UserMemoryMapper`:

```java
@Select("""
    SELECT *
    FROM agent_user_memory
    WHERE tenant_id = #{tenantId}
      AND user_id = #{userId}
      AND memory_generation = #{generation}
      AND category = #{category}
      AND status = 'ACTIVE'
      AND (expires_at IS NULL OR expires_at > #{now})
      AND (
            (source_type = 'USER_EXPLICIT' AND visibility = 'VISIBLE')
            OR (source_type = 'AUTO_EXTRACT' AND visibility = 'HIDDEN')
      )
    ORDER BY CASE WHEN source_type = 'USER_EXPLICIT' THEN 0 ELSE 1 END,
             confidence DESC, updated_at DESC, id DESC
    LIMIT #{limit}
    """)
List<UserMemoryEntity> selectActiveByCategory(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("generation") long generation,
        @Param("category") String category,
        @Param("now") LocalDateTime now,
        @Param("limit") int limit);
```

- [ ] **Step 4: Run the integration test and verify GREEN**

Run the command from Step 2.

Expected: one test passes; the explicit current-owner row precedes the automatic row, while the other owner and expired row are absent.

- [ ] **Step 5: Commit the Mapper slice**

```powershell
git add src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java
git commit -m "feat: query authoritative memory by category"
```

### Task 2: Add MySQL-Only Category Recall

**Files:**
- Modify: `src/test/java/com/xjjk/agent/memory/recall/UserMemoryRecallServiceTest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/UserMemoryRecallService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/MemoryCandidateSelector.java`

- [ ] **Step 1: Write failing service tests**

Add tests that call the wished-for method and verify the exact category path, suppression, state mapping, and semantic isolation:

```java
@Test
void recallsExactCategoryFromMysqlWithoutSemanticGateway() {
    UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
    UserMemoryMapper memories = mock(UserMemoryMapper.class);
    MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
    MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
    when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
    UserMemoryEntity java = memory("java", 1L, "AUTO_EXTRACT",
            "WORK_COMMON_SCOPE", "work.common_scope.java",
            "用户常用工作范围是Java开发");
    when(memories.selectActiveByCategory(
            7L, 9L, 3L, "WORK_COMMON_SCOPE", NOW, 20))
            .thenReturn(List.of(java));
    when(suppressions.selectActiveKeys(
            7L, 9L, 3L, List.of("work.common_scope.java"), 20))
            .thenReturn(List.of());

    UserMemoryRecallResult result = service(
            settings, memories, suppressions, gateway, true)
            .recallByCategory(IDENTITY, MemoryCategory.WORK_COMMON_SCOPE);

    assertThat(result.status()).isEqualTo(UserMemoryRecallStatus.AVAILABLE);
    assertThat(result.memories()).extracting(RecalledMemory::memoryId)
            .containsExactly("java");
    assertThat(result.semanticAttempted()).isFalse();
    assertThat(result.semanticResultCode()).isEqualTo("MYSQL_CATEGORY");
    verifyNoInteractions(gateway);
}

@Test
void categoryRecallDistinguishesUninitializedDisabledAndDatabaseFailure() {
    UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
    UserMemoryMapper memories = mock(UserMemoryMapper.class);
    MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
    MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
    UserMemoryRecallService service = service(
            settings, memories, suppressions, gateway, true);

    when(settings.selectOwned(7L, 9L)).thenReturn(null);
    assertThat(service.recallByCategory(
            IDENTITY, MemoryCategory.WORK_COMMON_SCOPE).status())
            .isEqualTo(UserMemoryRecallStatus.NOT_INITIALIZED);

    when(settings.selectOwned(7L, 9L)).thenReturn(setting(false, 3L));
    assertThat(service.recallByCategory(
            IDENTITY, MemoryCategory.WORK_COMMON_SCOPE).status())
            .isEqualTo(UserMemoryRecallStatus.DISABLED);

    when(settings.selectOwned(7L, 9L))
            .thenThrow(new IllegalStateException("db down"));
    assertThat(service.recallByCategory(
            IDENTITY, MemoryCategory.WORK_COMMON_SCOPE).status())
            .isEqualTo(UserMemoryRecallStatus.UNAVAILABLE);
}
```

Add imports for `MemoryCategory` and `verifyNoInteractions`.

- [ ] **Step 2: Run the recall test and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryRecallServiceTest" test
```

Expected: compilation fails because `recallByCategory` does not exist.

- [ ] **Step 3: Expose authoritative selection without duplicating rules**

Add to `MemoryCandidateSelector`:

```java
public List<RecalledMemory> selectAuthoritative(
        List<UserMemoryEntity> authoritative,
        Set<String> suppressedKeys,
        int maxSelected) {
    return select(authoritative, List.of(), suppressedKeys, maxSelected);
}
```

- [ ] **Step 4: Implement the guarded category recall method**

Add to `UserMemoryRecallService`:

```java
public UserMemoryRecallResult recallByCategory(
        AgentIdentity identity,
        MemoryCategory category) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(category, "category");
    if (identity.tenantId() <= 0 || identity.userId() <= 0) {
        return UserMemoryRecallResult.invalidRequest();
    }
    if (!memoryProperties.enabled()) {
        return UserMemoryRecallResult.disabled();
    }
    try {
        return recallCategorySafely(
                identity.tenantId(), identity.userId(), category);
    } catch (RuntimeException failure) {
        log.warn("user_memory_category_recall result=DEGRADED "
                        + "errorCode=MYSQL_VALIDATION_FAILED exceptionType={}",
                failure.getClass().getSimpleName());
        return UserMemoryRecallResult.unavailable();
    }
}

private UserMemoryRecallResult recallCategorySafely(
        long tenantId,
        long userId,
        MemoryCategory category) {
    UserMemorySettingEntity setting = settingMapper.selectOwned(tenantId, userId);
    if (setting == null) {
        return UserMemoryRecallResult.notInitialized();
    }
    if (!Boolean.TRUE.equals(setting.getMemoryEnabled())) {
        return UserMemoryRecallResult.disabled();
    }
    if (setting.getMemoryGeneration() == null
            || setting.getMemoryGeneration() <= 0) {
        return UserMemoryRecallResult.unavailable();
    }
    long generation = setting.getMemoryGeneration();
    LocalDateTime now = now();
    List<UserMemoryEntity> raw = memoryMapper.selectActiveByCategory(
            tenantId, userId, generation, category.name(), now,
            retrievalProperties.maxCandidates());
    List<UserMemoryEntity> valid = raw.stream()
            .filter(memory -> validOwned(
                    memory, tenantId, userId, generation, now))
            .filter(memory -> category.name().equals(memory.getCategory()))
            .toList();
    rejected("OWNER_OR_STATE", raw.size() - valid.size());
    List<String> keys = valid.stream()
            .map(UserMemoryEntity::getCanonicalKey).distinct().toList();
    Set<String> suppressed = keys.isEmpty() ? Set.of() : Set.copyOf(
            suppressionMapper.selectActiveKeys(
                    tenantId, userId, generation, keys,
                    retrievalProperties.maxCandidates()));
    rejected("SUPPRESSED", suppressed.size());
    List<RecalledMemory> selected = selector.selectAuthoritative(
            valid, suppressed, retrievalProperties.maxSelected());
    if (metrics != null) {
        metrics.candidateCount("MYSQL_VALIDATED", valid.size());
        metrics.candidateCount("SELECTED", selected.size());
    }
    return new UserMemoryRecallResult(
            selected, false, "MYSQL_CATEGORY",
            UserMemoryRecallStatus.AVAILABLE);
}
```

Import `com.xjjk.agent.memory.domain.MemoryCategory`.

- [ ] **Step 5: Run recall tests and verify GREEN**

Run the command from Step 2.

Expected: all `UserMemoryRecallServiceTest` tests pass, and the new test verifies no interaction with `MemoryRecallGateway`.

- [ ] **Step 6: Commit the recall slice**

```powershell
git add src/main/java/com/xjjk/agent/memory/recall/UserMemoryRecallService.java src/main/java/com/xjjk/agent/memory/recall/MemoryCandidateSelector.java src/test/java/com/xjjk/agent/memory/recall/UserMemoryRecallServiceTest.java
git commit -m "feat: recall direct memory facts from mysql"
```

### Task 3: Route Deterministic Answers Through MySQL

**Files:**
- Modify: `src/test/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerServiceTest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java`

- [ ] **Step 1: Change the direct-answer test to reproduce the cold-vector regression**

In `answersFromValidatedMatchingMemory`, replace the semantic recall stub with:

```java
when(recallService.recallByCategory(
        IDENTITY, MemoryCategory.WORK_COMMON_SCOPE))
        .thenReturn(new UserMemoryRecallResult(
                List.of(javaMemory), false, "MYSQL_CATEGORY",
                UserMemoryRecallStatus.AVAILABLE));
```

Then add:

```java
verify(recallService).recallByCategory(
        IDENTITY, MemoryCategory.WORK_COMMON_SCOPE);
verify(recallService, never()).recall(any(), anyString());
```

Update all other strict-answer stubs to use `recallByCategory`. Add imports for `MemoryCategory`, `any`, `anyString`, and `never`.

- [ ] **Step 2: Run the direct-answer test and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=DeterministicUserMemoryAnswerServiceTest" test
```

Expected: tests fail because production code still calls `recall(identity, query)`.

- [ ] **Step 3: Replace semantic recall with category recall**

In `DeterministicUserMemoryAnswerService.answer`, replace:

```java
UserMemoryRecallResult recalled = recallService.recall(identity, query);
```

with:

```java
UserMemoryRecallResult recalled = recallService.recallByCategory(
        identity, type.memoryCategory());
```

Delete the semantic-only status block:

```java
if (!recalled.semanticAttempted()
        || "UNAVAILABLE".equalsIgnoreCase(recalled.semanticResultCode())
        || "DISABLED".equalsIgnoreCase(recalled.semanticResultCode())) {
    return handled(requestId, type,
            DeterministicUserMemoryAnswerResult.Outcome.UNAVAILABLE,
            UNAVAILABLE);
}
```

An `AVAILABLE` empty category result now unambiguously means `NOT_REMEMBERED`.

- [ ] **Step 4: Run direct-answer and execution-chain tests and verify GREEN**

Run:

```powershell
.\mvnw.cmd "-Dtest=DeterministicUserMemoryAnswerServiceTest,ChatTurnRunnerDeterministicMemoryTest,ChatTurnRunnerBusinessQueryTest" test
```

Expected: all selected tests pass; strict memory answers bypass both semantic recall and the chat model, while business routing remains unchanged.

- [ ] **Step 5: Commit the routing slice**

```powershell
git add src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java src/test/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerServiceTest.java
git commit -m "fix: make direct memory answers mysql first"
```

### Task 4: Regression, Documentation, and Packaging

**Files:**
- Modify: `docs/runbook/user-memory-index-recall-verification.md`

- [ ] **Step 1: Update the runbook**

In “确定性直答验收”, explicitly require the first request after restart to succeed and state that strict direct answers do not depend on the Knowledge Service:

```markdown
1. 确认当前用户在 MySQL 中存在 `ACTIVE / AUTO_EXTRACT / WORK_COMMON_SCOPE` 记忆，正文经安全方式核验为 Java 开发；ES/Milvus 是否已预热不影响本项验收。
2. 重启 Agent、Knowledge Service 与本地 Embedding 服务后，立即新建会话发送 `我平时主要使用什么编程语言？`，随后连续执行 9 次。
3. 从重启后的第一次开始，10 次回答都必须包含 `Java`；严格直答不得调用 Knowledge Service、Embedding、ES、Milvus、Reranker 或聊天模型。
```

Adjust the degradation matrix so stopping Knowledge Service does not affect strict direct answers when MySQL is healthy; stopping MySQL must return “记忆服务暂时不可用”。

- [ ] **Step 2: Run focused memory and chat regression tests**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemorySpringTransactionIntegrationTest,UserMemoryRecallServiceTest,DeterministicMemoryAnswerRendererTest,DeterministicUserMemoryAnswerServiceTest,ChatTurnRunner*Test,FreshBusinessResultGateTest,ChatActionDispatcherTest" test
```

Expected: all selected tests pass with zero failures and zero errors.

- [ ] **Step 3: Run the complete test suite**

Run:

```powershell
.\mvnw.cmd -q test
```

Expected: Maven exits with code 0; all Surefire reports show zero failures and zero errors.

- [ ] **Step 4: Build the executable artifact**

Run:

```powershell
.\mvnw.cmd -q -DskipTests package
```

Expected: exit code 0 and `target/order-logistics-agent-server-0.0.1-SNAPSHOT.jar` exists.

- [ ] **Step 5: Check the final diff and commit the runbook**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only the runbook is uncommitted.

```powershell
git add docs/runbook/user-memory-index-recall-verification.md
git commit -m "docs: verify first memory answer after restart"
```

- [ ] **Step 6: Perform final verification after the last commit**

Run:

```powershell
.\mvnw.cmd -q test
.\mvnw.cmd -q -DskipTests package
git diff --check main..HEAD
git status --short
```

Expected: both Maven commands exit 0, the branch diff has no whitespace errors, and the worktree is clean.
