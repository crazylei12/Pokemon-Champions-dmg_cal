@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "REPO_ROOT=%~dp0.."
set "THIS_SCRIPT=%~f0"
set "ARG_FILE=%TEMP%\pokemon_champions_roi_args_%RANDOM%_%RANDOM%.txt"

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

function Test-PythonOpenCv {
  if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    return $false
  }

  & python -c "import cv2, numpy" >$null 2>$null
  return $LASTEXITCODE -eq 0
}

function Normalize-Identifier {
  param([string]$Value)

  if ([string]::IsNullOrWhiteSpace($Value)) {
    return ""
  }

  $normalized = $Value.Trim().ToLowerInvariant().Normalize([System.Text.NormalizationForm]::FormD)
  $builder = [System.Text.StringBuilder]::new()
  foreach ($char in $normalized.ToCharArray()) {
    $category = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($char)
    if ($category -eq [Globalization.UnicodeCategory]::NonSpacingMark) {
      continue
    }
    if ([char]::IsLetterOrDigit($char)) {
      [void]$builder.Append($char)
    }
  }
  return $builder.ToString()
}

function Add-SpeciesLookupKey {
  param(
    [string]$Key,
    $Entry
  )

  $normalized = Normalize-Identifier $Key
  if ($normalized -and -not $script:SpeciesByKey.ContainsKey($normalized)) {
    $script:SpeciesByKey[$normalized] = $Entry
  }
}

function Register-SpeciesEntry {
  param($Entry)

  Add-SpeciesLookupKey $Entry.canonicalId $Entry
  if ($Entry.canonicalId) {
    Add-SpeciesLookupKey ([string]$Entry.canonicalId -replace '^species\.', '') $Entry
  }
  Add-SpeciesLookupKey $Entry.showdownId $Entry
  Add-SpeciesLookupKey $Entry.englishName $Entry

  if ($Entry.localizedNames) {
    foreach ($name in @($Entry.localizedNames.'zh-Hans')) {
      Add-SpeciesLookupKey $name $Entry
    }
    foreach ($name in @($Entry.localizedNames.en)) {
      Add-SpeciesLookupKey $name $Entry
    }
  }
  foreach ($alias in @($Entry.aliases)) {
    Add-SpeciesLookupKey $alias $Entry
  }
}

function Resolve-SpeciesEntry {
  param($Selected)

  if ($null -eq $Selected) {
    return $null
  }

  $keys = @(
    $Selected.canonicalId,
    ([string]$Selected.canonicalId -replace '^species\.', ''),
    $Selected.pokemon_id,
    $Selected.showdownId,
    $Selected.displayName
  )
  foreach ($alias in @($Selected.aliases)) {
    $keys += $alias
  }

  foreach ($key in $keys) {
    $normalized = Normalize-Identifier ([string]$key)
    if ($normalized -and $script:SpeciesByKey.ContainsKey($normalized)) {
      return $script:SpeciesByKey[$normalized]
    }
  }

  return $null
}

function Get-SpeciesNamePair {
  param($Selected)

  if ($Selected -is [array]) {
    $Selected = @($Selected)[0]
  }

  if ($null -eq $Selected) {
    return [pscustomobject]@{
      Zh = "(unrecognized)"
      En = "(unrecognized)"
    }
  }

  $entry = Resolve-SpeciesEntry $Selected
  $zhNames = @()
  $enNames = @()
  if ($entry -and $entry.localizedNames) {
    $zhNames = @($entry.localizedNames.'zh-Hans')
    $enNames = @($entry.localizedNames.en)
  }

    $zh = if ($zhNames.Count) {
    [string]$zhNames[0]
  } elseif ($Selected.displayName) {
    [string]$Selected.displayName
  } elseif ($Selected.pokemon_id) {
    [string]$Selected.pokemon_id
  } else {
    [string]$Selected.showdownId
  }

  $en = if ($entry -and $entry.englishName) {
    [string]$entry.englishName
  } elseif ($enNames.Count) {
    [string]$enNames[0]
  } elseif ($Selected.pokemon_id) {
    [string]$Selected.pokemon_id
  } else {
    [string]$Selected.showdownId
  }

  [pscustomobject]@{
    Zh = $zh
    En = $en
  }
}

function Print-SideSlots {
  param(
    [string]$Title,
    [object[]]$Slots
  )

  Write-Host $Title
  if (-not $Slots.Count) {
    Write-Host "  (no slots)"
    return
  }

  foreach ($slot in ($Slots | Sort-Object slotIndex)) {
    $selected = @($slot.selected | Where-Object { $null -ne $_ })[0]
    $names = Get-SpeciesNamePair $selected
    $scoreValue = @($selected.score | Where-Object { $null -ne $_ })[0]
    $confidenceValue = @($selected.confidence | Where-Object { $null -ne $_ })[0]
    $score = if ($selected -and $null -ne $scoreValue) {
      "{0:N3}" -f [double]$scoreValue
    } elseif ($selected -and $null -ne $confidenceValue) {
      "{0:P1}" -f [double]$confidenceValue
    } else {
      "n/a"
    }
    Write-Host ("  {0}. {1} / {2}  score={3}" -f ([int]$slot.slotIndex + 1), $names.Zh, $names.En, $score)
  }
}

function Convert-PredictionRowToSlot {
  param($Row)

  $sideText = [string]$Row.side
  if ($sideText -notmatch '^(own|opponent)\.slot(\d+)$') {
    return $null
  }

  $side = $Matches[1].ToUpperInvariant()
  $slotIndex = [int]$Matches[2]
  $candidates = @()
  if ($Row.top5_candidates_json) {
    try {
      $candidates = @($Row.top5_candidates_json | ConvertFrom-Json)
    } catch {
      $candidates = @()
    }
  }
  $selected = if ($candidates.Count) {
    $candidates[0]
  } elseif ($Row.predicted_top1) {
    [pscustomobject]@{
      pokemon_id = $Row.predicted_top1
      score = $Row.top1_score
    }
  } else {
    $null
  }

  [pscustomobject]@{
    side = $side
    slotIndex = $slotIndex
    selected = $selected
    candidates = $candidates
  }
}

function Get-ImageKey {
  param([string]$ImagePath)

  $resolved = Resolve-Path -LiteralPath $ImagePath -ErrorAction SilentlyContinue
  if ($resolved) {
    return $resolved.Path.ToLowerInvariant()
  }
  return ([string]$ImagePath).ToLowerInvariant()
}

$script:RepoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
Set-Location -LiteralPath $script:RepoRootPath

$images = @()
if (Test-Path -LiteralPath $ArgFile) {
  $images = @(Get-Content -LiteralPath $ArgFile | Where-Object { $_ })
}

if (-not $images.Count) {
  Write-Host "Usage: drag one or more TEAM_PREVIEW screenshots onto this script."
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

if (-not (Test-PythonOpenCv)) {
  Write-Host "Missing Python OpenCV dependencies."
  Write-Host "Install them with: python -m pip install -r requirements-recognition.txt"
  exit 1
}

$nameEntries = Resolve-ReadablePath "src\data\localization\zh-Hans.json"

$script:SpeciesByKey = @{}
$localizedEntries = Get-Content -Raw -LiteralPath $nameEntries -Encoding UTF8 | ConvertFrom-Json
foreach ($entry in $localizedEntries) {
  if ($entry.entityType -eq "species" -and $entry.canonicalId) {
    Register-SpeciesEntry $entry
  }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $script:RepoRootPath ".tmp\manual-test-scripts\roi\$stamp"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$inputDir = Join-Path $runDir "input"
$outputDir = Join-Path $runDir "pipeline"
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null

$copiedImages = @()
$index = 0
foreach ($image in $validImages) {
  $index += 1
  $baseName = [System.IO.Path]::GetFileNameWithoutExtension($image)
  $safeBaseName = $baseName -replace '[<>:"/\\|?*\x00-\x1F]', '_'
  $extension = [System.IO.Path]::GetExtension($image).ToLowerInvariant()
  $copyPath = Join-Path $inputDir ("{0:D3}_{1}{2}" -f $index, $safeBaseName, $extension)
  Copy-Item -LiteralPath $image -Destination $copyPath -Force
  $copiedImages += [pscustomobject]@{
    Source = $image
    Copy = (Resolve-Path -LiteralPath $copyPath).Path
  }
}

$pythonArgs = @(
  "tools/recognition/pokemon-vision-pipeline.py",
  "predict",
  "--images-dir", $inputDir,
  "--output", $outputDir,
  "--clear-output",
  "--crop-mode", "team-preview-safe-zone",
  "--use-labeled-roi-templates",
  "--labeled-template-labels", "dataset/labels.csv",
  "--labeled-template-images-dir", "dataset/real_train",
  "--labeled-template-scope", "same-side",
  "--augment-count", "1",
  "--side-weights", "opponent:phash=0,edge=0.4,color=0.2,template=0.4",
  "--labeled-template-bonus", "0.04",
  "--skip-roi-debug-outputs"
)

Write-Host "Running TEAM_PREVIEW ROI recognition with pokemon-vision pipeline..."
& python @pythonArgs
if ($LASTEXITCODE -ne 0) {
  Stop-WithMessage "ROI recognizer failed. Run folder: $runDir"
}

$predictionsCsv = Join-Path $outputDir "predictions.csv"
if (-not (Test-Path -LiteralPath $predictionsCsv)) {
  Stop-WithMessage "ROI recognizer did not write predictions.csv. Run folder: $runDir"
}

$rowsByImage = @{}
foreach ($row in @(Import-Csv -LiteralPath $predictionsCsv -Encoding UTF8)) {
  $key = Get-ImageKey $row.image
  if (-not $rowsByImage.ContainsKey($key)) {
    $rowsByImage[$key] = @()
  }
  $rowsByImage[$key] += $row
}

foreach ($imageInfo in $copiedImages) {
  $imageRows = @($rowsByImage[(Get-ImageKey $imageInfo.Copy)])
  $slots = @()
  foreach ($row in $imageRows) {
    $slot = Convert-PredictionRowToSlot $row
    if ($null -ne $slot) {
      $slots += $slot
    }
  }

  $ownSlots = @($slots | Where-Object { $_.side -eq "OWN" })
  $opponentSlots = @($slots | Where-Object { $_.side -eq "OPPONENT" })

  Write-Host ""
  Write-Host ("ROI test result: {0}" -f $imageInfo.Source)
  Print-SideSlots "OWN team" $ownSlots
  Print-SideSlots "OPPONENT team" $opponentSlots

  Write-Host ("Raw CSV: {0}" -f $predictionsCsv)
  Write-Host ""
}
