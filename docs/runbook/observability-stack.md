# 可观测性平台运行手册

## 本地启动

先执行不依赖 Docker 和业务服务的配置契约测试：

```powershell
.\scripts\test-observability-config.ps1
```

再启动观测基础设施：

```powershell
Copy-Item infra\observability\.env.observability.example infra\observability\.env.observability
notepad infra\observability\.env.observability
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml up -d
.\scripts\verify-observability.ps1
```

启动前必须修改 `GRAFANA_ADMIN_PASSWORD`。`.env.observability` 包含本机凭据，不得提交 Git。

如果暂时没有启动 Agent Server 和 Knowledge Service，可先使用下面的命令只验证观测基础设施：

```powershell
.\scripts\verify-observability.ps1 -SkipBusinessServices
```

完整验收必须去掉该参数，确保两个业务健康接口正常，并且 Collector 确实抓取到了两个业务服务的指标。

本地完整验收或面试演示时，需要在 **Agent 进程的启动环境** 中设置：

```text
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

生产默认仍保持 `0.1`。Collector 的 100% 尾采样只能保留应用已经上报的 Trace，无法恢复被应用头采样丢弃的 Trace；因此不能只把该变量写入观测容器使用的 `.env.observability`，必须配置到 IDEA/命令行启动的 Agent 进程。

## 地址

| 组件 | 地址 |
|---|---|
| Grafana | `http://127.0.0.1:3000` |
| Prometheus | `http://127.0.0.1:9090` |
| Alertmanager | `http://127.0.0.1:9093` |
| Alert Webhook Adapter | `http://127.0.0.1:8095` |
| Tempo API | `http://127.0.0.1:3200` |
| Loki API | `http://127.0.0.1:3100` |
| Collector Health | `http://127.0.0.1:13133` |

Tempo 和 Loki 不直接作为日常查询界面，统一从 Grafana Explore 进入。

## Agent 业务看板

Grafana 会自动加载五个看板：总览、Agent Runtime、Tool Calls、SSE Stream 和 Composite Query V2。Agent Runtime 查看单轮吞吐、终态失败率、P95 总耗时和首 Token 延迟；Tool Calls 查看真实下游调用、保护器复用与超限、结构化结果门禁；SSE Stream 查看活动中继、直连与可恢复模式、断点恢复结果、心跳、Redis 回放故障和取消结果；Composite Query V2 查看图终态、节点 P95、分支、依赖、checkpoint、恢复、重试和部分成功状态。

总览看板同时展示告警适配器健康状态和企微投递结果。`AlertWebhookAdapterDown` 表示外部通知链路已中断；`AlertWebhookDeliveryFailed` 表示适配器已完成有限重试但仍未成功投递。由于适配器故障时无法通过自身发送企微消息，这两类告警必须同时在 Alertmanager/Grafana 中保留，并在生产环境配置第二条独立通知通道作为兜底。

关键指标的标签只使用服务端白名单值。`requestId`、`conversationId`、业务编号、问题正文、模型正文和工具载荷只允许出现在受控 Trace 或排障日志上下文，不能作为 Prometheus 标签。

## 常用命令

```powershell
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml ps
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml logs --tail 200 otel-collector
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml restart otel-collector
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml down
```

日常停止不能增加 `-v`，否则会删除本地指标、Trace、日志和 Grafana 数据卷。

## 离线评测门禁

离线黄金评测不依赖观测容器或外部业务数据，用于在改动编排、门禁和恢复逻辑后快速回归：

```powershell
.\mvnw.cmd -q -Dtest=OfflineGoldenEvaluationTest test
```

该命令验证固定的 12 个业务/失败/门禁场景，并额外检查同一规范化工具参数只产生一次下游调用、checkpoint 恢复不重复已完成业务分支。它不能替代真实请求的 SSE、Trace、Prometheus 和 Loki 验收；离线通过后仍需运行 `scripts\verify-observability.ps1` 并执行单次请求排查。

## 单次请求排查

完整的场景验收矩阵、通过标准和敏感数据检查见
[`agent-runtime-telemetry.md`](agent-runtime-telemetry.md)。

1. 从客户端或服务日志取得 `requestId`。
2. 在 Grafana Explore 选择 Loki，查询 `{service=~"order-logistics-.*"} |= "<requestId>"`。
3. 从日志字段取得 `traceId`，点击派生字段 `TraceID` 跳转 Tempo。
4. 在 Tempo 确认失败 Span 及其 `error.type`、服务版本、阶段和依赖耗时。
5. 回到 Loki 按 `traceId` 查看同一次请求在两个服务中的结构化日志。

禁止把用户原始消息、完整 Prompt、知识证据、记忆正文、Authorization 和 Cookie 复制到工单。

## 故障处理

### Collector不可用

检查 `otel-collector` 日志中的接收、队列、批处理和导出错误。业务服务必须继续提供服务；如果业务线程等待 Collector，立即按 P1 处理并回滚遥测变更。Collector 恢复后确认 `otelcol_exporter_send_failed_*` 不再增长。

### Prometheus目标Down

打开 `http://127.0.0.1:9090/targets`，先确认 Collector 的 `:8889/metrics` 可访问，再查询 `up{exported_job=~"order-logistics-.*"}` 判断 Agent 管理端口 18082 和 Knowledge 管理端口 18085 的 `/actuator/prometheus` 是否可访问。生产环境可通过 `AGENT_METRICS_TARGET`、`KNOWLEDGE_METRICS_TARGET` 覆盖目标地址。业务端口不承担指标采集。

### Tempo或Loki不可写

检查组件 `/ready`、Collector 导出错误和 Docker 卷磁盘空间。禁止通过关闭 Collector 队列上限来掩盖故障；先恢复后端，再确认丢弃计数和告警恢复。

Tempo 启用 `local-blocks` 时，`metrics_generator` 必须同时配置独立的 `traces_storage.path`。仅看到 `/ready` 返回 200 不能证明 metrics-generator 正常；统一执行 `scripts/verify-observability.ps1`，该脚本会检查配置并扫描当前 Tempo 启动日志中的 backoff/WAL 错误。

重启 Collector 后，Prometheus 指标需要等待几秒重新抓取。不要在容器刚启动时立即判定业务指标丢失，使用验证脚本的重试检查确认 `exported_job` 已回填。

### 磁盘不足

使用 `docker system df -v` 和宿主机磁盘工具定位增长来源。先缩短开发环境留存或归档数据，不直接删除未知 Docker 卷。生产环境按对象存储生命周期和备份策略处理。

## Collector故障演练

先确保完整验证脚本通过，然后执行：

```powershell
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml stop otel-collector
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18082/actuator/health
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18085/actuator/health
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml start otel-collector
.\scripts\verify-observability.ps1
```

预期是 Collector 停止期间两个业务健康接口仍返回 200；这证明遥测导出失败不会阻断业务线程。演练结束必须恢复 Collector 并重新执行完整验证。

## 生产边界

本目录 Compose 只用于本地集成验证。生产环境必须启用 TLS 与认证、独立 Secret、对象存储、组件多副本、容量配额、备份恢复和网络策略；Prometheus、Tempo、Loki 和 Grafana 不能直接暴露公网。生产日志与 Trace 访问必须审计。

## 留存与敏感数据

本地指标保留 30 天、Trace 保留 7 天、日志保留 14 天。生产期限由合规策略配置。发现敏感内容进入 Loki 或 Tempo 时，立即停止相关日志源、限制访问、记录事件范围、删除受影响数据并修复产生敏感字段的埋点，然后再恢复采集。
