# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
# Every CLI invocation and confirmation is mocked; no cloud access or deletions.
$ErrorActionPreference = 'Stop'
$cleanup = Join-Path (Split-Path -Parent $PSScriptRoot) 'cleanup-serialization-sample.ps1'
$database = 'synthetic-db-pojo-12345678-1234-4234-8234-123456789abc'
$table = $database + '__orders'

function Reset-TestState {
    param([string]$Answer)
    $script:state = @{
        Calls = [System.Collections.Generic.List[string]]::new()
        Prompts = [System.Collections.Generic.List[string]]::new()
        FailAt = 0
        EmptyResource = $false
        Answer = $Answer
    }
}

function Invoke-FakeCli {
    param([string]$Program, [object[]]$Arguments)
    $state.Calls.Add("$Program " + ($Arguments -join ' '))
    $global:LASTEXITCODE = 0
    if ($state.Calls.Count -eq $state.FailAt) {
        $global:LASTEXITCODE = 7
        return
    }
    if ($state.EmptyResource) { return }
    if ($Arguments -contains 'show') {
        return "/subscriptions/synthetic-sub/resourceGroups/synthetic-rg/providers/Microsoft.DocumentDB/databaseAccounts/synthetic-account/sqlDatabases/$database"
    }
    if ($Arguments -contains 'describe-table') {
        return "arn:aws:dynamodb:us-east-1:123456789012:table/$table"
    }
}
function az { Invoke-FakeCli 'az' $args }
function aws { Invoke-FakeCli 'aws' $args }
function Read-Host {
    param([string]$Prompt)
    $state.Prompts.Add($Prompt)
    return $state.Answer
}
function Assert-Equal {
    param($Expected, $Actual)
    if ($Expected -cne $Actual) { throw "Expected [$Expected], got [$Actual]" }
}
function Assert-Rejected {
    param([hashtable]$Parameters, [string]$Message)
    $rejected = $false
    try { & $cleanup @Parameters | Out-Null } catch {
        if ($_.Exception.Message -notlike "*$Message*") { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Expected cleanup to fail' }
}

$cosmos = @{
    Provider = 'cosmos'; Database = $database
    CosmosAccount = 'synthetic-account'; ResourceGroup = 'synthetic-rg'; Subscription = 'synthetic-sub'
}
$dynamo = @{ Provider = 'dynamo'; Database = $database; Collection = 'orders'; Region = 'us-east-1' }

Reset-TestState "DELETE $database"
$output = & $cleanup @cosmos
Assert-Equal 2 $state.Calls.Count
Assert-Equal "az cosmosdb sql database show --subscription synthetic-sub --resource-group synthetic-rg --account-name synthetic-account --name $database --only-show-errors --query id --output tsv" $state.Calls[0]
Assert-Equal "az cosmosdb sql database delete --subscription synthetic-sub --resource-group synthetic-rg --account-name synthetic-account --name $database --only-show-errors --yes --output none" $state.Calls[1]
Assert-Equal 1 $state.Prompts.Count
if ($output -notlike 'PASS resource cleanup:*') { throw 'Successful Cosmos cleanup was not reported' }

foreach ($profile in @('', 'synthetic-profile')) {
    Reset-TestState "DELETE $table"
    $output = & $cleanup @dynamo -Profile $profile
    $scope = '--region us-east-1 --no-cli-pager'
    if ($profile) { $scope += ' --profile synthetic-profile' }
    Assert-Equal 3 $state.Calls.Count
    Assert-Equal "aws dynamodb describe-table --table-name $table --query Table.TableArn --output text $scope" $state.Calls[0]
    Assert-Equal "aws dynamodb delete-table --table-name $table --output json $scope" $state.Calls[1]
    Assert-Equal "aws dynamodb wait table-not-exists --table-name $table $scope" $state.Calls[2]
    if ($output -notlike 'PASS resource cleanup:*') { throw 'Successful Dynamo cleanup was not reported' }
}

foreach ($parameters in @($cosmos, $dynamo)) {
    foreach ($invalid in @('synthetic-db', '*', "$database*", "$database`n", 'db-pojo-not-a-uuid')) {
        Reset-TestState 'DELETE'
        $bad = $parameters.Clone()
        $bad.Database = $invalid
        Assert-Rejected $bad 'exact generated database'
        Assert-Equal 0 $state.Calls.Count
    }
    Reset-TestState 'yes'
    Assert-Rejected $parameters 'Cancelled'
    Assert-Equal 1 $state.Calls.Count
    Reset-TestState 'DELETE'
    $state.EmptyResource = $true
    Assert-Rejected $parameters 'Could not identify'
    Assert-Equal 1 $state.Calls.Count
    Assert-Equal 0 $state.Prompts.Count
    foreach ($failAt in @(1, 2)) {
        $name = if ($parameters.Provider -eq 'cosmos') { $database } else { $table }
        Reset-TestState "DELETE $name"
        $state.FailAt = $failAt
        Assert-Rejected $parameters 'exit code 7'
        Assert-Equal $failAt $state.Calls.Count
        Assert-Equal ($failAt - 1) $state.Prompts.Count
    }
}
Reset-TestState "DELETE $table"
$state.FailAt = 3
Assert-Rejected $dynamo 'exit code 7'
Assert-Equal 3 $state.Calls.Count

foreach ($entry in @(
    @{ Parameters = $cosmos; Field = 'Subscription'; Message = 'Cosmos cleanup requires' },
    @{ Parameters = $dynamo; Field = 'Region'; Message = 'Dynamo cleanup requires' },
    @{ Parameters = $dynamo; Field = 'Collection'; Message = 'Dynamo cleanup requires' }
)) {
    Reset-TestState 'DELETE'
    $bad = $entry.Parameters.Clone()
    $bad[$entry.Field] = ''
    Assert-Rejected $bad $entry.Message
    Assert-Equal 0 $state.Calls.Count
}
Reset-TestState "DELETE $table"
$bad = $dynamo.Clone()
$bad.Collection = '*'
Assert-Rejected $bad 'Dynamo cleanup requires'
Assert-Equal 0 $state.Calls.Count
Write-Output 'PASS exact-resource deletion, confirmation, credential scope, invalid targets and CLI failures (mocked)'
