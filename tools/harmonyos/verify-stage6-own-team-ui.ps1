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
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence'

if (-not (Test-Path -LiteralPath $hdc)) { throw "HDC not found: $hdc" }
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
    return [pscustomobject]@{
        Path = $local
        Raw = Get-Content -LiteralPath $local -Raw -Encoding utf8
        Tree = Get-Content -LiteralPath $local -Raw -Encoding utf8 | ConvertFrom-Json
    }
}

function Assert-Node {
    param($Capture, [string]$Id)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Layout $($Capture.Path) does not contain node: $Id" }
    return $node
}

function Click-Node {
    param($Capture, [string]$Id)
    $node = Assert-Node -Capture $Capture -Id $Id
    $bounds = [string]$node.attributes.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds for ${Id}: $bounds" }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Write-Host "Click $Id at $x,$y ($bounds)"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$x, [string]$y) | Out-Null
    Start-Sleep -Milliseconds 500
}

function Wait-LayoutForId {
    param([string]$Name, [string]$Id, [int]$Attempts = 10)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $capture = Capture-Layout -Name $Name
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id $Id)) { return $capture }
        Start-Sleep -Milliseconds 500
    }
    throw "Layout $Name did not contain node $Id within $($Attempts / 2) seconds."
}

function Start-App {
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Start-Sleep -Seconds 1
        $logs = Invoke-TargetHdc -Arguments @('shell', 'hilog', '-T', 'PCApp', '-x') | Out-String
        if ($logs -match 'APP_STAGE5_DATA_FAIL') { throw "Application load failed:`n$logs" }
        if ($logs -match 'APP_STAGE5_DATA_READY') { return }
    }
    throw 'Application did not become ready within 15 seconds.'
}

function Invoke-DraftMode {
    param([ValidateSet('seed', 'clear')][string]$Mode)
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage6Verification', $Mode,
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name "pc-stage6-draft-$Mode"
        $node = Find-UiNodeById -Node $capture.Tree -Id 'stage6-verification-status'
        if ($null -ne $node -and [string]$node.attributes.text -eq "PASS $Mode") {
            Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
            return
        }
        if ($null -ne $node -and [string]$node.attributes.text -like 'FAIL*') {
            throw "Stage 6 draft $Mode failed: $([string]$node.attributes.text)"
        }
    }
    throw "Stage 6 draft $Mode did not complete within 5 seconds."
}

$summaries = @()
try {
    foreach ($variantName in @('standard', 'replay')) {
        $variant = $config.products.$variantName
        $hap = Join-Path $repositoryRoot "harmonyos\app\dist\$($variant.artifactName)-debug-unsigned.hap"
        if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
        Invoke-TargetHdc -Arguments @('install', '-r', $hap) | Out-Null
        Invoke-DraftMode -Mode 'seed'
        Start-App

        $homeCapture = Capture-Layout -Name "pc-stage6-$variantName-home"
        Click-Node -Capture $homeCapture -Id 'nav-battle'
        $battle = Wait-LayoutForId -Name "pc-stage6-$variantName-battle" -Id 'battle-start-assistant'
        Assert-Node -Capture $battle -Id 'battle-start-assistant' | Out-Null
        Assert-Node -Capture $battle -Id 'battle-review-own-team' | Out-Null
        if (-not $battle.Raw.Contains('2772') -or -not $battle.Raw.Contains('1240')) {
            throw "$variantName battle page does not show the fullscreen requirement"
        }

        # Deliberately do not click battle-start-assistant: that would open the system privacy decision.
        Click-Node -Capture $battle -Id 'battle-review-own-team'
        $correction = Wait-LayoutForId -Name "pc-stage6-$variantName-correction" -Id 'own-team-correction-page'
        Assert-Node -Capture $correction -Id 'own-team-correction-page' | Out-Null
        $expectedTexts = @(
            [regex]::Unescape('\u6838\u5bf9\u6211\u7684\u961f\u4f0d'),
            [regex]::Unescape('\u767e\u53d8\u602a'),
            [regex]::Unescape('\u9700\u8981 1 \u4e2a\u4e0d\u540c\u62db\u5f0f'),
            [regex]::Unescape('\u8bc6\u522b\u7ed3\u679c\u4e0d\u4f1a\u76f4\u63a5\u4fdd\u5b58')
        )
        foreach ($text in $expectedTexts) {
            if (-not $correction.Raw.Contains($text)) { throw "$variantName correction page does not contain: $text" }
        }
        $summaries += [pscustomobject]@{
            Variant = $variantName
            PrivacyPromptClicked = $false
            BattleLayout = $battle.Path
            CorrectionLayout = $correction.Path
        }
        Invoke-DraftMode -Mode 'clear'
    }
    $summaries | Format-List
    Write-Host 'HarmonyOS Stage 6 own-team UI verification PASS (capture/OCR intentionally not claimed on emulator)'
} finally {
    $standardHap = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.standard.artifactName)-debug-unsigned.hap"
    & $hdc -t $Target install -r $standardHap | Out-Null
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start --ps stage6Verification clear -a EntryAbility -b $bundleName | Out-Null
    Start-Sleep -Seconds 1
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start -a EntryAbility -b $bundleName | Out-Null
}
