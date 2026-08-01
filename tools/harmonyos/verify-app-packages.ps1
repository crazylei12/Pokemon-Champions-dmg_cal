[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$BuildMode = 'debug'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $repositoryRoot 'config\harmonyos-app-build.json') -Raw -Encoding utf8 | ConvertFrom-Json
$distDirectory = Join-Path $repositoryRoot 'harmonyos\app\dist'

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

$results = @()
foreach ($variantName in @('standard', 'replay')) {
    $variant = $config.products.$variantName
    $hapPath = Join-Path $distDirectory "$($variant.artifactName)-$BuildMode-unsigned.hap"
    if (-not (Test-Path -LiteralPath $hapPath)) {
        throw "Missing $variantName HAP: $hapPath"
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

        foreach ($abi in $config.toolchain.abis) {
            [void](Get-EntryBytes -Archive $archive -EntryName "libs/$abi/libpcbridge.so")
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
            Product = [string]$variant.hvigorProduct
            BundleName = [string]$moduleJson.app.bundleName
            Version = "$($moduleJson.app.versionName) ($($moduleJson.app.versionCode))"
            Abis = ($config.toolchain.abis -join ',')
            Assets = $config.runtimeAssets.Count
            Bytes = $hapBytes.Length
            Sha256 = Get-Sha256Hex -Bytes $hapBytes
            Path = $hapPath
        }
    } finally {
        $archive.Dispose()
    }
}

if ($results[0].Sha256 -eq $results[1].Sha256) {
    throw 'Standard and replay HAPs are byte-identical; the product selection was not applied.'
}

$results | Format-List
Write-Host 'HarmonyOS package verification PASS'
