[CmdletBinding()]
param(
    [switch]$Postgres,
    [switch]$FullBackend,
    [switch]$BackendOnly,
    [switch]$IncludePython,
    [switch]$Build,
    [string]$DatabaseEnvFile,
    [string]$JavaHome,
    [string]$MavenCommand
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportDir = Join-Path $taskRoot ('.tmp/module-validation/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
$outcomes = [System.Collections.Generic.List[object]]::new()
$originalDirectory = Get-Location
$savedEnvironment = @{}
foreach ($key in @('JAVA_HOME', 'Path', 'NODE_OPTIONS', 'JSD_AIRD_CONSISTENCY_PG_TEST', 'JSD_AIRD_OPERATION_MANUAL_PG_TEST', 'JSD_AIRD_WORKBENCH_DB_TEST', 'JSD_AIRD_CATALOG_DB_TEST', 'JSD_AIRD_DATASOURCE_URL', 'JSD_AIRD_DATASOURCE_USERNAME', 'JSD_AIRD_DATASOURCE_PASSWORD')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}

function Invoke-ModuleCheck([string]$Name, [string]$Directory, [scriptblock]$Action) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $log = Join-Path $reportDir ($Name + '.log')
    Write-Host "Running $Name"
    Push-Location $Directory
    $previousErrorAction = $ErrorActionPreference
    try {
        $global:LASTEXITCODE = 0
        # Windows PowerShell turns native stderr (including harmless JVM warnings)
        # into ErrorRecords. Judge native commands by their exit code, not stderr.
        $ErrorActionPreference = 'Continue'
        # Store output without interleaving Maven, Vitest and Python output.
        & $Action *> $log
        $code = $LASTEXITCODE
    } catch {
        $_ | Out-String | Add-Content -LiteralPath $log
        $code = 1
    } finally {
        $ErrorActionPreference = $previousErrorAction
        Pop-Location
    }
    $outcomes.Add([pscustomobject]@{ check = $Name; exitCode = $code; seconds = [Math]::Round($watch.Elapsed.TotalSeconds, 1); log = $log })
    Write-Host "$Name exited $code"
}

try {
    if ($BackendOnly -and ($IncludePython -or $Build)) { throw '-BackendOnly cannot be combined with -IncludePython or -Build.' }
    foreach ($options in @($env:MAVEN_OPTS, $env:JAVA_TOOL_OPTIONS, $env:JDK_JAVA_OPTIONS)) {
        if ($options -match 'jsd\.it\.jdbcUrl') { throw 'Remove external integration database JVM options before this isolated run.' }
    }
    # These older integration suites use application schemas and existing samples.
    # Enable only the allowlisted isolated-schema suites in this runner.
    $env:JSD_AIRD_WORKBENCH_DB_TEST = 'false'
    $env:JSD_AIRD_CATALOG_DB_TEST = 'false'
    if ($JavaHome) {
        $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
        $env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
    }
    if ($DatabaseEnvFile) {
        if (-not $Postgres) { throw 'DatabaseEnvFile requires -Postgres.' }
        # Explicit allowlist: never import or print other application secrets.
        foreach ($line in Get-Content -LiteralPath $DatabaseEnvFile) {
            if ($line -match '^\s*(JSD_AIRD_DATASOURCE_(?:URL|USERNAME|PASSWORD))\s*=\s*(.*)$') {
                $key = $Matches[1]
                $value = $Matches[2]
                if ($value -match '^(["''])(.*)\1$') { $value = $Matches[2] }
                [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            }
        }
    }
    if ($Postgres) {
        if ($env:JSD_AIRD_DATASOURCE_URL -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1)(:\d+)?/') {
            throw 'PostgreSQL tests require a loopback database; only isolated schemas will be written.'
        }
        $env:JSD_AIRD_CONSISTENCY_PG_TEST = 'true'
        $env:JSD_AIRD_OPERATION_MANUAL_PG_TEST = 'true'
    } else {
        $env:JSD_AIRD_CONSISTENCY_PG_TEST = 'false'
        $env:JSD_AIRD_OPERATION_MANUAL_PG_TEST = 'false'
    }
    $api = Join-Path $taskRoot 'jsd-aird-api'
    if ($MavenCommand) { $maven = (Get-Command $MavenCommand -ErrorAction Stop).Source }
    elseif (Get-Command mvn.cmd -ErrorAction SilentlyContinue) { $maven = (Get-Command mvn.cmd).Source }
    elseif (Get-Command mvn -ErrorAction SilentlyContinue) { $maven = (Get-Command mvn).Source }
    else { $maven = Join-Path $api 'mvnw.cmd' }

    # These PG suites enforce loopback + random test schema isolation themselves.
    $selection = '*ConsistencyTest,*ConsistencyPostgresTest,*ConcurrencyPostgresTest,*VersionContractTest,TestIsolationPostgresTest,OperationManualIndexPostgresTest,TemplateReviewConsistencyTest,ProjectTaskConsistencyTest,WritePermissionRefreshTest,RevisionTokenTest'
    if ($FullBackend) { $selection = '*Test,!ArchitectureRulesTest,!ModuleBoundaryTest' }
    Invoke-ModuleCheck 'backend' $api { & $maven "-Dtest=$selection" test }
    $surefire = Join-Path $api 'target/surefire-reports'
    $xmlDir = Join-Path $reportDir 'surefire'
    New-Item -ItemType Directory -Path $xmlDir -Force | Out-Null
    # Copy only reports touched by this run; unrelated old reports are not evidence.
    $backendStart = (Get-Item -LiteralPath (Join-Path $reportDir 'backend.log')).CreationTime
    Get-ChildItem -LiteralPath $surefire -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $backendStart } |
        Copy-Item -Destination $xmlDir
    if ($FullBackend) {
        Invoke-ModuleCheck 'architecture' $api { & $maven '-Dtest=ArchitectureRulesTest,ModuleBoundaryTest' test }
    }
    $web = Join-Path $taskRoot 'jsd-aird-web'
    if (-not $BackendOnly) {
        Invoke-ModuleCheck 'frontend-types' $web { & node 'node_modules/typescript/bin/tsc' -b --pretty false }
        $webReport = Join-Path $reportDir 'vitest.json'
        Invoke-ModuleCheck 'frontend' $web { & node 'node_modules/vitest/vitest.mjs' run --maxWorkers=2 --reporter=json "--outputFile=$webReport" }
    }
    if ($IncludePython) {
        $ai = Join-Path $taskRoot 'jsd-aird-ai'
        $python = Join-Path $ai '.venv/Scripts/python.exe'
        if (-not (Test-Path -LiteralPath $python)) { $python = (Get-Command python -ErrorAction Stop).Source }
        Invoke-ModuleCheck 'python' $ai { & $python -m pytest --tb=short }
    }
    if ($Build) {
        $env:NODE_OPTIONS = '--max-old-space-size=4096'
        Invoke-ModuleCheck 'frontend-build' $web { & node 'node_modules/vite/bin/vite.js' build }
        Invoke-ModuleCheck 'bundle-report-tests' $web { & node --test 'scripts/bundle-report.check.mjs' }
        Invoke-ModuleCheck 'bundle-budget' $web { & node 'scripts/check-bundle-size.mjs' }
    }
    $outcomes | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportDir 'results.json') -Encoding UTF8
    Write-Host "Reports: $reportDir"
} finally {
    Set-Location $originalDirectory
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
if (@($outcomes | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) { exit 1 }
