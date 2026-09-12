# 用户跨会话记忆基础能力验证手册

## 范围

本手册验证 Agent Server 的记忆基础能力：显式记忆写入、用户可见记忆管理、记忆开关、世代清空、异步隐式记忆抽取、自动记忆过期和索引 Outbox。ES/Milvus 投递与跨会话召回请继续执行 `user-memory-index-recall-verification.md`。

## 准备

1. 启动 MySQL 和 Agent Server 依赖服务。
2. 在 Nacos 的 `order-logistics-agent-server.properties` 中配置 `agent.memory.*`，通过安全配置提供至少 32 个字符的 `agent.memory.cursor.secret`，并将 `agent.memory.enabled=true`。该密钥没有生产默认值，缺失时服务必须启动失败。不要在文档、命令历史或日志中填写真实 Token、密码或内部密钥。
3. 启动 Agent Server，确认 Flyway 已成功执行 `V10__create_user_memory_foundation.sql`、`V11__add_user_memory_master_switch.sql`、`V12__create_memory_extraction_task.sql` 和 `V13__add_memory_extraction_observability.sql`。
4. 使用测试账号 `74680` 正常登录，由认证链路产生租户与用户身份；所有下列接口均不得提交 `tenantId` 或 `userId`。

## 验证步骤

1. 创建一个新会话，发送 `请记住以后回答简短一些`。
   - 预期助手回复：`好的，已记住：用户偏好简洁回答`（实际规范化文案由抽取模型决定）。
   - `agent_user_memory` 新增 `USER_EXPLICIT / VISIBLE / ACTIVE` 记录。
   - 首次保存时，同事务新增一条 `PENDING / UPSERT` Outbox；替换既有语义键时，按顺序新增旧 UUID 的 `DELETE` 和新 UUID 的 `UPSERT`。
2. 调用 `GET /api/v1/me/memories?limit=10`。
   - 预期只返回当前认证用户的可见显式记忆。
   - 响应不得包含租户、用户、证据、来源会话或内容哈希。
3. 调用 `PUT /api/v1/me/memories/{memoryId}` 修改内容或保留类型。
   - 预期旧版本变为 `SUPERSEDED`，新记录使用新 UUID，版本加一，并按顺序新增旧 UUID 的 `DELETE` 与新 UUID 的 `UPSERT` Outbox。
   - API 编辑产生的新记录，其会话与消息来源应为空，证据为本次直接用户输入，不得继续引用旧会话消息。
4. 调用 `DELETE /api/v1/me/memories/{memoryId}`。
   - 预期列表不再返回该记录，同时生成 ACTIVE 抑制记录和 `DELETE` Outbox。
5. 发送 `请永久记住叫我老师`。
   - 预期新记录 `retention_type=PERMANENT` 且 `expires_at` 为空。
6. 调用 `DELETE /api/v1/me/memories?scope=explicit`。
   - 预期只删除当前世代的显式可见记忆，隐式记忆不受影响，并新增 `DELETE_EXPLICIT_SCOPE` Outbox。
7. 准备显式和隐式测试记录后，调用 `DELETE /api/v1/me/memories?scope=all`。
   - 预期两类记录均退出 ACTIVE，`memory_generation` 原子加一，并新增旧世代的 `CLEAR_GENERATION` Outbox。
8. 换用另一测试用户访问步骤 2 的 `memoryId`。
   - 预期统一返回 `MEMORY_NOT_FOUND`，不能区分记录存在但不属于当前用户。
9. 检查待处理 Outbox。
   - 预期只包含标识、版本、操作、状态和租约字段，不包含记忆正文、密码或 Token。
10. 检查 `agent.user.memory.operation` 与 `agent.user.memory.affected` 指标。
   - 预期只包含固定操作、结果和错误码标签，不含记忆正文、证据、用户或租户信息。

## 隐式记忆抽取验证

在 Nacos 中保留以下生产配置；可按容量调节线程池、批量和间隔，但不要把模型 Token 写入普通日志：

```properties
agent.memory.auto-extract.confidence-threshold=0.85
agent.memory.auto-extract.expire-days=180
agent.memory.auto-extract.max-candidates=3
agent.memory.auto-extract.prompt-version=memory-auto-v1
agent.memory.auto-extract.model=qwen-plus
agent.memory.auto-extract.temperature=0.0
agent.memory.auto-extract.timeout=10s
agent.memory.auto-extract.model-executor.pool-size=2
agent.memory.auto-extract.model-executor.queue-capacity=100
agent.memory.auto-extract.worker.poll-interval=2s
agent.memory.auto-extract.worker.recovery-interval=30s
agent.memory.auto-extract.worker.claim-batch-size=10
agent.memory.auto-extract.worker.lease-duration=60s
agent.memory.auto-extract.worker.max-attempts=5
agent.memory.auto-extract.worker.initial-backoff=2s
agent.memory.auto-extract.worker.max-backoff=5m
agent.memory.auto-extract.worker.executor.pool-size=2
agent.memory.auto-extract.worker.executor.queue-capacity=100
agent.memory.auto-extract.expiry.poll-interval=10m
agent.memory.auto-extract.expiry.batch-size=100
```

1. 保持总开关和当前用户自动抽取开关为开启，创建新会话并正常提问：`我是一名 Java 开发，平时希望回答简洁一些。`。不要使用“请记住”，否则会进入显式记忆链路。
2. 助手成功回答后等待一个轮询周期，检查任务表：

```sql
SELECT task_id, status, result_code,
       model_candidate_count, accepted_candidate_count, saved_memory_count,
       retry_count, last_error_code, created_at, updated_at
FROM agent_memory_extraction_task
WHERE tenant_id = 1 AND user_id = 74680
ORDER BY id DESC
LIMIT 10;
```

   - 预期每个成功普通问答最多登记一条任务，正常最终状态为 `DONE`。
   - 新完成任务的 `result_code` 含义如下：
     - `SAVED`：至少一条通过校验的候选已保存。
     - `MODEL_EMPTY`：模型正常返回，但没有给出候选。
     - `ALL_REJECTED`：模型给出候选，但全部被确定性校验规则拒绝。
     - `NO_CHANGE`：存在通过校验的候选，但因显式记忆优先、删除抑制或同批去重等规则没有写入新记忆。
     - `MODEL_PROTOCOL_REJECTED`：模型响应不符合严格协议，系统按封闭策略拒绝整批结果。
   - 计数关系必须满足 `saved_memory_count <= accepted_candidate_count <= model_candidate_count`。这些计数只记录数量，不包含用户消息、候选正文或证据。
   - V13 迁移前已经完成的历史任务会保留 `result_code=NULL` 和零计数，不能据此反推历史抽取结果。
   - 暂时性失败进入 `RETRY`，达到最大次数进入 `DEAD`；错误字段只能是安全错误码，不得出现用户正文或供应商异常正文。
3. 检查自动记忆：

```sql
SELECT memory_id, source_type, category, canonical_key, confidence,
       visibility, retention_type, status, version, expires_at
FROM agent_user_memory
WHERE tenant_id = 1 AND user_id = 74680
ORDER BY id DESC
LIMIT 20;
```

   - 有稳定且符合策略的事实时，预期出现 `AUTO_EXTRACT / HIDDEN / NORMAL / ACTIVE`，默认 180 天后过期。
   - 闲聊、敏感数据、一次性业务状态或置信度不足时，任务仍可正常 `DONE`，但不会新增记忆。
   - “我的记忆”面板不得展示 `AUTO_EXTRACT / HIDDEN` 记录。
4. 对同一 `canonical_key` 先保存显式记忆，再通过普通对话表达不同偏好。
   - 预期显式 `USER_EXPLICIT` 继续有效，自动抽取不得覆盖它。
5. 删除显式记忆后再次表达相同事实。
   - 预期当前世代抑制记录阻止自动记忆重新生成。
6. 关闭总开关或当前用户 `autoExtractEnabled` 后继续正常问答。
   - 预期不登记新任务；恢复调度器会把尚未领取且已失效的旧任务改为 `CANCELLED`。
7. 将一条隐藏自动记忆的 `expires_at` 调整到当前 UTC 时间之前，等待过期调度。
   - 预期状态变为 `EXPIRED`，并在同一事务新增 `PENDING / DELETE` Outbox。

注意：`PENDING` Outbox 会由索引 worker 异步消费；只有事件变为 `DONE` 后，才进入跨会话召回验证。

## 开关与故障验证

- 将 `agent.memory.enabled=false` 后，显式记忆语句应按普通对话处理且不写记忆。
- 模拟抽取模型超时或线程池饱和，接口应返回安全的 `MEMORY_WRITE_FAILED`，日志中不得出现原始用户消息、证据或供应商异常正文。
- 调用 `PUT /api/v1/me/memory-settings` 切换 `autoExtractEnabled`，确认只修改当前用户设置，不改变世代和已有记忆。

## 自动化回归

本机有 Docker 时，回归会启动临时 MySQL 8.4，执行全部 Flyway 迁移并验证记忆与 Outbox 的事务回滚；没有 Docker 时该容器用例自动跳过，CI 发布门禁必须提供 Docker。

```powershell
.\mvnw.cmd '-Dtest=com.xjjk.agent.memory.**' test
.\mvnw.cmd test
```

两次命令均须 `BUILD SUCCESS` 且无失败或错误，才能进入后续 ES/Milvus 索引与召回阶段。
