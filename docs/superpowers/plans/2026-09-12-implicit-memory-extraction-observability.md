# Implicit Memory Extraction Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist privacy-safe result codes and candidate counts for every completed implicit-memory extraction task so a `DONE` task can be diagnosed without storing user or model text.

**Architecture:** A small domain value object classifies model/validator outcomes, while `ImplicitMemoryCommitService` remains the single transaction boundary that writes memories, Outbox events, and final task observability fields atomically. A Flyway migration adds nullable result state for new completions and zero-valued counters for backward-compatible historical rows.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus, Flyway, MySQL 8.4, JUnit 5, AssertJ, Mockito, Testcontainers

---

### Task 1: Add durable task-result columns

**Files:**
- Create: `src/main/resources/db/migration/V13__add_memory_extraction_observability.sql`
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java`

- [ ] **Step 1: Write the failing migration contract test**

Add this test to `UserMemoryMigrationContractTest`:

```java
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
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dtest=UserMemoryMigrationContractTest' test
```

Expected: FAIL because the V13 resource does not exist.

- [ ] **Step 3: Add the minimal Flyway migration**

Create `V13__add_memory_extraction_observability.sql`:

```sql
ALTER TABLE agent_memory_extraction_task
    ADD COLUMN result_code VARCHAR(40)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '安全完成结果码' AFTER last_error_code,
    ADD COLUMN model_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '模型候选数量' AFTER result_code,
    ADD COLUMN accepted_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '校验通过候选数量' AFTER model_candidate_count,
    ADD COLUMN saved_memory_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '实际写入记忆数量' AFTER accepted_candidate_count,
    ADD CONSTRAINT chk_memory_extraction_result_code CHECK (
        result_code IS NULL OR result_code IN (
            'SAVED', 'MODEL_EMPTY', 'ALL_REJECTED', 'NO_CHANGE',
            'MODEL_PROTOCOL_REJECTED'
        )
    ),
    ADD CONSTRAINT chk_memory_extraction_result_counts CHECK (
        saved_memory_count <= accepted_candidate_count
        AND accepted_candidate_count <= model_candidate_count
    );
```

Historical rows retain `result_code = NULL`; the migration must not infer old outcomes.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same focused command. Expected: `BUILD SUCCESS`, all `UserMemoryMigrationContractTest` tests pass.

- [ ] **Step 5: Commit the migration contract**

```powershell
git add src/main/resources/db/migration/V13__add_memory_extraction_observability.sql src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java
git commit -m "feat: add extraction result columns"
```

### Task 2: Model result classification without recording text

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionResultCode.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/ImplicitMemoryExtractionBatch.java`
- Create: `src/test/java/com/xjjk/agent/memory/domain/ImplicitMemoryExtractionBatchTest.java`

- [ ] **Step 1: Write failing classification tests**

Create `ImplicitMemoryExtractionBatchTest` with tests that assert:

```java
@Test
void classifiesEmptyRejectedSavedAndUnchangedOutcomes() {
    assertThat(ImplicitMemoryExtractionBatch.observed(0, List.of())
            .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.MODEL_EMPTY);
    assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of())
            .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.ALL_REJECTED);
    assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of(candidate()))
            .resultCodeFor(1)).isEqualTo(MemoryExtractionResultCode.SAVED);
    assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of(candidate()))
            .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.NO_CHANGE);
    assertThat(ImplicitMemoryExtractionBatch.protocolRejected()
            .resultCodeFor(0))
            .isEqualTo(MemoryExtractionResultCode.MODEL_PROTOCOL_REJECTED);
}

@Test
void rejectsImpossibleCounts() {
    assertThatThrownBy(() -> ImplicitMemoryExtractionBatch.observed(0, List.of(candidate())))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImplicitMemoryExtractionBatch.observed(1, List.of(candidate()))
            .resultCodeFor(2))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Use a local `candidate()` fixture returning one safe `WORK_COMMON_SCOPE` candidate.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dtest=ImplicitMemoryExtractionBatchTest' test
```

Expected: test compilation fails because the new domain types are absent.

- [ ] **Step 3: Implement the result enum and immutable batch**

Create the enum:

```java
public enum MemoryExtractionResultCode {
    SAVED,
    MODEL_EMPTY,
    ALL_REJECTED,
    NO_CHANGE,
    MODEL_PROTOCOL_REJECTED
}
```

Create `ImplicitMemoryExtractionBatch` as a record containing `modelCandidateCount`, an immutable `acceptedCandidates` list, and the empty-path result code. Its factories must enforce:

```java
public static ImplicitMemoryExtractionBatch observed(
        int modelCandidateCount, List<ImplicitMemoryCandidate> acceptedCandidates) {
    List<ImplicitMemoryCandidate> copy = List.copyOf(acceptedCandidates);
    if (modelCandidateCount < copy.size()) {
        throw new IllegalArgumentException("accepted candidates exceed model candidates");
    }
    MemoryExtractionResultCode emptyCode = copy.isEmpty()
            ? (modelCandidateCount == 0
                ? MemoryExtractionResultCode.MODEL_EMPTY
                : MemoryExtractionResultCode.ALL_REJECTED)
            : null;
    return new ImplicitMemoryExtractionBatch(modelCandidateCount, copy, emptyCode);
}

public static ImplicitMemoryExtractionBatch protocolRejected() {
    return new ImplicitMemoryExtractionBatch(
            0, List.of(), MemoryExtractionResultCode.MODEL_PROTOCOL_REJECTED);
}

public MemoryExtractionResultCode resultCodeFor(int savedCount) {
    if (savedCount < 0 || savedCount > acceptedCandidates.size()) {
        throw new IllegalArgumentException("saved candidates exceed accepted candidates");
    }
    if (savedCount > 0) {
        return MemoryExtractionResultCode.SAVED;
    }
    return acceptedCandidates.isEmpty()
            ? emptyResultCode : MemoryExtractionResultCode.NO_CHANGE;
}
```

The compact constructor must reject negative counts, null lists, null elements, and an invalid/missing empty-path code. No field may hold source text beyond the already validated candidate objects used by the commit transaction.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same focused command. Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the domain classification**

```powershell
git add src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionResultCode.java src/main/java/com/xjjk/agent/memory/domain/ImplicitMemoryExtractionBatch.java src/test/java/com/xjjk/agent/memory/domain/ImplicitMemoryExtractionBatchTest.java
git commit -m "feat: classify extraction outcomes"
```

### Task 3: Persist completion result atomically with memories and Outbox

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/entity/MemoryExtractionTaskEntity.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/MemoryExtractionTaskMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitServiceTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java`

- [ ] **Step 1: Update tests to require result persistence**

Change commit calls to pass `ImplicitMemoryExtractionBatch.observed(...)`. In the successful-save test verify:

```java
verify(taskMapper).completeLease(
        eq(10L), eq("lease-1"), eq("node-1"),
        eq("SAVED"), eq(1), eq(1), eq(1), any());
```

Add a no-change test where explicit precedence prevents insertion and verify:

```java
verify(taskMapper).completeLease(
        eq(10L), eq("lease-1"), eq("node-1"),
        eq("NO_CHANGE"), eq(1), eq(1), eq(0), any());
```

Extend `UserMemorySpringTransactionIntegrationTest` to pass
`ImplicitMemoryExtractionBatch.observed(1, List.of(candidate))`, query the completed task,
and assert `DONE / SAVED / 1 / 1 / 1` for status, result code, model count,
accepted count, and saved count. Retain its hidden-memory and pending-Outbox assertions.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\mvnw.cmd '-Dtest=ImplicitMemoryCommitServiceTest,UserMemorySpringTransactionIntegrationTest' test
```

Expected: compilation failure because the service and mapper still expose their old signatures.

- [ ] **Step 3: Add entity mappings and extend the completion update**

Map the four V13 columns on `MemoryExtractionTaskEntity`. Change `completeLease` to set and accept:

```java
SET status = 'DONE', lease_token = NULL, locked_by = NULL,
    locked_until = NULL, last_error_code = NULL,
    result_code = #{resultCode},
    model_candidate_count = #{modelCandidateCount},
    accepted_candidate_count = #{acceptedCandidateCount},
    saved_memory_count = #{savedMemoryCount},
    updated_at = #{now}
```

Add mapper parameters named exactly `resultCode`, `modelCandidateCount`, `acceptedCandidateCount`, and `savedMemoryCount`.

- [ ] **Step 4: Make the commit service consume the classified batch**

Change the signature to:

```java
public int commit(
        MemoryExtractionTaskClaim claim,
        ImplicitMemoryExtractionBatch batch)
```

Iterate over `deduplicate(batch.acceptedCandidates())`. After writes, derive the code with `batch.resultCodeFor(saved)` and call `completeLease` with model count, original accepted count, and saved count. Keep this call inside the existing `REQUIRES_NEW` transaction after all memory and Outbox writes.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the same focused command. Expected: `BUILD SUCCESS`; the MySQL 8.4 container applies
13 migrations and persists the completion fields in the same transaction as memory and Outbox.

- [ ] **Step 6: Commit atomic result persistence**

```powershell
git add src/main/java/com/xjjk/agent/memory/persistence/entity/MemoryExtractionTaskEntity.java src/main/java/com/xjjk/agent/memory/persistence/mapper/MemoryExtractionTaskMapper.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitServiceTest.java src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java
git commit -m "feat: persist extraction outcomes"
```

### Task 4: Classify Worker outcomes

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorker.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorkerTest.java`

- [ ] **Step 1: Write failing Worker outcome tests**

Update the existing successful test to verify a batch with model count `2` and one accepted candidate. Add tests for:

```java
verify(commitService).commit(claim,
        ImplicitMemoryExtractionBatch.observed(0, List.of()));

verify(commitService).commit(claim,
        ImplicitMemoryExtractionBatch.observed(2, List.of()));

verify(commitService).commit(claim,
        ImplicitMemoryExtractionBatch.protocolRejected());
```

The second case configures two model candidates and makes both validators throw `MEMORY_CONTENT_REJECTED`. The third makes `modelClient.extract` throw `MODEL_PROTOCOL_ERROR`. Retain assertions that timeout/call failures schedule retries and do not commit.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\mvnw.cmd '-Dtest=ImplicitMemoryTaskWorkerTest' test
```

Expected: FAIL because the Worker still passes only the accepted list.

- [ ] **Step 3: Build and submit the classified batch**

On a valid model response, keep `extracted.size()` before filtering and call:

```java
ImplicitMemoryExtractionBatch batch =
        ImplicitMemoryExtractionBatch.observed(extracted.size(), accepted);
int saved = commitService.commit(claim, batch);
```

On `MODEL_PROTOCOL_ERROR`, call `commitService.commit(claim, ImplicitMemoryExtractionBatch.protocolRejected())`. Do not log model output, candidate content, evidence, user content, tenant, or user identifiers.

- [ ] **Step 4: Run focused memory tests and verify GREEN**

```powershell
.\mvnw.cmd '-Dtest=com.xjjk.agent.memory.**' test
```

Expected: `BUILD SUCCESS`, no memory test failures.

- [ ] **Step 5: Commit Worker classification**

```powershell
git add src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorker.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorkerTest.java
git commit -m "feat: record extraction completion reasons"
```

### Task 5: Update operations guidance and run the release regression

**Files:**
- Modify: `docs/runbook/user-memory-foundation-verification.md`

- [ ] **Step 1: Update the runbook query and interpretation**

Replace the task query with:

```sql
SELECT task_id, status, result_code,
       model_candidate_count, accepted_candidate_count, saved_memory_count,
       retry_count, last_error_code, created_at, updated_at
FROM agent_memory_extraction_task
WHERE tenant_id = 1 AND user_id = 74680
ORDER BY id DESC
LIMIT 10;
```

Document the five result codes, clarify that historical rows may have `NULL`, and state that counters contain no message or candidate text.

- [ ] **Step 2: Run migration, memory, and full regression suites**

```powershell
.\mvnw.cmd '-Dtest=UserMemoryMigrationContractTest,ImplicitMemoryExtractionBatchTest,ImplicitMemoryCommitServiceTest,ImplicitMemoryTaskWorkerTest,UserMemorySpringTransactionIntegrationTest' test
.\mvnw.cmd test
git diff --check
```

Expected: both Maven commands end in `BUILD SUCCESS` with zero failures/errors; `git diff --check` emits no errors.

- [ ] **Step 3: Commit the runbook**

```powershell
git add docs/runbook/user-memory-foundation-verification.md
git commit -m "test: verify extraction observability"
```

### Task 6: Runtime verification after merge and restart

**Files:**
- No source changes

- [ ] **Step 1: Merge the verified feature branch into `main` using the approved branch-completion workflow**

Confirm `git status --short` is empty and rerun `git log -1 --oneline` to record the verified feature HEAD before merging.

- [ ] **Step 2: Restart the Agent Server from `main`**

Confirm Flyway reports schema version 13 and `/actuator/health` returns HTTP 200 with `UP`.

- [ ] **Step 3: Send one ordinary statement and inspect the newest task**

Send `我平时主要做 Java 开发。` without “请记住”, wait one polling cycle, then execute the runbook query. The result must now identify exactly one of `SAVED`, `MODEL_EMPTY`, `ALL_REJECTED`, `NO_CHANGE`, or `MODEL_PROTOCOL_REJECTED` with consistent counts.

- [ ] **Step 4: Continue root-cause repair only from the observed result**

If the code is not `SAVED`, open a separate design/fix cycle for the specific model prompt, confidence, protocol, or deterministic policy cause. Do not lower thresholds or relax safety rules without evidence from the new result fields.
