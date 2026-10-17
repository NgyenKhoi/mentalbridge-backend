param(
    [string]$ComposeFile = 'compose.yml',
    [string]$EnvironmentFile = '.env.compose.example'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$placeholder = 'mb650-compose-boundary-placeholder-never-a-real-secret'
$variableName = 'CARE_SUPPORT_PLAN_COMPANION_SERVICE_TOKEN'
$previousValue = [Environment]::GetEnvironmentVariable($variableName, 'Process')
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$Message) {
    $failures.Add($Message)
}

function Has-Property($Object, [string]$Name) {
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

Push-Location $repositoryRoot
try {
    [Environment]::SetEnvironmentVariable($variableName, $placeholder, 'Process')
    $jsonLines = @(& docker compose --file $ComposeFile --env-file $EnvironmentFile --profile demo --profile full-test config --format json --no-env-resolution)
    if ($LASTEXITCODE -ne 0) {
        throw 'docker compose config failed'
    }
    $configuration = ($jsonLines -join [Environment]::NewLine) | ConvertFrom-Json

    $expected = @{
        'care' = 'CARE_SUPPORT_PLAN_COMPANION_SERVICE_TOKEN'
        'journal-ai' = 'JOURNAL_AI_SUPPORT_PLAN_CARE_SERVICE_TOKEN'
    }
    foreach ($entry in $expected.GetEnumerator()) {
        if (-not (Has-Property $configuration.services $entry.Key)) {
            Add-Failure "Missing Compose service '$($entry.Key)'"
            continue
        }
        $service = $configuration.services.PSObject.Properties[$entry.Key].Value
        if (-not (Has-Property $service 'environment')) {
            Add-Failure "Service '$($entry.Key)' has no environment configuration"
            continue
        }
        $environment = $service.environment
        if (-not (Has-Property $environment $entry.Value)) {
            Add-Failure "Service '$($entry.Key)' is missing '$($entry.Value)'"
            continue
        }
        if ([string]$environment.PSObject.Properties[$entry.Value].Value -ne $placeholder) {
            Add-Failure "Service '$($entry.Key)' does not receive the shared Companion credential"
        }
    }

    $restrictedNames = @(
        'CARE_SUPPORT_PLAN_COMPANION_SERVICE_TOKEN',
        'JOURNAL_AI_SUPPORT_PLAN_CARE_SERVICE_TOKEN'
    )
    foreach ($serviceProperty in $configuration.services.PSObject.Properties) {
        $serviceName = $serviceProperty.Name
        if (-not (Has-Property $serviceProperty.Value 'environment')) {
            continue
        }
        $environment = $serviceProperty.Value.environment
        if ($null -eq $environment) {
            continue
        }
        foreach ($restrictedName in $restrictedNames) {
            if ((Has-Property $environment $restrictedName) -and
                (-not $expected.ContainsKey($serviceName) -or $expected[$serviceName] -ne $restrictedName)) {
                Add-Failure "Service '$serviceName' must not receive '$restrictedName'"
            }
        }
        foreach ($environmentProperty in $environment.PSObject.Properties) {
            if ([string]$environmentProperty.Value -eq $placeholder -and
                (-not $expected.ContainsKey($serviceName) -or $expected[$serviceName] -ne $environmentProperty.Name)) {
                Add-Failure "Service '$serviceName' receives the shared Companion credential through '$($environmentProperty.Name)'"
            }
        }
    }

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ }
        throw "Compose credential boundary verification failed with $($failures.Count) error(s)"
    }
    Write-Output 'Compose Companion credential boundary verified for Care and Journal/AI only.'
}
finally {
    [Environment]::SetEnvironmentVariable($variableName, $previousValue, 'Process')
    Pop-Location
}
