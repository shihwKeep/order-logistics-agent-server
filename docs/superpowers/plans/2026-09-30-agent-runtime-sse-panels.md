# Agent Runtime SSE Panels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SSE reconnect and replay-event observability panels to the existing Grafana Agent Runtime dashboard.

**Architecture:** Extend the existing `agent-runtime.json` dashboard with two Prometheus time-series panels. Reuse the current Prometheus datasource, refresh interval, time range, schema, and compact inline JSON style; do not change server instrumentation or existing panel queries.

**Tech Stack:** Grafana dashboard JSON, PromQL, PowerShell JSON validation, Git.

---

### Task 1: Add SSE observability panels

**Files:**
- Modify: `infra/observability/grafana/dashboards/agent-runtime.json`

- [ ] **Step 1: Capture the current dashboard panel ids and layout**

Run:

```powershell
$dashboard = Get-Content -Raw infra/observability/grafana/dashboards/agent-runtime.json | ConvertFrom-Json
$dashboard.panels | Select-Object id,title,@{Name='x';Expression={$_.gridPos.x}},@{Name='y';Expression={$_.gridPos.y}}
```

Expected: existing panels use ids 1 through 8 and occupy rows through `y=24`.

- [ ] **Step 2: Add the reconnect result panel**

Add a time-series panel with id `9`, title `SSE重连结果`, grid position `{h:8,w:12,x:0,y:32}`, Prometheus target:

```promql
sum(increase(agent_chat_stream_resume_attempt_total[15m])) by (result)
```

Set legend format to `{{result}}`, unit to `short`, datasource UID to `prometheus`, and keep the existing panel schema style.

- [ ] **Step 3: Add the replay-event panel**

Add a time-series panel with id `10`, title `SSE回放事件`, grid position `{h:8,w:12,x:12,y:32}`, Prometheus target:

```promql
sum(increase(agent_chat_stream_replay_events_total[15m])) by (type)
```

Set legend format to `{{type}}`, unit to `short`, datasource UID to `prometheus`, and keep the existing panel schema style.

- [ ] **Step 4: Bump the dashboard version**

Change only the dashboard `version` from `4` to `5`; preserve the existing 30-second refresh and `now-1h` time range.

- [ ] **Step 5: Validate the dashboard JSON and queries**

Run:

```powershell
$dashboard = Get-Content -Raw infra/observability/grafana/dashboards/agent-runtime.json | ConvertFrom-Json
if ($dashboard.title -ne 'Agent Runtime') { throw 'unexpected dashboard title' }
if ($dashboard.version -ne 5) { throw 'dashboard version was not bumped to 5' }
$panel9 = $dashboard.panels | Where-Object id -eq 9
$panel10 = $dashboard.panels | Where-Object id -eq 10
if ($panel9.targets[0].expr -ne 'sum(increase(agent_chat_stream_resume_attempt_total[15m])) by (result)') { throw 'invalid reconnect query' }
if ($panel10.targets[0].expr -ne 'sum(increase(agent_chat_stream_replay_events_total[15m])) by (type)') { throw 'invalid replay query' }
Write-Output 'dashboard validation passed'
```

Expected: `dashboard validation passed`.

- [ ] **Step 6: Review the focused diff**

Run:

```powershell
git diff -- infra/observability/grafana/dashboards/agent-runtime.json
```

Expected: only two panels and the dashboard version change appear; existing panel queries remain unchanged.

- [ ] **Step 7: Commit the dashboard change**

```powershell
git add -- infra/observability/grafana/dashboards/agent-runtime.json
git commit -m "feat: add SSE reconnect panels to agent runtime dashboard"
```
