param(
  [Parameter(Mandatory = $true)]
  [string]$Destination
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$store = $env:POKEMON_CHAMPIONS_SIGNING_STORE_FILE
$secret = Join-Path $env:APPDATA "PokemonChampionsAssistant\release-signing.clixml"
$expectedFingerprint = (Get-Content -Raw -LiteralPath (Join-Path $repoRoot "config\release-signing-certificate.sha256")).Trim()
if (-not $store -or -not (Test-Path -LiteralPath $store -PathType Leaf)) { throw "Release keystore is not configured" }
if (-not (Test-Path -LiteralPath $secret -PathType Leaf)) { throw "DPAPI signing credential file is missing" }

$resolvedDestination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $resolvedDestination | Out-Null
$storeCopy = Join-Path $resolvedDestination "pokemon-champions-release.p12"
$secretCopy = Join-Path $resolvedDestination "release-signing.clixml"
Copy-Item -LiteralPath $store -Destination $storeCopy -Force
Copy-Item -LiteralPath $secret -Destination $secretCopy -Force

$manifest = [ordered]@{
  kind = "PokemonChampionsReleaseSigningBackup"
  createdAt = [DateTime]::UtcNow.ToString("o")
  certificateSha256 = $expectedFingerprint
  keyAlias = $env:POKEMON_CHAMPIONS_SIGNING_KEY_ALIAS
  keystoreSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $storeCopy).Hash
  note = "The CLIXML credential is recoverable only by the same Windows user on the same machine. Keep the PKCS12 password separately in a password manager for disaster recovery."
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resolvedDestination "signing-backup-manifest.json") -Encoding UTF8

Write-Output "Release signing backup created: $resolvedDestination"
Write-Output "Certificate SHA-256: $expectedFingerprint"
