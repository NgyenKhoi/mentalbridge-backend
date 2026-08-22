param(
    [switch]$Integration,
    [switch]$SkipInstall,
    [string]$BaseSha = $env:BASE_SHA
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$runningOnWindows = [System.IO.Path]::DirectorySeparatorChar -eq '\'
$npmCommand = if ($runningOnWindows) { 'npm.cmd' } else { 'npm' }
$mavenWrapper = if ($runningOnWindows) { '.\mvnw.cmd' } else { './mvnw' }

function Invoke-Checked([string]$Directory, [string]$Command, [string[]]$Arguments) {
    Push-Location (Join-Path $repositoryRoot $Directory)
    try {
        Write-Output ">>> $Directory :: $Command $($Arguments -join ' ')"
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Directory failed: $Command $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

& (Join-Path $PSScriptRoot 'verify-repository.ps1') -BaseSha $BaseSha
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

foreach ($service in @('identity-service', 'care-service', 'consultation-service')) {
    if (Test-Path -LiteralPath (Join-Path $repositoryRoot "$service/pom.xml")) {
        Invoke-Checked $service $mavenWrapper @('-B', 'test')
    }
}

foreach ($service in @('journal-ai-service', 'realtime-service', 'content-notification-service')) {
    $packagePath = Join-Path $repositoryRoot "$service/package.json"
    if (-not (Test-Path -LiteralPath $packagePath)) {
        continue
    }

    if (-not $SkipInstall) {
        Invoke-Checked $service $npmCommand @('ci')
    }

    $package = Get-Content -LiteralPath $packagePath -Raw | ConvertFrom-Json
    foreach ($script in @('format:check', 'lint', 'typecheck', 'test', 'contract:check', 'migration:check', 'build')) {
        if ($null -ne $package.scripts.PSObject.Properties[$script]) {
            Invoke-Checked $service $npmCommand @('run', $script)
        }
    }

    if ($Integration -and $null -ne $package.scripts.PSObject.Properties['test:integration']) {
        Invoke-Checked $service $npmCommand @('run', 'test:integration')
    }
}

Write-Output 'All available repository quality gates passed.'
