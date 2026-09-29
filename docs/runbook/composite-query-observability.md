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

复合图固定使用 `graph="composite-v1"`，不会把订单号、客户号、会话号、提示词或异常全文作为指标标签：

- `agent_composite_graph_total{graph,outcome}`：一次复合图执行的最终状态。
- `agent_composite_node_seconds{graph,node,outcome}`：`input.validate`、`business.query`、`knowledge.query`、`result.validate`、`answer.compose` 各节点耗时。
- `agent_composite_result_total{kind,outcome}`：业务事实或知识引用的取得/失败结果。

Trace 中可以通过 `request.id` 关联节点 Observation 与 `agent.turn` 根 Span；指标只保留低基数标签。

## 验证要点

验证“订单物流 + 物流规则”时，应看到同一轮的 `agent.turn` 意图为 `COMPOSITE`，并出现复合图节点 Span；结果门禁同时放行 `logistics-timeline` 和 `knowledge-citations` 后，才发送二阶段模型回答。知识库不可回答或业务下游失败时，图状态应为失败/不可回答，不能发送模型猜测的成功话术。
