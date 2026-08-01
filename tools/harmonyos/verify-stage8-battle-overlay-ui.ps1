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
    [regex]::Unescape('\u518d\u6218'),
    [regex]::Unescape('\u8bc6\u522b\u6211\u65b9'),
    [regex]::Unescape('\u9690\u85cf HUD'),
    [regex]::Unescape('\u8be6\u7ec6'),
    [regex]::Unescape('\u53cc\u6253')
)
$singleText = [regex]::Unescape('\u5355\u6253')
$doubleText = [regex]::Unescape('\u53cc\u6253')
$showText = [regex]::Unescape('\u663e\u793a HUD')

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

function Find-UiNodeByPagePath {
    param($Node, [string]$PagePath)
    if ($null -ne $Node.attributes -and [string]$Node.attributes.pagePath -eq $PagePath) { return $Node }
    foreach ($child in @($Node.children)) {
        $match = Find-UiNodeByPagePath -Node $child -PagePath $PagePath
        if ($null -ne $match) { return $match }
    }
    return $null
}

function Find-VerificationStatus {
    param($Tree)
    $node = Find-UiNodeById -Node $Tree -Id 'stage8-verification-status'
    if ($null -eq $node) { $node = Find-UiNodeById -Node $Tree -Id 'stage8-verification-proxy' }
    return $node
}

function Capture-Layout {
    param([string]$Name, [switch]$AllWindows)
    $remote = "/data/local/tmp/$Name.json"
    $local = Join-Path $evidenceDirectory "$Name.json"
    $arguments = @('shell', 'uitest', 'dumpLayout', '-p', $remote)
    if ($AllWindows) { $arguments += '-a' }
    $arguments += @('-b', $bundleName)
    Invoke-TargetHdc -Arguments $arguments | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remote, $local) | Out-Null
    $raw = Get-Content -LiteralPath $local -Raw -Encoding utf8
    return [pscustomobject]@{ Path = $local; Raw = $raw; Tree = $raw | ConvertFrom-Json }
}

function Wait-LayoutForId {
    param([string]$Name, [string]$Id, [int]$Attempts = 30, [switch]$AllWindows)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name $Name -AllWindows:$AllWindows
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id $Id)) { return $capture }
    }
    throw "Layout $Name did not contain $Id"
}

function Wait-VerificationStatus {
    param([string]$Mode)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name 'pc-stage8-verification-status'
        $node = Find-VerificationStatus -Tree $capture.Tree
        if ($null -ne $node -and [string]$node.attributes.text -like "PASS $Mode*") { return $capture }
        if ($null -ne $node -and [string]$node.attributes.text -like 'FAIL*') {
            throw "Stage 8 $Mode failed: $([string]$node.attributes.text)"
        }
    }
    throw "Stage 8 $Mode did not report PASS"
}

function Start-VerificationMode {
    param([ValidateSet('panel', 'hud', 'single', 'hidden', 'restore', 'clear')][string]$Mode)
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    # The emulator acknowledges force-stop before the previous ability process has
    # always left its final window. Starting immediately can reuse the stale Index
    # window and drop the verification Want parameters.
    Start-Sleep -Seconds 1
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '--ps', 'stage8Verification', $Mode,
        '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    return Wait-VerificationStatus -Mode $Mode
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
        $damage = Find-UiNodeById -Node $capture.Tree -Id 'stage8-hud-damage'
        if ($null -eq $damage) { $damage = Find-UiNodeById -Node $capture.Tree -Id 'stage8-hud-damage-proxy' }
        if ($null -ne $damage -and
            [string]$damage.attributes.text -match '[0-9]+\.[0-9]+[^%]*%') { return $capture }
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

        $panelStatus = Start-VerificationMode -Mode 'panel'
        $panel = Wait-LayoutForId -Name "pc-stage8-$variantName-panel" -Id 'battle-overlay-panel' -Attempts 5 -AllWindows
        Assert-Texts -Capture $panel -Texts $panelTexts

        $hudStatus = Start-VerificationMode -Mode 'hud'
        $hud = Wait-HudDamage -Name "pc-stage8-$variantName-hud-damage"
        $hudVisual = $null
        for ($attempt = 0; $attempt -lt 5; $attempt++) {
            $hudVisual = Capture-Layout -Name "pc-stage8-$variantName-hud" -AllWindows
            if ($null -ne (Find-UiNodeByPagePath -Node $hudVisual.Tree -PagePath 'pages/BattleHudFormat')) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($null -eq $hudVisual -or
            $null -eq (Find-UiNodeByPagePath -Node $hudVisual.Tree -PagePath 'pages/BattleHudFormat')) {
            throw "$variantName HUD distributed windows were not visible"
        }
        Assert-Texts -Capture $hudVisual -Texts $hudTexts

        $single = Start-VerificationMode -Mode 'single'
        $singleStatus = Find-VerificationStatus -Tree $single.Tree
        if ([string]$singleStatus.attributes.text -notlike '*battle=SINGLE visible=true windows=13*') {
            throw "$variantName HUD did not switch to single battle"
        }

        $hidden = Start-VerificationMode -Mode 'hidden'
        $hiddenStatus = Find-VerificationStatus -Tree $hidden.Tree
        if ([string]$hiddenStatus.attributes.text -notlike '*battle=DOUBLE visible=false windows=6*') {
            throw "$variantName HUD hide state did not leave only its toolbar controls"
        }

        $restoredStatusCapture = Start-VerificationMode -Mode 'restore'
        $restoredStatus = Find-VerificationStatus -Tree $restoredStatusCapture.Tree
        $restored = Wait-HudDamage -Name "pc-stage8-$variantName-restored-damage"
        if ([string]$restoredStatus.attributes.text -notlike '*battle=DOUBLE visible=true windows=15*') {
            throw "$variantName HUD did not restore double battle"
        }

        $summaries += [pscustomobject]@{ Variant = $variantName; PrivacyPromptClicked = $false;
            Panel = 'PASS'; HudDamage = 'PASS'; SingleDouble = 'PASS'; HideRestore = 'PASS';
            PanelLayout = $panel.Path; HudLayout = $hudVisual.Path; DamageLayout = $restored.Path }
        Start-VerificationMode -Mode 'clear' | Out-Null
    }
} finally {
    foreach ($name in @('pc-stage8-verification-status', 'pc-stage8-standard-single',
        'pc-stage8-standard-hidden', 'pc-stage8-replay-single', 'pc-stage8-replay-hidden')) {
        $temporary = Join-Path $evidenceDirectory "$name.json"
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

$summaries | Format-Table -AutoSize
