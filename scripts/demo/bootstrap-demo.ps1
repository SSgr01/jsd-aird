[CmdletBinding()]
param(
    [switch]$Reset,
    [switch]$InstallDependencies,
    [switch]$StartServices,
    [switch]$SeedModels,
    [switch]$ImportClientTemplates,
    [string]$EnvFile = ".env.demo"
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envPath = Join-Path $Root $EnvFile
$runtime = Join-Path $Root ".runtime\demo"
$rootEnv = Join-Path $Root ".env"
if (Test-Path -LiteralPath $rootEnv) { throw "为避免覆盖当前业务配置，演示脚本要求仓库根目录不存在 .env。请使用独立演示工作副本。" }

function Read-DemoEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "找不到演示环境文件 $Path。请从 .env.demo.example 复制后修改本机密码。" }
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
            Set-Item -Path "Env:$($matches[1])" -Value $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}
function Assert-DemoDatabase {
    if ($env:JSD_AIRD_DATASOURCE_URL -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1)(:\d+)?/(?<db>[^?]+)') { throw "演示库只能连接 localhost/127.0.0.1。" }
    if ($matches.db -notmatch '(^|_)demo$') { throw "演示库名称必须以 _demo 结尾，当前为 $($matches.db)。" }
    if ($env:JSD_AIRD_STORAGE_PROVIDER -ne 'local') { throw "演示环境必须使用 JSD_AIRD_STORAGE_PROVIDER=local。" }
}
function Wait-Endpoint([string]$Url, [int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try { Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 | Out-Null; return }
        catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    throw "服务未在规定时间内就绪：$Url"
}
Read-DemoEnv $envPath
Assert-DemoDatabase
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
if ($Reset) {
    Write-Warning "-Reset 只允许清理已校验的本机演示库。"
    & (Join-Path $Root "scripts\db-init.ps1") -Database $matches.db -AppUser $env:JSD_AIRD_DATASOURCE_USERNAME -AppPassword $env:JSD_AIRD_DATASOURCE_PASSWORD
}
if ($InstallDependencies) {
    Push-Location (Join-Path $Root "jsd-aird-web")
    try { & npm.cmd ci } finally { Pop-Location }
    Push-Location (Join-Path $Root "jsd-aird-api")
    try { & .\mvnw.cmd -Dmaven.test.skip=true compile } finally { Pop-Location }
}
if ($StartServices) {
    $procs = @(
        @{Name='ai'; Command=(Join-Path $Root 'scripts\dev-ai.ps1')},
        @{Name='api'; Command=(Join-Path $Root 'scripts\dev-api.ps1')},
        @{Name='worker'; Command=(Join-Path $Root 'scripts\dev-worker.ps1')},
        @{Name='web'; Command=(Join-Path $Root 'scripts\dev-web.ps1')}
    )
    $pids = @{}
    foreach ($entry in $procs) {
        $stdout = Join-Path $runtime "$($entry.Name).out.log"
        $stderr = Join-Path $runtime "$($entry.Name).err.log"
        $p = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$entry.Command) -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        $pids[$entry.Name] = $p.Id
    }
    $pids | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'pids.json') -Encoding UTF8
    Write-Output "服务已后台启动，日志位于 $runtime。"
    Wait-Endpoint "http://127.0.0.1:$($env:JSD_AIRD_AI_PORT)/internal/v2/health/ready"
    Wait-Endpoint "http://127.0.0.1:$($env:SERVER_PORT)/actuator/health"
}
$python = Join-Path $Root "jsd-aird-ai\.venv\Scripts\python.exe"
if (Test-Path -LiteralPath $python) {
    & $python (Join-Path $Root "scripts\demo\prepare_demo.py") --root $Root --mode generate
} else { Write-Output "依赖尚未安装；安装完成后再次运行本脚本生成演示数据。" }
if ($SeedModels) {
    if (-not (Test-Path -LiteralPath $python)) { throw "请先安装Python依赖后再生成模型。" }
    $jdbc = $env:JSD_AIRD_DATASOURCE_URL -replace '^jdbc:', ''
    if ($jdbc -notmatch '^postgresql://') { throw "演示数据库必须使用PostgreSQL连接。" }
    $authority = $jdbc.Substring('postgresql://'.Length)
    $dbUser = [uri]::EscapeDataString($env:JSD_AIRD_DATASOURCE_USERNAME)
    $dbPassword = [uri]::EscapeDataString($env:JSD_AIRD_DATASOURCE_PASSWORD)
    $dbUrl = "postgresql://$dbUser`:$dbPassword@$authority"
    $baseUrl = "http://127.0.0.1:$($env:JSD_AIRD_AI_PORT)"
    $psqlCommand = if ($env:JSD_AIRD_PSQL_PATH) { $env:JSD_AIRD_PSQL_PATH } else { 'psql' }
    Push-Location $Root
    try {
        & $python scripts/testdata/r07_synthetic_15y_train_and_predict.py --database-url $dbUrl --base-url $baseUrl --psql $psqlCommand --output-dir (Join-Path $runtime 'r07-synthetic-15y')
        & $python scripts/testdata/r07_register_15y_candidates.py --database-url $dbUrl --psql $psqlCommand --output-sql (Join-Path $runtime 'r07-synthetic-15y/register-candidates.sql')
        $psql = $psqlCommand
        $sql = "UPDATE ai.model_version SET status='ACTIVE', revision=revision+1, updated_at=now() WHERE organization_id=(SELECT organization_id FROM iam.app_user WHERE username='$($env:JSD_AIRD_IAM_ADMIN_USERNAME)' LIMIT 1) AND data_nature='SYNTHETIC';"
        & $psql $dbUrl -v ON_ERROR_STOP=1 -c $sql
    } finally { Pop-Location }
}
if ($ImportClientTemplates) {
    if (-not (Test-Path -LiteralPath $python)) { throw "请先安装Python依赖后再导入演示数据。" }
    if (-not $StartServices) { Write-Warning "ImportClientTemplates 需要已经运行的API和Worker；请确认演示服务已启动。" }
    $env:JSD_AIRD_DEMO_API_BASE_URL = "http://127.0.0.1:$($env:SERVER_PORT)"
    Push-Location $Root
    try {
        & $python scripts/testdata/r10_bulk_client_template_import.py
        if ($LASTEXITCODE -ne 0) { throw "客户模板批量导入失败。请确认模板中心已有已发布且允许生成实验草稿的模板。" }
    } finally { Pop-Location }
}
