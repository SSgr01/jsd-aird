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
if (-not $Apply) { throw 'R01 upgrade is write-capable. Re-run with -Apply after reviewing preflight output.' }
if ($DatabaseName -ne $ExpectedDatabase) { throw 'DatabaseName does not match ExpectedDatabase.' }
if (-not $env:PGPASSWORD) { throw 'Set PGPASSWORD for psql. The script never persists it.' }

$api = Join-Path $PSScriptRoot '..\jsd-aird-api'
$migration = Join-Path $api 'src\main\resources\db\migration'
$foundation = Join-Path $api 'src\main\resources\db\foundation\mdm_rnd_foundation.sql'
$preflight = Join-Path $api 'src\main\resources\db\r01\preflight.sql'
$preserve = Join-Path $api 'src\main\resources\db\r01\preserve_legacy_ai_evidence.sql'
$manifest = Join-Path $api 'src\main\resources\db\r01\protected_data_manifest.sql'
$jdbcUrl = "jdbc:postgresql://${DatabaseHost}:${DatabasePort}/${DatabaseName}"

function Invoke-Psql([string]$SqlFile) {
    $value = & $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -f $SqlFile
    if ($LASTEXITCODE -ne 0) { throw "psql failed for $SqlFile" }
    return ($value | Where-Object { $_ }) -join ''
}
function Invoke-Flyway([string[]]$Arguments) {
    $oldUrl=$env:FLYWAY_URL; $oldUser=$env:FLYWAY_USER; $oldPassword=$env:FLYWAY_PASSWORD; $oldLocations=$env:FLYWAY_LOCATIONS
    try {
        $env:FLYWAY_URL=$jdbcUrl; $env:FLYWAY_USER=$DatabaseUser; $env:FLYWAY_PASSWORD=$env:PGPASSWORD
        $env:FLYWAY_LOCATIONS='filesystem:' + ($migration -replace '\\','/')
        Push-Location $api
        try {
            & '.\mvnw.cmd' -q @Arguments
            if ($LASTEXITCODE -ne 0) { throw "Flyway command failed: $Arguments" }
        } finally {
            Pop-Location
        }
    } finally {
        $env:FLYWAY_URL=$oldUrl; $env:FLYWAY_USER=$oldUser; $env:FLYWAY_PASSWORD=$oldPassword; $env:FLYWAY_LOCATIONS=$oldLocations
    }
}

$server = & $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -c "select current_setting('server_version_num')::int, current_database()"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect target database.' }
$parts = ($server -join '').Split('|')
if ([int]$parts[0] -lt 160000 -or [int]$parts[0] -ge 190000) { throw "PostgreSQL 16-18 required; target reports $($parts[0])." }
if ($parts[1] -ne $ExpectedDatabase) { throw 'Connected database identity does not match ExpectedDatabase.' }

# Inspect the domain foundation before the first write. A database with a
# partial or incompatible mdm/rnd foundation must not be advanced to V52 and
# then mistaken for an empty initialization target.
$initialState = (Invoke-Psql $preflight) | ConvertFrom-Json
if ($initialState.classification -eq 'INCOMPATIBLE_FOUNDATION' -and $initialState.presentTableCount -gt 0) {
    throw ($initialState | ConvertTo-Json -Depth 20)
}

$hasHistory = & $Psql -X -A -t -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -c "select to_regclass('public.flyway_schema_history') is not null"
if (($hasHistory -join '').Trim() -ne 't') { Invoke-Flyway @('-Dflyway.target=52','flyway:migrate') }
Invoke-Flyway @('-Dflyway.ignoreMigrationPatterns=*:pending','flyway:validate')
$vector = & $Psql -X -A -t -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -c "select exists(select 1 from pg_extension where extname='vector')"
if (($vector -join '').Trim() -ne 't') { throw 'pgvector extension is required.' }
$state = (Invoke-Psql $preflight) | ConvertFrom-Json
if ($state.classification -eq 'INCOMPATIBLE_FOUNDATION') { throw ($state | ConvertTo-Json -Depth 20) }
if ($state.classification -eq 'EMPTY_FOUNDATION') { [void](Invoke-Psql $foundation) }
Invoke-Flyway @('flyway:migrate')
Invoke-Flyway @('flyway:validate')
[void](Invoke-Psql $preserve)
$finalState = (Invoke-Psql $preflight) | ConvertFrom-Json
if ($finalState.classification -ne 'COMPLETE_FOUNDATION') { throw ($finalState | ConvertTo-Json -Depth 20) }
$finalState | ConvertTo-Json -Depth 20
Invoke-Psql $manifest
