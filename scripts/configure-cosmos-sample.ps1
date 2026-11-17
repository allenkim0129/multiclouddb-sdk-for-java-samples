# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
[CmdletBinding()]
param(
    [switch]$Load
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $root 'src/main/resources/serialization-cosmos-cloud.properties'
$legacyPath = Join-Path $root '.local/cosmos-sample.json'

function Show-ConfigLocation {
    Write-Host "Config ready: $configPath (gitignored; contains plaintext primary key)."
    Write-Host 'The shared ConfigLoader reads this file from the runtime classpath; no az login is needed.'
    Write-Host 'Keep the JAR first, then add src/main/resources to the classpath (Windows: -cp "$jar;src/main/resources").'
    Write-Host 'To select a different config, set -Dmulticlouddb.config to its resource name, not a filesystem path.'
    Write-Host 'No cloud connection or test was started. Run the sample separately with cosmos --allow-live.'
}

function ConvertTo-PropertyValue {
    param([string]$Value)
    return $Value.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n').
        Replace("`t", '\t').Replace(' ', '\ ').Replace('=', '\=').
        Replace(':', '\:').Replace('#', '\#').Replace('!', '\!')
}

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
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        Write-Host 'Using the existing properties file; the Java sample validates it before connecting.'
        Show-ConfigLocation
        return
    }
    if (-not (Test-Path -LiteralPath $legacyPath -PathType Leaf)) {
        throw 'No saved config. Run this script without -Load first.'
    }
    try {
        $settings = Get-Content -LiteralPath $legacyPath -Raw | ConvertFrom-Json
    } catch {
        # Parser errors can include the source JSON, which now contains a secret.
        throw 'Cannot read legacy Cosmos JSON config. Check file access and syntax locally; do not share its contents.'
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
            throw 'Cancelled; saved config was not changed.'
        }
    }
}

$secureKey = $null
$primaryKey = $null
try {
    if ($Load -and $null -ne $settings.PSObject.Properties['primaryKey']) {
        if ($settings.primaryKey -isnot [string]) {
            throw 'Invalid primaryKey in local config. Run setup without -Load to replace it.'
        }
        $primaryKey = $settings.primaryKey
    } else {
        Write-Warning 'The primary key will be saved as plaintext in gitignored serialization-cosmos-cloud.properties. Do not share or force-add this file.'
        Write-Host 'Enter the primary key, not the connection string. Paste with right-click or Shift+Insert.'
        $secureKey = Read-Host 'Cosmos PRIMARY KEY (hidden)' -AsSecureString
        $primaryKey = [System.Net.NetworkCredential]::new('', $secureKey).Password
    }
    if ([string]::IsNullOrWhiteSpace($primaryKey)) {
        throw 'Primary key is required; saved config was not changed.'
    }
    $values = [ordered]@{
        'multiclouddb.provider' = 'cosmos'
        'multiclouddb.connection.endpoint' = $settings.endpoint
        'multiclouddb.connection.key' = $primaryKey
        'multiclouddb.database' = $settings.database
        'multiclouddb.collection' = $settings.collection
        'multiclouddb.runPrefix' = $settings.runPrefix
    }
    $lines = @('# Local secret config: gitignored, loaded from disk, never package or share.')
    foreach ($name in $values.Keys) {
        $lines += $name + '=' + (ConvertTo-PropertyValue $values[$name])
    }
    New-Item -ItemType Directory -Force (Split-Path -Parent $configPath) | Out-Null
    [System.IO.File]::WriteAllLines($configPath, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))
} finally {
    $primaryKey = $null
    if ($null -ne $secureKey) {
        $secureKey.Dispose()
    }
}

if ($Load) {
    Write-Host 'Migrated the legacy JSON settings. The old .local/cosmos-sample.json was not deleted and still contains any saved key.'
}
Show-ConfigLocation
