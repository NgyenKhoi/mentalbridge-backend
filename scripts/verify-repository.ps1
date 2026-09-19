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

function Has-MaterialMigrationChange(
    [string]$ComparisonBase,
    [string[]]$MigrationFiles,
    [string[]]$UntrackedFiles,
    [string]$MaterialPattern
) {
    foreach ($path in $MigrationFiles) {
        $candidateLines = if ($UntrackedFiles -contains $path) {
            @(Get-Content -LiteralPath $path)
        }
        else {
            @(& git diff --unified=0 $ComparisonBase -- $path)
        }

        foreach ($line in $candidateLines) {
            $content = if ($UntrackedFiles -contains $path) {
                $line
            }
            elseif ($line -match '^[+-](?![+-])') {
                $line.Substring(1)
            }
            else {
                continue
            }

            if ($content -match $MaterialPattern) {
                return $true
            }
        }
    }
    return $false
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

    $canonicalModelReadme = 'docs/domain-model/README.md'
    $canonicalEntityIndex = 'docs/domain-model/canonical-entities.md'
    $canonicalRelationalModel = 'docs/domain-model/relational/postgresql-logical-schema.sql'
    $canonicalDocumentModel = 'docs/domain-model/document/mongodb-logical-model.md'
    if (-not (Test-Path -LiteralPath $canonicalModelReadme)) {
        Add-Failure "Missing canonical model entrypoint: $canonicalModelReadme"
    }
    if (-not (Test-Path -LiteralPath $canonicalEntityIndex)) {
        Add-Failure "Missing canonical entity index: $canonicalEntityIndex"
    }
    if (-not (Test-Path -LiteralPath $canonicalRelationalModel)) {
        Add-Failure "Missing canonical relational model: $canonicalRelationalModel"
    }
    if (-not (Test-Path -LiteralPath $canonicalDocumentModel)) {
        Add-Failure "Missing canonical document model: $canonicalDocumentModel"
    }
    if (Test-Path -LiteralPath 'database/postgresql/001_initial_schema.sql') {
        Add-Failure 'Misleading database/postgresql/001_initial_schema.sql must not exist; use docs/domain-model/'
    }
    if (Test-Path -LiteralPath $canonicalRelationalModel) {
        $logicalSchema = Get-Content -LiteralPath $canonicalRelationalModel -Raw
        foreach ($requiredText in @(
            'MENTALBRIDGE CANONICAL LOGICAL DATA MODEL',
            'THIS FILE IS READ-ONLY AND NON-EXECUTABLE',
            'must never be used to provision, initialize',
            "Each service's owner-specific migration history"
        )) {
            if ($logicalSchema -notmatch [regex]::Escape($requiredText)) {
                Add-Failure "Canonical relational model is missing required warning: $requiredText"
            }
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
        $minimumNodeMatch = if (Has-Property $package.engines 'node') {
            [regex]::Match([string]$package.engines.node, '>=\s*(\d+)')
        }
        else {
            $null
        }
        if ($null -eq $minimumNodeMatch -or -not $minimumNodeMatch.Success -or [int]$minimumNodeMatch.Groups[1].Value -lt 22) {
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
            $untrackedFiles = @(& git ls-files --others --exclude-standard)
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to inspect untracked files'
            }
            $workingFiles = @(& git diff --name-only)
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to inspect unstaged files'
            }
            $stagedFiles = @(& git diff --cached --name-only)
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to inspect staged files'
            }
            $changedFiles = @($changedFiles + $untrackedFiles + $workingFiles + $stagedFiles | Sort-Object -Unique)
        }
        else {
            Add-Failure "Base commit $BaseSha is unavailable; fetch full history before verification"
        }
    }

    if ($changedFiles.Count -gt 0) {
        $serviceContracts = @{
            'identity-service' = 'contracts/openapi/identity-service-v1.yaml'
            'care-service' = @(
                'contracts/openapi/care-service-v1.yaml'
                'contracts/openapi/care-support-evaluation-v2.yaml'
                'contracts/openapi/care-support-guide-v1.yaml'
            )
            'journal-ai-service' = 'contracts/openapi/journal-ai-service-v1.yaml'
            'realtime-service' = 'contracts/openapi/realtime-service-v1.yaml'
            'content-notification-service' = 'contracts/openapi/content-notification-service.yaml'
        }

        $realtimeGatewayChanged = Has-Changed $changedFiles '^realtime-service/.+gateway\.ts$'
        if ($realtimeGatewayChanged -and -not (Has-Changed $changedFiles '^contracts/websocket/realtime/.+\.schema\.json$')) {
            Add-Failure 'realtime-service gateway changed without its canonical WebSocket schema'
        }
        if ($realtimeGatewayChanged -and -not (Has-Changed $changedFiles '^realtime-service/.*(test/|\.test\.ts$|\.spec\.ts$|/__tests__/)')) {
            Add-Failure 'realtime-service gateway changed without WebSocket boundary tests'
        }

        foreach ($entry in $serviceContracts.GetEnumerator()) {
            $service = $entry.Key
            $controllerChanged = Has-Changed $changedFiles "^$([regex]::Escape($service))/.+(Controller\.java|controller\.ts)$"
            $contractChanged = @($entry.Value | Where-Object { $changedFiles -contains $_ }).Count -gt 0
            if ($controllerChanged -and -not $contractChanged) {
                Add-Failure "$service controller changed without its canonical OpenAPI contract"
            }
            if ($controllerChanged -and -not (Has-Changed $changedFiles "^$([regex]::Escape($service))/.*(src/test/|\.test\.ts$|\.spec\.ts$|/__tests__/)") ) {
                Add-Failure "$service controller/gateway changed without provider boundary tests"
            }
        }

        $postgresMigrationPattern = '^((identity|care|consultation)-service/src/main/resources/db/changelog/|content-notification-service/migrations/).+\.(sql|ya?ml|xml|js|cjs|mjs|ts)$'
        $postgresMigrationFiles = @($changedFiles | Where-Object { $_ -match $postgresMigrationPattern })
        $migrationChanged = $postgresMigrationFiles.Count -gt 0
        if ($migrationChanged -and $changedFiles -notcontains 'docs/database/postgresql-field-data-dictionary.md') {
            Add-Failure 'PostgreSQL migration changed without the field data dictionary'
        }

        $postgresMaterialPattern = '(?i)(create\s+table|drop\s+table|add\s+column|drop\s+column|rename\s+column|alter\s+column.+(?:set|drop)\s+not\s+null|add\s+constraint.+(?:primary\s+key|foreign\s+key|unique|check)|drop\s+constraint|create\s+unique\s+index|references\s+\w+|status.+\bin\s*\()'
        $postgresModelChanged = Has-MaterialMigrationChange $BaseSha $postgresMigrationFiles $untrackedFiles $postgresMaterialPattern
        if ($postgresModelChanged -and $changedFiles -notcontains $canonicalRelationalModel) {
            Add-Failure 'Material PostgreSQL migration changed without the canonical relational model'
        }

        $mongoMigrationPattern = '^(journal-ai-service|realtime-service)/migrations/.+\.(js|cjs|mjs|ts)$'
        $mongoMigrationFiles = @($changedFiles | Where-Object { $_ -match $mongoMigrationPattern })
        $mongoMigrationChanged = $mongoMigrationFiles.Count -gt 0
        if ($mongoMigrationChanged -and $changedFiles -notcontains 'docs/database/mongodb.md') {
            Add-Failure 'MongoDB migration changed without MongoDB documentation'
        }
        $mongoMaterialPattern = '(?i)(createCollection|collMod|\.drop\(|\$jsonSchema|bsonType\s*:|required\s*:|enum\s*:|unique\s*:\s*true)'
        $mongoModelChanged = Has-MaterialMigrationChange $BaseSha $mongoMigrationFiles $untrackedFiles $mongoMaterialPattern
        if ($mongoModelChanged -and $changedFiles -notcontains $canonicalDocumentModel) {
            Add-Failure 'Material MongoDB migration changed without the canonical document model'
        }
        foreach ($mongoService in @('journal-ai-service', 'realtime-service')) {
            $serviceMongoMigrationChanged = Has-Changed $changedFiles "^$([regex]::Escape($mongoService))/migrations/.+\.(js|cjs|mjs|ts)$"
            if ($serviceMongoMigrationChanged -and -not (Has-Changed $changedFiles "^$([regex]::Escape($mongoService))/.*(integration\.test\.ts$|test/integration/)")) {
                Add-Failure "$mongoService MongoDB migration changed without a real integration test"
            }
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
