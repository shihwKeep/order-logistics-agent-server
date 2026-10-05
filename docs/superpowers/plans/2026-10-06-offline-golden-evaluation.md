# Offline Golden Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a versioned, deterministic, offline golden evaluation suite that verifies composite business orchestration without changing production behavior or calling external services.

**Architecture:** A JSON resource defines low-cardinality cases and expected outcomes. Test-only Java classes load and validate the resource, build fixed gateway fixtures, execute the existing `CompositeQueryWorkflow`, and assert result status, result kinds, gateway calls, checkpoint semantics, and sensitive-data absence. Each case receives isolated mocks and a fixed clock.

**Tech Stack:** Java 21, JUnit 5, Mockito, AssertJ, Jackson, existing `CompositeQueryWorkflow` and checkpoint abstractions.

---

### Task 1: Add the dataset schema and loader

**Files:**
- Create: `src/test/resources/evaluation/agent-golden-cases.json`
- Create: `src/test/java/com/xjjk/agent/evaluation/GoldenEvaluationCase.java`
- Create: `src/test/java/com/xjjk/agent/evaluation/GoldenEvaluationCaseLoader.java`
- Test: `src/test/java/com/xjjk/agent/evaluation/GoldenEvaluationCaseLoaderTest.java`

- [ ] **Step 1: Write the failing loader tests**

Add tests proving that the loader:

```java
@Test
void loadsVersionedCasesAndRejectsDuplicateIds() { /* assert 12 cases and v1 */ }

@Test
void rejectsMissingRequiredExpectationFields() { /* assert IllegalArgumentException */ }

@Test
void rejectsHighCardinalityOrSensitiveCaseIdentifiers() { /* assert validation failure */ }
```

- [ ] **Step 2: Run the loader tests and verify they fail**

Run:

```powershell
mvn -Dtest=GoldenEvaluationCaseLoaderTest test
```

Expected: compilation failure because the dataset model and loader do not exist.

- [ ] **Step 3: Implement the minimal model and loader**

Use Jackson records with these fields: `caseId`, `version`, `category`, `input`, `plan`, `fixtures`, and nested `expect` containing `status`, `resultKinds`, `knowledgeCalls`, `forbiddenCalls`, and `sensitiveValuesAbsent`. Load the classpath resource `/evaluation/agent-golden-cases.json`, require one dataset version, require unique non-sensitive case IDs, and reject missing expectation fields.

- [ ] **Step 4: Add the first 12 JSON cases**

Add exactly the case IDs from the approved specification, with no real customer/order identifiers in the resource. Use fixture names such as `order.logistics.normal` and `customer.not-found`, not business payloads.

- [ ] **Step 5: Run the loader tests and verify they pass**

Run:

```powershell
mvn -Dtest=GoldenEvaluationCaseLoaderTest test
```

Expected: all loader tests pass.

- [ ] **Step 6: Commit the dataset foundation**

```powershell
git add src/test/resources/evaluation/agent-golden-cases.json src/test/java/com/xjjk/agent/evaluation/GoldenEvaluationCase.java src/test/java/com/xjjk/agent/evaluation/GoldenEvaluationCaseLoader.java src/test/java/com/xjjk/agent/evaluation/GoldenEvaluationCaseLoaderTest.java
git commit -m "test: add versioned offline evaluation dataset"
```

### Task 2: Build deterministic fixtures and isolated workflow execution

**Files:**
- Create: `src/test/java/com/xjjk/agent/evaluation/GoldenFixtureFactory.java`
- Create: `src/test/java/com/xjjk/agent/evaluation/GoldenCaseExecution.java`
- Test: `src/test/java/com/xjjk/agent/evaluation/GoldenFixtureFactoryTest.java`

- [ ] **Step 1: Write the failing fixture tests**

Cover normal logistics, customer-not-found, order-not-found, product-not-found, and downstream-unavailable fixtures. Assert that every fixture uses a fixed `Clock` and that safe failure messages do not contain exception text or internal IDs.

- [ ] **Step 2: Run the fixture tests and verify they fail**

Run:

```powershell
mvn -Dtest=GoldenFixtureFactoryTest test
```

Expected: compilation failure because the fixture factory does not exist.

- [ ] **Step 3: Implement test-only fixtures**

Construct existing domain records (`OrderLogisticsResult`, `OrderSearchResult`, `ProductSearchResult`, `KnowledgeRetrievalResult`) using stable dates and masked display values. Configure Mockito Gateway stubs from fixture names. Do not use Spring context, network clients, Redis, database containers, or real service URLs.

- [ ] **Step 4: Implement isolated execution context**

Create a small test-only context containing a fresh request ID, fixed identity, fresh mocks, fixed clock, and optional in-memory `CompositeQueryCheckpointStore`. Ensure each parameterized invocation creates a new context and cannot reuse invocation state.

- [ ] **Step 5: Run the fixture tests and verify they pass**

Run:

```powershell
mvn -Dtest=GoldenFixtureFactoryTest test
```

Expected: all fixture tests pass.

- [ ] **Step 6: Commit the fixture layer**

```powershell
git add src/test/java/com/xjjk/agent/evaluation/GoldenFixtureFactory.java src/test/java/com/xjjk/agent/evaluation/GoldenCaseExecution.java src/test/java/com/xjjk/agent/evaluation/GoldenFixtureFactoryTest.java
git commit -m "test: add deterministic offline evaluation fixtures"
```

### Task 3: Implement the parameterized golden runner and assertions

**Files:**
- Create: `src/test/java/com/xjjk/agent/evaluation/EvaluationAssertions.java`
- Create: `src/test/java/com/xjjk/agent/evaluation/OfflineGoldenEvaluationTest.java`
- Test: extend `src/test/java/com/xjjk/agent/evaluation/OfflineGoldenEvaluationTest.java`

- [ ] **Step 1: Write failing assertions for the approved matrix**

Use `@MethodSource("cases")` and assert, per case:

```java
assertThat(result.status()).isEqualTo(expected.status());
assertThat(result.actualResultKinds()).containsExactlyInAnyOrderElementsOf(expected.resultKinds());
verifyKnowledgeCallCount(expected.knowledgeCalls());
verifyForbiddenGateways(expected.forbiddenCalls());
assertNoSensitiveValues(result.verifiedAnswerContext());
```

Add explicit tests for empty-business knowledge short-circuit, unavailable downstream safe failure, parallel branch completion, and checkpoint resume without repeating a completed branch.

- [ ] **Step 2: Run the runner and verify it fails for missing runner behavior**

Run:

```powershell
mvn -Dtest=OfflineGoldenEvaluationTest test
```

Expected: compilation failure until the runner and assertion helper are implemented.

- [ ] **Step 3: Implement the runner**

Load all cases once, build a plan from the case category and fixture names, execute the existing `CompositeQueryService`/`CompositeQueryWorkflow`, and emit only `caseId`, version, status, and elapsed milliseconds. Keep all calls and assertions in test code; do not add production evaluation hooks.

- [ ] **Step 4: Implement explicit checkpoint and parallel assertions**

For the checkpoint case, save a completed business branch into the in-memory store, execute the same request again, and verify the completed Gateway is not called twice. For the parallel case, use the existing workflow executor and assert the set of result kinds rather than completion order.

- [ ] **Step 5: Run the complete offline evaluation and verify it passes**

Run three times:

```powershell
1..3 | ForEach-Object { mvn -q -Dtest=OfflineGoldenEvaluationTest test }
```

Expected: all 12 cases pass on every run with identical case order and statuses.

- [ ] **Step 6: Commit the golden runner**

```powershell
git add src/test/java/com/xjjk/agent/evaluation/EvaluationAssertions.java src/test/java/com/xjjk/agent/evaluation/OfflineGoldenEvaluationTest.java
git commit -m "test: add offline golden evaluation runner"
```

### Task 4: Document the evaluation gate and run the repository verification

**Files:**
- Modify: `docs/runbook/agent-runtime-telemetry.md`
- Modify: `docs/runbook/observability-stack.md`

- [ ] **Step 1: Add the offline evaluation command and boundaries**

Document `mvn -Dtest=OfflineGoldenEvaluationTest test`, the fact that it is deterministic and offline, and that it complements—not replaces—the live SSE/Trace/metrics acceptance matrix.

- [ ] **Step 2: Run focused and full verification**

Run:

```powershell
mvn -q -Dtest=GoldenEvaluationCaseLoaderTest,GoldenFixtureFactoryTest,OfflineGoldenEvaluationTest test
mvn -q test
git diff --check
```

Expected: focused tests and full suite pass; `git diff --check` emits no whitespace errors.

- [ ] **Step 3: Confirm the final scope**

Verify that only test resources, test classes, and runbook documentation changed. Confirm no production configuration, Nacos value, database migration, external service call, or secret was added.

- [ ] **Step 4: Commit the documentation**

```powershell
git add docs/runbook/agent-runtime-telemetry.md docs/runbook/observability-stack.md
git commit -m "docs: document offline evaluation gate"
```
