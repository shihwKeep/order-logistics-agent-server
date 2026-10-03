# Composite Answer Policy Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在复合查询正文发布前硬校验事实边界，违规时自动纠偏一次，仍违规则返回安全模板。

**Architecture:** 复合模型正文继续由 `ChatTurnExecution` 缓冲。`ChatTurnRunner` 在首次生成后调用独立校验器；校验失败时通过无工具纠偏提示再次生成，最终正文仍交给现有 `FreshBusinessResultGate` 发布。普通问答链路不经过该校验器。

**Tech Stack:** Java 21, Spring Boot, Spring AI `ChatResponse`, Reactor Flux, JUnit 5, AssertJ, Mockito。

---

### Task 1: Add the policy validator and regression tests

**Files:**
- Create: `src/main/java/com/xjjk/agent/chat/service/stream/CompositeAnswerPolicyValidator.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/stream/CompositeAnswerPolicyValidatorTest.java`

- [ ] **Step 1: Write failing tests**

Cover these exact cases:

```java
@Test
void acceptsAdviceWhenNoExecutionClaimIsPresent() {
    assertThat(validator.validate(
            "已达到停滞阈值，客服应生成预警并联系承运商核查。",
            "评估状态=EXCEEDED，适用阈值小时=24")).isEmpty();
}

@Test
void rejectsExecutionTenseWithoutBusinessExecutionFact() {
    assertThat(validator.validate(
            "系统已生成预警，正在核实中。",
            "评估状态=EXCEEDED，适用阈值小时=24"))
            .containsExactly("UNVERIFIED_EXECUTION");
}

@Test
void rejectsValidityInferenceFromTraceText() {
    assertThat(validator.validate(
            "距最新轨迹已超24小时无有效更新。",
            "最新轨迹时间=2026-08-20 15:58:06，距最新轨迹小时=1063"))
            .containsExactly("TRACE_VALIDITY_INFERENCE");
}

@Test
void allowsAClaimWhenTheBusinessContextExplicitlyConfirmsIt() {
    assertThat(validator.validate(
            "系统已生成预警。",
            "评估状态=EXCEEDED，执行结果=已生成预警")).isEmpty();
}
```

- [ ] **Step 2: Run the validator test and verify RED**

Run: `mvn -q -Dtest=CompositeAnswerPolicyValidatorTest test`

Expected: compilation/test failure because the validator class does not exist.

- [ ] **Step 3: Implement the minimal validator**

Implement a package-visible `validate(String answer, String verifiedContext)` method returning an immutable list of rule IDs. Detect execution markers (`已生成预警`, `已联系承运商`, `已启动核查`, `正在核实中`, `已升级`) only when the exact marker is absent from the verified context. Detect validity markers (`无有效更新`, `无效更新`, `乱码`, `非官方`, `轨迹内容异常`) only when absent from the verified context. Return no other rules and never log either input string.

- [ ] **Step 4: Run the validator tests and verify GREEN**

Run: `mvn -q -Dtest=CompositeAnswerPolicyValidatorTest test`

Expected: PASS.

- [ ] **Step 5: Commit the validator**

```bash
git add src/main/java/com/xjjk/agent/chat/service/stream/CompositeAnswerPolicyValidator.java src/test/java/com/xjjk/agent/chat/service/stream/CompositeAnswerPolicyValidatorTest.java
git commit -m "feat: validate composite answer claims"
```

### Task 2: Add a correction prompt and fallback rendering

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java:254-313`
- Test: `src/test/java/com/xjjk/agent/chat/service/model/AiChatServiceToolSelectionTest.java`

- [ ] **Step 1: Write the failing correction-prompt test**

Add a test that calls a new package-visible `groundedCompositeCorrectionPrompt(message, verifiedContext, draft, violations)` and asserts it contains the original question, verified context, draft, rule IDs, and the instruction that only the corrected final answer may be returned.

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -q -Dtest=AiChatServiceToolSelectionTest test`

Expected: compilation failure because the correction prompt method does not exist.

- [ ] **Step 3: Implement correction prompt and safe fallback**

Add `groundedCompositeCorrectionPrompt(...)` that uses no tool instructions and explicitly requires “应/需/建议” for unexecuted actions. Add a package-visible `compositeSafeFallback(String verifiedContext)` returning a neutral six-sentence maximum response that references only status, evaluated threshold, recommended action, and unknown facts; it must never claim that warning, contact, escalation, or verification has already happened.

- [ ] **Step 4: Run the prompt tests and verify GREEN**

Run: `mvn -q -Dtest=AiChatServiceToolSelectionTest test`

Expected: PASS.

- [ ] **Step 5: Commit the prompt helpers**

```bash
git add src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java src/test/java/com/xjjk/agent/chat/service/model/AiChatServiceToolSelectionTest.java
git commit -m "feat: add composite answer correction prompt"
```

### Task 3: Gate composite output before publishing

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java:545-563`
- Modify: `src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java`
- Test: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java`

- [ ] **Step 1: Write failing orchestration tests**

Add tests for: first draft accepted and published unchanged; first draft with `UNVERIFIED_EXECUTION` triggers one correction and publishes the corrected draft; a second violation replaces the content with the safe fallback and publishes no violating draft. Mock the model stream so each response is deterministic and assert the model is invoked once, twice, and twice respectively.

- [ ] **Step 2: Run the orchestration tests and verify RED**

Run: `mvn -q -Dtest=ChatTurnRunnerBusinessQueryTest test`

Expected: failure because composite output is currently accepted without policy validation.

- [ ] **Step 3: Implement buffered validation and one correction attempt**

Inject `CompositeAnswerPolicyValidator` into `ChatTurnRunner` with an optional setter for existing manual test construction. After the first `streamGroundedComposite` completes, validate `execution.content` against `verifiedAnswerContext`. If valid, return. If invalid, truncate the buffered content, call the correction stream once using `AiChatService.groundedCompositeCorrectionPrompt`, and validate again. If still invalid or empty, call `execution.replaceContent(aiChatService.compositeSafeFallback(verifiedAnswerContext))`. Never call `session.delta` from this method; the existing `FreshBusinessResultGate.flush` remains the only publisher for composite text.

- [ ] **Step 4: Add policy outcome logging**

Log only `requestId`, outcome (`ACCEPTED`, `CORRECTED`, `FALLBACK`) and rule IDs. Do not include answer text, order code, trace text, or verified context.

- [ ] **Step 5: Run orchestration tests and verify GREEN**

Run: `mvn -q -Dtest=ChatTurnRunnerBusinessQueryTest test`

Expected: PASS.

- [ ] **Step 6: Commit the gate**

```bash
git add src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnExecution.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerBusinessQueryTest.java
git commit -m "feat: gate composite answer publication"
```

### Task 4: Full regression verification

**Files:**
- No additional source files.

- [ ] **Step 1: Run the focused composite tests**

Run: `mvn -q '-Dtest=CompositeAnswerPolicyValidatorTest,AiChatServiceToolSelectionTest,ChatTurnRunnerBusinessQueryTest,FreshBusinessResultGateTest' test`

Expected: PASS.

- [ ] **Step 2: Run the complete test suite**

Run: `mvn -q test`

Expected: exit code 0. Existing Mockito, Flyway, and Testcontainers warnings are acceptable; any test failure is not.

- [ ] **Step 3: Check the final diff**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors and no unrelated files staged.
