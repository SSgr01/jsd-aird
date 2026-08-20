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

Push-Location (Join-Path $Root "jsd-aird-api")
try {
    # API and worker share target/classes. Serialize the initial compilation so
    # two concurrently started services cannot race javac on Windows.
    $compileMutex = [System.Threading.Mutex]::new($false, "Local\JsdAirdDevCompile")
    $compileLockHeld = $false
    try {
        try { $compileLockHeld = $compileMutex.WaitOne([TimeSpan]::FromMinutes(3)) }
        catch [System.Threading.AbandonedMutexException] { $compileLockHeld = $true }
        if (-not $compileLockHeld) { throw "Compilation mutex wait timed out" }
        & .\mvnw.cmd "-Dmaven.test.skip=true" compile
        if ($LASTEXITCODE -ne 0) {
            # Microsoft JDK 21 on Windows can report "cannot close compiler
            # resources" after all class files were emitted. The second pass is
            # then incremental and verifies the output without rebuilding it.
            & .\mvnw.cmd "-Dmaven.test.skip=true" compile
        }
        if ($LASTEXITCODE -ne 0) { throw "Backend compilation failed" }
    }
    finally {
        if ($compileLockHeld) { $compileMutex.ReleaseMutex() }
        $compileMutex.Dispose()
    }
    & .\mvnw.cmd "-Dmaven.test.skip=true" spring-boot:run "-Dspring-boot.run.profiles=local"
}
finally {
    Pop-Location
}
