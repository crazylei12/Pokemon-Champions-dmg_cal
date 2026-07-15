param(
  [string]$ApkPath = "android-app\app\build\outputs\apk\release\app-arm64-v8a-release.apk",
  [string]$ExpectedAbi = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resolvedApkPath = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
  (Resolve-Path -LiteralPath $ApkPath).Path
} else {
  (Resolve-Path -LiteralPath (Join-Path $repoRoot $ApkPath)).Path
}
$aapt2 = Join-Path $env:ANDROID_HOME "build-tools\36.0.0\aapt2.exe"

$permissions = (& $aapt2 dump permissions $resolvedApkPath | Out-String)
if ($LASTEXITCODE -ne 0) {
  throw "aapt2 could not inspect the APK."
}
if (-not $permissions.Contains("android.permission.INTERNET")) {
  throw "The APK is missing INTERNET permission required for user-triggered GitHub update checks."
}
if ($permissions.Contains("android.permission.ACCESS_NETWORK_STATE")) {
  throw "Unexpected network-state permission in APK: android.permission.ACCESS_NETWORK_STATE"
}

if ($ExpectedAbi) {
  $badging = (& $aapt2 dump badging $resolvedApkPath | Out-String)
  if ($LASTEXITCODE -ne 0) {
    throw "aapt2 could not inspect APK ABI metadata."
  }
  $nativeCodeLine = @($badging -split "`r?`n" | Where-Object { $_ -like "native-code:*" })
  if ($nativeCodeLine.Count -ne 1 -or $nativeCodeLine[0] -ne "native-code: '$ExpectedAbi'") {
    throw "APK ABI mismatch. Expected only $ExpectedAbi; found: $($nativeCodeLine -join ', ')"
  }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($resolvedApkPath)
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
  $licenseMappings = [ordered]@{
    "assets/licenses/THIRD_PARTY_NOTICES.md" = "THIRD_PARTY_NOTICES.md"
    "assets/licenses/smogon-damage-calc-MIT.txt" = "third_party/licenses/smogon-damage-calc-MIT.txt"
    "assets/licenses/pkmn-ps-MIT.txt" = "third_party/licenses/pkmn-ps-MIT.txt"
    "assets/licenses/pokeapi-BSD-3-Clause.txt" = "third_party/licenses/pokeapi-BSD-3-Clause.txt"
    "assets/licenses/pokeapi-sprites-CC0-1.0.txt" = "third_party/licenses/pokeapi-sprites-CC0-1.0.txt"
    "assets/licenses/APACHE-2.0.txt" = "third_party/licenses/APACHE-2.0.txt"
    "assets/licenses/ml-kit-TERMS.txt" = "third_party/licenses/ml-kit-TERMS.txt"
    "assets/licenses/42arch-pokemon-dataset-zh-MIT.txt" = "src/data/localization/sources/42arch-pokemon-dataset-zh/LICENSE"
  }

  foreach ($entryName in $licenseMappings.Keys) {
    $entry = $zip.GetEntry($entryName)
    if ($null -eq $entry) {
      throw "Missing license asset in APK: $entryName"
    }

    $stream = $entry.Open()
    try {
      $apkHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace("-", "")
    } finally {
      $stream.Dispose()
    }
    $sourcePath = Join-Path $repoRoot $licenseMappings[$entryName]
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash
    if ($apkHash -ne $sourceHash) {
      throw "License asset differs from its tracked source: $entryName"
    }
  }

  foreach ($entryName in @(
    "assets/licenses/ml-kit/third_party_licenses.json",
    "assets/licenses/ml-kit/third_party_licenses.txt"
  )) {
    $entry = $zip.GetEntry($entryName)
    if ($null -eq $entry -or $entry.Length -eq 0) {
      throw "Missing or empty ML Kit license asset in APK: $entryName"
    }
  }

  $presetsEntry = $zip.GetEntry("assets/damage/champions-presets.json")
  if ($null -eq $presetsEntry) {
    throw "Missing generated damage presets in APK."
  }
  $reader = [System.IO.StreamReader]::new($presetsEntry.Open())
  try {
    $presets = $reader.ReadToEnd() | ConvertFrom-Json
  } finally {
    $reader.Dispose()
  }
  $expectedLicenseAssets = @(
    "licenses/smogon-damage-calc-MIT.txt",
    "licenses/pkmn-ps-MIT.txt"
  )
  if ((Compare-Object -ReferenceObject $expectedLicenseAssets -DifferenceObject @($presets.licenseAssets))) {
    throw "Generated damage preset attribution is incomplete in the APK."
  }
} finally {
  $sha.Dispose()
  $zip.Dispose()
}

$apk = Get-Item -LiteralPath $resolvedApkPath
$abiSummary = if ($ExpectedAbi) { ", ABI $ExpectedAbi" } else { "" }
Write-Output "APK release check passed: $($apk.Name) ($($apk.Length) bytes)$abiSummary, licenses present, update-only network permission verified."
