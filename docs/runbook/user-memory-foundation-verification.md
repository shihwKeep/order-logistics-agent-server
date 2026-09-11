# 用户跨会话记忆基础能力验证手册

## 范围

本手册验证 Agent Server 第一阶段基础能力：显式记忆写入、用户可见记忆管理、记忆开关、世代清空和索引 Outbox。第一阶段不会消费 Outbox，也不会把记忆写入 Elasticsearch/Milvus 或注入聊天上下文。

## 准备

1. 启动 MySQL 和 Agent Server 依赖服务。
2. 在 Nacos 的 `order-logistics-agent-server.properties` 中配置 `agent.memory.*`，通过安全配置提供至少 32 个字符的 `agent.memory.cursor.secret`，并将 `agent.memory.enabled=true`。该密钥没有生产默认值，缺失时服务必须启动失败。不要在文档、命令历史或日志中填写真实 Token、密码或内部密钥。
3. 启动 Agent Server，确认 Flyway 已成功执行 `V10__create_user_memory_foundation.sql`。
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
