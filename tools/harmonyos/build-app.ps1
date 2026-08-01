[CmdletBinding()]
param(
    [ValidateSet('standard', 'replay', 'all')]
    [string]$Variant = 'all',
    [ValidateSet('debug', 'release')]
    [string]$BuildMode = 'debug',
    [switch]$Clean,
    [string]$SigningConfigPath
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$projectRoot = Join-Path $repositoryRoot 'harmonyos\app'
$projectBuildProfilePath = Join-Path $projectRoot 'build-profile.json5'
$moduleProfilePath = Join-Path $projectRoot 'entry\src\main\module.json5'
$materializedReplayCoordinatorPath = Join-Path $projectRoot `
    'entry\src\main\ets\services\ReplayRecordingCoordinator.ets'
$materializedRuntimeE3ProbePath = Join-Path $projectRoot `
    'entry\src\main\ets\services\RuntimeE3Probe.ets'
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$toolchainRoot = $localConfig.ToolchainRoot
$node = Join-Path $toolchainRoot 'tools\node\node.exe'
$hvigor = Join-Path $toolchainRoot 'tools\hvigor\bin\hvigorw.bat'

function Resolve-SigningMaterialPath {
    param(
        [string]$Value,
        [string]$BaseDirectory,
        [string]$FieldName,
        [string]$ExpectedExtension
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Release signing field '$FieldName' is empty"
    }
    $candidate = if ([System.IO.Path]::IsPathRooted($Value)) {
        [System.IO.Path]::GetFullPath($Value)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $Value))
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Release signing file '$FieldName' does not exist: $candidate"
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedExtension) -and
        [System.IO.Path]::GetExtension($candidate).ToLowerInvariant() -ne $ExpectedExtension) {
        throw "Release signing file '$FieldName' must use ${ExpectedExtension}: $candidate"
    }
    return $candidate
}

function Read-ReleaseSigningConfiguration {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'Release builds require -SigningConfigPath or HARMONY_SIGNING_CONFIG; unsigned Release output is forbidden.'
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $root = Get-Content -LiteralPath $resolved -Raw -Encoding utf8 | ConvertFrom-Json
    if ([int]$root.schemaVersion -ne 1) {
        throw "Unsupported HarmonyOS signing configuration schema: $($root.schemaVersion)"
    }
    $expected = ([string]$root.expectedCertificateSha256).Trim().ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') {
        throw 'expectedCertificateSha256 must be exactly 64 lowercase hexadecimal characters'
    }
    $signing = $root.signingConfig
    if ($null -eq $signing -or [string]::IsNullOrWhiteSpace([string]$signing.name)) {
        throw 'signingConfig.name is required'
    }
    if ([string]$signing.type -ne 'HarmonyOS') {
        throw 'signingConfig.type must be HarmonyOS'
    }
    if ([string]$signing.material.signAlg -ne 'SHA256withECDSA') {
        throw 'HarmonyOS Release signing requires SHA256withECDSA'
    }
    foreach ($field in @('storePassword', 'keyAlias', 'keyPassword')) {
        if ([string]::IsNullOrWhiteSpace([string]$signing.material.$field)) {
            throw "Release signing field '$field' is empty"
        }
    }

    $baseDirectory = Split-Path -Parent $resolved
    $signing.material.certpath = Resolve-SigningMaterialPath -Value ([string]$signing.material.certpath) `
        -BaseDirectory $baseDirectory -FieldName 'certpath' -ExpectedExtension '.cer'
    $signing.material.profile = Resolve-SigningMaterialPath -Value ([string]$signing.material.profile) `
        -BaseDirectory $baseDirectory -FieldName 'profile' -ExpectedExtension '.p7b'
    $signing.material.storeFile = Resolve-SigningMaterialPath -Value ([string]$signing.material.storeFile) `
        -BaseDirectory $baseDirectory -FieldName 'storeFile' -ExpectedExtension '.p12'

    try {
        $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
            [string]$signing.material.certpath)
        $actual = $certificate.GetCertHashString(
            [System.Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
        $certificate.Dispose()
    } catch {
        throw "Release certificate cannot be parsed as X.509: $($signing.material.certpath); $($_.Exception.Message)"
    }
    if ($actual -ne $expected) {
        throw "Release certificate SHA-256 mismatch: expected $expected, got $actual"
    }
    return [pscustomobject]@{
        SigningConfig = $signing
        ExpectedCertificateSha256 = $expected
    }
}

function Write-InjectedBuildProfile {
    param([object]$SigningConfig)

    $profile = Get-Content -LiteralPath $projectBuildProfilePath -Raw -Encoding utf8 | ConvertFrom-Json
    $profile.app.signingConfigs = @($SigningConfig)
    foreach ($product in $profile.app.products) {
        $product.signingConfig = [string]$SigningConfig.name
    }
    $json = $profile | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText($projectBuildProfilePath, "$json`n", [System.Text.UTF8Encoding]::new($false))
}

function Write-VariantModuleProfile {
    param([string]$VariantName)

    if ($VariantName -ne 'replay') {
        return
    }
    $profile = Get-Content -LiteralPath $moduleProfilePath -Raw -Encoding utf8 | ConvertFrom-Json
    $permissions = @($profile.module.requestPermissions)
    if (@($permissions | Where-Object { $_.name -eq 'ohos.permission.WRITE_IMAGEVIDEO' }).Count -eq 0) {
        $permissions += [pscustomobject]@{
            name = 'ohos.permission.WRITE_IMAGEVIDEO'
            reason = '$string:permission_write_imagevideo_reason'
            usedScene = [pscustomobject]@{
                abilities = @('EntryAbility')
                when = 'inuse'
            }
        }
    }
    $profile.module.requestPermissions = $permissions
    $json = $profile | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText($moduleProfilePath, "$json`n", [System.Text.UTF8Encoding]::new($false))
}

function Write-VariantReplayCoordinator {
    param([string]$VariantName)

    $sourcePath = Join-Path $projectRoot `
        "entry\src\$VariantName\ets\services\ReplayRecordingCoordinator.ets"
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Missing $VariantName replay coordinator source set: $sourcePath"
    }
    [System.IO.File]::WriteAllBytes(
        $materializedReplayCoordinatorPath,
        [System.IO.File]::ReadAllBytes($sourcePath))
}

function Write-BuildModeRuntimeE3Probe {
    param([string]$Mode)

    $sourcePath = Join-Path $projectRoot "entry\generated-src\$Mode\services\RuntimeE3Probe.ets"
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Missing $Mode runtime E3 probe source: $sourcePath"
    }
    [System.IO.File]::WriteAllBytes(
        $materializedRuntimeE3ProbePath,
        [System.IO.File]::ReadAllBytes($sourcePath))
}

$releaseSigning = $null
if ($BuildMode -eq 'release') {
    if ([string]::IsNullOrWhiteSpace($SigningConfigPath)) {
        $SigningConfigPath = $env:HARMONY_SIGNING_CONFIG
    }
    $releaseSigning = Read-ReleaseSigningConfiguration -Path $SigningConfigPath
}

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
$env:HARMONY_OPENCV_SOURCE = $localConfig.OpenCvSource
$env:HARMONY_OPENCV_BUILD_ARM64 = $localConfig.OpenCvBuildArm64
$env:HARMONY_OPENCV_BUILD_X64 = $localConfig.OpenCvBuildX64
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$(Split-Path -Parent $node);$(Split-Path -Parent $hvigor);$env:Path"

$variants = if ($Variant -eq 'all') { @('standard', 'replay') } else { @($Variant) }
$originalBuildProfile = [System.IO.File]::ReadAllBytes($projectBuildProfilePath)
$originalModuleProfile = [System.IO.File]::ReadAllBytes($moduleProfilePath)
$hadMaterializedReplayCoordinator = $false
$originalReplayCoordinator = $null
if (Test-Path -LiteralPath $materializedReplayCoordinatorPath -PathType Leaf) {
    $existingHash = (Get-FileHash -LiteralPath $materializedReplayCoordinatorPath -Algorithm SHA256).Hash
    $knownGeneratedHashes = @('standard', 'replay') | ForEach-Object {
        $knownPath = Join-Path $projectRoot "entry\src\$_\ets\services\ReplayRecordingCoordinator.ets"
        (Get-FileHash -LiteralPath $knownPath -Algorithm SHA256).Hash
    }
    if ($knownGeneratedHashes -notcontains $existingHash) {
        throw "Refusing to overwrite unexpected source file: $materializedReplayCoordinatorPath"
    }
    # Recover automatically from a previously interrupted generated-source build.
    Remove-Item -LiteralPath $materializedReplayCoordinatorPath -Force
}
if (Test-Path -LiteralPath $materializedRuntimeE3ProbePath -PathType Leaf) {
    $existingHash = (Get-FileHash -LiteralPath $materializedRuntimeE3ProbePath -Algorithm SHA256).Hash
    $knownGeneratedHashes = @('debug', 'release') | ForEach-Object {
        $knownPath = Join-Path $projectRoot "entry\generated-src\$_\services\RuntimeE3Probe.ets"
        (Get-FileHash -LiteralPath $knownPath -Algorithm SHA256).Hash
    }
    if ($knownGeneratedHashes -notcontains $existingHash) {
        throw "Refusing to overwrite unexpected source file: $materializedRuntimeE3ProbePath"
    }
    # Recover automatically from a previously interrupted generated-source build.
    Remove-Item -LiteralPath $materializedRuntimeE3ProbePath -Force
}
$distDirectory = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null

try {
    if ($null -ne $releaseSigning) {
        Write-InjectedBuildProfile -SigningConfig $releaseSigning.SigningConfig
    }

    Push-Location -LiteralPath $projectRoot
    try {
        foreach ($currentVariant in $variants) {
            # Keep the tracked manifest at least privilege. The system media write permission is
            # injected only while producing the replay HAP, then restored even on build failure.
            [System.IO.File]::WriteAllBytes($moduleProfilePath, $originalModuleProfile)
            Write-VariantModuleProfile -VariantName $currentVariant
            Write-VariantReplayCoordinator -VariantName $currentVariant
            Write-BuildModeRuntimeE3Probe -Mode $BuildMode
            $product = [string]$config.products.$currentVariant.hvigorProduct
            $tasks = [System.Collections.Generic.List[string]]::new()
            $tasks.Add('--no-daemon')
            $tasks.Add('--generate-build-profile')
            $tasks.Add('-p')
            $tasks.Add("product=$product")
            $tasks.Add('-p')
            $tasks.Add("module=entry@$currentVariant")
            $tasks.Add('-p')
            $tasks.Add("buildMode=$BuildMode")
            $tasks.Add('--mode')
            $tasks.Add('module')
            # Product-specific ArkTS and Native source sets must never share an incremental cache.
            # Clean is therefore mandatory even when the caller omits -Clean.
            $tasks.Add('clean')
            $tasks.Add('assembleHap')

            Write-Host "Building HarmonyOS $currentVariant product ($product, $BuildMode)..."
            & $hvigor @tasks
            if ($LASTEXITCODE -ne 0) {
                throw "Hvigor build failed for $currentVariant with exit code $LASTEXITCODE"
            }

            $sourceSignatureState = if ($BuildMode -eq 'release') { 'signed' } else { 'unsigned' }
            $outputRoot = Join-Path $projectRoot "entry\build\$product\outputs"
            $expectedHapFileName = "entry-$currentVariant-$sourceSignatureState.hap"
            $hapCandidates = @(Get-ChildItem -LiteralPath $outputRoot -Recurse -File `
                -Filter $expectedHapFileName -ErrorAction SilentlyContinue)
            if ($hapCandidates.Count -ne 1) {
                $candidateList = ($hapCandidates.FullName -join '; ')
                throw "Expected exactly one $sourceSignatureState HAP for $currentVariant under $outputRoot; found $($hapCandidates.Count): $candidateList"
            }
            $hapSource = $hapCandidates[0].FullName
            if ($BuildMode -eq 'release' -and $hapSource -notmatch '-signed\.hap$') {
                throw "Unsigned Release output is forbidden: $hapSource"
            }
            $artifactName = [string]$config.products.$currentVariant.artifactName
            $destinationSignatureState = if ($BuildMode -eq 'release') { 'signed-universal' } else { 'unsigned' }
            $hapDestination = Join-Path $distDirectory "$artifactName-$BuildMode-$destinationSignatureState.hap"
            Copy-Item -LiteralPath $hapSource -Destination $hapDestination -Force
            Write-Host "Saved $currentVariant HAP: $hapDestination"
        }
    } finally {
        Pop-Location
    }
} finally {
    [System.IO.File]::WriteAllBytes($projectBuildProfilePath, $originalBuildProfile)
    [System.IO.File]::WriteAllBytes($moduleProfilePath, $originalModuleProfile)
    if ($hadMaterializedReplayCoordinator) {
        [System.IO.File]::WriteAllBytes($materializedReplayCoordinatorPath, $originalReplayCoordinator)
    } elseif (Test-Path -LiteralPath $materializedReplayCoordinatorPath -PathType Leaf) {
        Remove-Item -LiteralPath $materializedReplayCoordinatorPath -Force
    }
    if (Test-Path -LiteralPath $materializedRuntimeE3ProbePath -PathType Leaf) {
        Remove-Item -LiteralPath $materializedRuntimeE3ProbePath -Force
    }
}

$verifyArguments = @{
    BuildMode = $BuildMode
    Variant = $Variant
}
if ($null -ne $releaseSigning) {
    $verifyArguments.ExpectedCertificateSha256 = $releaseSigning.ExpectedCertificateSha256
}
& (Join-Path $PSScriptRoot 'verify-app-packages.ps1') @verifyArguments
if ($LASTEXITCODE -ne 0) {
    throw "HarmonyOS package verification failed with exit code $LASTEXITCODE"
}

Write-Host "HarmonyOS build PASS: $($variants -join ', ') ($BuildMode)"
