# 聊天流断线重连与事件补发设计

## 1. 目标

为聊天 SSE 增加生产级临时断线恢复能力，并保证不会重复提交用户问题或重复执行 Agent 任务。

本功能必须满足：

- 意外网络断开后，后端原任务继续运行；
- 使用有上限的指数退避和随机抖动自动重连；
- 只补发客户端尚未收到的事件；
- 自动恢复期间锁定输入框及其他可能产生并发请求的操作；
- 达到最大重试次数后停止重试并明确提醒用户；
- 区分用户主动停止和意外网络断开；
- start、resume、status、cancel 全链路执行租户及用户归属校验；
- 一轮任务开始前 Redis 不可用时，降级为当前不可重连的直连 SSE，保证基本聊天可用。

## 2. 范围与非目标

本设计处理 Electron 应用仍在运行期间发生的网络抖动、网关断开、空闲超时和未收到终态事件的裸 EOF。

本次不在用户电脑上持久化活动流游标。Electron 被彻底退出或崩溃后，再次启动时通过现有 MySQL 会话历史恢复最终消息，不继续恢复旧 SSE 实时流。

本次不支持在持有模型任务的 Agent 实例崩溃后，把运行中的模型调用迁移到其他实例继续执行。已经写入 Redis 的事件仍可补发；原任务截止时间到达后，由现有条件恢复与收尾机制把遗留任务转为终态，并阻止旧实例恢复后覆盖新状态。

## 3. 时间模型

系统使用三套相互独立的时钟：

1. 后端一轮问答主链路的最大执行时间仍为 `agent.chat.stream.timeout=30s`。它从最初 POST 开始计时，包含线程池排队、会话准备、上下文与记忆召回、知识或工具调用、模型生成、回答落库和终态确认。重连不会暂停、刷新或延长该时间。
2. Electron 针对整轮请求最多执行 5 次重连。标准退避时间为 500ms、1s、2s、4s、8s，并加入有界随机抖动。
3. Redis 补发数据从最后一次事件写入后保留 2 分钟。它只用于事件恢复，不延长模型执行，也不替代 MySQL 消息持久化。

会话摘要、隐式记忆抽取、Milvus/Elasticsearch 索引和缓存预热等对话后的异步任务，不属于 30 秒用户可见问答主链路。

## 4. 选定架构

可恢复任务使用 Redis Streams 作为短期事件日志。

未采用的方案：

- JVM 内存缓冲无法支持请求落到其他实例，也会在进程重启后丢失；
- 每个 delta 和 heartbeat 都写入 MySQL 会放大持久化数据库的写入和清理压力。

可恢复任务把“任务生成”和“连接发送”解耦：

```text
Agent 任务生产者
    → 原子写入 Redis 事件
    → Redis Stream
    → 一个或多个短生命周期 SSE 转发器
    → Electron
```

任务生产者不再持有 `SseEmitter`。Emitter 关闭只会解除当前 SSE 转发器，不会取消 Agent 任务。

Redis 在任务开始前不可用时，使用现有 `ChatSseSession` 直接写入当前 Emitter，并保留当前“断连即取消”的不可恢复行为。

## 5. 请求标识与幂等

Electron 主进程在首次 HTTP 请求前生成高随机性的 UUID。渲染进程不能指定或覆盖该值。后端 DTO 以 `clientRequestId` 接收并校验 UUID 格式，在绑定当前认证身份后将其作为本轮可信 `requestId`。

当前 MySQL 表已经存在 `uk_request_role (request_id, role)`，能够保证一个全局唯一 requestId 只创建一条用户消息和一条助手消息，因此不需要重复增加消息表唯一索引。虽然 UUID 全局唯一，但按 requestId 查询时仍必须带上当前认证用户的 tenantId 和 userId 条件。

实际唯一规则为：

```text
(request_id, role)
```

Redis 使用原子“仅不存在时创建”抢占活动请求。同一个认证用户重复提交相同初始请求时，只连接已有任务，不再次插入消息或调用模型。

如果 requestId 属于其他租户或用户，接口统一返回不可访问，不暴露该标识是否真实存在。

## 6. HTTP 接口

初始接口保持不变：

```http
POST /api/v1/chat/stream
Accept: text/event-stream
Content-Type: application/json
```

Electron 主进程内部请求体新增 `clientRequestId`。服务器通过响应头下发恢复元数据，使连接即使在第一个 `session` 事件到达前断开，Electron 仍然知道如何恢复：

```text
X-Chat-Request-Id
X-Chat-Expires-At
X-Chat-Resumable
X-Chat-Reconnect-Max-Attempts
X-Chat-Reconnect-Initial-Backoff-Ms
X-Chat-Reconnect-Max-Backoff-Ms
X-Chat-Reconnect-Jitter-Ratio
```

`session` 事件也增加 `expiresAt` 和 `resumable`，方便协议观测。

恢复接口：

```http
GET /api/v1/chat/stream/{requestId}/resume?afterSequence={lastSequence}
Accept: text/event-stream
Authorization: Bearer ...
```

状态接口：

```http
GET /api/v1/chat/stream/{requestId}/status
Authorization: Bearer ...
```

状态接口只返回经过身份隔离的元数据：requestId、可用时的 conversationId、任务状态、是否可恢复、截止时间、最后事件序号、终态消息 ID 和终态错误码，不返回回答正文。

主动取消接口：

```http
POST /api/v1/chat/stream/{requestId}/cancel
Authorization: Bearer ...
```

恢复、状态和取消接口只能使用认证上下文中的 tenantId 和 userId 构造查询，禁止从请求体或查询参数接收身份字段。

## 7. Redis 数据模型

相关 Key 使用相同的 Redis Cluster hash tag，使元数据和事件可以原子更新：

```text
agent:chat:stream:v1:{tenantId:userId:requestId}:meta
agent:chat:stream:v1:{tenantId:userId:requestId}:events
agent:chat:stream:v1:{tenantId:userId:requestId}:control
```

元数据包含：

```text
tenantId、userId、orgId、conversationId、requestId
state、resumable、createdAt、expiresAt
lastSequence、totalEventCount、totalEventBytes
ownerInstanceId、ownerLeaseUntil、activeConnectionId
terminalMessageId、terminalErrorCode
```

每条 Stream 事件包含：

```text
sequence、type、timestamp、payloadJson、payloadBytes
```

Redis Stream ID 使用 `<sequence>-0`。

Lua 原子追加脚本按以下顺序执行：

1. 校验请求仍允许写入且尚未终态；
2. 校验单事件大小、事件总数和总字节数限制；
3. 递增 `lastSequence`；
4. 追加事件；
5. 更新容量计数，并在需要时更新终态；
6. 刷新 meta、events 和 control 的 TTL。

模型线程、工具线程、心跳线程和收尾线程统一经过这个顺序边界。`done/error` 成功写入后，后续事件全部拒绝。

## 8. 容量与保留策略

Nacos 默认配置：

```properties
agent.chat.stream.replay.enabled=true
agent.chat.stream.replay.key-prefix=agent:chat:stream:v1
agent.chat.stream.replay.ttl=2m
agent.chat.stream.replay.max-events=512
agent.chat.stream.replay.max-event-bytes=65536
agent.chat.stream.replay.max-stream-bytes=1048576
agent.chat.stream.replay.read-block-timeout=5s

agent.chat.stream.reconnect.max-attempts=5
agent.chat.stream.reconnect.initial-backoff=500ms
agent.chat.stream.reconnect.max-backoff=8s
agent.chat.stream.reconnect.jitter-ratio=0.2
```

系统为一个小型终态事件预留容量。如果写入会超过任意上限，则停止继续生成并将任务转为 `CHAT_STREAM_REPLAY_LIMIT`。不能静默裁剪旧事件，否则恢复后可能得到正文不完整但状态成功的错误结果。

每次追加事件都会刷新 TTL；`done/error` 写入后以终态写入时间重新计算 2 分钟。Redis 数据过期不会影响 MySQL 中的持久化聊天消息。

## 9. SSE 转发器与连接替换

初始连接和恢复连接都通过 Redis Stream 转发器工作。每个转发器：

1. 先补发严格大于 `afterSequence` 的事件；
2. 最多按配置时间阻塞等待新事件；
3. 每次醒来后重新检查连接归属、任务终态和任务截止时间；
4. 转发 `done/error` 后结束。

每次连接会生成新的 `activeConnectionId`。新连接建立后，旧转发器检测到自己已被替换并停止。新旧连接可以短暂重叠，但事件序号不可变，前端也会按序号去重，因此不会重复渲染。

可恢复模式下，SSE 转发器完成、异常或超时只会解除连接；只有显式取消接口能够请求取消任务。

## 10. 主动取消与进程故障

取消接口原子写入 Redis 取消意图，并通过本地活动任务注册表尝试立即中断。任务所属实例通过 Redis 通知接收取消信号，同时在会话准备、工具调用、模型分片、心跳和收尾边界检查持久化取消标记。即使通知丢失，持久化标记仍能兜底。

取消后，助手消息以 `CANCELLED` 收尾；Redis 可用时写入终态 error 事件。

如果任务生产实例崩溃，其他实例不会重新执行模型。恢复或状态接口可以补发已经提交的事件；到达 `expiresAt` 后，通过 requestId、消息状态和会话占用条件更新，把遗留任务转为 `TIMEOUT` 或 `INTERRUPTED`。旧生产者恢复后不能覆盖已经恢复的状态。

## 11. Redis 故障语义

创建业务消息前，如果初始化补发存储失败，则选择现有直连 SSE 模式，并在 `session` 中返回 `resumable=false`。Electron 对这一轮不执行恢复，保证 Redis 故障不会使聊天入口整体不可用。

一轮任务一旦声明为可恢复，Redis 就成为正确性边界。事件追加失败不能继续返回成功，否则客户端可能永久缺失正文。该轮以 `CHAT_STREAM_REPLAY_UNAVAILABLE` 收尾。如果 Redis 无法承载终态事件，后续状态接口从 MySQL 对账最终状态。

任务开始后不允许在直连和可恢复模式之间切换。

## 12. Electron 主进程恢复

恢复逻辑放在 `AgentChatStreamClient`，因为 Bearer Token 和真正的 Fetch 可读流都在 Electron 主进程中。

每条活动流保存：

```text
本地 streamId、所属渲染窗口 ID
服务器 requestId、conversationId
最后已接受 sequence、expiresAt、resumable
当前 reader 和 controller
整轮累计重连次数
重试耗尽后尚待对账的请求状态
```

遇到可重试错误后，主进程向渲染进程返回独立的连接状态，执行带随机抖动的指数退避，携带 `afterSequence` 打开恢复接口，替换 reader 后继续解析事件。

最多 5 次的额度按整轮请求累计。一次恢复成功后不清零，防止持续抖动的网络形成无限重连。

Electron 永远不会自动重放初始 POST。

## 13. 渲染状态与输入锁定

共享 IPC 读取结果增加独立的传输状态分支，不把本地重连状态伪造成服务器 ChatEvent，也不占用服务器事件 sequence。

自动恢复期间：

- 保留现有 active controller；
- `chat.isStreaming` 和 `chat.isBusy` 持续为 true；
- 输入框、发送按钮、卡片操作、会话切换和新建会话继续禁用；
- 状态文字显示 `连接中断，正在恢复（N/5）`；
- 已经展示的助手正文继续保留。

恢复成功后，状态跟随后续服务器事件变化，并应用补发事件。`ChatAccumulator` 继续按 sequence 去重。

第 5 次失败后：

- 停止自动恢复；
- 将部分助手消息标记为未完成，不伪装成成功回答；
- 显示 `连接恢复失败，请稍后重试`；
- 解锁输入框；
- Electron 在当前应用进程内保留一个待对账 requestId。

用户之后再次发送前，必须先查询状态：

- `DONE`：刷新会话历史，清除待对账请求，然后允许发送；
- `ERROR`、`TIMEOUT`、`CANCELLED` 或 `INTERRUPTED`：清除待对账请求并允许发送；
- `RUNNING`：不发送，提示 `上一轮仍在处理中`；
- 状态接口仍不可用：不发送，保留用户输入，提示暂时无法确认上一轮状态。

## 14. 重试错误分类

允许自动重试：

- 非用户主动取消导致的 Fetch 网络异常；
- TCP 重置、未收到 `done/error` 的裸 EOF、已建立连接后的空闲超时；
- 恢复或状态接口返回 HTTP 408、429、502、503、504；
- 恢复阶段 Redis 暂时不可用。

禁止自动重试：

- 已收到服务器 `error` 终态事件；
- HTTP 400 或 403；
- HTTP 401，此时直接退出登录；
- HTTP 404 或 410，表示请求不存在或补发数据已经过期；
- SSE 格式非法、单事件过大或事件积压越界；
- 用户主动停止；
- 已收到 `done`。

第 n 次重连的基准等待时间为：

```text
min(maxBackoff, initialBackoff × 2^(n-1))
```

然后乘以 `[0.8, 1.2]` 范围内的随机系数。HTTP 429/503 的 `Retry-After` 可以延长本次等待，但不能延长后端任务截止时间。

## 15. 终态竞态

网络恢复和任务完成相互独立：

- 任务在截止时间前已经完成时，即使恢复连接发生在截止时间之后，只要 Redis 事件仍在，就补发缺失事件和 `done`；
- 任务在截止时间前没有完成时，恢复或状态接口返回或合成持久化的 `CHAT_TIMEOUT` 终态；
- HTTP 恢复连接成功不等于 Agent 任务成功。

终态写入同时校验 requestId、消息状态和当前会话占用。只有一个终态转换能够成功。

## 16. 网关要求

聊天初始和恢复路由必须：

- 禁用代理缓冲和 SSE 响应压缩；
- read/response timeout 大于后端任务截止时间加终态发送宽限时间；30 秒任务至少配置 45 秒；
- 保留 Authorization 和本设计定义的恢复响应头；
- 禁止自动重放初始 POST。

## 17. 可观测性

指标：

```text
chat_stream_reconnect_attempt_total
chat_stream_reconnect_success_total
chat_stream_reconnect_exhausted_total
chat_stream_replay_events_total
chat_stream_replay_latency
chat_stream_replay_redis_error_total
chat_stream_active_jobs
chat_stream_active_relays
```

结构化日志只记录 requestId、connectionId、重连次数、事件序号范围、状态、耗时和失败分类，不记录事件 payload、Token、用户消息正文或工具结果内容。

## 18. 验证范围

后端测试覆盖：

- 配置参数校验；
- 模型与心跳并发追加时的原子序号；
- 严格补发游标之后的事件；
- 终态写入后禁止继续追加；
- TTL、单事件大小、事件总数和总字节限制；
- 跨租户和跨用户访问拒绝；
- 相同 clientRequestId 不重复执行；
- 意外断线只解除连接，主动停止才取消任务；
- Redis 启动前故障时降级为直连；
- Redis 运行中故障时安全失败；
- 超时与完成竞态、旧生产者保护；
- Redis 事件过期后的恢复与状态行为。

Electron 测试通过注入时钟、随机数和 Fetch 实现覆盖：

- 500ms、1s、2s、4s、8s 指数退避及抖动边界；
- 整轮累计最多 5 次；
- 可重试与不可重试错误分类；
- 游标推进和补发事件去重；
- 恢复期间 busy 状态与输入锁定；
- 等待退避时和恢复连接后主动停止；
- 重试耗尽提示与待对账状态；
- 对账失败时保留用户输入；
- 不自动重放初始 POST。

集成验证会在已知 sequence 后主动断开，通过共享 Redis 从另一个 Agent 实例恢复，检查事件按序补发到 `done`，并确认模型任务和消息对只创建一次。
