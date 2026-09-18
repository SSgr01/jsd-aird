$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $Root ".env"
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
            Set-Item -Path "Env:$($matches[1])" -Value $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}
$aiRoot = Join-Path $Root "jsd-aird-ai"
$venv = Join-Path $aiRoot ".venv"
$python = Join-Path $venv "Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw "找不到 Python 3.12，请先安装并加入 PATH。" }
    Push-Location $aiRoot
    try {
        & python -m venv .venv
        & $python -m pip install --upgrade pip
        & $python -m pip install -r requirements.lock.txt
        & $python -m pip install --no-deps -e .
    } finally { Pop-Location }
}
$env:PYTHONPATH = Join-Path $aiRoot "src"
Push-Location $aiRoot
try { & $python -m jsd_aird_ai.main } finally { Pop-Location }
