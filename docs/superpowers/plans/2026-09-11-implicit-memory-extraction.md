# Implicit User Memory Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist safe, hidden, automatically extracted user preferences after successful chat turns without blocking the chat path, while enforcing user settings, generation invalidation, suppression, expiry, and durable index Outbox creation.

**Architecture:** `ChatTurnFinishService` only registers an idempotent extraction task inside its existing success transaction. A leased worker later loads the authoritative user message, calls an isolated structured-output model client, validates candidates in Java, and commits accepted `AUTO_EXTRACT/HIDDEN/NORMAL` memories plus Outbox rows in a short owner-scoped transaction. A separate expiry worker logically expires old automatic memories and emits delete Outbox rows; ES/Milvus consumers and recall remain outside this phase.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI `ChatClient`, MyBatis-Plus, MySQL 8/Flyway, Micrometer, JUnit 5, AssertJ, Mockito, Testcontainers.

---

## Scope and invariants

- MySQL remains the only source of truth.
- Scheduling occurs only after a `SUCCESS` assistant result and in the same transaction as chat finalization.
- Explicit-memory-only turns, deletion/clear/settings turns, disabled memory, and disabled auto extraction do not create tasks.
- The model never supplies owner, visibility, retention, status, generation, version, timestamps, or expiry.
- Candidate evidence must be present in the authoritative user message, categories remain closed, and sensitive/business data is rejected.
- Every task claim and commit is tenant/user/generation scoped and protected by a lease token.
- Suppressed canonical keys are not re-created by automatic extraction.
- Active explicit memory wins over an automatic candidate with the same canonical key.
- Automatic memories are hidden, non-permanent, and expire after the configured number of days.
- Logs and metrics contain identifiers, bounded error codes, and counts only—never message, evidence, or memory text.
- This plan does not call ES or Milvus and does not inject memories into chat prompts.

## File structure

- `src/main/resources/db/migration/V12__create_memory_extraction_task.sql`: durable extraction-task state machine.
- `src/main/java/com/xjjk/agent/memory/config/UserMemoryProperties.java`: extraction, worker, retry, and expiry settings.
- `src/main/java/com/xjjk/agent/memory/config/ImplicitMemoryConfiguration.java`: isolated bounded model and worker executors.
- `src/main/java/com/xjjk/agent/memory/domain/ImplicitMemoryCandidate.java`: untrusted model candidate.
- `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionTaskClaim.java`: immutable leased-work descriptor without message text.
- `src/main/java/com/xjjk/agent/memory/persistence/entity/MemoryExtractionTaskEntity.java`: task persistence row.
- `src/main/java/com/xjjk/agent/memory/persistence/mapper/MemoryExtractionTaskMapper.java`: idempotent schedule, claim, lease completion, retry, cancel, and recovery SQL.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskScheduler.java`: mandatory-transaction task registration.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryModelClient.java`: structured extraction boundary.
- `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`: bounded Spring AI adapter.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java`: fail-closed Java validation.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java`: settings/generation/suppression recheck and atomic memory/Outbox commit.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorker.java`: load, extract, validate, commit, and retry orchestration.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryPoller.java`: scheduled leased-work dispatch.
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryRecoveryScheduler.java`: expired-lease recovery and disabled-task cancellation.
- `src/main/java/com/xjjk/agent/memory/service/UserMemoryExpiryService.java`: batch logical expiry and delete Outbox creation.
- `src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java`: low-cardinality extraction and expiry metrics.
- `src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java`: success-only task scheduling hook.
- `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`: exact-key, expiry, and owner-scoped persistence operations.
- `src/main/java/com/xjjk/agent/memory/persistence/mapper/MemorySuppressionMapper.java`: active suppression lookup.
- `src/main/resources/application.properties`: safe local defaults; Nacos overrides in deployed environments.

### Task 1: Add the durable extraction task schema

**Files:**
- Create: `src/main/resources/db/migration/V12__create_memory_extraction_task.sql`
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMySqlIntegrationTest.java`

- [ ] **Step 1: Write the failing migration contract test**

Assert V12 creates `agent_memory_extraction_task` with owner, source turn, generation, status/retry, lease, safe error, and timestamps; require a unique key over `(tenant_id,user_id,conversation_id,request_id)` and claim/lease indexes.

```java
assertThat(sql).contains(
        "CREATE TABLE agent_memory_extraction_task",
        "UNIQUE KEY uk_memory_extraction_request",
        "KEY idx_memory_extraction_claim",
        "KEY idx_memory_extraction_lease");
```

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dtest=UserMemoryMigrationContractTest test
```

Expected: FAIL because V12 is absent.

- [ ] **Step 3: Create V12**

The table contains `task_id`, owner IDs, `conversation_id`, `request_id`, `user_message_id`, `user_message_sequence`, `memory_generation`, `status`, `retry_count`, `next_run_at`, lease fields, `last_error_code`, and millisecond timestamps. Allowed states are `PENDING`, `PROCESSING`, `RETRY`, `DONE`, `CANCELLED`, and `DEAD` by application enum; do not persist any message or candidate text in the task table.

- [ ] **Step 4: Verify fresh and upgrade migrations**

Extend the Testcontainers integration test to assert V12 on an empty schema and an upgrade from V11, including duplicate request idempotency at the database level.

```powershell
.\mvnw.cmd -Dtest=UserMemoryMigrationContractTest,UserMemoryMySqlIntegrationTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/db/migration/V12__create_memory_extraction_task.sql src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMySqlIntegrationTest.java
git commit -m "feat: add implicit memory extraction task schema"
```

### Task 2: Add validated extraction and worker configuration

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/config/UserMemoryProperties.java`
- Create: `src/main/java/com/xjjk/agent/memory/config/ImplicitMemoryConfiguration.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/xjjk/agent/memory/config/UserMemoryPropertiesTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/config/ImplicitMemoryConfigurationTest.java`

- [ ] **Step 1: Write failing property tests**

Cover confidence `[0,1]`, positive expiry days, batch sizes, lease duration, polling/recovery intervals, retry attempts, bounded backoff, executor sizes, prompt version, model, timeout, and max candidate count. Reject invalid values at construction.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dtest=UserMemoryPropertiesTest,ImplicitMemoryConfigurationTest test
```

Expected: compilation or assertion failure because the extraction properties and executors do not exist.

- [ ] **Step 3: Add production-safe defaults**

Use these local defaults, all overridable by Nacos:

```properties
agent.memory.auto-extract-confidence-threshold=0.85
agent.memory.auto-extract-expire-days=180
agent.memory.auto-extract-max-candidates=3
agent.memory.auto-extract-prompt-version=memory-auto-v1
agent.memory.auto-extract-model=qwen-plus
agent.memory.auto-extract-temperature=0.0
agent.memory.auto-extract-timeout=10s
agent.memory.extraction-worker.poll-interval=2s
agent.memory.extraction-worker.recovery-interval=30s
agent.memory.extraction-worker.claim-batch-size=10
agent.memory.extraction-worker.lease-duration=60s
agent.memory.extraction-worker.max-attempts=5
agent.memory.extraction-worker.initial-backoff=2s
agent.memory.extraction-worker.max-backoff=5m
agent.memory.extraction-worker.pool-size=2
agent.memory.extraction-worker.queue-capacity=100
agent.memory.expiry.poll-interval=10m
agent.memory.expiry.batch-size=100
```

- [ ] **Step 4: Add isolated bounded executors**

Create one bounded `ThreadPoolTaskExecutor` for task processing and one bounded `ThreadPoolExecutor` for model calls. Prefix thread names `memory-extract-worker-` and `memory-extract-model-`; rejection must surface to the poller/adapter, not use the caller thread.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=UserMemoryPropertiesTest,ImplicitMemoryConfigurationTest test
git add src/main/java/com/xjjk/agent/memory/config src/main/resources/application.properties src/test/java/com/xjjk/agent/memory/config
git commit -m "feat: configure implicit memory extraction"
```

### Task 3: Implement task scheduling and lease persistence

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionTaskStatus.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionTaskClaim.java`
- Create: `src/main/java/com/xjjk/agent/memory/persistence/entity/MemoryExtractionTaskEntity.java`
- Create: `src/main/java/com/xjjk/agent/memory/persistence/mapper/MemoryExtractionTaskMapper.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskScheduler.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskSchedulerTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/persistence/MemoryExtractionTaskMapperContractTest.java`

- [ ] **Step 1: Write failing scheduling tests**

```java
scheduler.request(turn, 17L);
verify(taskMapper).insertRequested(
        turn.tenantId(), turn.userId(), turn.conversationId(),
        turn.requestId(), turn.userMessageId(), 17L, 4L, anyString(), any());
```

Assert the scheduler inserts only when the locked owner setting has both `memory_enabled` and `auto_extract_enabled`, uses its current generation, and treats duplicate request insertion as idempotent. A missing source sequence or owner setting inconsistency fails the enclosing transaction.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dtest=ImplicitMemoryTaskSchedulerTest,MemoryExtractionTaskMapperContractTest test
```

Expected: compilation failure.

- [ ] **Step 3: Implement owner-scoped task SQL**

Provide SQL for `INSERT ... SELECT` guarded by the owned user message and locked setting; claim via `FOR UPDATE SKIP LOCKED`; transition only with matching `id + task_id + lease_token + locked_by`; recover expired leases; and cancel pending/retry tasks when memory or auto extraction is disabled or the generation no longer matches.

- [ ] **Step 4: Keep transaction boundaries explicit**

`ImplicitMemoryTaskScheduler.request(...)` uses `Propagation.MANDATORY`. Claim, retry, completion, cancellation, and recovery operations are exposed through a later commit service using `REQUIRES_NEW`; no model call occurs inside a transaction.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=ImplicitMemoryTaskSchedulerTest,MemoryExtractionTaskMapperContractTest test
git add src/main/java/com/xjjk/agent/memory/domain src/main/java/com/xjjk/agent/memory/persistence src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskScheduler.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskSchedulerTest.java src/test/java/com/xjjk/agent/memory/persistence/MemoryExtractionTaskMapperContractTest.java
git commit -m "feat: schedule leased memory extraction tasks"
```

### Task 4: Register tasks atomically after successful chat turns

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/turn/ChatTurnStructuredResultFinishTest.java`
- Create: `src/test/java/com/xjjk/agent/chat/service/turn/ChatTurnImplicitMemorySchedulingTest.java`

- [ ] **Step 1: Write the failing integration-facing unit test**

Assert a normal successful answer invokes `ImplicitMemoryTaskScheduler.request(turn, userSequence)` after both messages and the conversation cursor are validated, while failed/cancelled/time-out turns do not. Assert finish rolls back when task registration fails.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dtest=ChatTurnImplicitMemorySchedulingTest test
```

Expected: FAIL because no extraction task is registered.

- [ ] **Step 3: Add the success-only hook**

Schedule after the conversation update and summary-task registration, but before returning from the transaction. The scheduler itself applies memory/auto-extract gates. Exclude `MEMORY_SAVED`, `MEMORY_REJECTED`, memory-setting, and memory-deletion finish reasons so memory management commands cannot recursively become hidden memory.

- [ ] **Step 4: Update constructor-based tests and verify**

Supply a mocked scheduler in every direct `ChatTurnFinishService` construction and default it to no-op.

```powershell
.\mvnw.cmd -Dtest=ChatTurnImplicitMemorySchedulingTest,ChatTurnStructuredResultFinishTest,ChatTurnRunnerExplicitMemoryTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/service/turn/ChatTurnFinishService.java src/test/java/com/xjjk/agent/chat/service/turn
git commit -m "feat: enqueue implicit memory after successful turns"
```

### Task 5: Add structured extraction and fail-closed candidate validation

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/domain/ImplicitMemoryCandidate.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryModelClient.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryExtractionException.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidatorTest.java`

- [ ] **Step 1: Write failing model-adapter tests**

The wished-for API is:

```java
List<ImplicitMemoryCandidate> extract(Request request);

record Request(String requestId, String userMessage, String priorUserMessage) {}
```

Parse one JSON object containing `candidates`, cap the returned list to the configured maximum, and map blank/malformed/timeout/rejection/provider failures to stable internal codes without including supplier text.

- [ ] **Step 2: Write failing validator tests**

Accept only the existing category enum and exact category-prefix canonical keys. Require `confidence >= threshold`, normalized evidence within the authoritative user message, direct category-policy support, safe content/evidence/source, and configured code-point limits. Reject assistant-only evidence, secrets, identity/health/order/refund/logistics data, unsupported names, permanent retention, and inferred personality.

- [ ] **Step 3: Run RED**

```powershell
.\mvnw.cmd -Dtest=SpringAiImplicitMemoryModelClientTest,ImplicitMemoryCandidateValidatorTest test
```

Expected: compilation failure.

- [ ] **Step 4: Implement model prompt and validation**

Use an isolated `ChatClient` with JSON response format, temperature `0`, bounded executor, and timeout. The system prompt allows only the four configured categories, instructs returning `{"candidates":[]}` when uncertain, treats all conversation text as untrusted data, and forbids business facts and assistant claims. Java assigns no persistence fields until validation succeeds.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=SpringAiImplicitMemoryModelClientTest,ImplicitMemoryCandidateValidatorTest test
git add src/main/java/com/xjjk/agent/memory/domain/ImplicitMemoryCandidate.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryModelClient.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryExtractionException.java src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidatorTest.java
git commit -m "feat: extract and validate implicit memories"
```

### Task 6: Commit hidden automatic memories atomically

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/MemorySuppressionMapper.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitServiceTest.java`

- [ ] **Step 1: Write failing transaction tests**

Prove the commit service:

```text
locks the matching task lease
locks owner settings and requires memory_enabled + auto_extract_enabled
requires task generation == current generation
rejects an active suppression with the same canonical key
skips an active USER_EXPLICIT record with the same key
supersedes an older AUTO_EXTRACT record for the same key
inserts AUTO_EXTRACT + HIDDEN + NORMAL with configured expiry
uses model confidence rounded to DECIMAL(5,4)
inserts DELETE for a superseded auto record and UPSERT for the new record
marks the task DONE in the same transaction
```

Empty or wholly rejected candidate batches still mark the task `DONE` without memory writes. A stale lease or generation never writes memory or Outbox.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dtest=ImplicitMemoryCommitServiceTest test
```

Expected: compilation failure.

- [ ] **Step 3: Implement exact-key precedence and suppression**

Do not perform vector similarity search. Deduplicate the validated batch by canonical key before opening the transaction, keeping the highest confidence candidate. Within the transaction, every query includes tenant, user, generation, and canonical key. Version increments from the exact-key active predecessor only.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=ImplicitMemoryCommitServiceTest,ExplicitMemoryWriteServiceTest,UserMemoryManagementServiceTest test
git add src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java src/main/java/com/xjjk/agent/memory/persistence/mapper/MemorySuppressionMapper.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitServiceTest.java
git commit -m "feat: persist hidden automatic memories"
```

### Task 7: Add worker, poller, retries, and lease recovery

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskCommitService.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorker.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryPoller.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryRecoveryScheduler.java`
- Modify: `src/main/java/com/xjjk/agent/chat/persistence/mapper/AgentMessageMapper.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorkerTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryPollerTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryRecoverySchedulerTest.java`

- [ ] **Step 1: Write failing worker tests**

The worker loads the authoritative owned `SUCCESS` user message by message ID and sequence, optionally one previous successful user message for pronoun resolution, calls the model outside a transaction, validates each candidate, and passes only validated candidates to the commit service. Missing/stale source data cancels the task; transient model failures retry with capped exponential backoff; deterministic invalid output completes without memories; exhausted attempts become `DEAD`.

- [ ] **Step 2: Write failing dispatch/recovery tests**

Poller disabled state must not access MySQL. Executor rejection immediately releases the lease to retry. Recovery turns expired `PROCESSING` leases into `RETRY` or `DEAD`, and cancels unclaimed tasks whose setting is disabled or generation is stale.

- [ ] **Step 3: Run RED**

```powershell
.\mvnw.cmd -Dtest=ImplicitMemoryTaskWorkerTest,ImplicitMemoryPollerTest,ImplicitMemoryRecoverySchedulerTest test
```

Expected: compilation failure.

- [ ] **Step 4: Implement orchestration and safe observability**

No model call, message text, candidate text, or evidence exists inside commit/retry transactions or logs. Log only task ID, conversation ID, result, attempt, and bounded error code. Catch candidate validation failures per candidate so one rejected candidate does not discard unrelated safe candidates.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=ImplicitMemoryTaskWorkerTest,ImplicitMemoryPollerTest,ImplicitMemoryRecoverySchedulerTest test
git add src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskCommitService.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorker.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryPoller.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryRecoveryScheduler.java src/main/java/com/xjjk/agent/chat/persistence/mapper/AgentMessageMapper.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorkerTest.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryPollerTest.java src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryRecoverySchedulerTest.java
git commit -m "feat: process implicit memory tasks"
```

### Task 8: Expire automatic memories safely

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/UserMemoryExpiryService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/UserMemoryExpiryServiceTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/observation/UserMemoryMetricsTest.java`

- [ ] **Step 1: Write failing expiry tests**

Claim only `ACTIVE + AUTO_EXTRACT + NORMAL + expires_at <= now` rows with `FOR UPDATE SKIP LOCKED`, mark each `EXPIRED`, and insert an idempotent delete Outbox row in the same transaction. Permanent and explicit memories are never selected. A partial insert failure rolls back the whole claimed batch.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dtest=UserMemoryExpiryServiceTest,UserMemoryMetricsTest test
```

Expected: compilation or assertion failure.

- [ ] **Step 3: Implement bounded scheduled expiry**

Run only when the global feature is enabled. Process at most the configured batch per transaction and report low-cardinality counts. Use memory ID/version/generation in Outbox and never log content.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=UserMemoryExpiryServiceTest,UserMemoryMetricsTest test
git add src/main/java/com/xjjk/agent/memory/service/UserMemoryExpiryService.java src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java src/test/java/com/xjjk/agent/memory/service/UserMemoryExpiryServiceTest.java src/test/java/com/xjjk/agent/memory/observation/UserMemoryMetricsTest.java
git commit -m "feat: expire automatic user memories"
```

### Task 9: Verify transactional behavior and document operations

**Files:**
- Create: `src/test/java/com/xjjk/agent/memory/persistence/ImplicitMemorySpringTransactionIntegrationTest.java`
- Modify: `docs/runbook/user-memory-foundation-verification.md`

- [ ] **Step 1: Add MySQL transaction integration coverage**

Using MySQL 8 Testcontainers, verify success finalization and task insert commit together; task insertion failure rolls back the assistant result; stale generation and disabled auto extraction prevent commit; suppression prevents recreation; clear-all cancels old-generation work; automatic expiry writes matching delete Outbox.

- [ ] **Step 2: Run all memory and chat-finalization tests**

```powershell
.\mvnw.cmd -Dtest="com.xjjk.agent.memory.**,com.xjjk.agent.chat.service.turn.**" test
```

Expected: all selected tests PASS.

- [ ] **Step 3: Update the runbook**

Document Nacos settings, expected task state transitions, SQL checks that avoid displaying `content`/`evidence_text`, switch-off behavior, retry/dead recovery, generation invalidation, suppression, and expiry. State clearly that ES/Milvus synchronization and chat recall are not delivered by this phase.

- [ ] **Step 4: Run the complete verification gate**

```powershell
.\mvnw.cmd test
git diff --check
git status --short
```

Expected: `BUILD SUCCESS`, zero failures/errors, no diff-check output, and only intentional phase files in status.

- [ ] **Step 5: Commit**

```powershell
git add src/test/java/com/xjjk/agent/memory/persistence/ImplicitMemorySpringTransactionIntegrationTest.java docs/runbook/user-memory-foundation-verification.md
git commit -m "docs: verify implicit memory extraction"
```

## Completion gate

Do not merge this phase until all of the following have fresh evidence:

- V12 migrates both empty and V11 schemas on MySQL 8.
- A successful normal chat turn commits exactly one idempotent task; unsuccessful and memory-management turns commit none.
- Model extraction happens only in the background and outside database transactions.
- Settings and generation are checked during scheduling and again during commit.
- Hidden candidates cannot bypass category, evidence, sensitive-content, suppression, explicit-precedence, or ownership checks.
- Automatic memories are `AUTO_EXTRACT/HIDDEN/NORMAL`, expire at 180 days by default, and emit durable Outbox rows.
- Clear-all and switch-off make queued/stale work unable to write.
- Logs and metrics contain no message, evidence, or memory body.
- The full Maven suite passes.

The next independent phase is Knowledge Service ES/Milvus memory indexing and hybrid search. Cross-session recall and prompt injection follow only after that index contract is implemented and verified.
