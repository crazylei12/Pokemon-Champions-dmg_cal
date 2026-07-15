param(
  [switch]$Rotate
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
$store = Join-Path $env:USERPROFILE ".android\pokemon-champions-release.p12"
$secretDirectory = Join-Path $env:APPDATA "PokemonChampionsAssistant"
$secret = Join-Path $secretDirectory "release-signing.clixml"
$fingerprintFile = Join-Path $repoRoot "config\release-signing-certificate.sha256"
$alias = "pokemon-champions-release"

if ((Test-Path -LiteralPath $store) -and -not $Rotate) {
  throw "Release keystore already exists. Refusing to replace it without -Rotate: $store"
}
if ($Rotate) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  if (Test-Path -LiteralPath $store) { Move-Item -LiteralPath $store -Destination "$store.$stamp.bak" }
  if (Test-Path -LiteralPath $secret) { Move-Item -LiteralPath $secret -Destination "$secret.$stamp.bak" }
}

$passwordBytes = [byte[]]::new(32)
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
  $random.GetBytes($passwordBytes)
} finally {
  $random.Dispose()
}
$password = [Convert]::ToBase64String($passwordBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

New-Item -ItemType Directory -Force -Path (Split-Path $store), $secretDirectory | Out-Null
& $keytool -genkeypair -v -storetype PKCS12 -keystore $store -storepass $password -keypass $password `
  -alias $alias -keyalg RSA -keysize 4096 -validity 9125 `
  -dname "CN=Pokemon Champions Assistant, OU=Android Release, O=crazylei12, C=CN"
if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the release key" }

$securePassword = ConvertTo-SecureString $password -AsPlainText -Force
[PSCredential]::new($alias, $securePassword) | Export-Clixml -LiteralPath $secret -Force

$certificateOutput = & $keytool -list -v -storetype PKCS12 -keystore $store -storepass $password -alias $alias
if ($LASTEXITCODE -ne 0) { throw "keytool failed to inspect the release key" }
$fingerprint = ($certificateOutput | Select-String -Pattern "SHA256:\s*([0-9A-F:]+)" | Select-Object -First 1).Matches.Groups[1].Value.Replace(':', '').ToUpperInvariant()
if ($fingerprint.Length -ne 64) { throw "Could not determine the release certificate SHA-256 fingerprint" }
New-Item -ItemType Directory -Force -Path (Split-Path $fingerprintFile) | Out-Null
Set-Content -LiteralPath $fingerprintFile -Value $fingerprint -Encoding ASCII -NoNewline

Write-Output "Release signing provisioned with certificate SHA-256 $fingerprint"
Write-Output "Keystore: $store"
Write-Output "DPAPI credentials: $secret"
Write-Output "Back up the PKCS12 keystore and store its password in an independent password manager before publishing."
