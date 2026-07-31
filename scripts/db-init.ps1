[CmdletBinding()]
param(
    [string]$HostName = "localhost",
    [int]$Port = 5432,
    [string]$AdminUser,
    [string]$Database = "jsd_aird",
    [string]$AppUser = "jsd_aird",
    [string]$AppPassword = "jsd_aird_dev"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $Root ".env"
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
            Set-Item -Path "Env:$($matches[1])" -Value $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}

if ($env:JSD_AIRD_DATASOURCE_URL -match "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/(?<database>[^?]+)") {
    $HostName = $matches.host
    $Port = if ($matches.port) { [int]$matches.port } else { 5432 }
    $Database = $matches.database
}
if ($env:JSD_AIRD_DATASOURCE_USERNAME) { $AppUser = $env:JSD_AIRD_DATASOURCE_USERNAME }
if ($env:JSD_AIRD_DATASOURCE_PASSWORD) { $AppPassword = $env:JSD_AIRD_DATASOURCE_PASSWORD }

$psql = if ($env:JSD_AIRD_PSQL_PATH -and (Test-Path -LiteralPath $env:JSD_AIRD_PSQL_PATH)) { $env:JSD_AIRD_PSQL_PATH } else { (Get-Command psql -ErrorAction SilentlyContinue).Source }
if (-not $psql) { throw "找不到 psql。请将 PostgreSQL bin 目录加入 PATH，或在 .env 中设置 JSD_AIRD_PSQL_PATH。" }
if (-not $AdminUser) { $AdminUser = Read-Host "PostgreSQL 管理员用户名" }
if (-not $AdminUser) { throw "必须提供 PostgreSQL 管理员用户名。" }

$securePassword = Read-Host "PostgreSQL 管理员密码（不会写入文件）" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$adminPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
$previousPassword = $env:PGPASSWORD

function Quote-Identifier([string]$Value) { '"' + $Value.Replace('"', '""') + '"' }
function Quote-Literal([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }

try {
    $env:PGPASSWORD = $adminPassword
    $serverNumber = (& $psql -X -tAc "SHOW server_version_num" -h $HostName -p $Port -U $AdminUser -d postgres).Trim()
    if ($LASTEXITCODE -ne 0) { throw "无法连接 PostgreSQL 服务。请确认服务已启动、管理员账号正确且允许本机连接。" }
    $major = [math]::Floor([int64]$serverNumber / 10000)
    if ($major -lt 16 -or $major -gt 18) { throw "需要 PostgreSQL 16、17 或 18；当前服务器版本号：$serverNumber。" }
    if ($major -ne 18) { Write-Warning "当前使用 PostgreSQL $major；可用于本机开发，CI/Compose 基线为 PostgreSQL 18。" }

    $roleExists = (& $psql -X -tAc "SELECT 1 FROM pg_roles WHERE rolname = $(Quote-Literal $AppUser)" -h $HostName -p $Port -U $AdminUser -d postgres).Trim()
    if (-not $roleExists) {
        & $psql -X -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $AdminUser -d postgres -c "CREATE ROLE $(Quote-Identifier $AppUser) LOGIN PASSWORD $(Quote-Literal $AppPassword);"
        if ($LASTEXITCODE -ne 0) { throw "无法创建业务角色 $AppUser。请检查管理员权限。" }
    }

    $databaseExists = (& $psql -X -tAc "SELECT 1 FROM pg_database WHERE datname = $(Quote-Literal $Database)" -h $HostName -p $Port -U $AdminUser -d postgres).Trim()
    if (-not $databaseExists) {
        & $psql -X -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $AdminUser -d postgres -c "CREATE DATABASE $(Quote-Identifier $Database) OWNER $(Quote-Identifier $AppUser);"
        if ($LASTEXITCODE -ne 0) { throw "无法创建业务数据库 $Database。请检查管理员权限。" }
    }

    & $psql -X -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $AdminUser -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE $(Quote-Identifier $Database) TO $(Quote-Identifier $AppUser);"
    if ($LASTEXITCODE -ne 0) { throw "无法授予业务数据库权限。请检查管理员权限。" }
    & $psql -X -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $AdminUser -d $Database -c "CREATE EXTENSION IF NOT EXISTS vector;"
    if ($LASTEXITCODE -ne 0) { throw "无法创建 vector 扩展。请安装与 PostgreSQL $major 匹配的 pgvector 扩展。" }

    $env:PGPASSWORD = $AppPassword
    $vectorVersion = (& $psql -X -v ON_ERROR_STOP=1 -tAc "SELECT extversion FROM pg_extension WHERE extname = 'vector'" -h $HostName -p $Port -U $AppUser -d $Database).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $vectorVersion) { throw "业务账号无法连接已初始化的数据库，或 pgvector 未启用。" }
    Write-Host "本机数据库初始化完成：$HostName`:$Port/$Database（pgvector $vectorVersion）。"
}
finally {
    $env:PGPASSWORD = $previousPassword
    if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
}
