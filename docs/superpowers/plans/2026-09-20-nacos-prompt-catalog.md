# Nacos Prompt Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every model-facing prompt and Spring AI tool/parameter description from Java source into Nacos, bind it once at startup, and fail application startup when any required value or placeholder is missing.

**Architecture:** Add one immutable typed prompt catalog under `agent.ai.prompt.catalog`, a strict literal placeholder renderer, and a startup validator. Prompt consumers receive the catalog directly; tool callbacks are generated from existing annotated methods and wrapped with Nacos-backed `ToolDefinition` metadata while delegating execution to the original callback.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, Jakarta Validation, Jackson, JUnit 5, AssertJ, Mockito, Maven Wrapper, Nacos properties configuration.

---

## File structure

**Create**

- `src/main/java/com/xjjk/agent/prompt/AgentPromptCatalogProperties.java` — immutable Nacos binding for summary, memory, context, knowledge, and tool prompts.
- `src/main/java/com/xjjk/agent/prompt/StrictPromptTemplateRenderer.java` — literal placeholder extraction and replacement with no expression evaluation.
- `src/main/java/com/xjjk/agent/prompt/AgentPromptCatalogValidator.java` — startup validation for text, required placeholders, and configured tool names.
- `src/main/java/com/xjjk/agent/prompt/ConfiguredToolCallbackFactory.java` — wraps Spring AI callbacks with Nacos descriptions and patched JSON Schema.
- `src/main/java/com/xjjk/agent/prompt/DelegatingConfiguredToolCallback.java` — preserves original execution while exposing the configured definition.
- `src/test/java/com/xjjk/agent/prompt/AgentPromptCatalogPropertiesTest.java`
- `src/test/java/com/xjjk/agent/prompt/StrictPromptTemplateRendererTest.java`
- `src/test/java/com/xjjk/agent/prompt/ConfiguredToolCallbackFactoryTest.java`
- `src/test/java/com/xjjk/agent/prompt/PromptHardcodingContractTest.java`

**Modify**

- `src/main/java/com/xjjk/agent/chat/service/summary/ChatSummaryGenerator.java`
- `src/main/java/com/xjjk/agent/chat/service/summary/ChatSummaryContextRenderer.java`
- `src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java`
- `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`
- `src/main/java/com/xjjk/agent/memory/service/SpringAiMemoryEvidenceVerifier.java`
- `src/main/java/com/xjjk/agent/memory/recall/UserMemorySystemPromptPolicy.java`
- `src/main/java/com/xjjk/agent/memory/recall/UserMemoryContextRenderer.java`
- `src/main/java/com/xjjk/agent/knowledge/tool/KnowledgeQueryTools.java`
- `src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`
- `src/main/java/com/xjjk/agent/product/tool/ProductQueryTools.java`
- `src/main/java/com/xjjk/agent/order/tool/OrderQueryTools.java`
- `src/main/java/com/xjjk/agent/customer/tool/CustomerQueryTools.java`
- `src/main/java/com/xjjk/agent/customer/tool/CustomerOrderQueryTools.java`
- `src/main/java/com/xjjk/agent/aftersale/tool/AfterSaleQueryTools.java`
- Existing unit tests whose constructors gain prompt dependencies.
- `C:/Users/shwfo/.codex/attachments/69366dcc-d3c7-4572-87b1-3cd2dc866f29/已粘贴的文本.txt` — user-provided full Nacos configuration; keep its existing secrets local and uncommitted.

## Configuration key contract

Use these exact keys beneath the existing `agent.ai.prompt.version` and `agent.ai.prompt.system` keys:

```properties
agent.ai.prompt.catalog.summary.system=
agent.ai.prompt.catalog.summary.user-template=
agent.ai.prompt.catalog.memory.explicit.system=
agent.ai.prompt.catalog.memory.explicit.user-template=
agent.ai.prompt.catalog.memory.implicit.system=
agent.ai.prompt.catalog.memory.implicit.user-template=
agent.ai.prompt.catalog.memory.evidence.system=
agent.ai.prompt.catalog.memory.evidence.user-template=
agent.ai.prompt.catalog.context.system-policy=
agent.ai.prompt.catalog.context.summary.open-marker=
agent.ai.prompt.catalog.context.summary.close-marker=
agent.ai.prompt.catalog.context.summary.header-template=
agent.ai.prompt.catalog.context.summary.fact-heading=
agent.ai.prompt.catalog.context.summary.decision-heading=
agent.ai.prompt.catalog.context.summary.open-question-heading=
agent.ai.prompt.catalog.context.summary.entity-heading=
agent.ai.prompt.catalog.context.user-memory.open-marker=
agent.ai.prompt.catalog.context.user-memory.close-marker=
agent.ai.prompt.catalog.context.user-memory.header=
agent.ai.prompt.catalog.context.user-memory.entry-template=
agent.ai.prompt.catalog.knowledge.evidence-header=
agent.ai.prompt.catalog.tools.<tool-name>.description=
agent.ai.prompt.catalog.tools.<tool-name>.parameters.<parameter-name>=
```

Required template placeholders:

| Template | Required placeholders |
|---|---|
| summary.user-template | `promptVersion`, `targetOutputTokens`, `formatCorrection`, `inputJson` |
| memory.explicit.user-template | `promptVersion`, `sourceMessage` |
| memory.implicit.user-template | `promptVersion`, `previousMessage`, `currentMessage` |
| memory.evidence.user-template | `inputJson` |
| context.summary.header-template | `topic`, `currentState` |
| context.user-memory.entry-template | `sourceType`, `category`, `content` |

Tool keys are the existing stable names: `search_products`, `search_orders`, `get_order_logistics`, `search_customers`, `list_customer_orders`, `search_after_sales`, `get_after_sale_detail`, and `search_knowledge`.

### Task 1: Add strict prompt catalog binding and validation

**Files:**
- Create: `src/main/java/com/xjjk/agent/prompt/AgentPromptCatalogProperties.java`
- Create: `src/main/java/com/xjjk/agent/prompt/StrictPromptTemplateRenderer.java`
- Create: `src/main/java/com/xjjk/agent/prompt/AgentPromptCatalogValidator.java`
- Test: `src/test/java/com/xjjk/agent/prompt/AgentPromptCatalogPropertiesTest.java`
- Test: `src/test/java/com/xjjk/agent/prompt/StrictPromptTemplateRendererTest.java`

- [ ] **Step 1: Write failing renderer tests**

Cover successful replacement, multiline values, missing parameters, unexpected parameters, and unresolved placeholders:

```java
assertThat(renderer.render("summary.user-template", "版本={version};输入={input}",
        Map.of("version", "v1", "input", "第一行\n第二行")))
        .isEqualTo("版本=v1;输入=第一行\n第二行");

assertThatThrownBy(() -> renderer.render("summary.user-template", "输入={input}", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("summary.user-template")
        .hasMessageContaining("input");
```

- [ ] **Step 2: Run the renderer test and verify failure**

Run: `./mvnw -Dtest=StrictPromptTemplateRendererTest test`

Expected: compilation fails because `StrictPromptTemplateRenderer` does not exist.

- [ ] **Step 3: Implement literal rendering**

Use `Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}")` to collect placeholders. Require the placeholder set to equal the provided parameter-key set, replace tokens using plain `String.replace`, and reject any residual recognized token. Do not use SpEL or Spring `PropertyPlaceholderHelper`.

- [ ] **Step 4: Write binding and fail-fast tests**

Use `ApplicationContextRunner` with `ConfigurationPropertiesAutoConfiguration` and the catalog/validator beans. One test supplies every required property and expects startup success. Separate tests omit `memory.implicit.system`, omit `{currentMessage}` from the implicit template, and add an unrecognized tool name; each must assert a startup failure containing the exact property path.

- [ ] **Step 5: Implement the typed catalog**

Create nested records `Summary`, `Memory`, `MemoryPrompt`, `Context`, `SummaryContext`, `UserMemoryContext`, `Knowledge`, and `ToolPrompt`. Use `@NotBlank`, `@NotNull`, `@NotEmpty`, and `@Valid`; provide no defaults. Expose tools as `Map<String, ToolPrompt>` and each tool's parameters as `Map<String, String>`.

- [ ] **Step 6: Implement startup validation**

Implement `SmartInitializingSingleton.afterSingletonsInstantiated()` and validate each template against the exact placeholder table above. Require the tools map to contain exactly the eight stable tool names. Error messages must begin with `提示词配置无效:` and contain the full failing key.

- [ ] **Step 7: Run focused tests**

Run: `./mvnw -Dtest=AgentPromptCatalogPropertiesTest,StrictPromptTemplateRendererTest test`

Expected: both test classes pass.

- [ ] **Step 8: Commit only Task 1 files**

```powershell
git add -- src/main/java/com/xjjk/agent/prompt src/test/java/com/xjjk/agent/prompt/AgentPromptCatalogPropertiesTest.java src/test/java/com/xjjk/agent/prompt/StrictPromptTemplateRendererTest.java
git commit -m "feat: add validated Nacos prompt catalog"
```

### Task 2: Externalize summary prompts and summary context text

**Files:**
- Modify: `src/main/java/com/xjjk/agent/chat/service/summary/ChatSummaryGenerator.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/summary/SpringAiChatSummaryModelClient.java`
- Modify: `src/main/java/com/xjjk/agent/chat/service/summary/ChatSummaryContextRenderer.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/summary/ChatSummaryGeneratorTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/summary/ChatSummaryProviderTest.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/memory/ChatContextWithSummaryTest.java`

- [ ] **Step 1: Add failing tests for configured summary text**

Construct a catalog fixture whose summary system value is `CONFIGURED_SUMMARY_SYSTEM` and whose headings are unique test strings. Assert the generated model request and rendered context contain those configured strings and do not contain the previous hardcoded headings.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -Dtest=ChatSummaryGeneratorTest,ChatSummaryProviderTest,ChatContextWithSummaryTest test`

Expected: assertions fail because the production classes still use hardcoded text.

- [ ] **Step 3: Inject the catalog into summary components**

Remove `ChatSummaryGenerator.SYSTEM_PROMPT`. Pass `catalog.summary().system()` to `ChatSummaryModelClient.Request`. Render the User Prompt with `StrictPromptTemplateRenderer` using `promptVersion`, `targetOutputTokens`, `formatCorrection`, and `inputJson`; remove instruction concatenation from `SpringAiChatSummaryModelClient`.

In `ChatSummaryContextRenderer`, replace open/close markers, safety header, topic/state template, and four section headings with `catalog.context().summary()` values. Keep escaping, token budgeting, item ordering, and source labels in code.

- [ ] **Step 4: Run focused tests**

Run: `./mvnw -Dtest=ChatSummaryGeneratorTest,ChatSummaryProviderTest,ChatContextWithSummaryTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 2 paths only**

```powershell
git add -- src/main/java/com/xjjk/agent/chat/service/summary src/test/java/com/xjjk/agent/chat/service/summary src/test/java/com/xjjk/agent/chat/service/memory/ChatContextWithSummaryTest.java
git commit -m "refactor: load summary prompts from Nacos"
```

### Task 3: Externalize long-term memory model prompts

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiMemoryEvidenceVerifier.java`
- Modify: corresponding three test classes under `src/test/java/com/xjjk/agent/memory/service/`

- [ ] **Step 1: Add failing tests for all three configured prompts**

Capture `ChatClient` prompt arguments. Assert explicit extraction receives configured System/User text, implicit extraction substitutes both previous and current messages, and evidence verification receives the JSON input through `{inputJson}` without changing JSON content.

- [ ] **Step 2: Run the tests and verify failure**

Run: `./mvnw -Dtest=SpringAiExplicitMemoryExtractorTest,SpringAiImplicitMemoryModelClientTest,SpringAiMemoryEvidenceVerifierTest test`

Expected: configured sentinel values are absent.

- [ ] **Step 3: Replace constants and string concatenation**

Inject `AgentPromptCatalogProperties` and `StrictPromptTemplateRenderer` into all three classes. Delete `SYSTEM_PROMPT` and `SEMANTIC_SYSTEM_PROMPT`. Render the exact parameter maps defined in the configuration contract and keep JSON serialization, timeouts, retry behavior, parsing, validation, and metrics unchanged.

- [ ] **Step 4: Run focused memory tests**

Run: `./mvnw -Dtest=SpringAiExplicitMemoryExtractorTest,SpringAiImplicitMemoryModelClientTest,SpringAiMemoryEvidenceVerifierTest,GeneralSemanticMemoryAcceptanceTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 3 paths only**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java src/main/java/com/xjjk/agent/memory/service/SpringAiMemoryEvidenceVerifier.java src/test/java/com/xjjk/agent/memory/service
git commit -m "refactor: load memory prompts from Nacos"
```

### Task 4: Externalize memory security and knowledge evidence wrappers

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/recall/UserMemorySystemPromptPolicy.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/UserMemoryContextRenderer.java`
- Modify: `src/main/java/com/xjjk/agent/knowledge/tool/KnowledgeQueryTools.java`
- Modify: relevant tests for these classes.

- [ ] **Step 1: Add failing configured-text tests**

Assert `UserMemorySystemPromptPolicy` appends the configured policy once, `UserMemoryContextRenderer` uses configured markers/header/entry template while preserving sanitization and token limits, and `KnowledgeQueryTools` prefixes answerable evidence with the configured evidence header.

- [ ] **Step 2: Run tests and verify failure**

Run: `./mvnw -Dtest=UserMemoryContextRendererTest,KnowledgeQueryToolsTest,ChatContextWithUserMemoryTest test`

Expected: hardcoded production strings do not match configured sentinels.

- [ ] **Step 3: Inject catalog values**

Derive the memory-policy idempotency marker from the configured `system-policy` value rather than a separate Java constant. Render memory entries with `sourceType`, `category`, and sanitized `content`. Keep bracket/control-character escaping and token calculations unchanged. Replace only the answerable knowledge evidence header; ordinary user-facing failure messages remain code-owned because they are not model instructions.

- [ ] **Step 4: Run focused tests**

Run: `./mvnw -Dtest=UserMemoryContextRendererTest,KnowledgeQueryToolsTest,ChatContextWithUserMemoryTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 4 paths only**

```powershell
git add -- src/main/java/com/xjjk/agent/memory/recall src/main/java/com/xjjk/agent/knowledge/tool/KnowledgeQueryTools.java src/test/java/com/xjjk/agent/memory/recall src/test/java/com/xjjk/agent/knowledge/tool/KnowledgeQueryToolsTest.java src/test/java/com/xjjk/agent/chat/service/memory/ChatContextWithUserMemoryTest.java
git commit -m "refactor: externalize context safety prompts"
```

### Task 5: Configure Spring AI tool descriptions from Nacos

**Files:**
- Create: `src/main/java/com/xjjk/agent/prompt/ConfiguredToolCallbackFactory.java`
- Create: `src/main/java/com/xjjk/agent/prompt/DelegatingConfiguredToolCallback.java`
- Create: `src/test/java/com/xjjk/agent/prompt/ConfiguredToolCallbackFactoryTest.java`
- Modify: the six tool classes listed in File structure.
- Modify: `src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`
- Modify: `src/test/java/com/xjjk/agent/chat/service/model/AiChatServiceToolSelectionTest.java`

- [ ] **Step 1: Write failing callback metadata tests**

Build a small test tool with one required and one optional parameter. Configure tool and parameter descriptions in the catalog. Assert the resulting callback exposes the configured tool description, patched `properties.<name>.description`, unchanged `required` array, and still delegates `call(String, ToolContext)` to the original callback.

- [ ] **Step 2: Run the callback factory test and verify failure**

Run: `./mvnw -Dtest=ConfiguredToolCallbackFactoryTest test`

Expected: compilation fails because the factory and delegating callback do not exist.

- [ ] **Step 3: Implement the delegating callback**

`DelegatingConfiguredToolCallback` must return the replacement `DefaultToolDefinition`, delegate `getToolMetadata()`, `call(String)`, and `call(String, ToolContext)` to the original callback, and reject null constructor arguments.

- [ ] **Step 4: Implement callback configuration and schema validation**

For every callback from `ToolCallbacks.from(toolObject)`, parse `inputSchema()` as an `ObjectNode`. Compare the schema `properties` names with the configured parameter names exactly, set each property's `description`, and construct `new DefaultToolDefinition(name, configuredDescription, patchedSchemaJson)`. Missing, extra, blank, or non-object schema properties must throw `IllegalStateException` containing `agent.ai.prompt.catalog.tools.<tool-name>`.

- [ ] **Step 5: Remove source descriptions and use the factory**

Keep `@Tool(name = "...")` and `@ToolParam(required = false)` as needed, but remove every annotation `description`. Inject `ConfiguredToolCallbackFactory` into `AiChatService` and replace all `ToolCallbacks.from(...)` calls with `configuredToolCallbackFactory.from(...)`. Keep availability filtering and stable-name checks unchanged.

- [ ] **Step 6: Run callback and selection tests**

Run: `./mvnw -Dtest=ConfiguredToolCallbackFactoryTest,AiChatServiceToolSelectionTest,ProductQueryToolsTest,OrderQueryToolsTest,CustomerQueryToolsTest,CustomerOrderQueryToolsTest,AfterSaleQueryToolsTest,KnowledgeQueryToolsTest test`

Expected: all selected tests pass and tool selection order is unchanged.

- [ ] **Step 7: Commit Task 5 paths only**

```powershell
git add -- src/main/java/com/xjjk/agent/prompt/ConfiguredToolCallbackFactory.java src/main/java/com/xjjk/agent/prompt/DelegatingConfiguredToolCallback.java src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java src/main/java/com/xjjk/agent/product/tool/ProductQueryTools.java src/main/java/com/xjjk/agent/order/tool/OrderQueryTools.java src/main/java/com/xjjk/agent/customer/tool/CustomerQueryTools.java src/main/java/com/xjjk/agent/customer/tool/CustomerOrderQueryTools.java src/main/java/com/xjjk/agent/aftersale/tool/AfterSaleQueryTools.java src/main/java/com/xjjk/agent/knowledge/tool/KnowledgeQueryTools.java src/test/java/com/xjjk/agent/prompt/ConfiguredToolCallbackFactoryTest.java src/test/java/com/xjjk/agent/chat/service/model/AiChatServiceToolSelectionTest.java
git commit -m "refactor: configure tool prompts from Nacos"
```

### Task 6: Add the complete prompt catalog to the user's Nacos file

**Files:**
- Modify locally only: `C:/Users/shwfo/.codex/attachments/69366dcc-d3c7-4572-87b1-3cd2dc866f29/已粘贴的文本.txt`

- [ ] **Step 1: Inventory migrated source text**

Before deleting any remaining source prompt, map each original text to one key from the configuration contract. Preserve wording and line order exactly; encode property-file line breaks with `\n` where they are semantically required.

- [ ] **Step 2: Patch the supplied configuration in place**

Insert the catalog immediately after the existing `agent.ai.prompt.system` section. Add all eight tools and every schema parameter. Do not print, commit, or copy existing secret values.

- [ ] **Step 3: Validate the resulting property keys without exposing values**

Run a PowerShell check that reads only key names, rejects duplicates, and compares the resulting catalog keys with the expected key list. Output only missing, duplicate, or extra key names.

Expected: no missing or duplicate catalog keys.

### Task 7: Add hardcoding regression protection and run the full verification

**Files:**
- Create: `src/test/java/com/xjjk/agent/prompt/PromptHardcodingContractTest.java`
- Modify: test fixtures affected by constructor changes.

- [ ] **Step 1: Add a source contract test**

Read production Java files beneath `src/main/java`. Assert there is no text block or constant named `SYSTEM_PROMPT`, `SEMANTIC_SYSTEM_PROMPT`, or `POLICY`, and no `@Tool`/`@ToolParam` annotation contains `description =`. Limit the scan to these prompt signatures so ordinary Chinese logs and user-facing validation messages remain allowed.

- [ ] **Step 2: Run the contract test**

Run: `./mvnw -Dtest=PromptHardcodingContractTest test`

Expected: PASS.

- [ ] **Step 3: Compile the application**

Run: `./mvnw -DskipTests compile`

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run all unit and contract tests**

Run: `./mvnw test`

Expected: `BUILD SUCCESS`; Docker-dependent integration tests may be skipped only if they are already guarded by the project's existing assumptions.

- [ ] **Step 5: Verify startup fails when a required prompt is missing**

Run the catalog context test that omits `agent.ai.prompt.catalog.memory.implicit.system` and confirm its captured startup failure contains that exact key. Then run the complete-context case and confirm it starts.

- [ ] **Step 6: Re-scan production source**

Run:

```powershell
rg -n --glob '*.java' 'SYSTEM_PROMPT|SEMANTIC_SYSTEM_PROMPT|@Tool\([^\r\n]*description\s*=|@ToolParam\([^\r\n]*description\s*=' src/main/java
```

Expected: no matches.

- [ ] **Step 7: Commit the regression test and constructor-fixture updates only**

```powershell
git add -- src/test/java/com/xjjk/agent/prompt/PromptHardcodingContractTest.java src/test/java
git commit -m "test: enforce externalized prompt catalog"
```

Before committing, inspect `git diff --cached --name-only` and remove unrelated pre-existing comment-only files from the index. The local Nacos attachment must never be staged.

### Task 8: Deployment handoff

- [ ] **Step 1: Report the restart contract**

State that Nacos changes do not refresh a running instance. The deployment sequence is: publish the complete config, restart `order-logistics-agent-server`, and confirm startup passes prompt validation before sending traffic.

- [ ] **Step 2: Provide the local complete-config link**

Return a clickable link to the updated attachment file without reproducing secrets in chat.

- [ ] **Step 3: Provide verification evidence**

Report the compile command, focused test commands, full-suite result, hardcoding scan result, and the exact startup-failure test used. Do not claim success if any required check did not run or did not pass.
