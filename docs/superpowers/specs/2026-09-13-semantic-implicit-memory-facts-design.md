# 生产级隐式记忆事实抽取与编程语言召回设计

## 1. 背景与根因

用户在旧会话中表达“我平时用 Java 语言进行开发”，模型已经生成 1 条候选，但任务结果为 `ALL_REJECTED`，没有形成有效的 `AUTO_EXTRACT` 记忆；新会话询问“我平时喜欢用什么语言开发”时因此无法回答。

根因不是 MySQL、ES、Milvus 或跨会话召回故障，而是候选准入仍调用 `MemoryCategoryContentPolicy.isAllowed()`：它会从整句中删除固定事实词和固定填充词，并要求剩余字符为空。自然表达中的“用、语言、进行”等未登记词会导致候选被拒绝。该策略校验的是句型，而不是事实是否受到原文支持。

另有两个结构问题：

1. 编程语言没有独立类别，被塞入 `WORK_COMMON_SCOPE`，导致“职业范围”和“主要编程语言”无法按属性稳定更新、冲突治理和精确查询。
2. 确定性回答层使用 `Java|Python` 正则读取正文，无法支持 Go、Kotlin、TypeScript、C#、C++、Rust、ArkTS 等值。

## 2. 目标

- 自然句式不再作为隐式记忆准入条件；只验证结构、原文证据、事实值、安全策略和置信度。
- 为编程语言建立独立的结构化类别和稳定键。
- 模型只负责理解语义并提出候选，不能自由决定最终持久化正文。
- Java 根据模型提供的原文值片段生成规范正文，阻止模型增加原文不存在的语言。
- 新会话中的直接编程语言问题优先从 MySQL 按类别读取，不依赖 ES/Milvus 刚好取得足够高的相似度。
- 保持旧 `WORK_COMMON_SCOPE` 编程语言记忆可读，避免升级后历史数据立即失效。
- 保持现有用户隔离、generation、软删除、显式优先、Outbox 和索引终审边界。
- 为被拒绝的隐式候选记录不含用户正文的稳定原因。

## 3. 非目标

- 本次不把任意聊天内容都保存为长期记忆。
- 本次不允许保存订单、退款、物流、支付、客户、健康、身份和凭据等敏感或业务流水信息。
- 本次不取消 ES/Milvus；它们继续负责开放语义召回，MySQL 继续作为事实源。
- 本次不新增前端入口或改变显式/隐式记忆的可见性约定。
- 本次不引入第二次远程模型调用。独立语义验证模型保留为后续可插拔能力；当前先用“原文值片段 + 类型化规范器 + 敏感策略”建立可测试、低成本的事实落地边界。

## 4. 方案比较

### 方案 A：继续扩充填充词

把“用、语言、进行”等加入 `WORK_FILLERS`。改动最小，但每出现一种新说法都要补词，继续把自然语言当模板处理，不能接受。

### 方案 B：隐式链路直接改用现有 `supportsCandidate()`

能够修复当前 Java 句式，并继续验证候选正文和证据包含相同的固定事实词。它适合作为短期止血，但编程语言仍混在工作范围中，回答层仍只有 Java/Python，未解决结构根因。

### 方案 C：类型化事实值抽取，推荐并采用

模型输出类别、稳定键、原文事实值片段、完整证据片段和置信度；Java 根据类别规范化事实值并生成持久化正文。全句不再经过固定填充词剥离。该方案兼顾句式泛化、事实可验证性、低调用成本和属性级召回。

## 5. 领域模型

新增记忆类别：

```text
WORK_PROGRAMMING_LANGUAGE
canonical key: work.programming_language
canonical content: 用户常用编程语言是{normalizedValue}
```

隐式模型候选调整为：

```json
{
  "category": "WORK_PROGRAMMING_LANGUAGE",
  "canonicalKey": "work.programming_language",
  "valueText": "java",
  "evidenceText": "我平时用java语言进行开发",
  "confidence": 0.96
}
```

`valueText` 必须逐字存在于 `evidenceText`，`evidenceText` 必须逐字存在于当前用户消息。模型不再输出最终 `content`；最终正文由服务端类型化编码器生成。

现有 `PROFILE_PREFERRED_NAME`、`PREFERENCE_LANGUAGE`、`PREFERENCE_ANSWER_STYLE` 和 `WORK_COMMON_SCOPE` 也使用同一候选结构：

- 称呼：`valueText=石海文`；服务端生成“用户希望被称为石海文”。
- 交流语言：`valueText=英语`；服务端规范为“用户偏好使用英文交流”。
- 回答风格：`valueText=简短`；服务端规范为“用户偏好简洁回答”。
- 工作范围：`valueText=后端开发`；服务端生成“用户常用工作范围是后端开发”。

## 6. 类型化事实值校验

新增专门的事实值策略，职责是把模型候选转换成可信规范事实，而不是判断整句是否符合模板：

1. category 必须是允许枚举，canonicalKey 必须与类别严格对应。
2. confidence 必须有限且达到配置阈值。
3. evidenceText 必须是当前用户消息的逐字子串。
4. valueText 必须是 evidenceText 的逐字子串；比较时只允许 Unicode 规范化、首尾空白和大小写规范化，不允许凭空同义改写。
5. 当前消息、证据和值分别通过敏感内容策略和长度限制。
6. 类别规范器只根据 valueText 生成 content，不采用模型自由文本。

编程语言值允许 Unicode 字母、数字、空格以及 `+ # . _ -`，长度为 1 到 32 个 Unicode code point。常见别名只做值级规范化，例如 `js -> JavaScript`、`ts -> TypeScript`、`golang -> Go`、`c sharp -> C#`；未知但格式安全的原文值保持原样。因此该规则约束的是结构化实体值，不约束用户句型。

工作范围属于更开放的文本值：必须是原文连续片段、通过敏感策略、长度受限，最终正文由固定前缀和该片段组成。提示词要求只有明确、稳定、可长期复用的职业或工作范围才可生成候选，不允许从临时任务推断。

## 7. 显式记忆兼容

显式语义抽取允许输出 `WORK_PROGRAMMING_LANGUAGE`。显式候选当前仍带规范 `content`，校验器通过正文固定前缀取得语言值，并要求该值能由 evidence 支持；不对完整用户句子做固定语法匹配。

现有确定性快速路径继续存在，只作为少量无歧义表达的性能优化，不能限制语义路径的表达能力。

## 8. 写入、版本和兼容

- 新隐式编程语言统一写入 `canonical_key=work.programming_language`。
- 同一用户、租户、generation 下的新值覆盖旧值，旧记录转为 `SUPERSEDED`，并产生 DELETE/UPSERT Outbox。
- 有效显式记忆继续压制同键隐式记忆。
- 不新增数据库列；现有 category、canonical_key、content、evidence_text、confidence 已能表达类型化事实，避免为本次修复制造破坏性迁移。
- 旧 `WORK_COMMON_SCOPE` 中可安全识别的 Java/Python 编程语言记录作为只读回退；新写入不再使用该旧结构。

## 9. 召回与回答

`PROGRAMMING_LANGUAGE` 直接问题首先读取 `WORK_PROGRAMMING_LANGUAGE`。没有新类别记录时，再读取旧 `WORK_COMMON_SCOPE` 作为兼容回退。

新类别正文按固定前缀解码安全值，不再使用 `Java|Python` 正则。回答示例：

```text
根据您之前提供的信息，您平时主要使用 Java。
```

未命中仍返回 `NOT_HANDLED`，让当前会话上下文和普通模型链路继续处理；记忆服务异常与真正未命中继续区分。

开放问题仍由现有 ES/Milvus 混合召回，候选回到 MySQL 按所有者、generation、状态、版本、过期时间和来源终审。

## 10. 可观测性

隐式候选拒绝原因使用不含正文的稳定代码：

- `REJECTED_CONFIDENCE`
- `REJECTED_CATEGORY_KEY`
- `REJECTED_EVIDENCE`
- `REJECTED_SENSITIVE`
- `REJECTED_VALUE_POLICY`

任务无候选继续使用 `MODEL_EMPTY`；候选通过但没有数据变化继续使用 `NO_CHANGE`；保存成功使用 `SAVED`。多个候选全部被不同原因拒绝时使用通用 `ALL_REJECTED`，同时按原因记录指标计数。

日志和指标不得记录 source message、evidenceText、valueText 或规范正文。

## 11. 测试与验收

### 11.1 TDD 回归

首先新增失败测试，证明当前实现会拒绝：

```text
source: 我平时用java语言进行开发
category: WORK_PROGRAMMING_LANGUAGE
key: work.programming_language
valueText: java
evidence: 我平时用java语言进行开发
```

### 11.2 单元测试

- 不同自然句式均生成同一 Java 规范事实。
- Go、Kotlin、TypeScript、C#、C++、Rust、ArkTS 能被安全保存和回答。
- 候选声称 Python、原文只有 Java 时拒绝。
- evidence 不在 source、value 不在 evidence、错误 key、低置信度分别拒绝并产生正确原因。
- 敏感信息、提示注入、超长值、控制字符和非法符号拒绝。
- 编程语言问题优先读取新类别，未命中才回退旧工作范围。

### 11.3 契约与集成测试

- 隐式模型提示词和 JSON 协议包含 valueText 与新类别。
- 隐式 Worker 将拒绝原因映射到任务结果和指标，不记录正文。
- MySQL 事务验证新类别写入、同键替换、显式优先和 Outbox 同事务。
- 索引请求继续携带 category、canonicalKey 和服务端生成的 content，不修改 Knowledge Service 现有接口。

### 11.4 端到端验收

1. 会话 A 发送“我平时用 Java 语言进行开发”。
2. 等待异步任务结果为 `SAVED`，确认 MySQL 存在 `ACTIVE / AUTO_EXTRACT / WORK_PROGRAMMING_LANGUAGE / work.programming_language`。
3. 确认 UPSERT Outbox 被消费；ES/Milvus 暂时延迟不影响第 4 步。
4. 新建会话 B，询问“我平时喜欢用什么语言开发”。
5. 应回答 Java，且直接回答指标为 `PROGRAMMING_LANGUAGE / ANSWERED`。
6. 完全退出桌面应用并重启，再次询问仍应回答 Java。

## 12. 发布与回滚

- 本次仅修改 Agent Server；Knowledge Service 和前端协议保持兼容。
- 合并后需要重启 Agent Server，新的隐式候选协议才会生效。
- 已完成但被旧规则拒绝的历史任务不会自动重放；验收时需要发送一条新的用户陈述。
- 回滚代码不会破坏已有表结构；新类别记录对旧代码只是不认识的普通类别，不会被错误展示为显式记忆。
