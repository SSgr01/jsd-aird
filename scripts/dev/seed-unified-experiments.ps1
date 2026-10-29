param([int]$Count = 80, [switch]$ReplaceGenerated)
$ErrorActionPreference = 'Stop'
if (-not $env:SEED_DB_URL -or -not $env:SEED_DB_USER -or -not $env:SEED_DB_PASSWORD) {
    throw 'Set SEED_DB_URL, SEED_DB_USER and SEED_DB_PASSWORD for the non-production database.'
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location (Join-Path $root 'jsd-aird-api')
try {
    & .\mvnw.cmd -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/seed-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Maven failed' }
    $classpath = "$(Join-Path $root 'jsd-aird-api/target/classes');$(Get-Content target/seed-classpath.txt -Raw)".Trim()
    $seedArgs = @((Join-Path $root 'scripts/dev/SeedUnifiedExperiments.java'), $env:SEED_DB_URL, $env:SEED_DB_USER, "$Count")
    if ($ReplaceGenerated) { $seedArgs += '--replace-generated' }
    & java -cp $classpath @seedArgs
    if ($LASTEXITCODE -ne 0) { throw 'Data generation failed' }
} finally { Pop-Location }
