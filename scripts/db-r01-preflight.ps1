[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DatabaseHost,
    [int]$DatabasePort = 5432,
    [Parameter(Mandatory=$true)][string]$DatabaseName,
    [Parameter(Mandatory=$true)][string]$DatabaseUser,
    [string]$Psql = 'psql'
)
$ErrorActionPreference = 'Stop'
$sql = Join-Path $PSScriptRoot '..\jsd-aird-api\src\main\resources\db\r01\preflight.sql'
$result = & $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -f $sql
if ($LASTEXITCODE -ne 0) { throw 'R01 preflight query failed.' }
$json = ($result | Where-Object { $_ -and -not $_.StartsWith('psql:') }) -join ''
$parsed = $json | ConvertFrom-Json
$parsed | ConvertTo-Json -Depth 20
if ($parsed.classification -eq 'INCOMPATIBLE_FOUNDATION') { exit 2 }
