[CmdletBinding()]
param(
    [string]$Target = '127.0.0.1:5555',
    [switch]$SkipEmulator,
    [switch]$SkipBuild,
    [string]$SigningConfigPath
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$nodeTests = @(
    'tools/harmonyos/phase0-contracts.test.mjs',
    'tools/harmonyos/phase2-scaffold.test.mjs',
    'tools/harmonyos/phase3-domain.test.mjs',
    'tools/harmonyos/phase4-storage.test.mjs',
    'tools/harmonyos/phase5-main-ui.test.mjs',
    'tools/harmonyos/phase6-own-team-ocr.test.mjs',
    'tools/harmonyos/phase7-team-preview.test.mjs',
    'tools/harmonyos/phase8-battle-overlay.test.mjs',
    'tools/harmonyos/phase9-replay-recording.test.mjs',
    'tools/harmonyos/phase10-final-acceptance.test.mjs'
)
$emulatorChecks = @(
    'verify-stage3-runtime.ps1',
    'verify-stage4-storage.ps1',
    'verify-stage5-main-ui.ps1',
    'verify-stage6-own-team-ui.ps1',
    'verify-stage7-team-preview-ui.ps1',
    'verify-stage8-battle-overlay-ui.ps1',
    'verify-stage9-replay-ui.ps1'
)

Push-Location $repositoryRoot
try {
    & node --test @nodeTests
    if ($LASTEXITCODE -ne 0) { throw 'HarmonyOS phase 0 and 2-10 tests failed' }

    $releaseStatus = 'BLOCKED_BUILD_SKIPPED'
    if (-not $SkipBuild) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-app.ps1') `
            -Variant all -BuildMode debug -Clean
        if ($LASTEXITCODE -ne 0) { throw 'HarmonyOS clean Debug build failed' }
    }

    if (-not $SkipEmulator) {
        foreach ($check in $emulatorChecks) {
            & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $check) -Target $Target
            if ($LASTEXITCODE -ne 0) { throw "HarmonyOS emulator check failed: $check" }
        }
    }

    if (-not $SkipBuild -and [string]::IsNullOrWhiteSpace($SigningConfigPath)) {
        $releaseStatus = 'BLOCKED_RELEASE_SIGNING_CONFIG_REQUIRED'
        Write-Host 'BLOCKED_RELEASE_SIGNING_CONFIG_REQUIRED: pass -SigningConfigPath with production signing material; unsigned Release output is not built or accepted.'
    } elseif (-not $SkipBuild) {
        $resolvedSigningConfig = (Resolve-Path -LiteralPath $SigningConfigPath).Path
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-app.ps1') `
            -Variant all -BuildMode release -Clean -SigningConfigPath $resolvedSigningConfig
        if ($LASTEXITCODE -ne 0) { throw 'HarmonyOS signed clean Release build failed' }
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify-app-packages.ps1') `
            -BuildMode release -Variant all -SigningConfigPath $resolvedSigningConfig
        if ($LASTEXITCODE -ne 0) { throw 'HarmonyOS signed Release package verification failed' }
        $releaseStatus = 'PASS_SIGNED_RELEASE_BUILD_AND_PACKAGE_VERIFICATION'
    }

    Write-Host 'PASS_DETERMINISTIC_TESTS_AND_FORMAL_EMULATOR_SMOKES: only the checks actually executed above are covered.'
    Write-Host "RELEASE_STATUS: $releaseStatus"
    Write-Host 'BLOCKED_REAL_DEVICE_E5: gallery AVScreenCapture frames, Core Vision OCR, float-window touch/rotation, H.264/AAC MP4, media-library confirmation, upgrade installation, and 30-minute recording still require release hardware.'
    Write-Host 'No privacy or media-library decision is automated by this verifier.'
}
finally {
    Pop-Location
}
