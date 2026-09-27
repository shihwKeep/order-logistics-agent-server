param(
    [switch]$SkipBusinessServices,
    [string]$AgentManagementHealthUrl = 'http://127.0.0.1:18082/actuator/health',
    [string]$KnowledgeServiceHealthUrl = 'http://127.0.0.1:18085/actuator/health'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $repoRoot 'infra\observability\compose.observability.yml'
$environmentFile = Join-Path $repoRoot 'infra\observability\.env.observability'

function Wait-HttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$Attempts = 30,
        [int]$DelaySeconds = 2
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Output "[OK] $Name"
                return
            }
            $lastError = "HTTP $($response.StatusCode)"
        } catch {
            $lastError = $_.Exception.Message
        }

        if ($attempt -lt $Attempts) {
            Start-Sleep -Seconds $DelaySeconds
        }
    }

    throw "$Name is not ready: $Uri; last error: $lastError"
}

if (-not (Test-Path -LiteralPath $compose)) {
    throw "Missing observability compose file: $compose"
}
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing local environment file: $environmentFile; copy .env.observability.example and change the password"
}

$dashboardDirectory = Join-Path $repoRoot 'infra\observability\grafana\dashboards'
$expectedDashboards = @(
    'observability-overview.json',
    'agent-runtime.json',
    'tool-calls.json',
    'sse-stream.json'
)
foreach ($dashboardName in $expectedDashboards) {
    $dashboardPath = Join-Path $dashboardDirectory $dashboardName
    if (-not (Test-Path -LiteralPath $dashboardPath)) {
        throw "Missing Grafana dashboard: $dashboardPath"
    }
    try {
        $dashboard = Get-Content -Raw -LiteralPath $dashboardPath | ConvertFrom-Json
    } catch {
        throw "Invalid Grafana dashboard JSON: $dashboardPath; $($_.Exception.Message)"
    }
    if ([string]::IsNullOrWhiteSpace($dashboard.uid)) {
        throw "Grafana dashboard has no UID: $dashboardPath"
    }
}
Write-Output '[OK] Grafana dashboard JSON and UIDs'

& docker compose --env-file $environmentFile -f $compose config --quiet
if ($LASTEXITCODE -ne 0) {
    throw 'Observability compose validation failed'
}

$observabilityEndpoints = [ordered]@{
    Prometheus = 'http://127.0.0.1:9090/-/ready'
    Grafana = 'http://127.0.0.1:3000/api/health'
    Loki = 'http://127.0.0.1:3100/ready'
    Tempo = 'http://127.0.0.1:3200/ready'
    Collector = 'http://127.0.0.1:13133/'
    Alertmanager = 'http://127.0.0.1:9093/-/ready'
}
foreach ($entry in $observabilityEndpoints.GetEnumerator()) {
    Wait-HttpOk -Name $entry.Key -Uri $entry.Value
}

if (-not $SkipBusinessServices) {
    Wait-HttpOk -Name 'Agent Server management' -Uri $AgentManagementHealthUrl -Attempts 3
    Wait-HttpOk -Name 'Knowledge Service' -Uri $KnowledgeServiceHealthUrl -Attempts 3

    $targets = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/api/v1/targets' -TimeoutSec 5
    $downTargets = @($targets.data.activeTargets | Where-Object { $_.health -ne 'up' })
    if ($downTargets.Count -gt 0) {
        throw "Prometheus contains unavailable scrape targets: $($downTargets.scrapeUrl -join ', ')"
    }

    # Business metrics are scraped by the Collector and re-exposed to Prometheus.
    # Query the embedded business job up series in addition to Prometheus targets.
    $upQuery = [Uri]::EscapeDataString('up{job=~"order-logistics-agent-server|order-logistics-knowledge-service"}')
    $upResult = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query?query=$upQuery" -TimeoutSec 5
    $series = @($upResult.data.result)
    foreach ($job in @('order-logistics-agent-server', 'order-logistics-knowledge-service')) {
        $jobSeries = @($series | Where-Object { $_.metric.job -eq $job })
        if ($jobSeries.Count -eq 0 -or [double]$jobSeries[0].value[1] -ne 1) {
            throw "Business metrics scrape target is unavailable: $job"
        }
    }
    Write-Output '[OK] Agent and Knowledge metrics ingestion'
} else {
    Write-Output '[SKIP] Business service and metrics ingestion checks were skipped'
}

Write-Output 'observability stack is healthy'
