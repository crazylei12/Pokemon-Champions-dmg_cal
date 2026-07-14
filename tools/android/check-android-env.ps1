$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sdkRoot = $env:ANDROID_HOME
$androidProject = Join-Path $repoRoot "android-app"
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$gradleWrapper = Join-Path $androidProject "gradlew.bat"

$requiredFiles = @(
  (Join-Path $env:JAVA_HOME "bin\java.exe"),
  $sdkManager,
  $adb,
  (Join-Path $sdkRoot "platforms\android-36\android.jar"),
  (Join-Path $sdkRoot "build-tools\36.0.0\aapt2.exe"),
  $gradleWrapper
)

$missing = @($requiredFiles | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
if ($missing.Count) {
  Write-Output "Missing Android toolchain files:"
  $missing | ForEach-Object { Write-Output "  - $_" }
  throw "Android toolchain is incomplete. Run: npm.cmd run android:setup"
}

Write-Output "== Java =="
& (Join-Path $env:JAVA_HOME "bin\java.exe") -version

Write-Output "`n== Android command-line tools =="
& $sdkManager --version

Write-Output "`n== ADB =="
& $adb version

Write-Output "`n== Installed SDK packages =="
& $sdkManager "--sdk_root=$sdkRoot" --list_installed

Write-Output "`n== Gradle wrapper =="
Push-Location $androidProject
try {
  & $gradleWrapper --version
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle wrapper failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}

Write-Output "`nAndroid environment check passed."
