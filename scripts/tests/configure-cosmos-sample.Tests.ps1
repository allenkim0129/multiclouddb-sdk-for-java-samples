# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
# Offline tests with synthetic input only; no test framework or cloud access required.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scratch = Join-Path $repoRoot ('.local/cosmos-config-test-' + [guid]::NewGuid().ToString())
$names = @('MCDB_COSMOS_ENDPOINT', 'MCDB_DATABASE', 'MCDB_COLLECTION', 'MCDB_RUN_PREFIX', 'MCDB_COSMOS_KEY')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

$answers = [System.Collections.Generic.Queue[string]]::new()
$testState = [pscustomobject]@{ Key = 'synthetic-key-not-a-credential'; Prompts = 0 }
function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if ($AsSecureString) {
        $testState.Prompts++
        $secure = [System.Security.SecureString]::new()
        foreach ($character in $testState.Key.ToCharArray()) { $secure.AppendChar($character) }
        return $secure
    }
    return $answers.Dequeue()
}

try {
    New-Item -ItemType Directory (Join-Path $scratch 'scripts') | Out-Null
    Copy-Item (Join-Path $repoRoot 'scripts/configure-cosmos-sample.ps1') (Join-Path $scratch 'scripts')
    $setup = Join-Path $scratch 'scripts/configure-cosmos-sample.ps1'
    $config = Join-Path $scratch '.local/cosmos-sample.json'
    foreach ($value in @('https://synthetic.documents.azure.com/', 'synthetic-db', 'synthetic-container')) {
        $answers.Enqueue($value)
    }
    & $setup
    $saved = Get-Content $config -Raw
    $fields = @(($saved | ConvertFrom-Json).PSObject.Properties.Name | Sort-Object)
    if (($fields -join ',') -ne 'collection,database,endpoint,primaryKey,runPrefix' -or
        ($saved | ConvertFrom-Json).primaryKey -ne $testState.Key -or $env:MCDB_COSMOS_KEY -ne $testState.Key -or
        $env:MCDB_DATABASE -ne 'synthetic-db') { throw 'Save or environment check failed' }

    $env:MCDB_COSMOS_KEY = 'synthetic-stale-environment-key'
    & $setup -Load
    if ($testState.Prompts -ne 1 -or $env:MCDB_COSMOS_KEY -ne $testState.Key -or
        (Get-Content $config -Raw) -ne $saved) { throw 'Load must reuse saved key without prompting or changing the file' }

    $legacy = $saved | ConvertFrom-Json
    $legacy.PSObject.Properties.Remove('primaryKey')
    $legacy | ConvertTo-Json | Set-Content $config -Encoding UTF8
    $legacyText = Get-Content $config -Raw
    $testState.Key = ''
    $rejected = $false
    try { & $setup -Load } catch { $rejected = $true }
    if (-not $rejected -or $env:MCDB_COSMOS_KEY -ne 'synthetic-key-not-a-credential' -or
        (Get-Content $config -Raw) -ne $legacyText) { throw 'Empty key must fail without changing settings' }

    $testState.Key = 'synthetic-replacement-key'
    & $setup -Load
    $saved = Get-Content $config -Raw
    if ($testState.Prompts -ne 3 -or ($saved | ConvertFrom-Json).primaryKey -ne $testState.Key) {
        throw 'Legacy config must prompt once and save the key'
    }
    & $setup -Load
    if ($testState.Prompts -ne 3) { throw 'Migrated config should not prompt again' }

    foreach ($value in @('https://synthetic.documents.azure.com/', 'different', 'different', 'NO')) {
        $answers.Enqueue($value)
    }
    $rejected = $false
    try { & $setup } catch { $rejected = $true }
    if (-not $rejected -or $testState.Prompts -ne 3 -or (Get-Content $config -Raw) -ne $saved) {
        throw 'Cancellation must preserve config and not prompt for a key'
    }
    Set-Content $config '{"primaryKey":"synthetic-private-value", broken' -Encoding UTF8
    $rejected = $false
    try { & $setup -Load } catch {
        $rejected = $true
        if ($_ | Out-String | Select-String 'synthetic-private-value') { throw 'Parser error leaked file contents' }
    }
    if (-not $rejected) { throw 'Malformed config must fail' }
    Write-Output 'PASS saved-key reload, legacy migration, empty-key rejection, cancellation and safe parse error'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
