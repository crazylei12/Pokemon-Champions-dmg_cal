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

if (-not (Test-Path -LiteralPath $hdc)) {
    throw "HDC not found: $hdc"
}
$connectedTargets = & $hdc list targets
if ($connectedTargets -notcontains $Target) {
    throw "HarmonyOS target is not connected: $Target"
}

function Invoke-TargetHdc {
    param([string[]]$Arguments)

    & $hdc -t $Target @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "HDC command failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
}

function Get-UiAttribute {
    param($Node)

    $values = @()
    if ($null -ne $Node.attributes -and -not [string]::IsNullOrWhiteSpace([string]$Node.attributes.id)) {
        $values += [string]$Node.attributes.id
    }
    foreach ($child in @($Node.children)) {
        $values += Get-UiAttribute -Node $child
    }
    return $values
}

$summaries = @()
foreach ($variantName in @('standard', 'replay')) {
    $variant = $config.products.$variantName
    $hapPath = Join-Path $repositoryRoot "harmonyos\app\dist\$($variant.artifactName)-debug-unsigned.hap"
    if (-not (Test-Path -LiteralPath $hapPath)) {
        throw "Missing debug HAP for emulator verification: $hapPath"
    }

    Invoke-TargetHdc -Arguments @('install', '-r', $hapPath)
    Invoke-TargetHdc -Arguments @('shell', 'hilog', '-r')
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'force-stop', $bundleName)
    Invoke-TargetHdc -Arguments @('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', $bundleName)
    Start-Sleep -Seconds 2

    $remoteBase = "/data/local/tmp/pc-stage2-$variantName"
    $screenshotPath = Join-Path $evidenceDirectory "pc-stage2-$variantName.png"
    $layoutPath = Join-Path $evidenceDirectory "pc-stage2-$variantName.json"
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'screenCap', '-p', "$remoteBase.png")
    Invoke-TargetHdc -Arguments @('shell', 'uitest', 'dumpLayout', '-p', "$remoteBase.json", '-a', '-b', $bundleName)
    Invoke-TargetHdc -Arguments @('file', 'recv', "$remoteBase.png", $screenshotPath)
    Invoke-TargetHdc -Arguments @('file', 'recv', "$remoteBase.json", $layoutPath)

    $layout = Get-Content -LiteralPath $layoutPath -Raw -Encoding utf8 | ConvertFrom-Json
    $visibleIds = @(Get-UiAttribute -Node $layout) | Select-Object -Unique
    if ($variantName -eq 'standard') {
        if ($visibleIds -notcontains 'variant-standard' -or $visibleIds -contains 'entry-replay') {
            throw "Standard UI feature gate failed: $($visibleIds -join ' | ')"
        }
    } else {
        if ($visibleIds -notcontains 'variant-replay' -or $visibleIds -notcontains 'entry-replay') {
            throw "Replay UI feature gate failed: $($visibleIds -join ' | ')"
        }
    }

    $logs = (& $hdc -t $Target shell hilog -T PCApp -x | Out-String)
    if ($logs -notmatch "APP_NATIVE_BRIDGE_READY variant=$variantName") {
        throw "$variantName native bridge readiness marker was not logged"
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $evidenceDirectory "pc-stage2-$variantName.log"),
        $logs,
        [System.Text.UTF8Encoding]::new($false)
    )

    $summaries += [pscustomobject]@{
        Variant = $variantName
        Target = $Target
        VisibleEntries = ($visibleIds -join ' | ')
        NativeBridge = 'PASS'
        Screenshot = $screenshotPath
        Layout = $layoutPath
    }
}

$summaries | Format-List
Write-Host 'HarmonyOS emulator shell verification PASS'
