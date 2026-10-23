[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$Port = 55439, [string]$PgBin, [switch]$Stop)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$clusterRoot = Join-Path $repoRoot ".tmp\agents-pg"
$dataRoot = Join-Path $clusterRoot "data"
$expectedRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot ".tmp")) + [IO.Path]::DirectorySeparatorChar
if (-not [IO.Path]::GetFullPath($dataRoot).StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to manage a cluster outside this repository's .tmp folder."
}
if (-not $PgBin) { $PgBin = Split-Path -Parent (Get-Command psql -ErrorAction Stop).Source }
$pgCtl = Join-Path $PgBin "pg_ctl.exe"
$psql = Join-Path $PgBin "psql.exe"
if ($Stop) {
    & $pgCtl -D $dataRoot -m fast -w stop
    if ($LASTEXITCODE -ne 0) { throw "Could not stop this agents cluster." }
    return
}
New-Item -ItemType Directory -Path $clusterRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $dataRoot "PG_VERSION"))) {
    & (Join-Path $PgBin "initdb.exe") -D $dataRoot -U agents_bootstrap -A trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw "initdb failed." }
}
& $pgCtl -D $dataRoot status *> $null
if ($LASTEXITCODE -ne 0) {
    & $pgCtl -D $dataRoot -l (Join-Path $clusterRoot "server.log") -o "-p $Port -h 127.0.0.1" -w -t 20 start
    if ($LASTEXITCODE -ne 0) { throw "Could not start the isolated agents cluster." }
}
$connection = @('-X','-w','-h','127.0.0.1','-p',"$Port",'-U','agents_bootstrap','-d','postgres','-v','ON_ERROR_STOP=1')
$actualData = (& $psql @connection -At -c 'SHOW data_directory').Trim()
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($actualData) -ne [IO.Path]::GetFullPath($dataRoot)) {
    throw "The PostgreSQL endpoint is not this repository's isolated agents cluster."
}
foreach ($entry in @(@('agents_dev','jsd_aird_agents_dev'), @('agents_test','jsd_aird_agents_test'))) {
    $role = $entry[0]; $database = $entry[1]
    $roleExists = & $psql @connection -At -c "SELECT 1 FROM pg_roles WHERE rolname='$role'"
    if ($LASTEXITCODE -ne 0) { throw "Role check failed." }
    if (-not $roleExists) {
        & $psql @connection -c "CREATE ROLE $role LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;"
        if ($LASTEXITCODE -ne 0) { throw "Role creation failed." }
    }
    $dbExists = & $psql @connection -At -c "SELECT 1 FROM pg_database WHERE datname='$database'"
    if ($LASTEXITCODE -ne 0) { throw "Database check failed." }
    if (-not $dbExists) {
        & $psql @connection -c "CREATE DATABASE $database OWNER $role;"
        if ($LASTEXITCODE -ne 0) { throw "Database creation failed." }
    }
}
Write-Host "Isolated native PostgreSQL ready on 127.0.0.1:$Port. Existing PostgreSQL services are unchanged."
Write-Host "Dev: postgresql://agents_dev@127.0.0.1:$Port/jsd_aird_agents_dev"
Write-Host "Test: postgresql://agents_test@127.0.0.1:$Port/jsd_aird_agents_test"
