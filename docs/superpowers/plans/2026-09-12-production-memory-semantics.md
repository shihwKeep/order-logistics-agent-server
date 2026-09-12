# Production Memory Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace fixed-sentence memory admission with a hybrid semantic pipeline and ensure direct memory questions never discard valid current-conversation context.

**Architecture:** A cheap broad gate decides whether a message may contain an explicit memory instruction. A hybrid resolver uses the existing deterministic parser only as a fast path and otherwise invokes a structured semantic extractor; all candidates pass deterministic evidence, category, sensitive-content, retention and confidence checks before transactional persistence. Direct long-term-memory answers return `NOT_HANDLED` on a true miss so the existing current-conversation context and model path can answer the question.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI `ChatClient`, MyBatis-Plus, MySQL/Flyway, Micrometer, JUnit 5, Mockito, AssertJ, Testcontainers

---

## File Structure

- Create `memory/config/ExplicitMemorySemanticProperties.java`: configurable confidence threshold.
- Create `memory/domain/ExplicitMemoryResolution.java`: `SAVE`, `CLARIFY`, and `NONE` result contract.
- Create `memory/service/ExplicitMemoryCandidateGate.java`: broad, non-authoritative candidate gate.
- Create `memory/service/HybridExplicitMemoryResolver.java`: deterministic fast path plus semantic fallback.
- Modify `memory/service/ExplicitMemoryExtractor.java` and `SpringAiExplicitMemoryExtractor.java`: structured semantic resolution from the original message.
- Modify `memory/service/MemoryCategoryContentPolicy.java`: preserve strict fast parsing while adding evidence-based semantic validation and safe free-form names.
- Modify `memory/service/ExplicitMemoryCandidateValidator.java`: validate model candidates without requiring fixed sentence grammar.
- Modify `memory/service/ExplicitMemoryCommandService.java`: resolve, validate, persist, and acknowledge only after success.
- Modify `memory/answer/DeterministicUserMemoryAnswerService.java`: fall through on a true miss or disabled long-term memory.
- Modify `memory/recall/UserMemorySystemPromptPolicy.java`: prohibit unverified “saved” acknowledgements.
- Modify memory configuration, focused tests, transaction tests, and chat runner tests.

### Task 1: Add semantic resolution contracts and candidate gate

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/config/ExplicitMemorySemanticProperties.java`
- Create: `src/main/java/com/xjjk/agent/memory/domain/ExplicitMemoryResolution.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateGate.java`
- Create: `src/test/java/com/xjjk/agent/memory/config/ExplicitMemorySemanticPropertiesTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/domain/ExplicitMemoryResolutionTest.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateGateTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`

- [ ] **Step 1: Write failing contract and gate tests**

Test these exact behaviors:

```java
assertThat(gate.mightContainExplicitMemory("你以后都叫我石海文")).isTrue();
assertThat(gate.mightContainExplicitMemory("从今往后称呼我为小石")).isTrue();
assertThat(gate.mightContainExplicitMemory("后面跟我交流时叫我老师就行")).isTrue();
assertThat(gate.mightContainExplicitMemory("我希望你以后回答得简洁一些")).isTrue();
assertThat(gate.mightContainExplicitMemory("之后默认使用中文回复我")).isTrue();
assertThat(gate.mightContainExplicitMemory("帮我查询订单 JTS0102")).isFalse();
assertThat(gate.mightContainExplicitMemory("签收后多久可以退款")).isFalse();
```

Verify `ExplicitMemoryResolution.save(candidate, path, confidence)` requires a candidate and confidence in `[0,1]`; `clarify(path)` and `none()` cannot carry a candidate. Verify confidence thresholds reject `0`, negative values, NaN and values greater than `1`.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=ExplicitMemorySemanticPropertiesTest,ExplicitMemoryResolutionTest,ExplicitMemoryCandidateGateTest" test
```

Expected: compilation fails because the new contracts do not exist.

- [ ] **Step 3: Implement the contracts and broad gate**

Use this domain shape:

```java
public record ExplicitMemoryResolution(
        Action action,
        ExplicitMemoryCandidate candidate,
        Path path,
        double confidence
) {
    public enum Action { SAVE, CLARIFY, NONE }
    public enum Path { FAST_PATH, SEMANTIC_PATH, NONE }

    public static ExplicitMemoryResolution save(
            ExplicitMemoryCandidate candidate, Path path, double confidence) {
        return new ExplicitMemoryResolution(Action.SAVE,
                Objects.requireNonNull(candidate), Objects.requireNonNull(path), confidence);
    }

    public static ExplicitMemoryResolution clarify(Path path) {
        return new ExplicitMemoryResolution(Action.CLARIFY, null,
                Objects.requireNonNull(path), 0.0);
    }

    public static ExplicitMemoryResolution none() {
        return new ExplicitMemoryResolution(Action.NONE, null, Path.NONE, 0.0);
    }
}
```

The compact constructor must enforce the invariants tested in Step 1.

Use a configuration record:

```java
@Validated
@ConfigurationProperties(prefix = "agent.memory.semantic")
public record ExplicitMemorySemanticProperties(double confidenceThreshold) {
    public ExplicitMemorySemanticProperties {
        if (!Double.isFinite(confidenceThreshold)
                || confidenceThreshold <= 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException("显式记忆语义置信度阈值必须位于(0,1]");
        }
    }
}
```

Configure `agent.memory.semantic.confidence-threshold=0.85` in both application property files.

Implement `ExplicitMemoryCandidateGate` as a length-bounded broad lexical gate. It must recognize independent intent concepts such as `记住/记下/别忘/保存` and future-personalization combinations containing `以后/今后/之后/从今往后/后面` plus `叫我/称呼/回复/回答/交流/使用/默认`. These terms only decide whether to invoke semantic classification; they never authorize persistence.

- [ ] **Step 4: Run tests and verify GREEN**

Run the command from Step 2. Expected: all three test classes pass.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/config/ExplicitMemorySemanticProperties.java src/main/java/com/xjjk/agent/memory/domain/ExplicitMemoryResolution.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateGate.java src/test/java/com/xjjk/agent/memory/config/ExplicitMemorySemanticPropertiesTest.java src/test/java/com/xjjk/agent/memory/domain/ExplicitMemoryResolutionTest.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateGateTest.java src/main/resources/application.properties src/test/resources/application.properties
git commit -m "feat: define semantic memory intent contracts"
```

### Task 2: Make the memory model return semantic actions

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryExtractor.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractorTest.java`

- [ ] **Step 1: Write failing structured-output tests**

Cover these model responses:

```json
{"action":"SAVE","category":"PROFILE_PREFERRED_NAME","canonicalKey":"profile.preferred_name","content":"用户希望被称为石海文","evidenceText":"你以后都叫我石海文","retention":"NORMAL","confidence":0.98}
```

```json
{"action":"CLARIFY","confidence":0.72}
```

```json
{"action":"NONE","confidence":0.99}
```

Assert that malformed JSON, unknown actions/categories/retention, missing evidence, confidence outside `[0,1]`, timeout and executor rejection retain stable extraction error codes and never expose model output in exceptions.

- [ ] **Step 2: Run the extractor test and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=SpringAiExplicitMemoryExtractorTest" test
```

Expected: compilation or assertions fail because the extractor still accepts a pre-detected command and only returns a candidate.

- [ ] **Step 3: Implement semantic structured extraction**

Change the interface to:

```java
public interface ExplicitMemoryExtractor {
    ExplicitMemoryResolution resolve(String originalMessage);
}
```

The Spring AI implementation must:

- accept only the original user message;
- request JSON object output with `action`, candidate fields, retention and confidence;
- map `NONE` and `CLARIFY` without constructing a candidate;
- map `SAVE` into `ExplicitMemoryCandidate` and `ExplicitMemoryResolution.Path.SEMANTIC_PATH`;
- use the existing bounded executor and timeout;
- instruct the model that arbitrary safe names such as `石海文` and `小石` are supported;
- forbid credentials, sensitive personal data, business records and inferred facts;
- never log the raw prompt or model response.

- [ ] **Step 4: Run the extractor test and verify GREEN**

Run the command from Step 2. Expected: every extractor test passes.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryExtractor.java src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java src/test/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractorTest.java
git commit -m "feat: extract semantic explicit memory actions"
```

### Task 3: Validate semantic evidence and safe free-form names

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/MemoryCategoryContentPolicy.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidatorTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParserTest.java`

- [ ] **Step 1: Write failing semantic-validation tests**

Add positive cases for:

```java
candidate(PROFILE_PREFERRED_NAME, "profile.preferred_name",
        "用户希望被称为石海文", "你以后都叫我石海文", NORMAL)
candidate(PROFILE_PREFERRED_NAME, "profile.preferred_name",
        "用户希望被称为小石", "从今往后称呼我为小石", NORMAL)
candidate(PREFERENCE_ANSWER_STYLE, "preference.answer_style",
        "用户偏好简洁回答", "我希望你以后回答得简洁一些", NORMAL)
```

Keep negative cases for injected names, credentials, health data, addresses, business records, invented evidence, mismatched category/key, unsupported content prefixes and names longer than 32 Unicode code points.

- [ ] **Step 2: Run validator tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=ExplicitMemoryCandidateValidatorTest,DeterministicExplicitMemoryCandidateParserTest" test
```

Expected: free-form name and natural-language evidence cases fail under the closed grammar.

- [ ] **Step 3: Separate fast parsing from semantic validation**

Keep `canonicalize(category, value)` unchanged for the deterministic fast path. Add an evidence-support method used by the validator:

```java
public boolean supportsCandidate(
        MemoryCategory category, String evidence, String canonicalContent) {
    return switch (category) {
        case PROFILE_PREFERRED_NAME -> supportsPreferredName(evidence, canonicalContent);
        case PREFERENCE_LANGUAGE -> supportsKnownCanonicalGroup(
                evidence, canonicalContent, LANGUAGES, "用户偏好使用", "交流");
        case PREFERENCE_ANSWER_STYLE -> supportsKnownCanonicalGroup(
                evidence, canonicalContent, ANSWER_STYLES, "用户偏好", "回答");
        case WORK_COMMON_SCOPE -> supportsWorkScope(evidence, canonicalContent);
    };
}
```

For preferred names, require canonical content to start with `用户希望被称为`, extract a 1–32 code-point name, allow Unicode letters/numbers plus spaces, middle dot, underscore and hyphen, reject control/invisible characters, and require normalized evidence to contain the exact extracted name. Continue applying `MemorySensitiveContentPolicy` to the original message, evidence and canonical content.

Change `ExplicitMemoryCandidateValidator` to call `supportsCandidate` rather than requiring both evidence and content to independently match the fast-path grammar. Keep category/key, evidence substring, length, retention and sensitive-data checks.

- [ ] **Step 4: Run validator and parser tests and verify GREEN**

Run the command from Step 2. Expected: semantic cases pass and all previous rejection/fast-path cases remain green.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/service/MemoryCategoryContentPolicy.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidatorTest.java src/test/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParserTest.java
git commit -m "feat: validate natural language memory evidence"
```

### Task 4: Resolve and persist explicit memory through the hybrid pipeline

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/HybridExplicitMemoryResolver.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/HybridExplicitMemoryResolverTest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/observation/UserMemoryMetricsTest.java`

- [ ] **Step 1: Write failing hybrid and command-service tests**

Verify:

- deterministic supported input returns `SAVE/FAST_PATH` without calling the model;
- `你以后都叫我石海文` passes the broad gate and returns the semantic model result;
- ordinary order/refund questions return `NONE` without a model call;
- semantic `SAVE` below the configured confidence threshold becomes `CLARIFY`;
- `NONE` continues the normal chat path;
- `CLARIFY` returns a fixed clarification question and performs no write;
- safe `SAVE` calls the writer once and only then returns “好的，已记住：…”;
- extraction timeout, protocol error, rejected content and persistence failure never return success wording;
- disabled memory performs no model call and no write.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=HybridExplicitMemoryResolverTest,ExplicitMemoryCommandServiceTest,UserMemoryMetricsTest" test
```

Expected: compilation fails because the hybrid resolver and result metrics do not exist.

- [ ] **Step 3: Implement hybrid resolution and transactional acknowledgement**

`HybridExplicitMemoryResolver.resolve(message)` must execute in this order:

```java
Optional<CommandText> command = detector.detect(message);
if (command.isPresent()) {
    Optional<ExplicitMemoryCandidate> fast = parser.parse(command.get());
    if (fast.isPresent()) {
        return ExplicitMemoryResolution.save(fast.get(), FAST_PATH, 1.0);
    }
}
if (!gate.mightContainExplicitMemory(message)) {
    return ExplicitMemoryResolution.none();
}
ExplicitMemoryResolution semantic = extractor.resolve(message);
if (semantic.action() == SAVE
        && semantic.confidence() < properties.confidenceThreshold()) {
    return ExplicitMemoryResolution.clarify(SEMANTIC_PATH);
}
return semantic;
```

`ExplicitMemoryCommandService` must check global/user switches and sensitive content before invoking the semantic model for a gated candidate, validate `SAVE`, call the transactional writer, and generate success text only from `SaveResult`. It must use stable non-content metrics for fast saved, semantic saved, none, clarify, policy rejection, model failure and persistence failure.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all hybrid, command and metric tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/service/HybridExplicitMemoryResolver.java src/test/java/com/xjjk/agent/memory/service/HybridExplicitMemoryResolverTest.java src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java src/test/java/com/xjjk/agent/memory/observation/UserMemoryMetricsTest.java
git commit -m "feat: persist explicit memory through semantic resolution"
```

### Task 5: Preserve current-conversation memory on durable misses

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerResult.java`
- Modify: `src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/UserMemorySystemPromptPolicy.java`
- Modify: `src/test/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerServiceTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerDeterministicMemoryTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/recall/UserMemoryContextRendererTest.java`

- [ ] **Step 1: Write failing fallback tests**

Assert that a classified question with `NOT_INITIALIZED`, disabled memory, or an available result containing no renderable fact returns `NOT_HANDLED`. In `ChatTurnRunnerDeterministicMemoryTest`, verify a direct question with `NOT_HANDLED` calls `context.prepare(...)` and `ai.stream(...)`, allowing the previous same-conversation instruction to be used. Keep the existing test proving an actual durable match bypasses the model.

Add prompt-policy assertions for these rules:

```text
未经记忆写入服务返回成功结果，不得声称已保存、已记住或会永久遵守。
长期记忆没有命中时，必须继续依据当前会话历史；只有两者都没有事实时才能说明尚不知道。
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=DeterministicUserMemoryAnswerServiceTest,ChatTurnRunnerDeterministicMemoryTest,UserMemoryContextRendererTest" test
```

Expected: tests fail because a durable miss currently returns a handled negative answer and the prompt lacks the anti-false-acknowledgement rule.

- [ ] **Step 3: Implement miss fallthrough and prompt constraints**

In `DeterministicUserMemoryAnswerService`, retain deterministic answers only for a real durable match and retain the explicit `UNAVAILABLE` response for infrastructure failure. Record a `FALLTHROUGH` metric and return `DeterministicUserMemoryAnswerResult.notHandled()` for `NOT_INITIALIZED`, `DISABLED`, and available-but-empty results. Remove `NOT_REMEMBERED` and `DISABLED` from the handled result enum if no remaining caller requires them.

Extend `UserMemorySystemPromptPolicy` with the two tested rules. Do not include storage names, internal source labels or implementation details in model-visible context.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all direct-answer, runner and prompt tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerResult.java src/main/java/com/xjjk/agent/memory/observation/UserMemoryMetrics.java src/main/java/com/xjjk/agent/memory/recall/UserMemorySystemPromptPolicy.java src/test/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerServiceTest.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerDeterministicMemoryTest.java src/test/java/com/xjjk/agent/memory/recall/UserMemoryContextRendererTest.java
git commit -m "fix: preserve current conversation memory context"
```

### Task 6: Verify persistence and end-to-end contracts

**Files:**
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerExplicitMemoryTest.java`
- Create: `docs/testing/production-memory-semantics-acceptance.md`

- [ ] **Step 1: Add failing integration assertions**

Use the natural instruction `你以后都叫我石海文` and assert that a successful turn creates one active `USER_EXPLICIT/PROFILE_PREFERRED_NAME` row with canonical content `用户希望被称为石海文` and one `UPSERT` Outbox event in the same transaction. Add a rollback assertion proving neither row remains when persistence fails. At the chat runner boundary, assert that success text is emitted only for a saved result and failure/clarification results never contain `已记住`.

- [ ] **Step 2: Run integration tests and verify RED or missing coverage**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemorySpringTransactionIntegrationTest,ChatTurnRunnerExplicitMemoryTest" test
```

Expected before assertions are satisfied: at least one new assertion fails; after wiring from earlier tasks, the test may already pass, which is acceptable only if temporarily reverting the hybrid resolver causes the new test to fail.

- [ ] **Step 3: Complete the acceptance runbook**

Document exact manual checks:

```text
1. In one conversation send “你以后都叫我石海文”; require a committed-success acknowledgement.
2. In the same conversation ask “你怎么称呼我”; require “石海文”.
3. Start a new conversation and ask again; require “石海文”.
4. Fully exit and restart the desktop app and ask again; require “石海文”.
5. Verify one active MySQL explicit-memory row and a DONE UPSERT Outbox event.
6. Delete the visible memory and verify a later new conversation no longer answers with the deleted value.
```

- [ ] **Step 4: Run focused integration tests**

Run the command from Step 2. Expected: all tests pass with zero failures.

- [ ] **Step 5: Run the full verification suite and package**

Run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
git status --short
git diff main...HEAD --check
```

Expected: 0 test failures/errors, package exits 0, only planned files are changed, and no whitespace errors are reported.

- [ ] **Step 6: Commit final tests and runbook**

```powershell
git add -- src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java src/test/java/com/xjjk/agent/chat/service/stream/ChatTurnRunnerExplicitMemoryTest.java docs/testing/production-memory-semantics-acceptance.md
git commit -m "test: verify production memory semantics"
```
