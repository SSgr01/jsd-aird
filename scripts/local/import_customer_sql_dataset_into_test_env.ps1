param(
    [Parameter(Mandatory = $true)] [string] $PackageRoot,
    [Parameter(Mandatory = $true)] [string] $StorageRoot,
    [Parameter(Mandatory = $true)] [string] $TemplateSnapshotFile,
    [Parameter(Mandatory = $true)] [string] $TemplateVersionId,
    [Parameter(Mandatory = $true)] [string] $TemplateSnapshotHash,
    [Parameter(Mandatory = $true)] [string] $TemplateSnapshotFileId,
    [string] $OrganizationId = '00000000-0000-0000-0000-000000000001',
    [string] $ActorId = '00000000-0000-0000-0000-000000000099',
    [string] $ObjectBucket = 'jsd-aird',
    [string] $ObjectPrefix = 'ai/customer-sql-extended-20260921',
    [switch] $Apply
)

$ErrorActionPreference = 'Stop'

if (-not $env:JSD_AIRD_DATASOURCE_URL) {
    throw '请先设置 JSD_AIRD_DATASOURCE_URL，例如 jdbc:postgresql://10.70.70.169:5432/jsd_ai'
}
if (-not $env:JSD_AIRD_DATASOURCE_USERNAME) { throw '缺少 JSD_AIRD_DATASOURCE_USERNAME' }
if (-not $env:JSD_AIRD_DATASOURCE_PASSWORD) { throw '缺少 JSD_AIRD_DATASOURCE_PASSWORD' }

$jdbc = $env:JSD_AIRD_DATASOURCE_URL -replace '^jdbc:', ''
$env:JSD_AIRD_TEST_DATABASE_URL = $jdbc
$env:PGPASSWORD = $env:JSD_AIRD_DATASOURCE_PASSWORD
$sqlOut = Join-Path (Resolve-Path $PackageRoot) 'customer-test-env-import.generated.sql'

$arguments = @(
    'scripts/local/import_customer_sql_dataset_into_test_db.py',
    '--package-root', $PackageRoot,
    '--storage-root', $StorageRoot,
    '--template-snapshot-file', $TemplateSnapshotFile,
    '--template-version-id', $TemplateVersionId,
    '--template-snapshot-hash', $TemplateSnapshotHash,
    '--template-snapshot-file-id', $TemplateSnapshotFileId,
    '--organization-id', $OrganizationId,
    '--actor-id', $ActorId,
    '--object-bucket', $ObjectBucket,
    '--object-prefix', $ObjectPrefix,
    '--sql-out', $sqlOut
)
if ($Apply) { $arguments += '--apply' }

python @arguments
