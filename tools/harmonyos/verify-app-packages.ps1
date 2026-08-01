[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$BuildMode = 'debug',
    [ValidateSet('standard', 'replay', 'all')]
    [string]$Variant = 'all',
    [string]$ExpectedCertificateSha256,
    [string]$SigningConfigPath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$distDirectory = Join-Path $repositoryRoot 'harmonyos\app\dist'
$localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
$toolchainRoot = $localConfig.ToolchainRoot
$java = Join-Path $toolchainRoot 'jbr\bin\java.exe'
$signTool = Join-Path $toolchainRoot 'sdk\default\openharmony\toolchains\lib\hap-sign-tool.jar'

if ($BuildMode -eq 'release' -and [string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) {
    if ([string]::IsNullOrWhiteSpace($SigningConfigPath)) {
        $SigningConfigPath = $env:HARMONY_SIGNING_CONFIG
    }
    if ([string]::IsNullOrWhiteSpace($SigningConfigPath)) {
        throw 'Release verification requires -SigningConfigPath, HARMONY_SIGNING_CONFIG, or -ExpectedCertificateSha256.'
    }
    $signingRoot = Get-Content -LiteralPath (Resolve-Path -LiteralPath $SigningConfigPath).Path -Raw -Encoding utf8 | ConvertFrom-Json
    $ExpectedCertificateSha256 = ([string]$signingRoot.expectedCertificateSha256).Trim().ToLowerInvariant()
}

function Get-EntryBytes {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [string]$EntryName
    )

    $entry = $Archive.GetEntry($EntryName)
    if ($null -eq $entry) {
        throw "Missing HAP entry: $EntryName"
    }
    $stream = $entry.Open()
    try {
        $memory = [System.IO.MemoryStream]::new()
        try {
            $stream.CopyTo($memory)
            return $memory.ToArray()
        } finally {
            $memory.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Assert-ReleaseSignature {
    param(
        [string]$HapPath,
        [string]$VariantName,
        [string]$ExpectedSha256
    )

    if ($ExpectedSha256 -notmatch '^[0-9a-f]{64}$') {
        throw 'Release verification requires -ExpectedCertificateSha256 with exactly 64 lowercase hexadecimal characters'
    }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or -not (Test-Path -LiteralPath $signTool -PathType Leaf)) {
        throw "HarmonyOS signature verifier is unavailable under $toolchainRoot"
    }

    $verificationRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot ".tmp\harmonyos-signature-verify\$VariantName"))
    $expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot '.tmp\harmonyos-signature-verify'))
    if (-not $verificationRoot.StartsWith($expectedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe signature verification path: $verificationRoot"
    }
    if (Test-Path -LiteralPath $verificationRoot) {
        Remove-Item -LiteralPath $verificationRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null
    $certChain = Join-Path $verificationRoot 'certificate-chain.cer'
    $profile = Join-Path $verificationRoot 'profile.p7b'
    try {
        & $java -jar $signTool verify-app -inFile $HapPath -outCertChain $certChain -outProfile $profile
        if ($LASTEXITCODE -ne 0) {
            throw "$VariantName HAP signature verification failed with exit code $LASTEXITCODE"
        }
        foreach ($output in @($certChain, $profile)) {
            if (-not (Test-Path -LiteralPath $output -PathType Leaf) -or (Get-Item -LiteralPath $output).Length -le 0) {
                throw "$VariantName signature verifier did not produce $output"
            }
        }

        $certificates = [System.Collections.Generic.List[System.Security.Cryptography.X509Certificates.X509Certificate2]]::new()
        $pemText = Get-Content -LiteralPath $certChain -Raw -Encoding ascii
        $pemMatches = [regex]::Matches(
            $pemText,
            '-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----',
            [System.Text.RegularExpressions.RegexOptions]::Singleline)
        foreach ($pemMatch in $pemMatches) {
            $base64 = [regex]::Replace($pemMatch.Groups[1].Value, '\s', '')
            $certificates.Add(
                [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
                    [Convert]::FromBase64String($base64)))
        }
        if ($certificates.Count -eq 0) {
            throw "$VariantName signature certificate chain is empty"
        }
        $hashAlgorithm = [System.Security.Cryptography.HashAlgorithmName]::SHA256
        $fingerprints = @($certificates | ForEach-Object { $_.GetCertHashString($hashAlgorithm).ToLowerInvariant() })
        if ($fingerprints -notcontains $ExpectedSha256) {
            throw "$VariantName signature certificate mismatch: expected $ExpectedSha256, got $($fingerprints -join ',')"
        }
    } finally {
        if (Test-Path -LiteralPath $verificationRoot) {
            Remove-Item -LiteralPath $verificationRoot -Recurse -Force
        }
    }
}

$variantNames = if ($Variant -eq 'all') { @('standard', 'replay') } else { @($Variant) }
$signatureState = if ($BuildMode -eq 'release') { 'signed-universal' } else { 'unsigned' }
$results = @()
$nativeHashes = @{}
foreach ($variantName in $variantNames) {
    $variantConfig = $config.products.$variantName
    $hapPath = Join-Path $distDirectory "$($variantConfig.artifactName)-$BuildMode-$signatureState.hap"
    if (-not (Test-Path -LiteralPath $hapPath -PathType Leaf)) {
        throw "Missing $variantName HAP: $hapPath"
    }
    if ($BuildMode -eq 'release') {
        Assert-ReleaseSignature -HapPath $hapPath -VariantName $variantName -ExpectedSha256 $ExpectedCertificateSha256
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($hapPath)
    try {
        $moduleJson = [System.Text.Encoding]::UTF8.GetString((Get-EntryBytes -Archive $archive -EntryName 'module.json')) | ConvertFrom-Json
        if ($moduleJson.app.bundleName -ne $config.bundleName) {
            throw "$variantName bundleName mismatch: $($moduleJson.app.bundleName)"
        }
        if ([int]$moduleJson.app.versionCode -ne [int]$config.versionCode -or $moduleJson.app.versionName -ne $config.versionName) {
            throw "$variantName version mismatch"
        }
        if ($moduleJson.app.label -ne "`$string:app_name_$variantName") {
            throw "$variantName product label mismatch: $($moduleJson.app.label)"
        }
        $expectedDebug = $BuildMode -eq 'debug'
        if ([bool]$moduleJson.app.debug -ne $expectedDebug) {
            throw "$variantName build mode mismatch: expected debug=$expectedDebug, got $($moduleJson.app.debug)"
        }

        $permissionNames = @($moduleJson.module.requestPermissions | ForEach-Object { [string]$_.name } | Sort-Object)
        $expectedPermissions = @('ohos.permission.INTERNET', 'ohos.permission.SYSTEM_FLOAT_WINDOW')
        if ($variantName -eq 'replay') {
            $expectedPermissions += 'ohos.permission.WRITE_IMAGEVIDEO'
        }
        $expectedPermissions = $expectedPermissions | Sort-Object
        if (($permissionNames -join ',') -ne ($expectedPermissions -join ',')) {
            throw "$variantName permission set mismatch: $($permissionNames -join ',')"
        }

        $pages = [System.Text.Encoding]::UTF8.GetString((Get-EntryBytes -Archive $archive -EntryName 'resources/base/profile/main_pages.json')) | ConvertFrom-Json
        $pageNames = @($pages.src | ForEach-Object { [string]$_ })
        $forbiddenPages = @($pageNames | Where-Object { $_ -match 'Stage\d+Verification|probe|test' })
        if ($forbiddenPages.Count -gt 0) {
            throw "$variantName contains forbidden product pages: $($forbiddenPages -join ',')"
        }
        if ($variantName -eq 'standard' -and $pageNames -contains 'pages/BattleHudRecording') {
            throw 'Standard HAP contains the replay-only recording page'
        }
        if ($variantName -eq 'replay' -and $pageNames -notcontains 'pages/BattleHudRecording') {
            throw 'Replay HAP is missing its recording page'
        }

        $abcText = [System.Text.Encoding]::UTF8.GetString((Get-EntryBytes -Archive $archive -EntryName 'ets/modules.abc'))
        foreach ($forbiddenText in @('Stage3Verification', 'Stage4Verification', 'Stage6Verification', 'Stage7Verification', 'Stage8Verification', 'Stage9Verification')) {
            if ($abcText.Contains($forbiddenText)) {
                throw "$variantName modules.abc contains forbidden Release/debug implementation: $forbiddenText"
            }
        }
        $replayArkMarkers = @('showAssetsCreationDialog', 'MediaAssetChangeRequest', 'prepareReplayCapture', 'startReplayRecorder')
        if ($variantName -eq 'standard') {
            foreach ($marker in $replayArkMarkers) {
                if ($abcText.Contains($marker)) {
                    throw "Standard modules.abc contains replay-only implementation: $marker"
                }
            }
        } else {
            foreach ($marker in $replayArkMarkers) {
                if (-not $abcText.Contains($marker)) {
                    throw "Replay modules.abc is missing replay implementation: $marker"
                }
            }
        }

        $nativeHashes[$variantName] = @{}
        foreach ($abi in $config.toolchain.abis) {
            $nativeBytes = Get-EntryBytes -Archive $archive -EntryName "libs/$abi/libpcbridge.so"
            $nativeHashes[$variantName][$abi] = Get-Sha256Hex -Bytes $nativeBytes
            $nativeText = [System.Text.Encoding]::ASCII.GetString($nativeBytes)
            $replayMarkers = @('prepareReplayCapture', 'prepareReplayRecorder', 'startReplayRecorder', 'stopReplayRecorder')
            if ($variantName -eq 'standard') {
                foreach ($marker in $replayMarkers) {
                    if ($nativeText.Contains($marker)) {
                        throw "Standard $abi Native library exposes replay marker: $marker"
                    }
                }
            } elseif (-not $nativeText.Contains('startReplayRecorder')) {
                throw "Replay $abi Native library is missing replay recorder exports"
            }
        }

        foreach ($asset in $config.runtimeAssets) {
            $bytes = Get-EntryBytes -Archive $archive -EntryName "resources/rawfile/$($asset.packagePath)"
            $hash = Get-Sha256Hex -Bytes $bytes
            if ($hash -ne $asset.sha256) {
                throw "$variantName packaged hash mismatch for $($asset.packagePath): $hash"
            }
        }

        $manifestBytes = Get-EntryBytes -Archive $archive -EntryName 'resources/rawfile/runtime/manifest.json'
        $manifest = [System.Text.Encoding]::UTF8.GetString($manifestBytes) | ConvertFrom-Json
        if ($manifest.kind -ne 'HarmonyOSRuntimeAssetManifest' -or $manifest.assets.Count -ne $config.runtimeAssets.Count) {
            throw "$variantName runtime manifest mismatch"
        }

        $hapBytes = [System.IO.File]::ReadAllBytes($hapPath)
        $results += [pscustomobject]@{
            Variant = $variantName
            Product = [string]$variantConfig.hvigorProduct
            BundleName = [string]$moduleJson.app.bundleName
            Version = "$($moduleJson.app.versionName) ($($moduleJson.app.versionCode))"
            Signed = $BuildMode -eq 'release'
            Abis = ($config.toolchain.abis -join ',')
            Assets = $config.runtimeAssets.Count
            Pages = $pageNames.Count
            Bytes = $hapBytes.Length
            Sha256 = Get-Sha256Hex -Bytes $hapBytes
            Path = $hapPath
        }
    } finally {
        $archive.Dispose()
    }
}

if ($variantNames.Count -eq 2) {
    if ($results[0].Sha256 -eq $results[1].Sha256) {
        throw 'Standard and replay HAPs are byte-identical; the product selection was not applied.'
    }
    foreach ($abi in $config.toolchain.abis) {
        if ($nativeHashes.standard[$abi] -eq $nativeHashes.replay[$abi]) {
            throw "Standard and replay $abi Native libraries are byte-identical; replay code was not compiled out."
        }
    }
}

$results | Format-List
Write-Host "HarmonyOS package verification PASS: $($variantNames -join ', ') ($BuildMode)"
