param(
  [string]$ApkPath = "android-app\app\build\outputs\apk\release\app-arm64-v8a-release.apk",
  [string]$ExpectedAbi = "",
  [string]$ExpectedSignerSha256 = ""
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
$apksigner = Join-Path $env:ANDROID_HOME "build-tools\36.0.0\apksigner.bat"

if (-not $ExpectedSignerSha256 -and $resolvedApkPath -match "[\\/]release[\\/]") {
  $fingerprintPath = Join-Path $repoRoot "config\release-signing-certificate.sha256"
  if (-not (Test-Path -LiteralPath $fingerprintPath -PathType Leaf)) {
    throw "Release signer fingerprint file is missing: $fingerprintPath"
  }
  $ExpectedSignerSha256 = (Get-Content -Raw -LiteralPath $fingerprintPath).Trim()
}

if ($ExpectedSignerSha256) {
  $signerOutput = (& $apksigner verify --print-certs $resolvedApkPath | Out-String)
  if ($LASTEXITCODE -ne 0) {
    throw "apksigner rejected the APK signature."
  }
  $signerMatches = [regex]::Matches($signerOutput, "Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)")
  if ($signerMatches.Count -ne 1) {
    throw "Expected exactly one APK signer, found $($signerMatches.Count)."
  }
  $actualSigner = $signerMatches[0].Groups[1].Value.ToUpperInvariant()
  if ($actualSigner -ne $ExpectedSignerSha256.Replace(":", "").ToUpperInvariant()) {
    throw "APK signer mismatch. Expected $ExpectedSignerSha256; found $actualSigner"
  }
}

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

  $featureEntry = $zip.GetEntry("assets/recognition/team-preview-templates-v2.bin")
  $metadataEntry = $zip.GetEntry("assets/recognition/team-preview-templates-v2.json")
  $roiEntry = $zip.GetEntry("assets/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json")
  if ($null -eq $featureEntry -or $featureEntry.Length -eq 0) {
    throw "Missing or empty team-preview recognition feature pack in APK."
  }
  if ($null -eq $metadataEntry -or $metadataEntry.Length -eq 0) {
    throw "Missing or empty team-preview recognition feature metadata in APK."
  }
  if ($null -eq $roiEntry -or $roiEntry.Length -eq 0) {
    throw "Missing team-preview ROI metadata in APK."
  }
  $metadataReader = [System.IO.StreamReader]::new($metadataEntry.Open())
  try {
    $featureMetadata = $metadataReader.ReadToEnd() | ConvertFrom-Json
  } finally {
    $metadataReader.Dispose()
  }
  if ($featureMetadata.binaryFormat -ne "PTVFEAT2") {
    throw "Unexpected team-preview feature pack format in APK metadata."
  }
  if ([long]$featureMetadata.binary.bytes -ne $featureEntry.Length) {
    throw "Team-preview feature pack size does not match APK metadata."
  }
  $featureStream = $featureEntry.Open()
  try {
    $featureHash = [BitConverter]::ToString($sha.ComputeHash($featureStream)).Replace("-", "").ToLowerInvariant()
  } finally {
    $featureStream.Dispose()
  }
  if ($featureHash -ne ([string]$featureMetadata.binary.sha256).ToLowerInvariant()) {
    throw "Team-preview feature pack SHA-256 does not match APK metadata."
  }
  $roiStream = $roiEntry.Open()
  try {
    $roiHash = [BitConverter]::ToString($sha.ComputeHash($roiStream)).Replace("-", "").ToLowerInvariant()
  } finally {
    $roiStream.Dispose()
  }
  if ($roiHash -ne ([string]$featureMetadata.roi.sha256).ToLowerInvariant()) {
    throw "Team-preview ROI SHA-256 does not match APK metadata."
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
$signerSummary = if ($ExpectedSignerSha256) { ", production signer verified" } else { "" }
Write-Output "APK release check passed: $($apk.Name) ($($apk.Length) bytes)$abiSummary$signerSummary, recognition feature pack and licenses present, update-only network permission verified."
