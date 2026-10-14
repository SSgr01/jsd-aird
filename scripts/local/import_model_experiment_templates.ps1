param(
    [Parameter(Mandatory = $true)] [string] $CatalogFile,
    [Parameter(Mandatory = $true)] [string] $StorageRoot,
    [string] $OrganizationId = '00000000-0000-0000-0000-000000000001',
    [string] $ActorId = '00000000-0000-0000-0000-000000000099',
    [string] $ObjectBucket = 'jsd-aird',
    [switch] $Apply
)

$ErrorActionPreference = 'Stop'
if (-not $env:JSD_AIRD_DATASOURCE_URL) { throw '请先设置 JSD_AIRD_DATASOURCE_URL' }
if (-not $env:JSD_AIRD_DATASOURCE_USERNAME) { throw '缺少 JSD_AIRD_DATASOURCE_USERNAME' }
if (-not $env:JSD_AIRD_DATASOURCE_PASSWORD) { throw '缺少 JSD_AIRD_DATASOURCE_PASSWORD' }

$jdbc = $env:JSD_AIRD_DATASOURCE_URL -replace '^jdbc:', ''
$env:JSD_AIRD_TEST_DATABASE_URL = $jdbc
$env:PGPASSWORD = $env:JSD_AIRD_DATASOURCE_PASSWORD
$arguments = @(
    'scripts/local/import_model_experiment_templates.py',
    '--catalog-file', $CatalogFile,
    '--storage-root', $StorageRoot,
    '--organization-id', $OrganizationId,
    '--actor-id', $ActorId,
    '--object-bucket', $ObjectBucket
)
if ($Apply) { $arguments += '--apply' }
python @arguments
