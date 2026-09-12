param(
  [ValidateSet('up', 'down', 'status', 'logs')]
  [string]$Action = 'up'
)

$composeFile = Join-Path $PSScriptRoot '..\docker-compose.local.yml'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker CLI was not found. Install Docker Desktop and reopen PowerShell.'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
  throw 'Docker daemon is unavailable. Start Docker Desktop, wait for Docker Engine to be ready, then rerun this script.'
}

switch ($Action) {
  'up' {
    docker compose -f $composeFile up -d
    if ($LASTEXITCODE -eq 0) {
      docker compose -f $composeFile ps
    }
  }
  'down' { docker compose -f $composeFile down }
  'status' { docker compose -f $composeFile ps }
  'logs' { docker compose -f $composeFile logs --tail 100 }
}

if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}
