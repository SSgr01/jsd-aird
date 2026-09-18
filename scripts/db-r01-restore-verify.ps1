[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DumpFile,
    [Parameter(Mandatory=$true)][string]$ManifestFile,
    [Parameter(Mandatory=$true)][string]$DatabaseHost,
    [int]$DatabasePort = 5432,
    [Parameter(Mandatory=$true)][string]$DatabaseName,
    [Parameter(Mandatory=$true)][string]$DatabaseUser,
    [Parameter(Mandatory=$true)][string]$ExpectedEmptyDatabase,
    [switch]$Apply,
    [string]$PgRestore='pg_restore', [string]$Psql='psql'
)
$ErrorActionPreference='Stop'
if (-not $Apply) { throw 'Restore writes to the target. Re-run with -Apply.' }
if ($DatabaseName -ne $ExpectedEmptyDatabase) { throw 'Target database identity mismatch.' }
if (-not $env:PGPASSWORD) { throw 'Set PGPASSWORD; it is never persisted.' }
$count=& $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -c "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')"
if ($LASTEXITCODE -ne 0 -or [int](($count -join '').Trim()) -ne 0) { throw 'Restore target must be an empty temporary database.' }
$existingExtensions = @(& $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -c "select extname from pg_extension") |
    ForEach-Object { $_.Trim() } | Where-Object { $_ }
$tocList = Join-Path ([IO.Path]::GetTempPath()) ("jsd-aird-restore-" + [guid]::NewGuid().ToString('N') + '.list')
$tocCreated = $false
try {
    # A normal application role can use an extension installed by the cluster
    # administrator but cannot DROP/CREATE it.  Keep those extensions in the
    # empty target and omit their TOC entries so a full logical restore remains
    # verifiable without silently weakening the target emptiness check.
    $dumpToc = @(& $PgRestore -l $DumpFile)
    $preserved = @($dumpToc | Where-Object {
        $line = $_
        $match = [regex]::Match($line, 'EXTENSION - ([^\s]+)')
        if (-not $match.Success) { return $false }
        $existingExtensions -contains $match.Groups[1].Value
    } | ForEach-Object { [regex]::Match($_, 'EXTENSION - ([^\s]+)').Groups[1].Value })
    if ($preserved.Count -gt 0) {
        $filteredToc = foreach ($line in $dumpToc) {
            $skip = $false
            foreach ($extension in $preserved) {
                if ($line -match ("EXTENSION - " + [regex]::Escape($extension) + '($|\s)') -or
                    $line -match ("COMMENT - EXTENSION " + [regex]::Escape($extension) + '($|\s)')) {
                    $skip = $true
                    break
                }
            }
            if (-not $skip) { $line }
        }
        $filteredToc | Set-Content -Encoding ascii -LiteralPath $tocList
        $tocCreated = $true
    }
    $restoreArgs = @('--exit-on-error','--no-owner','--no-privileges','--clean','--if-exists','-h',$DatabaseHost,'-p',$DatabasePort,'-U',$DatabaseUser,'-d',$DatabaseName)
    if ($tocCreated) { $restoreArgs += @('-L',$tocList) }
    $restoreArgs += $DumpFile
    & $PgRestore @restoreArgs
} finally {
    if (Test-Path -LiteralPath $tocList) { Remove-Item -Force -LiteralPath $tocList }
}
if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed.' }
$sql=Join-Path $PSScriptRoot '..\jsd-aird-api\src\main\resources\db\r01\protected_data_manifest.sql'
$actualJson=& $Psql -X -A -t -v ON_ERROR_STOP=1 -h $DatabaseHost -p $DatabasePort -U $DatabaseUser -d $DatabaseName -f $sql
if ($LASTEXITCODE -ne 0) { throw 'Restored inventory failed.' }
$expected=Get-Content -Raw -LiteralPath $ManifestFile | ConvertFrom-Json
$actual=($actualJson -join '') | ConvertFrom-Json
if ($expected.protectedDataDigest -ne $actual.protectedDataDigest) {
    throw "Restore verification failed: protected-data digest differs (expected $($expected.protectedDataDigest), actual $($actual.protectedDataDigest))."
}
[pscustomobject]@{
    restoredDatabase=$DatabaseName
    protectedDataDigest=$actual.protectedDataDigest
    manifestMatch=$true
    orphanAiExperimentLinks=$actual.orphanAiExperimentLinks
} | ConvertTo-Json
