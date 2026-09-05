# Redis 会话历史快照缓存设计

## 1. 背景与目标

`order-logistics-agent-server` 已将会话消息持久化到 MySQL，并通过
`memory_version` 与 `memory_until_sequence` 标记已经进入终态的稳定历史。
每次模型调用前，系统仍需扫描消息元信息并读取历史正文。随着会话变长和并发增加，
重复读取 MySQL 会增加数据库压力和上下文准备延迟。

本设计在不改变 MySQL 权威地位的前提下，为稳定历史窗口增加 Redis 缓存，目标如下：

- 缓存最近的完整成功问答轮次，降低重复数据库读取开销。
- 严格隔离租户、用户和会话，禁止跨身份复用历史。
- Redis 故障时自动回源 MySQL，不影响正常对话。
- 避免在数据库事务内访问 Redis。
- 避免数据库与 Redis 强一致双写。
- 为后续摘要记忆预留组合位置，但本阶段不实现摘要生成。

## 2. 非目标

本阶段不实现以下能力：

- 不缓存身份认证结果、Bearer Token 或 SSPX 登录状态。
- 不缓存系统提示词、当前用户问题或最终 Token 预算筛选结果。
- 不把 Redis 作为消息或摘要的唯一持久化介质。
- 不生成长期会话摘要。
- 不实现跨会话语义记忆；该能力后续由结构化存储及 Milvus + ES 承担。
- 不修改 `sspx-server` 和 Gateway。

## 3. 核心原则

### 3.1 MySQL 是唯一权威源

权限归属、当前请求占用、稳定历史版本和消息终态全部以 MySQL 为准。
Redis 中的数据只有在其身份范围、版本和边界与数据库当前状态一致时才允许使用。

### 3.2 只缓存稳定历史

缓存只包含 `memory_until_sequence` 之前已经结束的消息。处于
`GENERATING` 状态的助手消息和当前用户问题不进入缓存。

历史轮次仍沿用现有规则：同一 `request_id` 下相邻的 USER 与 ASSISTANT
消息均为 `SUCCESS` 时，才构成可提交给模型的完整轮次。失败、中断、超时和输出受限
的轮次虽然位于稳定消息边界内，但不会成为模型历史。

### 3.3 缓存故障采用 Fail-Open

Redis 超时、连接失败、反序列化失败、内容校验失败均视为缓存未命中，系统记录安全日志和指标后回源 MySQL。缓存故障不能直接导致用户对话失败。

MySQL 权限校验、占用校验或历史读取失败不能由 Redis 掩盖，仍按现有业务错误处理。

## 4. 方案选择

### 4.1 采用：版本化 Key + 提交后异步预热 + 读取时回源补写

缓存 Key 包含稳定版本和边界：

```text
agent:chat:history:v1:{tenantId}:{userId}:{conversationId}:{memoryVersion}:{memoryUntilSequence}:{policyFingerprint}
```

`policyFingerprint` 由影响缓存内容的读取策略生成，至少包含：

- `maxScanMessages`
- `maxReadBytes`
- 缓存结构版本

读取策略变化后会自然使用新 Key，不会误用旧策略生成的历史窗口。

单纯在缓存未命中后写入当前版本无法产生有效命中：每轮收尾都会推进
`memory_version`，下一轮会访问一个新版本。因此本方案在数据库收尾事务成功提交后，
异步预热新版本缓存；读取路径仍保留未命中回源和补写，覆盖预热延迟或失败的场景。

### 4.2 不采用：固定 Key + 收尾时删除

数据库提交成功但 Redis 删除失败时会留下旧值。虽然可以通过值内版本检查规避误用，
但仍需要处理覆盖竞争和删除补偿，边界复杂度高于版本化 Key。

### 4.3 不采用：数据库事务内同步写 Redis

Redis 不参与 MySQL 本地事务。同步双写会延长锁持有时间，并产生一方成功、一方失败的
不一致窗口，因此不采用。

## 5. 组件职责

### 5.1 稳定历史游标加载器

在短只读事务中完成：

- 根据 `tenantId`、`userId`、`conversationId` 查询会话。
- 校验当前 `activeRequestId` 属于本轮请求。
- 查询本轮用户消息序号。
- 校验 `beforeSequence == memoryUntilSequence + 1`。
- 返回内部稳定游标对象。

该组件只访问 MySQL，不访问 Redis。

### 5.2 历史快照数据库加载器

根据已经验证的身份范围和稳定边界读取消息元信息及正文，执行现有的完整成功轮次筛选、
消息数量限制和正文读取预算限制，生成 `ChatHistorySnapshot`。

读取条件必须显式包含租户、用户、会话和排他消息边界，不能仅凭会话 ID 查询。

### 5.3 历史快照缓存

封装 Redis 访问、Key 生成、JSON 序列化、TTL 和缓存内容校验，不包含业务权限判断。
对外提供安全的读取与尽力写入接口：

- 读取异常转换为缓存未命中。
- 写入异常只记录日志和指标，不中断主流程。
- 反序列化后重新构造领域对象，复用领域校验，不直接信任 Redis 内容。
- 日志不输出问答正文。

### 5.4 历史快照提供器

在数据库事务之外编排：

1. 从 MySQL 取得并验证稳定游标。
2. 根据游标和读取策略生成缓存 Key。
3. 尝试读取 Redis。
4. 命中且校验通过则返回缓存快照。
5. 未命中或缓存异常则从 MySQL 加载快照。
6. 将数据库结果尽力写入 Redis后返回。

现有 `ChatContextPreparationService` 只依赖该提供器，不直接感知 Redis。

### 5.5 历史变更事件与预热监听器

`ChatTurnFinishService` 和 `ChatTurnRecoveryService` 在成功推进稳定游标后发布内部历史变更事件。事件至少包含：

- `tenantId`
- `userId`
- `conversationId`
- 新的 `memoryVersion`
- 新的 `memoryUntilSequence`

监听器使用 `AFTER_COMMIT`，只有数据库事务提交成功后才执行，并由独立的有界线程池异步预热缓存。监听器不得阻塞 SSE 收尾，也不得将预热失败传播给客户端。

预热前重新读取数据库游标：

- 与事件版本一致：加载并写入该版本缓存。
- 数据库已经推进到更高版本：跳过过时事件，避免无效查询。
- 数据库版本低于事件版本或身份范围不存在：记录异常指标，不写缓存。

## 6. 缓存内容与序列化

缓存值使用显式版本的 JSON DTO，不直接序列化 MyBatis 实体或 Spring AI 消息对象。内容包括：

- `schemaVersion`
- `tenantId`
- `userId`
- `conversationId`
- `memoryVersion`
- `memoryUntilSequence`
- `beforeSequence`
- 完整成功历史轮次
- `hasEarlierMessages`
- `readBudgetTruncated`

读取后必须校验：

- 身份范围与请求一致。
- `memoryVersion` 和 `memoryUntilSequence` 与数据库游标一致。
- `beforeSequence == memoryUntilSequence + 1`。
- 历史轮次严格升序、不重叠且小于边界。
- 正文和集合不为空，字段长度与总字节数不超过配置限制。

任何校验失败都不使用缓存值。

## 7. TTL 与容量控制

初始 TTL 为 30 分钟，并添加 0 至 5 分钟的随机抖动，避免大量 Key 同时过期。
TTL、抖动上限和 Key 前缀由 `agent.chat.history.cache` 配置管理并进行启动校验。

版本化 Key 会产生旧版本数据，但旧版本只保留到 TTL 到期，不在收尾事务中主动删除。
单个缓存值继续受 `maxScanMessages` 和 `maxReadBytes` 约束，避免超大对象进入 Redis。

生产 Redis 需要配置认证、网络隔离、容量上限和淘汰策略。敏感凭据通过运行环境或密钥管理系统注入，不写入 Nacos 明文配置或代码仓库。

## 8. 与长期记忆的边界

后续摘要记忆必须持久化到 MySQL，并使用独立字段或表维护：

- `summary_version`
- `summary_until_sequence`
- `summary_content`
- `summary_updated_at`

Redis 可以缓存最新摘要，但不能作为摘要唯一存储。模型上下文最终由以下部分组成：

```text
系统提示词 + 历史摘要 + 摘要边界之后的最近完整轮次 + 当前问题
```

`memory_until_sequence` 表示稳定消息边界，`summary_until_sequence` 表示摘要覆盖边界，二者语义不同，不能复用。

## 9. 错误处理与可观测性

建议记录以下指标或结构化日志字段：

- `chat_history_cache_hit`
- `chat_history_cache_miss`
- `chat_history_cache_error`
- `chat_history_cache_invalid`
- `chat_history_cache_write_success`
- `chat_history_cache_warm_skipped`
- 缓存读取、数据库回源和预热耗时
- `memoryVersion`、`memoryUntilSequence`、`policyFingerprint`

日志只能输出元信息，不能输出用户问题、模型回答、Token、密码或密钥。

## 10. 并发与一致性

- 同一会话仍由数据库会话行和 `active_request_id` 保证单个活动请求。
- 缓存只包含终态历史，不参与会话占用控制。
- 不依赖 Redis 锁保证消息一致性。
- 版本化 Key 使不同版本互不覆盖，异步任务乱序不会污染新版本。
- 每次使用缓存前仍以 MySQL 当前稳定游标选择 Key，因此旧版本不会被误用。
- 事务回滚不会触发 `AFTER_COMMIT` 预热。

## 11. 测试与验收

实现采用测试先行，至少覆盖：

- 正确 Key 包含身份、稳定游标和策略指纹。
- 缓存命中时不读取历史正文。
- 缓存未命中时回源 MySQL并补写。
- Redis 读取、写入、超时和反序列化失败时能够降级。
- 缓存身份、版本、边界或内容非法时拒绝使用并回源。
- 数据库权限或占用校验失败时不会使用缓存绕过。
- 收尾事务提交后触发预热，回滚后不触发。
- 恢复事务提交后触发预热。
- 过时预热事件被跳过。
- 并发或乱序预热不会污染当前版本。
- 日志和缓存对象的 `toString()` 不泄露正文。

验收时除单元和装配测试外，还需要在本地 Redis 与 MySQL 环境验证：首次回源、下一轮命中、Redis 停机降级、Redis 恢复补写和 TTL 到期后的重新加载。

## 12. 实施顺序

1. 定义缓存配置、稳定游标对象和缓存 DTO。
2. 拆分数据库游标校验与稳定历史正文加载职责。
3. 实现 Redis Key、序列化、校验和 Fail-Open 访问层。
4. 实现历史快照提供器并接入上下文准备入口。
5. 实现提交后历史变更事件和异步预热。
6. 增加指标、结构化日志和本地集成验证。
7. 在实际命中率和延迟数据基础上调整 TTL、读取预算与预热线程池参数。
