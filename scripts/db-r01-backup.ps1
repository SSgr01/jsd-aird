[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DatabaseHost,
    [int]$DatabasePort = 5432,
    [Parameter(Mandatory=$true)][string]$DatabaseName,
    [Parameter(Mandatory=$true)][string]$DatabaseUser,
    [Parameter(Mandatory=$true)][string]$BackupDirectory,
    [string]$PgDump = 'pg_dump', [string]$Psql = 'psql'
)
$ErrorActionPreference='Stop'
if (-not $env:PGPASSWORD) { throw 'Set PGPASSWORD; it is never persisted.' }
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir=[IO.Path]::GetFullPath($BackupDirectory)
$repoRoot=$repo.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
$repoPrefix=$repoRoot + [IO.Path]::DirectorySeparatorChar
if ($dir.Equals($repoRoot,[StringComparison]::OrdinalIgnoreCase) -or
    $dir.StartsWith($repoPrefix,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'BackupDirectory must be outside the repository.'
}
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$dump=Join-Path $dir "${DatabaseName}-${stamp}.dump"
$inventory=Join-Path $dir "${DatabaseName}-${stamp}.manifest.json"
& $PgDump -Fc --no-owner --no-privileges -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -f $dump
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
$sql=Join-Path $repo 'jsd-aird-api\src\main\resources\db\r01\protected_data_manifest.sql'
$json=& $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -f $sql
if ($LASTEXITCODE -ne 0) { throw 'Protected-data inventory failed.' }
[IO.File]::WriteAllText($inventory,($json -join ''),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{dump=$dump;manifest=$inventory;dumpSha256=(Get-FileHash -Algorithm SHA256 $dump).Hash.ToLowerInvariant()} | ConvertTo-Json
