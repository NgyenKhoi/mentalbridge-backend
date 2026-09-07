#!/usr/bin/env pwsh

param(
    [string]$OutputPath = ".env",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedOutputPath = if ([IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $repositoryRoot $OutputPath
}

if ((Test-Path -LiteralPath $resolvedOutputPath) -and -not $Force) {
    throw "Refusing to overwrite $resolvedOutputPath. Pass -Force only when replacement is intentional."
}

$identitySecretsDirectory = Join-Path $repositoryRoot ".local/identity-secrets"
& (Join-Path $PSScriptRoot "generate-local-jwt-keys.ps1") -OutputDirectory $identitySecretsDirectory
if ($LASTEXITCODE -ne 0) {
    throw "Identity secret generation failed."
}

$identitySecrets = @{}
Get-Content -LiteralPath (Join-Path $identitySecretsDirectory "identity-secrets.env") | ForEach-Object {
    $separator = $_.IndexOf('=')
    if ($separator -gt 0) {
        $identitySecrets[$_.Substring(0, $separator)] = $_.Substring($separator + 1)
    }
}

function New-UrlSafeSecret {
    $bytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-Base64Key {
    $bytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    [Convert]::ToBase64String($bytes)
}

$redisPassword = New-UrlSafeSecret
$values = @{
    IDENTITY_JWT_PRIVATE_KEY = $identitySecrets.IDENTITY_JWT_PRIVATE_KEY
    IDENTITY_JWT_PUBLIC_KEY = $identitySecrets.IDENTITY_JWT_PUBLIC_KEY
    IDENTITY_ENCRYPTION_KEY = $identitySecrets.IDENTITY_ENCRYPTION_KEY
    CARE_IDEMPOTENCY_HMAC_KEY = New-UrlSafeSecret
    REDIS_PASSWORD = $redisPassword
    REALTIME_REDIS_URL = "redis://:$redisPassword@redis:6379"
    REALTIME_MESSAGE_ENCRYPTION_KEY = New-Base64Key
}

$templatePath = Join-Path $repositoryRoot ".env.compose.example"
$rendered = Get-Content -LiteralPath $templatePath | ForEach-Object {
    $separator = $_.IndexOf('=')
    if ($separator -le 0) {
        return $_
    }
    $key = $_.Substring(0, $separator)
    if ($values.ContainsKey($key)) {
        return "$key=$($values[$key])"
    }
    $_
}

[IO.File]::WriteAllLines($resolvedOutputPath, $rendered, [Text.UTF8Encoding]::new($false))
Write-Output "Created ignored Review 1 Compose environment file at $resolvedOutputPath. Fill every shared cloud database placeholder before starting Compose."
