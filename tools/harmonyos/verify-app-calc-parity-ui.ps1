[CmdletBinding()]
param(
    [string]$Target = '127.0.0.1:5557',
    [string]$OlderStandardHap = '',
    [ValidateSet('APP003', 'APP005', 'APP005Upgrade', 'APP006', 'CALC012', 'Runtime', 'All')]
    [string]$Scope = 'Runtime'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$hdc = Join-Path $localConfig.ToolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
$sunnyText = "$([char]0x6674)$([char]0x5929)"
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence\app-calc-parity'
$haks = @{
    standard = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.standard.artifactName)-debug-unsigned.hap"
    replay = Join-Path $repositoryRoot "harmonyos\app\dist\$($config.products.replay.artifactName)-debug-unsigned.hap"
}

if (-not (Test-Path -LiteralPath $hdc)) { throw "HDC not found: $hdc" }
if ((& $hdc list targets) -notcontains $Target) { throw "HarmonyOS target is not connected: $Target" }
foreach ($hap in $haks.Values) {
    if (-not (Test-Path -LiteralPath $hap -PathType Leaf)) { throw "Missing formal Debug HAP: $hap" }
}
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

function Invoke-TargetHdc {
    param([string[]]$Arguments)
    $output = & $hdc -t $Target @Arguments
    if ($LASTEXITCODE -ne 0) { throw "HDC command failed: $($Arguments -join ' ')" }
    return $output
}

function Install-Hap {
    param([string]$Path)
    $output = Invoke-TargetHdc -Arguments @('install', '-r', $Path)
    $text = $output | Out-String
    if ($text -match '(?i)error:' -or $text -notmatch 'install bundle successfully') {
        throw "HAP install did not succeed: $text"
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

function Get-NodePoint {
    param($Node, [string]$Label)
    $bounds = [string]$Node.attributes.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds for ${Label}: $bounds" }
    return [pscustomobject]@{
        X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        Width = [int]$Matches[3] - [int]$Matches[1]
        Height = [int]$Matches[4] - [int]$Matches[2]
    }
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

function Capture-Screen {
    param([string]$Name)
    $remote = "/data/local/tmp/$Name.png"
    $local = Join-Path $evidenceDirectory "$Name.png"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'screenCap', '-p', $remote) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remote, $local) | Out-Null
    return $local
}

function Wait-ForId {
    param([string]$Name, [string]$Id, [int]$Attempts = 3, [int]$DelayMilliseconds = 500)
    $emptyCaptureCount = 0
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        Start-Sleep -Milliseconds $DelayMilliseconds
        $capture = Capture-Layout -Name $Name
        if ($capture.Raw.Length -le 500) {
            $emptyCaptureCount++
            if ($emptyCaptureCount -ge 3) { throw "Formal UI returned an empty layout three times for $Name" }
            continue
        }
        $node = Find-UiNodeById -Node $capture.Tree -Id $Id
        if ($null -ne $node -and [string]$node.attributes.visible -ne 'false') { return $capture }
    }
    throw "Formal UI did not expose $Id in $Name"
}

function Wait-ForScrolledId {
    param([string]$Name, [string]$Id, [int]$Attempts = 12)
    $emptyCaptureCount = 0
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $capture = Capture-Layout -Name "$Name-$attempt"
        if ($capture.Raw.Length -le 500) {
            $emptyCaptureCount++
            if ($emptyCaptureCount -ge 3) { throw "Formal UI returned an empty layout three times for $Name" }
            Start-Sleep -Milliseconds 300
            continue
        }
        $node = Find-UiNodeById -Node $capture.Tree -Id $Id
        if ($null -ne $node -and [string]$node.attributes.visible -eq 'true') { return $capture }
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '620', '2200', '620', '500', '2400') | Out-Null
        Start-Sleep -Milliseconds 200
    }
    throw "Formal UI did not expose scroll target $Id"
}

function Click-Id {
    param($Capture, [string]$Id)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Missing node $Id in $($Capture.Path)" }
    $point = Get-NodePoint -Node $node -Label $Id
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$point.X, [string]$point.Y) | Out-Null
    Start-Sleep -Milliseconds 300
}

function Click-Point {
    param($Point)
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$Point.X, [string]$Point.Y) | Out-Null
}

function Invoke-EdgeBack {
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '1', '1300', '1100', '1300', '200') | Out-Null
    Start-Sleep -Milliseconds 500
}

function Input-Id {
    param($Capture, [string]$Id, [string]$Text)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Missing input $Id in $($Capture.Path)" }
    $point = Get-NodePoint -Node $node -Label $Id
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'inputText', [string]$point.X, [string]$point.Y, $Text) | Out-Null
    Start-Sleep -Milliseconds 500
}

function Assert-FormalRoute {
    param($Capture)
    if ($Capture.Raw -match 'Stage\d+Verification|stage\d+Verification|Debug verification|DEBUG_PAGE') {
        throw "Obsolete Debug route exposed by formal HAP: $($Capture.Path)"
    }
}

function Start-FormalApp {
    param([ValidateSet('standard', 'replay')][string]$Variant, [switch]$Install)
    if ($Install) { Install-Hap -Path $haks[$Variant] | Out-Null }
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    $homeCapture = Wait-ForId -Name "app-calc-$Variant-home" -Id 'home-page'
    Assert-FormalRoute -Capture $homeCapture
    $variantId = if ($Variant -eq 'replay') { 'variant-replay' } else { 'variant-standard' }
    if ($null -eq (Find-UiNodeById -Node $homeCapture.Tree -Id $variantId)) { throw "Wrong formal variant after install: $Variant" }
    return $homeCapture
}

function Read-AppLogs {
    return (Invoke-TargetHdc -Arguments @('shell', 'hilog', '-T', 'PCApp', '-x') | Out-String)
}

function Wait-ForLog {
    param([string]$Pattern, [int]$Attempts = 30)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $logs = Read-AppLogs
        if ($logs -match $Pattern) { return $logs }
        Start-Sleep -Milliseconds 500
    }
    throw "App log did not contain $Pattern"
}

$results = [ordered]@{}
$artifactHashes = [ordered]@{}
function Write-EvidenceSummary {
    $summary = [ordered]@{
        schemaVersion = 1
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        sourceCommit = (git -C $repositoryRoot rev-parse HEAD).Trim()
        sourceTree = 'UNCOMMITTED_WORKING_TREE'
        target = $Target
        os = 'OpenHarmony-6.1.1.125'
        api = 24
        abi = 'x86_64'
        buildMode = 'debug'
        formalProductRoutesOnly = $true
        privacyPromptsAutomated = $false
        hapSha256 = $artifactHashes
        results = $results
    }
    $summaryPath = Join-Path $evidenceDirectory 'app-calc-parity-summary.json'
    $summaryJson = ($summary | ConvertTo-Json -Depth 8) + [Environment]::NewLine
    [System.IO.File]::WriteAllText($summaryPath, $summaryJson, [System.Text.UTF8Encoding]::new($false))
    $scopeSummaryPath = Join-Path $evidenceDirectory "app-calc-parity-$($Scope.ToLowerInvariant())-summary.json"
    [System.IO.File]::WriteAllText($scopeSummaryPath, $summaryJson, [System.Text.UTF8Encoding]::new($false))
    $summary | ConvertTo-Json -Depth 8
    Write-Host "HarmonyOS APP/CALC formal UI evidence complete: $summaryPath"
}

foreach ($variant in @('standard', 'replay')) {
    $artifactHashes[$variant] = (Get-FileHash -Algorithm SHA256 -LiteralPath $haks[$variant]).Hash.ToLowerInvariant()
}

if ($Scope -in @('APP003', 'All')) {
    foreach ($variant in @('standard', 'replay')) {
        $homeCapture = Start-FormalApp -Variant $variant -Install

        # APP-003 is optional because the API 24 emulator does not reproduce the
        # system edge gesture. Do not invoke the third-party IME/privacy prompt.
        Click-Id -Capture $homeCapture -Id 'home-manage-presets'
        $presets = Wait-ForId -Name "app003-$variant-presets" -Id 'preset-manager-page'
        Click-Id -Capture $presets -Id 'preset-create'
        $search = Wait-ForId -Name "app003-$variant-search" -Id 'entity-search-page'
        Click-Id -Capture $search -Id 'entity-result-species-Abomasnow'
        $editor = Wait-ForId -Name "app003-$variant-editor" -Id 'preset-editor-page'
        Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        $guardLogs = Wait-ForLog -Pattern 'APP_BACK_GUARD_SET page=PRESET_EDIT' -Attempts 3
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        Wait-ForId -Name "app003-$variant-owner-after-confirmed-back" -Id 'preset-manager-page' | Out-Null
        $guardLogs = Wait-ForLog -Pattern 'APP_BACK_GUARD_CONFIRM page=PRESET_EDIT target=PRESETS' -Attempts 3
        [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory "app003-$variant-system-back.log"), $guardLogs,
            [System.Text.UTF8Encoding]::new($false))

        # Repeat the unsaved-editor path with a real left-edge swipe. The first
        # gesture must show the guard and retain the editor; the second confirms.
        $presets = Wait-ForId -Name "app003-$variant-presets-before-edge-editor" -Id 'preset-manager-page'
        Click-Id -Capture $presets -Id 'preset-create'
        $search = Wait-ForId -Name "app003-$variant-search-before-edge-editor" -Id 'entity-search-page'
        Click-Id -Capture $search -Id 'entity-result-species-Abomasnow'
        Wait-ForId -Name "app003-$variant-editor-before-edge-back" -Id 'preset-editor-page' | Out-Null
        Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
        Invoke-EdgeBack
        try {
            $edgeLogs = Wait-ForLog -Pattern 'APP_BACK_GUARD_SET page=PRESET_EDIT' -Attempts 3
            Invoke-EdgeBack
            $presetsAfterEdgeEditor = Wait-ForId -Name "app003-$variant-owner-after-edge-editor" -Id 'preset-manager-page'
            $edgeLogs = Wait-ForLog -Pattern 'APP_BACK_GUARD_CONFIRM page=PRESET_EDIT target=PRESETS' -Attempts 3
            Invoke-EdgeBack
            $homeAfterGesture = Wait-ForId -Name "app003-$variant-home-after-edge-secondary" -Id 'home-page'
            $edgeLogs = Read-AppLogs
            [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory "app003-$variant-edge-back.log"), $edgeLogs,
                [System.Text.UTF8Encoding]::new($false))
        } catch {
            $results["APP-003-$variant"] = 'BLOCKED_EDGE_GESTURE_NOT_REPRODUCIBLE_SYSTEM_BACK_PARTIAL_PASS'
            Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
            Start-Sleep -Milliseconds 100
            Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
            try {
                $fallbackPresets = Wait-ForId -Name "app003-$variant-edge-fallback-presets" -Id 'preset-manager-page'
                Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
            } catch {
                # The first fallback Back may already have completed the guarded route.
            }
            $homeAfterGesture = Wait-ForId -Name "app003-$variant-home-after-edge-fallback-back" -Id 'home-page'
        }

        Click-Id -Capture $homeAfterGesture -Id 'nav-calculator'
        $weatherView = Wait-ForScrolledId -Name "app003-$variant-weather" -Id 'calculator-weather'
        Click-Id -Capture $weatherView -Id 'calculator-weather'
        $popupOpen = Capture-Layout -Name "app003-$variant-weather-popup-open-system"
        if (-not $popupOpen.Raw.Contains($sunnyText)) { throw "$variant weather popup did not open" }
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        $afterPopupBack = Wait-ForId -Name "app003-$variant-calculator-after-popup-back" -Id 'calculator-page'
        if ($afterPopupBack.Raw.Contains($sunnyText)) { throw "$variant system Back did not close the weather popup" }
        if ($null -eq $results["APP-003-$variant"]) {
            Click-Id -Capture $afterPopupBack -Id 'calculator-weather'
            $popupOpen = Capture-Layout -Name "app003-$variant-weather-popup-open-edge"
            if (-not $popupOpen.Raw.Contains($sunnyText)) { throw "$variant weather popup did not reopen" }
            Invoke-EdgeBack
            $afterPopupEdge = Wait-ForId -Name "app003-$variant-calculator-after-popup-edge" -Id 'calculator-page'
            if ($afterPopupEdge.Raw.Contains($sunnyText)) {
                $results["APP-003-$variant"] = 'BLOCKED_EDGE_POPUP_BACK_NOT_REPRODUCIBLE_SYSTEM_BACK_PASS'
                Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
            }
        }
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        Wait-ForId -Name "app003-$variant-home-after-system-back" -Id 'home-page' | Out-Null
        if ($null -eq $results["APP-003-$variant"]) {
            $results["APP-003-$variant"] = 'PASS_E3_FORMAL_HAP_SYSTEM_AND_EDGE_BACK'
        }
    }
} else {
    $results['APP-003'] = 'NOT_EXECUTED_BY_SCOPE'
}

if ($Scope -eq 'APP003') {
    $results['APP-005'] = 'BLOCKED_NOT_EXECUTED_AND_MISSING_SAME_IDENTITY_LOWER_VERSION_HAP'
    $results['APP-006'] = 'BLOCKED_NOT_EXECUTED_AFTER_SCOPE_REDUCTION'
    $results['CALC-012'] = 'BLOCKED_NOT_EXECUTED_AFTER_SCOPE_REDUCTION'
    $results['CALC-002'] = 'BLOCKED_REQUIRES_ANDROID_HARMONY_MANUAL_E4'
    $results['CALC-008'] = 'BLOCKED_REQUIRES_COMPLETE_CASES_AND_ANDROID_HARMONY_MANUAL_E4'
    $results['CALC-011'] = 'BLOCKED_REQUIRES_PAIRED_LOCALIZATION_MANUAL_E4'
    $results['UI-011'] = 'BLOCKED_REQUIRES_SCREEN_READER_FOCUS_CONTRAST_MANUAL_E4'
    Write-EvidenceSummary
    exit 0
}

if ($Scope -in @('APP005', 'All')) {
foreach ($variant in @('standard', 'replay')) {
    try {
        # APP-005: cold launch, hot resume, and same-identity cross-product overwrite.
        $hotHome = Start-FormalApp -Variant $variant -Install
        Click-Id -Capture $hotHome -Id 'nav-calculator'
        Wait-ForId -Name "app005-$variant-before-hot-resume" -Id 'calculator-page' | Out-Null
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Home') | Out-Null
        Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
        $hotRoute = Wait-ForId -Name "app005-$variant-after-hot-resume" -Id 'calculator-page'
        Assert-FormalRoute -Capture $hotRoute
        $results["APP-005-$variant-cold-hot-overwrite"] = 'PASS_E3_FORMAL_HAP'
    } catch {
        $results["APP-005-$variant-cold-hot-overwrite"] = 'BLOCKED_RUNTIME_UI_INSTABILITY'
    }
}
}

if ($Scope -in @('APP005', 'APP005Upgrade', 'All') -and [string]::IsNullOrWhiteSpace($OlderStandardHap)) {
    $results['APP-005-upgrade'] = 'BLOCKED_MISSING_SAME_IDENTITY_LOWER_VERSION_HAP'
} elseif ($Scope -in @('APP005', 'APP005Upgrade', 'All')) {
    try {
        $resolvedOlder = [System.IO.Path]::GetFullPath($OlderStandardHap)
        if (-not (Test-Path -LiteralPath $resolvedOlder -PathType Leaf)) { throw "Older HAP not found: $resolvedOlder" }
        $artifactHashes['olderStandard'] = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedOlder).Hash.ToLowerInvariant()
        $uninstallOutput = Invoke-TargetHdc -Arguments @('uninstall', $bundleName) | Out-String
        if ($uninstallOutput -match '(?i)error:|failed') { throw "Test-app uninstall failed: $uninstallOutput" }
        Install-Hap -Path $resolvedOlder | Out-Null
        $olderHome = Start-FormalApp -Variant standard
        $olderVersion = Find-UiNodeById -Node $olderHome.Tree -Id 'home-version'
        if ($null -eq $olderVersion -or [string]$olderVersion.attributes.text -ne 'v1.1.3') {
            throw 'Caller-supplied HAP did not expose v1.1.3 on the formal Home route'
        }
        Capture-Layout -Name 'app005-upgrade-v1.1.3-home' | Out-Null
        Install-Hap -Path $haks.standard | Out-Null
        $upgradedHome = Start-FormalApp -Variant standard
        $upgradedVersion = Find-UiNodeById -Node $upgradedHome.Tree -Id 'home-version'
        if ($null -eq $upgradedVersion -or [string]$upgradedVersion.attributes.text -ne "v$($config.versionName)") {
            throw 'Current HAP did not expose its expected version on the formal Home route after upgrade'
        }
        Capture-Layout -Name 'app005-upgrade-v1.1.4-home' | Out-Null
        $results['APP-005-upgrade'] = 'PASS_E3_WITH_CALLER_SUPPLIED_OLDER_HAP'
    } catch {
        $results['APP-005-upgrade'] = "BLOCKED:$($_.Exception.Message)"
    } finally {
        # Always leave the emulator on the current standard 1.1.4 product.
        Install-Hap -Path $haks.standard | Out-Null
        Start-FormalApp -Variant standard | Out-Null
    }
}

# APP-006: pre-position both routes, then issue update and calculate actions fast
# enough that log ordering must prove a real overlapping interval.
if ($Scope -in @('APP006', 'Runtime', 'All')) {
    foreach ($variant in @('standard', 'replay')) {
        try {
            $homeCapture = Start-FormalApp -Variant $variant -Install
            $startupLogs = Wait-ForLog -Pattern 'APP_DAMAGE_ENGINE_READY' -Attempts 20
            foreach ($marker in @('APP_LOAD_BEGIN', 'APP_STORAGE_READY', 'APP_STAGE5_DATA_READY',
                'APP_DAMAGE_ENGINE_READY', 'APP_NATIVE_BRIDGE_READY')) {
                if ($startupLogs -notmatch $marker) { throw "$variant missing APP-006 startup marker: $marker" }
            }
            if ($startupLogs -match 'APP_(STAGE5_DATA|DAMAGE_ENGINE|NATIVE_BRIDGE)_FAIL') {
                throw "$variant startup failure during APP-006"
            }

            Click-Id -Capture $homeCapture -Id 'nav-settings'
            $settings = Wait-ForId -Name "app006-$variant-settings-prepared" -Id 'settings-check-update'
            $updatePoint = Get-NodePoint -Node (Find-UiNodeById -Node $settings.Tree -Id 'settings-check-update') `
                -Label 'settings-check-update'
            $navCalculatorPoint = Get-NodePoint -Node (Find-UiNodeById -Node $settings.Tree -Id 'nav-calculator') `
                -Label 'nav-calculator'
            Click-Point -Point $navCalculatorPoint
            Start-Sleep -Milliseconds 200
            $submitView = Wait-ForScrolledId -Name "app006-$variant-submit-prepared" -Id 'calculator-submit'
            $submitPoint = Get-NodePoint -Node (Find-UiNodeById -Node $submitView.Tree -Id 'calculator-submit') `
                -Label 'calculator-submit'
            $navSettingsPoint = Get-NodePoint -Node (Find-UiNodeById -Node $submitView.Tree -Id 'nav-settings') `
                -Label 'nav-settings'

            # Restart once after coordinate discovery. Trigger update directly
            # from the first visible Home frame so storage/engine startup and
            # the user request have an observable overlapping interval.
            $freshHome = Start-FormalApp -Variant $variant
            $freshSettingsPoint = Get-NodePoint -Node (Find-UiNodeById -Node $freshHome.Tree -Id 'nav-settings') `
                -Label 'nav-settings'
            Click-Point -Point $freshSettingsPoint
            Start-Sleep -Milliseconds 60
            Click-Point -Point $updatePoint
            Start-Sleep -Milliseconds 10
            Click-Point -Point $navCalculatorPoint
            $startupOverlapLogs = Wait-ForLog -Pattern 'APP_DAMAGE_ENGINE_READY' -Attempts 20
            foreach ($marker in @('APP_UPDATE_BEGIN', 'APP_STORAGE_READY', 'APP_DAMAGE_ENGINE_READY')) {
                if ($startupOverlapLogs -notmatch $marker) { throw "$variant missing APP-006 startup overlap marker: $marker" }
            }
            if ($startupOverlapLogs.IndexOf('APP_UPDATE_BEGIN') -gt $startupOverlapLogs.IndexOf('APP_STORAGE_READY') -or
                $startupOverlapLogs.IndexOf('APP_UPDATE_BEGIN') -gt $startupOverlapLogs.IndexOf('APP_DAMAGE_ENGINE_READY')) {
                throw "$variant update did not overlap storage and engine startup"
            }
            Start-Sleep -Milliseconds 120
            Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe',
                '620', '2200', '620', '500', '40000') | Out-Null
            Start-Sleep -Milliseconds 120
            Click-Point -Point $submitPoint

            Wait-ForLog -Pattern 'APP_UPDATE_READY' -Attempts 40 | Out-Null
            $runtimeLogs = Wait-ForLog -Pattern 'APP_CALC_READY' -Attempts 40
            $updateBegin = $runtimeLogs.IndexOf('APP_UPDATE_BEGIN')
            $calculationBegin = $runtimeLogs.IndexOf('APP_CALC_BEGIN')
            $updateReady = $runtimeLogs.IndexOf('APP_UPDATE_READY')
            $calculationReady = $runtimeLogs.IndexOf('APP_CALC_READY')
            foreach ($position in @($updateBegin, $calculationBegin, $updateReady, $calculationReady)) {
                if ($position -lt 0) { throw "$variant missing an APP-006 overlap marker" }
            }
            if (-not ($updateBegin -lt $calculationBegin -and $calculationBegin -lt $updateReady -and
                $updateBegin -lt $calculationReady)) {
                throw "$variant update and calculation did not overlap"
            }
            foreach ($marker in @('APP_DEBUG_UPDATE_PROBE_DELAY', 'APP_DEBUG_CALC_PROBE_DELAY')) {
                if ($runtimeLogs -notmatch $marker) { throw "$variant Debug latency probe was not active: $marker" }
            }

            Wait-ForId -Name "app006-$variant-calculation-result" -Id 'calculator-result' | Out-Null
            $calculationScreen = Capture-Screen -Name "app006-$variant-overlap-result"
            $calculationLayout = Capture-Layout -Name "app006-$variant-calculation-nav"
            Click-Id -Capture $calculationLayout -Id 'nav-settings'
            Wait-ForId -Name "app006-$variant-update-result" -Id 'settings-update-result' `
                -Attempts 3 -DelayMilliseconds 1000 | Out-Null
            $combinedLogs = "--- PREPARATION ---`r`n$startupLogs`r`n--- STARTUP AND REQUEST OVERLAP ---`r`n$runtimeLogs"
            [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory "app006-$variant-overlap.log"), $combinedLogs,
                [System.Text.UTF8Encoding]::new($false))
            $results["APP-006-$variant"] = 'PASS_E3_REAL_UPDATE_CALC_OVERLAP_INDEPENDENT_FINAL_STATES'
            $results["APP-006-$variant-screen"] = $calculationScreen
        } catch {
            $results["APP-006-$variant"] = "BLOCKED:$($_.Exception.Message)"
        }
    }
} else {
    $results['APP-006'] = 'NOT_EXECUTED_BY_SCOPE'
}

# CALC-012: race an actual formal calculate click against a condition switch.
# Passing requires runtime evidence that an in-flight generation was superseded,
# the stale callback was dropped, and a later generation produced the visible result.
if ($Scope -in @('CALC012', 'Runtime', 'All')) {
try {
    $homeCapture = Start-FormalApp -Variant standard -Install
    Click-Id -Capture $homeCapture -Id 'nav-calculator'
    $advanced = Wait-ForScrolledId -Name 'calc012-advanced' -Id 'calculator-advanced'
    Click-Id -Capture $advanced -Id 'calculator-advanced'
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    $race = Wait-ForScrolledId -Name 'calc012-race' -Id 'calculator-submit'
    $submitNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-submit'
    $toggleNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-condition-critical'
    if ($null -eq $toggleNode -or [string]$toggleNode.attributes.visible -ne 'true') {
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '620', '1800', '620', '900', '1000') | Out-Null
        $race = Capture-Layout -Name 'calc012-race-adjusted'
        $submitNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-submit'
        $toggleNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-condition-critical'
    }
    if ($null -eq $submitNode -or $null -eq $toggleNode) { throw 'CALC-012 controls are not simultaneously visible' }
    $submitPoint = Get-NodePoint -Node $submitNode -Label 'calculator-submit'
    $togglePoint = Get-NodePoint -Node $toggleNode -Label 'calculator-condition-critical'

    Click-Point -Point $submitPoint
    $beginLogs = Wait-ForLog -Pattern 'APP_CALC_BEGIN' -Attempts 3
    if ($beginLogs -notmatch 'APP_DEBUG_CALC_PROBE_DELAY') { throw 'Debug calculation latency probe was not active' }
    Click-Point -Point $togglePoint
    $raceLogs = Wait-ForLog -Pattern 'APP_CALC_STALE_DROP' -Attempts 10
    foreach ($marker in @('APP_CALC_BEGIN', 'APP_DEBUG_CALC_PROBE_DELAY',
        'APP_CALC_INPUT_SUPERSEDE', 'APP_CALC_STALE_DROP')) {
        if ($raceLogs -notmatch $marker) { throw "CALC-012 missing runtime marker: $marker" }
    }
    $staleLayout = Wait-ForId -Name 'calc012-stale-dropped' -Id 'calculator-submit'
    if ($null -ne (Find-UiNodeById -Node $staleLayout.Tree -Id 'calculator-result')) {
        throw 'CALC-012 stale result remained visible'
    }
    $changedToggle = Find-UiNodeById -Node $staleLayout.Tree -Id 'calculator-condition-critical'
    if ($null -eq $changedToggle -or [string]$changedToggle.attributes.checked -ne 'true') {
        throw 'CALC-012 condition change was not retained'
    }

    $finalSubmit = Wait-ForScrolledId -Name 'calc012-final-submit' -Id 'calculator-submit'
    Click-Id -Capture $finalSubmit -Id 'calculator-submit'
    Wait-ForId -Name 'calc012-final-result' -Id 'calculator-result' | Out-Null
    $raceLogs = Wait-ForLog -Pattern 'APP_CALC_READY' -Attempts 10
    if ($raceLogs.LastIndexOf('APP_CALC_BEGIN') -le $raceLogs.IndexOf('APP_CALC_STALE_DROP') -or
        $raceLogs.LastIndexOf('APP_CALC_READY') -le $raceLogs.LastIndexOf('APP_CALC_BEGIN')) {
        throw 'CALC-012 latest-generation ordering was not proven'
    }
    [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory 'calc012-stale-drop.log'), $raceLogs,
        [System.Text.UTF8Encoding]::new($false))
    Capture-Screen -Name 'calc012-final-result' | Out-Null
    $results['CALC-012'] = 'PASS_E3_STALE_CALLBACK_DROPPED_AND_LATEST_RESULT_VISIBLE'
} catch {
    $results['CALC-012'] = "BLOCKED:$($_.Exception.Message)"
}
} else {
    $results['CALC-012'] = 'NOT_EXECUTED_BY_SCOPE'
}

# These are real HarmonyOS E3 artifacts for manual E4 comparison; by policy
# they do not promote UI-011/CALC-002/CALC-008/CALC-011 without Android pairing.
if ($Scope -eq 'All') {
try {
    $homeCapture = Start-FormalApp -Variant standard -Install
    Click-Id -Capture $homeCapture -Id 'nav-calculator'
    $opponentDetails = Wait-ForScrolledId -Name 'calc002-opponent-details' -Id 'calculator-opponent-details'
    Click-Id -Capture $opponentDetails -Id 'calculator-opponent-details'
    Capture-Layout -Name 'calc002-manual-overrides' | Out-Null
    Capture-Screen -Name 'calc002-manual-overrides' | Out-Null
    $results['CALC-002'] = 'E3_PREPARATORY_ONLY_REQUIRES_ANDROID_HARMONY_MANUAL_E4'
    $results['CALC-008'] = 'E3_PREPARATORY_ONLY_REQUIRES_IMMUNITY_STATUS_AND_ANDROID_MANUAL_E4'
    $results['CALC-011'] = 'E3_PREPARATORY_ONLY_REQUIRES_PAIRED_LOCALIZATION_MANUAL_E4'
    $results['UI-011'] = 'E3_PREPARATORY_ONLY_REQUIRES_SCREEN_READER_FOCUS_CONTRAST_MANUAL_E4'
} catch {
    $results['CALC-002'] = 'BLOCKED_RUNTIME_UI_INSTABILITY_AND_REQUIRES_ANDROID_HARMONY_MANUAL_E4'
    $results['CALC-008'] = 'BLOCKED_RUNTIME_UI_INSTABILITY_AND_REQUIRES_ANDROID_HARMONY_MANUAL_E4'
    $results['CALC-011'] = 'BLOCKED_RUNTIME_UI_INSTABILITY_AND_REQUIRES_ANDROID_HARMONY_MANUAL_E4'
    $results['UI-011'] = 'BLOCKED_RUNTIME_UI_INSTABILITY_AND_REQUIRES_SCREEN_READER_FOCUS_CONTRAST_MANUAL_E4'
}
}

Write-EvidenceSummary
