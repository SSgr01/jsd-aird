[CmdletBinding()]
param([int]$Port = 55439, [string[]]$PytestArgs = @('-q'))
$ErrorActionPreference = "Stop"
$moduleRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "setup-local-postgres.ps1") -Port $Port
$pythonExe = Join-Path $moduleRoot ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $pythonExe)) { throw "Install the module in .venv first; see README.md." }
$env:JSD_AIRD_AGENTS_TEST_DATABASE_URL = "postgresql://agents_test@127.0.0.1:$Port/jsd_aird_agents_test"
Push-Location $moduleRoot
try {
    & $pythonExe -X utf8 -m ruff check src tests tools
    if ($LASTEXITCODE -ne 0) { throw "Ruff failed." }
    & $pythonExe -X utf8 -m pytest @PytestArgs
    if ($LASTEXITCODE -ne 0) { throw "Tests failed." }
} finally { Pop-Location }
