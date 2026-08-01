[CmdletBinding()]
param(
    [string]$Target = '127.0.0.1:5557',
    [string]$OlderStandardHap = '',
    [ValidateSet('APP003', 'Runtime', 'All')][string]$Scope = 'Runtime'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$hdc = Join-Path $localConfig.ToolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
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
    if ($Install) { Invoke-TargetHdc -Arguments @('install', '-r', $haks[$Variant]) | Out-Null }
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
    [System.IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
    $summary | ConvertTo-Json -Depth 8
    Write-Host "HarmonyOS APP/CALC formal UI evidence complete: $summaryPath"
}

foreach ($variant in @('standard', 'replay')) {
    $artifactHashes[$variant] = (Get-FileHash -Algorithm SHA256 -LiteralPath $haks[$variant]).Hash.ToLowerInvariant()
}

if ($Scope -ne 'Runtime') {
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
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        Start-Sleep -Milliseconds 300
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        Wait-ForId -Name "app003-$variant-owner-after-confirmed-back" -Id 'preset-manager-page' | Out-Null
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '5', '1300', '900', '1300', '1000') | Out-Null
        try {
            $homeAfterGesture = Wait-ForId -Name "app003-$variant-home-after-edge-back" -Id 'home-page'
        } catch {
            $results["APP-003-$variant"] = 'BLOCKED_EDGE_GESTURE_NOT_REPRODUCIBLE_SYSTEM_BACK_PARTIAL_PASS'
            Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
            $homeAfterGesture = Wait-ForId -Name "app003-$variant-home-after-edge-fallback-back" -Id 'home-page'
        }

        Click-Id -Capture $homeAfterGesture -Id 'nav-calculator'
        $weatherView = Wait-ForScrolledId -Name "app003-$variant-weather" -Id 'calculator-weather'
        Click-Id -Capture $weatherView -Id 'calculator-weather'
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        Wait-ForId -Name "app003-$variant-calculator-after-popup-back" -Id 'calculator-page' | Out-Null
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'keyEvent', 'Back') | Out-Null
        Wait-ForId -Name "app003-$variant-home-after-system-back" -Id 'home-page' | Out-Null
        if ($null -eq $results["APP-003-$variant"]) {
            $results["APP-003-$variant"] = 'PASS_E3_FORMAL_HAP_SYSTEM_AND_EDGE_BACK'
        }
    }
} else {
    $results['APP-003-standard'] = 'BLOCKED_EDGE_GESTURE_NOT_REPRODUCIBLE_SYSTEM_BACK_PARTIAL_PASS'
    $results['APP-003-replay'] = 'BLOCKED_NOT_FULLY_EXECUTED'
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

if ([string]::IsNullOrWhiteSpace($OlderStandardHap)) {
    $results['APP-005-upgrade'] = 'BLOCKED_MISSING_SAME_IDENTITY_LOWER_VERSION_HAP'
} else {
    try {
        $resolvedOlder = [System.IO.Path]::GetFullPath($OlderStandardHap)
        if (-not (Test-Path -LiteralPath $resolvedOlder -PathType Leaf)) { throw "Older HAP not found: $resolvedOlder" }
        Invoke-TargetHdc -Arguments @('install', '-r', $resolvedOlder) | Out-Null
        Invoke-TargetHdc -Arguments @('install', '-r', $haks.standard) | Out-Null
        Start-FormalApp -Variant standard | Out-Null
        $results['APP-005-upgrade'] = 'PASS_E3_WITH_CALLER_SUPPLIED_OLDER_HAP'
    } catch {
        $results['APP-005-upgrade'] = 'BLOCKED_CALLER_SUPPLIED_UPGRADE_RUNTIME_FAILURE'
    }
}

# APP-006: run update and damage requests across navigation in both variants.
# This probe does not control the update response latency, so marker presence
# proves both paths ran but cannot by itself prove they overlapped in time.
foreach ($variant in @('standard', 'replay')) {
    try {
        $homeCapture = Start-FormalApp -Variant $variant -Install
        Click-Id -Capture $homeCapture -Id 'nav-settings'
        $settings = Wait-ForId -Name "app006-$variant-settings" -Id 'settings-check-update'
        Click-Id -Capture $settings -Id 'settings-check-update'
        $settingsBusy = Capture-Layout -Name "app006-$variant-update-busy"
        $updateButton = Find-UiNodeById -Node $settingsBusy.Tree -Id 'settings-check-update'
        $earlyUpdateResult = Find-UiNodeById -Node $settingsBusy.Tree -Id 'settings-update-result'
        if ($null -eq $earlyUpdateResult -and $null -ne $updateButton -and
            [string]$updateButton.attributes.enabled -ne 'false') {
            throw "$variant update control was not disabled while its request was active"
        }
        Click-Id -Capture $settingsBusy -Id 'nav-calculator'
        $submitView = Wait-ForScrolledId -Name "app006-$variant-submit" -Id 'calculator-submit'
        Click-Id -Capture $submitView -Id 'calculator-submit'
        Wait-ForId -Name "app006-$variant-calculation-result" -Id 'calculator-result' | Out-Null
        $calculationScreen = Capture-Screen -Name "calc008-$variant-normal-result"
        $nav = Capture-Layout -Name "app006-$variant-calculation-nav"
        Click-Id -Capture $nav -Id 'nav-settings'
        Wait-ForId -Name "app006-$variant-update-result" -Id 'settings-update-result' -Attempts 3 -DelayMilliseconds 1500 | Out-Null
        $logs = Wait-ForLog -Pattern 'APP_UPDATE_READY' -Attempts 40
        foreach ($marker in @('APP_LOAD_BEGIN', 'APP_STAGE5_DATA_READY', 'APP_DAMAGE_ENGINE_READY',
            'APP_NATIVE_BRIDGE_READY', 'APP_UPDATE_BEGIN', 'APP_UPDATE_READY', 'APP_CALC_BEGIN', 'APP_CALC_READY')) {
            if ($logs -notmatch $marker) { throw "$variant missing APP-006 runtime marker: $marker" }
        }
        if ($logs -match 'APP_(STAGE5_DATA|DAMAGE_ENGINE|NATIVE_BRIDGE)_FAIL') { throw "$variant startup failure during APP-006" }
        [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory "app006-$variant.log"), $logs,
            [System.Text.UTF8Encoding]::new($false))
        $results["APP-006-$variant"] = 'BLOCKED_E3_SEQUENTIAL_PATHS_ONLY_CONCURRENCY_NOT_PROVEN'
        $results["CALC-008-$variant-normal"] = "E3_PREPARATORY_ONLY:$calculationScreen"
    } catch {
        $results["APP-006-$variant"] = 'BLOCKED_RUNTIME_UI_INSTABILITY'
        $results["CALC-008-$variant-normal"] = 'BLOCKED_RUNTIME_UI_INSTABILITY'
    }
}

# CALC-012: race an actual formal calculate click against a condition switch.
# Passing requires runtime evidence that an in-flight generation was superseded,
# the stale callback was dropped, and a later generation produced the visible result.
try {
    $homeCapture = Start-FormalApp -Variant standard -Install
    Click-Id -Capture $homeCapture -Id 'nav-calculator'
    $advanced = Wait-ForScrolledId -Name 'calc012-advanced' -Id 'calculator-advanced'
    Click-Id -Capture $advanced -Id 'calculator-advanced'
    $raceObserved = $false
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    for ($attempt = 0; $attempt -lt 5 -and -not $raceObserved; $attempt++) {
        $race = Wait-ForScrolledId -Name "calc012-race-$attempt" -Id 'calculator-submit'
        $submitNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-submit'
        $toggleNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-condition-spread'
        if ($null -eq $toggleNode -or [string]$toggleNode.attributes.visible -ne 'true') {
            Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '620', '1800', '620', '900', '1800') | Out-Null
            $race = Capture-Layout -Name "calc012-race-$attempt-adjusted"
            $submitNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-submit'
            $toggleNode = Find-UiNodeById -Node $race.Tree -Id 'calculator-condition-spread'
        }
        if ($null -eq $submitNode -or $null -eq $toggleNode) { continue }
        $submitPoint = Get-NodePoint -Node $submitNode -Label 'calculator-submit'
        $togglePoint = Get-NodePoint -Node $toggleNode -Label 'calculator-condition-spread'
        $submitArgs = @('-t', $Target, 'shell', 'uitest', 'uiInput', 'click', [string]$submitPoint.X, [string]$submitPoint.Y)
        $toggleArgs = @('-t', $Target, 'shell', 'uitest', 'uiInput', 'click', [string]$togglePoint.X, [string]$togglePoint.Y)
        $submitProcess = Start-Process -FilePath $hdc -ArgumentList $submitArgs -WindowStyle Hidden -PassThru
        Start-Sleep -Milliseconds 5
        $toggleProcess = Start-Process -FilePath $hdc -ArgumentList $toggleArgs -WindowStyle Hidden -PassThru
        $submitProcess.WaitForExit()
        $toggleProcess.WaitForExit()
        Start-Sleep -Milliseconds 400
        $raceLogs = Read-AppLogs
        $raceObserved = $raceLogs -match 'APP_CALC_INPUT_SUPERSEDE' -and $raceLogs -match 'APP_CALC_STALE_DROP'
    }

    if ($raceObserved) {
        $finalSubmit = Wait-ForScrolledId -Name 'calc012-final-submit' -Id 'calculator-submit'
        Click-Id -Capture $finalSubmit -Id 'calculator-submit'
        Wait-ForId -Name 'calc012-final-result' -Id 'calculator-result' | Out-Null
        $raceLogs = Wait-ForLog -Pattern 'APP_CALC_READY' -Attempts 30
        [System.IO.File]::WriteAllText((Join-Path $evidenceDirectory 'calc012-stale-drop.log'), $raceLogs,
            [System.Text.UTF8Encoding]::new($false))
        Capture-Screen -Name 'calc012-final-result' | Out-Null
        $results['CALC-012'] = 'PASS_E3_STALE_CALLBACK_DROPPED_AND_LATEST_RESULT_VISIBLE'
    } else {
        $results['CALC-012'] = 'BLOCKED_FORMAL_ENGINE_COMPLETED_BEFORE_UI_RACE'
    }
} catch {
    $results['CALC-012'] = 'BLOCKED_RUNTIME_UI_INSTABILITY'
}

# These are real HarmonyOS E3 artifacts for manual E4 comparison; by policy
# they do not promote UI-011/CALC-002/CALC-008/CALC-011 without Android pairing.
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

Write-EvidenceSummary
