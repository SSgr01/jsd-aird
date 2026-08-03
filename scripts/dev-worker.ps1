$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

$envFile = Join-Path $Root ".env"
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
            $name = $matches[1]
            $value = $matches[2].Trim().Trim('"').Trim("'")
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

if ($env:JSD_AIRD_JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JSD_AIRD_JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME = $env:JSD_AIRD_JAVA_HOME
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}

$env:JSD_AIRD_WORKER_ENABLED = "true"
Push-Location (Join-Path $Root "jsd-aird-api")
try {
    .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,worker"
}
finally {
    Pop-Location
}
