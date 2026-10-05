# Skip Knowledge on No Business Match Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Avoid calling or publishing knowledge citations when a dependency-first business query has no customer or order match.

**Architecture:** Keep independent composite queries parallel. Mark plans that require business existence gating, route their business branch first, and only dispatch knowledge after a successful business match. A no-match business result ends safely without a knowledge call.

**Tech Stack:** Java 21, Spring Boot, LangGraph4j, JUnit 5, Maven.

---

### Task 1: Define the gated execution behavior with tests

**Files:**
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **Step 1:** Add a failing workflow test proving a no-order dependent plan returns no knowledge results and never calls the knowledge gateway.
- [ ] **Step 2:** Add a failing workflow test proving a matching dependent plan still calls knowledge after the business result is available.
- [ ] **Step 3:** Run the focused tests and confirm the new tests fail because knowledge currently runs in parallel.

### Task 2: Gate knowledge dispatch only for dependency-sensitive plans

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryPlan.java`

- [ ] **Step 1:** Add a plan-level predicate for latest-order dependency or explicit order-existence gating.
- [ ] **Step 2:** Route gated plans through `business.query` first; skip `knowledge.query` when the business result has no matching customer/order.
- [ ] **Step 3:** Preserve the current parallel business/knowledge edges for independent composite plans.
- [ ] **Step 4:** Run the focused workflow tests and confirm both gated and independent paths pass.

### Task 3: Verify result publication and observability

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/observation/CompositeQueryMetrics.java`
- Modify: `src/test/java/com/xjjk/agent/chat/observation/CompositeQueryMetricsTest.java`

- [ ] **Step 1:** Add a bounded knowledge-skip outcome for no business match.
- [ ] **Step 2:** Run the full composite regression suite and package build.
- [ ] **Step 3:** Run `git diff --check`, inspect the diff, and commit.
