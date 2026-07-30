$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
docker compose --project-directory $Root -f (Join-Path $Root "compose.yaml") up -d --wait

