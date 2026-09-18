$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $Root "jsd-aird-web")
try {
    if (-not (Test-Path -LiteralPath "node_modules")) {
        & npm.cmd ci
    }
    & npm.cmd run dev
}
finally {
    Pop-Location
}

