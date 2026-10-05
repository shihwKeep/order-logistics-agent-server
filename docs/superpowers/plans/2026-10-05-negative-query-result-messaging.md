# Negative Query Result Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve safe partial-query behavior while returning explicit no-customer/no-order messages and tightening price-analysis wording.

**Architecture:** Keep the workflow’s structured result and dependency statuses unchanged. Improve the partial-result safe message from the composite service using verified state, and make order-plus-logistics plans perform an order existence check before dependent logistics. Strengthen the catalog prompt so price analysis distinguishes arithmetic consistency from pricing-policy validity.

**Tech Stack:** Java 21, Spring Boot, LangGraph4j, JUnit 5, Maven.

---

### Task 1: Lock down negative customer and order behavior

**Files:**
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **Step 1: Add failing workflow assertions** for a customer with zero orders: no dependent logistics gateway call, `PARTIAL_SUCCESS`, and a safe context containing “未找到客户订单” and “未执行物流查询”.
- [ ] **Step 2: Add failing planner assertion** that an explicit order-plus-logistics query creates both an order lookup and a logistics intent, so a syntactically valid missing order can be distinguished from a downstream outage.
- [ ] **Step 3: Add failing stream assertion** that a partial result publishes the specific safe message rather than only the generic partial-success sentence.
- [ ] **Step 4: Run the focused tests and confirm they fail for the intended missing behavior.**

### Task 2: Implement structured negative-result handling

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/routing/BusinessQueryPlanner.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryService.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`

- [ ] **Step 1:** Include the order lookup in an explicit order-plus-logistics composite plan when the user asks to query an order, even if they do not request order goods or amount.
- [ ] **Step 2:** Derive partial safe messages from verified branch data: distinguish no matching customer/order from an unavailable failed branch, and always state whether logistics was skipped.
- [ ] **Step 3:** Keep unverified logistics facts out of the message and preserve the existing structured result publication.
- [ ] **Step 4:** Run the focused tests and confirm they pass.

### Task 3: Tighten price-analysis instruction and verify

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/xjjk/agent/chat/routing/BusinessQueryPlannerTest.java`

- [ ] **Step 1:** Add an explicit prompt-catalog constraint that arithmetic consistency may be reported, but pricing-policy or market reasonableness requires a returned pricing baseline.
- [ ] **Step 2:** Run the focused regression suite and package build.
- [ ] **Step 3:** Run `git diff --check`, inspect the final diff, and commit the changes.
