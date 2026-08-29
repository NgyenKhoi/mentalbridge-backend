param(
    [string]$BaseSha = $env:BASE_SHA
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$Message) {
    $failures.Add($Message)
}

function Has-Property($Object, [string]$Name) {
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Has-Changed([string[]]$ChangedFiles, [string]$Pattern) {
    return $null -ne ($ChangedFiles | Where-Object { $_ -match $Pattern } | Select-Object -First 1)
}

Push-Location $repositoryRoot
try {
    $trackedFiles = @(& git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files failed'
    }

    foreach ($path in $trackedFiles) {
        if ($path -match '^services/') {
            Add-Failure "Deployables must remain top-level; found $path"
        }
        if ($path -match '(^|/)contracts/(openapi|events|websocket|proposals)/' -and $path -notmatch '^contracts/') {
            Add-Failure "Language-neutral contract must live below root contracts/: $path"
        }
    }

    foreach ($contract in Get-ChildItem -LiteralPath 'contracts/openapi' -File -Filter '*.yaml') {
        $pathCount = @(Select-String -LiteralPath $contract.FullName -Pattern '^  /').Count
        $statusMatches = @(Select-String -LiteralPath $contract.FullName -Pattern '^    x-mentalbridge-status: (implemented|planned)$')
        if ($pathCount -ne $statusMatches.Count) {
            Add-Failure "$($contract.Name) has $pathCount paths but $($statusMatches.Count) explicit availability statuses"
        }
    }

    foreach ($service in @('journal-ai-service', 'realtime-service', 'content-notification-service')) {
        $packagePath = Join-Path $service 'package.json'
        if (-not (Test-Path -LiteralPath $packagePath)) {
            continue
        }

        $package = Get-Content -LiteralPath $packagePath -Raw | ConvertFrom-Json
        if (-not (Has-Property $package.engines 'node') -or $package.engines.node -notmatch '>=\s*22') {
            Add-Failure "$service must support Node.js 22 or newer"
        }

        foreach ($dependency in @('@nestjs/common', '@nestjs/core', '@nestjs/platform-express')) {
            if (-not (Has-Property $package.dependencies $dependency)) {
                Add-Failure "$service is missing required NestJS dependency $dependency"
            }
        }

        foreach ($dependency in @('express', 'mongoose')) {
            if (Has-Property $package.dependencies $dependency) {
                Add-Failure "$service must not declare direct production dependency $dependency"
            }
        }

        foreach ($script in @('format:check', 'lint', 'typecheck', 'test', 'contract:check', 'migration:check', 'build')) {
            if (-not (Has-Property $package.scripts $script)) {
                Add-Failure "$service is missing npm script $script"
            }
        }

        if (Has-Property $package.dependencies 'pg') {
            if (-not (Has-Property $package.dependencies 'node-pg-migrate')) {
                Add-Failure "$service owns PostgreSQL through Node.js and must use node-pg-migrate"
            }
            if (Test-Path -LiteralPath (Join-Path $service 'liquibase.properties')) {
                Add-Failure "$service must not use Liquibase; Node.js/PostgreSQL uses node-pg-migrate"
            }
        }
    }

    foreach ($service in @('identity-service', 'care-service', 'consultation-service')) {
        $pomPath = Join-Path $service 'pom.xml'
        if (-not (Test-Path -LiteralPath $pomPath)) {
            continue
        }
        $pom = Get-Content -LiteralPath $pomPath -Raw
        if ($pom -match 'spring-boot-starter-data-jpa' -and $pom -notmatch 'spring-boot-starter-liquibase') {
            Add-Failure "$service uses Spring/PostgreSQL and must use Liquibase"
        }
    }

    $changedFiles = @()
    if (-not [string]::IsNullOrWhiteSpace($BaseSha)) {
        & git cat-file -e "$BaseSha^{commit}" 2>$null
        if ($LASTEXITCODE -eq 0) {
            $changedFiles = @(& git diff --name-only "$BaseSha...HEAD")
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to compare changes with $BaseSha"
            }
        }
        else {
            Add-Failure "Base commit $BaseSha is unavailable; fetch full history before verification"
        }
    }

    if ($changedFiles.Count -gt 0) {
        $serviceContracts = @{
            'identity-service' = 'contracts/openapi/identity-service-v1.yaml'
            'care-service' = 'contracts/openapi/care-service-v1.yaml'
            'journal-ai-service' = 'contracts/openapi/journal-ai-service-v1.yaml'
            'content-notification-service' = 'contracts/openapi/content-notification-service.yaml'
        }

        foreach ($entry in $serviceContracts.GetEnumerator()) {
            $service = $entry.Key
            $controllerChanged = Has-Changed $changedFiles "^$([regex]::Escape($service))/.+(Controller\.java|controller\.ts|gateway\.ts)$"
            if ($controllerChanged -and $changedFiles -notcontains $entry.Value) {
                Add-Failure "$service controller/gateway changed without its canonical OpenAPI contract"
            }
            if ($controllerChanged -and -not (Has-Changed $changedFiles "^$([regex]::Escape($service))/.*(src/test/|\.test\.ts$|\.spec\.ts$|/__tests__/)") ) {
                Add-Failure "$service controller/gateway changed without provider boundary tests"
            }
        }

        $migrationChanged = Has-Changed $changedFiles '^((identity|care|consultation)-service/src/main/resources/db/changelog/|content-notification-service/migrations/).+\.(sql|ya?ml|xml|js|cjs|mjs|ts)$'
        if ($migrationChanged -and $changedFiles -notcontains 'docs/database/postgresql-field-data-dictionary.md') {
            Add-Failure 'PostgreSQL migration changed without the field data dictionary'
        }

        foreach ($service in @('identity-service', 'care-service', 'consultation-service', 'journal-ai-service', 'realtime-service', 'content-notification-service')) {
            $configurationChanged = Has-Changed $changedFiles "^$([regex]::Escape($service))/src/.+(configuration|config).+\.(java|ts)$"
            if (-not $configurationChanged) {
                continue
            }
            if (-not (Has-Changed $changedFiles "^$([regex]::Escape($service))/.*(src/test/|\.test\.ts$|\.spec\.ts$|/__tests__/)") ) {
                Add-Failure "$service configuration changed without configuration tests"
            }
        }

        foreach ($service in @('identity-service', 'care-service', 'consultation-service', 'journal-ai-service', 'realtime-service', 'content-notification-service')) {
            if ($changedFiles -contains "$service/.env.example" -and $changedFiles -notcontains "$service/README.md") {
                Add-Failure "$service .env.example changed without README.md"
            }
        }
    }

    if ($failures.Count -gt 0) {
        Write-Error ("Repository policy failed:`n- " + ($failures -join "`n- "))
        exit 1
    }

    Write-Output "Repository policy passed ($($trackedFiles.Count) tracked files checked)."
    if ($changedFiles.Count -gt 0) {
        Write-Output "Paired-change policy passed for $($changedFiles.Count) changed files since $BaseSha."
    }
}
finally {
    Pop-Location
}
