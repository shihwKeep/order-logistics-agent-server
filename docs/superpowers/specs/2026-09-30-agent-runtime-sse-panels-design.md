# Agent Runtime SSE 重连观测面板设计

## 目标

在现有 Grafana `Agent Runtime` Dashboard 中增加 SSE 重连验证所需的图表，使一次客户端断流测试能够与 Agent 吞吐、P95、Token 等指标在同一面板中对照观察。

## 范围

本次只修改 Grafana Dashboard JSON，不修改 SSE 业务逻辑、Prometheus 采集配置或客户端代码。新增面板使用服务端已经定义的 SSE 指标；如果当前环境未采集这些指标，Grafana 应显示 `No data`，不能用其他指标伪造重连结果。

## 面板设计

### 1. SSE 重连结果

- 标题：`SSE重连结果`
- 类型：Grafana time series
- 查询：

  ```promql
  sum(rate(agent_chat_stream_resume_attempt_total[5m])) by (result)
  ```

- 图例：`{{result}}`
- 单位：`reqps`
- 用途：观察 `success`、`failure`、`rejected`、`not_found` 等断点恢复结果。

### 2. SSE 回放事件

- 标题：`SSE回放事件`
- 类型：Grafana time series
- 查询：

  ```promql
  sum(rate(agent_chat_stream_replay_events_total[5m])) by (type)
  ```

- 图例：`{{type}}`
- 单位：`reqps`
- 用途：观察恢复期间 `session`、`status`、`delta`、`result`、`done` 等事件是否被回放。

## 布局与兼容性

- 新面板放入现有 `Agent Runtime` Dashboard，不改变现有 Agent 吞吐、Agent P95、Token、工具调用等面板的查询。
- 使用现有 Dashboard 的 30 秒刷新和最近 1 小时默认时间范围。
- 面板 JSON 使用现有 Grafana schema 和 Prometheus 数据源 UID。

## 验证标准

1. Dashboard JSON 能被 JSON 解析器正常解析。
2. 新增面板包含准确的标题、PromQL、图例和单位。
3. 执行一次 `AGENT_CHAT_STREAM_TEST_DISCONNECT_AFTER_MS=1000` 测试后，若 SSE 指标已采集，应能看到 `success` 曲线变化；若指标未采集，应明确显示 `No data`。
4. 原有 Dashboard 面板数量、查询和布局不被意外修改。

