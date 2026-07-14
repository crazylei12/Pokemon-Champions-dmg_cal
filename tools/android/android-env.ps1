param(
  [switch]$Quiet
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$toolRoot = Join-Path $repoRoot ".android-tools"
$jdkHome = Join-Path $toolRoot "jdk-17"
$sdkRoot = Join-Path $toolRoot "android-sdk"
$gradleUserHome = Join-Path $toolRoot "gradle-home"
$androidUserHome = Join-Path $toolRoot "android-user-home"

$java = Join-Path $jdkHome "bin\java.exe"
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"

if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
  throw "Project-local JDK is missing. Run: npm.cmd run android:setup"
}

if (-not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
  throw "Project-local Android command-line tools are missing. Run: npm.cmd run android:setup"
}

New-Item -ItemType Directory -Force -Path $gradleUserHome, $androidUserHome | Out-Null

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkRoot
# Modern Android tooling uses ANDROID_HOME. A stale machine-level
# ANDROID_SDK_ROOT pointing at another SDK makes Gradle reject the build.
$env:ANDROID_SDK_ROOT = $null
$env:ANDROID_USER_HOME = $androidUserHome
$env:GRADLE_USER_HOME = $gradleUserHome
# A machine-level DEBUG value makes Gradle and Android SDK batch launchers echo
# every internal command. It is unrelated to the Android build variant.
$env:DEBUG = $null

$toolPaths = @(
  (Join-Path $jdkHome "bin"),
  (Join-Path $sdkRoot "cmdline-tools\latest\bin"),
  (Join-Path $sdkRoot "platform-tools")
)
$env:Path = (($toolPaths + @($env:Path)) -join [IO.Path]::PathSeparator)

if (-not $Quiet) {
  Write-Output "JAVA_HOME=$env:JAVA_HOME"
  Write-Output "ANDROID_HOME=$env:ANDROID_HOME"
  Write-Output "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
}
