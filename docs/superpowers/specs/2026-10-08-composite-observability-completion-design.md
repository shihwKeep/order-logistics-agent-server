# Composite V2 可观测性补齐设计

## 目标

在不修改业务编排、业务接口和现有指标埋点的前提下，补齐 LangGraph4j Composite V2 的 Grafana 展示、Prometheus 告警、本地全量 Trace 演示说明和离线配置验证，使项目能够从图级状态下钻到节点、分支、依赖、checkpoint 和重试结果。

## 范围

本次包含：

- 新增独立的 `Composite Query V2` Grafana 看板；
- 为现有 `agent.composite.node` Timer 开启直方图，以支持真实 P95 查询；
- 在现有 Prometheus 规则中增加复合图专项告警；
- 保留生产默认 Trace 采样率 `0.1`，补充本地演示使用 `1.0` 的配置说明；
- 增加无需启动容器即可执行的观测配置契约测试；
- 更新观测运行手册和复合查询观测手册。

本次不包含：

- 修改 LangGraph4j 工作流、Gateway、checkpoint 存储或 SSE 行为；
- 新增业务指标或改变已有指标名称；
- 修改生产默认采样率；
- 重构现有 Grafana 看板生成方式。

## Grafana 看板

新增 `infra/observability/grafana/dashboards/composite-query-v2.json`，固定 UID 为 `order-logistics-composite-v2`，并复用现有 Prometheus 数据源。

看板展示以下关系：

1. 图最终状态：按 `outcome` 展示 `agent_composite_graph_total` 的五分钟速率；
2. 节点 P95：使用 `agent_composite_node_seconds_bucket` 按 `node` 计算 P95；
3. 分支状态：按 `branch`、`outcome` 展示 `agent_composite_branch_total`；
4. 依赖解析：按 `type`、`outcome` 展示 `agent_composite_dependency_total`；
5. checkpoint 操作：按 `operation`、`outcome` 展示 `agent_composite_checkpoint_total`；
6. checkpoint 恢复：按 `outcome` 展示 `agent_composite_checkpoint_resume_total`；
7. 节点重试：按 `node`、`outcome` 展示 `agent_composite_retry_total`；
8. 降级状态：展示 `agent_composite_partial_success_total` 和 `agent_composite_knowledge_skip_total`。

所有查询只使用代码中已有的低基数标签，不加入 requestId、conversationId、订单号、客户号或原始问题。

节点 P95 依赖 `agent_composite_node_seconds_bucket`。因此应用配置必须增加：

```properties
management.metrics.distribution.percentiles-histogram.agent.composite.node=true
```

该配置只让现有 Timer 导出直方图 bucket，不改变工作流执行或指标标签。

## Prometheus 告警

在现有 `infra/observability/prometheus/alerts.yml` 中追加独立规则组 `composite-query-v2`：

- `CompositeGraphFailureRateHigh`：最近五分钟至少执行五次，`FAILED` 或 `UNAVAILABLE` 比例持续五分钟超过 10%；
- `CompositePartialSuccessRateHigh`：最近五分钟至少执行五次，`PARTIAL_SUCCESS` 比例持续五分钟超过 20%；
- `CompositeCheckpointOperationError`：checkpoint 的 `ERROR` 或 `UNAVAILABLE` 持续出现；
- `CompositeCheckpointResumeFailed`：checkpoint 恢复结果 `FAILED` 持续出现；
- `CompositeNodeRetryExhausted`：节点重试耗尽持续出现；
- `CompositeDependencyFailureRateHigh`：最近五分钟至少产生五次依赖解析，`FAILED` 比例超过 20%。

比例告警必须同时校验最小样本量，避免单次本地请求触发高比例告警。checkpoint 和重试耗尽属于明确错误事件，使用事件速率而不是比例。

告警注解继续引用现有运行手册，不在注解中放业务标识或异常正文。

## Trace 采样

生产默认继续使用：

```properties
management.tracing.sampling.probability=${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}
```

本地完整验收和面试演示前，在 Agent 启动环境中设置：

```text
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

Collector 的 100% 尾采样只能保留已经由应用发送的 Trace，不能恢复应用头采样丢弃的 Trace。运行手册需要明确这一点，并说明该变量必须配置在 Agent 进程而不是只配置在观测容器中。

## 离线配置契约测试

新增 `scripts/test-observability-config.ps1`，不依赖 Docker 和业务服务，验证：

- Composite 看板 JSON 可以解析；
- 看板 UID、标题、面板数量和关键 PromQL 指标齐全；
- `agent.composite.node` 直方图已经开启；
- Prometheus 规则包含六项专项告警；
- 比例告警包含最小样本量限制；
- 告警引用的指标名称与 `CompositeQueryMetrics` 的 Prometheus 导出名称一致；
- 运行手册包含本地 `1.0` 采样说明，且应用默认值仍是 `0.1`。

实施时先提交测试并执行，确认其因缺少看板和告警而失败；再添加最小配置使测试通过。随后运行 Grafana JSON 解析、Prometheus 规则检查、现有观测验证脚本的静态部分以及 Maven 构建。

## 数据流与故障定位

最终排障路径保持为：

```text
Grafana Composite V2 看板发现异常
  -> Prometheus 确定图、节点、分支或 checkpoint 类型
  -> Tempo 按 request.id 查看单次 agent.turn 和复合节点 Span
  -> Loki 按 traceId/requestId 查看重试和异常分类日志
```

本次新增看板和告警只消费已有遥测，不成为业务执行的前置条件；Prometheus、Tempo、Loki 或 Collector 不可用时，Agent 业务仍应继续运行。

## 验收标准

- Grafana 自动发现第五个看板 `Composite Query V2`；
- 八类面板查询均引用实际存在的 Composite V2 指标；
- 六项新告警可被 Prometheus 正确加载；
- 低流量单次失败不会触发比例告警；
- 本地文档明确说明如何获得每次请求的完整 Trace；
- 离线配置契约测试通过；
- 现有业务测试和构建不因本次配置变更失败。
