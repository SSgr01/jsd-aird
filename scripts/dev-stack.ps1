$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$RuntimeDirectory = Join-Path $Root ".runtime\dev-stack"
$ApiScript = Join-Path $PSScriptRoot "dev-api.ps1"
$WorkerScript = Join-Path $PSScriptRoot "dev-worker.ps1"
$PowerShellExecutable = (Get-Process -Id $PID).Path

New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
$ApiOutput = Join-Path $RuntimeDirectory "api.out.log"
$ApiError = Join-Path $RuntimeDirectory "api.err.log"
$WorkerOutput = Join-Path $RuntimeDirectory "worker.out.log"
$WorkerError = Join-Path $RuntimeDirectory "worker.err.log"
$Children = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

function Start-ServiceProcess([string]$Name, [string]$ScriptPath, [string]$OutputPath, [string]$ErrorPath) {
    Write-Host "Starting $Name..."
    $arguments = @("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$ScriptPath`"")
    return Start-Process -FilePath $PowerShellExecutable -ArgumentList $arguments -WorkingDirectory $Root `
        -RedirectStandardOutput $OutputPath -RedirectStandardError $ErrorPath -WindowStyle Hidden -PassThru
}

function Stop-ServiceProcessTree([System.Diagnostics.Process]$Process) {
    if ($null -eq $Process) { return }
    $Process.Refresh()
    if (-not $Process.HasExited) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $Process.Id /T /F | Out-Null
        $Process.WaitForExit(10000)
    }
}

function Assert-ProcessIsRunning([System.Diagnostics.Process]$Process, [string]$Name) {
    $Process.Refresh()
    if ($Process.HasExited) {
        $errorPath = if ($Name -eq "API") { $ApiError } else { $WorkerError }
        $outputPath = if ($Name -eq "API") { $ApiOutput } else { $WorkerOutput }
        $details = @()
        if (Test-Path -LiteralPath $outputPath) { $details += Get-Content -LiteralPath $outputPath -Tail 30 }
        if (Test-Path -LiteralPath $errorPath) { $details += Get-Content -LiteralPath $errorPath -Tail 30 }
        throw "$Name process exited unexpectedly.`n$($details -join "`n")"
    }
}

try {
    $existingListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($existingListener) {
        throw "Port 8080 is already in use by PID $($existingListener.OwningProcess). Stop the existing local API before starting this stack."
    }

    $api = Start-ServiceProcess "API" $ApiScript $ApiOutput $ApiError
    $Children.Add($api)

    $apiDeadline = (Get-Date).AddMinutes(3)
    $apiReady = $false
    while ((Get-Date) -lt $apiDeadline) {
        Assert-ProcessIsRunning $api "API"
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { $apiReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $apiReady) { throw "API did not become healthy within 3 minutes; see $ApiOutput and $ApiError" }

    $worker = Start-ServiceProcess "Worker" $WorkerScript $WorkerOutput $WorkerError
    $Children.Add($worker)
    $workerDeadline = (Get-Date).AddMinutes(3)
    $workerReady = $false
    while ((Get-Date) -lt $workerDeadline) {
        Assert-ProcessIsRunning $worker "Worker"
        $workerReady = (Test-Path -LiteralPath $WorkerOutput) -and
            ((Get-Content -LiteralPath $WorkerOutput -Raw -ErrorAction SilentlyContinue) -match "worker_online")
        if ($workerReady) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $workerReady) { throw "Worker did not announce readiness within 3 minutes; see $WorkerOutput and $WorkerError" }

    Write-Host "Local API and Worker are ready. API: http://localhost:8080; logs: $RuntimeDirectory"
    Write-Host "Press Ctrl+C to stop both processes."
    while ($true) {
        Assert-ProcessIsRunning $api "API"
        Assert-ProcessIsRunning $worker "Worker"
        Start-Sleep -Seconds 2
    }
}
finally {
    for ($index = $Children.Count - 1; $index -ge 0; $index--) {
        Stop-ServiceProcessTree $Children[$index]
    }
}
