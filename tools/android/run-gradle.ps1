param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$GradleArgs = @(":app:assembleDebug")
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidProject = Join-Path $repoRoot "android-app"
$gradleWrapper = Join-Path $androidProject "gradlew.bat"

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
  throw "Gradle wrapper is missing. Run: npm.cmd run android:setup"
}

Push-Location $androidProject
try {
  & $gradleWrapper @GradleArgs
  exit $LASTEXITCODE
} finally {
  Pop-Location
}
