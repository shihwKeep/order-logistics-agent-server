# Customer TimeLimiter Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `customerSearch` circuit breaker honor `integration.customer.resilience.call-timeout` instead of Resilience4j's one-second default.

**Architecture:** Register the customer integration's named `TimeLimiterConfig` in `TimeLimiterRegistry`, following the established order integration pattern. Keep the existing circuit-breaker configuration and Nacos property contract unchanged.

**Tech Stack:** Java 21, Spring Cloud CircuitBreaker, Resilience4j, JUnit 5, AssertJ, Maven

---

### Task 1: Reproduce the named TimeLimiter configuration gap

**Files:**
- Create: `src/test/java/com/xjjk/agent/customer/config/CustomerIntegrationCircuitBreakerConfigurationTest.java`

- [ ] **Step 1: Write the failing test**

Create a test that configures a seven-second customer call timeout, invokes `registerTimeLimiterConfiguration`, and asserts that `TimeLimiterRegistry` contains a named `customerSearch` configuration with the same duration and cancellation enabled.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -q -Dtest=CustomerIntegrationCircuitBreakerConfigurationTest test`

Expected: compilation failure because `registerTimeLimiterConfiguration` does not exist.

### Task 2: Register the customer TimeLimiter by name

**Files:**
- Modify: `src/main/java/com/xjjk/agent/customer/config/CustomerIntegrationCircuitBreakerConfiguration.java`

- [ ] **Step 1: Implement the minimal production change**

Inject `TimeLimiterRegistry`, register the `customerSearch` configuration from `integration.customer.resilience.call-timeout`, and keep the factory builder configuration aligned with it.

- [ ] **Step 2: Run the focused test and verify GREEN**

Run: `mvn -q -Dtest=CustomerIntegrationCircuitBreakerConfigurationTest test`

Expected: PASS.

- [ ] **Step 3: Run the complete test suite**

Run: `mvn -q test`

Expected: exit code 0.

- [ ] **Step 4: Commit only the focused implementation and test**

Stage the customer configuration, its test, and these two design documents without staging unrelated worktree changes. Commit directly to `main` with `fix: honor customer integration timeout`.
