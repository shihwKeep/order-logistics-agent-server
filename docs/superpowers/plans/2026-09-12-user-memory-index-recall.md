# User Memory Index and Cross-Conversation Recall Implementation Plan

> **Execution rule:** implement task-by-task with test-driven development. Do not declare cross-conversation memory complete until both repositories and the real dependency smoke test pass.

**Goal:** Consume durable user-memory Outbox events into isolated Elasticsearch and Milvus indexes, retrieve relevant memories in a new conversation, revalidate every candidate against Agent MySQL, and inject only safe bounded memory context into the model.

**Architecture:** Agent Server remains the authority for identity, settings, generation, lifecycle and content. It calls signed Knowledge Service endpoints for idempotent indexing and candidate-only hybrid retrieval. Knowledge Service owns isolated memory indexes and returns IDs plus ranking signals; Agent performs the final owner-scoped MySQL read, suppression/conflict filtering and low-privilege context rendering.

**Tech stack:** Java 21, Spring Boot 3.5, Spring AI, OpenFeign, MyBatis-Plus, MySQL 8/Flyway, Elasticsearch 8, Milvus Java SDK, BGE reranker, Micrometer, JUnit 5, AssertJ, Mockito, Testcontainers.

**Repositories:**

- Agent: `D:\GitCode\order-logistics-agent-server`
- Knowledge: `D:\GitCode\order-logistics-knowledge-service`

---

## Invariants

- MySQL in Agent is the only source of truth.
- No internal API accepts an owner wider than the authenticated Agent request headers.
- Every memory search filters tenant, user and generation in both ES and Milvus.
- Knowledge retrieval returns no memory content.
- Every candidate must pass Agent MySQL owner/status/version/expiry/suppression validation.
- Memory context is a bounded untrusted USER message and cannot override the current turn, system prompt, tools or knowledge evidence.
- Remote calls never run inside an Agent database transaction.
- Outbox operations are idempotent and recoverable after partial ES/Milvus success.
- Degraded memory retrieval never blocks ordinary chat.
- Logs, metrics and task errors never contain memory content.

## Checkpoint A — Knowledge Service memory index and retrieval

### Task 1: Add memory-specific configuration and domain contracts

**Knowledge files:**

- Create `src/main/java/com/xjjk/knowledge/usermemory/config/UserMemoryIndexProperties.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/domain/MemoryIndexDocument.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/domain/MemoryIndexOperation.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/domain/MemoryRecallCandidate.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/domain/MemoryRetrievalResult.java`
- Modify `src/main/resources/application.properties`
- Create matching tests under `src/test/java/com/xjjk/knowledge/usermemory/...`

- [ ] Write property-validation and domain-invariant tests first.
- [ ] Verify RED with `mvnw.cmd -Dtest=UserMemoryIndexPropertiesTest,MemoryIndexDocumentTest test`.
- [ ] Add fail-safe-disabled defaults and validate names, positive Top K, weights, thresholds, timeouts and embedding dimension compatibility.
- [ ] Keep DTO/domain fields bounded and reject invalid owner IDs, generations, versions and blank IDs/content.
- [ ] Run the focused tests and commit `feat: add user memory index contracts`.

### Task 2: Add path-bound HMAC verification for memory APIs

**Knowledge files:**

- Refactor `src/main/java/com/xjjk/knowledge/retrieval/web/InternalRequestVerifier.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/web/MemoryInternalRequestCanonicalizer.java`
- Create tests for both existing knowledge retrieval and new memory paths.

- [ ] Add failing tests proving a signature for one path cannot authorize another path, tampered generation/body fails, stale timestamps fail and nonce reuse fails.
- [ ] Preserve the current knowledge retrieval canonical form for backward compatibility.
- [ ] Add memory canonical forms using payload hashes; compare signatures in constant time and consume nonce only after signature validation.
- [ ] Run all internal-auth tests and commit `feat: secure user memory internal APIs`.

### Task 3: Implement isolated Elasticsearch memory storage

**Knowledge files:**

- Create `src/main/java/com/xjjk/knowledge/usermemory/index/MemoryKeywordIndex.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/index/ElasticsearchMemoryKeywordIndex.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/index/MemoryIndexUnavailableException.java`
- Create focused HTTP-fixture tests.

- [ ] Write failing tests for index bootstrap, owner/generation filters, expiry filter, upsert, single delete and source/generation scope delete.
- [ ] Create the physical v1 mapping and stable alias without touching knowledge-document indexes.
- [ ] Use `memory_id` as document ID and store only the fields in the approved design.
- [ ] Ensure query JSON always contains tenant, user and generation filters, even when content is empty or special characters are present.
- [ ] Run tests and commit `feat: add elasticsearch user memory index`.

### Task 4: Implement isolated Milvus memory storage

**Knowledge files:**

- Create `src/main/java/com/xjjk/knowledge/usermemory/index/MemoryVectorIndex.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/index/MilvusMemoryVectorIndex.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/index/MemoryMilvusGateway.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/index/SdkMemoryMilvusGateway.java`
- Create unavailable adapter and focused tests.

- [ ] Write failing tests for collection schema, embedding dimension, scalar filters, upsert, delete and source/generation clear.
- [ ] Create `agent_user_memory_v1` with `memory_id` primary key and required owner/generation/version/source/content/expiry fields.
- [ ] Require tenant, user and generation in every search expression; escape scalar expressions safely.
- [ ] Reuse the existing embedding client but not the document `IndexChunk` schema.
- [ ] Run tests and commit `feat: add milvus user memory index`.

### Task 5: Add idempotent index-event API

**Knowledge files:**

- Create `src/main/java/com/xjjk/knowledge/usermemory/service/UserMemoryIndexService.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/web/InternalUserMemoryController.java`
- Create request/response DTOs and controller/service tests.

- [ ] Write failing tests for `UPSERT`, `DELETE`, `DELETE_EXPLICIT_SCOPE`, `CLEAR_GENERATION`, malformed field combinations and one-store failure.
- [ ] Verify HMAC before invoking the service.
- [ ] Execute both stores; report success only after both operations complete. Repeated operations must converge.
- [ ] Return stable low-cardinality result codes without content.
- [ ] Run focused tests and commit `feat: expose user memory index events`.

### Task 6: Add hybrid memory retrieval API

**Knowledge files:**

- Create `src/main/java/com/xjjk/knowledge/usermemory/service/UserMemoryRetrievalService.java`
- Create `src/main/java/com/xjjk/knowledge/usermemory/fusion/MemoryRrfFusion.java` or reuse the generic RRF after proving type safety.
- Extend the internal controller and DTOs.
- Add retrieval metrics and tests.

- [ ] Write failing tests for dual recall, RRF ordering, optional BGE rerank, lexical-only/vector-only degradation, all-unavailable empty result and result content omission.
- [ ] Run ES and Milvus in parallel with bounded executor/timeouts.
- [ ] Apply memory-specific thresholds and Top K; do not inherit the knowledge-answerability policy implicitly.
- [ ] Return candidate IDs, versions, scores, sources, degradation and strategy only.
- [ ] Run all Knowledge unit tests: `mvnw.cmd test`.
- [ ] Commit `feat: add hybrid user memory retrieval`.

### Checkpoint A verification

- [ ] Start Elasticsearch, Milvus, embedding and reranker dependencies.
- [ ] Run signed index and retrieval API smoke tests for two users in the same tenant and two tenants.
- [ ] Prove cross-user and cross-tenant queries return zero.
- [ ] Record the exact Nacos properties needed by Knowledge Service.

## Checkpoint B — Agent Outbox delivery

### Task 7: Add signed Knowledge memory client

**Agent files:**

- Extend `src/main/java/com/xjjk/agent/knowledge/client/KnowledgeClient.java` or add a dedicated `UserMemoryKnowledgeClient.java`.
- Create `src/main/java/com/xjjk/agent/memory/index/MemoryIndexGateway.java` and Feign adapter.
- Refactor `KnowledgeRequestSigner` into path-specific canonical operations without changing existing knowledge signatures.
- Add signer, gateway and DTO tests.

- [ ] Write RED tests for exact canonical payloads, unique nonce, tamper detection fixtures and candidate responses without content.
- [ ] Reuse `integration.knowledge.internal-secret` and existing timeout policy.
- [ ] Keep tenant/user derived only from server-owned Outbox claims or `AgentIdentity`.
- [ ] Run focused tests and commit `feat: add signed memory index gateway`.

### Task 8: Implement leased Outbox state machine

**Agent files:**

- Create `src/main/java/com/xjjk/agent/memory/config/MemoryIndexWorkerProperties.java`
- Extend `MemoryOutboxMapper.java` with claim/lease/CAS/recovery SQL.
- Add `MemoryOutboxClaim` domain record.
- Create `MemoryIndexOutboxService`, poller, recovery scheduler and bounded executor configuration.
- Add mapper/service/config tests and, where available, MySQL integration coverage.

- [ ] Write failing tests for ordered claim, `SKIP LOCKED`, lease ownership, success CAS, retry backoff, DEAD transition and expired lease recovery.
- [ ] Claim in a short transaction, call Knowledge outside the transaction, and complete with lease-token CAS.
- [ ] For `UPSERT`, reload authoritative memory; stale/missing/inactive/expired rows resolve to DELETE.
- [ ] Never log content or evidence.
- [ ] Run focused tests and commit `feat: deliver user memory outbox events`.

### Checkpoint B verification

- [ ] Create an explicit and implicit memory and wait until their Outbox events become `DONE`.
- [ ] Query ES and Milvus directly to confirm both contain only the correct owner/generation records.
- [ ] Delete/clear and prove both indexes converge after retries.

## Checkpoint C — Agent authoritative recall and context injection

### Task 9: Implement recall gate and Knowledge recall gateway

**Agent files:**

- Create `src/main/java/com/xjjk/agent/memory/recall/MemoryRecallGate.java`
- Create recall request/result domain records and gateway adapter.
- Add `MemoryRetrievalProperties` and tests.

- [ ] Write RED tests for first-person preference/history questions, category keywords, unrelated business queries and disabled settings.
- [ ] Directly load bounded global explicit categories; invoke semantic recall only when the deterministic gate matches.
- [ ] Treat Knowledge errors as an empty semantic candidate list.
- [ ] Run focused tests and commit `feat: add user memory recall gate`.

### Task 10: Implement authoritative MySQL selection and governance

**Agent files:**

- Extend `UserMemoryMapper.java` with bounded global and candidate-ID queries.
- Extend `MemorySuppressionMapper.java` with bounded active-key query.
- Create `UserMemoryRecallService`, `MemoryCandidateSelector` and tests.

- [ ] Write RED tests for owner/generation/status/version/expiry validation, suppression, explicit-over-implicit, same-key dedupe, text similarity dedupe, unsafe conflict drop and final Top K.
- [ ] Query by candidate IDs and exact current owner/generation; never query by IDs alone.
- [ ] If settings or candidate validation reads fail, return no memories and continue chat.
- [ ] Preserve deterministic ordering and immutable results.
- [ ] Run mapper/service tests, including MySQL integration if Docker is available, and commit `feat: validate recalled memories against mysql`.

### Task 11: Render and budget untrusted memory context

**Agent files:**

- Create `src/main/java/com/xjjk/agent/memory/recall/UserMemoryContextRenderer.java`
- Add memory context fields to `ChatContextSelection.java`.
- Extend `QwenChatTokenEstimator.java`, `ChatContextSelector.java`, `ChatContextPreparationService.java`, `RequestChatMemory.java` and `AiChatService.java`.
- Add/modify all affected chat-context tests.

- [ ] Write RED tests for delimiter escaping, length/entry limits, enhanced system policy, token estimate parity and budget rejection.
- [ ] Load recall best-effort in context preparation after identity-scoped snapshot creation.
- [ ] Preserve summary, recent raw history and business reference before attempting memory inclusion.
- [ ] Insert memory as one USER message before raw turns.
- [ ] Use exactly the same enhanced system prompt in token estimation and the actual model request.
- [ ] Run focused tests and commit `feat: inject safe cross-conversation memory context`.

### Task 12: Observability, configuration and regression

**Both repositories:**

- Add low-cardinality metrics for Outbox status, index operations, recall degradation, candidate counts and MySQL rejection reasons.
- Update `application.properties`, `.env.example` where appropriate, and create a runbook under Agent `docs/runbook/`.
- Add contract tests proving all documented properties exist and safe defaults remain disabled.

- [ ] Run Agent full suite: `mvnw.cmd test`.
- [ ] Run Knowledge full suite: `mvnw.cmd test`.
- [ ] Run `git diff --check` and inspect both worktrees for unrelated changes.
- [ ] Commit `docs: add user memory recall verification runbook`.

## Final end-to-end acceptance

- [ ] Start MySQL, Redis, Elasticsearch, Milvus, embedding, reranker, Knowledge Service and Agent Server.
- [ ] In conversation A send `我平时主要做 Java 开发。` and wait for extraction `SAVED` plus Outbox `DONE`.
- [ ] In a new conversation B ask `我平时主要使用什么编程语言？`.
- [ ] Verify the answer uses Java and that the hidden memory is not shown in the memory panel.
- [ ] Verify the model request contains one bounded untrusted-memory block and logs contain no content.
- [ ] Stop ES and repeat with Milvus only; stop Milvus and repeat with ES only; stop both and confirm ordinary chat still succeeds without claiming memory access.
- [ ] Clear all memories and prove a delayed old-generation event cannot restore the answer.
- [ ] Verify another user and another tenant cannot retrieve the memory.
- [ ] Only after all checks pass, merge both implementation branches into their respective `main` branches and provide the exact Nacos delta and restart order.
