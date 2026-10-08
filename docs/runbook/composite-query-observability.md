# 复合查询编排的配置与观测

## Nacos 系统提示边界

在 `agent.ai.prompt.system` 现有内容末尾追加以下边界，不要覆盖已有商品、订单、物流、客户和售后规则：

```text
复合问题可以同时查询已授权的内部实时业务数据和企业知识库。只有服务端已经完成查询、校验并提供的业务事实和知识证据，才能用于最终回答；不得把历史消息或模型猜测当作实时结果。通用物流规则、制度、阈值和处理流程只使用知识库证据，不要求用户执行任何内部工具。当前未接入外部市场数据；用户询问市场价格、行业价格区间或外部竞品信息时，必须明确说明暂未接入外部数据，不能编造结论。最终回答不得暴露内部工具名称、工具调用步骤、内部标识或工具参数。
```

直接实时查询仍要求用户提供完整业务编号；缺少必要编号时只提出澄清问题，不猜测参数，也不建议用户执行内部工具。

知识库二阶段回答边界单独配置为 `agent.ai.prompt.knowledge-answer-boundary`，建议值为：

```text
最终回答只面向客服坐席，直接给出知识结论和适用边界；不得输出内部工具名称，不得描述工具调用步骤，不得要求用户提供订单号或执行查询步骤。
```

该配置由服务端拼入知识库检索后的无工具二阶段模型请求，并在启动时校验不能为空。

## 关键 Prometheus 指标

复合图第二版固定使用 `graph="composite-v2"`，不会把订单号、客户号、会话号、提示词或异常全文作为指标标签；旧版 `composite-v1` 只保留用于兼容读取：

- `agent_composite_graph_total{graph,outcome}`：一次复合图执行的最终状态。
- `agent_composite_node_seconds{graph,node,outcome}`：`input.validate`、`branch.dispatch`、`business.query`、`knowledge.query`、`result.validate`、`answer.compose` 各节点耗时。
- `agent_composite_result_total{kind,outcome}`：业务事实或知识引用的取得/失败结果。
- `agent_composite_branch_total{graph,branch,outcome}`：固定业务/知识/综合分析分支的完成、失败或跳过。
- `agent_composite_checkpoint_total{graph,operation,outcome}`：checkpoint 的加载、恢复、缺失或异常。
- `agent_composite_retry_total{graph,node,outcome}`：编排节点重试调度和耗尽情况。

checkpoint 使用 `agent:composite:checkpoint:v2:{requestId}` 前缀和 10 分钟 TTL，与 SSE 回放使用不同 key 前缀；生产环境可由 Nacos 覆盖。并行分支使用有界线程池，默认核心 4、最大 8、队列 32。

Trace 中可以通过 `request.id` 关联节点 Observation 与 `agent.turn` 根 Span；指标只保留低基数标签。

## Grafana 与告警

Grafana 自动加载 `Composite Query V2` 看板，包含图最终状态、节点 P95、分支状态、依赖解析、checkpoint 操作、checkpoint 恢复、节点重试、降级与知识跳过八类面板。节点 P95 依赖 `management.metrics.distribution.percentiles-histogram.agent.composite.node=true` 导出的 histogram bucket。

Prometheus 为复合图配置以下专项告警：

- `CompositeGraphFailureRateHigh`：图失败或不可用比例持续过高；
- `CompositePartialSuccessRateHigh`：部分成功比例持续过高；
- `CompositeCheckpointOperationError`：checkpoint 加载、保存或释放持续异常；
- `CompositeCheckpointResumeFailed`：checkpoint 恢复持续失败；
- `CompositeNodeRetryExhausted`：节点重试持续耗尽；
- `CompositeDependencyFailureRateHigh`：最近订单等依赖解析失败率持续过高。

失败率和部分成功率告警至少需要五个样本才会触发，避免本地单次请求造成误报。checkpoint 错误、恢复失败和重试耗尽属于明确故障事件，不使用比例门槛。

推荐按以下顺序定位：

```text
Composite Query V2 看板 -> Tempo request.id -> Loki traceId/requestId
```

## 验证要点

验证“订单物流 + 物流规则”时，应看到同一轮的 `agent.turn` 意图为 `COMPOSITE`，并出现复合图节点 Span；结果门禁同时放行 `logistics-timeline` 和 `knowledge-citations` 后，才发送二阶段模型回答。知识库不可回答或业务下游失败时，图状态应为失败/不可回答，不能发送模型猜测的成功话术。
