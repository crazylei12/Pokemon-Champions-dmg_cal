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
$logPath = Join-Path $evidenceDirectory 'pc-stage5-main-ui.log'

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

function Find-UiNodeById {
    param($Node, [string]$Id)
    if ($null -ne $Node.attributes -and [string]$Node.attributes.id -eq $Id) { return $Node }
    foreach ($child in @($Node.children)) {
        $match = Find-UiNodeById -Node $child -Id $Id
        if ($null -ne $match) { return $match }
    }
    return $null
}

function Find-UiNodeByText {
    param($Node, [string]$Text)
    if ($null -ne $Node.attributes -and [string]$Node.attributes.text -eq $Text) { return $Node }
    foreach ($child in @($Node.children)) {
        $match = Find-UiNodeByText -Node $child -Text $Text
        if ($null -ne $match) { return $match }
    }
    return $null
}

function Capture-Layout {
    param([string]$Name)
    $remotePath = "/data/local/tmp/$Name.json"
    $localPath = Join-Path $evidenceDirectory "$Name.json"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'dumpLayout', '-p', $remotePath, '-a', '-b', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remotePath, $localPath) | Out-Null
    return [pscustomobject]@{
        Path = $localPath
        Raw = Get-Content -LiteralPath $localPath -Raw -Encoding utf8
        Tree = Get-Content -LiteralPath $localPath -Raw -Encoding utf8 | ConvertFrom-Json
    }
}

function Assert-Contains {
    param($Capture, [string]$Text)
    if (-not $Capture.Raw.Contains($Text)) { throw "Layout $($Capture.Path) does not contain: $Text" }
}

function ConvertFrom-UnicodeEscape {
    param([string]$Value)
    return [System.Text.RegularExpressions.Regex]::Unescape($Value)
}

function Assert-Node {
    param($Capture, [string]$Id)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Layout $($Capture.Path) does not contain node: $Id" }
    return $node
}

function Click-UiNode {
    param($Node, [string]$Label)
    $bounds = [string]$node.attributes.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds for ${Label}: $bounds" }
    $x = [int]( ([int]$Matches[1] + [int]$Matches[3]) / 2 )
    $y = [int]( ([int]$Matches[2] + [int]$Matches[4]) / 2 )
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$x, [string]$y) | Out-Null
    Start-Sleep -Milliseconds 800
}

function Click-Node {
    param($Capture, [string]$Id)
    $node = Assert-Node -Capture $Capture -Id $Id
    Click-UiNode -Node $node -Label $Id
}

function Click-Text {
    param($Capture, [string]$Text)
    $node = Find-UiNodeByText -Node $Capture.Tree -Text $Text
    if ($null -eq $node) { throw "Layout $($Capture.Path) does not contain text node: $Text" }
    Click-UiNode -Node $node -Label $Text
}

function Wait-AppReady {
    $logs = ''
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Start-Sleep -Seconds 1
        $logs = (Invoke-TargetHdc -Arguments @('shell', 'hilog', '-T', 'PCApp', '-x') | Out-String)
        if ($logs -match 'APP_STAGE5_DATA_FAIL') { throw "Stage 5 application data load failed:`n$logs" }
        if ($logs -match 'APP_STAGE5_DATA_READY' -and $logs -match 'APP_DAMAGE_ENGINE_READY') { return $logs }
    }
    throw "Stage 5 app did not become ready within 15 seconds:`n$logs"
}

function Start-NormalApp {
    param([switch]$SelectReplayRecognition)

    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r') | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
    if ($SelectReplayRecognition) {
        $launch = $null
        for ($attempt = 0; $attempt -lt 15; $attempt++) {
            Start-Sleep -Milliseconds 500
            $launch = Capture-Layout -Name 'pc-stage5-replay-launch'
            if ($null -ne (Find-UiNodeById -Node $launch.Tree -Id 'replay-mode-recognition')) { break }
        }
        if ($null -eq $launch -or $null -eq (Find-UiNodeById -Node $launch.Tree -Id 'replay-mode-recognition')) {
            throw 'Replay mode selector did not expose the recognition-only route.'
        }
        Click-Node -Capture $launch -Id 'replay-mode-recognition'
    }
    return Wait-AppReady
}

$standardLogs = ''
$replayLogs = ''
try {
    Invoke-TargetHdc -Arguments @('install', '-r', $standardHap) | Out-Null
    $standardLogs = Start-NormalApp
    $homeCapture = Capture-Layout -Name 'pc-stage5-standard-home'
    foreach ($id in @('nav-home', 'nav-calculator', 'nav-battle', 'nav-settings',
        'home-start-calculator', 'home-manage-presets')) { Assert-Node -Capture $homeCapture -Id $id | Out-Null }
    foreach ($escaped in @('Champions', '\u79bb\u7ebf\u4f24\u5bb3\u8ba1\u7b97\u5668 \u00b7 \u6807\u51c6\u7248',
        '\u672c\u5730\u4f24\u5bb3\u5f15\u64ce\u5df2\u5c31\u7eea')) {
        Assert-Contains -Capture $homeCapture -Text (ConvertFrom-UnicodeEscape $escaped)
    }

    Click-Node -Capture $homeCapture -Id 'home-manage-presets'
    $presets = Capture-Layout -Name 'pc-stage5-standard-presets'
    foreach ($id in @('preset-create', 'preset-search')) { Assert-Node -Capture $presets -Id $id | Out-Null }
    Assert-Contains -Capture $presets -Text (ConvertFrom-UnicodeEscape '\u6211\u4fdd\u5b58\u7684\u5b9d\u53ef\u68a6\u914d\u7f6e')

    Click-Text -Capture $presets -Text (ConvertFrom-UnicodeEscape '\u8fd4\u56de')
    $homeAfterPresets = Capture-Layout -Name 'pc-stage5-standard-home-after-presets'
    Click-Node -Capture $homeAfterPresets -Id 'nav-calculator'
    $calculator = Capture-Layout -Name 'pc-stage5-standard-calculator'
    foreach ($escaped in @('\u81ea\u7531\u4f24\u5bb3\u8ba1\u7b97', '\u76ae\u5361\u4e18', '\u7ea2\u83b2\u94e0\u9a91')) {
        Assert-Contains -Capture $calculator -Text (ConvertFrom-UnicodeEscape $escaped)
    }

    $submitCapture = $calculator
    for ($attempt = 0; $attempt -lt 9; $attempt++) {
        $submit = Find-UiNodeById -Node $submitCapture.Tree -Id 'calculator-submit'
        if ($null -ne $submit -and [string]$submit.attributes.enabled -eq 'true') { break }
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '620', '2200', '620', '500', '3500') | Out-Null
        Start-Sleep -Milliseconds 300
        $submitCapture = Capture-Layout -Name "pc-stage5-standard-calculator-scroll-$attempt"
    }
    if ($null -eq (Find-UiNodeById -Node $submitCapture.Tree -Id 'calculator-submit')) {
        throw 'Calculator submit button was not reachable by scrolling.'
    }
    Click-Node -Capture $submitCapture -Id 'calculator-submit'

    $resultCapture = Capture-Layout -Name 'pc-stage5-standard-calculation-result-0'
    $resultTitle = ConvertFrom-UnicodeEscape '\u7ed3\u679c\uff1a\u6211\u65b9\u8f93\u51fa'
    for ($attempt = 1; $attempt -le 10 -and -not $resultCapture.Raw.Contains($resultTitle); $attempt++) {
        Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '620', '2200', '620', '500', '3500') | Out-Null
        Start-Sleep -Milliseconds 300
        $resultCapture = Capture-Layout -Name "pc-stage5-standard-calculation-result-$attempt"
    }
    Assert-Contains -Capture $resultCapture -Text $resultTitle
    if ($resultCapture.Raw -notmatch '\d+\.\d+% . \d+\.\d+%') { throw 'Calculator result does not contain a percentage range.' }
    $calculationPath = Join-Path $evidenceDirectory 'pc-stage5-standard-calculation-result.json'
    [System.IO.File]::WriteAllText($calculationPath, $resultCapture.Raw, [System.Text.UTF8Encoding]::new($false))

    Click-Node -Capture $resultCapture -Id 'nav-battle'
    $battle = Capture-Layout -Name 'pc-stage5-standard-battle'
    Assert-Node -Capture $battle -Id 'battle-page' | Out-Null
    foreach ($escaped in @('\u5bf9\u5c40\u52a9\u624b', '\u5f55\u5165\u6211\u7684\u961f\u4f0d', '\u5f00\u59cb\u4e00\u573a\u5bf9\u5c40')) {
        Assert-Contains -Capture $battle -Text (ConvertFrom-UnicodeEscape $escaped)
    }

    Click-Node -Capture $battle -Id 'nav-settings'
    $settings = Capture-Layout -Name 'pc-stage5-standard-settings'
    foreach ($id in @('settings-page', 'settings-check-update', 'settings-export-backup')) { Assert-Node -Capture $settings -Id $id | Out-Null }
    foreach ($escaped in @('\u8bbe\u7f6e\u4e0e\u8bca\u65ad', '\u6807\u51c6\u7248 \u00b7 1.1.4\uff089\uff09', '\u6570\u636e\u5907\u4efd\u4e0e\u6062\u590d')) {
        Assert-Contains -Capture $settings -Text (ConvertFrom-UnicodeEscape $escaped)
    }

    Invoke-TargetHdc -Arguments @('install', '-r', $replayHap) | Out-Null
    $replayLogs = Start-NormalApp -SelectReplayRecognition
    $replayHome = Capture-Layout -Name 'pc-stage5-replay-home'
    foreach ($id in @('nav-home', 'nav-calculator', 'nav-battle', 'nav-settings')) { Assert-Node -Capture $replayHome -Id $id | Out-Null }
    Assert-Contains -Capture $replayHome -Text (ConvertFrom-UnicodeEscape '\u79bb\u7ebf\u4f24\u5bb3\u8ba1\u7b97\u5668 \u00b7 \u5f55\u5c4f\u529f\u80fd\u7248')

    $filteredLogs = ((($standardLogs + $replayLogs) -split "`r?`n") | Where-Object {
        $_ -match 'APP_(STAGE5_DATA|DAMAGE_ENGINE|NATIVE_BRIDGE)_(READY|FAIL)'
    }) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($logPath, $filteredLogs + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))

    [pscustomobject]@{
        Status = 'PASS'
        Target = $Target
        StandardHome = $homeCapture.Path
        Presets = $presets.Path
        Calculation = $calculationPath
        Battle = $battle.Path
        Settings = $settings.Path
        ReplayHome = $replayHome.Path
        Log = $logPath
    } | Format-List
    Write-Host 'HarmonyOS Stage 5 main UI verification PASS'
} finally {
    $temporaryNames = @('pc-stage5-replay-launch', 'pc-stage5-standard-calculator',
        'pc-stage5-standard-home-after-presets')
    for ($index = 0; $index -le 10; $index++) {
        $temporaryNames += "pc-stage5-standard-calculator-scroll-$index"
        $temporaryNames += "pc-stage5-standard-calculation-result-$index"
    }
    foreach ($name in $temporaryNames) {
        $temporary = Join-Path $evidenceDirectory "$name.json"
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
    & $hdc -t $Target install -r $standardHap | Out-Null
    & $hdc -t $Target shell aa force-stop $bundleName | Out-Null
    & $hdc -t $Target shell aa start -a EntryAbility -b $bundleName | Out-Null
}
