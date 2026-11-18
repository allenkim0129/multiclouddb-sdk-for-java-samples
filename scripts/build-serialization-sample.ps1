# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Sha = 'ef339fb6529a1b367987bff0b4884c4127708fd9'
$Sdk = Join-Path $Root '.local/serialization-sdk'
$Repo = Join-Path $Root ".local/m2-$Sha"
$Upstream = 'https://github.com/microsoft/multiclouddb-sdk-for-java.git'

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

function Assert-SameHash {
    param([string]$Built, [string]$Resolved)
    $builtHash = (Get-FileHash -Algorithm SHA256 $Built).Hash
    $resolvedHash = (Get-FileHash -Algorithm SHA256 $Resolved).Hash
    if ($builtHash -ne $resolvedHash) { throw "Artifact mismatch: $Resolved" }
    return $resolvedHash
}

Push-Location $Root
try {
    foreach ($tool in @('git', 'java', 'mvn')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Install $tool and add it to PATH first" }
    }
    if (Test-Path '.mvn/maven.config') {
        throw 'Move .mvn/maven.config aside first: this build must not inherit SDK version overrides'
    }
    Invoke-Checked 'java' @('-version')
    Invoke-Checked 'mvn' @('-version')
    New-Item -ItemType Directory -Force (Join-Path $Root '.local') | Out-Null
    if (-not (Test-Path $Sdk)) {
        Invoke-Checked 'git' @('clone', '--no-checkout', $Upstream, $Sdk)
        Invoke-Checked 'git' @('-C', $Sdk, 'fetch', $Upstream, 'refs/pull/122/head')
        Invoke-Checked 'git' @('-C', $Sdk, 'checkout', '--detach', $Sha)
    }
    $head = & git -C $Sdk rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $head -ne $Sha) { throw "SDK checkout must be exactly $Sha; refusing to overwrite it" }
    $dirty = & git -C $Sdk status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'SDK checkout is not clean; refusing to build modified source' }
    if (Test-Path (Join-Path $Sdk '.mvn/maven.config')) {
        throw 'SDK checkout must not contain local Maven overrides'
    }

    [xml]$sdkPom = Get-Content -Raw (Join-Path $Sdk 'pom.xml')
    $modules = [ordered]@{
        'multiclouddb-api' = $sdkPom.project.properties.'multiclouddb-api.version'
        'multiclouddb-provider-cosmos' = $sdkPom.project.properties.'multiclouddb-provider-cosmos.version'
        'multiclouddb-provider-dynamo' = $sdkPom.project.properties.'multiclouddb-provider-dynamo.version'
        'multiclouddb-provider-spanner' = $sdkPom.project.properties.'multiclouddb-provider-spanner.version'
    }
    $versionArgs = @()
    foreach ($module in $modules.Keys) {
        $versionArgs += "-D$module.version=$($modules[$module])"
    }
    $localRepoArg = "-Dmaven.repo.local=$Repo"
    Invoke-Checked 'mvn' (@('-B', '-ntp', '-f', (Join-Path $Sdk 'pom.xml'), $localRepoArg,
            '-pl', ($modules.Keys -join ','), '-am', 'clean', 'install',
            '-DskipTests', '-Dmaven.javadoc.skip=true') + $versionArgs)

    # This profile packages UI assets and safe logging, never local credential configs.
    Invoke-Checked 'mvn' (@('-B', '-ntp', '-Pserialization-pr', $localRepoArg, 'clean', 'package',
            'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
            '-DincludeGroupIds=com.microsoft.multiclouddb',
            '-DoutputDirectory=target/serialization-sdk-artifacts') + $versionArgs)
    Invoke-Checked 'mvn' (@('-B', '-ntp', '-Pserialization-pr', $localRepoArg,
            'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree',
            '-Dverbose', '-DoutputFile=target/serialization-dependencies.txt') + $versionArgs)

    $evidence = @("SDK SHA: $head", 'Source: microsoft/multiclouddb-sdk-for-java PR 122',
        "Isolated Maven repository: $Repo", 'Artifact SHA256 hashes (built = installed = sample-resolved):')
    foreach ($module in $modules.Keys) {
        $version = $modules[$module]
        $file = "$module-$version.jar"
        $built = Join-Path $Sdk "$module/target/$file"
        $installed = Join-Path $Repo "com/microsoft/multiclouddb/$module/$version/$file"
        $resolved = Join-Path $Root "target/serialization-sdk-artifacts/$file"
        $null = Assert-SameHash $built $installed
        $hash = Assert-SameHash $built $resolved
        $installedPom = Join-Path $Repo "com/microsoft/multiclouddb/$module/$version/$module-$version.pom"
        $pomHash = Assert-SameHash (Join-Path $Sdk "$module/.flattened-pom.xml") $installedPom
        $evidence += "$module $version JAR=$hash POM=$pomHash"
    }
    $evidence | Set-Content -Encoding UTF8 'target/serialization-build-evidence.txt'

    Write-Host 'Built and checked offline. No live account was accessed.'
    Write-Host 'Run the live sample separately after configuring your test target: cosmos (or dynamo).'
    Write-Host 'Evidence: target/serialization-build-evidence.txt and target/serialization-dependencies.txt'
} finally {
    Pop-Location
}
