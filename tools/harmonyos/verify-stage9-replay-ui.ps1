[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$toolchainRoot = [System.IO.Path]::GetFullPath(($config.toolchain.root -replace '/', '\'))
$hdc = Join-Path $toolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence'

if (-not (Test-Path -LiteralPath $hdc)) { throw "HDC not found: $hdc" }
if ((& $hdc list targets) -notcontains $Target) { throw "HarmonyOS target is not connected: $Target" }
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

function Invoke-TargetHdc {
    param([string[]]$Arguments)
    $output = & $hdc -t $Target @Arguments
    if ($LASTEXITCODE -ne 0) { throw "HDC command failed: $($Arguments -join ' ')" }
    return $output
}

function Find-UiNodeById {
    param($Node, [string]$Id)
    $queue = [System.Collections.Generic.Queue[object]]::new()
    $queue.Enqueue($Node)
    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue()
        if ($null -ne $current.attributes -and [string]$current.attributes.id -eq $Id) { return $current }
        foreach ($child in @($current.children)) {
            if ($null -ne $child) { $queue.Enqueue($child) }
        }
    }
    return $null
}

function Capture-Layout {
    param([string]$Name)
    $remote = "/data/local/tmp/$Name.json"
    $local = Join-Path $evidenceDirectory "$Name.json"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'dumpLayout', '-p', $remote, '-a', '-b', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remote, $local) | Out-Null
    $raw = Get-Content -LiteralPath $local -Raw -Encoding utf8
    return [pscustomobject]@{ Path = $local; Raw = $raw; Tree = $raw | ConvertFrom-Json }
}

function Wait-LayoutForId {
    param([string]$Name, [string]$Id, [int]$Attempts = 30)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name $Name
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id $Id)) { return $capture }
    }
    throw "Layout $Name did not contain $Id"
}

function Assert-Node {
    param($Capture, [string]$Id)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "$($Capture.Path) does not contain $Id" }
    return $node
}

function Click-Node {
    param($Capture, [string]$Id)
    $node = Assert-Node -Capture $Capture -Id $Id
    $bounds = [string]$node.attributes.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds for ${Id}: $bounds" }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$x, [string]$y) | Out-Null
}

function Start-NormalApp {
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
}

function Start-Verification {
    param([ValidateSet('routes', 'profile')][string]$Mode)
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage9Verification', $Mode,
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name "pc-stage9-$Mode-status"
        $node = Find-UiNodeById -Node $capture.Tree -Id 'stage9-verification-status'
        if ($null -ne $node -and [string]$node.attributes.text -like "PASS $Mode*") {
            return [string]$node.attributes.text
        }
        if ($null -ne $node -and [string]$node.attributes.text -like 'FAIL*') {
            throw "Stage 9 $Mode failed: $([string]$node.attributes.text)"
        }
    }
    throw "Stage 9 $Mode did not report PASS"
}

$summaries = @()
foreach ($variantName in @('replay', 'standard')) {
    $variant = $config.products.$variantName
    $hap = Join-Path $repositoryRoot "harmonyos\app\dist\$($variant.artifactName)-debug-unsigned.hap"
    if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
    Invoke-TargetHdc -Arguments @('install', '-r', $hap) | Out-Null
    $routeStatus = Start-Verification -Mode 'routes'
    $profileStatus = Start-Verification -Mode 'profile'
    $codecStatus = if ($profileStatus -like '*emulator-blocked*') { 'BLOCKED_BY_EMULATOR' } else { 'PASS' }

    Start-NormalApp
    if ($variantName -eq 'replay') {
        $launch = Wait-LayoutForId -Name 'pc-stage9-replay-launch' -Id 'replay-launch-page'
        foreach ($id in @('replay-mode-combined', 'replay-mode-recognition', 'replay-mode-record-only')) {
            Assert-Node -Capture $launch -Id $id | Out-Null
        }
        Click-Node -Capture $launch -Id 'replay-mode-record-only'
        $recordOnly = Wait-LayoutForId -Name 'pc-stage9-replay-record-only' -Id 'replay-record-only-start'
        if ($recordOnly.Raw -match 'battle-start-assistant|calculator-submit') {
            throw 'Record-only route unexpectedly loaded recognition or damage UI'
        }
        $summaries += [pscustomobject]@{ Variant = $variantName; Routes = 'PASS'; CodecPrepare = $codecStatus;
            ProductGate = 'PASS'; PrivacyPromptClicked = $false; Layout = $recordOnly.Path }
    } else {
        $standard = Wait-LayoutForId -Name 'pc-stage9-standard-home' -Id 'home-start-calculator'
        if ($standard.Raw -match 'replay-mode-combined|replay-mode-record-only|replay-launch-page') {
            throw 'Standard product exposed replay launch controls'
        }
        $summaries += [pscustomobject]@{ Variant = $variantName; Routes = 'PASS'; CodecPrepare = $codecStatus;
            ProductGate = 'PASS'; PrivacyPromptClicked = $false; Layout = $standard.Path }
    }
}

foreach ($name in @('pc-stage9-routes-status', 'pc-stage9-profile-status')) {
    $temporary = Join-Path $evidenceDirectory "$name.json"
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
$summaries | Format-Table -AutoSize
