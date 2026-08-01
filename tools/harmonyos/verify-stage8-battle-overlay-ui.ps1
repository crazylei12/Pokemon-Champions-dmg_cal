[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$toolchainRoot = $localConfig.ToolchainRoot
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
    param([string]$Name, [string]$Id, [int]$Attempts = 30)
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        Start-Sleep -Milliseconds 500
        $capture = Capture-Layout -Name $Name
        if ($null -ne (Find-UiNodeById -Node $capture.Tree -Id $Id)) { return $capture }
    }
    throw "Formal layout $Name did not contain $Id"
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

$summaries = @()
foreach ($variantName in @('standard', 'replay')) {
    $variant = $config.products.$variantName
    $hap = Join-Path $repositoryRoot "harmonyos\app\dist\$($variant.artifactName)-debug-unsigned.hap"
    if (-not (Test-Path -LiteralPath $hap)) { throw "Missing debug HAP: $hap" }
    Invoke-TargetHdc -Arguments @('install', '-r', $hap) | Out-Null
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName) | Out-Null
    Start-Sleep -Seconds 1
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName) | Out-Null

    $homeCapture = Wait-LayoutForId -Name "pc-stage8-$variantName-formal-home" -Id 'nav-battle'
    if ($homeCapture.Raw -match 'Stage\d+Verification|stage\d+Verification') {
        throw "$variantName formal UI exposed a verification route"
    }
    Click-NodeById -Capture $homeCapture -Id 'nav-battle'
    $battle = Wait-LayoutForId -Name "pc-stage8-$variantName-formal-battle" -Id 'battle-page'
    if (-not $battle.Raw.Contains([regex]::Unescape('\u542f\u52a8\u5bf9\u5c40\u52a9\u624b'))) {
        throw "$variantName formal battle assistant entry is missing"
    }
    $summaries += [pscustomobject]@{
        Variant = $variantName
        FormalHome = 'PASS'
        FormalBattleEntry = 'PASS'
        PanelHud = 'BLOCKED_USER_AUTH_AND_CONFIRMED_BATTLE'
        PrivacyPromptClicked = $false
        HomeLayout = $homeCapture.Path
        BattleLayout = $battle.Path
    }
}

Write-Warning 'This script intentionally does not automate screen-capture privacy prompts. Panel/HUD E5 remains blocked until a user-authorized confirmed battle is exercised on a real device.'
$summaries | Format-Table -AutoSize
