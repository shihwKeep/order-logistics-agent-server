# Agent 运行时观测与验收手册

本手册用于本地集成验证和上线前验收。目标不是只确认接口返回 200，而是确认一次请求的业务终态、Trace、指标、日志和前端 SSE 状态彼此一致。

## 验收前置条件

1. Agent、网关和知识服务已经启动，并能访问 MySQL、Redis、模型服务以及需要验证的下游服务。
2. 观测栈已启动：Prometheus、Grafana、Tempo、Loki 和 OpenTelemetry Collector。
3. 打开 Grafana 的 Agent Runtime、Tool Calls、SSE Stream 三个看板，确认目标服务的 `up` 为 1。
4. 每次验收记录 `requestId`、服务版本和测试时间；不要记录完整问题、Prompt、Token、Cookie 或业务载荷。

## 离线黄金评测

上线前先执行不依赖模型、MySQL、Redis 或外部下游的确定性评测：

```powershell
.\mvnw.cmd -q -Dtest=OfflineGoldenEvaluationTest test
```

评测数据位于
`src/test/resources/evaluation/agent-golden-cases.json`，覆盖正常业务查询、业务加知识库、空结果知识库门禁、下游失败、工具参数复用、并行分支和 checkpoint 恢复。评测使用固定夹具，不代表线上吞吐或外部服务可用性；专项测试通过后，仍必须按本手册的实时 SSE、Trace、指标、日志矩阵验收。

评测失败时先查看失败 case 的状态、结果类型、知识库调用次数和禁止调用断言，再决定是否需要启动完整观测栈。不要为了让离线评测通过而放宽“空业务结果不查知识库”或敏感字段断言。

## 场景验收矩阵

| 场景 | 操作 | 必须看到的结果 |
|---|---|---|
| 普通问答 | 发送一个不触发工具的短问题 | 出现 turn、model、completion 指标；SSE 只出现一个终态 `done` |
| 知识库问答 | 询问已发布文档中明确存在的规则 | 出现知识库工具调用、检索结果和引用门禁通过；回答带可追溯来源 |
| 业务工具 | 发送包含合法业务编号的查询 | 只调用对应业务工具；工具结果经过结构化校验后再输出 |
| 工具并发复用 | 让模型在同一轮重复提出相同工具参数 | 保护器出现 `reused`，下游真实调用次数不随等待线程增加 |
| 工具失败重试 | 让下游返回一次失败后恢复 | 失败尝试有记录；重试成功或耗尽状态可在 Tool Calls 看板解释 |
| 下游熔断 | 在测试环境连续触发下游失败 | 出现 `circuit_open` 或降级结果；Agent 仍返回受控业务错误，不泄漏内部异常 |
| SSE 断连恢复 | 请求生成过程中刷新/关闭前端，再用同一 requestId 和最后 sequence 重连 | 恢复结果为成功或明确终态；重复事件不重复渲染，后台任务不重复执行 |
| SSE Redis 故障 | 暂停 Redis 或阻断回放访问 | 记录 Redis/replay failure；前端收到可解释错误，服务线程不被静默卡死 |
| 用户取消 | 生成过程中点击停止 | 取消指标增加；后续不再发布 delta/result；最终为取消或中断终态 |
| 后端超时 | 让模型或工具超过请求绝对截止时间 | 任务进入 timeout；连接关闭、上游流释放，旧任务不能覆盖新请求 |
| 终态完整性 | 检查一轮请求的所有事件 | 每个 requestId 至多一个 `done/error`，终态后没有业务事件 |

## 单次请求核对顺序

1. 从前端或服务日志取得 `requestId`，在 Loki 按该 ID 查找结构化日志。
2. 记录对应 `traceId`，在 Tempo 检查根 Span 下是否有 turn、context、model、tool、stream 等子 Span。
3. 对照 Grafana 看板核对请求计数、耗时分位数、首 Token 延迟、工具结果和 SSE 终态。
4. 检查前端事件序列：`session/status/delta/result/heartbeat/done|error`；重连场景还要核对 sequence 是否单调递增且无重复渲染。
5. 检查 MySQL 的消息终态和 Redis Stream 元数据是否一致；Redis 只作为回放加速层，不能替代数据库事实源。

## 通过标准

- 正常、失败、取消、超时都能落到明确的业务终态，不能出现长时间 `RUNNING` 且没有排查线索。
- SSE 断连后，后台任务最多创建一次；恢复请求只消费已有 Stream，不重新调用模型。
- 工具和下游指标的标签只使用白名单服务、操作和结果值，不包含 requestId、业务编号或正文。
- 观测组件不可用时，业务请求仍能完成或按原有错误映射失败；不能因为埋点异常阻塞主链路。
- 终态之后没有 delta、result 或新的工具结果；重复 done/error 只能产生重复终态计数。

## 故障定位路径

1. **无 Trace**：先查 Collector 接收和导出日志，再查服务是否暴露 Prometheus/OTLP 端点。
2. **有 Trace 无指标**：检查 Micrometer 注册表和指标白名单；不要直接放宽标签。
3. **有指标无前端终态**：查 SSE relay、Redis Stream、心跳和网络写出异常。
4. **前端显示重复**：核对客户端最后 sequence、后端 resume 起点和 Stream 事件 ID。
5. **旧任务覆盖新结果**：检查 requestId/版本条件更新以及超时后的收尾事务。

## 敏感数据检查

验收结束前抽查 Loki、Tempo、Prometheus 标签和 Grafana 变量，确认不存在用户原始消息、完整 Prompt、记忆正文、知识库证据全文、Authorization、Cookie、业务编号或完整工具参数。发现后立即停止相关采集、限制访问、删除受影响数据并修复埋点。
