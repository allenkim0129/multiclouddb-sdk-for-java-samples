# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
# Synthetic data only. No account connection or external test framework required.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scratch = Join-Path $repoRoot ('.local/cosmos-config-test-' + [guid]::NewGuid().ToString())
$answers = [System.Collections.Generic.Queue[string]]::new()
$testState = [pscustomobject]@{ Key = 'synthetic+/key=='; Prompts = 0 }
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
    $config = Join-Path $scratch 'src/main/resources/serialization-cosmos-cloud.properties'
    $previousKey = $env:MCDB_COSMOS_KEY
    $database = 'synthetic.db.' + ('a' * 64)
    foreach ($value in @('https://synthetic.documents.azure.com/', $database, 'synthetic.container')) {
        $answers.Enqueue($value)
    }
    & $setup
    $saved = Get-Content $config -Raw
    if (-not $saved.Contains('multiclouddb.connection.key=synthetic+/key\=\=') -or
        -not $saved.Contains("multiclouddb.database=$database") -or
        -not $saved.Contains('multiclouddb.collection=synthetic.container') -or
        $env:MCDB_COSMOS_KEY -cne $previousKey) { throw 'Properties generation or environment isolation failed' }
    & $setup -Load
    if ($testState.Prompts -ne 1 -or (Get-Content $config -Raw) -ne $saved) {
        throw 'Existing properties must not be changed or prompt again'
    }
    foreach ($value in @('https://synthetic.documents.azure.com/', 'different', 'different', 'NO')) {
        $answers.Enqueue($value)
    }
    $rejected = $false
    try { & $setup } catch { $rejected = $true }
    if (-not $rejected -or $testState.Prompts -ne 1 -or (Get-Content $config -Raw) -ne $saved) {
        throw 'Cancellation must preserve config and not prompt for a key'
    }

    Remove-Item -LiteralPath $config
    New-Item -ItemType Directory (Join-Path $scratch '.local') | Out-Null
    $legacy = Join-Path $scratch '.local/cosmos-sample.json'
    $oldSettings = [ordered]@{
        endpoint = 'not-a-url'
        database = 'legacy.db'; collection = 'legacy.container'; runPrefix = 'legacy_prefix_' + ('b' * 32)
    }
    $oldSettings | ConvertTo-Json | Set-Content $legacy -Encoding UTF8
    $testState.Key = ''
    $rejected = $false
    try { & $setup -Load } catch { $rejected = $true }
    if (-not $rejected -or (Test-Path $config)) { throw 'Empty key must not create new config' }

    $oldSettings.primaryKey = 'synthetic-legacy-key'
    $oldSettings | ConvertTo-Json | Set-Content $legacy -Encoding UTF8
    $legacyText = Get-Content $legacy -Raw
    & $setup -Load
    if ($testState.Prompts -ne 2 -or
        -not (Get-Content $config -Raw).Contains('multiclouddb.connection.key=synthetic-legacy-key') -or
        (Get-Content $legacy -Raw) -ne $legacyText) { throw 'Saved JSON migration failed' }
    Remove-Item -LiteralPath $config
    Set-Content $legacy '{"primaryKey":"synthetic-private-value", broken' -Encoding UTF8
    $rejected = $false
    try { & $setup -Load } catch {
        $rejected = $true
        if ($_ | Out-String | Select-String 'synthetic-private-value') { throw 'Parser error leaked file contents' }
    }
    if (-not $rejected -or (Test-Path $config)) { throw 'Malformed JSON must not create config' }
    Write-Output 'PASS properties generation, saved config reuse, JSON migration, empty-key rejection and cancellation'
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
