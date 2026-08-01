[CmdletBinding()]
param(
    [string]$Target = '127.0.0.1:5555',
    [ValidateSet('standard', 'replay')][string]$Variant = 'standard',
    [ValidateSet('HOME', 'CALCULATOR', 'BATTLE')][string]$Page = 'HOME',
    [string[]]$ExpectedText = @(),
    [string[]]$ForbiddenText = @(),
    [string]$EvidenceName = 'formal-ui-smoke'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$hdc = Join-Path $localConfig.ToolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
$bundleName = [string]$config.bundleName
$product = $config.products.$Variant
$hap = Join-Path $repositoryRoot "harmonyos\app\dist\$($product.artifactName)-debug-unsigned.hap"
$evidenceDirectory = Join-Path $repositoryRoot 'harmonyos\app\evidence'

if (-not (Test-Path -LiteralPath $hdc)) { throw "HDC not found: $hdc" }
if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
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
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'dumpLayout', '-p', $remote, '-b', $bundleName) | Out-Null
    Invoke-TargetHdc -Arguments @('file', 'recv', $remote, $local) | Out-Null
    $raw = Get-Content -LiteralPath $local -Raw -Encoding utf8
    return [pscustomobject]@{ Path = $local; Raw = $raw; Tree = $raw | ConvertFrom-Json }
}

function Wait-LayoutForId {
    param([string]$Name, [string]$Id, [string[]]$Texts = @(), [switch]$ScrollForText, [int]$Attempts = 10)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name $Name
        $hasTexts = @($Texts | Where-Object { -not $capture.Raw.Contains($_) }).Count -eq 0
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id $Id) -and $hasTexts) { return $capture }
        if ($ScrollForText -and -not $hasTexts) {
            Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'swipe', '620', '2200', '620', '500', '1800') | Out-Null
        }
    }
    throw "Formal layout $Name did not contain $Id and all expected text"
}

function Click-NodeById {
    param($Capture, [string]$Id)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Missing formal UI node: $Id" }
    $bounds = [string]$node.attributes.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds for ${Id}: $bounds" }
    $x = [int](([int]$matches[1] + [int]$matches[3]) / 2)
    $y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'uiInput', 'click', [string]$x, [string]$y) | Out-Null
}

function Assert-UiNodeAttributes {
    param($Capture, [string]$Id, [hashtable]$Expected)
    $node = Find-UiNodeById -Node $Capture.Tree -Id $Id
    if ($null -eq $node) { throw "Missing formal UI node for attribute assertion: $Id" }
    foreach ($name in $Expected.Keys) {
        $actual = ([string]$node.attributes.$name).ToLowerInvariant()
        $wanted = ([string]$Expected[$name]).ToLowerInvariant()
        if ($actual -ne $wanted) {
            throw "$($Capture.Path) node $Id expected $name=$wanted but found $actual"
        }
    }
}

Invoke-TargetHdc -Arguments @('install', '-r', $hap) | Out-Null
Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
Start-Sleep -Seconds 1
Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null
$homeCapture = Wait-LayoutForId -Name "$EvidenceName-$Variant-home" -Id 'nav-home'
Assert-UiNodeAttributes -Capture $homeCapture -Id 'home-page' -Expected @{
    scrollable = 'false'; enabled = 'true'; visible = 'true'
}
Assert-UiNodeAttributes -Capture $homeCapture -Id 'nav-home' -Expected @{
    clickable = 'true'; enabled = 'true'; visible = 'true'
}

$capture = $homeCapture
if ($Page -eq 'CALCULATOR') {
    Click-NodeById -Capture $homeCapture -Id 'nav-calculator'
    $capture = Wait-LayoutForId -Name "$EvidenceName-$Variant-calculator" -Id 'calculator-page' -Texts $ExpectedText
    Assert-UiNodeAttributes -Capture $capture -Id 'calculator-page' -Expected @{
        scrollable = 'true'; enabled = 'true'; visible = 'true'
    }
    Assert-UiNodeAttributes -Capture $capture -Id 'nav-calculator' -Expected @{
        clickable = 'true'; enabled = 'true'; visible = 'true'
    }
} elseif ($Page -eq 'BATTLE') {
    Click-NodeById -Capture $homeCapture -Id 'nav-battle'
    # Assert the formal controls before scrolling for explanatory copy. The
    # HarmonyOS UI dump contains only nodes in the visible Scroll viewport, so
    # a successful text search near the bottom must not make the top controls
    # disappear from the semantic assertion capture.
    $controlsCapture = Wait-LayoutForId -Name "$EvidenceName-$Variant-battle-controls" -Id 'battle-page'
    Assert-UiNodeAttributes -Capture $controlsCapture -Id 'battle-page' -Expected @{
        scrollable = 'true'; enabled = 'true'; visible = 'true'
    }
    Assert-UiNodeAttributes -Capture $controlsCapture -Id 'battle-start-assistant' -Expected @{
        clickable = 'true'; enabled = 'true'; visible = 'true'
    }
    Assert-UiNodeAttributes -Capture $controlsCapture -Id 'battle-stop-assistant' -Expected @{
        clickable = 'false'; enabled = 'false'; visible = 'true'
    }
    if ($ExpectedText.Count -gt 0) {
        $capture = Wait-LayoutForId -Name "$EvidenceName-$Variant-battle" -Id 'battle-page' `
            -Texts $ExpectedText -ScrollForText
    } else {
        $capture = $controlsCapture
    }
} else {
    foreach ($expected in $ExpectedText) {
        if (-not $capture.Raw.Contains($expected)) { throw "$($capture.Path) does not contain: $expected" }
    }
}

foreach ($forbidden in $ForbiddenText) {
    if ($capture.Raw.Contains($forbidden)) { throw "$($capture.Path) unexpectedly contains: $forbidden" }
}
if ($capture.Raw -match 'Stage\d+Verification|stage\d+Verification') {
    throw "$Variant formal UI exposed an obsolete verification route"
}

[pscustomobject]@{ Variant = $Variant; Page = $Page; Status = 'PASS_FORMAL_UI_SMOKE'; Evidence = $capture.Path }
