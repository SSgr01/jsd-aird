[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('api', 'web', 'gateway', 'control', 'migrate-control')][string]$Service,
    [Parameter(Mandatory)][string]$EnvFile
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repoRoot '.runtime/dev-harness'
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw 'Environment file not found.' }
Get-Content -LiteralPath $EnvFile | ForEach-Object {
    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        Set-Item -Path ('Env:' + $matches[1]) -Value $matches[2].Trim().Trim('"').Trim("'")
    }
}

# Local service credentials are generated once, never printed or copied to a model workspace.
$secretFile = Join-Path $runtimeRoot 'service-secrets.json'
$secretMutex = [Threading.Mutex]::new($false, 'Local\JsdAirdHarnessDevSecrets')
$secretLockHeld = $false
try {
    $secretLockHeld = $secretMutex.WaitOne([TimeSpan]::FromSeconds(10))
    if (-not $secretLockHeld) { throw 'Service secret lock timed out.' }
    if (-not (Test-Path -LiteralPath $secretFile)) {
        $secretValues = @{}
        foreach ($name in @('delegation', 'runtime')) {
            $bytes = New-Object byte[] 32
            $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
            $secretValues[$name] = [Convert]::ToBase64String($bytes)
        }
        [IO.File]::WriteAllText($secretFile, ($secretValues | ConvertTo-Json))
    }
    $secrets = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
} finally {
    if ($secretLockHeld) { $secretMutex.ReleaseMutex() }
    $secretMutex.Dispose()
}
$env:JSD_AIRD_HARNESS_SHARED_SECRET = $secrets.delegation
$env:JSD_AIRD_HARNESS_RUNTIME_SECRET = $secrets.runtime
$env:JSD_AIRD_HARNESS_DATABASE_URL = 'postgresql://agents_dev@127.0.0.1:55449/jsd_aird_agents_dev'
$env:JSD_AIRD_HARNESS_JAVA_URL = 'http://127.0.0.1:8180'
$env:JSD_AIRD_HARNESS_CONTROL_URL = 'http://127.0.0.1:8093'
$env:JSD_AIRD_HARNESS_RUNTIME_URL = 'http://127.0.0.1:8092'
$env:JSD_AIRD_HARNESS_STORAGE_ROOT = Join-Path $runtimeRoot 'control'
if (-not (Test-Path Env:JSD_AIRD_HARNESS_SANDBOX_ENABLED)) { $env:JSD_AIRD_HARNESS_SANDBOX_ENABLED = 'false' }
if ($env:JSD_AIRD_HARNESS_SANDBOX_ENABLED -eq 'true') {
    $sandboxSecretFile = Join-Path $runtimeRoot 'sandbox-secret.txt'
    if (-not (Test-Path -LiteralPath $sandboxSecretFile)) { throw 'Start scripts/dev-sandbox.ps1 before enabling the sandbox.' }
    $env:JSD_AIRD_HARNESS_SANDBOX_SECRET = [IO.File]::ReadAllText($sandboxSecretFile).Trim()
    $env:JSD_AIRD_HARNESS_SANDBOX_URL = 'http://127.0.0.1:8094'
}
$env:JSD_AIRD_HARNESS_MAX_STEPS = '64'
$env:JSD_AIRD_DSH_ROOT = Join-Path $runtimeRoot 'dsh'
$env:JSD_AIRD_DSH_EXECUTION_ROOT = Join-Path $runtimeRoot 'execution'
$env:JSD_AIRD_PUBLIC_URL = 'http://127.0.0.1:5184'
$env:APP_AI_HARNESS_SHARED_SECRET = $secrets.delegation
$env:APP_AI_HARNESS_CONTROL_URL = 'http://127.0.0.1:8093'
$env:APP_AI_HARNESS_ENABLED = 'true'
if (-not (Test-Path Env:APP_AI_HARNESS_QA_ENABLED)) { $env:APP_AI_HARNESS_QA_ENABLED = 'true' }
$env:PYTHONUTF8 = '1'

switch ($Service) {
    'gateway' {
        $taskNode = $env:JSD_AIRD_DSH_NODE
        if (-not $taskNode) { $taskNode = Join-Path $repoRoot '.runtime/deepseek-migration/toolchain/node_modules/node-win-x64/bin/node.exe' }
        if (-not (Test-Path -LiteralPath $taskNode -PathType Leaf)) { throw 'Set JSD_AIRD_DSH_NODE to the dedicated Node 24 executable.' }
        $taskVersion = & $taskNode -p 'process.versions.node'
        if ($taskVersion -notmatch '^24\.' -or [version]$taskVersion -lt [version]'24.9.0') { throw 'DeepSeek requires its dedicated Node 24 runtime.' }
        $env:JSD_AIRD_DSH_NODE = $taskNode
        Push-Location (Join-Path $repoRoot 'jsd-aird-agent/deepseek')
        try { & $taskNode src/server.mjs } finally { Pop-Location }
    }
    { $_ -in 'control', 'migrate-control' } {
        $agentsRoot = Join-Path $repoRoot 'jsd-aird-agent/control'
        $env:PYTHONPATH = Join-Path $agentsRoot 'src'
        Push-Location $agentsRoot
        try {
            $pythonCommand = Join-Path $agentsRoot '.venv/Scripts/python.exe'
            if ($Service -eq 'migrate-control') {
                & $pythonCommand -m aird_agent.server --migrate
            } else { & $pythonCommand -m aird_agent.server }
        } finally { Pop-Location }
    }
    'web' {
        $env:VITE_DEV_API_TARGET = 'http://127.0.0.1:8180'
        $env:VITE_DEV_AGENT_TARGET = 'http://127.0.0.1:8092'
        Push-Location (Join-Path $repoRoot 'jsd-aird-web')
        try { & node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5184 --strictPort }
        finally { Pop-Location }
    }
    'api' {
        $env:SPRING_FLYWAY_ENABLED = 'false'
        if ($env:JSD_AIRD_JAVA_HOME) {
            $env:JAVA_HOME = $env:JSD_AIRD_JAVA_HOME
            $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
        }
        $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
        $env:SERVER_PORT = '8180'
        $env:SERVER_ADDRESS = '127.0.0.1'
        $env:JSD_AIRD_MANAGEMENT_PORT = '9191'
        $env:JSD_AIRD_ALLOWED_ORIGINS = 'http://localhost:5184,http://127.0.0.1:5184'
        $env:JSD_AIRD_SESSION_COOKIE_NAME = 'JSD_AIRD_HARNESS_SESSION'
        $env:JSD_AIRD_WORKER_ENABLED = 'false'
        $env:JSD_AIRD_IAM_BOOTSTRAP_ENABLED = 'false'
        $env:JSD_AIRD_RENDER_FRONTEND_BASE_URL = 'http://127.0.0.1:5184'
        Push-Location (Join-Path $repoRoot 'jsd-aird-api')
        try { & ./mvnw.cmd '-Dmaven.test.skip=true' 'spring-boot:run' '-Dspring-boot.run.profiles=local' }
        finally { Pop-Location }
    }
}
if ($LASTEXITCODE) { exit $LASTEXITCODE }
