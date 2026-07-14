@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "REPO_ROOT=%~dp0.."
set "THIS_SCRIPT=%~f0"
set "ARG_FILE=%TEMP%\pokemon_champions_ocr_args_%RANDOM%_%RANDOM%.txt"

if exist "%ARG_FILE%" del /f /q "%ARG_FILE%" >nul 2>nul

:collect_args
if "%~1"=="" goto run_script
>>"%ARG_FILE%" echo(%~f1
shift
goto collect_args

:run_script
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $env:THIS_SCRIPT; $marker = '### POWERSHELL ###'; $idx = $raw.LastIndexOf($marker); if ($idx -lt 0) { throw 'Embedded PowerShell marker not found.' }; $code = $raw.Substring($idx + $marker.Length); & ([scriptblock]::Create($code)) -RepoRoot $env:REPO_ROOT -ArgFile $env:ARG_FILE"
set "ERR=%ERRORLEVEL%"

if exist "%ARG_FILE%" del /f /q "%ARG_FILE%" >nul 2>nul
echo.
pause
exit /b %ERR%

### POWERSHELL ###
param(
  [string]$RepoRoot,
  [string]$ArgFile
)

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Stop-WithMessage {
  param([string]$Message)
  Write-Host $Message -ForegroundColor Red
  exit 1
}

function Resolve-ReadablePath {
  param([string]$RelativePath)

  $fullPath = if ([System.IO.Path]::IsPathRooted($RelativePath)) {
    $RelativePath
  } else {
    Join-Path $script:RepoRootPath $RelativePath
  }

  $resolver = Join-Path $env:USERPROFILE ".codex\skills\local-encrypted-files\scripts\resolve_readable_path.ps1"
  if (Test-Path -LiteralPath $resolver) {
    $resolved = & $resolver -InputPath $fullPath -Quiet
    if ($LASTEXITCODE -eq 0 -and $resolved) {
      return ([string]($resolved | Select-Object -First 1)).Trim()
    }
  }

  return $fullPath
}

function Find-RapidOcrPython {
  $candidates = @(
    (Join-Path $script:RepoRootPath ".tmp\rapidocr-venv\Scripts\python.exe"),
    $env:RAPIDOCR_PYTHON,
    "python"
  ) | Where-Object { $_ }

  foreach ($candidate in $candidates) {
    if ([System.IO.Path]::IsPathRooted($candidate)) {
      if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        continue
      }
    } elseif (-not (Get-Command $candidate -ErrorAction SilentlyContinue)) {
      continue
    }

    try {
      & $candidate -c "import rapidocr, onnxruntime" >$null 2>$null
      if ($LASTEXITCODE -eq 0) {
        return $candidate
      }
    } catch {
      continue
    }
  }

  return $null
}

function Test-NodeSharp {
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    return $false
  }

  & node -e "import('sharp').then(() => process.exit(0)).catch(() => process.exit(1))" >$null 2>$null
  return $LASTEXITCODE -eq 0
}

function Get-OcrImageKind {
  param([string]$ImagePath)

  $normalized = $ImagePath.ToLowerInvariant()
  if ($normalized -match 'own_team_move_item|move[_-]?item|moves?[_-]?item|招式|道具') {
    return "move"
  }
  if ($normalized -match 'own_team_stats|stats?|能力|数值') {
    return "stats"
  }
  return $null
}

function Request-OcrImageKind {
  param(
    [string]$ImagePath,
    [int]$Index
  )

  while ($true) {
    Write-Host ""
    Write-Host ("Cannot classify image {0}: {1}" -f $Index, $ImagePath)
    $answer = Read-Host "Type m for move/item page, s for stats page, or q to cancel"
    if ($answer -match '^[mM]$') {
      return "move"
    }
    if ($answer -match '^[sS]$') {
      return "stats"
    }
    if ($answer -match '^[qQ]$') {
      Stop-WithMessage "Cancelled."
    }
  }
}

$script:RepoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
Set-Location -LiteralPath $script:RepoRootPath

$images = @()
if (Test-Path -LiteralPath $ArgFile) {
  $images = @(Get-Content -LiteralPath $ArgFile | Where-Object { $_ })
}

if (-not $images.Count) {
  Write-Host "Usage: drag one or more own-team screenshots onto this script."
  Write-Host "For a damage-ready result, drag both the move/item page and the stats page together."
  exit 1
}

$validImages = @()
foreach ($image in $images) {
  if (-not (Test-Path -LiteralPath $image -PathType Leaf)) {
    Stop-WithMessage "Input file does not exist: $image"
  }
  if ([System.IO.Path]::GetExtension($image) -notmatch '^\.(png|jpe?g|webp|bmp)$') {
    Stop-WithMessage "Input is not a supported image: $image"
  }
  $validImages += (Resolve-Path -LiteralPath $image).Path
}

$hasNodeSharp = Test-NodeSharp
$python = Find-RapidOcrPython
$hasMissingDependency = $false

if (-not $hasNodeSharp) {
  Write-Host "Missing Node dependency: sharp."
  Write-Host "Install project Node dependencies before using this OCR test script."
  $hasMissingDependency = $true
}

if (-not $python) {
  Write-Host "RapidOCR Python environment was not found."
  Write-Host "Create one with:"
  Write-Host "  python -m venv .tmp\rapidocr-venv"
  Write-Host "  .\.tmp\rapidocr-venv\Scripts\python.exe -m pip install rapidocr onnxruntime"
  $hasMissingDependency = $true
}

if ($hasMissingDependency) {
  exit 1
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $script:RepoRootPath ".tmp\manual-test-scripts\ocr\$stamp"
$moveItemDir = Join-Path $runDir "move_item"
$statsDir = Join-Path $runDir "stats"
$outputDir = Join-Path $runDir "raw"
$savedTeamDir = Join-Path $runDir "saved-teams"

New-Item -ItemType Directory -Force -Path $moveItemDir, $statsDir, $outputDir, $savedTeamDir | Out-Null

$moveItemImages = @()
$statsImages = @()
$index = 0
foreach ($image in $validImages) {
  $index += 1
  $kind = Get-OcrImageKind $image
  if (-not $kind) {
    $kind = Request-OcrImageKind $image $index
  }
  if ($kind -eq "move") {
    $moveItemImages += $image
  } elseif ($kind -eq "stats") {
    $statsImages += $image
  }
}

if (-not $moveItemImages.Count -or -not $statsImages.Count) {
  Write-Host "Need at least one move/item page and one stats page for damage-ready OCR output."
  Write-Host "Tip: file or folder names containing own_team_move_item, move_item, 招式, 道具, own_team_stats, stats, 能力, or 数值 are classified automatically."
  exit 1
}

$index = 0
foreach ($image in $moveItemImages) {
  $index += 1
  $fileName = "{0:D2}_{1}" -f $index, [System.IO.Path]::GetFileName($image)
  Copy-Item -LiteralPath $image -Destination (Join-Path $moveItemDir $fileName) -Force
}

$index = 0
foreach ($image in $statsImages) {
  $index += 1
  $fileName = "{0:D2}_{1}" -f $index, [System.IO.Path]::GetFileName($image)
  Copy-Item -LiteralPath $image -Destination (Join-Path $statsDir $fileName) -Force
}

$nameEntries = Resolve-ReadablePath "src\data\localization\zh-Hans.json"
$nodeArgs = @(
  "tools/recognition/extract-own-team-rapidocr.mjs",
  "--move-item-dir", $moveItemDir,
  "--stats-dir", $statsDir,
  "--output-dir", $outputDir,
  "--saved-team-dir", $savedTeamDir,
  "--team-name", "ocr-import-$stamp",
  "--name-entries", $nameEntries,
  "--python", $python,
  "--compact"
)

Write-Host "Running RapidOCR own-team extraction..."
$nodeOutput = & node @nodeArgs
if ($LASTEXITCODE -ne 0) {
  Stop-WithMessage "OCR extractor failed. Run folder: $runDir"
}

$summaryText = ($nodeOutput | Out-String).Trim()
$summary = $summaryText | ConvertFrom-Json

Write-Host ""
Write-Host "OCR test result"
Write-Host ("Run folder: {0}" -f $runDir)
Write-Host ("Input images: {0}" -f $validImages.Count)

if ($null -eq $summary.savedTeam) {
  Write-Host "No SavedOwnTeam was produced. Drag the stats page together with the move/item page for a complete result."
  Write-Host ("Raw output folder: {0}" -f $outputDir)
  exit 2
}

$team = $summary.savedTeam
Write-Host ("Team: {0}" -f $team.teamName)
Write-Host ("Status: {0}; damageReady={1}" -f $team.status, $team.damageReady)

if ($team.warnings -and $team.warnings.Count) {
  Write-Host ""
  Write-Host "Warnings:"
  foreach ($warning in $team.warnings) {
    Write-Host ("  - {0}" -f $warning)
  }
}

$builds = @($team.members | ForEach-Object { $_.build })
$payloadPath = Join-Path $runDir "known-pokemon-builds.json"
ConvertTo-Json -InputObject $builds -Depth 40 | Set-Content -LiteralPath $payloadPath -Encoding UTF8

Write-Host ""
Write-Host "KnownPokemonBuild[] JSON:"
ConvertTo-Json -InputObject $builds -Depth 40
Write-Host ""
Write-Host ("Saved JSON: {0}" -f $payloadPath)
