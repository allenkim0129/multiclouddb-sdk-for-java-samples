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
    $settings = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    Assert-Settings $settings
} else {
    Write-Host 'Use an existing test container with partition key /partitionKey. Do not enter account keys.'
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
    New-Item -ItemType Directory -Force (Split-Path -Parent $configPath) | Out-Null
    $settings | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
}

$env:MCDB_COSMOS_ENDPOINT = $settings.endpoint
$env:MCDB_DATABASE = $settings.database
$env:MCDB_COLLECTION = $settings.collection
$env:MCDB_RUN_PREFIX = $settings.runPrefix

Write-Host 'Config ready: .local/cosmos-sample.json (gitignored; no credentials).'
Write-Host 'Variables are set for this terminal only. In a new terminal, run this script with -Load.'
if (-not [string]::IsNullOrWhiteSpace($env:MCDB_COSMOS_KEY)) {
    Write-Warning 'MCDB_COSMOS_KEY is already set and takes precedence over Azure identity. It was not saved.'
} else {
    Write-Host 'Authentication uses DefaultAzureCredential; sign in with az login if using Azure CLI credentials.'
}
Write-Host 'No cloud connection or test was started. Run the sample separately with live cosmos --allow-live.'
