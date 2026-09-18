$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$checks = @(
    @{Name='AI live'; Url='http://127.0.0.1:8090/internal/v2/health/live'},
    @{Name='AI ready'; Url='http://127.0.0.1:8090/internal/v2/health/ready'},
    @{Name='API'; Url='http://127.0.0.1:8080/actuator/health'},
    @{Name='Web'; Url='http://127.0.0.1:5173'}
)
foreach ($check in $checks) {
    try { $response = Invoke-WebRequest -Uri $check.Url -UseBasicParsing -TimeoutSec 10; Write-Output "$($check.Name): $($response.StatusCode)" }
    catch { throw "$($check.Name) 不可用：$($_.Exception.Message)" }
}
Write-Output "演示服务探活通过。下一步打开 http://127.0.0.1:5173/login。"
