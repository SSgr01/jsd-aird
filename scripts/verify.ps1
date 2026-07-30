$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $Root "jsd-aird-web")
try {
    npm ci
    npm run verify
}
finally {
    Pop-Location
}

Push-Location (Join-Path $Root "jsd-aird-api")
try {
    .\mvnw.cmd verify
}
finally {
    Pop-Location
}

