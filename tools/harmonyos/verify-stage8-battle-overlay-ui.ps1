[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$toolchainRoot = [System.IO.Path]::GetFullPath(($config.toolchain.root -replace '/', '\'))
$hdc = Join-Path $toolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence'
$panelTexts = @(
    [regex]::Unescape('\u6211\u65b9\u8f93\u51fa'),
    [regex]::Unescape('\u6211\u65b9\u627f\u4f24'),
    [regex]::Unescape('\u6218\u573a\u72b6\u6001'),
    [regex]::Unescape('\u901f\u5ea6\u7ebf'),
    [regex]::Unescape('\u5bf9\u624b\u914d\u7f6e')
)
$hudTexts = @(
    [regex]::Unescape('\u56db\u62db\u4f24\u5bb3'),
    [regex]::Unescape('\u901f\u5ea6\u987a\u5e8f'),
    [regex]::Unescape('\u518d\u6218'),
    [regex]::Unescape('\u5341\u4e07\u4f0f\u7279'),
    [regex]::Unescape('\u53cc\u6253')
)
$singleText = [regex]::Unescape('\u5355\u6253')
$doubleText = [regex]::Unescape('\u53cc\u6253')

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

function Wait-VerificationStatus {
    param([string]$Mode)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name 'pc-stage8-verification-status'
        $node = Find-UiNodeById -Node $capture.Tree -Id 'stage8-verification-status'
        if ($null -ne $node -and [string]$node.attributes.text -eq "PASS $Mode") { return }
        if ($null -ne $node -and [string]$node.attributes.text -like 'FAIL*') {
            throw "Stage 8 $Mode failed: $([string]$node.attributes.text)"
        }
    }
    throw "Stage 8 $Mode did not report PASS"
}

function Start-VerificationMode {
    param([ValidateSet('panel', 'hud', 'single', 'hidden', 'restore', 'clear')][string]$Mode)
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage8Verification', $Mode,
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    Wait-VerificationStatus -Mode $Mode
}

function Assert-Texts {
    param($Capture, [string[]]$Texts)
    foreach ($expected in $Texts) {
        if (-not $Capture.Raw.Contains($expected)) { throw "$($Capture.Path) does not contain: $expected" }
    }
}

function Wait-HudDamage {
    param([string]$Name)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name $Name
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id 'battle-direct-hud') -and
            $capture.Raw -match '[0-9]+\.[0-9]+[^%]*%') { return $capture }
    }
    throw "$Name did not show a calculated percentage"
}

$summaries = @()
try {
    foreach ($variantName in @('standard', 'replay')) {
        $variant = $config.products.$variantName
        $hap = Join-Path $repositoryRoot "harmonyos\app\dist\$($variant.artifactName)-debug-unsigned.hap"
        if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
        Invoke-TargetHdc -Arguments @('install', '-r', $hap) | Out-Null

        Start-VerificationMode -Mode 'panel'
        $panel = Wait-LayoutForId -Name "pc-stage8-$variantName-panel" -Id 'battle-overlay-panel'
        Assert-Texts -Capture $panel -Texts $panelTexts

        Start-VerificationMode -Mode 'hud'
        $hud = Wait-HudDamage -Name "pc-stage8-$variantName-hud"
        Assert-Texts -Capture $hud -Texts $hudTexts

        Start-VerificationMode -Mode 'single'
        $single = Wait-LayoutForId -Name "pc-stage8-$variantName-single" -Id 'battle-hud-format'
        $formatNode = Find-UiNodeById -Node $single.Tree -Id 'battle-hud-format'
        if ([string]$formatNode.attributes.text -ne $singleText) { throw "$variantName HUD did not switch to single battle" }

        Start-VerificationMode -Mode 'hidden'
        $hidden = Wait-LayoutForId -Name "pc-stage8-$variantName-hidden" -Id 'battle-hud-hidden-entry'

        Start-VerificationMode -Mode 'restore'
        $restored = Wait-HudDamage -Name "pc-stage8-$variantName-hud"
        Assert-Texts -Capture $restored -Texts $hudTexts
        $formatNode = Find-UiNodeById -Node $restored.Tree -Id 'battle-hud-format'
        if ([string]$formatNode.attributes.text -ne $doubleText) { throw "$variantName HUD did not restore double battle" }

        $summaries += [pscustomobject]@{ Variant = $variantName; PrivacyPromptClicked = $false;
            Panel = 'PASS'; HudDamage = 'PASS'; SingleDouble = 'PASS'; HideRestore = 'PASS';
            PanelLayout = $panel.Path; HudLayout = $restored.Path }
        Start-VerificationMode -Mode 'clear'
    }
} finally {
    foreach ($name in @('pc-stage8-verification-status', 'pc-stage8-standard-single',
        'pc-stage8-standard-hidden', 'pc-stage8-replay-single', 'pc-stage8-replay-hidden')) {
        $temporary = Join-Path $evidenceDirectory "$name.json"
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

$summaries | Format-Table -AutoSize
