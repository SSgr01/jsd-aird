$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $Root "jsd-aird-api")
try {
    .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
}
finally {
    Pop-Location
}

