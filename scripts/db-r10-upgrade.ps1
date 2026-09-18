[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DatabaseHost,
    [int]$DatabasePort = 5432,
    [Parameter(Mandatory=$true)][string]$DatabaseName,
    [Parameter(Mandatory=$true)][string]$DatabaseUser,
    [Parameter(Mandatory=$true)][string]$ExpectedDatabase,
    [switch]$Apply,
    [string]$Psql = 'psql'
)
$ErrorActionPreference = 'Stop'
if (-not $Apply) { throw 'R10 upgrade is destructive. Re-run with -Apply only after backup and evidence review.' }
if ($DatabaseName -ne $ExpectedDatabase) { throw 'DatabaseName does not match ExpectedDatabase.' }
if (-not $env:PGPASSWORD) { throw 'Set PGPASSWORD for psql. The script never persists it.' }

$root = Split-Path $PSScriptRoot -Parent
$api = Join-Path $root 'jsd-aird-api'
$migration = Join-Path $api 'src\main\resources\db\migration'
$preserve = Join-Path $api 'src\main\resources\db\r01\preserve_legacy_ai_evidence.sql'
$preflight = Join-Path $api 'src\main\resources\db\r10\preflight.sql'
$manifest = Join-Path $api 'src\main\resources\db\r01\legacy_ai_deletion_inventory.sql'
$protected = Join-Path $api 'src\main\resources\db\r01\protected_data_manifest.sql'
$jdbcUrl = "jdbc:postgresql://${DatabaseHost}:${DatabasePort}/${DatabaseName}"

function Invoke-Psql([string]$File) {
    $value = & $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -f $File
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $File" }
    return ($value | Where-Object { $_ -and -not $_.StartsWith('psql:') }) -join ''
}
function Invoke-Flyway([string[]]$Arguments) {
    $oldUrl=$env:FLYWAY_URL; $oldUser=$env:FLYWAY_USER; $oldPassword=$env:FLYWAY_PASSWORD; $oldLocations=$env:FLYWAY_LOCATIONS
    try {
        $env:FLYWAY_URL=$jdbcUrl; $env:FLYWAY_USER=$DatabaseUser; $env:FLYWAY_PASSWORD=$env:PGPASSWORD
        $env:FLYWAY_LOCATIONS='filesystem:' + ($migration -replace '\\','/')
        Push-Location $api
        try { & '.\mvnw.cmd' -q @Arguments; if ($LASTEXITCODE -ne 0) { throw "Flyway command failed: $Arguments" } }
        finally { Pop-Location }
    } finally {
        $env:FLYWAY_URL=$oldUrl; $env:FLYWAY_USER=$oldUser; $env:FLYWAY_PASSWORD=$oldPassword; $env:FLYWAY_LOCATIONS=$oldLocations
    }
}

$state = (Invoke-Psql $preflight) | ConvertFrom-Json
[void]($state | ConvertTo-Json -Depth 20)
if ($state.currentFlywayVersion -ne 62 -or $state.foundationStatus -ne 'COMPLETE_FOUNDATION') {
    throw ($state | ConvertTo-Json -Depth 20)
}
[void](Invoke-Psql $protected)
Invoke-Psql $preserve | Out-Null
$stateAfterPreserve = (Invoke-Psql $preflight) | ConvertFrom-Json
if (-not $stateAfterPreserve.readyForV63) { throw ($stateAfterPreserve | ConvertTo-Json -Depth 20) }
Invoke-Psql $manifest | Out-Null
Invoke-Flyway @('flyway:validate')
Invoke-Flyway @('flyway:migrate')
Invoke-Flyway @('flyway:validate')
$after = & $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -c "select max(version::int) from flyway_schema_history where success"
if (($after -join '').Trim() -ne '63') { throw "Expected Flyway version 63, got $after" }
Write-Output ('R10 migration completed at version ' + ($after -join '').Trim())
