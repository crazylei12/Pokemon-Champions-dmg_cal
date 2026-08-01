[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$toolchainRoot = $localConfig.ToolchainRoot

if (-not $toolchainRoot.StartsWith('D:\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "HarmonyOS toolchain must be on D:; configured root is $toolchainRoot"
}

$paths = [ordered]@{
    DevEcoStudio = $toolchainRoot
    Sdk = Join-Path $toolchainRoot 'sdk'
    Hvigor = Join-Path $toolchainRoot 'tools\hvigor\bin\hvigorw.bat'
    Ohpm = Join-Path $toolchainRoot 'tools\ohpm\bin\ohpm.bat'
    Node = Join-Path $toolchainRoot 'tools\node\node.exe'
    Java = Join-Path $toolchainRoot 'jbr\bin\java.exe'
    Hdc = Join-Path $toolchainRoot 'sdk\default\openharmony\toolchains\hdc.exe'
    NativeSdk = Join-Path $toolchainRoot 'sdk\default\hms\native'
    OpenCvSource = $localConfig.OpenCvSource
    OpenCvCoreArm64 = Join-Path $localConfig.OpenCvBuildArm64 'lib\libopencv_core.a'
    OpenCvCoreX64 = Join-Path $localConfig.OpenCvBuildX64 'lib\libopencv_core.a'
}

foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) {
        throw "Missing $($entry.Key): $($entry.Value)"
    }
}

$env:DEVECO_SDK_HOME = $paths.Sdk
$env:JAVA_HOME = Join-Path $toolchainRoot 'jbr'
$env:HARMONY_OPENCV_SOURCE = $localConfig.OpenCvSource
$env:HARMONY_OPENCV_BUILD_ARM64 = $localConfig.OpenCvBuildArm64
$env:HARMONY_OPENCV_BUILD_X64 = $localConfig.OpenCvBuildX64
$env:Path = "$(Split-Path -Parent $paths.Node);$(Split-Path -Parent $paths.Hvigor);$(Split-Path -Parent $paths.Ohpm);$(Split-Path -Parent $paths.Hdc);$env:Path"

$actualHvigor = (& $paths.Hvigor --version | Select-Object -Last 1).Trim()
$actualOhpm = (& $paths.Ohpm --version | Select-Object -Last 1).Trim()
$actualNode = ((& $paths.Node --version).Trim() -replace '^v', '')
$javaRelease = Get-Content -LiteralPath (Join-Path $toolchainRoot 'jbr\release') -Raw -Encoding utf8
$javaMatch = [regex]::Match($javaRelease, 'JAVA_VERSION="([^"]+)"')
$actualJava = if ($javaMatch.Success) { $javaMatch.Groups[1].Value } else { 'unknown' }
$productInfo = Get-Content -LiteralPath (Join-Path $toolchainRoot 'product-info.json') -Raw -Encoding utf8 | ConvertFrom-Json
$etsSdk = Get-Content -LiteralPath (Join-Path $toolchainRoot 'sdk\default\hms\ets\uni-package.json') -Raw -Encoding utf8 | ConvertFrom-Json
$nativeSdk = Get-Content -LiteralPath (Join-Path $toolchainRoot 'sdk\default\hms\native\uni-package.json') -Raw -Encoding utf8 | ConvertFrom-Json
if ($etsSdk.version -ne $config.sdkBuild -or $nativeSdk.version -ne $config.sdkBuild) {
    throw "HarmonyOS SDK build mismatch: expected $($config.sdkBuild), ETS=$($etsSdk.version), Native=$($nativeSdk.version)"
}
if ($etsSdk.apiVersion -ne '24' -or $nativeSdk.apiVersion -ne '24') {
    throw "HarmonyOS SDK API mismatch: ETS=$($etsSdk.apiVersion), Native=$($nativeSdk.apiVersion)"
}

$expected = [ordered]@{
    DevEcoStudio = [string]$config.toolchain.devecoStudio
    Hvigor = [string]$config.toolchain.hvigor
    Ohpm = [string]$config.toolchain.ohpm
    Node = [string]$config.toolchain.node
    Java = [string]$config.toolchain.java
}
$actual = [ordered]@{
    DevEcoStudio = [string]$productInfo.version
    Hvigor = $actualHvigor
    Ohpm = $actualOhpm
    Node = $actualNode
    Java = $actualJava
}

foreach ($name in $expected.Keys) {
    if ($actual[$name] -ne $expected[$name]) {
        throw "$name version mismatch: expected $($expected[$name]), got $($actual[$name])"
    }
}

[pscustomobject]@{
    Status = 'PASS'
    ToolchainRoot = $toolchainRoot
    Sdk = [string]$config.sdk
    SdkBuild = [string]$config.sdkBuild
    DevEcoStudio = $actual.DevEcoStudio
    Hvigor = $actual.Hvigor
    Ohpm = $actual.Ohpm
    Node = $actual.Node
    Java = $actual.Java
    Abis = ($config.toolchain.abis -join ',')
    LocalConfig = $localConfig.Source
    OpenCvSource = $localConfig.OpenCvSource
} | Format-List
