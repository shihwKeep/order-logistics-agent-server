# Logistics Stagnation Assessment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make composite logistics answers use deterministic server-side elapsed-time evaluation instead of model arithmetic.

**Architecture:** `CompositeQueryWorkflow` will evaluate each shipment timeline with an injected `Clock` before building the verified answer context. A focused evaluator will map the returned logistics status to the applicable threshold, parse the latest trace timestamp, and return an explicit exceeded/within/unknown result. The model will only verbalize this result.

**Tech Stack:** Java 17, Spring Boot, LangGraph4j, JUnit 5, AssertJ.

---

### Task 1: Add failing evaluator tests

**Files:**
- Create: `src/test/java/com/xjjk/agent/order/domain/LogisticsStagnationEvaluatorTest.java`

- [ ] Add tests for a 24-hour mainline shipment already beyond the threshold, a shipment within the threshold, and a missing timestamp.
- [ ] Run the focused test and verify it fails because the evaluator does not exist yet.

### Task 2: Implement deterministic logistics evaluation

**Files:**
- Create: `src/main/java/com/xjjk/agent/order/domain/LogisticsStagnationAssessment.java`
- Create: `src/main/java/com/xjjk/agent/order/domain/LogisticsStagnationEvaluator.java`

- [ ] Implement status-to-stage mapping and thresholds: linehaul 24h, uncollected 6h, delivery 12h, cold-chain 2h.
- [ ] Parse the logistics timestamp formats returned by the service and calculate elapsed time using an injected `Clock` in Asia/Shanghai.
- [ ] Return an explicit unknown assessment for blank or unparseable timestamps.
- [ ] Run evaluator tests and verify they pass.

### Task 3: Add assessment to the LangGraph4j verified context

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflow.java`
- Modify: `src/test/java/com/xjjk/agent/chat/orchestration/CompositeQueryWorkflowTest.java`

- [ ] Inject a testable `Clock` and evaluator into the workflow while preserving the existing constructor.
- [ ] Append deterministic assessment fields to the logistics context consumed by the second-stage model.
- [ ] Add a regression assertion for the current order scenario showing the threshold is exceeded.
- [ ] Run the workflow tests and the complete relevant Maven test set.

### Task 4: Verify the user-facing boundary

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java` only if the context wording needs a narrow clarification.

- [ ] Confirm the grounded composite prompt instructs the model to use the server assessment and not recalculate time.
- [ ] Re-run the focused tests and inspect the final diff; do not change downstream services or Elasticsearch configuration.
