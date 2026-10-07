# Composite V2 Observability Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated Composite V2 Grafana dashboard, orchestration alerts, local full-trace guidance, and an offline observability configuration contract test without changing business behavior.

**Architecture:** Reuse the existing low-cardinality Micrometer metrics emitted by `CompositeQueryMetrics`. Add presentation and alerting artifacts only, enable histogram buckets for the existing node timer, and validate the artifacts with a standalone PowerShell contract test before invoking container-backed verification.

**Tech Stack:** PowerShell 7/Windows PowerShell, Grafana dashboard JSON, Prometheus/PromQL, Spring Boot Actuator, Micrometer, Docker Compose.

---

## File Map

- Create `scripts/test-observability-config.ps1`: offline contract test for dashboard, alerts, histogram and documentation.
- Create `infra/observability/grafana/dashboards/composite-query-v2.json`: dedicated orchestration dashboard.
- Modify `src/main/resources/application.properties`: enable histogram buckets for `agent.composite.node`.
- Modify `scripts/verify-observability.ps1`: require the fifth dashboard during live-stack verification.
- Modify `infra/observability/prometheus/alerts.yml`: add the `composite-query-v2` rule group.
- Modify `docs/runbook/observability-stack.md`: document the fifth dashboard, offline contract test and local 100% application trace sampling.
- Modify `docs/runbook/composite-query-observability.md`: document dashboard panels and orchestration alerts.

The working tree already contains unrelated edits, including edits in several target files. Never use `git add -A`, never replace whole existing files, and review each target diff before staging. Do not commit an already-dirty file unless its pre-existing changes can be separated safely from this work.

### Task 1: Add the failing offline observability contract test

**Files:**
- Create: `scripts/test-observability-config.ps1`

- [ ] **Step 1: Create the contract test before adding the dashboard or alerts**

```powershell
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Message)
    Assert-True -Condition $Text.Contains($Needle) -Message $Message
}

$dashboardPath = Join-Path $repoRoot 'infra\observability\grafana\dashboards\composite-query-v2.json'
Assert-True (Test-Path -LiteralPath $dashboardPath) "Missing Composite V2 dashboard: $dashboardPath"
$dashboard = Get-Content -Raw -LiteralPath $dashboardPath | ConvertFrom-Json
Assert-True ($dashboard.uid -eq 'order-logistics-composite-v2') 'Unexpected Composite V2 dashboard UID'
Assert-True ($dashboard.title -eq 'Composite Query V2') 'Unexpected Composite V2 dashboard title'
Assert-True (@($dashboard.panels).Count -ge 8) 'Composite V2 dashboard must contain at least eight panels'
$dashboardQueries = @(
    $dashboard.panels | ForEach-Object { $_.targets } | ForEach-Object { $_.expr }
) -join "`n"
@(
    'agent_composite_graph_total',
    'agent_composite_node_seconds_bucket',
    'agent_composite_branch_total',
    'agent_composite_dependency_total',
    'agent_composite_checkpoint_total',
    'agent_composite_checkpoint_resume_total',
    'agent_composite_retry_total',
    'agent_composite_partial_success_total',
    'agent_composite_knowledge_skip_total'
) | ForEach-Object {
    Assert-Contains $dashboardQueries $_ "Dashboard is missing metric: $_"
}

$alertsPath = Join-Path $repoRoot 'infra\observability\prometheus\alerts.yml'
$alertsText = Get-Content -Raw -LiteralPath $alertsPath
@(
    'CompositeGraphFailureRateHigh',
    'CompositePartialSuccessRateHigh',
    'CompositeCheckpointOperationError',
    'CompositeCheckpointResumeFailed',
    'CompositeNodeRetryExhausted',
    'CompositeDependencyFailureRateHigh'
) | ForEach-Object {
    Assert-Contains $alertsText "alert: $_" "Missing Prometheus alert: $_"
}
Assert-Contains $alertsText 'increase(agent_composite_graph_total[5m])' 'Graph ratio alerts must enforce a minimum sample size'
Assert-Contains $alertsText 'increase(agent_composite_dependency_total[5m])' 'Dependency ratio alert must enforce a minimum sample size'

$propertiesPath = Join-Path $repoRoot 'src\main\resources\application.properties'
$propertiesText = Get-Content -Raw -LiteralPath $propertiesPath
Assert-Contains $propertiesText 'management.metrics.distribution.percentiles-histogram.agent.composite.node=true' 'Composite node histogram is not enabled'
Assert-Contains $propertiesText 'management.tracing.sampling.probability=${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}' 'Production trace sampling default must remain 0.1'

$runbookPath = Join-Path $repoRoot 'docs\runbook\observability-stack.md'
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
Assert-Contains $runbookText 'MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0' 'Runbook is missing local full-trace sampling guidance'
Assert-Contains $runbookText 'test-observability-config.ps1' 'Runbook is missing the offline contract test command'

Write-Output '[OK] Composite V2 observability configuration contract'
```

- [ ] **Step 2: Run the contract test and verify RED**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-observability-config.ps1
```

Expected: non-zero exit with `Missing Composite V2 dashboard`. This proves the test detects the absent feature rather than passing against existing artifacts.

### Task 2: Add the Composite V2 dashboard and node histogram

**Files:**
- Create: `infra/observability/grafana/dashboards/composite-query-v2.json`
- Modify: `src/main/resources/application.properties:107-111`
- Modify: `scripts/verify-observability.ps1:48-54`

- [ ] **Step 1: Create the dashboard with eight focused panels**

Create a Grafana dashboard with this top-level contract and panel queries:

```json
{
  "annotations": {"list": []},
  "editable": false,
  "panels": [
    {"id": 1, "type": "timeseries", "title": "图最终状态", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0}, "targets": [{"expr": "sum(rate(agent_composite_graph_total[5m])) by (outcome)", "legendFormat": "{{outcome}}", "refId": "A"}]},
    {"id": 2, "type": "timeseries", "title": "节点 P95 耗时", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0}, "fieldConfig": {"defaults": {"unit": "s"}, "overrides": []}, "targets": [{"expr": "histogram_quantile(0.95, sum(rate(agent_composite_node_seconds_bucket[5m])) by (le, node))", "legendFormat": "{{node}}", "refId": "A"}]},
    {"id": 3, "type": "timeseries", "title": "分支状态", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 0, "y": 8}, "targets": [{"expr": "sum(rate(agent_composite_branch_total[5m])) by (branch, outcome)", "legendFormat": "{{branch}} / {{outcome}}", "refId": "A"}]},
    {"id": 4, "type": "timeseries", "title": "依赖解析状态", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 12, "y": 8}, "targets": [{"expr": "sum(rate(agent_composite_dependency_total[5m])) by (type, outcome)", "legendFormat": "{{type}} / {{outcome}}", "refId": "A"}]},
    {"id": 5, "type": "timeseries", "title": "Checkpoint 操作", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 0, "y": 16}, "targets": [{"expr": "sum(rate(agent_composite_checkpoint_total[5m])) by (operation, outcome)", "legendFormat": "{{operation}} / {{outcome}}", "refId": "A"}]},
    {"id": 6, "type": "timeseries", "title": "Checkpoint 恢复", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 12, "y": 16}, "targets": [{"expr": "sum(rate(agent_composite_checkpoint_resume_total[5m])) by (outcome)", "legendFormat": "{{outcome}}", "refId": "A"}]},
    {"id": 7, "type": "timeseries", "title": "节点重试", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 0, "y": 24}, "targets": [{"expr": "sum(rate(agent_composite_retry_total[5m])) by (node, outcome)", "legendFormat": "{{node}} / {{outcome}}", "refId": "A"}]},
    {"id": 8, "type": "timeseries", "title": "降级与知识跳过", "datasource": {"type": "prometheus", "uid": "prometheus"}, "gridPos": {"h": 8, "w": 12, "x": 12, "y": 24}, "targets": [{"expr": "sum(rate(agent_composite_partial_success_total[5m]))", "legendFormat": "PARTIAL_SUCCESS", "refId": "A"}, {"expr": "sum(rate(agent_composite_knowledge_skip_total[5m])) by (outcome)", "legendFormat": "knowledge {{outcome}}", "refId": "B"}]}
  ],
  "refresh": "30s",
  "schemaVersion": 41,
  "tags": ["order-logistics", "langgraph4j", "composite-v2"],
  "templating": {"list": []},
  "time": {"from": "now-1h", "to": "now"},
  "timezone": "browser",
  "title": "Composite Query V2",
  "uid": "order-logistics-composite-v2",
  "version": 1
}
```

- [ ] **Step 2: Enable histogram buckets for the existing node timer**

Append next to the existing custom timer histogram properties:

```properties
management.metrics.distribution.percentiles-histogram.agent.composite.node=true
```

- [ ] **Step 3: Require the new dashboard in live verification**

Add the fifth filename to `$expectedDashboards`:

```powershell
$expectedDashboards = @(
    'observability-overview.json',
    'agent-runtime.json',
    'tool-calls.json',
    'sse-stream.json',
    'composite-query-v2.json'
)
```

- [ ] **Step 4: Re-run the contract test**

Run the same PowerShell test. Expected: it progresses past dashboard checks and fails at the first missing Composite alert. Do not weaken the assertion.

### Task 3: Add orchestration alerts with low-traffic protection

**Files:**
- Modify: `infra/observability/prometheus/alerts.yml`

- [ ] **Step 1: Append the Composite V2 rule group**

```yaml
  - name: composite-query-v2
    rules:
      - alert: CompositeGraphFailureRateHigh
        expr: |
          (
            sum(rate(agent_composite_graph_total{outcome=~"FAILED|UNAVAILABLE"}[5m]))
            /
            clamp_min(sum(rate(agent_composite_graph_total[5m])), 0.001) > 0.10
          )
          and
          sum(increase(agent_composite_graph_total[5m])) >= 5
        for: 5m
        labels:
          severity: P1
        annotations:
          summary: Composite V2图失败率连续5分钟超过10%
          runbook: docs/runbook/composite-query-observability.md

      - alert: CompositePartialSuccessRateHigh
        expr: |
          (
            sum(rate(agent_composite_graph_total{outcome="PARTIAL_SUCCESS"}[5m]))
            /
            clamp_min(sum(rate(agent_composite_graph_total[5m])), 0.001) > 0.20
          )
          and
          sum(increase(agent_composite_graph_total[5m])) >= 5
        for: 5m
        labels:
          severity: P2
        annotations:
          summary: Composite V2部分成功比例连续5分钟超过20%
          runbook: docs/runbook/composite-query-observability.md

      - alert: CompositeCheckpointOperationError
        expr: sum(rate(agent_composite_checkpoint_total{outcome=~"ERROR|UNAVAILABLE"}[5m])) > 0
        for: 2m
        labels:
          severity: P1
        annotations:
          summary: Composite V2 checkpoint持久化持续异常
          runbook: docs/runbook/composite-query-observability.md

      - alert: CompositeCheckpointResumeFailed
        expr: sum(rate(agent_composite_checkpoint_resume_total{outcome="FAILED"}[5m])) > 0
        for: 2m
        labels:
          severity: P1
        annotations:
          summary: Composite V2 checkpoint恢复持续失败
          runbook: docs/runbook/composite-query-observability.md

      - alert: CompositeNodeRetryExhausted
        expr: sum(rate(agent_composite_retry_total{outcome="EXHAUSTED"}[5m])) > 0
        for: 2m
        labels:
          severity: P1
        annotations:
          summary: Composite V2节点重试持续耗尽
          runbook: docs/runbook/composite-query-observability.md

      - alert: CompositeDependencyFailureRateHigh
        expr: |
          (
            sum(rate(agent_composite_dependency_total{outcome="FAILED"}[5m]))
            /
            clamp_min(sum(rate(agent_composite_dependency_total[5m])), 0.001) > 0.20
          )
          and
          sum(increase(agent_composite_dependency_total[5m])) >= 5
        for: 5m
        labels:
          severity: P1
        annotations:
          summary: Composite V2依赖解析失败率连续5分钟超过20%
          runbook: docs/runbook/composite-query-observability.md
```

- [ ] **Step 2: Re-run the contract test**

Expected: dashboard, histogram and alert checks pass; the test now fails because the local full-trace runbook guidance has not been added.

### Task 4: Document local full tracing and the new dashboard

**Files:**
- Modify: `docs/runbook/observability-stack.md:3-43`
- Modify: `docs/runbook/composite-query-observability.md:21-38`

- [ ] **Step 1: Add offline validation and local full-trace guidance to the stack runbook**

Add the command before Docker startup:

```powershell
.\scripts\test-observability-config.ps1
```

Document this application-process setting for local acceptance and interviews:

```text
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

State explicitly that production keeps the application default `0.1`, Collector tail sampling cannot restore traces discarded by application head sampling, and the environment variable must be set in the Agent run configuration rather than only in `.env.observability`.

Change the dashboard count and list from four to five, adding `Composite Query V2` and its graph/node/branch/dependency/checkpoint/retry purpose.

- [ ] **Step 2: Extend the Composite runbook**

Add a `Grafana 与告警` section that lists the eight panel categories, the six alert names, the minimum-volume rule for ratio alerts, and the recommended investigation sequence:

```text
Composite V2 dashboard -> Tempo request.id -> Loki traceId/requestId
```

- [ ] **Step 3: Run the offline contract test and verify GREEN**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-observability-config.ps1
```

Expected:

```text
[OK] Composite V2 observability configuration contract
```

### Task 5: Validate configuration and regression safety

**Files:**
- Verify all files changed in Tasks 1-4.

- [ ] **Step 1: Parse every Grafana dashboard**

```powershell
Get-ChildItem infra\observability\grafana\dashboards\*.json |
    ForEach-Object { Get-Content -Raw $_.FullName | ConvertFrom-Json | Out-Null }
```

Expected: exit code 0 with no JSON parsing error.

- [ ] **Step 2: Validate Prometheus rules**

If the observability stack is running:

```powershell
docker compose --env-file infra\observability\.env.observability `
  -f infra\observability\compose.observability.yml `
  exec -T prometheus promtool check rules /etc/prometheus/alerts.yml
```

Expected: `SUCCESS` and the rule file count with exit code 0.

- [ ] **Step 3: Run the existing observability verification**

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-observability.ps1 -SkipBusinessServices
```

Expected: dashboard JSON/UID, Tempo metrics generator, Compose and infrastructure readiness checks pass. If local containers are intentionally stopped, report this runtime limitation separately; do not treat the offline contract result as proof of runtime readiness.

- [ ] **Step 4: Run focused metrics tests and the full Maven suite**

```powershell
.\mvnw.cmd -q -Dtest=CompositeQueryMetricsTest,AgentTurnTelemetryTest test
.\mvnw.cmd -q test
```

Expected: both commands exit 0 with no failing tests.

- [ ] **Step 5: Inspect only the intended diff**

```powershell
git diff --check -- `
  scripts/test-observability-config.ps1 `
  infra/observability/grafana/dashboards/composite-query-v2.json `
  src/main/resources/application.properties `
  scripts/verify-observability.ps1 `
  infra/observability/prometheus/alerts.yml `
  docs/runbook/observability-stack.md `
  docs/runbook/composite-query-observability.md
```

Expected: no whitespace errors. Review the targeted diff to ensure pre-existing edits were preserved.

- [ ] **Step 6: Commit only safely isolated files**

The new dashboard and offline test may be committed independently:

```powershell
git add -- scripts/test-observability-config.ps1 infra/observability/grafana/dashboards/composite-query-v2.json
git commit -m "feat: add composite observability dashboard contract"
```

Do not stage entire already-dirty files. Leave overlapping alert, application, verifier and runbook edits unstaged unless their pre-existing changes can be separated and reviewed safely.
