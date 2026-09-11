# Explicit Memory Deterministic Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make supported explicit-memory commands such as “请永久记住：叫我老师。” save reliably without depending on model wording.

**Architecture:** Add a deterministic candidate parser that reuses `MemoryCategoryContentPolicy` as the single closed-grammar source. `ExplicitMemoryCommandService` will use the parser after command and sensitive-content checks; unmatched or ambiguous payloads remain rejected, while existing validation and transactional persistence stay unchanged.

**Tech Stack:** Java 21, Spring Boot, Spring AI, MyBatis-Plus, JUnit 5, Mockito, AssertJ, Maven

---

## File structure

- Create `src/main/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParser.java`: map one command payload to exactly one closed memory category and canonical candidate.
- Create `src/test/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParserTest.java`: verify canonical parsing, retention, unsupported input, and ambiguity rejection.
- Modify `src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java`: register the deterministic parser with the existing category policy.
- Modify `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java`: replace the model extractor dependency with deterministic parsing for the supported explicit-memory path.
- Modify `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java`: reproduce the valid-command rejection and verify the parser-to-writer flow.

### Task 1: Reproduce the valid preferred-name rejection

**Files:**
- Modify: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java`

- [ ] **Step 1: Add a regression test for the exact user command**

Add a test that uses the real detector, sensitive policy, category policy, and validator. Configure the existing model extractor mock to return a semantically correct but non-canonical paraphrase, reproducing the current production failure:

```java
@Test
void savesSupportedPermanentPreferredNameWithoutDependingOnModelWording() {
    String message = "请永久记住：叫我老师。";
    when(extractor.extract(
            new ExplicitMemoryCommandDetector.CommandText("叫我老师", true),
            message
    )).thenReturn(new ExplicitMemoryCandidate(
            MemoryCategory.PROFILE_PREFERRED_NAME,
            "profile.preferred_name",
            "称呼用户为老师",
            message,
            MemoryRetentionType.PERMANENT
    ));
    when(writer.save(eq(turn()), any())).thenReturn(
            new ExplicitMemoryWriteService.SaveResult("memory-1", "用户希望被称为老师")
    );

    ExplicitMemoryCommandResult result = service.handle(turn(), message);

    assertThat(result.handled()).isTrue();
    assertThat(result.saved()).isTrue();
    assertThat(result.assistantText()).isEqualTo("好的，已记住：用户希望被称为老师");
}
```

- [ ] **Step 2: Run the regression test and verify RED**

Run:

```powershell
./mvnw.cmd -Dtest=ExplicitMemoryCommandServiceTest#savesSupportedPermanentPreferredNameWithoutDependingOnModelWording test
```

Expected: FAIL because the current validator rejects the model paraphrase and returns `saved=false`.

- [ ] **Step 3: Commit the failing regression test**

```powershell
git add src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java
git commit -m "test: reproduce explicit memory preferred name rejection"
```

### Task 2: Implement deterministic closed-grammar parsing

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParser.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParserTest.java`

- [ ] **Step 1: Write parser behavior tests**

Create tests using the real `MemoryCategoryContentPolicy`:

```java
class DeterministicExplicitMemoryCandidateParserTest {

    private final DeterministicExplicitMemoryCandidateParser parser =
            new DeterministicExplicitMemoryCandidateParser(new MemoryCategoryContentPolicy());

    @Test
    void parsesPermanentPreferredNameIntoCanonicalCandidate() {
        ExplicitMemoryCandidate candidate = parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("叫我老师", true)
        ).orElseThrow();

        assertThat(candidate).isEqualTo(new ExplicitMemoryCandidate(
                MemoryCategory.PROFILE_PREFERRED_NAME,
                "profile.preferred_name",
                "用户希望被称为老师",
                "叫我老师",
                MemoryRetentionType.PERMANENT
        ));
    }

    @Test
    void parsesAllSupportedPreferredNames() {
        for (String name : List.of("老师", "先生", "女士", "同学", "伙伴", "朋友")) {
            assertThat(parser.parse(
                    new ExplicitMemoryCommandDetector.CommandText("叫我" + name, false)
            )).get().extracting(ExplicitMemoryCandidate::content)
                    .isEqualTo("用户希望被称为" + name);
        }
    }

    @Test
    void rejectsUnsupportedAndAmbiguousPayloads() {
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("叫我小王", false)
        )).isEmpty();
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("以后使用中文并简洁回答", false)
        )).isEmpty();
    }
}
```

- [ ] **Step 2: Run parser tests and verify RED**

Run:

```powershell
./mvnw.cmd -Dtest=DeterministicExplicitMemoryCandidateParserTest test
```

Expected: compilation FAIL because `DeterministicExplicitMemoryCandidateParser` does not exist.

- [ ] **Step 3: Add the minimal parser implementation**

Create the parser with one responsibility and no model dependency:

```java
package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DeterministicExplicitMemoryCandidateParser {

    private final MemoryCategoryContentPolicy contentPolicy;

    public DeterministicExplicitMemoryCandidateParser(MemoryCategoryContentPolicy contentPolicy) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
    }

    public Optional<ExplicitMemoryCandidate> parse(
            ExplicitMemoryCommandDetector.CommandText command
    ) {
        Objects.requireNonNull(command, "command");
        List<ExplicitMemoryCandidate> matches = new ArrayList<>();
        for (MemoryCategory category : MemoryCategory.values()) {
            contentPolicy.canonicalize(category, command.payload())
                    .map(content -> new ExplicitMemoryCandidate(
                            category,
                            category.keyPrefix(),
                            content,
                            command.payload(),
                            command.permanent()
                                    ? MemoryRetentionType.PERMANENT
                                    : MemoryRetentionType.NORMAL
                    ))
                    .ifPresent(matches::add);
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }
}
```

- [ ] **Step 4: Run parser tests and verify GREEN**

Run:

```powershell
./mvnw.cmd -Dtest=DeterministicExplicitMemoryCandidateParserTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the parser**

```powershell
git add src/main/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParser.java src/test/java/com/xjjk/agent/memory/service/DeterministicExplicitMemoryCandidateParserTest.java
git commit -m "feat: parse closed explicit memories deterministically"
```

### Task 3: Wire deterministic parsing into explicit-memory saving

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java`

- [ ] **Step 1: Register the parser bean**

Add to `AiMemoryConfiguration`:

```java
@Bean
public DeterministicExplicitMemoryCandidateParser deterministicExplicitMemoryCandidateParser(
        MemoryCategoryContentPolicy contentPolicy
) {
    return new DeterministicExplicitMemoryCandidateParser(contentPolicy);
}
```

- [ ] **Step 2: Replace model extraction in the command service**

Replace the `ExplicitMemoryExtractor` field and constructor argument with `DeterministicExplicitMemoryCandidateParser`. In `handle`, obtain the candidate deterministically and reject unmatched input before persistence:

```java
ExplicitMemoryCandidate extracted = parser.parse(detected.get())
        .orElseThrow(() -> new IllegalArgumentException("MEMORY_CONTENT_REJECTED"));
ExplicitMemoryCandidate candidate = validator.validate(
        extracted, userMessage, detected.get().permanent());
ExplicitMemoryWriteService.SaveResult saved = writer.save(turn, candidate);
```

Do not fall back to `ExplicitMemoryExtractor`; zero or multiple closed-grammar matches must remain rejected.

- [ ] **Step 3: Update the regression test wiring without weakening its assertion**

Construct `ExplicitMemoryCommandService` with a real deterministic parser. Remove the model extractor stub from the regression test and capture the value passed to `writer.save`:

```java
ArgumentCaptor<ExplicitMemoryCandidate> candidate =
        ArgumentCaptor.forClass(ExplicitMemoryCandidate.class);
verify(writer).save(eq(turn()), candidate.capture());
assertThat(candidate.getValue()).isEqualTo(new ExplicitMemoryCandidate(
        MemoryCategory.PROFILE_PREFERRED_NAME,
        "profile.preferred_name",
        "用户希望被称为老师",
        "叫我老师",
        MemoryRetentionType.PERMANENT
));
```

- [ ] **Step 4: Run service and parser tests**

Run:

```powershell
./mvnw.cmd -Dtest=ExplicitMemoryCommandServiceTest,DeterministicExplicitMemoryCandidateParserTest,ExplicitMemoryCandidateValidatorTest test
```

Expected: PASS with no failure or error.

- [ ] **Step 5: Commit service integration**

```powershell
git add src/main/java/com/xjjk/agent/memory/config/AiMemoryConfiguration.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java
git commit -m "fix: save supported explicit memories deterministically"
```

### Task 4: Verify the complete backend

**Files:**
- Verify only; no production file changes expected.

- [ ] **Step 1: Run all memory and chat-runner tests**

Run:

```powershell
./mvnw.cmd -Dtest='com.xjjk.agent.memory.**,ChatTurnRunnerExplicitMemoryTest' test
```

Expected: all selected tests PASS.

- [ ] **Step 2: Run the complete Maven suite**

Run:

```powershell
./mvnw.cmd test
```

Expected: build SUCCESS with zero failures and zero errors.

- [ ] **Step 3: Check the final diff**

Run:

```powershell
git diff --check HEAD~2..HEAD
git status --short
```

Expected: no whitespace errors and no uncommitted implementation files.

- [ ] **Step 4: Perform the manual acceptance test after restart**

Restart `order-logistics-agent-server`, send:

```text
请永久记住：叫我老师。
```

Expected assistant text starts with `好的，已记住：` and both queries return one new row:

```sql
SELECT * FROM agent_user_memory ORDER BY id DESC LIMIT 1;
SELECT * FROM agent_memory_outbox ORDER BY id DESC LIMIT 1;
```

