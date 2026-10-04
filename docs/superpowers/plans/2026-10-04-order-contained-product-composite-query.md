# Order-Contained Product Composite Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correctly answer queries about products contained in a known order without issuing a malformed independent product search, while preserving the external-market-data boundary.

**Architecture:** `BusinessQueryPlanner` will treat “订单中的商品” as an order-owned relationship and produce only the order business branch. `CompositeQueryWorkflow` will convert the returned order cards into a bounded, non-sensitive answer context containing order product facts, so the final answer cannot confuse an empty unrelated product search with the order’s actual goods.

**Tech Stack:** Java 21, Spring Boot, LangGraph4j, JUnit 5, AssertJ, Mockito, Maven

---

### Task 1: Stop malformed independent product searches for order-owned goods

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`
- Test: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`

- [ ] **Step 1: Write the failing planner test**

Extend `orderProductAndMarketPriceCreatesUnsupportedExternalMarker` so it asserts the exact business intents:

```java
assertThat(plan.compositePlan().intents())
        .filteredOn(intent -> intent.source() == CompositeQueryIntent.Source.BUSINESS)
        .extracting(CompositeQueryIntent::resultKind, CompositeQueryIntent::value)
        .containsExactly(tuple("order-list", "XJTS0120260820000011"));
assertThat(plan.acceptedResultKinds())
        .doesNotContain("product-list")
        .contains("order-list", "general-analysis", "external-data-unavailable");
```

Add the required static import:

```java
import static org.assertj.core.api.Assertions.tuple;
```

- [ ] **Step 2: Run the planner test and verify RED**

Run:

```powershell
mvn -q -Dtest=BusinessQueryPlannerTest#orderProductAndMarketPriceCreatesUnsupportedExternalMarker test
```

Expected: FAIL because the current plan still contains a `product-list` intent whose value is derived from `订单 XJTS... 中的`.

- [ ] **Step 3: Implement the minimal routing correction**

In `compositePlanFor`, calculate whether the product reference is owned by the identified order and suppress only the independent product intent:

```java
boolean orderOwnedProduct = product && orderCode != null
        && containsAny(message, "订单中的商品", "订单中商品", "订单内商品", "订单里的商品", "订单里商品");

String productIdentifier = extractProductIdentifier(message);
if (product && !orderOwnedProduct && productIdentifier != null) {
    intents.add(CompositeQueryIntent.product(productIdentifier));
}
```

Use a whitespace-tolerant helper or normalized message if required by the existing test phrase `订单 XJTS... 中的商品`; the behavior must be based on the relationship between the complete order identifier and “商品”, not on one exact hard-coded full sentence.

- [ ] **Step 4: Run planner tests and verify GREEN**

Run:

```powershell
mvn -q -Dtest=BusinessQueryPlannerTest test
```

Expected: PASS, including the existing ordinary product query coverage.

- [ ] **Step 5: Commit the planner correction**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java
git commit -m "fix: route order-contained product queries"
```

### Task 2: Expose safe order product facts to composite answer generation

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Test: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`

- [ ] **Step 1: Write the failing workflow test**

Create an `OrderSearchResult` with one `OrderCard` containing one `OrderGoodsSummary`, execute an order plus external-boundary composite plan, and assert:

```java
assertThat(result.verifiedAnswerContext())
        .contains(
                "订单号=XJTS0120260820000011",
                "商品名称=老炊五香牛肉粒",
                "SKU=1020300801",
                "规格=50g/袋",
                "数量=6",
                "订单成交单价=10.00元",
                "当前未接入外部市场数据")
        .doesNotContain("联系电话", "收货地址");
verify(productSearchGateway, never()).search(any());
```

Use the production domain constructors `OrderSearchResult`, `OrderCard`, and `OrderGoodsSummary`; use `CompositeQueryIntent.order(...)` plus `CompositeQueryIntent.externalUnavailable(...)` in the plan.

- [ ] **Step 2: Run the workflow test and verify RED**

Run:

```powershell
mvn -q -Dtest=CompositeQueryWorkflowTest#includesOrderGoodsFactsWithoutIndependentProductSearch test
```

Expected: FAIL because `safeResultText(OrderSearchResult)` currently returns only `订单数量=1`.

- [ ] **Step 3: Implement the bounded order summary**

Replace the order-result branch in `safeResultText` with a dedicated formatter that emits:

```text
订单数量=1；订单号=XJTS0120260820000011，订单状态=在途，商品行数=1；商品名称=老炊五香牛肉粒，SKU=1020300801，规格=50g/袋，数量=6，订单成交单价=10.00元，订单商品小计=60.00元
```

Implementation constraints:

- Read product facts only from `OrderCard.goods()`.
- Limit output to the already bounded cards and goods returned by the order service.
- Omit null or blank fields instead of printing `null`.
- Format fen amounts with two decimal places and the `元` suffix using deterministic integer arithmetic or `BigDecimal`.
- Do not include recipient, phone, address, internal IDs, or unmasked sensitive values.
- When `goods()` is empty, state `未取得订单商品明细`; do not claim the order has no goods.

- [ ] **Step 4: Run workflow tests and verify GREEN**

Run:

```powershell
mvn -q -Dtest=CompositeQueryWorkflowTest test
```

Expected: PASS.

- [ ] **Step 5: Run focused regression tests**

Run:

```powershell
mvn -q -Dtest=BusinessQueryPlannerTest,CompositeQueryWorkflowTest test
```

Expected: PASS.

- [ ] **Step 6: Run the full test suite**

Run:

```powershell
mvn -q test
```

Expected: PASS with zero test failures.

- [ ] **Step 7: Commit the answer-context correction**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java
git commit -m "fix: include order goods in composite context"
```

### Task 3: Verify repository scope and provide manual validation steps

**Files:**
- No production file changes expected

- [ ] **Step 1: Confirm only intended paths were committed**

Run:

```powershell
git status --short
git log -3 --oneline
```

Expected: pre-existing unrelated changes remain uncommitted; the new commits contain only the design, plan, planner/test, and workflow/test paths.

- [ ] **Step 2: Manual end-to-end validation after the user restarts Agent**

Submit:

```text
请查询订单 XJTS0120260820000011 中的商品，并分析当前市场价格区间。
```

Expected:

- The order card shows the order and its goods.
- No malformed product card with keyword `订单XJTS0120260820000011中的` appears.
- The text names the order product from the current order result.
- The text states that external market data is unavailable and does not invent a market price range.
