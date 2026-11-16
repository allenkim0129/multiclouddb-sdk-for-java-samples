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
    if (($fields -join ',') -ne 'collection,database,endpoint,runPrefix' -or
        $saved.Contains($testState.Key) -or $env:MCDB_COSMOS_KEY -ne $testState.Key -or
        $env:MCDB_DATABASE -ne 'synthetic-db') { throw 'Save or environment check failed' }

    $testState.Key = 'synthetic-replacement-key'
    & $setup -Load
    if ($testState.Prompts -ne 2 -or $env:MCDB_COSMOS_KEY -ne $testState.Key -or
        (Get-Content $config -Raw) -ne $saved) { throw 'Load must prompt for a fresh key without changing the file' }

    $testState.Key = ''
    $rejected = $false
    try { & $setup -Load } catch { $rejected = $true }
    if (-not $rejected -or $env:MCDB_COSMOS_KEY -ne 'synthetic-replacement-key' -or
        (Get-Content $config -Raw) -ne $saved) { throw 'Empty key must fail without changing settings' }

    foreach ($value in @('https://synthetic.documents.azure.com/', 'different', 'different', 'NO')) {
        $answers.Enqueue($value)
    }
    $rejected = $false
    try { & $setup } catch { $rejected = $true }
    if (-not $rejected -or $testState.Prompts -ne 3 -or (Get-Content $config -Raw) -ne $saved) {
        throw 'Cancellation must preserve config and not prompt for a key'
    }
    Write-Output 'PASS primary-key prompt, credential-free save, reload, empty-key rejection and cancellation'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
