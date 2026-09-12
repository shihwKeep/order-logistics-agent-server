# General Semantic User Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace fixed-sentence implicit memory admission with a general semantic decision, atomic fact, evidence-verification, persistence, and cross-conversation recall pipeline.

**Architecture:** The asynchronous extractor returns a typed decision and atomic fact candidates instead of trusted final prose. A schema registry canonicalizes supported slots and open safe facts, deterministic guards verify provenance and sensitive policy, and a semantic verifier handles open candidates. MySQL remains authoritative; exact predicate lookup and ES/Milvus semantic retrieval are merged and revalidated before answering.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI `ChatClient`, MyBatis-Plus, MySQL 8.4/Flyway, Micrometer, JUnit 5, Mockito, AssertJ, Testcontainers

---

## File Structure

- Create `memory/domain/MemoryDecision.java`, `MemoryExplicitness.java`, `MemoryStability.java`, `MemoryType.java`: bounded semantic protocol enums.
- Create `memory/domain/MemoryFactCandidate.java`, `MemoryExtractionDecision.java`, `ValidatedMemoryFact.java`: untrusted extraction and trusted fact contracts.
- Create `memory/service/MemorySchemaRegistry.java`: predicate registration, value normalization, canonical-key/content generation, direct-query metadata.
- Create `memory/service/MemoryCandidateValidationException.java`: stable rejection reasons without正文.
- Create `memory/service/MemoryEvidenceVerifier.java`, `SpringAiMemoryEvidenceVerifier.java`: independent batch entailment verification for open candidates.
- Modify `SpringAiImplicitMemoryModelClient.java`, `ImplicitMemoryModelClient.java`: general decision/candidate JSON protocol.
- Modify `ImplicitMemoryCandidateValidator.java`, `ImplicitMemoryTaskWorker.java`, `ImplicitMemoryCommitService.java`: structured validation and persistence.
- Create Flyway `V14__add_structured_user_memory_facts.sql`; modify `UserMemoryEntity.java`: structured fact metadata.
- Modify `UserMemoryMetrics.java`, extraction result domain and task migration contract: stage-specific observation.
- Modify explicit semantic extractor/validator/write service: use the same schema registry for semantic SAVE candidates.
- Modify recall mapper/service, direct question types/classifier/renderer and recall gate: exact predicate lookup plus non-exclusive semantic retrieval.
- Modify focused unit, contract, integration and end-to-end tests; add a versioned acceptance matrix.

### Task 1: Introduce the semantic decision and atomic fact contracts

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryDecision.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryExplicitness.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryStability.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryType.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryFactCandidate.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionDecision.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/ValidatedMemoryFact.java`
- Test: `src/test/java/com/xjjk/agent/memory/domain/MemorySemanticDomainTest.java`

- [ ] **Step 1: Write failing domain tests**

Cover `IGNORE`, `SESSION_ONLY`, and `LONG_TERM`; require non-empty bounded candidates only for `LONG_TERM`; reject `LONG_TERM` with `TEMPORARY`/`UNKNOWN` facts; reject blank predicate/value/evidence and non-finite confidence.

```java
MemoryFactCandidate java = new MemoryFactCandidate(
        MemoryType.WORK_CONTEXT, "primary_programming_language", "Java", "Java",
        "我平时用 Java 语言进行开发", MemoryStability.STABLE, 0.96);
assertThat(MemoryExtractionDecision.longTerm(
        MemoryExplicitness.IMPLICIT, List.of(java)).candidates()).containsExactly(java);
MemoryFactCandidate temporary = new MemoryFactCandidate(
        MemoryType.WORK_CONTEXT, "primary_programming_language", "Java", "Java",
        "我今天临时用 Java 修一次问题", MemoryStability.TEMPORARY, 0.96);
assertThatThrownBy(() -> MemoryExtractionDecision.longTerm(
        MemoryExplicitness.IMPLICIT, List.of(temporary)))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=MemorySemanticDomainTest test`

Expected: compilation fails because the new contracts do not exist.

- [ ] **Step 3: Implement immutable contracts**

Use these bounded enums:

```java
enum MemoryDecision { IGNORE, SESSION_ONLY, LONG_TERM }
enum MemoryExplicitness { EXPLICIT, IMPLICIT }
enum MemoryStability { STABLE, TEMPORARY, UNKNOWN }
enum MemoryType {
    PROFILE, COMMUNICATION_PREFERENCE, RESPONSE_PREFERENCE,
    WORK_CONTEXT, STABLE_PREFERENCE, STABLE_USER_FACT
}
```

`MemoryFactCandidate` contains exactly the fields shown in Step 1. `MemoryExtractionDecision` exposes `ignore()`, `sessionOnly()`, and `longTerm(explicitness, candidates)` factories and performs all invariants in its compact constructor. `ValidatedMemoryFact` adds `canonicalKey`, `canonicalContent`, `verificationMethod`, and the original confidence/evidence fields.

- [ ] **Step 4: Run GREEN and commit**

Run: `./mvnw.cmd -Dtest=MemorySemanticDomainTest test`

Commit: `feat: define semantic memory fact contracts`

### Task 2: Add the schema registry and evidence-grounding validator

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/MemorySchemaRegistry.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/MemoryCandidateValidationException.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/MemorySchemaRegistryTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidatorTest.java`

- [ ] **Step 1: Write failing registry and regression tests**

Cover these without whole-sentence grammar:

```java
assertThat(validate("我平时用 Java 语言进行开发", "Java",
        "我平时用 Java 语言进行开发").canonicalKey())
        .isEqualTo("work.primary_programming_language");
assertThat(validate("Java 是我主要使用的开发语言", "Java",
        "Java 是我主要使用的开发语言").canonicalContent())
        .isEqualTo("用户主要使用 Java 进行开发");
assertRejected("我平时用 Java 开发", "Python", Rejection.EVIDENCE);
```

Also cover preferred name, answer language/style, occupation, technology stack, an open safe stable preference, wrong predicate, low confidence, sensitive content, prompt injection, missing evidence, control characters and overlong values.

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=MemorySchemaRegistryTest,ImplicitMemoryCandidateValidatorTest test`

Expected: failures because the existing validator accepts only `MemoryCategory` candidates and calls `isAllowed()`.

- [ ] **Step 3: Implement the registry**

Register stable predicates:

```text
PROFILE/preferred_name -> profile.preferred_name
COMMUNICATION_PREFERENCE/answer_language -> communication.answer_language
RESPONSE_PREFERENCE/answer_style -> response.answer_style
WORK_CONTEXT/occupation -> work.occupation
WORK_CONTEXT/primary_programming_language -> work.primary_programming_language
WORK_CONTEXT/technology_stack -> work.technology_stack
WORK_CONTEXT/common_scope -> work.common_scope
```

The registry must return a `SchemaResolution` containing canonical key/content, value JSON, legacy category/key, verification requirement and default expiry. Known value aliases are value-level normalization only; they never inspect or strip the surrounding sentence. Unknown open facts receive `fact.<type>.<sha256>` keys only after semantic verification.

- [ ] **Step 4: Implement stable validation reasons**

```java
enum Rejection {
    SCHEMA, EVIDENCE, SENSITIVE, STABILITY, CONFIDENCE, UNSUPPORTED
}
```

The validator checks source contains evidence, evidence contains valueEvidence, schema normalization supports value, confidence meets threshold, and every text field passes sensitive and Unicode limits. It returns `ValidatedMemoryFact`; it never calls `MemoryCategoryContentPolicy.isAllowed()`.

- [ ] **Step 5: Run GREEN and commit**

Run: `./mvnw.cmd -Dtest=MemorySchemaRegistryTest,ImplicitMemoryCandidateValidatorTest test`

Commit: `feat: validate semantic memory facts by evidence`

### Task 3: Replace the implicit model protocol

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryModelClient.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/config/ImplicitMemoryProperties.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`

- [ ] **Step 1: Write failing protocol tests**

Require this JSON shape and bounded parsing:

```json
{
  "decision":"LONG_TERM",
  "explicitness":"IMPLICIT",
  "candidates":[{
    "memoryType":"WORK_CONTEXT",
    "predicate":"primary_programming_language",
    "value":"Java",
    "valueEvidence":"Java",
    "evidenceText":"我平时用 Java 语言进行开发",
    "stability":"STABLE",
    "confidence":0.96
  }]
}
```

Verify the prompt defines all three decisions, distinguishes explicit/implicit semantics without literal trigger words, requires atomic facts and prohibits business/sensitive/inferred facts. Verify malformed JSON, unknown enums, invalid action/candidate combinations and timeout produce stable errors.

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=SpringAiImplicitMemoryModelClientTest test`

- [ ] **Step 3: Implement parser and prompt version 2**

Change `extract(Request)` to return `MemoryExtractionDecision`. Parse with private Jackson records, limit candidates by configuration, and construct only domain factories. Set default prompt version to `memory-semantic-v2`; keep model, temperature, timeout and executor properties externally configurable.

- [ ] **Step 4: Run GREEN and commit**

Run: `./mvnw.cmd -Dtest=SpringAiImplicitMemoryModelClientTest test`

Commit: `feat: extract general semantic memory decisions`

### Task 4: Add open-candidate semantic verification and worker orchestration

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/MemoryEvidenceVerifier.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/SpringAiMemoryEvidenceVerifier.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/SpringAiMemoryEvidenceVerifierTest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/config/ImplicitMemoryConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/memory/config/ImplicitMemoryProperties.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorker.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryTaskWorkerTest.java`

- [ ] **Step 1: Write failing verifier and worker tests**

Cover batch results `SUPPORTED`, `CONTRADICTED`, `UNCERTAIN`; verify open candidates are saved only when `SUPPORTED`, stable registered slots skip the second call after deterministic grounding, `IGNORE` and `SESSION_ONLY` complete without memory, and transient verifier failures retry the task.

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=SpringAiMemoryEvidenceVerifierTest,ImplicitMemoryTaskWorkerTest test`

- [ ] **Step 3: Implement bounded verifier**

The verifier receives candidate ids plus source/evidence/category/predicate/value and returns only id/outcome JSON. Use the existing implicit model executor, a dedicated prompt version and the configured timeout. It must not return rewritten content. Treat protocol errors as non-retryable rejection and call/timeout as retryable.

- [ ] **Step 4: Update worker orchestration**

The worker handles decisions, validates candidates, batches only those requiring semantic verification, records rejection reasons, and commits only verified `ValidatedMemoryFact` values. Never log source/evidence/value.

- [ ] **Step 5: Run GREEN and commit**

Run: `./mvnw.cmd -Dtest=SpringAiMemoryEvidenceVerifierTest,ImplicitMemoryTaskWorkerTest test`

Commit: `feat: verify open semantic memory candidates`

### Task 5: Persist structured facts and precise task outcomes

**Files:**
- Create: `src/main/resources/db/migration/V14__add_structured_user_memory_facts.sql`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemoryEntity.java`
- Modify: `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionResultCode.java`
- Modify: `src/main/java/com/xjjk/agent/memory/domain/ImplicitMemoryExtractionBatch.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/MemoryExtractionTaskMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java`
- Modify: related persistence/worker/domain tests.

- [ ] **Step 1: Write failing migration, commit and metrics tests**

Require nullable/backward-compatible columns `schema_version`, `memory_type`, `predicate_name`, `value_json`, `stability`, `verification_method`; check allowed values and indexes on owner/generation/predicate/status. Verify stable predicates supersede by canonical key, explicit records block implicit overwrite, and open facts deduplicate by canonical hash.

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=UserMemoryMigrationContractTest,ImplicitMemoryCommitServiceTest,UserMemoryMetricsTest,ImplicitMemoryExtractionBatchTest test`

- [ ] **Step 3: Implement migration and persistence**

Use Flyway V14 with nullable columns for existing rows and non-null writes for schema version 2. Serialize only registry-produced scalar/list values with the existing `ObjectMapper`; set `content` exclusively from `ValidatedMemoryFact.canonicalContent()`. Add task result/check-constraint values for decision and validation outcomes.

- [ ] **Step 4: Run focused integration tests**

Run: `./mvnw.cmd -Dtest=UserMemoryMySqlIntegrationTest,UserMemorySpringTransactionIntegrationTest,ImplicitMemoryCommitServiceTest test`

Expected: migrations apply through V14; facts and Outbox commit atomically; all tests pass.

- [ ] **Step 5: Commit**

Commit: `feat: persist structured semantic memory facts`

### Task 6: Unify explicit semantic saves with the registry

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java`
- Modify: explicit memory tests.

- [ ] **Step 1: Write failing natural-language explicit tests**

Cover “以后叫我小石”“后面回答先给结论”“从今往后默认中文交流”“请永久记住我主要做供应链后端”。 Verify no literal `请记住` requirement, service-rendered content, successful persistence before acknowledgement, and sensitive/unsupported facts rejected.

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=SpringAiExplicitMemoryExtractorTest,ExplicitMemoryCandidateValidatorTest,ExplicitMemoryCommandServiceTest,ExplicitMemoryWriteServiceTest test`

- [ ] **Step 3: Map SAVE candidates through the same schema registry**

Keep deterministic parsing only as a fast path. Semantic SAVE returns the same `MemoryFactCandidate` fields plus retention; validator resolves through `MemorySchemaRegistry`; write service persists structured columns and visible `USER_EXPLICIT` source. Acknowledgement continues only after transaction commit.

- [ ] **Step 4: Run GREEN and commit**

Run the command from Step 2.

Commit: `feat: unify explicit semantic memory facts`

### Task 7: Add exact predicate recall and non-exclusive semantic retrieval

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/UserMemoryRecallService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/MemoryRecallGate.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionType.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionClassifier.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DeterministicMemoryAnswerRenderer.java`
- Modify: recall/answer tests.

- [ ] **Step 1: Write failing recall tests**

Verify exact predicate lookup answers any safe programming-language value without `Java|Python` regex, prefers explicit over implicit, falls back to legacy category records, and falls through on true miss. Verify a query not matching the old keyword list can still invoke semantic memory retrieval.

- [ ] **Step 2: Run RED**

Run: `./mvnw.cmd -Dtest=DirectMemoryQuestionClassifierTest,DeterministicMemoryAnswerRendererTest,DeterministicUserMemoryAnswerServiceTest,MemoryRecallGateTest,UserMemoryRecallServiceTest test`

- [ ] **Step 3: Implement exact predicate reads**

Add mapper query ordered by explicit source, confidence, updated time and id. Direct question types expose predicate lists instead of one legacy category. Renderer reads structured value JSON and validates through the registry; legacy canonical content is supported only as migration fallback.

- [ ] **Step 4: Make the old gate non-exclusive**

Retain `MemoryRecallGate` as a fast positive signal but do not interpret a miss as final denial. For users with active long-term memory, bounded ES/Milvus retrieval may run for other safe queries; business-only queries remain excluded unless they also ask about user preferences.

- [ ] **Step 5: Run GREEN and commit**

Run the command from Step 2.

Commit: `feat: recall semantic memory across conversations`

### Task 8: Add production acceptance coverage and verification documentation

**Files:**
- Create: `src/test/java/com/xjjk/agent/memory/MemorySemanticAcceptanceTest.java`
- Create: `docs/testing/general-semantic-memory-evaluation.md`
- Modify: `docs/runbook/user-memory-index-recall-verification.md`

- [ ] **Step 1: Add a table-driven acceptance matrix**

Include paraphrases across profile, communication, response, work and stable preference types; negatives for transient/business/sensitive/inferred content; conflicting updates; explicit priority; same conversation, new conversation and restart-equivalent MySQL reads.

- [ ] **Step 2: Run the memory suite**

Run: `./mvnw.cmd -Dtest='com.xjjk.agent.memory.**,ChatContextWithUserMemoryTest,ChatTurnRunnerDeterministicMemoryTest,ChatTurnRunnerExplicitMemoryTest' test`

Expected: all memory tests pass with no failures.

- [ ] **Step 3: Run full verification**

Run: `./mvnw.cmd test`

Expected: all project tests pass, including Testcontainers migrations through V14.

- [ ] **Step 4: Verify the original symptom end to end**

With required local services running, send a stable fact in conversation A, wait for `SAVED`, create conversation B, ask a semantically equivalent question, and verify the response plus MySQL/outbox/index state. Repeat with an unrelated stable preference and a forbidden business fact.

- [ ] **Step 5: Commit**

Commit: `test: verify general semantic cross-conversation memory`

### Task 9: Review, merge, and verify main

- [ ] **Step 1: Review the full diff against the approved design**

Check for fixed-sentence admission, user-content logging, model-controlled keys/content, cross-user queries, missing result codes, and compatibility gaps.

- [ ] **Step 2: Run `git diff --check` and fresh full tests**

Run: `git diff --check` and `./mvnw.cmd test`.

- [ ] **Step 3: Merge locally to `main`**

Use a non-destructive local merge, preserving unrelated user changes. Do not pull, push or reset.

- [ ] **Step 4: Re-run full tests from merged `main`**

Run: `./mvnw.cmd test`.

- [ ] **Step 5: Report required runtime restart and concrete acceptance steps**

The Agent Server must restart because the model protocol, beans and Flyway migration changed. Historical rejected tasks are not replayed automatically; validation uses new messages after restart.
