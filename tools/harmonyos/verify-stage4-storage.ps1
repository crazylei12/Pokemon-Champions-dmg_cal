[CmdletBinding()]
param(
    [string]$Target = '127.0.0.1:5555'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$toolchainRoot = [System.IO.Path]::GetFullPath(($config.toolchain.root -replace '/', '\'))
$hdc = Join-Path $toolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
$standardHap = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.standard.artifactName)-debug-unsigned.hap"
$replayHap = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.replay.artifactName)-debug-unsigned.hap"
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence'
$standardLayoutPath = Join-Path $evidenceDirectory 'pc-stage4-standard-seed.json'
$replayLayoutPath = Join-Path $evidenceDirectory 'pc-stage4-replay-verify.json'
$logPath = Join-Path $evidenceDirectory 'pc-stage4-storage.log'

if (-not (Test-Path -LiteralPath $hdc)) { throw "HDC not found: $hdc" }
foreach ($hap in @($standardHap, $replayHap)) {
    if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
}
if ((& $hdc list targets) -notcontains $Target) { throw "HarmonyOS target is not connected: $Target" }
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

function Invoke-TargetHdc {
    param([string[]]$Arguments)
    $output = & $hdc -t $Target @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "HDC command failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
    return $output
}

function Wait-StageMarker {
    param([string]$PassMarker)
    $logs = ''
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Start-Sleep -Seconds 2
        $logs = (Invoke-TargetHdc -Arguments @('shell', 'hilog', '-T', 'PCStage4', '-x') | Out-String)
        if ($logs -match 'STAGE4_.*_FAIL') { throw "Stage 4 runtime reported a failure:`n$logs" }
        if ($logs -match $PassMarker) { return $logs }
    }
    throw "Stage 4 marker $PassMarker did not appear within 30 seconds:`n$logs"
}

function Find-UiNodeById {
    param($Node, [string]$Id)
    if ($null -ne $Node.attributes -and [string]$Node.attributes.id -eq $Id) { return $Node }
    foreach ($child in @($Node.children)) {
        $match = Find-UiNodeById -Node $child -Id $Id
        if ($null -ne $match) { return $match }
    }
    return $null
}

function Capture-And-AssertStatus {
    param([string]$RemoteName, [string]$LocalPath, [string]$ExpectedText)
    $remotePath = "/data/local/tmp/$RemoteName"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'dumpLayout', '-p', $remotePath, '-a', '-b', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remotePath, $LocalPath) | Out-Null
    $layout = Get-Content -LiteralPath $LocalPath -Raw -Encoding utf8 | ConvertFrom-Json
    $statusNode = Find-UiNodeById -Node $layout -Id 'stage4-verification-status'
    if ($null -eq $statusNode -or [string]$statusNode.attributes.text -notmatch $ExpectedText) {
        throw "Stage 4 UI status did not match $ExpectedText"
    }
    return [string]$statusNode.attributes.text
}

$seedLogs = ''
$replayLogs = ''
try {
    Invoke-TargetHdc -Arguments @('install', '-r', $standardHap) | Out-Null
    $bundleDump = (Invoke-TargetHdc -Arguments @('shell', 'bm', 'dump', '-n', $bundleName) | Out-String)
    if ($bundleDump -notmatch 'EntryBackupAbility' -or $bundleDump -notmatch '"extensionTypeName": "backup"') {
        throw 'Installed standard HAP does not register EntryBackupAbility as a backup extension.'
    }
    Invoke-TargetHdc -Arguments @('shell', 'bm', 'clean', '-n', $bundleName, '-d') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage4Verification', 'seed',
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    $seedLogs = Wait-StageMarker -PassMarker 'STAGE4_STORAGE_PASS'
    $standardStatus = Capture-And-AssertStatus -RemoteName 'pc-stage4-standard-seed.json' `
        -LocalPath $standardLayoutPath -ExpectedText '^PASS seed '

    Invoke-TargetHdc -Arguments @('install', '-r', $replayHap) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage4Verification', 'verify',
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    $replayLogs = Wait-StageMarker -PassMarker 'STAGE4_VARIANT_PASS'
    $replayStatus = Capture-And-AssertStatus -RemoteName 'pc-stage4-replay-verify.json' `
        -LocalPath $replayLayoutPath -ExpectedText '^PASS replay persistence$'

    $filteredLogs = ((($seedLogs + $replayLogs) -split "`r?`n") | Where-Object {
        $_ -match 'STAGE4_(STORAGE|VARIANT)_(PASS|FAIL)'
    }) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($logPath, $filteredLogs + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))

    [pscustomobject]@{
        Status = 'PASS'
        Target = $Target
        StandardUi = $standardStatus
        ReplayUi = $replayStatus
        StandardLayout = $standardLayoutPath
        ReplayLayout = $replayLayoutPath
        Log = $logPath
    } | Format-List
    Write-Host 'HarmonyOS Stage 4 storage verification PASS'
} finally {
    & $hdc -t $Target install -r $standardHap | Out-Null
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start -a EntryAbility -b $bundleName | Out-Null
}
