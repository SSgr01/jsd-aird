[CmdletBinding()]
param([ValidateSet('api','worker','scheduler','migrate','seed','seed-monitoring','packs-list')][string]$Mode = 'api')
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$moduleRoot = Join-Path $repoRoot "jsd-aird-agents"
$pythonExe = Join-Path $moduleRoot ".venv\Scripts\python.exe"
$rootEnv = Join-Path $repoRoot ".env"
if (Test-Path -LiteralPath $rootEnv) {
    Get-Content -LiteralPath $rootEnv | ForEach-Object {
        if ($_ -match '^\s*(JSD_AIRD_MODEL_[A-Z_]+)=(.*)$') {
            Set-Item -Path "Env:$($matches[1])" -Value $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}
if (-not (Test-Path -LiteralPath $pythonExe)) { throw "Create jsd-aird-agents/.venv and install requirements.lock.txt first." }
Push-Location $moduleRoot
try {
    & $pythonExe -X utf8 -m jsd_aird_agents $Mode
    if ($LASTEXITCODE -ne 0) { throw "Agents $Mode exited unsuccessfully." }
} finally { Pop-Location }
