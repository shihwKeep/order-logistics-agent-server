# Dependent Query Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让“客户最近订单 → 该订单物流 → 企业售后规则”通过服务端依赖编排执行，并在依赖分支失败或进程恢复时只返回已验证事实。

**Architecture:** 扩展 `CompositeQueryIntent` 表达依赖模式，Planner 为客户最近订单物流问题生成基础订单分支、依赖物流分支和知识分支。Workflow 先并行执行无依赖分支，再由服务端从订单卡片确定性选出公开订单号，执行物流分支；状态和已发布分支写入现有 Redis checkpoint，失败时以 `PARTIAL_SUCCESS` 或安全跳过终态收尾。

**Tech Stack:** Java 21, Spring Boot, Spring AI, LangGraph4j, Spring Data Redis, JUnit 5, Mockito, AssertJ, Micrometer。

---

## 文件结构与职责

- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryIntent.java` — 增加依赖模式和标识来源，并提供“最近订单物流”工厂方法。
- Modify: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java` — 识别客户最近订单物流问题并生成依赖计划，避免误生成售后工单查询。
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/LatestOrderResolver.java` — 从 `CustomerOrderQueryResult` 选择最近的公开订单号，处理空结果和并列时间。
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryState.java` — 保存 resolved order、依赖状态和已发布结果类型，并加入安全日志字段。
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java` — 将客户订单和知识分支并行执行，在订单分支后运行解析节点和物流依赖节点；支持部分成功、跳过和恢复。
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaver.java` — 允许安全 checkpoint 保存 resolved order 和 dependency state。
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java` — 将 `PARTIAL_SUCCESS` 映射为可展示结果和明确的缺失分支提示。
- Modify: `src/main/java/com/xjjk/agent/chat/observation/CompositeQueryMetrics.java` — 增加依赖解析、等待、部分成功和 checkpoint 恢复计数。
- Test: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java` — 计划识别回归。
- Create: `src/test/java/com/xjjk/agent/chat/orchestration/LatestOrderResolverTest.java` — 最近订单选择规则。
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryStateTest.java` — 新状态字段和安全状态。
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java` — 依赖执行、部分失败和恢复。
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaverTest.java` — 新字段安全持久化。
- Modify: `src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java` — 新指标低基数边界。
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java` — 端到端复合查询收尾。

### Task 1: 扩展依赖意图模型

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryIntent.java`
- Test: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`

- [ ] **Step 1: Write the failing model/planner tests**

在 `BusinessQueryPlannerTest` 增加：

```java
@Test
void customerLatestOrderLogisticsCreatesDependentIntentWithoutAfterSaleTool() {
    BusinessQueryPlan plan = planner.plan(
            "查询客户 C24101816040001 最近一笔订单的物流状态，并结合售后规则判断");

    assertThat(plan.mode()).isEqualTo(BusinessQueryMode.COMPOSITE);
    assertThat(plan.compositePlan().intents())
            .filteredOn(intent -> intent.source() == CompositeQueryIntent.Source.BUSINESS)
            .extracting(CompositeQueryIntent::resultKind,
                    CompositeQueryIntent::dependsOnResultKind,
                    CompositeQueryIntent::dependencyMode,
                    CompositeQueryIntent::identifierSource)
            .containsExactlyInAnyOrder(
                    tuple("order-list", null, DependencyMode.NONE, IdentifierSource.USER_INPUT),
                    tuple("logistics-timeline", "order-list", DependencyMode.LATEST_ORDER,
                            IdentifierSource.RESOLVED_ORDER));
    assertThat(plan.compositePlan().intents())
            .filteredOn(intent -> "after-sale-detail".equals(intent.resultKind()))
            .isEmpty();
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=BusinessQueryPlannerTest#customerLatestOrderLogisticsCreatesDependentIntentWithoutAfterSaleTool" test
```

Expected: compilation/test failure because dependency enums/accessors and planner output do not exist.

- [ ] **Step 3: Implement the minimal intent model**

Add enums `DependencyMode { NONE, LATEST_ORDER }` and `IdentifierSource { USER_INPUT, RESOLVED_ORDER }` to the orchestration package. Extend the record with these fields while retaining the existing three-argument constructor as:

```java
this(source, value, resultKind, true, null,
        DependencyMode.NONE, IdentifierSource.USER_INPUT);
```

Add factory:

```java
public static CompositeQueryIntent latestOrderLogistics() {
    return new CompositeQueryIntent(
            Source.BUSINESS, "", "logistics-timeline", true,
            "order-list", DependencyMode.LATEST_ORDER,
            IdentifierSource.RESOLVED_ORDER);
}
```

Reject `RESOLVED_ORDER` intents with a non-`LATEST_ORDER` dependency and reject blank user values for `USER_INPUT` business intents in `validateInput`, not in the record constructor.

- [ ] **Step 4: Run the focused and existing planner tests**

```powershell
.\mvnw.cmd -q "-Dtest=BusinessQueryPlannerTest" test
```

Expected: all planner tests pass; existing direct and composite routes remain unchanged.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryIntent.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java
git commit -m "feat: model dependent composite intents"
```

### Task 2: Add deterministic latest-order resolver

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/orchestration/LatestOrderResolver.java`
- Create: `src/test/java/com/xjjk/agent/chat/orchestration/LatestOrderResolverTest.java`

- [ ] **Step 1: Write failing resolver tests**

Cover: newest `orderTime` wins; equal timestamps use lexicographically stable public order code; blank order code is ignored; `NOT_FOUND`/null orders return empty; malformed timestamps do not throw and fall back to the remaining valid item.

```java
@Test
void selectsNewestPublicOrderCodeAndUsesStableTieBreak() {
    OrderSearchResult orders = result(
            card("XJ002", "2026-08-20 10:00:00"),
            card("XJ001", "2026-08-20 10:00:00"),
            card("XJ003", "2026-08-19 10:00:00"));

    assertThat(new LatestOrderResolver().resolve(found(orders)))
            .contains(new ResolvedOrder("XJ001", "2026-08-20 10:00:00"));
}
```

- [ ] **Step 2: Run resolver tests to verify failure**

```powershell
.\mvnw.cmd -q "-Dtest=LatestOrderResolverTest" test
```

Expected: compilation failure because resolver types do not exist.

- [ ] **Step 3: Implement resolver**

Use `DateTimeFormatter` with the existing order time formats, filter `OrderCard.orderCode()` blanks, compare parsed time descending, and use `orderCode` ascending as tie-break. Return `Optional<ResolvedOrder>` and never expose internal IDs.

- [ ] **Step 4: Run resolver tests**

```powershell
.\mvnw.cmd -q "-Dtest=LatestOrderResolverTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/orchestration/LatestOrderResolver.java src/test/java/com/xjjk/agent/chat/orchestration/LatestOrderResolverTest.java
git commit -m "feat: resolve latest customer order deterministically"
```

### Task 3: Route the customer/latest-order query in the Planner

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`
- Modify: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`

- [ ] **Step 1: Add failing route assertions**

Assert the customer code produces `customerOrders(customerCode)`, logistics produces `latestOrderLogistics()`, the knowledge intent contains the original question, and no `after-sale-detail` intent is generated when no work-order identifier is present.

- [ ] **Step 2: Run planner test to verify failure**

```powershell
.\mvnw.cmd -q "-Dtest=BusinessQueryPlannerTest#customerLatestOrderLogisticsCreatesDependentIntentWithoutAfterSaleTool" test
```

Expected: failure because `compositePlanFor` currently only adds logistics when an order code is present.

- [ ] **Step 3: Implement constrained route**

In `compositePlanFor`, detect `customer && order && logistics && customerCode != null && containsAny(message, "最近", "最新", "上一笔")`. Add `CompositeQueryIntent.customerOrders(customerCode)` and `CompositeQueryIntent.latestOrderLogistics()`. Remove any existing order-list intent before adding the customer-order intent, as current logic does. Keep explicit order-code logistics behavior unchanged. Only add `knowledge(message)` for rule/policy terms; do not add after-sale detail unless `AFTER_SALE_CODE` is independently matched.

- [ ] **Step 4: Run all planner tests**

```powershell
.\mvnw.cmd -q "-Dtest=BusinessQueryPlannerTest,BusinessQueryPlanTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java
git commit -m "feat: plan customer latest order logistics dependency"
```

### Task 4: Extend graph state and safe checkpoint mapping

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryState.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaver.java`
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryStateTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaverTest.java`

- [ ] **Step 1: Add failing state/checkpoint tests**

Verify `CompositeQueryState.initial` exposes empty resolved order and dependency maps; `toSafeLogData` includes only public resolved order fields; saver persists `resolvedOrderCode`, `resolvedOrderAt`, `dependencyStatuses`, and `publishedResultKinds`, but not `PLAN`, `RESULTS`, `KNOWLEDGE`, or identity data.

- [ ] **Step 2: Run focused tests to verify failure**

```powershell
.\mvnw.cmd -q "-Dtest=CompositeQueryStateTest,LangGraph4jRedisCheckpointSaverTest" test
```

Expected: failure because the state keys/accessors are not present.

- [ ] **Step 3: Add state keys and accessors**

Add constants and initial values:

```java
RESOLVED_ORDER_CODE = "resolvedOrderCode";
RESOLVED_ORDER_AT = "resolvedOrderAt";
DEPENDENCY_STATUSES = "dependencyStatuses";
PUBLISHED_RESULT_KINDS = "publishedResultKinds";
```

Add typed accessors returning empty string/map/set defaults. Include only the public order code/time and dependency maps in `toSafeLogData`; keep internal business objects out of state.

- [ ] **Step 4: Extend saver allow-list**

Add the four keys to `SAFE_STATE_KEYS`. Keep the existing allow-list behavior and ensure state values are JSON-safe strings, maps, lists, and numbers only.

- [ ] **Step 5: Run focused tests**

```powershell
.\mvnw.cmd -q "-Dtest=CompositeQueryStateTest,LangGraph4jRedisCheckpointSaverTest" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryState.java src/main/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaver.java src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryStateTest.java src/test/java/com/xjjk/agent/chat/orchestration/LangGraph4jRedisCheckpointSaverTest.java
git commit -m "feat: checkpoint dependent query context safely"
```

### Task 5: Implement dependent workflow execution

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`

- [ ] **Step 1: Add failing workflow tests**

Add tests that:

1. customer orders and knowledge run before logistics; logistics receives the resolved public order code;
2. customer order success plus logistics exception returns `PARTIAL_SUCCESS`, publishes the customer order card, and has no logistics result or threshold text;
3. no customer orders marks logistics `SKIPPED_NO_MATCHING_ORDER`;
4. a checkpoint containing `resolvedOrderCode` resumes at logistics without invoking customer order query again;
5. a knowledge-only after-sale rule query does not invoke `AfterSaleQueryGateway`.

Use Mockito `InOrder` for dependency sequencing and `verify(customerOrderQueryService, times(1))` for recovery.

- [ ] **Step 2: Run workflow tests to verify failure**

```powershell
.\mvnw.cmd -q "-Dtest=CompositeQueryWorkflowTest" test
```

Expected: failures because the current graph runs all business intents in one loop and terminates on any branch failure.

- [ ] **Step 3: Split business execution into base and dependent nodes**

Add graph nodes:

```text
business.base.query
resolve.latest.order
business.dependent.query
```

`business.base.query` executes user-input business intents except `IdentifierSource.RESOLVED_ORDER`; `knowledge.query` remains parallel. `resolve.latest.order` reads the successful `CustomerOrderQueryResult`, uses `LatestOrderResolver`, and returns `SKIPPED_NO_MATCHING_ORDER` when no code exists. `business.dependent.query` replaces the blank resolved intent value with the checkpointed public order code and invokes `orderGateway.logistics(code, ORDER_CODE, ...)`.

Do not mutate the immutable intent; pass an execution value to `queryBusinessIntent`. A dependency branch may run only when its dependency status is `SUCCESS` and `resolvedOrderCode` is nonblank. If the base branch failed, mark the dependent branch `SKIPPED_DEPENDENCY_FAILED`.

- [ ] **Step 4: Preserve branch facts across nodes and recovery**

At each node merge existing state snapshots, failures, completed branches, and results from the invocation context. Use branch names that include the dependency mode (for example `business:logistics-timeline:latest-order`) so the dependent branch cannot collide with explicit order-code logistics. Add `publishedResultKinds` when a result is added to `context.results`.

- [ ] **Step 5: Change result validation to partial success**

Compute successful result kinds and failed/skipped required branches separately:

```java
boolean hasSuccessfulBusiness = !context.results.isEmpty()
        || state.branchSnapshots().stream().anyMatch(s -> "SUCCESS".equals(s.status()));
boolean hasMissingRequired = requiredKinds.stream().anyMatch(kind -> !actual.contains(kind));
String status = hasMissingRequired && hasSuccessfulBusiness
        ? "PARTIAL_SUCCESS"
        : hasMissingRequired ? "MISSING_RESULT" : "SUCCESS";
```

Knowledge failure still takes precedence as `NO_RELIABLE_KNOWLEDGE` when knowledge is required. Do not add `logistics-timeline` to `actual` from an order-list snapshot.

- [ ] **Step 6: Compose safe partial answer context**

Allow `answer.compose` for `SUCCESS` and `PARTIAL_SUCCESS`. Include successful structured facts, then append a fixed missing-branch sentence based on snapshots:

```text
物流查询未完成，因此无法确认当前物流状态、最新轨迹时间或停滞阈值；以上订单状态不等同于物流状态。
```

For an unqueried after-sale work order, append only:

```text
本轮未查询售后工单，无法确认是否存在售后申请或工单状态。
```

Never include those sentences when the corresponding branch succeeded.

- [ ] **Step 7: Run workflow tests**

```powershell
.\mvnw.cmd -q "-Dtest=CompositeQueryWorkflowTest" test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java
git commit -m "feat: execute dependent composite business branches"
```

### Task 6: Expose partial success safely in the application service

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **Step 1: Add failing service/runner assertion**

Assert `CompositeQueryResult.from` keeps successful `order-list` UI results when status is `PARTIAL_SUCCESS`, sets `success=false`, and returns a safe message that names the failed logistics branch without claiming the whole business query returned no data.

- [ ] **Step 2: Run focused test to verify failure**

```powershell
.\mvnw.cmd -q "-Dtest=ChatTurnRunnerBusinessQueryTest" test
```

Expected: failure because all non-`SUCCESS` statuses currently map to the generic retry message.

- [ ] **Step 3: Implement partial-result mapping**

Add `PARTIAL_RESULT_MESSAGE` and select it for `PARTIAL_SUCCESS`. Keep `NO_RELIABLE_KNOWLEDGE` and complete business failure messages unchanged. Preserve any successful UI cards from `results` and checkpoint snapshots.

- [ ] **Step 4: Run application-service tests**

```powershell
.\mvnw.cmd -q "-Dtest=ChatTurnRunnerBusinessQueryTest,FreshBusinessResultGateTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java
git commit -m "feat: publish safe partial composite results"
```

### Task 7: Add dependency and recovery metrics

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/observation/CompositeQueryMetrics.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Modify: `src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java`

- [ ] **Step 1: Add failing metrics tests**

Assert that `dependency("LATEST_ORDER", "RESOLVED")`, `partialSuccess()`, and `checkpointResume("success")` create bounded counters and reject arbitrary high-cardinality values.

- [ ] **Step 2: Run metrics tests to verify failure**

```powershell
.\mvnw.cmd -q "-Dtest=CompositeQueryMetricsTest" test
```

Expected: compilation failure because methods/counters do not exist.

- [ ] **Step 3: Implement bounded metrics**

Add fixed sets for dependency types, dependency outcomes, and resume outcomes. Add methods that use only those sets as low-cardinality tags. Call them at dependency wait/resolve, partial result, and checkpoint restoration sites.

- [ ] **Step 4: Run metrics tests**

```powershell
.\mvnw.cmd -q "-Dtest=CompositeQueryMetricsTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/agent/chat/observation/CompositeQueryMetrics.java src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java
git commit -m "feat: observe dependent query recovery"
```

### Task 8: Full regression and manual acceptance

**Files:**
- Test only: existing test suite and manual HTTP endpoint.

- [ ] **Step 1: Run focused composite regression**

```powershell
.\mvnw.cmd -q "-Dtest=BusinessQueryPlannerTest,BusinessQueryPlanTest,LatestOrderResolverTest,CompositeQueryStateTest,CompositeQueryWorkflowTest,LangGraph4jRedisCheckpointSaverTest,CompositeQueryMetricsTest,ChatTurnRunnerBusinessQueryTest,FreshBusinessResultGateTest" test
```

Expected: PASS.

- [ ] **Step 2: Run package verification**

```powershell
.\mvnw.cmd -q -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Verify the customer/latest-order/logistics question manually**

Send:

```text
查询客户 C24101816040001 最近一笔订单的物流状态，并结合售后规则判断。
```

Verify the answer contains the customer order card, a real logistics result or an explicit logistics failure, the after-sale rule evidence, and missing facts. Verify it does not claim that the order’s “在途/运输中” field is the logistics status and does not claim a non-existent after-sale work order.

- [ ] **Step 4: Verify partial failure**

Use a test double or controlled downstream timeout for logistics. Verify the order card remains visible, status is `PARTIAL_SUCCESS`, and the answer explicitly says logistics facts and threshold evaluation cannot be confirmed.

- [ ] **Step 5: Verify checkpoint recovery**

Interrupt after `resolve.latest.order`, invoke with the same request/thread ID, and verify customer order query count remains one while logistics executes once with the checkpointed public order code.

- [ ] **Step 6: Inspect working tree and commit only feature changes**

```powershell
git diff --check
git status --short
git log -8 --oneline
```

Do not stage the pre-existing observability, memory, temporary, or unrelated files in the working tree.

## Self-review checklist

- The design’s dependency plan, latest-order resolution, partial success, checkpoint fields, recovery semantics, metrics, and acceptance query each have an implementation task.
- No task requires external data or a business write operation.
- `CompositeQueryIntent` fields used by Planner, State, Workflow, and tests are defined in Task 1 before later tasks reference them.
- `LatestOrderResolver` is defined in Task 2 before Workflow uses it.
- `PARTIAL_SUCCESS` is introduced in Workflow before Service maps it.
- Every test step has a concrete Maven command and expected result.
