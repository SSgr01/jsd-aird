[CmdletBinding()]
param([string]$Image = 'jsd-aird-analysis:1')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repoRoot '.runtime/dev-harness'
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$secretFile = Join-Path $runtimeRoot 'sandbox-secret.txt'
if (-not (Test-Path -LiteralPath $secretFile)) {
    [IO.File]::WriteAllText($secretFile, [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
}
# The broker must not inherit business credentials from a parent application shell.
Get-ChildItem Env: | Where-Object { $_.Name -match '^(JSD_AIRD_|APP_AI_|PAPERQA_|OPENAI_|ANTHROPIC_|AWS_|AZURE_)' } |
    ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
$env:JSD_AIRD_SANDBOX_TOKEN = [IO.File]::ReadAllText($secretFile).Trim()
$env:JSD_AIRD_SANDBOX_WORKSPACE_ROOT = Join-Path $runtimeRoot 'sandbox-broker'
$env:JSD_AIRD_SANDBOX_IMAGE = $Image
$env:PYTHONPATH = Join-Path $repoRoot 'jsd-aird-agent/control/src'
$env:PYTHONUTF8 = '1'
& (Join-Path $repoRoot 'jsd-aird-agent/control/.venv/Scripts/python.exe') -m aird_agent.sandbox.broker
exit $LASTEXITCODE
