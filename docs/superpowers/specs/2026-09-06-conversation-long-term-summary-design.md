# 会话级长期滚动摘要设计

## 1. 背景

当前项目已经具备以下会话能力：

- `agent_conversation` 保存会话归属、消息序号、请求占用和稳定历史游标。
- `agent_message` 保存完整原始消息。
- `memory_version` 标识稳定历史版本。
- `memory_until_sequence` 标识稳定历史边界。
- MySQL 是会话消息的权威数据源。
- Redis 缓存经过消息条数和正文字节预算筛选的短期历史快照。
- 请求调用模型前，再根据当前问题和模型配置执行 Token 预算选择。
- `ChatHistoryChangedEvent` 在事务提交后异步预热 Redis 短期历史快照。

随着单个会话持续增长，仅保留近期原始问答会导致较早上下文无法进入模型。长期摘要用于把较早的稳定历史压缩成一份有界、可追溯的会话摘要，同时继续保留最近原始问答。

## 2. 目标

本阶段实现生产可用的会话级长期滚动摘要：

1. 一个 `conversation_id` 对应一份当前有效摘要。
2. 摘要与近期原始问答按照连续消息边界衔接。
3. 摘要生成异步执行，不阻塞 SSE 聊天主链路。
4. 摘要任务持久化，可在应用重启、模型超时和多实例并发下恢复。
5. 摘要生成、校验和提交均有明确版本及覆盖边界。
6. 模型只生成摘要草稿，版本、来源、边界和可信度由 Java 代码决定。
7. 摘要不能代替商品、订单、物流等实时业务查询结果。
8. 摘要和原始消息都受租户及用户归属约束。
9. 所有运行参数进入 Nacos，真实密钥继续来自运行环境变量。

## 3. 非目标

本阶段不实现：

- 跨 `conversation_id` 的坐席记忆。
- 终端客户记忆或用户画像。
- Milvus 语义记忆召回。
- ES 或 Milvus RAG。
- 商品、订单、物流等业务工具。
- 删除已经被摘要覆盖的原始消息。
- 让摘要直接修改或替代实时业务数据。
- 为长期摘要新增 Redis 缓存。

跨会话记忆在后续 Milvus 和业务主体模型明确后独立设计。当前摘要只服务一个会话内部的上下文压缩。

## 4. 核心术语与不变量

### 4.1 `last_message_sequence`

会话已经分配的最后一个消息序号，可能包含当前仍在生成的助手消息。

### 4.2 `memory_version`

稳定历史版本。每当助手消息从 `GENERATING` 进入最终状态，正常收尾或过期恢复都会使其加一。

它回答“稳定历史是否发生变化”，不是消息数量，也不是摘要版本。

### 4.3 `memory_until_sequence`

当前稳定历史已经到达的消息序号边界。该边界内仍需按照请求、角色、状态和结构规则判断如何进入短期历史或摘要。

### 4.4 `summary_version`

当前会话长期摘要成功更新的次数。第一次摘要为版本 1，生成或提交失败时不能推进。

### 4.5 `summary_until_sequence`

当前摘要流程已经连续处理到的最后一条消息序号：

- 成功问答的用户问题和助手回答会进入摘要。
- 非成功问答的用户诉求和未完成状态会进入摘要，部分助手回答默认不作为事实来源。
- 数据结构损坏的轮次不能越过。

摘要读取起点永远是 `summary_until_sequence + 1`。

### 4.6 `last_evaluated_memory_version`

摘要 Worker 最后成功判断过的稳定历史版本。它只表示某个稳定版本已经检查，不表示对应消息已经被摘要。

当候选内容未达到普通触发条件时，可以推进该字段，但不能推进 `summary_until_sequence`。出现新增稳定历史后，`memory_version` 再次大于该字段，Worker 重新检查累计候选。

### 4.7 核心不变量

任何时候必须满足：

```text
0 <= summary_until_sequence <= memory_until_sequence <= last_message_sequence
0 <= last_evaluated_memory_version <= requested_memory_version
source_memory_version <= 当前 memory_version
```

摘要边界必须停在一个完整终态轮次的助手消息上，不能拆分问答，不能跨越结构损坏的消息。

## 5. 总体架构

```text
ChatTurnFinishService / ChatTurnRecoveryService
    │
    ├── 同一事务：保存最终消息、推进稳定历史游标
    ├── 同一事务：合并长期摘要任务目标
    └── 提交后事件：预热 Redis 短期历史快照

持久化摘要 Worker
    ├── 领取任务和租约
    ├── 读取当前摘要及连续候选历史
    ├── 判断 Token、轮数、扫描和上下文压力
    ├── 生成结构化摘要草稿
    ├── Java 校验、脱敏和来源约束
    └── 乐观锁提交摘要及任务进度

下一轮 ChatContextPreparationService
    ├── 加载当前摘要
    ├── 加载 Redis/MySQL 短期历史快照
    ├── 按 summary_until_sequence 去重
    ├── 统一执行 Token 预算选择
    └── 组装摘要、近期原文和当前问题
```

Redis 短期预热允许丢失，因为可以回源 MySQL；长期摘要任务不允许只依赖内存事件或内存线程池，必须保存在 MySQL。

## 6. 数据模型

### 6.1 `agent_conversation_summary`

一条会话最多有一条当前有效摘要。新会话不创建空摘要，第一次摘要成功时才插入。

字段：

| 字段 | 说明 |
|---|---|
| `id` | 数据库自增主键 |
| `tenant_id` | 租户 ID |
| `user_id` | 坐席用户 ID |
| `conversation_id` | 所属会话 ID |
| `summary_version` | 当前摘要版本，从 1 开始 |
| `covered_until_sequence` | 即 `summary_until_sequence` |
| `source_memory_version` | 生成本版摘要时捕获的稳定历史版本 |
| `schema_version` | 摘要 JSON 结构版本 |
| `content_json` | 经过校验的结构化摘要 |
| `prompt_version` | 摘要提示词版本 |
| `model_name` | 摘要生成模型 |
| `input_tokens` | 本次生成输入 Token |
| `output_tokens` | 本次生成输出 Token |
| `created_at` | 创建时间，UTC，毫秒精度 |
| `updated_at` | 更新时间，UTC，毫秒精度 |

约束和索引：

- `conversation_id` 唯一。
- 普通联合索引 `(tenant_id, user_id, conversation_id)`。
- 外键引用 `agent_conversation(conversation_id)`，使用 `ON DELETE RESTRICT` 和 `ON UPDATE RESTRICT`。
- 所有业务查询显式携带 `tenant_id + user_id + conversation_id`。

当前表只保存最新有效摘要。原始消息永久保留，因此摘要错误或算法升级时可以重建。摘要历史审计表不在本阶段范围内。

### 6.2 `agent_summary_task`

同一会话复用一条摘要调度状态记录，不为每轮问答永久追加一条任务流水。

字段：

| 字段 | 说明 |
|---|---|
| `id` | 数据库自增主键 |
| `task_id` | 日志和诊断使用的 UUID |
| `tenant_id` | 租户 ID |
| `user_id` | 坐席用户 ID |
| `conversation_id` | 所属会话 ID |
| `requested_memory_version` | 最新待检查稳定版本 |
| `requested_until_sequence` | 目标版本对应的稳定边界 |
| `last_evaluated_memory_version` | 最后完成判断的稳定版本 |
| `force_generation` | 是否因上下文压力要求绕过普通最小阈值 |
| `force_reason` | 强制原因，如 `RAW_CONTEXT_PRESSURE` |
| `status` | 调度状态 |
| `retry_count` | 连续失败次数 |
| `next_run_at` | 最早可执行时间 |
| `lease_token` | 本次领取资格 UUID |
| `locked_by` | 当前处理实例 |
| `locked_until` | 租约失效时间 |
| `last_error_code` | 安全错误码，不存原始异常文本 |
| `created_at` | 创建时间，UTC，毫秒精度 |
| `updated_at` | 更新时间，UTC，毫秒精度 |

约束和索引：

- `task_id` 唯一。
- `conversation_id` 唯一。
- 联合索引 `(tenant_id, user_id, conversation_id)`。
- 正常领取索引 `(status, next_run_at, id)`。
- 租约恢复索引 `(status, locked_until, id)`。
- 外键引用 `agent_conversation(conversation_id)`，删除和更新均限制。

### 6.3 任务状态

```text
IDLE        当前已经检查到目标版本，暂时无工作
PENDING     有新版本或强制请求等待检查
PROCESSING  已被一个 Worker 领取
RETRY       上次失败，等待退避重试
DEAD        超过限制或遇到永久错误
```

`PROCESSING` 必须同时具有 `lease_token`、`locked_by` 和 `locked_until`；其他状态不持有租约。

## 7. 任务登记与合并

每当稳定历史推进，正常收尾和过期恢复都在原数据库事务内合并摘要任务目标：

```text
requested_memory_version = max(旧值, 新 memory_version)
requested_until_sequence = max(旧值, 新 memory_until_sequence)
```

所有最终状态都会登记检查，因为非成功轮次中的用户诉求需要作为未解决事项被连续处理。是否真正生成摘要由 Worker 的状态策略和触发策略决定。

如果任务当前为 `PROCESSING`，新历史只推进请求目标，不立即改变处理状态，避免第二个 Worker 重复领取。当前 Worker 完成捕获版本后，如发现请求目标更大，则把任务恢复为 `PENDING`。

任务登记只写游标和调度元数据，不读取正文、不估算 Token、不调用模型，因此不会把远程调用放入聊天收尾事务。

## 8. Worker 领取、租约与多实例并发

Worker 使用短事务和 MySQL 8 `FOR UPDATE SKIP LOCKED` 批量领取：

```text
status IN (PENDING, RETRY)
next_run_at <= now
```

领取时生成唯一 `lease_token`，写入实例标识和租约到期时间，然后提交领取事务。模型调用发生在事务之外。

后续提交必须同时校验：

- 任务仍为 `PROCESSING`。
- `lease_token` 与本次领取一致。
- 摘要版本和原覆盖边界与捕获值一致。

租约到期的 `PROCESSING` 任务由补偿调度器转为 `RETRY`。旧 Worker 即使稍后返回，也会因为租约令牌失效而无法提交。

系统允许极端情况下发生重复模型计算，但不允许过期结果覆盖新摘要。

## 9. 候选消息读取

### 9.1 两条独立读取链路

短期历史和长期摘要都读取 MySQL 权威消息，但不能从已经裁剪的 Redis 短期快照生成摘要：

```text
短期历史：从最新向以前读取，目标是保留最近问答
长期摘要：从 summary_until_sequence + 1 向后读取，目标是连续压缩较早问答
```

摘要读取器使用独立的消息数、正文字节和单批 Token 保护参数。

### 9.2 先元数据、后正文

先读取：

- `request_id`
- `message_sequence`
- `role`
- `status`
- 正文字节数

元数据用于分组、校验完整性、计算保留边界和控制读取预算。确定本批轮次后再读取 `MEDIUMTEXT` 正文。

### 9.3 完整轮次规则

一轮必须具有：

- 相同 `request_id` 的一条 `USER` 和一条 `ASSISTANT`。
- 用户消息在前、助手消息在后，序号连续。
- 租户、用户和会话归属一致。
- 助手消息已经是最终状态。

单批预算不足时，在上一轮助手消息处停止，不能拆开问答。

### 9.4 状态策略

- `SUCCESS`：用户问题和完整助手回答都可进入摘要输入。
- `FAILED`、`TIMEOUT`、`CANCELLED`、`INTERRUPTED`、`OUTPUT_LIMIT`、`OUTPUT_ERROR`：保留用户诉求、最终状态和未解决标记，默认不使用部分助手回答作为事实来源。
- `GENERATING`：不允许进入摘要。
- 缺失消息、角色错误、请求分组错误等结构异常：停止在异常轮次之前，不越过边界，并记录 `SUMMARY_HISTORY_STRUCTURE_INVALID`。

## 10. 最近原始轮次与摘要候选

Worker 从稳定历史末尾按真实完整轮次识别最近保留区域，不能通过消息序号简单减去 `轮数 × 2` 猜测。

初始保留最近 4 个完整终态轮次。摘要候选为：

```text
(summary_until_sequence, eligible_summary_end_sequence]
```

其中 `eligible_summary_end_sequence` 是最近保留区域之前最后一个完整轮次的助手消息序号。

## 11. 触发策略

摘要生成采用组合触发：

```text
TOKEN_THRESHOLD       候选 Token 达到普通阈值
TURN_THRESHOLD        候选完整轮数达到兜底阈值
SCAN_LIMIT            即将达到扫描或读取安全上限
RAW_CONTEXT_PRESSURE  未摘要原始尾部即将无法连续进入聊天上下文
BACKLOG_CONTINUATION   上一批成功后仍存在可摘要积压
```

初始运行参数：

```properties
agent.chat.summary.trigger-tokens=4096
agent.chat.summary.trigger-turns=30
agent.chat.summary.retain-recent-turns=4
agent.chat.summary.raw-tail-max-tokens=3072
agent.chat.summary.max-batch-messages=200
agent.chat.summary.max-batch-bytes=1048576
agent.chat.summary.max-batch-tokens=12000
```

这些值是首个可运行基线，必须根据真实 Token 估算偏差、空档率、摘要调用成本和响应质量继续校准。

当候选未达到普通阈值时，可以推进 `last_evaluated_memory_version`，但摘要边界保持不变。下一次出现新稳定版本时，仍从 `summary_until_sequence + 1` 重新累计，不能从检查游标之后读取。

`RAW_CONTEXT_PRESSURE` 和 `BACKLOG_CONTINUATION` 可以绕过普通最小 Token 阈值。

Worker 只有在以下条件同时成立时才能跳过任务：

```text
requested_memory_version == last_evaluated_memory_version
force_generation == false
```

请求侧检测到上下文空档时，可以在不改变稳定历史版本的情况下把 `force_generation` 设为 `true`，并把任务调度为 `PENDING`。因此，即使请求版本与已检查版本相等，Worker 也会重新读取从 `summary_until_sequence + 1` 开始的累计候选，并以 `RAW_CONTEXT_PRESSURE` 绕过普通最小阈值。

强制摘要成功或重新检查确认压力已经消失后才能清除强制标记；模型、校验或提交失败时保留可重试状态，不能因版本相等而丢弃该请求。

## 12. 分批滚动摘要

一次模型调用的预算包括摘要系统提示词、JSON Schema、旧摘要、新增历史、输出预留和安全余量。新增历史只能使用扣除这些固定成本后的剩余预算。

积压超过单批上限时，从 `summary_until_sequence + 1` 开始连续选择完整前缀：

```text
旧摘要 + 第一批原文 → 新摘要版本 N，推进覆盖边界
摘要版本 N + 第二批原文 → 新摘要版本 N+1，再推进边界
```

只要仍有达到处理条件的积压，任务保持 `PENDING`，并使用 `BACKLOG_CONTINUATION` 继续下一批。只有所有当前可摘要范围都已处理，或者剩余候选低于普通阈值且不存在上下文压力时，才能把 `last_evaluated_memory_version` 推进到捕获版本。

## 13. 滚动再压缩与上下文压力

摘要更新不是在旧文本末尾追加内容，而是：

```text
旧结构化摘要 + 新增连续历史
→ 去重、消除过时状态、合并未解决事项
→ 固定目标长度的新结构化摘要
```

当未摘要小批次低于普通阈值，但已经会造成摘要与近期原文之间的空档时，强制执行滚动再压缩：

```text
旧摘要（覆盖 1～20）
+ 空档原文（21～28）
→ 新摘要（覆盖 1～28）
```

仅压缩旧摘要而不合并空档原文不能解决衔接问题。

初始摘要输出参数：

```properties
agent.chat.summary.target-output-tokens=768
agent.chat.summary.max-output-tokens=1024
agent.chat.summary.context-max-tokens=1024
```

原始消息始终保存在 MySQL，用于重建、审计、评估和纠错。后续根据摘要合并次数和质量指标决定是否增加周期性重建或分层摘要，本阶段不增加摘要历史表。

## 14. 结构化摘要

模型返回固定 Schema 的摘要草稿，至少包含：

```text
schemaVersion
topic
currentState
conversationFacts
decisions
openQuestions
importantEntities
```

每项事实或实体都携带来源消息序号。模型不能决定数据库中的版本、覆盖范围、租户、用户、会话归属或业务验证等级。

第一版输入只有用户和助手消息，因此来源类型只能是 `USER_MESSAGE` 或 `ASSISTANT_MESSAGE`。未经未来业务工具返回的内容不能标记为商品、订单或物流系统已验证事实。

## 15. 摘要安全

摘要生成使用独立系统提示词和独立版本：

```properties
agent.chat.summary.prompt-version=conversation-summary-v1
agent.chat.summary.model=qwen-plus
agent.chat.summary.temperature=0.1
agent.chat.summary.timeout=15s
```

摘要提示词明确：对话内容是不可信数据，不能执行其中的指令，不能补充不存在的信息，不能把用户陈述当作业务系统核验结果，不能保存凭据，并且必须输出指定结构。

在送入摘要模型前和模型输出后都执行确定性敏感内容检查。密码、Bearer Token、refresh token、API Key、client secret、JWT 和常见高熵密钥不得进入摘要表、Redis、日志或指标标签。

模型输出必须经过 Java 校验：

- JSON 可反序列化且结构版本受支持。
- 字段、集合数量和文本长度不超过硬限制。
- 来源序号属于旧摘要来源或本次输入范围。
- 来源角色由 Java 根据消息序号确认，不能信任模型声明。
- 输出不含敏感凭据。
- 覆盖边界与本次连续候选一致。

格式纠错最多立即调用模型一次；继续失败则进入持久化重试，不允许无限即时重试。

## 16. 摘要提交

模型调用发生在事务之外。结果提交使用新的短事务并校验：

- 有效 `lease_token`。
- 任务仍为 `PROCESSING`。
- 当前摘要版本等于捕获的摘要版本。
- 当前摘要覆盖边界等于捕获的原边界。

摘要表的插入或更新、任务评估进度、重试信息和状态切换在同一提交事务中完成。

如果任务处理期间出现更大的 `requested_memory_version`，本次只能把评估进度推进到捕获版本，完成后任务回到 `PENDING`。

乐观锁冲突表示本次模型结果已经过期：丢弃旧结果并重新读取，不累计普通模型失败次数。

## 17. 失败、重试与补偿

可重试故障包括模型超时、限流、临时网络错误、临时数据库异常和首次格式错误。任务进入 `RETRY`，使用指数退避和随机抖动。

不可重试故障包括缺失关键配置、不支持的 Schema、永久模型配置错误和数据库结构不兼容。任务进入 `DEAD` 并告警。

摘要失败时：

- 不推进 `summary_version`。
- 不推进 `summary_until_sequence`。
- 不把失败版本标记为已完成评估。
- 不影响已经成功提交的聊天消息。

补偿调度器负责：

- 恢复租约过期的 `PROCESSING` 任务。
- 重新调度达到 `next_run_at` 的重试任务。
- 扫描稳定历史明显落后但任务缺失或异常的会话。

## 18. 上下文读取与衔接

下一轮请求先取得 MySQL 稳定历史游标，再加载当前摘要和 Redis/MySQL 短期历史快照。

摘要必须满足：

```text
summary_version >= 1
covered_until_sequence <= memory_until_sequence
source_memory_version <= memory_version
schema_version 受当前代码支持
```

短期快照可能包含已经被摘要覆盖的原始轮次。进入模型前必须以摘要边界过滤完整轮次：

```text
user_sequence > summary_until_sequence
assistant_sequence > summary_until_sequence
```

因此缓存层允许重叠，模型输入层不允许重叠。旧 Redis Key 依靠 TTL 自然过期，不原地修改。

## 19. 统一 Token 预算

预算包含：

```text
系统提示词
+ 受限长期摘要
+ 近期完整原始问答
+ 当前问题
+ 安全余量
```

后续工具定义、工具结果和 RAG 内容也必须纳入同一预算器。

选择时保证当前问题和系统规则，优先保留最近完整原始问答，并把摘要限制在 `context-max-tokens` 内。发送顺序仍为：

```text
系统提示词
长期摘要
最近原始问答（旧到新）
当前问题
```

摘要由 Java 以固定标签和栏目渲染成不可信历史上下文，不能直接拼进高权限系统提示词。真正的系统提示词明确规定摘要不能覆盖系统规则，也不能代替实时工具结果。

## 20. 重叠、空档与连续性

理想输入连续覆盖：

```text
摘要 1～20 + 原文 21～40 + 当前问题 41
```

如果 Token 预算只能选中原文 29～40，则本次模型输入存在 21～28 的临时空档。任何系统都无法在硬 Token 上限下保证所有历史始终可见，因此本设计采用：

1. `RAW_CONTEXT_PRESSURE` 在空档形成前强制滚动摘要。
2. 多批积压通过 `BACKLOG_CONTINUATION` 连续追赶。
3. 请求侧发现空档时，持久化 `force_generation` 请求并加速后台摘要。
4. 当前请求不等待摘要模型，继续使用已提交摘要和最近原文。
5. 显式记录空档起止序号和指标。

`ChatContextSelection` 需要表达摘要边界、原文起止、是否存在空档以及空档范围。不能把预算省略误报为已经被摘要覆盖。

## 21. 摘要加载降级

摘要不存在或发生非权限类读取、解析错误时：

- 记录 `summary_load_failed`。
- 忽略本次摘要。
- 允许短期历史从更早范围参与选择。
- 当前聊天继续执行。

租户或用户归属失败、跨租户数据不一致等安全错误不能降级为继续读取摘要。

摘要第一版直接从 MySQL 读取，不增加新的 Redis 摘要缓存。上线后根据查询耗时、QPS 和数据库压力决定是否增加版本化缓存。

## 22. 建议组件边界

```text
summary/
├── ChatSummaryTaskScheduler
├── ChatSummaryTaskWorker
├── ChatSummaryCandidateLoader
├── ChatSummaryTriggerPolicy
├── ChatSummaryGenerator
├── ChatSummaryValidator
├── ChatSummaryCommitService
├── ChatSummaryRecoveryScheduler
├── ChatSummaryProvider
└── ChatSummaryContextRenderer
```

- `ChatSummaryTaskScheduler`：在收尾事务中合并任务目标，或登记上下文压力强制请求。
- `ChatSummaryTaskWorker`：编排领取、加载、判断、生成、校验和提交。
- `ChatSummaryCandidateLoader`：读取连续候选，校验轮次和预算。
- `ChatSummaryTriggerPolicy`：计算触发原因，不调用模型。
- `ChatSummaryGenerator`：使用独立摘要客户端生成草稿，不写数据库。
- `ChatSummaryValidator`：校验结构、来源、边界、长度和敏感内容。
- `ChatSummaryCommitService`：短事务乐观锁提交摘要和任务状态。
- `ChatSummaryRecoveryScheduler`：租约、重试和遗漏任务补偿。
- `ChatSummaryProvider`：按租户、用户、会话读取当前摘要并降级。
- `ChatSummaryContextRenderer`：确定性渲染受控摘要上下文。

HTTP Controller、SSE 协议和模型流循环不直接感知摘要持久化细节。

## 23. 线程与运行配置

首个运行基线：

```properties
agent.chat.summary.enabled=true
agent.chat.summary.worker.core-pool-size=1
agent.chat.summary.worker.max-pool-size=2
agent.chat.summary.worker.claim-batch-size=10
agent.chat.summary.worker.poll-interval=5s
agent.chat.summary.worker.lease-duration=60s
agent.chat.summary.retry.max-attempts=5
agent.chat.summary.retry.initial-delay=10s
agent.chat.summary.retry.max-delay=10m
agent.chat.summary.retry.jitter=5s
```

全部配置写入 Nacos。密钥只通过环境变量提供。摘要 Worker 使用独立有界执行器，不能占用 SSE 流线程池和 Redis 预热线程池。

## 24. 可观测性

结构化日志记录：

```text
taskId
requestId（存在时）
conversationId
capturedMemoryVersion
summaryVersion
summaryUntilSequence
candidateTurns
candidateTokens
triggerReason
hasContextGap
gapFromSequence
gapUntilSequence
durationMs
retryCount
result
errorCode
```

日志不得记录用户正文、摘要正文、Token、密码和密钥。

低基数指标包括：

```text
summary_task_pending
summary_task_processing
summary_task_retry
summary_task_dead
summary_triggered_total
summary_skipped_total
summary_generated_total
summary_failed_total
summary_stale_result_total
summary_lease_recovered_total
summary_context_gap_total
summary_generation_duration
summary_input_tokens
summary_output_tokens
```

租户、用户、会话和任务 ID 只进入日志，不作为 Micrometer 标签。

## 25. 验证要求

### 25.1 数据与边界

1. 新会话无摘要时行为与当前一致。
2. 成功轮次能够连续进入摘要。
3. 非成功轮次保留用户诉求和未完成状态。
4. `GENERATING` 和结构异常轮次不能被越过。
5. 最近保留轮次不进入摘要。
6. 单批不足时不能拆开完整问答。
7. 多批积压连续推进，无永久空档。
8. 剩余候选低于阈值时只推进检查版本，不推进摘要边界。
9. 新历史到来后，旧候选和新增候选能够继续累计。

### 25.2 并发与恢复

1. 多实例只允许有效租约持有者提交。
2. 处理中新版本到来后任务重新进入 `PENDING`。
3. 租约过期能够恢复。
4. 乐观锁冲突不能覆盖新摘要。
5. 模型失败、格式失败和数据库失败均不推进摘要边界。
6. 应用重启后持久化任务继续执行。

### 25.3 上下文

1. 摘要覆盖范围不会重复作为原文进入模型。
2. 摘要与近期原文按时间顺序组合。
3. Token 不足时保持完整问答，不做字符串截断。
4. 上下文空档可以检测、记录并触发压力摘要。
5. 摘要损坏时降级使用短期历史。
6. 其他租户或用户无法读取摘要。

### 25.4 安全与质量

1. 摘要中的提示注入不能覆盖系统规则。
2. 敏感凭据不能进入摘要存储、Redis、日志和指标。
3. 用户陈述不能被升级为业务系统核验事实。
4. 实时工具结果将来能够覆盖摘要中的过期信息。
5. 摘要结构、来源序号和字段长度均由 Java 校验。

## 26. 发布策略

1. 先创建摘要表和任务表，功能开关保持关闭。
2. 部署只登记和观察任务但不调用摘要模型的影子模式，验证候选范围、Token、触发原因和并发状态。
3. 对测试租户启用摘要生成，但暂不注入聊天上下文，进行人工质量评估。
4. 对测试租户启用上下文注入，验证回答连续性、空档率和Token成本。
5. 分租户逐步放量，保留快速关闭开关。
6. 关闭时不删除摘要和原始消息；聊天自动回退到现有短期历史链路。

## 27. 后续阶段

会话级长期摘要稳定后，项目继续按以下顺序推进：

1. 会话列表、创建、切换、标题和归档能力。
2. 意图识别与工具路由。
3. 商品查询工具。
4. 订单与物流工具。
5. Milvus + ES RAG。
6. 跨会话记忆的独立数据治理和语义召回设计。
