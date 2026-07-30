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

$allAvailable = $true
$allAvailable = (Show-ToolVersion "Java" "java" @("-version")) -and $allAvailable
$allAvailable = (Show-ToolVersion "Node.js" "node" @("--version")) -and $allAvailable
$allAvailable = (Show-ToolVersion "npm" "npm" @("--version")) -and $allAvailable
$allAvailable = (Show-ToolVersion "Docker" "docker" @("--version")) -and $allAvailable

if (Get-Command docker -ErrorAction SilentlyContinue) {
    $allAvailable = (Show-ToolVersion "Docker Compose" "docker" @("compose", "version")) -and $allAvailable
}

if (-not $allAvailable) {
    exit 1
}

