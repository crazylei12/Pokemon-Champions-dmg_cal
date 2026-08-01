[CmdletBinding()]
param(
    [ValidateSet('standard', 'replay', 'all')]
    [string]$Variant = 'all',
    [ValidateSet('debug', 'release')]
    [string]$BuildMode = 'debug',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$projectRoot = Join-Path $repositoryRoot 'harmonyos\app'
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$toolchainRoot = [System.IO.Path]::GetFullPath(($config.toolchain.root -replace '/', '\'))
$node = Join-Path $toolchainRoot 'tools\node\node.exe'
$hvigor = Join-Path $toolchainRoot 'tools\hvigor\bin\hvigorw.bat'

& (Join-Path $PSScriptRoot 'check-app-environment.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "HarmonyOS environment check failed with exit code $LASTEXITCODE"
}

& $node (Join-Path $PSScriptRoot 'package-app-assets.mjs')
if ($LASTEXITCODE -ne 0) {
    throw "HarmonyOS asset packaging failed with exit code $LASTEXITCODE"
}

$env:DEVECO_SDK_HOME = Join-Path $toolchainRoot 'sdk'
$env:JAVA_HOME = Join-Path $toolchainRoot 'jbr'
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$(Split-Path -Parent $node);$(Split-Path -Parent $hvigor);$env:Path"

$variants = if ($Variant -eq 'all') { @('standard', 'replay') } else { @($Variant) }
$first = $true
Push-Location -LiteralPath $projectRoot
try {
    foreach ($currentVariant in $variants) {
        $product = [string]$config.products.$currentVariant.hvigorProduct
        $tasks = [System.Collections.Generic.List[string]]::new()
        $tasks.Add('--no-daemon')
        $tasks.Add('--generate-build-profile')
        $tasks.Add('-p')
        $tasks.Add("product=$product")
        $tasks.Add('-p')
        $tasks.Add("buildMode=$BuildMode")
        $tasks.Add('--mode')
        $tasks.Add('module')
        if ($Clean -and $first) {
            $tasks.Add('clean')
        }
        $tasks.Add('assembleHap')

        Write-Host "Building HarmonyOS $currentVariant product ($product, $BuildMode)..."
        & $hvigor @tasks
        if ($LASTEXITCODE -ne 0) {
            throw "Hvigor build failed for $currentVariant with exit code $LASTEXITCODE"
        }
        $hapSource = Join-Path $projectRoot "entry\build\$product\outputs\default\entry-default-unsigned.hap"
        if (-not (Test-Path -LiteralPath $hapSource)) {
            throw "Expected HAP was not produced: $hapSource"
        }
        $distDirectory = Join-Path $projectRoot 'dist'
        New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null
        $artifactName = [string]$config.products.$currentVariant.artifactName
        $hapDestination = Join-Path $distDirectory "$artifactName-$BuildMode-unsigned.hap"
        Copy-Item -LiteralPath $hapSource -Destination $hapDestination -Force
        Write-Host "Saved $currentVariant HAP: $hapDestination"
        $first = $false
    }
} finally {
    Pop-Location
}

Write-Host "HarmonyOS build PASS: $($variants -join ', ')"
