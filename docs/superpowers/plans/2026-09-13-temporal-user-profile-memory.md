# Temporal User Profile Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户画像事实能够按语义自动抽取、携带有效时间并跨会话回答，同时正确区分“过去”和“现在”的职业、年龄等信息。

**Architecture:** MySQL 继续作为记忆事实与时态状态的权威来源，Milvus/Elasticsearch 仅保存可重建的检索索引。模型负责开放语义抽取，服务端负责模式归一化、证据一致性、安全、时态和置信度校验；当前事实采用稳定 canonical key，历史事实采用派生 key，写入新的当前事实时关闭旧的当前版本但保留历史记录。

**Tech Stack:** Java 21、Spring Boot、Spring AI、MyBatis-Plus、Flyway、MySQL 8、Milvus、Elasticsearch、JUnit 5、AssertJ、Mockito。

---

## File map

- `src/main/java/com/xjjk/agent/memory/domain/MemoryTemporalScope.java`: 当前/历史时态枚举。
- `src/main/java/com/xjjk/agent/memory/domain/MemoryFactCandidate.java`: 模型候选增加时态字段，并保留兼容构造器。
- `src/main/java/com/xjjk/agent/memory/domain/MemoryStability.java`: 增加 `TIME_BOUND`。
- `src/main/java/com/xjjk/agent/memory/domain/MemoryCategory.java`: 增加通用画像类别。
- `src/main/java/com/xjjk/agent/memory/domain/ValidatedMemoryFact.java`: 携带 canonical key、时态和有效期。
- `src/main/java/com/xjjk/agent/memory/service/MemorySchemaRegistry.java`: 年龄模式、开放低风险画像模式、时态 canonical key 生成。
- `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`: 抽取协议支持年龄、时间范围和低风险画像。
- `src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java`: 显式保存协议同步支持时态画像。
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java`: 接受 `TIME_BOUND`，拒绝缺失时态或证据不一致候选。
- `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java`: 显式记忆应用相同的时态约束。
- `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java`: 当前事实版本关闭、历史事实追加、写入时态列。
- `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java`: 显式记忆使用相同的时态写入规则。
- `src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemoryEntity.java`: 映射时态列。
- `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`: 当前/历史查询和关闭当前版本。
- `src/main/resources/db/migration/V15__add_temporal_user_profile_memory.sql`: schema v3、时态列、索引和约束。
- `src/main/java/com/xjjk/agent/memory/recall/RecalledMemory.java`: 召回结果携带时态。
- `src/main/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionType.java`: 增加年龄及历史职业问题类型。
- `src/main/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionClassifier.java`: 识别年龄、当前职业和过去职业问法。
- `src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java`: 按问题时态读取相应事实。
- `src/main/java/com/xjjk/agent/memory/answer/DeterministicMemoryAnswerRenderer.java`: 生成带时间限定的可靠回答。
- 对应 `src/test/java/com/xjjk/agent/memory/**` 测试：覆盖协议、模式、写入、迁移、召回、安全与回归。

### Task 1: 建立时态领域模型和数据库契约

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/domain/MemoryTemporalScope.java`
- Modify: `src/main/java/com/xjjk/agent/memory/domain/MemoryStability.java`
- Modify: `src/main/java/com/xjjk/agent/memory/domain/MemoryFactCandidate.java`
- Modify: `src/main/java/com/xjjk/agent/memory/domain/ValidatedMemoryFact.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemoryEntity.java`
- Create: `src/main/resources/db/migration/V15__add_temporal_user_profile_memory.sql`
- Test: `src/test/java/com/xjjk/agent/memory/domain/MemorySemanticDomainTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java`

- [ ] **Step 1: 写失败测试，固定领域枚举与兼容默认值**

```java
assertThat(MemoryStability.valueOf("TIME_BOUND")).isEqualTo(MemoryStability.TIME_BOUND);
MemoryFactCandidate legacy = new MemoryFactCandidate(
        MemoryType.PROFILE, "age", "32", "32岁", "我今年32岁了",
        MemoryStability.TIME_BOUND, 0.96);
assertThat(legacy.temporalScope()).isEqualTo(MemoryTemporalScope.CURRENT);
```

- [ ] **Step 2: 运行领域测试并确认因缺少枚举/字段失败**

Run: `mvn -Dtest=MemorySemanticDomainTest test`

Expected: FAIL，提示 `TIME_BOUND` 或 `temporalScope()` 不存在。

- [ ] **Step 3: 实现枚举、候选时态字段和兼容构造器**

```java
public enum MemoryTemporalScope { CURRENT, HISTORICAL }

public enum MemoryStability { STABLE, TIME_BOUND, TEMPORARY, UNKNOWN }

public MemoryFactCandidate(
        MemoryType memoryType, String predicate, String value,
        String valueEvidence, String evidenceText,
        MemoryStability stability, double confidence) {
    this(memoryType, predicate, value, valueEvidence, evidenceText,
            stability, MemoryTemporalScope.CURRENT, confidence);
}
```

- [ ] **Step 4: 写迁移契约失败测试**

```java
assertThat(sql)
        .contains("ADD COLUMN observed_at DATETIME(3)")
        .contains("ADD COLUMN valid_from DATETIME(3)")
        .contains("ADD COLUMN valid_to DATETIME(3)")
        .contains("ADD COLUMN temporal_scope VARCHAR(16)")
        .contains("schema_version = 3")
        .contains("stability IN ('STABLE', 'TIME_BOUND')");
```

- [ ] **Step 5: 增加 V15 迁移和实体映射**

```sql
ALTER TABLE agent_user_memory
    ADD COLUMN observed_at DATETIME(3) NULL,
    ADD COLUMN valid_from DATETIME(3) NULL,
    ADD COLUMN valid_to DATETIME(3) NULL,
    ADD COLUMN temporal_scope VARCHAR(16) NULL;

UPDATE agent_user_memory
SET observed_at = created_at,
    valid_from = created_at,
    temporal_scope = 'CURRENT',
    schema_version = 3
WHERE schema_version = 2;
```

迁移同时重建结构化事实 CHECK：旧非结构化行仍允许全空；v3 行必须有 `observed_at`、`valid_from`、`temporal_scope`，稳定性只能是 `STABLE` 或 `TIME_BOUND`，时态只能是 `CURRENT` 或 `HISTORICAL`。

- [ ] **Step 6: 运行领域和迁移测试**

Run: `mvn -Dtest=MemorySemanticDomainTest,UserMemoryMigrationContractTest test`

Expected: PASS。

- [ ] **Step 7: 提交领域和迁移改动**

```bash
git add src/main/java/com/xjjk/agent/memory/domain src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemoryEntity.java src/main/resources/db/migration/V15__add_temporal_user_profile_memory.sql src/test/java/com/xjjk/agent/memory/domain/MemorySemanticDomainTest.java src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java
git commit -m "feat: add temporal memory fact model"
```

### Task 2: 实现年龄和开放低风险画像模式

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/domain/MemoryCategory.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/MemorySchemaRegistry.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/MemorySchemaRegistryTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/GeneralSemanticMemoryAcceptanceTest.java`

- [ ] **Step 1: 写失败测试，覆盖年龄和未知低风险画像**

```java
var age = registry.resolve(new MemoryFactCandidate(
        MemoryType.PROFILE, "age", "32", "32岁", "我今年32岁了",
        MemoryStability.TIME_BOUND, MemoryTemporalScope.CURRENT, 0.97));
assertThat(age.canonicalKey()).isEqualTo("profile.age");
assertThat(age.canonicalContent()).isEqualTo("用户曾表示年龄为32岁");
assertThat(age.valueJson()).isEqualTo("\"32\"");

var city = registry.resolve(new MemoryFactCandidate(
        MemoryType.PROFILE, "preferred_city", "南京", "南京", "我更喜欢南京",
        MemoryStability.STABLE, MemoryTemporalScope.CURRENT, 0.95));
assertThat(city.canonicalKey()).startsWith("profile.open.");
```

同时断言 `-1`、`121`、小数年龄、身份证号、电话、精确地址仍被拒绝。

- [ ] **Step 2: 运行模式测试并确认失败**

Run: `mvn -Dtest=MemorySchemaRegistryTest,GeneralSemanticMemoryAcceptanceTest test`

Expected: FAIL，PROFILE 仍只接受 `preferred_name`。

- [ ] **Step 3: 增加年龄模式与通用画像类别**

```java
case "age" -> temporal(
        candidate, "age", "profile.age", "PROFILE_PERSONAL_FACT",
        this::age, value -> "用户曾表示年龄为" + value + "岁", true);
default -> openProfile(candidate);
```

`age()` 仅接受用户证据中明确出现的 0–120 整数；开放 predicate 必须是安全 snake_case，value 必须经过已有敏感内容策略和语义复核，canonical key 由服务端哈希生成。

- [ ] **Step 4: 为历史事实生成不可覆盖当前事实的派生 key**

```java
private String temporalKey(String currentKey, MemoryFactCandidate candidate) {
    if (candidate.temporalScope() == MemoryTemporalScope.CURRENT) {
        return currentKey;
    }
    return currentKey + ".history." + MemoryHashing.sha256(
            candidate.value() + "\n" + candidate.evidenceText());
}
```

- [ ] **Step 5: 运行模式和安全回归测试**

Run: `mvn -Dtest=MemorySchemaRegistryTest,GeneralSemanticMemoryAcceptanceTest,ImplicitMemoryCandidateValidatorTest,ExplicitMemoryCandidateValidatorTest test`

Expected: PASS，低风险画像可扩展，敏感信息仍被拒绝。

- [ ] **Step 6: 提交画像模式改动**

```bash
git add src/main/java/com/xjjk/agent/memory/domain/MemoryCategory.java src/main/java/com/xjjk/agent/memory/service/MemorySchemaRegistry.java src/test/java/com/xjjk/agent/memory/service/MemorySchemaRegistryTest.java src/test/java/com/xjjk/agent/memory/service/GeneralSemanticMemoryAcceptanceTest.java
git commit -m "feat: support temporal profile facts"
```

### Task 3: 升级模型抽取协议和候选校验

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionDecision.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClientTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractorTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidatorTest.java`

- [ ] **Step 1: 写失败协议测试**

```java
String response = """
    {"decision":"LONG_TERM","candidates":[
      {"memoryType":"PROFILE","predicate":"age","value":"32",
       "valueEvidence":"32岁","evidenceText":"我今年32岁了",
       "stability":"TIME_BOUND","temporalScope":"CURRENT","confidence":0.97},
      {"memoryType":"WORK_CONTEXT","predicate":"occupation","value":"Java开发",
       "valueEvidence":"Java开发","evidenceText":"我以前是Java开发",
       "stability":"TIME_BOUND","temporalScope":"HISTORICAL","confidence":0.96}
    ]}
    """;
assertThat(result.candidates()).extracting(c -> c.temporalScope())
        .containsExactly(MemoryTemporalScope.CURRENT, MemoryTemporalScope.HISTORICAL);
```

- [ ] **Step 2: 运行协议测试并确认失败**

Run: `mvn -Dtest=SpringAiImplicitMemoryModelClientTest,SpringAiExplicitMemoryExtractorTest test`

Expected: FAIL，协议尚无 `temporalScope`/`TIME_BOUND`。

- [ ] **Step 3: 修改提示词和 JSON 协议**

提示词明确要求：

```text
从任意用户表达中抽取可复用的低风险用户画像，不依赖固定句式或预设语言枚举。
每个候选必须包含 stability 和 temporalScope。
年龄、当前职位、当前单位等会变化但可跨会话使用的事实标记 TIME_BOUND。
“以前/曾经”标记 HISTORICAL，“现在/目前/今年”标记 CURRENT。
无法判断当前或历史时不要保存。
禁止身份证号、联系方式、账户、健康、精确地址等高风险身份信息；普通年龄、职业、偏好不属于本条禁区。
```

解析层对缺字段、非法枚举和模型额外文本采取 fail-closed。

- [ ] **Step 4: 写校验失败测试并实现稳定性/时态白名单**

```java
assertThatCode(() -> validator.validate(ageCandidate, sourceText))
        .doesNotThrowAnyException();
assertThatThrownBy(() -> validator.validate(unknownTemporalCandidate, sourceText))
        .isInstanceOf(MemoryCandidateValidationException.class);
```

LONG_TERM 仅接受 `STABLE` 和 `TIME_BOUND`；时态必须是 CURRENT/HISTORICAL；继续校验证据逐字存在、规范值一致、敏感内容、置信度和语义复核。

- [ ] **Step 5: 运行协议、校验和安全测试**

Run: `mvn -Dtest=SpringAiImplicitMemoryModelClientTest,SpringAiExplicitMemoryExtractorTest,ImplicitMemoryCandidateValidatorTest,ExplicitMemoryCandidateValidatorTest,MemorySemanticDomainTest test`

Expected: PASS。

- [ ] **Step 6: 提交抽取协议改动**

```bash
git add src/main/java/com/xjjk/agent/memory/domain/MemoryExtractionDecision.java src/main/java/com/xjjk/agent/memory/service/SpringAiImplicitMemoryModelClient.java src/main/java/com/xjjk/agent/memory/service/SpringAiExplicitMemoryExtractor.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCandidateValidator.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCandidateValidator.java src/test/java/com/xjjk/agent/memory/service
git commit -m "feat: extract temporal profile semantics"
```

### Task 4: 实现时态写入、版本关闭与索引事件

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitServiceTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteServiceTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMapperContractTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/persistence/UserMemorySpringTransactionIntegrationTest.java`

- [ ] **Step 1: 写失败测试，固定当前更新和历史追加行为**

```java
verify(memoryMapper).closeCurrentFact(
        tenantId, userId, generation, "work.occupation", observedAt, observedAt);
assertThat(saved.getTemporalScope()).isEqualTo("CURRENT");
assertThat(saved.getObservedAt()).isEqualTo(observedAt);
assertThat(saved.getValidFrom()).isEqualTo(observedAt);
assertThat(saved.getValidTo()).isNull();
```

历史候选测试应断言不调用 `closeCurrentFact`，canonical key 带 `.history.`，且不会覆盖当前事实。

- [ ] **Step 2: 运行写入测试并确认失败**

Run: `mvn -Dtest=ImplicitMemoryCommitServiceTest,ExplicitMemoryWriteServiceTest,UserMemoryMapperContractTest test`

Expected: FAIL，写入链路尚未处理时态。

- [ ] **Step 3: 实现原子关闭当前事实**

```java
@Update("""
    UPDATE agent_user_memory
    SET status = 'SUPERSEDED', valid_to = #{validTo}, updated_at = #{updatedAt}
    WHERE tenant_id = #{tenantId}
      AND user_id = #{userId}
      AND memory_generation = #{generation}
      AND canonical_key = #{canonicalKey}
      AND temporal_scope = 'CURRENT'
      AND status = 'ACTIVE'
    """)
int closeCurrentFact(...);
```

同一事务中：锁定当前 canonical key、关闭旧版本并产生 DELETE outbox、插入新事实并产生 UPSERT outbox。HISTORICAL 事实只追加并 UPSERT，不关闭 CURRENT。

- [ ] **Step 4: 写入 schema v3 和时态列**

```java
memory.setSchemaVersion(3);
memory.setObservedAt(observedAt);
memory.setValidFrom(observedAt);
memory.setValidTo(null);
memory.setTemporalScope(candidate.temporalScope().name());
```

`observedAt` 使用源消息入库时间；无法取得时以服务器当前时间兜底，绝不使用模型生成时间。

- [ ] **Step 5: 运行写入、事务和 outbox 测试**

Run: `mvn -Dtest=ImplicitMemoryCommitServiceTest,ExplicitMemoryWriteServiceTest,UserMemoryMapperContractTest,UserMemorySpringTransactionIntegrationTest,MemoryIndexOutboxWorkerTest test`

Expected: PASS，MySQL 与 outbox 在同一事务中一致。

- [ ] **Step 6: 提交写入链路改动**

```bash
git add src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java src/main/java/com/xjjk/agent/memory/service/ImplicitMemoryCommitService.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java src/test/java/com/xjjk/agent/memory
git commit -m "feat: persist temporal memory versions"
```

### Task 5: 实现当前/历史召回与年龄确定性回答

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/recall/RecalledMemory.java`
- Modify: `src/main/java/com/xjjk/agent/memory/recall/UserMemoryRecallService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionType.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionClassifier.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/answer/DeterministicMemoryAnswerRenderer.java`
- Test: `src/test/java/com/xjjk/agent/memory/answer/DirectMemoryQuestionClassifierTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/answer/DeterministicUserMemoryAnswerServiceTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/answer/DeterministicMemoryAnswerRendererTest.java`
- Test: `src/test/java/com/xjjk/agent/memory/recall/UserMemoryRecallServiceTest.java`

- [ ] **Step 1: 写失败分类测试**

```java
assertThat(classifier.classify("我多大年纪了"))
        .contains(DirectMemoryQuestionType.AGE);
assertThat(classifier.classify("我现在做什么工作"))
        .contains(DirectMemoryQuestionType.CURRENT_OCCUPATION);
assertThat(classifier.classify("我以前做什么工作"))
        .contains(DirectMemoryQuestionType.HISTORICAL_OCCUPATION);
```

- [ ] **Step 2: 写失败召回/渲染测试**

```java
assertThat(renderer.render(AGE, currentAge))
        .contains("根据您之前提供的信息，您当时32岁。");
assertThat(renderer.render(CURRENT_OCCUPATION, currentSeatAgent))
        .contains("您目前的职业是坐席");
assertThat(renderer.render(HISTORICAL_OCCUPATION, historicalJavaDeveloper))
        .contains("您以前从事过Java开发");
```

- [ ] **Step 3: 运行回答测试并确认失败**

Run: `mvn -Dtest=DirectMemoryQuestionClassifierTest,DeterministicUserMemoryAnswerServiceTest,DeterministicMemoryAnswerRendererTest,UserMemoryRecallServiceTest test`

Expected: FAIL，尚无年龄和历史职业分流。

- [ ] **Step 4: 实现时态查询隔离**

```sql
AND temporal_scope = #{temporalScope}
AND (valid_from IS NULL OR valid_from <= #{now})
AND (valid_to IS NULL OR valid_to > #{now})
```

当前问题只读取 CURRENT；历史问题只读取 HISTORICAL。对 schema v2 兼容行按 CURRENT 处理。召回网关返回的向量候选仍须回 MySQL 校验状态、租户、用户、generation、时态与有效期。

- [ ] **Step 5: 实现安全确定性回答**

年龄不依据自然年自动加一，不推断出生日期；统一说“当时 32 岁”。历史职业使用历史限定语，当前职业只展示仍有效的 CURRENT 事实。

- [ ] **Step 6: 运行回答与召回测试**

Run: `mvn -Dtest=DirectMemoryQuestionClassifierTest,DeterministicUserMemoryAnswerServiceTest,DeterministicMemoryAnswerRendererTest,UserMemoryRecallServiceTest,KnowledgeMemoryRecallGatewayTest test`

Expected: PASS。

- [ ] **Step 7: 提交召回和回答改动**

```bash
git add src/main/java/com/xjjk/agent/memory/answer src/main/java/com/xjjk/agent/memory/recall src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemoryMapper.java src/test/java/com/xjjk/agent/memory/answer src/test/java/com/xjjk/agent/memory/recall
git commit -m "feat: answer temporal profile memories"
```

### Task 6: 完整回归、迁移验证与本地合并

**Files:**
- Modify only if a regression reveals a defect in files already listed above.
- Test: all Maven tests.

- [ ] **Step 1: 运行全部测试**

Run: `mvn test`

Expected: `BUILD SUCCESS`，全部测试通过。

- [ ] **Step 2: 检查差异质量**

Run: `git diff --check main...HEAD`

Expected: 无输出。

- [ ] **Step 3: 检查迁移可执行性**

Run: `mvn -Dtest=UserMemoryMigrationContractTest,UserMemoryMySqlIntegrationTest,UserMemorySpringTransactionIntegrationTest test`

Expected: PASS；若 Docker 集成测试由环境条件跳过，必须明确记录跳过原因，不得声称已做真实数据库迁移验证。

- [ ] **Step 4: 本地合并回 main**

```bash
git switch main
git merge --no-ff fix/temporal-user-profile-memory
```

- [ ] **Step 5: 在 main 上复验**

Run: `mvn test`

Expected: `BUILD SUCCESS`。

- [ ] **Step 6: 用户重启 IDEA 中的 agent-server 后执行验收**

验收序列：

1. 会话 A：`我今年32岁了。`
2. 等待隐式抽取任务为 `SAVED`，MySQL 存在 `profile.age`、`TIME_BOUND`、`CURRENT`，outbox 为 `DONE`。
3. 新建会话 B：`我多大年纪了？`
4. 预期：`根据您之前提供的信息，您当时32岁。`
5. 会话 C：`我以前是Java开发，现在是坐席。`
6. 新建会话 D 分别询问当前和以前职业。
7. 预期：当前回答坐席；历史回答 Java 开发；两条记录均有 observed/valid 时间，旧当前记录已关闭且未丢失。

