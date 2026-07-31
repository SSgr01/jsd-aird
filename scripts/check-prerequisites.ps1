$ErrorActionPreference = "Stop"

function Show-ToolVersion {
    param(
        [string]$Name,
        [string]$Command,
        [string[]]$Arguments
    )

    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        Write-Warning "$Name is not installed or is not available on PATH."
        return $false
    }

    Write-Host "[$Name]"
    & $Command @Arguments 2>&1 | Select-Object -First 3
    return $true
}

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
            Set-Item -Path "Env:$($matches[1])" -Value $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}
if ($env:JSD_AIRD_JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JSD_AIRD_JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME = $env:JSD_AIRD_JAVA_HOME
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}
$psql = if ($env:JSD_AIRD_PSQL_PATH -and (Test-Path -LiteralPath $env:JSD_AIRD_PSQL_PATH)) { $env:JSD_AIRD_PSQL_PATH } else { (Get-Command psql -ErrorAction SilentlyContinue).Source }

$allAvailable = $true
$allAvailable = (Show-ToolVersion "Java" "java" @("-version")) -and $allAvailable
$allAvailable = (Show-ToolVersion "Node.js" "node" @("--version")) -and $allAvailable
$allAvailable = (Show-ToolVersion "npm" "npm" @("--version")) -and $allAvailable
if ($psql) {
    Write-Host "[PostgreSQL client]"
    & $psql --version
}
else {
    Write-Warning "PostgreSQL client is not installed or is not available on PATH."
    $allAvailable = $false
}

$postgresServices = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "postgresql*" }
if (-not $postgresServices) {
    Write-Warning "未发现 PostgreSQL Windows 服务。请安装并启动 PostgreSQL 16、17 或 18。"
    $allAvailable = $false
}
elseif ($postgresServices.Status -contains "Running") {
    Write-Host "[PostgreSQL service] Running"
}
else {
    Write-Warning "发现 PostgreSQL 服务，但尚未运行。请在 Windows 服务管理器中启动它。"
    $allAvailable = $false
}

if ((Test-Path -LiteralPath $envFile) -and $psql) {
    $url = if ($env:JSD_AIRD_DATASOURCE_URL) { $env:JSD_AIRD_DATASOURCE_URL } else { "jdbc:postgresql://localhost:5432/jsd_aird" }
    if ($url -match "^jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\d+))?/(?<database>[^?]+)") {
        $serverHost = $matches.host
        $serverPort = if ($matches.port) { $matches.port } else { "5432" }
        $database = $matches.database
        $username = if ($env:JSD_AIRD_DATASOURCE_USERNAME) { $env:JSD_AIRD_DATASOURCE_USERNAME } else { "jsd_aird" }
        $previousPassword = $env:PGPASSWORD
        try {
            $env:PGPASSWORD = if ($env:JSD_AIRD_DATASOURCE_PASSWORD) { $env:JSD_AIRD_DATASOURCE_PASSWORD } else { "jsd_aird_dev" }
            $serverOutput = & $psql -X -tAc "SHOW server_version_num" -h $serverHost -p $serverPort -U $username -d $database 2>$null
            $serverNumber = if ($serverOutput) { ($serverOutput | Out-String).Trim() } else { "" }
            if ($LASTEXITCODE -ne 0 -or -not $serverNumber) {
                Write-Warning "无法使用业务账号连接 $serverHost`:$serverPort/$database。请先执行 .\\scripts\\db-init.ps1。"
                $allAvailable = $false
            }
            else {
                $major = [math]::Floor([int64]$serverNumber / 10000)
                if ($major -lt 16 -or $major -gt 18) {
                    Write-Warning "PostgreSQL 主版本必须为 16、17 或 18；当前值：$serverNumber。"
                    $allAvailable = $false
                }
                else {
                    Write-Host "[PostgreSQL server] $serverNumber (major $major)"
                    if ($major -ne 18) { Write-Warning "当前不是 PostgreSQL 18；可用于本机开发，但 CI/Compose 基线为 PostgreSQL 18。" }
                    $vectorOutput = & $psql -X -tAc "SELECT extversion FROM pg_extension WHERE extname = 'vector'" -h $serverHost -p $serverPort -U $username -d $database 2>$null
                    $vectorVersion = if ($vectorOutput) { ($vectorOutput | Out-String).Trim() } else { "" }
                    if ($LASTEXITCODE -eq 0 -and $vectorVersion) { Write-Host "[pgvector] $vectorVersion" } else { Write-Warning "未检测到 pgvector。请执行 .\\scripts\\db-init.ps1 或安装匹配的 pgvector 扩展。"; $allAvailable = $false }
                }
            }
        }
        finally { $env:PGPASSWORD = $previousPassword }
    }
}
else {
    Write-Warning "未发现 .env；运行 db-init.ps1 后复制 .env.example 为 .env，再检查数据库版本与 pgvector。"
}

if (Get-Command docker -ErrorAction SilentlyContinue) {
    Write-Host "[Docker] 可选；仅用于 Linux/CI 的 PostgreSQL 18 Compose 基线。"
}

if (-not $allAvailable) { exit 1 }
