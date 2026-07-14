param(
  [switch]$AcceptSdkLicenses
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Numerics

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$toolRoot = Join-Path $repoRoot ".android-tools"
$downloads = Join-Path $toolRoot "downloads"
$staging = Join-Path $toolRoot "staging"
$jdkHome = Join-Path $toolRoot "jdk-17"
$sdkRoot = Join-Path $toolRoot "android-sdk"
$gradleHome = Join-Path $toolRoot "gradle-9.4.1"
$androidProject = Join-Path $repoRoot "android-app"

$jdkUrl = "https://download.visualstudio.microsoft.com/download/pr/8fdc33a5-2cf8-4e3a-82a8-abe718da0aea/f09364512aaaabfd27bcd1014f3f66a6/microsoft-jdk-17.0.19-windows-x64.zip"
$jdkSha256 = "394d1d8253d58b462300f15f9c81369478cf8813f82dca914c3b5dfdef080f9f"
$commandLineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-15641748_latest.zip"
$commandLineToolsSha1 = "2bea1388b8a248040a340a08ca0638138633f687"
$gradleUrl = "https://services.gradle.org/distributions/gradle-9.4.1-bin.zip"
$gradleSha256 = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"

function Assert-UnderToolRoot {
  param([string]$Path)
  $fullRoot = [IO.Path]::GetFullPath($toolRoot).TrimEnd('\') + '\'
  $fullPath = [IO.Path]::GetFullPath($Path)
  if (-not $fullPath.StartsWith($fullRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to modify a path outside .android-tools: $fullPath"
  }
}

function Reset-Directory {
  param([string]$Path)
  Assert-UnderToolRoot $Path
  if (Test-Path -LiteralPath $Path) {
    Remove-Item -LiteralPath $Path -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Get-VerifiedDownload {
  param(
    [string]$Uri,
    [string]$Destination,
    [string]$ExpectedHash,
    [ValidateSet("SHA1", "SHA256")][string]$Algorithm
  )

  if (Test-Path -LiteralPath $Destination -PathType Leaf) {
    $currentHash = (Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash.ToLowerInvariant()
    if ($currentHash -eq $ExpectedHash) {
      Write-Output "Using cached download: $Destination"
      return
    }
    Remove-Item -LiteralPath $Destination -Force
  }

  Write-Output "Downloading: $Uri"
  Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Destination
  $actualHash = (Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash.ToLowerInvariant()
  if ($actualHash -ne $ExpectedHash) {
    Remove-Item -LiteralPath $Destination -Force
    throw "$Algorithm mismatch for $Uri. Expected $ExpectedHash, got $actualHash"
  }
}

function ConvertTo-Base36 {
  param([Numerics.BigInteger]$Value)
  $characters = "0123456789abcdefghijklmnopqrstuvwxyz"
  if ($Value -eq 0) {
    return "0"
  }
  $result = ""
  while ($Value -gt 0) {
    $remainder = [int]($Value % 36)
    $result = $characters[$remainder] + $result
    $Value = [Numerics.BigInteger]::Divide($Value, 36)
  }
  return $result
}

function Get-GradleWrapperUrlHash {
  param([string]$Uri)
  $digest = [Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($Uri))
  [Array]::Reverse($digest)
  $positiveBytes = New-Object byte[] ($digest.Length + 1)
  [Array]::Copy($digest, $positiveBytes, $digest.Length)
  return ConvertTo-Base36 ([Numerics.BigInteger]::new($positiveBytes))
}

New-Item -ItemType Directory -Force -Path $toolRoot, $downloads, $staging, $sdkRoot | Out-Null

$jdkArchive = Join-Path $downloads "microsoft-jdk-17.0.19-windows-x64.zip"
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome "bin\java.exe") -PathType Leaf)) {
  Get-VerifiedDownload $jdkUrl $jdkArchive $jdkSha256 "SHA256"
  $jdkStage = Join-Path $staging "jdk-17"
  Reset-Directory $jdkStage
  Expand-Archive -LiteralPath $jdkArchive -DestinationPath $jdkStage -Force
  $jdkSource = Get-ChildItem -LiteralPath $jdkStage -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe") } |
    Select-Object -First 1
  if (-not $jdkSource) {
    throw "Could not find bin\java.exe in the JDK archive"
  }
  if (Test-Path -LiteralPath $jdkHome) {
    Assert-UnderToolRoot $jdkHome
    Remove-Item -LiteralPath $jdkHome -Recurse -Force
  }
  Move-Item -LiteralPath $jdkSource.FullName -Destination $jdkHome
}

$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
  $toolsArchive = Join-Path $downloads "commandlinetools-win-15641748_latest.zip"
  Get-VerifiedDownload $commandLineToolsUrl $toolsArchive $commandLineToolsSha1 "SHA1"
  $toolsStage = Join-Path $staging "command-line-tools"
  Reset-Directory $toolsStage
  Expand-Archive -LiteralPath $toolsArchive -DestinationPath $toolsStage -Force
  $toolsSource = Join-Path $toolsStage "cmdline-tools"
  if (-not (Test-Path -LiteralPath (Join-Path $toolsSource "bin\sdkmanager.bat") -PathType Leaf)) {
    throw "Could not find sdkmanager.bat in the command-line tools archive"
  }
  $latest = Join-Path $sdkRoot "cmdline-tools\latest"
  if (Test-Path -LiteralPath $latest) {
    Assert-UnderToolRoot $latest
    Remove-Item -LiteralPath $latest -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path (Split-Path $latest -Parent) | Out-Null
  Move-Item -LiteralPath $toolsSource -Destination $latest
}

. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

if ($AcceptSdkLicenses) {
  Write-Output "Accepting Android SDK licenses requested by the setup command..."
  $licenseAnswers = Join-Path $toolRoot "license-answers.txt"
  [IO.File]::WriteAllLines(
    $licenseAnswers,
    [string[]](1..100 | ForEach-Object { "y" }),
    [Text.UTF8Encoding]::new($false)
  )
  try {
    $licenseProcess = Start-Process -FilePath $sdkManager `
      -ArgumentList @("--sdk_root=$sdkRoot", "--licenses") `
      -RedirectStandardInput $licenseAnswers `
      -NoNewWindow -Wait -PassThru
    if ($licenseProcess.ExitCode -ne 0) {
      throw "sdkmanager --licenses failed with exit code $($licenseProcess.ExitCode)"
    }
  } finally {
    if (Test-Path -LiteralPath $licenseAnswers) {
      Remove-Item -LiteralPath $licenseAnswers -Force
    }
  }
} else {
  throw "Android SDK licenses must be accepted to install packages. Re-run with -AcceptSdkLicenses."
}

Write-Output "Installing Android SDK packages..."
$packageProcess = Start-Process -FilePath $sdkManager `
  -ArgumentList @(
    "--sdk_root=$sdkRoot",
    "platform-tools",
    "platforms;android-36",
    "build-tools;36.0.0"
  ) `
  -NoNewWindow -Wait -PassThru
if ($packageProcess.ExitCode -ne 0) {
  throw "Android SDK package installation failed with exit code $($packageProcess.ExitCode)"
}

$requiredSdkFiles = @(
  (Join-Path $sdkRoot "platform-tools\adb.exe"),
  (Join-Path $sdkRoot "platforms\android-36\android.jar"),
  (Join-Path $sdkRoot "build-tools\36.0.0\aapt2.exe")
)
$missingSdkFiles = @($requiredSdkFiles | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
if ($missingSdkFiles.Count) {
  throw "sdkmanager returned success but required files are missing: $($missingSdkFiles -join ', ')"
}

$gradleArchive = Join-Path $downloads "gradle-9.4.1-bin.zip"
$gradleExecutable = Join-Path $gradleHome "bin\gradle.bat"
Get-VerifiedDownload $gradleUrl $gradleArchive $gradleSha256 "SHA256"
if (-not (Test-Path -LiteralPath $gradleExecutable -PathType Leaf)) {
  $gradleStage = Join-Path $staging "gradle-9.4.1"
  Reset-Directory $gradleStage
  Expand-Archive -LiteralPath $gradleArchive -DestinationPath $gradleStage -Force
  $gradleSource = Join-Path $gradleStage "gradle-9.4.1"
  if (-not (Test-Path -LiteralPath (Join-Path $gradleSource "bin\gradle.bat") -PathType Leaf)) {
    throw "Could not find Gradle in its archive"
  }
  if (Test-Path -LiteralPath $gradleHome) {
    Assert-UnderToolRoot $gradleHome
    Remove-Item -LiteralPath $gradleHome -Recurse -Force
  }
  Move-Item -LiteralPath $gradleSource -Destination $gradleHome
}

$sdkPropertyPath = $sdkRoot.Replace('\', '/').Replace(':', '\:')
$localProperties = Join-Path $androidProject "local.properties"
[IO.File]::WriteAllText($localProperties, "sdk.dir=$sdkPropertyPath`n", [Text.UTF8Encoding]::new($false))

Write-Output "Generating Gradle wrapper..."
& $gradleExecutable -p $androidProject wrapper --gradle-version 9.4.1 --distribution-type bin | Out-Host
if ($LASTEXITCODE -ne 0) {
  throw "Gradle wrapper generation failed with exit code $LASTEXITCODE"
}

$wrapperProperties = Join-Path $androidProject "gradle\wrapper\gradle-wrapper.properties"
$wrapperText = Get-Content -Raw -LiteralPath $wrapperProperties
$wrapperText = [Text.RegularExpressions.Regex]::Replace(
  $wrapperText,
  '(?m)^distributionUrl=.*$',
  'distributionUrl=https\://downloads.gradle.org/distributions/gradle-9.4.1-bin.zip'
)
$wrapperText = [Text.RegularExpressions.Regex]::Replace(
  $wrapperText,
  '(?m)^networkTimeout=.*$',
  'networkTimeout=60000'
)
if ($wrapperText -notmatch '(?m)^distributionSha256Sum=') {
  $wrapperText = $wrapperText.TrimEnd() + "`ndistributionSha256Sum=$gradleSha256`n"
}
[IO.File]::WriteAllText($wrapperProperties, $wrapperText, [Text.UTF8Encoding]::new($false))

# The standard Gradle Wrapper remains configured with the official CDN URL.
# Seed its project-local cache from the already verified bootstrap archive so
# Windows machines with a blocked Java HTTPS path can still run the wrapper.
$wrapperDistributionUrl = "https://downloads.gradle.org/distributions/gradle-9.4.1-bin.zip"
$wrapperUrlHash = Get-GradleWrapperUrlHash $wrapperDistributionUrl
$wrapperCacheDir = Join-Path $env:GRADLE_USER_HOME "wrapper\dists\gradle-9.4.1-bin\$wrapperUrlHash"
Assert-UnderToolRoot $wrapperCacheDir
New-Item -ItemType Directory -Force -Path $wrapperCacheDir | Out-Null
$wrapperZip = Join-Path $wrapperCacheDir "gradle-9.4.1-bin.zip"
Copy-Item -LiteralPath $gradleArchive -Destination $wrapperZip -Force
foreach ($partialName in "gradle-9.4.1-bin.zip.part", "gradle-9.4.1-bin.zip.lck") {
  $partialPath = Join-Path $wrapperCacheDir $partialName
  if (Test-Path -LiteralPath $partialPath) {
    Remove-Item -LiteralPath $partialPath -Force
  }
}

Write-Output "Android project-local toolchain is ready."
Write-Output "Next: npm.cmd run android:doctor"
Write-Output "Then: npm.cmd run android:assemble"
