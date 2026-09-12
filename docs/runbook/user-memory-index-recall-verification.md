# 用户跨会话记忆索引与召回验证手册

## 范围与安全边界

本手册验证用户记忆从 Agent MySQL Outbox 投递到 Knowledge Service 独立 ES/Milvus 索引，再由新会话召回并以低权限 `USER` 数据块进入模型上下文的完整链路。MySQL 始终是唯一事实源；索引只返回 ID、版本和分数。所有索引和回表查询必须同时绑定 `tenant_id`、`user_id` 和 `memory_generation`。

日志、指标和排障截图不得包含记忆正文、证据、Token、内部签名密钥或数据库密码。

## 依赖与启动顺序

1. 启动 MySQL、Redis、Elasticsearch、Milvus 及其依赖、Embedding 服务和 BGE Reranker。
2. 启动 Knowledge Service（默认端口 8085）。
3. 启动 Agent Server（默认端口 8082）。
4. 启动桌面前端。

Knowledge 与 Agent 必须配置同一个至少 32 字符的内部 HMAC 密钥，但不要把真实值提交到 Git。Agent 继续使用既有 `integration.knowledge.internal-secret`，Knowledge 继续使用既有内部 API 密钥配置。

Knowledge Service 的 Nacos 增量：

```yaml
knowledge:
  user-memory:
    enabled: true
    elasticsearch:
      index-alias: agent-user-memory-active
      index-name: agent-user-memory-v1
    milvus:
      collection: agent_user_memory_v1
      dimension: 2560
    retrieval:
      es-top-k: 20
      milvus-top-k: 20
      rrf-top-k: 10
      final-top-k: 10
      vector-weight: 1.0
      keyword-weight: 1.0
      min-vector-score: 0.45
      rerank-enabled: true
      reranker-score-threshold: 0.50
      timeout: 3s
      strategy-version: user-memory-es-milvus-rrf60-bge-v1
```

Agent Server 的 Nacos 增量：

```properties
agent.memory.enabled=true
agent.memory.context-max-tokens=256
agent.memory.index-worker.poll-interval=2s
agent.memory.index-worker.recovery-interval=30s
agent.memory.index-worker.claim-batch-size=10
agent.memory.index-worker.lease-duration=60s
agent.memory.index-worker.max-attempts=8
agent.memory.index-worker.initial-backoff=2s
agent.memory.index-worker.max-backoff=5m
agent.memory.index-worker.executor.pool-size=2
agent.memory.index-worker.executor.queue-capacity=100
agent.memory.retrieval.max-candidates=20
agent.memory.retrieval.max-selected=5
agent.memory.retrieval.global-explicit-limit=3
```

## 主链路验收

1. 在会话 A 发送：`我平时主要做 Java 开发。`
2. 等待异步抽取完成，检查：

```sql
SELECT task_id, status, result_code, saved_memory_count, retry_count, last_error_code
FROM agent_memory_extraction_task
WHERE tenant_id = 1 AND user_id = 74680
ORDER BY id DESC LIMIT 5;

SELECT event_id, memory_id, operation, status, retry_count, last_error_code
FROM agent_memory_outbox
WHERE tenant_id = 1 AND user_id = 74680
ORDER BY id DESC LIMIT 10;
```

预期抽取任务为 `DONE / SAVED`，对应 Outbox 最终为 `DONE`。`RETRY` 应按指数退避重试；连续失败达到上限后为 `DEAD`，错误字段只能保存固定错误码。

3. 查询 ES 别名 `agent-user-memory-active` 和 Milvus 集合 `agent_user_memory_v1`，确认记录的租户、用户、世代、memory ID 和版本与 MySQL 一致。不要在共享日志中输出正文。
4. 新建会话 B，发送：`我平时主要使用什么编程语言？`
5. 预期回答能够使用 Java 这一跨会话事实；`AUTO_EXTRACT / HIDDEN` 记录不得出现在“我的记忆”面板。
6. 检查上下文选择日志，只应看到 `hasUserMemoryContext=true`、Token 数和固定结果码，不得出现记忆正文。记忆块最多 5 条，独立不超过 256 Token。

## 确定性直答验收

1. 确认当前用户在 MySQL 中存在 `ACTIVE / AUTO_EXTRACT / WORK_COMMON_SCOPE` 记忆，正文经安全方式核验为 Java 开发；ES/Milvus 是否已预热不影响本项验收。
2. 重启 Agent、Knowledge Service 与本地 Embedding 服务后，立即新建会话发送 `我平时主要使用什么编程语言？`，随后连续执行 9 次。
3. 从重启后的第一次开始，10 次回答都必须包含 `Java`，不得出现“无法获取或记忆”或“还没有记住”；严格直答不得调用 Knowledge Service、Embedding、ES、Milvus、Reranker 或聊天模型，每次助手消息为 `SUCCESS / MEMORY_RECALLED`。
4. 对应 `chat_call_metrics` 的 `responseModel`、`inputTokens`、`outputTokens`、`totalTokens` 均为空；`agent.user.memory.direct.answer{question="PROGRAMMING_LANGUAGE",outcome="ANSWERED"}` 增加 10。
5. 清空全部记忆后再次询问，必须返回 `我还没有记住您常用的编程语言。`。
6. 恢复记忆后停止 Knowledge Service，再次询问仍必须从 MySQL 返回 Java；停止 MySQL 后才应返回 `记忆服务暂时不可用，请稍后重试。`。
7. 发送 `签收后多久可以退款？` 和 `查询订单 C24101816040`，确认仍分别走知识库证据门禁和业务查询路径。

日志与截图不得展示记忆正文、用户输入、Token、签名或数据库密码。

## 删除、世代与隔离验收

1. 删除单条显式记忆后等待对应 `DELETE` Outbox 为 `DONE`，ES 与 Milvus 均不再返回旧 memory ID。
2. 清空显式记忆后确认只清理当前世代显式索引，隐式记忆仍保留。
3. 清空全部记忆后确认 MySQL 世代加一，旧世代 `CLEAR_GENERATION` 为 `DONE`；延迟到达的旧世代事件不能在新世代召回中生效。
4. 使用另一用户和另一租户提出相同问题，预期均不能召回测试用户的记忆。

## 降级矩阵

- 停止 Elasticsearch：预期 `VECTOR_ONLY`，聊天继续且可由 Milvus 候选回答。
- 恢复 ES、停止 Milvus：预期 `KEYWORD_ONLY`，聊天继续且可由 ES 候选回答。
- 同时停止 ES 与 Milvus：预期 `ALL_RECALL_UNAVAILABLE`，普通聊天继续，但助手不得声称记得用户事实。
- 停止 Knowledge Service：Outbox 进入 `RETRY`；普通聊天主链路继续，MySQL 健康时严格的本人记忆问句仍可确定性回答。
- 模拟 MySQL 终审失败：严格的本人记忆问句返回“记忆服务暂时不可用”，其他跨会话记忆失效，普通聊天继续。

## 指标与自动回归

重点观察以下低基数指标：

- `agent.user.memory.index.operation`
- `agent.user.memory.outbox.transition`
- `agent.user.memory.recall`
- `agent.user.memory.recall.candidates`
- `agent.user.memory.mysql.rejected`
- `agent.user.memory.direct.answer`

发布前在两个仓库分别执行：

```powershell
.\mvnw.cmd test
git diff --check
```

只有两个仓库均为 `BUILD SUCCESS`，且主链路、隔离、删除和三种降级场景均通过，才能启用生产开关。
