[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
$localPath = Join-Path $RepositoryRoot 'config\harmonyos-local.json'
if (Test-Path -LiteralPath $localPath -PathType Leaf) {
    $local = Get-Content -LiteralPath $localPath -Raw -Encoding utf8 | ConvertFrom-Json
    if ([int]$local.schemaVersion -ne 1) {
        throw "Unsupported HarmonyOS local configuration schema: $($local.schemaVersion)"
    }
} else {
    $local = [pscustomobject]@{}
}

function Select-ConfiguredPath {
    param(
        [string]$EnvironmentValue,
        [string]$LocalValue,
        [string]$Name
    )

    $selected = if (-not [string]::IsNullOrWhiteSpace($EnvironmentValue)) { $EnvironmentValue } else { $LocalValue }
    if ([string]::IsNullOrWhiteSpace($selected)) {
        throw "Missing $Name. Set its environment variable or copy config/harmonyos-local.example.json to config/harmonyos-local.json."
    }
    return [System.IO.Path]::GetFullPath(($selected -replace '/', '\'))
}

function Select-OptionalConfiguredPath {
    param([string]$EnvironmentValue, [string]$LocalValue)

    $selected = if (-not [string]::IsNullOrWhiteSpace($EnvironmentValue)) { $EnvironmentValue } else { $LocalValue }
    if ([string]::IsNullOrWhiteSpace($selected)) { return '' }
    return [System.IO.Path]::GetFullPath(($selected -replace '/', '\'))
}

[pscustomobject]@{
    ToolchainRoot = Select-ConfiguredPath -EnvironmentValue $env:HARMONY_TOOLCHAIN_ROOT `
        -LocalValue ([string]$local.toolchainRoot) -Name 'HARMONY_TOOLCHAIN_ROOT'
    OpenCvSource = Select-ConfiguredPath -EnvironmentValue $env:HARMONY_OPENCV_SOURCE `
        -LocalValue ([string]$local.opencv.sourceRoot) -Name 'HARMONY_OPENCV_SOURCE'
    OpenCvBuildArm64 = Select-ConfiguredPath -EnvironmentValue $env:HARMONY_OPENCV_BUILD_ARM64 `
        -LocalValue ([string]$local.opencv.buildRoots.'arm64-v8a') -Name 'HARMONY_OPENCV_BUILD_ARM64'
    OpenCvBuildX64 = Select-ConfiguredPath -EnvironmentValue $env:HARMONY_OPENCV_BUILD_X64 `
        -LocalValue ([string]$local.opencv.buildRoots.x86_64) -Name 'HARMONY_OPENCV_BUILD_X64'
    FfprobePath = Select-OptionalConfiguredPath -EnvironmentValue $env:HARMONY_FFPROBE_PATH `
        -LocalValue ([string]$local.tools.ffprobePath)
    Source = if (Test-Path -LiteralPath $localPath -PathType Leaf) { $localPath } else { 'environment' }
}
