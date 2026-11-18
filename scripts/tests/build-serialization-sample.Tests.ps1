# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
# Synthetic files only; do not run Maven or connect to live accounts.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scratch = Join-Path $repoRoot ('.local/hash-test-' + [guid]::NewGuid().ToString())

# Load only the two hash functions, not the build script's top-level operations.
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $repoRoot 'scripts/build-serialization-sample.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'Build script has parse errors' }
foreach ($name in @('Get-Sha256', 'Assert-SameHash')) {
    $definition = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false)
    if ($null -eq $definition) { throw "Missing function: $name" }
    Set-Item -Path "Function:$name" -Value $definition.Body.GetScriptBlock()
}

function Get-FileHash { throw 'Get-FileHash must not be required' }

try {
    New-Item -ItemType Directory -Force $scratch | Out-Null
    $built = Join-Path $scratch 'built.jar'
    $resolved = Join-Path $scratch 'resolved.jar'
    [System.IO.File]::WriteAllBytes($built, [System.Text.Encoding]::ASCII.GetBytes('abc'))
    [System.IO.File]::WriteAllBytes($resolved, [System.Text.Encoding]::ASCII.GetBytes('abc'))
    $expected = 'BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD'
    if ((Assert-SameHash $built $resolved) -cne $expected) { throw 'Incorrect SHA-256 digest' }

    [System.IO.File]::WriteAllBytes($resolved, [System.Text.Encoding]::ASCII.GetBytes('different'))
    $rejected = $false
    try { Assert-SameHash $built $resolved } catch {
        if ($_.Exception.Message -ne "Artifact mismatch: $resolved") { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Mismatched artifacts must fail verification' }

    $rejected = $false
    try { Get-Sha256 (Join-Path $scratch 'missing.jar') } catch { $rejected = $true }
    if (-not $rejected) { throw 'Missing artifacts must fail verification' }

    # Exclusive access also verifies that successful and mismatch paths close both file streams.
    foreach ($path in @($built, $resolved)) {
        $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $stream.Dispose()
    }
    Write-Output 'PASS SHA-256, equal artifacts, mismatches, missing files and stream cleanup without Get-FileHash'
} finally {
    if (Test-Path $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
}
