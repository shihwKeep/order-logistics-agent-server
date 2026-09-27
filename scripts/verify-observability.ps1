$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $repoRoot 'infra\observability\compose.observability.yml'

if (-not (Test-Path -LiteralPath $compose)) {
    throw "缺少观测Compose文件：$compose"
}

docker compose --env-file (Join-Path $repoRoot 'infra\observability\.env.observability') `
    -f $compose config --quiet

$required = @(
    'http://127.0.0.1:9090/-/ready',
    'http://127.0.0.1:3000/api/health',
    'http://127.0.0.1:3100/ready',
    'http://127.0.0.1:3200/ready',
    'http://127.0.0.1:13133/'
)
foreach ($uri in $required) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5
    if ($response.StatusCode -ne 200) {
        throw "观测组件未就绪：$uri"
    }
}
Write-Output 'observability stack is healthy'
