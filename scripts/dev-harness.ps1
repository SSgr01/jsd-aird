[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('api', 'web', 'node', 'control', 'migrate-control')][string]$Service,
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
$env:JSD_AIRD_HARNESS_SANDBOX_ENABLED = 'false'
$env:JSD_AIRD_HARNESS_MAX_STEPS = '64'
$env:JSD_AIRD_HARNESS_PAPERQA_MODEL = 'openai/' + $env:JSD_AIRD_MODEL_NAME
$env:JSD_AIRD_HARNESS_PAPERQA_BASE_URL = $env:JSD_AIRD_MODEL_BASE_URL
$env:JSD_AIRD_HARNESS_PAPERQA_API_KEY = $env:JSD_AIRD_MODEL_API_KEY
$env:JSD_AIRD_PI_SESSION_ROOT = Join-Path $runtimeRoot 'pi-sessions'
$env:APP_AI_HARNESS_SHARED_SECRET = $secrets.delegation
$env:APP_AI_HARNESS_CONTROL_URL = 'http://127.0.0.1:8093'
$env:APP_AI_HARNESS_ENABLED = 'true'
$env:PYTHONUTF8 = '1'

switch ($Service) {
    'node' {
        Push-Location (Join-Path $repoRoot 'jsd-aird-agent-runtime')
        try { & node src/server.mjs } finally { Pop-Location }
    }
    { $_ -in 'control', 'migrate-control' } {
        $agentsRoot = Join-Path $repoRoot 'jsd-aird-agents'
        $env:PYTHONPATH = Join-Path $agentsRoot 'src'
        # Reuse the locally verified optional PaperQA environment when available.
        $sourceRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $repoRoot))
        $researchPackages = Join-Path $sourceRoot '.runtime/paperqa/venv/Lib/site-packages'
        if (Test-Path -LiteralPath (Join-Path $researchPackages 'paperqa')) {
            $env:PYTHONPATH += ';' + $researchPackages
        }
        Push-Location $agentsRoot
        try {
            $pythonCommand = Join-Path $agentsRoot '.venv/Scripts/python.exe'
            if ($Service -eq 'migrate-control') {
                & $pythonCommand -m jsd_aird_agents.harness_v1 --migrate
            } else { & $pythonCommand -m jsd_aird_agents.harness_v1 }
        } finally { Pop-Location }
    }
    'web' {
        $env:VITE_DEV_API_TARGET = 'http://127.0.0.1:8180'
        Push-Location (Join-Path $repoRoot 'jsd-aird-web')
        try { & node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5184 --strictPort }
        finally { Pop-Location }
    }
    'api' {
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
