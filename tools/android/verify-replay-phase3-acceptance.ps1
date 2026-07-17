param(
  [Parameter(Mandatory = $true)]
  [string]$Serial,
  [long]$ReplayId = 0,
  [long]$MinimumDurationMs = 600000,
  [switch]$ExpectSilent,
  [switch]$SkipBaseline,
  [string]$OutputRoot
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$gamePackage = "jp.pokemon.pokemonchampions"
$debugPackage = "com.crazylei12.pokemonchampionsassistant.debug"
$probeActivity = "$debugPackage/com.crazylei12.pokemonchampionsassistant.replayprobe.ReplayProbeActivity"
$verifyAction = "$debugPackage.VERIFY_REPLAY_ARTIFACT"
$replayCollection = "content://media/external_primary/video/media"
$replayRelativePath = "DCIM/Pokemon Champions Replays/"
$externalProbeRoot = "/sdcard/Android/data/$debugPackage/files/replay-probe"

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
  throw "ADB is missing: $adb"
}
if ($ReplayId -lt 0) {
  throw "ReplayId cannot be negative."
}
if ($MinimumDurationMs -le 0) {
  throw "MinimumDurationMs must be positive."
}

function Invoke-AdbRaw {
  param(
    [string[]]$Arguments,
    [switch]$AllowFailure
  )

  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = & $script:adb @Arguments 2>&1
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousPreference
  }

  $text = ($lines | Out-String).TrimEnd()
  if ($exitCode -ne 0 -and -not $AllowFailure) {
    throw "ADB failed ($exitCode): adb $($Arguments -join ' ')`n$text"
  }
  return $text
}

function Invoke-DeviceAdb {
  param(
    [string[]]$Arguments,
    [switch]$AllowFailure
  )

  return Invoke-AdbRaw -Arguments (@("-s", $script:Serial) + $Arguments) -AllowFailure:$AllowFailure
}

function Invoke-DeviceShell {
  param(
    [string[]]$Arguments,
    [switch]$AllowFailure
  )

  return Invoke-DeviceAdb -Arguments (@("shell") + $Arguments) -AllowFailure:$AllowFailure
}

function Add-Check {
  param(
    [string]$Name,
    [bool]$Passed,
    [string]$Evidence
  )

  $script:Checks.Add([pscustomobject]@{
    name = $Name
    passed = $Passed
    evidence = $Evidence
  })
}

function Get-PackageDump {
  param([string]$PackageName)

  return Invoke-DeviceShell -Arguments @("dumpsys", "package", $PackageName) -AllowFailure
}

function Get-ProbeReports {
  $listing = Invoke-DeviceShell -Arguments @(
    "run-as", $script:debugPackage, "ls", "-1", "files/replay-probe"
  ) -AllowFailure
  $names = @(
    $listing -split "`r?`n" |
      Where-Object { $_ -match '^\d+-(audible-game|muted-game|other-app-tone)\.json$' } |
      Sort-Object
  )
  if ($names.Count -eq 0) {
    throw "No Phase 0 PCM probe reports are available in $debugPackage private storage."
  }

  $reports = foreach ($name in $names) {
    $json = Invoke-DeviceShell -Arguments @(
      "run-as", $script:debugPackage, "cat", "files/replay-probe/$name"
    )
    $localPath = Join-Path $script:ProbeOutputDirectory $name
    Set-Content -LiteralPath $localPath -Value $json -Encoding UTF8
    $report = $json | ConvertFrom-Json
    [pscustomobject]@{
      name = $name
      timestamp = [long]($name -replace '-.*$', '')
      scenario = [string]$report.scenario
      report = $report
    }
  }

  return @(
    $reports |
      Group-Object scenario |
      ForEach-Object { $_.Group | Sort-Object timestamp -Descending | Select-Object -First 1 }
  )
}

function Test-ProbeReports {
  param([object[]]$Reports)

  foreach ($scenario in @("audible-game", "muted-game", "other-app-tone")) {
    $entry = $Reports | Where-Object { $_.scenario -eq $scenario } | Select-Object -First 1
    Add-Check "PCM probe exists: $scenario" ([bool]$entry) $(if ($entry) { $entry.name } else { "missing" })
    if (-not $entry) { continue }

    $report = $entry.report
    Add-Check "$scenario completed without error" (-not [bool]$report.error) $(if ($report.error) { [string]$report.error } else { "error=null" })
    Add-Check "$scenario duration is about 10 seconds" (
      [long]$report.durationMs -ge 9000 -and [long]$report.durationMs -le 12000
    ) "$($report.durationMs) ms"
    Add-Check "$scenario uses the target game UID" (
      [string]$report.gamePackage -eq $script:gamePackage -and [long]$report.gameUid -gt 0
    ) "$($report.gamePackage), uid=$($report.gameUid)"
    Add-Check "$scenario single-app isolation" ([bool]$report.video.singleAppIsolationPass) (
      "frames=$($report.video.framesAnalyzed), markerDetected=$($report.video.markerDetected)"
    )
    Add-Check "$scenario PCM format" (
      [int]$report.audio.sampleRate -eq 48000 -and [int]$report.audio.channelCount -in @(1, 2)
    ) "$($report.audio.sampleRate) Hz, $($report.audio.channelCount) channel(s)"

    if ($scenario -eq "audible-game") {
      Add-Check "audible game signal is captured" ([bool]$report.audio.signalDetected) (
        "nonZeroRatio=$($report.audio.nonZeroRatio), dbfs=$($report.audio.dbfs)"
      )
    } elseif ($scenario -eq "muted-game") {
      Add-Check "muted game is digital silence" (
        -not [bool]$report.audio.signalDetected -and [long]$report.audio.nonZeroSamples -eq 0
      ) "nonZeroSamples=$($report.audio.nonZeroSamples)"
    } else {
      Add-Check "other-app control tone was played" ([bool]$report.audio.otherAppTonePlayed) (
        "otherAppTonePlayed=$($report.audio.otherAppTonePlayed)"
      )
      Add-Check "other-app audio is excluded" (
        -not [bool]$report.audio.signalDetected -and [long]$report.audio.nonZeroSamples -eq 0
      ) "nonZeroSamples=$($report.audio.nonZeroSamples)"
    }
  }
}

function Get-ReplayRows {
  $query = Invoke-DeviceShell -Arguments @(
    "content", "query",
    "--uri", $script:replayCollection,
    "--projection", "_id:_display_name:duration:_size:is_pending:relative_path"
  )
  Set-Content -LiteralPath (Join-Path $script:RunDirectory "media-store.txt") -Value $query -Encoding UTF8

  $rows = foreach ($line in $query -split "`r?`n") {
    if (
      $line -match '_id=(\d+), _display_name=(.*?), duration=(\d+), _size=(\d+), is_pending=(\d+), relative_path=(.*)$'
    ) {
      $relativePath = $Matches[6]
      if ($relativePath -eq $script:replayRelativePath) {
        [pscustomobject]@{
          id = [long]$Matches[1]
          displayName = $Matches[2]
          durationMs = [long]$Matches[3]
          sizeBytes = [long]$Matches[4]
          isPending = [int]$Matches[5]
          relativePath = $relativePath
        }
      }
    }
  }
  return @($rows)
}

function Get-InspectionDirectories {
  $listing = Invoke-DeviceShell -Arguments @("ls", "-1", $script:externalProbeRoot) -AllowFailure
  return @($listing -split "`r?`n" | Where-Object { $_ -match '^artifact-inspection-\d+$' })
}

function Invoke-ReplayInspection {
  param([long]$MediaId)

  $activeServices = Invoke-DeviceShell -Arguments @(
    "dumpsys", "activity", "services", $script:debugPackage
  ) -AllowFailure
  if ($activeServices -match 'ReplayProbeService') {
    throw "A PCM probe service is still active. Stop it before artifact verification."
  }

  $before = @(Get-InspectionDirectories)
  $uri = "$($script:replayCollection)/$MediaId"
  Invoke-DeviceShell -Arguments @(
    "am", "start", "-W",
    "-n", $script:probeActivity,
    "-a", $script:verifyAction,
    "-d", $uri
  ) | Out-Null

  $deadline = [DateTime]::UtcNow.AddSeconds(30)
  $inspectionName = $null
  do {
    Start-Sleep -Milliseconds 500
    $after = @(Get-InspectionDirectories)
    $inspectionName = @($after | Where-Object { $_ -notin $before } | Sort-Object -Descending) | Select-Object -First 1
  } while (-not $inspectionName -and [DateTime]::UtcNow -lt $deadline)

  if (-not $inspectionName) {
    throw "Timed out waiting for ReplayArtifactVerifier output for $uri"
  }

  $remoteDirectory = "$($script:externalProbeRoot)/$inspectionName"
  $remoteInspection = "$remoteDirectory/inspection.json"
  do {
    $inspectionListing = Invoke-DeviceShell -Arguments @("ls", $remoteInspection) -AllowFailure
    if ($inspectionListing -match 'inspection\.json$') { break }
    Start-Sleep -Milliseconds 500
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($inspectionListing -notmatch 'inspection\.json$') {
    throw "Timed out waiting for ReplayArtifactVerifier to finish $uri"
  }

  $localParent = Join-Path $script:RunDirectory "artifact"
  New-Item -ItemType Directory -Force -Path $localParent | Out-Null
  Invoke-DeviceAdb -Arguments @("pull", $remoteDirectory, $localParent) | Out-Null
  $localDirectory = Join-Path $localParent $inspectionName
  $inspectionPath = Join-Path $localDirectory "inspection.json"
  if (-not (Test-Path -LiteralPath $inspectionPath -PathType Leaf)) {
    throw "ReplayArtifactVerifier did not produce inspection.json"
  }
  return [pscustomobject]@{
    uri = $uri
    directory = $localDirectory
    report = (Get-Content -Raw -Encoding UTF8 -LiteralPath $inspectionPath | ConvertFrom-Json)
  }
}

function Test-ReplayInspection {
  param(
    [object]$MediaRow,
    [object]$Inspection
  )

  $report = $Inspection.report
  $tracks = @($report.tracks)
  $video = $tracks | Where-Object { $_.mime -eq "video/avc" } | Select-Object -First 1
  $audio = $tracks | Where-Object { $_.mime -eq "audio/mp4a-latm" } | Select-Object -First 1
  $measuredVideoFps = if ($video -and [long]$video.durationUs -gt 0) {
    [double]$video.sampleCount * 1000000.0 / [double]$video.durationUs
  } else {
    0.0
  }

  Add-Check "MediaStore replay is published" ($MediaRow.isPending -eq 0) (
    "id=$($MediaRow.id), isPending=$($MediaRow.isPending), name=$($MediaRow.displayName)"
  )
  Add-Check "Replay duration reaches the requested minimum" (
    [long]$report.durationMs -ge $script:MinimumDurationMs
  ) "$($report.durationMs) ms >= $($script:MinimumDurationMs) ms"
  Add-Check "Replay container is readable MP4" ([bool]$report.ok -and [string]$report.mime -eq "video/mp4") (
    "ok=$($report.ok), mime=$($report.mime)"
  )
  Add-Check "Replay canvas is 960 x 540" (
    [int]$report.width -eq 960 -and [int]$report.height -eq 540
  ) "$($report.width) x $($report.height)"
  Add-Check "H.264 track is approximately 24 fps with samples" (
    [bool]$video -and [int]$video.width -eq 960 -and [int]$video.height -eq 540 -and
      [int]$video.frameRate -in @(23, 24) -and $measuredVideoFps -ge 23.0 -and
      $measuredVideoFps -le 24.1 -and [long]$video.sampleCount -gt 0
  ) $(if ($video) {
    "metadataFps=$($video.frameRate), measuredFps=$($measuredVideoFps.ToString('F3')), samples=$($video.sampleCount)"
  } else { "missing" })
  Add-Check "Five timeline checkpoints decode" (
    @($report.frames).Count -eq 5 -and @($report.frames | Where-Object {
      [int]$_.width -eq 960 -and [int]$_.height -eq 540
    }).Count -eq 5
  ) "decodedFrames=$(@($report.frames).Count)"

  if ($script:ExpectSilent) {
    Add-Check "Silent fallback has no audio track" (-not [bool]$audio) $(if ($audio) { "unexpected AAC track" } else { "no audio track" })
  } else {
    Add-Check "AAC track format and samples" (
      [bool]$audio -and [int]$audio.sampleRate -eq 48000 -and
        [int]$audio.channelCount -in @(1, 2) -and [long]$audio.sampleCount -gt 0
    ) $(if ($audio) { "$($audio.sampleRate) Hz, $($audio.channelCount) channel(s), samples=$($audio.sampleCount)" } else { "missing" })
    if ($video -and $audio) {
      $ptsDifferenceUs = [Math]::Abs([long]$video.lastPtsUs - [long]$audio.lastPtsUs)
      Add-Check "Audio/video end PTS difference is at most 100 ms" ($ptsDifferenceUs -le 100000) (
        "$ptsDifferenceUs us"
      )
    } else {
      Add-Check "Audio/video end PTS difference is at most 100 ms" $false "missing audio or video track"
    }
  }
}

function Write-Reports {
  param(
    [object]$Device,
    [object]$MediaRow,
    [object]$Inspection,
    [object[]]$ProbeReports,
    [string]$BaselineDirectory
  )

  $failed = @($script:Checks | Where-Object { -not $_.passed })
  $result = [pscustomobject]@{
    schemaVersion = 1
    capturedAt = [DateTime]::UtcNow.ToString("o")
    serial = $script:Serial
    device = $Device
    baselineDirectory = $BaselineDirectory
    probeReports = @($ProbeReports | ForEach-Object { $_.name })
    mediaStore = $MediaRow
    inspectionUri = $Inspection.uri
    inspectionDirectory = $Inspection.directory
    checks = @($script:Checks | ForEach-Object { $_ })
    passed = $failed.Count -eq 0
  }
  $jsonPath = Join-Path $script:RunDirectory "acceptance.json"
  Set-Content -LiteralPath $jsonPath -Value ($result | ConvertTo-Json -Depth 8) -Encoding UTF8

  $markdown = New-Object System.Collections.Generic.List[string]
  $markdown.Add("# Battle replay Phase 3 device acceptance")
  $markdown.Add("")
  $markdown.Add("- Serial: ``$($script:Serial)``")
  $markdown.Add("- Device: $($Device.manufacturer) $($Device.model), Android $($Device.androidVersion) / API $($Device.apiLevel)")
  $markdown.Add("- Replay: ``$($MediaRow.displayName)`` (MediaStore ``$($MediaRow.id)``)")
  $markdown.Add("- Overall: **$(if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' })**")
  $markdown.Add("")
  $markdown.Add("| Check | Result | Evidence |")
  $markdown.Add("| --- | --- | --- |")
  foreach ($check in $script:Checks) {
    $evidence = ([string]$check.evidence).Replace("|", "\\|").Replace("`r", " ").Replace("`n", " ")
    $markdown.Add("| $($check.name) | $(if ($check.passed) { 'PASS' } else { 'FAIL' }) | $evidence |")
  }
  $markdownPath = Join-Path $script:RunDirectory "report.md"
  Set-Content -LiteralPath $markdownPath -Value $markdown -Encoding UTF8

  Write-Output "Phase 3 acceptance: $(if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' })"
  Write-Output "Report: $markdownPath"
  Write-Output "JSON: $jsonPath"
  if ($failed.Count -gt 0) {
    throw "$($failed.Count) Phase 3 acceptance check(s) failed."
  }
}

$devices = Invoke-AdbRaw -Arguments @("devices")
$deviceLines = @($devices -split "`r?`n" | Where-Object { $_ -match '^([^\s]+)\s+device$' })
$targetLine = $deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" } | Select-Object -First 1
if (-not $targetLine) {
  throw "ADB device is not online: $Serial"
}

if (-not $OutputRoot) {
  $OutputRoot = Join-Path $repoRoot ".tmp\replay-phase3"
}
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$RunDirectory = Join-Path (Join-Path $OutputRoot $timestamp) $Serial
$ProbeOutputDirectory = Join-Path $RunDirectory "pcm-probes"
New-Item -ItemType Directory -Force -Path $ProbeOutputDirectory | Out-Null
$Checks = New-Object System.Collections.Generic.List[object]

$manufacturer = (Invoke-DeviceShell -Arguments @("getprop", "ro.product.manufacturer")).Trim()
$model = (Invoke-DeviceShell -Arguments @("getprop", "ro.product.model")).Trim()
$androidVersion = (Invoke-DeviceShell -Arguments @("getprop", "ro.build.version.release")).Trim()
$apiLevel = [int](Invoke-DeviceShell -Arguments @("getprop", "ro.build.version.sdk")).Trim()
$gameDump = Get-PackageDump -PackageName $gamePackage
$debugDump = Get-PackageDump -PackageName $debugPackage
$device = [pscustomobject]@{
  manufacturer = $manufacturer
  model = $model
  androidVersion = $androidVersion
  apiLevel = $apiLevel
  gameInstalled = [bool]($gameDump -match "Package \[$([regex]::Escape($gamePackage))\]")
  debugAssistantInstalled = [bool]($debugDump -match "Package \[$([regex]::Escape($debugPackage))\]")
}
Add-Check "Device is Android 16 / API 36 or newer" ($apiLevel -ge 36) "Android $androidVersion / API $apiLevel"
Add-Check "Pokemon Champions is installed" $device.gameInstalled $gamePackage
Add-Check "Debug replay verifier is installed" $device.debugAssistantInstalled $debugPackage
if (-not $device.debugAssistantInstalled) {
  throw "The Debug assistant package is required for PCM report access and MP4 inspection: $debugPackage"
}

$baselineDirectory = $null
if (-not $SkipBaseline) {
  $baselineRoot = Join-Path $RunDirectory "baseline"
  & (Join-Path $PSScriptRoot "collect-replay-phase0-baseline.ps1") `
    -Serial $Serial `
    -GamePackage $gamePackage `
    -AssistantPackage $debugPackage `
    -OutputRoot $baselineRoot
  $baselineDirectory = $baselineRoot
}

$probeReports = @(Get-ProbeReports)
Test-ProbeReports -Reports $probeReports

$replayRows = @(Get-ReplayRows)
if ($ReplayId -gt 0) {
  $mediaRow = $replayRows | Where-Object { $_.id -eq $ReplayId } | Select-Object -First 1
} else {
  $mediaRow = $replayRows | Sort-Object id -Descending | Select-Object -First 1
}
if (-not $mediaRow) {
  throw "No published replay row was found for ReplayId=$ReplayId in $replayRelativePath"
}

$inspection = Invoke-ReplayInspection -MediaId $mediaRow.id
Test-ReplayInspection -MediaRow $mediaRow -Inspection $inspection
Write-Reports `
  -Device $device `
  -MediaRow $mediaRow `
  -Inspection $inspection `
  -ProbeReports $probeReports `
  -BaselineDirectory $baselineDirectory
