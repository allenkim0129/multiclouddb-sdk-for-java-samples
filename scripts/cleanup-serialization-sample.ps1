# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.
# Delete one explicitly named, run-specific resource. Never enumerate or delete by prefix.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('cosmos', 'dynamo')]
    [string]$Provider,
    [Parameter(Mandatory = $true)]
    [string]$Database,
    [string]$Collection,
    [string]$Region,
    [string]$Profile,
    [string]$CosmosAccount,
    [string]$ResourceGroup,
    [string]$Subscription
)

$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    $result = & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE; cleanup stopped" }
    return $result
}

# Require the full generated database name, not the configured prefix or a wildcard.
if ($Database -cnotmatch '^[A-Za-z0-9_.-]+-pojo-[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\z') {
    throw 'Pass the exact generated database name (<prefix>-pojo-<UUID>) from this run, not the properties prefix.'
}

if ($Provider -eq 'cosmos') {
    if ([string]::IsNullOrWhiteSpace($CosmosAccount) -or
        [string]::IsNullOrWhiteSpace($ResourceGroup) -or [string]::IsNullOrWhiteSpace($Subscription)) {
        throw 'Cosmos cleanup requires -CosmosAccount, -ResourceGroup and -Subscription.'
    }
    $scope = @('--subscription', $Subscription, '--resource-group', $ResourceGroup,
        '--account-name', $CosmosAccount, '--name', $Database, '--only-show-errors')
    $resource = Invoke-Checked 'az' (@('cosmosdb', 'sql', 'database', 'show') + $scope + @('--query', 'id', '--output', 'tsv'))
    $confirmation = $Database
} else {
    if ($Collection -notmatch '^[A-Za-z0-9_.-]+\z' -or [string]::IsNullOrWhiteSpace($Region)) {
        throw 'Dynamo cleanup requires -Collection (letters, digits, dot, underscore or hyphen) and -Region.'
    }
    $table = $Database + '__' + $Collection
    $scope = @('--region', $Region, '--no-cli-pager')
    if (-not [string]::IsNullOrWhiteSpace($Profile)) { $scope += @('--profile', $Profile) }
    $resource = Invoke-Checked 'aws' (@('dynamodb', 'describe-table', '--table-name', $table,
        '--query', 'Table.TableArn', '--output', 'text') + $scope)
    $confirmation = $table
}

if ([string]::IsNullOrWhiteSpace([string]$resource) -or [string]$resource -eq 'None') {
    throw 'Could not identify the exact resource; nothing deleted.'
}
Write-Host "Target resource: $resource"
Write-Host 'This deletes the ENTIRE named database (Cosmos) or table (Dynamo), including any remaining data.'
Write-Host 'Verify the subscription/account, region and generated run name above before continuing.'
if ((Read-Host "Type DELETE $confirmation to confirm") -cne "DELETE $confirmation") {
    throw 'Cancelled; nothing deleted.'
}

if ($Provider -eq 'cosmos') {
    $null = Invoke-Checked 'az' (@('cosmosdb', 'sql', 'database', 'delete') + $scope + @('--yes', '--output', 'none'))
} else {
    $null = Invoke-Checked 'aws' (@('dynamodb', 'delete-table', '--table-name', $table, '--output', 'json') + $scope)
    # Dynamo deletion is asynchronous. Wait for this exact table, not a prefix or list of tables.
    $null = Invoke-Checked 'aws' (@('dynamodb', 'wait', 'table-not-exists', '--table-name', $table) + $scope)
}
Write-Output "PASS resource cleanup: $resource"
