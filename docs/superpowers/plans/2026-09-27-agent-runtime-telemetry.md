# Agent、工具与 SSE 业务遥测实施计划

> **执行要求：** 按任务顺序进行；每个任务先补失败测试，再实现最小变更，单独提交。禁止把租户、用户、会话、请求、业务编号、问题正文或模型正文放入指标标签。

**目标：** 让一轮 Agent 问答从后台任务启动到数据库收尾都能通过 Trace、低基数指标和结构化日志解释，并覆盖工具调用、保护器、下游治理、Redis Stream、SSE 重连、取消和终态完整性。

**架构原则：** `requestId`、`conversationId` 只进入 Trace 高基数属性和结构化日志，不进入 Prometheus 标签；指标结果码必须通过服务端白名单收敛。后台 Agent 任务是根业务生命周期，SSE 连接只是可重复建立的消费者，二者分别观测。遥测失败必须静默降级，不能改变业务结果。

**技术栈：** Java 21、Spring Boot 3.5、Micrometer Observation/Tracing、Micrometer Metrics、Resilience4j、Redis Stream、SSE、Prometheus、Tempo、Loki、Grafana。

---

## Task 1：建立 Agent 单轮根 Observation 与低基数终态指标

**文件：**

- 新建：`src/main/java/com/xjjk/agent/chat/observation/AgentTurnTelemetry.java`
- 新建：`src/test/java/com/xjjk/agent/chat/observation/AgentTurnTelemetryTest.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnFinalizer.java`

**步骤：**

1. 先写测试，验证 `agent.turn` 只使用 `mode`、`outcome`、`intent` 等白名单低基数标签，并把 `requestId`、`conversationId` 放入高基数 Trace 属性。
2. 实现单轮上下文对象，在后台生产任务线程启动根 Observation，并在统一收尾后关闭。
3. 终态只允许 `SUCCESS`、`FAILED`、`TIMEOUT`、`CANCELLED`、`OUTPUT_ERROR`、`REJECTED` 等有限值；未知值归一为 `UNKNOWN`。
4. 验证直连和可恢复两种生产模式都只创建一个业务根 Observation，网络重连不得重复创建。
5. 运行目标测试并提交。

## Task 2：补齐准备、上下文、意图、模型、门禁与收尾阶段 Span

**文件：**

- 修改：`src/main/java/com/xjjk/agent/chat/observation/AgentTurnTelemetry.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/memory/ChatContextPreparationService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/FreshBusinessResultGate.java`
- 新建：`src/test/java/com/xjjk/agent/chat/observation/AgentTurnStageTelemetryTest.java`

**步骤：**

1. 为 `turn.prepare`、`context.load`、`intent.route`、`model.stream`、`result.gate`、`turn.finalize` 建立子 Observation。
2. 阶段结果只记录白名单状态、意图类型、路由模式、门禁原因；不得记录问题正文或工具原始结果。
3. 模型阶段记录首 Token 延迟、输入/输出 Token、完成原因和模型逻辑名；模型实例名须归一，不能任意扩散标签。
4. 异常必须标记当前阶段并继续交给现有统一收尾，不允许遥测捕获改变异常语义。
5. 运行 Runner、上下文和门禁相关测试并提交。

## Task 3：观测工具注册、调用保护和结构化结果发布

**文件：**

- 新建：`src/main/java/com/xjjk/agent/tool/observation/ToolCallMetrics.java`
- 修改：`src/main/java/com/xjjk/agent/tool/ToolCallGuard.java`
- 修改：`src/main/java/com/xjjk/agent/tool/AgentToolRequestContext.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- 修改：`src/test/java/com/xjjk/agent/tool/ToolCallGuardTest.java`
- 新建：`src/test/java/com/xjjk/agent/tool/observation/ToolCallMetricsTest.java`

**步骤：**

1. 先覆盖首次执行、并发复用、调用上限、执行失败后开放重试四条路径。
2. 指标记录 `tool`、`outcome`、`reuse`，工具名必须来自启动期注册白名单；参数和业务编号不得成为标签。
3. 为每次真正执行的工具建立 `agent.tool.call` 子 Span；等待复用只记录复用指标，不伪造新的下游 Span。
4. 记录结构化卡片发布成功、协议拒绝和结果门禁拒绝。
5. 运行工具单测并提交。

## Task 4：统一观测下游熔断、重试、超时、降级与协议异常

**文件：**

- 新建：`src/main/java/com/xjjk/agent/integration/observation/DownstreamCallMetrics.java`
- 修改：订单、客户、售后、商品与知识库 Gateway 的公共调用边界
- 新建：`src/test/java/com/xjjk/agent/integration/observation/DownstreamCallMetricsTest.java`
- 修改：各 Gateway 现有测试

**步骤：**

1. 统一低基数维度为 `service`、`operation`、`outcome`、`attempt`、`breaker_state`，值全部受白名单约束。
2. 区分业务拒绝、协议错误、连接失败、读超时、熔断拒绝、重试成功、重试耗尽和降级成功。
3. 对真实 HTTP 调用建立客户端 Span，但不记录 Token、请求体、响应体或业务编号。
4. 验证遥测对象不可用或记录失败不会阻断 Gateway 原有返回与异常映射。
5. 运行 Gateway 与熔断配置测试并提交。

## Task 5：补齐 Redis Stream、SSE 中继、心跳、重放、取消与终态完整性指标

**文件：**

- 修改：`src/main/java/com/xjjk/agent/chat/observation/ChatStreamReplayMetrics.java`
- 修改：`src/main/java/com/xjjk/agent/chat/replay/RedisChatReplayRepository.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatSseRelayService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatSseHeartbeat.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatStreamService.java`
- 修改：`src/main/java/com/xjjk/agent/chat/service/stream/ChatReplayCancellationProbe.java`
- 修改：对应 Replay、Relay、Heartbeat、Cancel 测试

**步骤：**

1. 记录 Stream 写入延迟、事件数、事件字节数、容量拒绝和 Redis 异常。
2. 记录中继连接数、回放事件数、追平耗时、心跳数、客户端断开和中继失败。
3. 记录首次连接与恢复连接，区分成功、不存在、过期、越权、非法序号和基础设施故障。
4. 记录本机取消、跨实例取消、探针命中、截止超时及取消到停止输出的延迟。
5. 增加终态完整性计数：每个请求最多一个 `done/error`，终态后不得再发布业务事件。
6. 运行 Redis 单测与 Testcontainers 集成测试并提交。

## Task 6：建立 Agent、工具、SSE 看板和告警

**文件：**

- 新建：`infra/observability/grafana/dashboards/agent-runtime.json`
- 新建：`infra/observability/grafana/dashboards/tool-calls.json`
- 新建：`infra/observability/grafana/dashboards/sse-stream.json`
- 修改：`infra/observability/prometheus/alerts.yml`
- 修改：`scripts/verify-observability.ps1`
- 修改：`docs/runbook/observability-stack.md`

**步骤：**

1. Agent 看板展示吞吐、成功率、P50/P95/P99、首 Token 延迟、Token 用量、意图和门禁拒绝。
2. 工具看板展示调用率、复用率、上限拒绝、失败率、重试、熔断、超时和降级。
3. SSE 看板展示活动中继、直连/可恢复比例、重连结果、回放量、Redis 故障、取消与终态异常。
4. 告警覆盖 Agent 错误率、模型延迟、工具失败/熔断、SSE 恢复失败、终态缺失和 Redis 回放故障。
5. 扩展验证脚本，检查新指标、看板 UID、规则加载和至少一次可解释聊天 Trace。
6. 启动本地栈完成运行时验收并提交。

## Task 7：跨链路验收与安全检查

**文件：**

- 修改：`docs/runbook/observability-stack.md`
- 新建：`docs/runbook/agent-runtime-telemetry.md`

**步骤：**

1. 执行正常问答、知识库工具、业务工具、调用上限、下游超时、网络断连恢复、用户取消和后台超时场景。
2. 逐场景核对 Trace 层级、指标增量、日志 Trace 关联和前端终态。
3. 扫描指标标签与日志字段，确认无 Token、Cookie、问题正文、证据正文和工具完整结果泄露。
4. 运行 Agent 全量测试与观测栈验证脚本。
5. 只提交本计划文件，保留用户已有未提交改动；验收通过后再进入 Plan 3。

