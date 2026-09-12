# Implicit Memory Work Scope Extraction Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure the implicit-memory model recognizes a directly stated, stable Java development work scope instead of returning an empty candidate list.

**Architecture:** Keep the existing asynchronous model-first extraction pipeline and fail-closed Java validation unchanged. Strengthen only the isolated model system prompt with an explicit `WORK_COMMON_SCOPE` definition and a validator-compatible positive JSON example, then protect the prompt contract with a focused unit test.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI 1.1, JUnit 5, Mockito, AssertJ, Maven Wrapper.

---

## File Structure

- Modify `src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java`: capture the system prompt sent through `ChatClient` and assert the work-scope definition and positive example are present.
- Modify `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`: clarify the allowed implicit-memory domain and add one strict `WORK_COMMON_SCOPE` example while preserving all safety constraints.

### Task 1: Lock the Work-Scope Prompt Contract

**Files:**
- Test: `src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java`

- [ ] **Step 1: Write the failing prompt-contract test**

Add these imports:

```java
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.verify;
```

Add a test that invokes the real `SpringAiImplicitMemoryModelClient`, captures the string passed to `.system(...)`, and checks the complete prompt contract:

```java
@Test
void definesStableWorkScopeWithValidatorCompatibleExample() {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec =
            mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec responseSpec =
            mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(anyString())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(responseSpec);
    when(responseSpec.content()).thenReturn("{\"candidates\":[]}");
    SpringAiImplicitMemoryModelClient client = new SpringAiImplicitMemoryModelClient(
            chatClient, properties(Duration.ofSeconds(1), 3), executor(), new ObjectMapper());

    client.extract(new ImplicitMemoryModelClient.Request(
            "request-1", "我平时主要做 Java 开发。", null));

    ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
    verify(requestSpec).system(systemPrompt.capture());
    assertThat(systemPrompt.getValue())
            .contains("WORK_COMMON_SCOPE 表示用户直接明确表达、可长期复用的职业方向、常用技术栈或稳定业务范围")
            .contains("我平时主要做 Java 开发。")
            .contains("\"category\":\"WORK_COMMON_SCOPE\"")
            .contains("\"canonicalKey\":\"work.common_scope\"")
            .contains("\"content\":\"用户常用工作范围是Java开发\"")
            .contains("禁止账号凭据、身份信息、健康信息、订单、退款、物流、支付、客户资料、企业制度、临时任务")
            .contains("只输出 candidates JSON 数组");
}
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=SpringAiImplicitMemoryModelClientTest' test
```

Expected: FAIL because the current system prompt does not contain the `WORK_COMMON_SCOPE` definition or Java-development positive example.

- [ ] **Step 3: Commit the failing regression test**

```powershell
git add -- src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java
git commit -m "test: reproduce empty work scope memory extraction"
```

### Task 2: Strengthen the Extraction Prompt

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`

- [ ] **Step 1: Implement the minimal prompt change**

Replace the opening prompt sentence about extracting only preferences with wording that covers stable user attributes and work scope. Add the definition and one positive example below the category/key rules:

```java
只提取用户本轮直接明确表达、可长期复用的稳定用户特征、偏好或常用工作范围；不确定时返回 {"candidates":[]}。
WORK_COMMON_SCOPE 表示用户直接明确表达、可长期复用的职业方向、常用技术栈或稳定业务范围。
正例：当前用户消息“我平时主要做 Java 开发。”应输出
{"candidates":[{"category":"WORK_COMMON_SCOPE","canonicalKey":"work.common_scope","content":"用户常用工作范围是Java开发","evidenceText":"我平时主要做 Java 开发。","confidence":0.95}]}。
```

Retain the existing closed category list, preferred-name allowlist, exact-evidence requirement, forbidden-content list, untrusted-history rule, and JSON-only output rule unchanged.

- [ ] **Step 2: Run the focused test and verify GREEN**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=SpringAiImplicitMemoryModelClientTest' test
```

Expected: PASS.

- [ ] **Step 3: Run the related memory regression tests**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=SpringAiImplicitMemoryModelClientTest,ImplicitMemoryCandidateValidatorTest,ImplicitMemoryTaskWorkerTest,MemoryCategoryContentPolicyTest' test
```

Expected: PASS with no test failures.

- [ ] **Step 4: Commit the production fix**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java
git commit -m "fix: clarify implicit work scope extraction"
```

### Task 3: Verify the Branch and Prepare Runtime Validation

**Files:**
- Verify only; no additional source files.

- [ ] **Step 1: Run the full unit test suite**

Run:

```powershell
.\mvnw.cmd -q test
```

Expected: Maven exits with code `0` and reports no failures or errors.

- [ ] **Step 2: Check repository hygiene**

Run:

```powershell
git status --short
git diff --check main...HEAD
```

Expected: no uncommitted files and no whitespace errors.

- [ ] **Step 3: Merge into the main working tree after review**

From `D:\GitCode\order-logistics-agent-server`:

```powershell
git merge --ff-only fix/implicit-memory-work-scope-extraction
```

Expected: fast-forward succeeds without conflicts.

- [ ] **Step 4: Perform runtime acceptance after the user restarts Agent Server**

Send `我平时主要做 Java 开发。` in a new conversation, wait for the extraction task, and verify:

```sql
SELECT status, result_code, model_candidate_count,
       accepted_candidate_count, saved_memory_count
FROM agent_memory_extraction_task
ORDER BY id DESC
LIMIT 1;
```

Expected: `DONE`, `SAVED`, and counts `1, 1, 1` (or another internally consistent positive candidate count if the model emits more than one allowed candidate). Then create a second conversation and ask `我平时主要使用什么编程语言？`; the answer should use the Java work-scope memory.
