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
    if ($null -ne $Node.attributes -and [string]$Node.attributes.id -eq $Id) { return $Node }
    foreach ($child in @($Node.children)) {
        $match = Find-UiNodeById -Node $child -Id $Id
        if ($null -ne $match) { return $match }
    }
    return $null
}

function Capture-Layout {
    param([string]$Name)
    $remote = "/data/local/tmp/$Name.json"
    $local = Join-Path $evidenceDirectory "$Name.json"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'dumpLayout', '-p', $remote, '-a', '-b', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remote, $local) | Out-Null
    return [pscustomobject]@{ Path = $local; Raw = Get-Content -LiteralPath $local -Raw -Encoding utf8;
        Tree = Get-Content -LiteralPath $local -Raw -Encoding utf8 | ConvertFrom-Json }
}

function Wait-LayoutForId {
    param([string]$Name, [string]$Id, [int]$Attempts = 16)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name $Name
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id $Id)) { return $capture }
    }
    throw "Layout $Name did not contain $Id"
}

function Click-Node {
    param($Capture, [string]$Id)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Missing node: $Id" }
    $bounds = [string]$node.attributes.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds: $bounds" }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$x, [string]$y) | Out-Null
    Start-Sleep -Milliseconds 500
}

function Invoke-SeedMode {
    param([ValidateSet('seed', 'clear')][string]$Mode)
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage7Verification', $Mode,
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    $capture = Wait-LayoutForId -Name "pc-stage7-$Mode" -Id 'stage7-verification-status'
    $node = Find-UiNodeById -Node $capture.Tree -Id 'stage7-verification-status'
    if ([string]$node.attributes.text -ne "PASS $Mode") { throw "Stage 7 $Mode failed: $([string]$node.attributes.text)" }
}

function Invoke-NativeSmoke {
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage7Verification', 'native-smoke',
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name 'pc-stage7-native-smoke'
        $node = Find-UiNodeById -Node $capture.Tree -Id 'stage7-verification-status'
        if ($null -ne $node -and [string]$node.attributes.text -eq 'PASS native-smoke') { return }
        if ($null -ne $node -and [string]$node.attributes.text -like 'FAIL*') {
            throw "Stage 7 native smoke failed: $([string]$node.attributes.text)"
        }
    }
    throw 'Stage 7 native smoke did not complete within 60 seconds.'
}

function Start-App {
    param([switch]$SelectReplayRecognition)

    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    if ($SelectReplayRecognition) {
        $launch = Wait-LayoutForId -Name 'pc-stage7-replay-launch' -Id 'replay-mode-recognition'
        Click-Node -Capture $launch -Id 'replay-mode-recognition'
    }
    return Wait-LayoutForId -Name 'pc-stage7-home' -Id 'nav-battle'
}

$summaries = @()
try {
    foreach ($variantName in @('standard', 'replay')) {
        $variant = $config.products.$variantName
        $hap = Join-Path $repositoryRoot "harmonyos\app\dist\$($variant.artifactName)-debug-unsigned.hap"
        if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
        Invoke-TargetHdc -Arguments @('install', '-r', $hap) | Out-Null
        Invoke-NativeSmoke
        Invoke-SeedMode -Mode 'seed'
        $homeCapture = Start-App -SelectReplayRecognition:($variantName -eq 'replay')
        Click-Node -Capture $homeCapture -Id 'nav-battle'
        $battle = Wait-LayoutForId -Name "pc-stage7-$variantName-battle" -Id 'battle-review-team-preview'
        Click-Node -Capture $battle -Id 'battle-review-team-preview'
        $review = Wait-LayoutForId -Name "pc-stage7-$variantName-review" -Id 'battle-preview-review-page'
        $expectedTexts = @(
            [regex]::Unescape('\u6838\u5bf9\u53cc\u65b9\u9635\u5bb9'),
            [regex]::Unescape('\u6211\u65b9\u516d\u53ea'),
            [regex]::Unescape('\u767e\u53d8\u602a'),
            [regex]::Unescape('\u91cd\u70b9\u6838\u5bf9'),
            [regex]::Unescape('\u524d\u4e09\u4e2a\u5019\u9009')
        )
        foreach ($text in $expectedTexts) {
            if (-not $review.Raw.Contains($text)) { throw "$variantName review page does not contain: $text" }
        }
        $summaries += [pscustomobject]@{ Variant = $variantName; PrivacyPromptClicked = $false;
            NativeSmoke = 'PASS'; BattleLayout = $battle.Path; ReviewLayout = $review.Path }
        Invoke-SeedMode -Mode 'clear'
    }
    $summaries | Format-List
    Write-Host 'HarmonyOS Stage 7 team-preview UI verification PASS (native capture intentionally not claimed on emulator)'
} finally {
    foreach ($name in @('pc-stage7-clear', 'pc-stage7-home', 'pc-stage7-native-smoke', 'pc-stage7-seed',
        'pc-stage7-replay-launch')) {
        $temporary = Join-Path $evidenceDirectory "$name.json"
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
    $standardHap = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.standard.artifactName)-debug-unsigned.hap"
    & $hdc -t $Target install -r $standardHap | Out-Null
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start --ps stage7Verification clear -a EntryAbility -b $bundleName | Out-Null
    Start-Sleep -Seconds 1
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start -a EntryAbility -b $bundleName | Out-Null
}
