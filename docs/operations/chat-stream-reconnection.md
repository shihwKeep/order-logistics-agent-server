# 聊天流断线恢复运维说明

## 运行边界

- 后端任务从首次 `POST /api/v1/chat/stream` 开始计算，绝对最长执行 30 秒。排队、上下文与记忆召回、工具调用、模型输出、回答落库和终态确认都包含在内。
- 客户端断线重连不会暂停、刷新或延长这 30 秒。Redis Streams 只负责补发已经产生的事件。
- Redis 回放数据在最后一次写入后保留 2 分钟，供短时网络抖动恢复使用；它不是聊天历史，最终历史以 MySQL 为准。
- Electron 对整轮请求累计最多重连 5 次，退避基准为 500ms、1s、2s、4s、8s，并加入 ±20% 随机抖动。恢复成功后次数也不清零。

## Nacos 配置

```properties
agent.chat.stream.timeout=30s
agent.chat.stream.heartbeat-interval=10s
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

## 网关与 Nginx

聊天初始请求、恢复请求和状态、取消接口都必须透传 Authorization 与 `X-Chat-*` 响应头。SSE 路由应关闭缓冲和压缩：

```nginx
location /api/v1/chat/stream {
    proxy_http_version 1.1;
    proxy_buffering off;
    gzip off;
    proxy_read_timeout 45s;
    proxy_send_timeout 45s;
}
```

45 秒用于覆盖 30 秒任务期限及最终持久化、终态发送余量，与客户端重连耗时无关。网关不得自动重放初始 POST；初始连接失败时由 Electron 使用同一个 `clientRequestId` 重试，后端据此保证幂等。

## Redis 容量

单任务硬限制为 512 个事件、单事件 64 KiB、总事件正文 1 MiB。粗略容量可按“同时活跃及两分钟内刚结束的任务数 × 1 MiB”估算上界，并额外预留元数据、Stream 索引和 Redis 内存碎片空间。生产环境应以实际 `used_memory`、活动任务峰值和淘汰率校准，禁止依赖 Redis 的随机淘汰维持容量。

Redis 在业务任务创建前不可用时，系统降级为不可恢复的直连 SSE，并返回 `X-Chat-Resumable: false`。任务开始后 Redis 故障时不得切换到另一条输出通道，应安全结束并由 MySQL 收尾状态兜底，防止重复回答。

## 指标与告警

Micrometer 指标均使用固定低基数标签，不包含租户、用户、会话或 requestId：

- `agent.chat.stream.mode`：可恢复与直连模式计数；
- `agent.chat.stream.resume.attempt`：恢复成功、拒绝或失败计数；
- `agent.chat.stream.replay.events`、`agent.chat.stream.replay.bytes`：回放事件量与字节量；
- `agent.chat.stream.replay.failure`：Redis、容量、连接中继或启动失败；
- `agent.chat.stream.cancel`：取消接受、已终态或失败；
- `agent.chat.stream.relay.active`：当前活动 SSE 中继数。

建议针对 Redis 失败持续增长、直连降级出现、恢复失败率升高、容量拒绝和活动中继异常堆积设置告警。

## 验收步骤

1. 以 `--diagnostic-mode` 启动桌面程序，打开 DevTools Console。
2. 发送一条持续时间较长的问题，确认出现 sequence 递增的 `session/status/delta/heartbeat/done` 事件。
3. 在收到部分 delta 后短暂中断网络再恢复，确认界面显示“连接中断，正在恢复（n/5）”，随后显示“连接已恢复，继续接收回答”。
4. 确认恢复后的 sequence 继续递增，正文没有重复，最终收到 `done` 或明确的 `error`。
5. 连续断网使 5 次尝试全部失败，确认恢复期间输入框锁定，达到上限后提示“网络连接不稳定，重连失败，请稍后重试”并解锁。
6. 点击停止，确认调用取消接口且不进入自动重连。

