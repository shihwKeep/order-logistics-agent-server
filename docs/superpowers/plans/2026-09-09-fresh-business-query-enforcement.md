# Fresh Business Query Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee that every real-time business answer is backed by a matching structured result produced in the current turn, while routing three unambiguous query forms directly to existing trusted services.

**Architecture:** Add a stateless planner before context/model preparation, reuse `ChatActionDispatcher` for high-confidence direct requests, and stage both model-path tool results and text until a freshness gate validates them. General chat keeps its existing streaming path; no database or frontend protocol change is required.

**Tech Stack:** Java 21, Spring Boot, Spring AI, Reactor, JUnit 5, Mockito, AssertJ, Maven

---

## File map

- Create `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryMode.java`: planner mode enum.
- Create `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlan.java`: immutable plan and validated factories.
- Create `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`: current-message-only high-confidence routing.
- Create `src/main/java/com/xjjk/agent/chat/config/BusinessQueryEnforcementProperties.java`: Nacos-backed emergency switch.
- Create `src/main/java/com/xjjk/agent/chat/service/stream/StagedToolResult.java`: pairs one validated persistence snapshot with its SSE value.
- Create `src/main/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGate.java`: validates and flushes staged model-path output.
- Modify `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java`: owns the plan, committed results, staged results and buffered-output helpers.
- Modify `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`: chooses DIRECT/GENERAL/MODEL_REQUIRED and invokes the gate.
- Modify `src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java`: supplies the new runner dependencies without changing action assertions.
- Create focused tests in matching `chat/routing` and `chat/service/stream` packages.
- Create `docs/runbook/fresh-business-query-enforcement.md`: Nacos setting, safe logs and verification queries.

Existing modified files shown by `git status` must not be staged by these tasks unless explicitly listed. In particular, preserve the current index/worktree state of `V8__create_agent_message_result.sql`.

### Task 1: Add the configuration and immutable query plan

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/config/BusinessQueryEnforcementProperties.java`
- Create: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryMode.java`
- Create: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlan.java`
- Test: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlanTest.java`

- [ ] **Step 1: Write the failing plan contract test**

```java
package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BusinessQueryPlanTest {

    @Test
    void createsValidatedGeneralDirectAndModelPlans() {
        assertThat(BusinessQueryPlan.general().mode())
                .isEqualTo(BusinessQueryMode.GENERAL);

        ChatActionRequest action = new ChatActionRequest(
                "QUERY_AFTER_SALE_DETAIL", null, null, "HH20260414_00002");
        BusinessQueryPlan direct = BusinessQueryPlan.direct(
                action, "after-sale-detail");
        assertThat(direct.directAction()).isEqualTo(action);
        assertThat(direct.acceptedResultKinds())
                .containsExactly("after-sale-detail");

        BusinessQueryPlan model = BusinessQueryPlan.modelRequired(
                Set.of("product-list"));
        assertThat(model.mode()).isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(model.directAction()).isNull();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> BusinessQueryPlan.modelRequired(Set.of()));
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
./mvnw.cmd -Dtest=BusinessQueryPlanTest test
```

Expected: compilation fails because `BusinessQueryPlan` and `BusinessQueryMode` do not exist.

- [ ] **Step 3: Implement the configuration and plan types**

```java
package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 实时业务查询结果约束；生产值只从 Nacos 注入。 */
@ConfigurationProperties(prefix = "agent.chat.business-query-enforcement")
public record BusinessQueryEnforcementProperties(boolean enabled) {
}
```

```java
package com.xjjk.agent.chat.routing;

public enum BusinessQueryMode {
    GENERAL,
    DIRECT,
    MODEL_REQUIRED
}
```

```java
package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;

import java.util.Objects;
import java.util.Set;

/** 当前用户消息的业务查询执行计划，不包含历史消息或可信身份。 */
public record BusinessQueryPlan(
        BusinessQueryMode mode,
        ChatActionRequest directAction,
        Set<String> acceptedResultKinds) {

    public BusinessQueryPlan {
        Objects.requireNonNull(mode, "查询计划类型不能为空");
        acceptedResultKinds = Set.copyOf(
                Objects.requireNonNull(acceptedResultKinds, "结果类型集合不能为空"));
        if (mode == BusinessQueryMode.GENERAL
                && (directAction != null || !acceptedResultKinds.isEmpty())) {
            throw new IllegalArgumentException("普通问答不能携带业务执行计划");
        }
        if (mode == BusinessQueryMode.DIRECT
                && (directAction == null || acceptedResultKinds.size() != 1)) {
            throw new IllegalArgumentException("确定性查询必须携带动作和唯一结果类型");
        }
        if (mode == BusinessQueryMode.MODEL_REQUIRED
                && (directAction != null || acceptedResultKinds.isEmpty())) {
            throw new IllegalArgumentException("模型业务查询必须携带允许结果类型");
        }
    }

    public static BusinessQueryPlan general() {
        return new BusinessQueryPlan(BusinessQueryMode.GENERAL, null, Set.of());
    }

    public static BusinessQueryPlan direct(
            ChatActionRequest action, String acceptedResultKind) {
        return new BusinessQueryPlan(
                BusinessQueryMode.DIRECT,
                Objects.requireNonNull(action, "确定性动作不能为空"),
                Set.of(validateKind(acceptedResultKind)));
    }

    public static BusinessQueryPlan modelRequired(Set<String> acceptedResultKinds) {
        return new BusinessQueryPlan(
                BusinessQueryMode.MODEL_REQUIRED, null, acceptedResultKinds);
    }

    public boolean buffersModelOutput() {
        return mode == BusinessQueryMode.MODEL_REQUIRED;
    }

    private static String validateKind(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("结果类型不能为空");
        }
        return value;
    }
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run: `./mvnw.cmd -Dtest=BusinessQueryPlanTest test`

Expected: `BusinessQueryPlanTest` passes.

- [ ] **Step 5: Commit only Task 1 files**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/config/BusinessQueryEnforcementProperties.java src/main/java/com/xjjk/agent/chat/routing/BusinessQueryMode.java src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlan.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlanTest.java
git commit --only -m "feat: add business query plan contract" -- src/main/java/com/xjjk/agent/chat/config/BusinessQueryEnforcementProperties.java src/main/java/com/xjjk/agent/chat/routing/BusinessQueryMode.java src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlan.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlanTest.java
```

### Task 2: Implement current-message-only planning

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`
- Test: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`

- [ ] **Step 1: Write failing examples for the three DIRECT forms and guarded model forms**

```java
package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.config.BusinessQueryEnforcementProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessQueryPlannerTest {

    private final BusinessQueryPlanner planner = new BusinessQueryPlanner(
            new BusinessQueryEnforcementProperties(true));

    @Test
    void routesOnlyHighConfidenceCompleteRequestsDirectly() {
        assertDirect("查看订单 XJTS0120260820000011 的物流",
                "QUERY_ORDER_LOGISTICS", "logistics-timeline");
        assertDirect("帮忙查下客户 C24101816040001 的订单",
                "QUERY_CUSTOMER_ORDERS", "order-list");
        assertDirect("查看售后工单 HH20260414_00002",
                "QUERY_AFTER_SALE_DETAIL", "after-sale-detail");
    }

    @Test
    void keepsNaturalLanguageBusinessQueriesOnTheGuardedModelPath() {
        assertThat(planner.plan("查询鱼油商品").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(planner.plan("查询客户张三的信息").acceptedResultKinds())
                .contains("customer-list");
        assertThat(planner.plan("查询张三本月的售后").acceptedResultKinds())
                .contains("after-sale-list");
    }

    @Test
    void leavesGeneralConversationStreamingAndDoesNotGuessAmbiguousDirectCommands() {
        assertThat(planner.plan("你好").mode()).isEqualTo(BusinessQueryMode.GENERAL);
        assertThat(planner.plan("介绍一下订单状态是什么意思").mode())
                .isEqualTo(BusinessQueryMode.GENERAL);
        assertThat(planner.plan("查一下客户 C1 的订单").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(planner.plan("查询订单 XJTS0120260820000011 和售后").mode())
                .isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
    }

    @Test
    void disablesPlanningThroughTheNacosEmergencySwitch() {
        BusinessQueryPlanner disabled = new BusinessQueryPlanner(
                new BusinessQueryEnforcementProperties(false));
        assertThat(disabled.plan("查看售后工单 HH20260414_00002"))
                .isEqualTo(BusinessQueryPlan.general());
    }

    private void assertDirect(
            String message, String actionType, String expectedKind) {
        BusinessQueryPlan plan = planner.plan(message);
        assertThat(plan.mode()).isEqualTo(BusinessQueryMode.DIRECT);
        assertThat(plan.directAction().type()).isEqualTo(actionType);
        assertThat(plan.acceptedResultKinds()).containsExactly(expectedKind);
    }
}
```

- [ ] **Step 2: Run the planner test and verify RED**

Run: `./mvnw.cmd -Dtest=BusinessQueryPlannerTest test`

Expected: compilation fails because `BusinessQueryPlanner` does not exist.

- [ ] **Step 3: Implement conservative priority-based planning**

Implement `BusinessQueryPlanner` as a stateless `@Component` with these constants and priority rules:

```java
private static final Pattern ORDER_CODE = Pattern.compile(
        "(?i)(?<![A-Z0-9_-])(XJ[A-Z0-9_-]{8,61})(?![A-Z0-9_-])");
private static final Pattern CUSTOMER_CODE = Pattern.compile(
        "(?i)(?<![A-Z0-9_-])(C\\d{10,31})(?![A-Z0-9_-])");
private static final Pattern AFTER_SALE_CODE = Pattern.compile(
        "(?i)(?<![A-Z0-9_-])([A-Z]{2,8}\\d{8}(?:[_-]?\\d{3,32}))(?![A-Z0-9_-])");
```

The complete `plan` flow is:

```java
public BusinessQueryPlan plan(String rawMessage) {
    if (!properties.enabled() || rawMessage == null || rawMessage.isBlank()) {
        return BusinessQueryPlan.general();
    }
    String message = rawMessage.strip();
    boolean query = containsAny(message, "查询", "查看", "查下", "查一下", "帮我查");
    if (!query) return BusinessQueryPlan.general();

    boolean product = containsAny(message, "商品", "SKU", "sku", "库存", "上下架");
    boolean customer = message.contains("客户");
    boolean order = message.contains("订单");
    boolean logistics = containsAny(message, "物流", "运单", "轨迹");
    boolean afterSale = containsAny(message, "售后", "退货", "换货", "退款");
    int domains = count(product, customer, logistics, afterSale);

    if (domains == 1 && logistics) {
        String code = find(ORDER_CODE, message);
        if (code != null) {
            return BusinessQueryPlan.direct(
                    new ChatActionRequest("QUERY_ORDER_LOGISTICS", code),
                    "logistics-timeline");
        }
    }
    if (domains == 1 && customer && order) {
        String code = find(CUSTOMER_CODE, message);
        if (code != null) {
            return BusinessQueryPlan.direct(
                    new ChatActionRequest("QUERY_CUSTOMER_ORDERS", null, code),
                    "order-list");
        }
    }
    if (domains == 1 && afterSale
            && containsAny(message, "售后单", "售后工单")
            && !message.contains("客户") && !message.contains("订单")) {
        String code = find(AFTER_SALE_CODE, message);
        if (code != null) {
            return BusinessQueryPlan.direct(
                    new ChatActionRequest(
                            "QUERY_AFTER_SALE_DETAIL", null, null, code),
                    "after-sale-detail");
        }
    }

    Set<String> acceptedKinds = acceptedKinds(
            product, customer, order, logistics, afterSale, message);
    return acceptedKinds.isEmpty()
            ? BusinessQueryPlan.general()
            : BusinessQueryPlan.modelRequired(acceptedKinds);
}
```

`acceptedKinds(...)` must apply specific-before-general rules: logistics only maps to `logistics-timeline`; customer plus order maps to `order-list`; after-sale plus `详情`/`工单` maps to `after-sale-detail`, otherwise `after-sale-list`; customer maps to `customer-list`; order maps to `order-list`; product maps to `product-list`. If two independent domains are present, return their union so no unrelated result can pass. `find(...)` returns the first matcher group upper-cased with `Locale.ROOT`. `count(...)` and `containsAny(...)` are private pure helpers.

- [ ] **Step 4: Run planner tests and adjust only proven classification gaps**

Run: `./mvnw.cmd -Dtest=BusinessQueryPlannerTest test`

Expected: all planner tests pass. Do not broaden identifier patterns without adding a failing example first.

- [ ] **Step 5: Commit the planner**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java
git commit --only -m "feat: plan fresh business queries" -- src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java
```

### Task 3: Stage model-path results and validate freshness

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/service/stream/StagedToolResult.java`
- Create: `src/main/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGate.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGateTest.java`

- [ ] **Step 1: Write failing gate tests**

Create a Mockito test in the same package so it can construct `ChatTurnExecution`. Cover these exact cases:

```java
@Test
void flushesMatchingResultsBeforeBufferedText() throws Exception {
    ChatTurnExecution execution = execution(
            BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
    ToolUiResult ui = uiResult("after-sale-detail");
    execution.stageResult(pending(1, "after-sale-detail"), ui);
    execution.content.append("详情卡片已展示");

    gate.flush(execution, session);

    InOrder order = inOrder(session);
    order.verify(session).result(ui);
    order.verify(session).delta("详情卡片已展示");
    assertThat(execution.resultSnapshot()).hasSize(1);
}

@Test
void replacesModelTextWhenNoFreshResultExists() throws Exception {
    ChatTurnExecution execution = execution(
            BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
    execution.content.append("已查询，卡片已展示");

    gate.flush(execution, session);

    verify(session).delta(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
    assertThat(execution.content.toString())
            .isEqualTo(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
    assertThat(execution.resultSnapshot()).isEmpty();
}

@Test
void rejectsTheWholeBatchWhenAnyResultKindIsUnexpected() throws Exception {
    ChatTurnExecution execution = execution(
            BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
    execution.stageResult(pending(1, "product-list"), uiResult("product-list"));
    execution.content.append("已完成");

    gate.flush(execution, session);

    verify(session, never()).result(any());
    assertThat(execution.resultSnapshot()).isEmpty();
}
```

The test helper must initialize a prepared execution with a real `ChatTurnContext` so metrics can mark the first emitted delta. Use `PendingMessageResult` constructors already used by `ChatActionDispatcherTest`.

- [ ] **Step 2: Run the gate test and verify RED**

Run: `./mvnw.cmd -Dtest=FreshBusinessResultGateTest test`

Expected: compilation fails because staging and the gate do not exist.

- [ ] **Step 3: Add the staged result pair and execution-state APIs**

```java
package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.tool.ToolUiResult;

import java.util.Objects;

record StagedToolResult(PendingMessageResult pending, ToolUiResult uiResult) {
    StagedToolResult {
        Objects.requireNonNull(pending, "待持久化结果不能为空");
        Objects.requireNonNull(uiResult, "SSE 工具结果不能为空");
        if (!pending.kind().equals(uiResult.kind())
                || pending.schemaVersion() != uiResult.schemaVersion()) {
            throw new IllegalArgumentException("暂存结果协议不一致");
        }
    }
}
```

Add these fields and methods to `ChatTurnExecution`:

```java
BusinessQueryPlan queryPlan = BusinessQueryPlan.general();
private final List<StagedToolResult> stagedResults = new ArrayList<>();

void queryPlan(BusinessQueryPlan plan) {
    queryPlan = Objects.requireNonNull(plan, "业务查询计划不能为空");
}

boolean buffersModelOutput() {
    return queryPlan.buffersModelOutput();
}

void stageResult(PendingMessageResult pending, ToolUiResult uiResult) {
    if (pending.resultSequence() != nextResultSequence()) {
        throw new IllegalArgumentException("结构化结果序号必须连续递增");
    }
    stagedResults.add(new StagedToolResult(pending, uiResult));
}

List<StagedToolResult> stagedResultSnapshot() {
    return List.copyOf(stagedResults);
}

void promoteStagedResults() {
    List<StagedToolResult> values = List.copyOf(stagedResults);
    stagedResults.clear();
    for (StagedToolResult result : values) addResult(result.pending());
}

void discardStagedResults() {
    stagedResults.clear();
}

void replaceContent(String value) {
    content.setLength(0);
    content.append(Objects.requireNonNull(value, "替换正文不能为空"));
}
```

Change `nextResultSequence()` to return `results.size() + stagedResults.size() + 1`. Keep `resultSnapshot()` limited to promoted/committed results so exceptions and cancellation cannot persist unpublished staged cards.

- [ ] **Step 4: Implement the gate**

```java
package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.routing.BusinessQueryMode;
import com.xjjk.agent.chat.stream.ChatSseSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FreshBusinessResultGate {
    static final String MISSING_RESULT_MESSAGE =
            "本轮未完成实时业务查询，请补充查询条件或稍后重试。";

    void flush(ChatTurnExecution execution, ChatSseSession session) throws IOException {
        if (execution.queryPlan.mode() != BusinessQueryMode.MODEL_REQUIRED) return;
        List<StagedToolResult> staged = execution.stagedResultSnapshot();
        Set<String> actualKinds = staged.stream()
                .map(value -> value.uiResult().kind())
                .collect(Collectors.toUnmodifiableSet());
        boolean accepted = !staged.isEmpty()
                && execution.queryPlan.acceptedResultKinds().containsAll(actualKinds);
        if (!accepted) {
            execution.discardStagedResults();
            execution.replaceContent(MISSING_RESULT_MESSAGE);
            session.delta(MISSING_RESULT_MESSAGE);
            execution.metrics.markFirstDeltaSent();
            logOutcome(execution, actualKinds,
                    staged.isEmpty() ? "MISSING_RESULT" : "UNEXPECTED_RESULT");
            return;
        }
        execution.promoteStagedResults();
        for (StagedToolResult value : staged) session.result(value.uiResult());
        String text = execution.content.toString();
        if (!text.isEmpty()) {
            session.delta(text);
            execution.metrics.markFirstDeltaSent();
        }
        logOutcome(execution, actualKinds, "FRESH_RESULT_ACCEPTED");
    }

    private void logOutcome(
            ChatTurnExecution execution, Set<String> actualKinds, String outcome) {
        log.info(
                "chat_business_query_gate requestId={}, mode={}, expectedKinds={}, actualKinds={}, outcome={}",
                execution.requestId,
                execution.queryPlan.mode(),
                execution.queryPlan.acceptedResultKinds(),
                actualKinds,
                outcome);
    }
}
```

The implementation must not log the user message or any identifier. Before promotion, an exception leaves `resultSnapshot()` empty.

- [ ] **Step 5: Run the gate tests and verify GREEN**

Run: `./mvnw.cmd -Dtest=FreshBusinessResultGateTest test`

Expected: all gate ordering, fallback and rejection tests pass.

- [ ] **Step 6: Commit Task 3**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/service/stream/StagedToolResult.java src/main/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGate.java src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java src/test/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGateTest.java
git commit --only -m "feat: gate model business query results" -- src/main/java/com/xjjk/agent/chat/service/stream/StagedToolResult.java src/main/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGate.java src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java src/test/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGateTest.java
```

### Task 4: Integrate planning, DIRECT reuse and buffered model output

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- Modify: `src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java`
- Create: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **Step 1: Write the regression test for the reproduced bug**

`ChatTurnRunnerBusinessQueryTest` must create two independent `ChatTurnExecution` lifecycles by calling `runner.run(...)` twice with the same manual message `查看售后工单 HH20260414_00002`. Mock `BusinessQueryPlanner.plan(...)` to return a DIRECT `QUERY_AFTER_SALE_DETAIL` plan, configure `AfterSaleQueryGateway.detail(...)` and `ChatToolResultRecorder.prepare(...)`, then assert:

```java
verify(afterSaleGateway, times(2)).detail(
        eq("HH20260414_00002"), eq(identity), anyString());
verify(sessionOne).result(any(ToolUiResult.class));
verify(sessionTwo).result(any(ToolUiResult.class));
verifyNoInteractions(aiChatService);
```

Add a model-required test whose mocked `AiChatService` emits a `ChatResponse` containing `已查询，卡片已展示` but whose publisher is never invoked. Assert the session only receives `FreshBusinessResultGate.MISSING_RESULT_MESSAGE`, never the model text.

- [ ] **Step 2: Run the new test and verify RED**

Run: `./mvnw.cmd -Dtest=ChatTurnRunnerBusinessQueryTest test`

Expected: the manual query still enters `AiChatService`, so the DIRECT assertions fail.

- [ ] **Step 3: Inject the planner and gate into ChatTurnRunner**

Add final dependencies through the existing Lombok-generated constructor:

```java
private final BusinessQueryPlanner businessQueryPlanner;
private final FreshBusinessResultGate freshBusinessResultGate;
```

After `session.session(...)`, preserve explicit frontend actions first, then plan manual text:

```java
if (request.action() != null) {
    executeAction(request, identity, session, execution);
    return;
}

BusinessQueryPlan plan = businessQueryPlanner.plan(request.message());
execution.queryPlan(plan);
if (plan.mode() == BusinessQueryMode.DIRECT) {
    ChatStreamRequest directRequest = new ChatStreamRequest(
            request.conversationId(), request.message(), plan.directAction());
    executeAction(directRequest, identity, session, execution);
    return;
}
```

This ordering ensures an authenticated frontend action is never reinterpreted from its display text.

- [ ] **Step 4: Stage tool results and model deltas only for MODEL_REQUIRED**

Change `publishToolResult(...)` after `resultRecorder.prepare(...)`:

```java
if (execution.buffersModelOutput()) {
    execution.stageResult(pending, result);
} else {
    execution.addResult(pending);
    session.result(result);
}
```

Change `acceptResponse(...)` after appending text:

```java
execution.content.append(text);
if (!execution.buffersModelOutput()) {
    session.delta(text);
    execution.metrics.markFirstDeltaSent();
}
```

After computing `execution.status` for a completed model stream, invoke the gate only for a successful MODEL_REQUIRED response:

```java
execution.status = completedStatus(execution);
execution.error = ChatStreamError.forStatus(execution.status);
if (execution.status == MessageStatus.SUCCESS && execution.buffersModelOutput()) {
    freshBusinessResultGate.flush(execution, session);
}
```

If the stream fails, is cancelled, has an output limit, or ends incomplete, staged results remain unpromoted and therefore are not persisted by the finalizer.

- [ ] **Step 5: Update existing ChatTurnRunner constructor tests**

In each `new ChatTurnRunner(...)` in `ChatActionDispatcherTest`, append mocked `BusinessQueryPlanner` and a real or mocked `FreshBusinessResultGate`. Explicit card actions return before planner invocation, so existing action behavior and assertions remain unchanged.

- [ ] **Step 6: Run focused routing and action tests**

Run:

```powershell
./mvnw.cmd -Dtest=ChatTurnRunnerBusinessQueryTest,ChatActionDispatcherTest test
```

Expected: repeated manual queries produce two current-turn results; existing frontend actions still bypass the model.

- [ ] **Step 7: Commit Task 4**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java
git commit --only -m "feat: enforce fresh business query results" -- src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java
```

### Task 5: Document Nacos activation and verify the complete server

**Files:**
- Create: `docs/runbook/fresh-business-query-enforcement.md`
- Verify: all Task 1-4 files

- [ ] **Step 1: Write the operator runbook**

The runbook must include this exact Nacos property and state that it must not be copied into `application.properties`:

```properties
# 实时业务查询必须产生本轮新鲜结构化结果；生产环境保持开启
agent.chat.business-query-enforcement.enabled=true
```

Add these exact verification prompts:

```text
查看售后工单 HH20260414_00002
查看售后工单 HH20260414_00002
查看订单 XJTS0120260820000011 的物流
帮忙查下客户 C24101816040001 的订单
查询鱼油商品
你好，请介绍一下你自己
```

For the repeated after-sale query, provide this read-only SQL and expected invariant:

```sql
SELECT m.message_sequence,
       m.role,
       m.status,
       GROUP_CONCAT(r.kind ORDER BY r.result_sequence) AS result_kinds
FROM agent_message m
LEFT JOIN agent_message_result r ON r.message_id = m.message_id
WHERE m.conversation_id = :conversation_id
GROUP BY m.message_id, m.message_sequence, m.role, m.status
ORDER BY m.message_sequence DESC
LIMIT 8;
```

Expected: each successful assistant response to the two repeated queries has its own `after-sale-detail`; no successful card claim has `result_kinds = NULL`.

- [ ] **Step 2: Run focused tests**

Run:

```powershell
./mvnw.cmd -Dtest=BusinessQueryPlanTest,BusinessQueryPlannerTest,FreshBusinessResultGateTest,ChatTurnRunnerBusinessQueryTest,ChatActionDispatcherTest test
```

Expected: all new routing, gate, repeated-query and existing action tests pass.

- [ ] **Step 3: Run the complete server test suite**

Run:

```powershell
./mvnw.cmd test
```

Expected: Maven exits with code 0 and reports no failed or errored tests.

- [ ] **Step 4: Verify no frontend protocol change is needed**

In `D:\GitCode\order-logistics-agent-web`, run:

```powershell
npm test -- src/renderer/src/stores/chat-accumulator.test.ts src/renderer/src/components/AfterSaleDetailCard.test.ts
npm run typecheck
```

Expected: the existing `after-sale-detail` parser/card tests and TypeScript type checks pass. Do not rebuild the desktop app because no frontend source is modified.

- [ ] **Step 5: Review the final diff and working-tree ownership**

Run:

```powershell
git diff --check
git status --short
git log --oneline -8
```

Expected: task commits contain only the files explicitly listed above. The pre-existing modified/staged files retain their original status and are not included in task commits.

- [ ] **Step 6: Commit the runbook**

```powershell
git add -- docs/runbook/fresh-business-query-enforcement.md
git commit --only -m "docs: add business query enforcement runbook" -- docs/runbook/fresh-business-query-enforcement.md
```

## Implementation notes

- Use `apply_patch` for every source and documentation edit.
- Do not add a database migration; `agent_message_result` already stores the evidence required by the invariant.
- Do not place the Nacos setting in repository `application.properties` or YAML.
- Do not add new business-query Gateway implementations; DIRECT must reuse the current action dispatcher and its existing services.
- Keep all new log messages free of user text and identifiers.
- Before any completion claim or commit, invoke the verification-before-completion workflow and read command output in full.
