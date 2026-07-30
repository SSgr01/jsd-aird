$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $Root "jsd-aird-web")
try {
    if (-not (Test-Path -LiteralPath "node_modules")) {
        npm ci
    }
    npm run dev
}
finally {
    Pop-Location
}

