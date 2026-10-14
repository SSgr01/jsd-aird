$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pidFile = Join-Path $Root ".runtime\demo\pids.json"
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output "没有找到演示进程清单。"; exit 0 }
$pids = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
foreach ($property in $pids.PSObject.Properties) {
    $process = Get-Process -Id ([int]$property.Value) -ErrorAction SilentlyContinue
    if ($process) { Stop-Process -Id $process.Id -Force }
}
Remove-Item -LiteralPath $pidFile -Force
Write-Output "演示服务已停止。"
