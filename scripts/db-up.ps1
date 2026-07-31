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

$psql = if ($env:JSD_AIRD_PSQL_PATH -and (Test-Path -LiteralPath $env:JSD_AIRD_PSQL_PATH)) { $env:JSD_AIRD_PSQL_PATH } else { (Get-Command psql -ErrorAction SilentlyContinue).Source }
if (-not $psql) {
    throw "找不到 psql。请将 PostgreSQL bin 目录加入 PATH，或在 .env 中设置 JSD_AIRD_PSQL_PATH。"
}

$url = if ($env:JSD_AIRD_DATASOURCE_URL) { $env:JSD_AIRD_DATASOURCE_URL } else { "jdbc:postgresql://localhost:5432/jsd_aird" }
if ($url -notmatch "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/(?<database>[^?]+)") {
    throw "JSD_AIRD_DATASOURCE_URL 格式无效：$url"
}

$hostName = $matches.host
$port = if ($matches.port) { $matches.port } else { "5432" }
$database = $matches.database
$username = if ($env:JSD_AIRD_DATASOURCE_USERNAME) { $env:JSD_AIRD_DATASOURCE_USERNAME } else { "jsd_aird" }
$password = if ($env:JSD_AIRD_DATASOURCE_PASSWORD) { $env:JSD_AIRD_DATASOURCE_PASSWORD } else { "jsd_aird_dev" }

$previousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $password
    $connection = (& $psql -X -v ON_ERROR_STOP=1 -tAc "SELECT current_database() || ':' || current_user" -h $hostName -p $port -U $username -d $database).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "无法连接本机数据库。请确认 PostgreSQL 服务已启动并先执行 .\\scripts\\db-init.ps1。"
    }
    $vectorVersion = (& $psql -X -v ON_ERROR_STOP=1 -tAc "SELECT extversion FROM pg_extension WHERE extname = 'vector'" -h $hostName -p $port -U $username -d $database).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $vectorVersion) {
        throw "数据库未启用 pgvector。请安装与 PostgreSQL 主版本匹配的 pgvector 扩展，然后执行 .\\scripts\\db-init.ps1。"
    }
    Write-Host "本机 PostgreSQL 与 pgvector $vectorVersion 已就绪（$connection）；Windows 开发环境不需要 Docker。"
}
finally {
    $env:PGPASSWORD = $previousPassword
}
