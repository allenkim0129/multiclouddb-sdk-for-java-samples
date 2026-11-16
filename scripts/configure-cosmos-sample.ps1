# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
[CmdletBinding()]
param(
    [switch]$Load
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $root '.local/cosmos-sample.json'

function Assert-Settings {
    param($Settings)
    foreach ($name in @('endpoint', 'database', 'collection', 'runPrefix')) {
        if ($Settings.$name -isnot [string] -or [string]::IsNullOrWhiteSpace($Settings.$name)) {
            throw "Missing or invalid config field: $name"
        }
    }
    $uri = $null
    if (-not [Uri]::TryCreate($Settings.endpoint, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or -not $uri.Host -or $uri.UserInfo -or
        $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
        throw 'Endpoint must be an HTTPS account URL without credentials, query, fragment or extra path.'
    }
    foreach ($name in @('database', 'collection')) {
        if ($Settings.$name -cnotmatch '^[A-Za-z0-9_-]{1,63}$') {
            throw "$name must contain 1-63 letters, digits, underscores or hyphens."
        }
    }
    if ($Settings.runPrefix -cnotmatch '^[A-Za-z0-9-]{1,32}$') {
        throw 'Run prefix must contain 1-32 letters, digits or hyphens.'
    }
}

if ($Load) {
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'No saved config. Run this script without -Load first.'
    }
    try {
        $settings = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    } catch {
        # Parser errors can include the source JSON, which now contains a secret.
        throw 'Cannot read local Cosmos config. Check file access and JSON syntax locally; do not share its contents.'
    }
    Assert-Settings $settings
} else {
    Write-Host 'Use an existing test container with partition key /partitionKey.'
    $settings = [pscustomobject]@{
        endpoint = (Read-Host 'Cosmos HTTPS endpoint').Trim()
        database = (Read-Host 'Existing database name').Trim()
        collection = (Read-Host 'Existing container (collection) name').Trim()
        runPrefix = 'pojo-test'
    }
    Assert-Settings $settings
    if (Test-Path -LiteralPath $configPath) {
        if ((Read-Host 'Replace saved Cosmos config? Type YES to confirm') -cne 'YES') {
            throw 'Cancelled; saved config and environment were not changed.'
        }
    }
}

$secureKey = $null
$primaryKey = $null
try {
    $saveConfig = -not $Load
    if ($Load -and $null -ne $settings.PSObject.Properties['primaryKey']) {
        if ($settings.primaryKey -isnot [string]) {
            throw 'Invalid primaryKey in local config. Run setup without -Load to replace it.'
        }
        $primaryKey = $settings.primaryKey
    } else {
        Write-Warning 'The primary key will be saved as plaintext in gitignored .local/cosmos-sample.json. Do not share or force-add this file.'
        Write-Host 'Enter the primary key, not the connection string. Paste with right-click or Shift+Insert.'
        $secureKey = Read-Host 'Cosmos PRIMARY KEY (hidden)' -AsSecureString
        $primaryKey = [System.Net.NetworkCredential]::new('', $secureKey).Password
        $saveConfig = $true
    }
    if ([string]::IsNullOrWhiteSpace($primaryKey)) {
        throw 'Primary key is required; saved config and environment were not changed.'
    }
    if ($saveConfig) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $configPath) | Out-Null
        [ordered]@{
            endpoint = $settings.endpoint
            database = $settings.database
            collection = $settings.collection
            runPrefix = $settings.runPrefix
            primaryKey = $primaryKey
        } | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    }
    $env:MCDB_COSMOS_ENDPOINT = $settings.endpoint
    $env:MCDB_DATABASE = $settings.database
    $env:MCDB_COLLECTION = $settings.collection
    $env:MCDB_RUN_PREFIX = $settings.runPrefix
    $env:MCDB_COSMOS_KEY = $primaryKey
} finally {
    $primaryKey = $null
    if ($null -ne $secureKey) {
        $secureKey.Dispose()
    }
}

Write-Host "Config ready: $configPath (gitignored; contains plaintext primary key)."
Write-Host 'Variables are set for this terminal only. In a new terminal, run this script with -Load.'
Write-Host 'Primary-key authentication is ready; az login is not needed.'
Write-Host 'The key remains in this terminal environment until cleared or the terminal closes.'
Write-Host 'After testing: Remove-Item Env:MCDB_COSMOS_KEY -ErrorAction SilentlyContinue'
Write-Host 'Clearing the environment does not remove the key from the saved file.'
Write-Host 'No cloud connection or test was started. Run the sample separately with live cosmos --allow-live.'
