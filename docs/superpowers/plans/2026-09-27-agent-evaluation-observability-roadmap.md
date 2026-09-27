# Agent Evaluation and Observability Delivery Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 分阶段交付覆盖 Agent、知识库、分层记忆、工具和 SSE 的生产级评测与可观测体系。

**Architecture:** 使用独立评测控制面管理黄金集、运行、评分、基线和发布门禁；业务服务通过 OpenTelemetry 输出 Trace、指标和结构化日志，经 Collector 写入 Prometheus、Tempo 和 Loki，并通过 Grafana 与 Alertmanager 完成展示和告警。整个设计拆成七份可独立验收的实施计划，避免一次变更同时触碰所有业务链路。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AI、Micrometer、OpenTelemetry、Prometheus、Grafana、Tempo、Loki、Alertmanager、MySQL 8、MinIO、Testcontainers、Docker Compose。

---

## 仓库边界

| 仓库 | 职责 |
|---|---|
| `D:\GitCode\order-logistics-agent-server` | Agent、意图、模型、工具、记忆、Redis Stream 和 SSE 遥测 |
| `D:\GitCode\order-logistics-knowledge-service` | 文档入库、发布、检索、引用和用户记忆索引遥测 |
| `D:\GitCode\order-logistics-agent-evaluation-service` | 数据集、评测运行、评分器、基线、报告与发布门禁 |

观测基础设施的本地集成配置保存在 Agent Server 的 `infra/observability`；生产环境使用相同配置语义部署到独立基础设施，不把单机 Compose 当成高可用生产部署。

## 子计划

### Plan 1：观测基础设施与统一遥测

文件：`docs/superpowers/plans/2026-09-27-observability-foundation.md`

交付：

- Agent Server 与 Knowledge Service 接入 Prometheus、OpenTelemetry Trace 和 JSON 结构化日志。
- 建立 Collector、Prometheus、Tempo、Loki、Grafana、Alertmanager 的本地集成栈。
- 建立数据源、基础告警和跨指标/Trace/日志跳转。
- 验证观测后端故障不阻断业务服务。

验收门：两个服务均可被抓取指标，HTTP调用产生Trace，日志包含Trace关联字段，Grafana数据源全部健康。

### Plan 2：Agent、工具与 SSE 业务遥测

计划文件：`docs/superpowers/plans/2026-09-27-agent-runtime-telemetry.md`

交付：

- `agent.turn` 根 Span 与身份、会话占用、上下文、意图、模型、工具、门禁、收尾阶段Span。
- 工具调用、保护器复用、调用上限、熔断、重试、降级和协议拒绝指标。
- Redis Stream发布、中继、重放、心跳、断连、重连、取消和终态完整性指标。
- Agent、意图工具和SSE三块Grafana看板及告警。

验收门：一次聊天可以从 `requestId` 定位完整Trace；所有终态都有低基数结果码；取消和重连测试产生可解释遥测。

### Plan 3：知识库与分层记忆业务遥测

计划文件：`docs/superpowers/plans/2026-09-27-knowledge-memory-telemetry.md`

交付：

- 文档解析、OCR、切片、Embedding、双索引、发布、回滚和清理遥测。
- ES、Milvus、RRF、重排、Release过滤、MySQL终检和引用门禁遥测。
- 短期快照、摘要任务、长期事实抽取、Outbox、索引与召回遥测。
- Knowledge和Memory两块Grafana看板及告警。

验收门：文档从上传到发布以及用户事实从落库到召回均可跨异步任务追踪；索引不一致和任务积压能够告警。

### Plan 4：独立评测服务基础

计划文件：`docs/superpowers/plans/2026-09-27-evaluation-service-foundation.md`

交付：

- 新建评测服务、独立MySQL数据库和Flyway模型。
- 数据集、样本、运行、Case结果、断言、指标、基线和门禁API。
- 多实例Worker租约、幂等Case执行、失败重试和版本快照。
- MinIO大型产物存储及受控服务身份。

验收门：可以导入版本化数据集、启动运行、并发执行固定Case、恢复过期租约并产出可查询报告。

### Plan 5：评分器与黄金评测集

计划文件：`docs/superpowers/plans/2026-09-27-evaluators-and-golden-datasets.md`

交付：

- 意图、工具参数、结果门禁、SSE协议和安全确定性评分器。
- 知识Precision@K、Recall@K、MRR、nDCG与引用评分器。
- 短期记忆、摘要和长期记忆事实、时间与删除评分器。
- 受控LLM Judge、量表、协议校验、人工校准和 `UNSCORED` 处理。
- 各业务域第一版脱敏黄金集。

验收门：每个核心模块至少有正常、边界、失败和安全样本；同一版本重复执行结果可复现。

### Plan 6：故障注入与线上抽样闭环

计划文件：`docs/superpowers/plans/2026-09-27-chaos-and-online-sampling.md`

交付：

- 网络、Redis、RabbitMQ、ES、Milvus、模型和下游HTTP故障注入。
- SSE断连、乱序、重放、实例重启和跨实例取消测试。
- 脱敏线上抽样、人工标注任务、审核和黄金集候选流程。
- 数据访问审计、留存与到期删除。

验收门：所有降级和恢复路径具有自动化证据；未经人工审核的线上样本不能进入黄金基线。

### Plan 7：发布门禁、SLO与运维闭环

计划文件：`docs/superpowers/plans/2026-09-27-release-gates-slo-runbooks.md`

交付：

- 提交级、发布前、夜间和基线对比评测工作流。
- 绝对阈值、相对回退、零容忍安全项和成本门禁。
- P0～P3 Alertmanager路由、抑制、静默和升级策略。
- 全部Grafana看板、容量规划和故障Runbook。

验收门：候选版本发生质量回退时自动阻止发布；告警包含看板、Trace和Runbook入口且不泄露用户内容。

## 实施顺序和提交纪律

严格按 Plan 1 → Plan 7 顺序执行。每份计划均遵循：

1. 先写失败测试或配置验证。
2. 实现最小可通过变更。
3. 运行单模块测试和跨服务冒烟测试。
4. 只提交当前任务文件，保留用户已有未提交改动。
5. 每个Plan通过验收门后再开始下一Plan。

涉及多个仓库时分别在各自 `main` 分支形成可回滚提交，不使用一个仓库的提交描述掩盖另一个仓库的失败。
