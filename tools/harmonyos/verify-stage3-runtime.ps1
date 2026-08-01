[CmdletBinding()]
param(
    [string]$Target = '127.0.0.1:5555'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$configPath = Join-Path $repositoryRoot 'config\harmonyos-app-build.json'
$config = Get-Content -LiteralPath $configPath -Raw -Encoding utf8 | ConvertFrom-Json
$toolchainRoot = [System.IO.Path]::GetFullPath(($config.toolchain.root -replace '/', '\'))
$hdc = Join-Path $toolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
$hapPath = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.standard.artifactName)-debug-unsigned.hap"
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence'
$layoutPath = Join-Path $evidenceDirectory 'pc-stage3-verification.json'
$logPath = Join-Path $evidenceDirectory 'pc-stage3-verification.log'
$remoteLayout = '/data/local/tmp/pc-stage3-verification.json'

if (-not (Test-Path -LiteralPath $hdc)) {
    throw "HDC not found: $hdc"
}
if (-not (Test-Path -LiteralPath $hapPath)) {
    throw "Missing standard debug HAP: $hapPath"
}
$connectedTargets = & $hdc list targets
if ($connectedTargets -notcontains $Target) {
    throw "HarmonyOS target is not connected: $Target"
}
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

function Invoke-TargetHdc {
    param([string[]]$Arguments)

    $output = & $hdc -t $Target @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "HDC command failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
    return $output
}

function Find-UiNodeById {
    param($Node, [string]$Id)

    if ($null -ne $Node.attributes -and [string]$Node.attributes.id -eq $Id) {
        return $Node
    }
    foreach ($child in @($Node.children)) {
        $match = Find-UiNodeById -Node $child -Id $Id
        if ($null -ne $match) {
            return $match
        }
    }
    return $null
}

try {
    Invoke-TargetHdc -Arguments @('install', '-r', $hapPath) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @(
        'shell', 'aa', 'start', '--ps', 'stage3Verification', 'true',
        '-a', 'EntryAbility', '-b', $bundleName
    ) | Out-Null

    $logs = ''
    $passed = $false
    for ($attempt = 0; $attempt -lt 25; $attempt++) {
        Start-Sleep -Seconds 2
        $logs = (Invoke-TargetHdc -Arguments @('shell', 'hilog', '-T', 'PCStage3', '-x') | Out-String)
        if ($logs -match 'STAGE3_.*_FAIL') {
            throw "Stage 3 runtime reported a failure:`n$logs"
        }
        if ($logs -match 'STAGE3_DAMAGE_100_PASS' -and $logs -match 'STAGE3_CATALOG_PASS') {
            $passed = $true
            break
        }
    }
    if (-not $passed) {
        throw "Stage 3 runtime markers did not appear within 50 seconds:`n$logs"
    }

    Invoke-TargetHdc -Arguments @(
        'shell', 'uitest', 'dumpLayout', '-p', $remoteLayout, '-a', '-b', $bundleName
    ) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remoteLayout, $layoutPath) | Out-Null
    $layout = Get-Content -LiteralPath $layoutPath -Raw -Encoding utf8 | ConvertFrom-Json
    $statusNode = Find-UiNodeById -Node $layout -Id 'stage3-verification-status'
    if ($null -eq $statusNode -or [string]$statusNode.attributes.text -notmatch '^PASS 100 ') {
        throw 'Stage 3 verification UI did not report PASS 100.'
    }

    $filteredLogs = (($logs -split "`r?`n") | Where-Object {
        $_ -match 'STAGE3_(DAMAGE_100|CATALOG)_(PASS|FAIL)'
    }) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($logPath, $filteredLogs + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))

    [pscustomobject]@{
        Status = 'PASS'
        Target = $Target
        DamageIterations = 100
        RuntimeMarkers = 'STAGE3_DAMAGE_100_PASS | STAGE3_CATALOG_PASS'
        UiStatus = [string]$statusNode.attributes.text
        Layout = $layoutPath
        Log = $logPath
    } | Format-List
    Write-Host 'HarmonyOS Stage 3 runtime verification PASS'
} finally {
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start -a EntryAbility -b $bundleName | Out-Null
}
