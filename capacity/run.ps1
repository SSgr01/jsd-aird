[CmdletBinding()]
param(
    [ValidateSet('Preflight','Light','Full','Cleanup','Faults')][string]$Mode='Preflight',
    [string]$RunId,
    [string]$Revision,
    [string]$FoundationSql,
    [string]$DatabaseEnvFile,
    [string]$JavaHome,
    [string]$MavenCommand,
    [ValidateSet('read','poll','write','hotspot','review','inventory','workspace','publication','mixed')][string[]]$Profiles=@('read','poll','write','hotspot','review','inventory','workspace','publication','mixed'),
    [int]$MaximumLevel=1600
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not $RunId){$RunId='aird-capacity-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)}
if($RunId -notmatch '^aird-capacity-[a-z0-9-]{8,48}$'){throw 'Invalid isolated run ID'}
$runRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot ('.tmp/capacity/'+$RunId)))
$expectedParent=[IO.Path]::GetFullPath((Join-Path $taskRoot '.tmp/capacity'))+[IO.Path]::DirectorySeparatorChar
if(-not $runRoot.StartsWith($expectedParent,[StringComparison]::OrdinalIgnoreCase)){throw 'Run path escaped the workspace'}
New-Item -ItemType Directory -Path (Join-Path $runRoot 'assets'),(Join-Path $runRoot 'results') -Force | Out-Null
$results=Join-Path $runRoot 'results'

function Write-Json([string]$Name,$Value){$Value|ConvertTo-Json -Depth 30|Set-Content -LiteralPath (Join-Path $results $Name) -Encoding UTF8}
function Host-Resources {
    if($env:OS -eq 'Windows_NT'){
        $system=Get-CimInstance Win32_OperatingSystem
        $freeRam=[long]$system.FreePhysicalMemory*1024
        $drive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($runRoot))
        return @{availableMemoryBytes=$freeRam;freeDiskBytes=$drive.AvailableFreeSpace;cpuCount=[Environment]::ProcessorCount}
    }
    $available=((Get-Content /proc/meminfo|Where-Object{$_ -match '^MemAvailable:'}) -split '\s+')[1]
    $drive=[IO.DriveInfo]::new('/')
    return @{availableMemoryBytes=[long]$available*1024;freeDiskBytes=$drive.AvailableFreeSpace;cpuCount=[Environment]::ProcessorCount}
}
$resources=Host-Resources
$dockerReady=$false
try{& docker info --format '{{.ServerVersion}}' *> (Join-Path $results 'docker-version.txt');$dockerReady=($LASTEXITCODE -eq 0)}catch{}
$reasons=[Collections.Generic.List[string]]::new()
if(-not $dockerReady){$reasons.Add('Docker daemon is not ready')}
if($resources.availableMemoryBytes -lt 6GB){$reasons.Add('Available RAM is below 6GB')}
if($resources.freeDiskBytes -lt 20GB){$reasons.Add('Free disk is below 20GB')}
$preflight=@{runId=$RunId;at=(Get-Date).ToUniversalTime().ToString('o');resources=$resources;dockerReady=$dockerReady;fullAllowed=($reasons.Count -eq 0);reasons=@($reasons);status='pending-capacity-acceptance'}
Write-Json 'preflight.json' $preflight
if($Mode -eq 'Preflight'){Write-Host "Preflight: $results";if($reasons.Count){Write-Host ($reasons -join '; ')};return}
if($Mode -eq 'Light'){
    $checkArguments=@{FullBackend=$true;BackendOnly=$true}
    if($DatabaseEnvFile){$checkArguments.Postgres=$true;$checkArguments.DatabaseEnvFile=(Resolve-Path -LiteralPath $DatabaseEnvFile).Path}
    if($JavaHome){$checkArguments.JavaHome=$JavaHome}
    if($MavenCommand){$checkArguments.MavenCommand=$MavenCommand}
    & (Join-Path $taskRoot 'scripts/verify-module-consistency.ps1') @checkArguments
    $code=$LASTEXITCODE
    Write-Json 'light-result.json' @{exitCode=$code;capacityAccepted=$false;note='Unit/isolated database checks do not establish HTTP capacity'}
    exit $code
}
$envFile=Join-Path $runRoot 'run.env'
$snapshot=Join-Path $runRoot 'source'
if($Mode -eq 'Full'){
    if($reasons.Count){throw ('Full run is pending: '+($reasons -join '; '))}
    if(-not $Revision){throw 'Full runs require an explicit fixed Git revision containing the capacity changes'}
    if(Test-Path -LiteralPath $envFile){throw 'This run already exists; select a fresh run ID'}
    $resolved=& git -C $taskRoot rev-parse --verify ($Revision+'^{commit}')
    if($LASTEXITCODE -ne 0){throw 'Revision does not resolve to a commit'}
    $archive=Join-Path $runRoot 'assets/source.zip'
    & git -C $taskRoot archive --format=zip --output=$archive $resolved
    if($LASTEXITCODE -ne 0){throw 'Could not create immutable source snapshot'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive,$snapshot)
    if(-not(Test-Path -LiteralPath (Join-Path $snapshot 'capacity/compose.yml'))){throw 'Selected revision does not contain the capacity harness'}
    if($FoundationSql){Copy-Item -LiteralPath (Resolve-Path -LiteralPath $FoundationSql).Path -Destination (Join-Path $runRoot 'assets/foundation.sql')}
    $settings=@{AIRD_CAPACITY_RUN_ID=$RunId;CAPACITY_RUN_ROOT=$runRoot.Replace('\','/');CAPACITY_API_PORT=18082;
        CAPACITY_PASSWORD=('Cap-'+[Guid]::NewGuid().ToString('N'));CAPACITY_DB_PASSWORD=[Guid]::NewGuid().ToString('N');CAPACITY_STORAGE_PASSWORD=[Guid]::NewGuid().ToString('N')}
    ($settings.GetEnumerator()|ForEach-Object{"$($_.Key)=$($_.Value)"})|Set-Content -LiteralPath $envFile -Encoding ASCII
    Write-Json 'source.json' @{revision=$resolved;archiveSha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash;runId=$RunId}
}elseif(-not(Test-Path -LiteralPath $envFile)){throw 'No owned run.env exists for this run ID'}
$compose=@('compose','--env-file',$envFile,'--project-name',$RunId,'--file',(Join-Path $snapshot 'capacity/compose.yml'))
function Compose {param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    & docker @compose @Arguments
    if($LASTEXITCODE -ne 0){throw ('Owned Compose command failed: '+(($Arguments|Select-Object -First 3) -join ' '))}
}

# Validate labels before any stop, fault, or volume deletion.
function Assert-Owned {
    $containers=& docker @compose ps -aq
    foreach($container in $containers){
        $label=& docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $container
        if($label -ne $RunId){throw 'Container ownership mismatch'}
    }
}
if($Mode -eq 'Cleanup'){
    Assert-Owned
    Compose @('down','--volumes','--remove-orphans')
    Write-Host "Removed only Compose resources owned by $RunId. Reports retained at $results"
    return
}
if($Mode -eq 'Faults'){
    Assert-Owned
    foreach($fault in @('deadlock','queue','connection-pool')){Compose @('run','--rm','tools','fault',$fault)}
    Compose @('pause','minio')
    try{Compose @('run','--rm','tools','fault','storage')}finally{Compose @('unpause','minio')}
    $quota=Join-Path $runRoot 'assets/quota.yml'
    "services:`n  api:`n    environment:`n      APP_CAPACITY_TEMPORARY_BYTES: '536870912'"|Set-Content -LiteralPath $quota -Encoding ASCII
    & docker @compose --file $quota up --detach --wait --force-recreate api
    if($LASTEXITCODE -ne 0){throw 'Could not start the isolated quota fault'}
    try{Compose @('run','--rm','tools','fault','quota')}finally{Compose @('up','--detach','--wait','--force-recreate','api')}
    Write-Host 'Owned contention/storage/quota faults complete. Slow-SQL and held-lock paired load commands are in README.md.'
    return
}

function Sample-Resources {
    $hostState=Host-Resources
    $hostState.at=(Get-Date).ToUniversalTime().ToString('o')
    $hostState|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $results 'host.ndjson')
    if($hostState.availableMemoryBytes -lt 1GB -or $hostState.freeDiskBytes -lt 10GB){throw 'Host safety floor reached; stop increasing load'}
    $ids=& docker @compose ps -q
    if($ids){foreach($line in (& docker stats --no-stream --format '{{json .}}' @ids)){
        $sample=$line|ConvertFrom-Json;$sample|Add-Member -NotePropertyName sampledAt -NotePropertyValue $hostState.at
        $sample|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $results 'containers.ndjson')
    }}
}
try {
    Compose @('up','--build','--detach','postgres','minio','stub','python','bootstrap')
    Compose @('wait','bootstrap')
    Compose @('run','--rm','tools','seed')
    Compose @('up','--build','--detach','--wait','api')
    Compose @('run','--rm','tools','grant')
    Compose @('run','--rm','tools','metrics','baseline')
    Assert-Owned
    # Only resource limits/image identities are retained; inspect environment/credentials are never exported.
    $ids=& docker @compose ps -q
    foreach($id in $ids){& docker inspect --format '{{json .Id}} {{json .Image}} {{json .HostConfig.Memory}} {{json .HostConfig.NanoCpus}}' $id|Add-Content -LiteralPath (Join-Path $results 'actual-limits.txt')}
    $stages=[Collections.Generic.List[object]]::new()
    function Stage([string]$Profile,[string]$Model,[int]$Level,[string]$Duration,[string]$Name){
        $summary='/results/'+$Name+'.json'
        $stageArguments=@('run','--rm','--name',($RunId+'-generator'),'k6','run','--out',('json=/results/'+$Name+'.ndjson'),
            '-e',('PROFILE='+$Profile),'-e',('MODEL='+$Model),'-e',('LEVEL='+$Level),'-e',('STAGE_ID='+$Name),'-e',('DURATION='+$Duration),'-e',('SUMMARY_FILE='+$summary),'/scripts/workload.js')
        # Start without a visible helper window; sample the host while Docker executes the load.
        $startedAt=(Get-Date).ToUniversalTime().ToString('o')
        $payload=@{compose=$compose;arguments=$stageArguments;log=(Join-Path $results ($Name+'.log'))}|ConvertTo-Json -Compress
        $job=Start-Job -ScriptBlock {param($payload)$inputData=$payload|ConvertFrom-Json;$command=@($inputData.compose);$arguments=@($inputData.arguments);& docker @command @arguments *> $inputData.log;exit $LASTEXITCODE} -ArgumentList $payload
        try{
            while($job.State -eq 'Running'){
                Sample-Resources
                Wait-Job $job -Timeout 10|Out-Null
            }
            Receive-Job $job *> (Join-Path $results ($Name+'.supervisor.log'))
        }finally{if($job.State -eq 'Running'){& docker stop --time 10 ($RunId+'-generator')|Out-Null;Stop-Job $job};Remove-Job $job -Force}
        $path=Join-Path $results ($Name+'.json')
        if(-not(Test-Path -LiteralPath $path)){throw "Load stage did not produce a summary: $Name"}
        $value=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
        $failed=($value.metrics.business_attempted.values.count -le 0) -or @($value.metrics.PSObject.Properties|Where-Object{$_.Value.thresholds -and @($_.Value.thresholds.PSObject.Properties|Where-Object{-not $_.Value.ok}).Count -gt 0}).Count -gt 0
        $stages.Add(@{name=$Name;profile=$Profile;model=$Model;level=$Level;duration=$Duration;startedAt=$startedAt;finishedAt=(Get-Date).ToUniversalTime().ToString('o');thresholdPass=(-not $failed)})
        Write-Json 'stages.json' $stages.ToArray()
        Compose @('run','--rm','tools','compress',$Name)
        return (-not $failed)
    }
    foreach($loadProfile in $Profiles){foreach($model in @('closed','open')){
        if($loadProfile -eq 'poll' -and $model -eq 'open'){continue}
        $levels=if($model -eq 'closed'){@(20,50,100,200)}else{@(10,25,50,100,200)}
        $next=400;while($next -le $MaximumLevel){$levels+=,$next;$next*=2}
        $consecutive=0;$stable=0
        foreach($level in $levels){
            if($model -eq 'closed' -and $level -gt 400){break} # generator/fixture bound; report as unmeasured
            $pass=Stage $loadProfile $model $level '5m' ("$loadProfile-$model-$level")
            if($pass){$stable=$level;$consecutive=0}else{$consecutive++}
            if($consecutive -ge 2){break}
        }
        if($stable -gt 0){
            $null=Stage $loadProfile $model $stable '5m' ("$loadProfile-$model-repeat")
            $null=Stage $loadProfile $model $stable '30m' ("$loadProfile-$model-soak")
            if($model -eq 'open' -or $stable -le 200){$null=Stage $loadProfile $model ($stable*2) '1m' ("$loadProfile-$model-burst")}
            else{$stages.Add(@{name="$loadProfile-$model-burst";profile=$loadProfile;model=$model;level=$stable*2;thresholdPass=$false;unmeasured='Generator has only 400 VU slots'})}
        }
    }}
    foreach($module in @('projects','experiments','data')){foreach($rows in @(10000,100000,1000000)){
        Sample-Resources
        Compose @('run','--rm','tools','tier',$module,[string]$rows)
        $null=Stage 'mixed' 'open' 10 '5m' ("$module-$rows-tier")
    }}
    Compose @('run','--rm','tools','boundaries')
    Start-Sleep -Seconds 60
    Compose @('run','--rm','tools','metrics','recovery')
    $python=Join-Path $taskRoot 'jsd-aird-ai/.venv/Scripts/python.exe'
    if(Test-Path -LiteralPath $python){& $python (Join-Path $PSScriptRoot 'report.py') $results}
    Write-Host "Capacity artifacts: $results. Review generator saturation, consistency and recovery before acceptance."
}catch{
    Write-Json 'interrupted.json' @{runId=$RunId;capacityAccepted=$false;reason=$_.Exception.Message;at=(Get-Date).ToUniversalTime().ToString('o')}
    # Stop only this run's generator. Keep isolated services/reports for diagnosis.
    if($dockerReady){Assert-Owned;& docker stop --time 10 ($RunId+'-generator') *> $null}
    throw
}
