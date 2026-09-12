param(
  [ValidateSet('up', 'down', 'status')]
  [string]$Action = 'up'
)

$ErrorActionPreference = 'Stop'
$backend = Join-Path $PSScriptRoot '..'
$compose = Join-Path $backend 'docker-compose.local.yml'
$content = Join-Path $backend 'content-notification-service'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker CLI was not found.'
}

switch ($Action) {
  'up' {
    docker compose -f $compose up -d
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host 'Local databases are ready. Start Identity, Care, and Content with E2E_TEST_MODE=true.'
    Write-Host "Content outage control: POST http://127.0.0.1:3003/__test/content/outage with x-e2e-secret."
  }
  'down' { docker compose -f $compose down }
  'status' { docker compose -f $compose ps }
}

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
