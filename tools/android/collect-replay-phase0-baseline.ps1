param(
  [string]$Serial,
  [string]$GamePackage = "jp.pokemon.pokemonchampions",
  [string]$AssistantPackage = "com.crazylei12.pokemonchampionsassistant",
  [string]$OutputRoot
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "android-env.ps1") -Quiet

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
  throw "ADB is missing: $adb"
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

  $fullArguments = @("-s", $script:Serial) + $Arguments
  return Invoke-AdbRaw -Arguments $fullArguments -AllowFailure:$AllowFailure
}

function Invoke-DeviceShell {
  param(
    [string[]]$Arguments,
    [switch]$AllowFailure
  )

  return Invoke-DeviceAdb -Arguments (@("shell") + $Arguments) -AllowFailure:$AllowFailure
}

function Get-DeviceProperty {
  param([string]$Name)

  return (Invoke-DeviceShell -Arguments @("getprop", $Name) -AllowFailure).Trim()
}

function Save-RawText {
  param(
    [string]$Name,
    [string]$Text
  )

  $path = Join-Path $script:RunDirectory $Name
  Set-Content -LiteralPath $path -Value $Text -Encoding UTF8
  return $path
}

function Get-FirstMatch {
  param(
    [string]$Text,
    [string]$Pattern
  )

  $match = [regex]::Match($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)
  if ($match.Success) {
    return $match.Groups[1].Value.Trim()
  }
  return $null
}

function Get-PackageSnapshot {
  param([string]$PackageName)

  $listOutput = Invoke-DeviceShell -Arguments @("cmd", "package", "list", "packages", "-U", $PackageName) -AllowFailure
  $packageDump = Invoke-DeviceShell -Arguments @("dumpsys", "package", $PackageName) -AllowFailure
  Save-RawText -Name ("package-{0}.txt" -f ($PackageName -replace '[^A-Za-z0-9._-]', '_')) -Text $packageDump | Out-Null

  $listLine = @($listOutput -split "`r?`n" | Where-Object { $_ -match "^package:$([regex]::Escape($PackageName))\s" }) | Select-Object -First 1
  $uidText = if ($listLine -and $listLine -match 'uid:([^\s]+)') { $Matches[1] } else { $null }
  $uids = @()
  if ($uidText) {
    $uids = @($uidText -split ',' | Where-Object { $_ } | ForEach-Object { [int]$_ })
  }

  [pscustomobject]@{
    packageName = $PackageName
    installed = [bool]$listLine
    uids = $uids
    versionName = Get-FirstMatch -Text $packageDump -Pattern '^\s*versionName=(.+)$'
    versionCode = Get-FirstMatch -Text $packageDump -Pattern '^\s*versionCode=(\d+)\b'
    minSdk = Get-FirstMatch -Text $packageDump -Pattern '^\s*versionCode=.*\bminSdk=(\d+)\b'
    targetSdk = Get-FirstMatch -Text $packageDump -Pattern '^\s*versionCode=.*\btargetSdk=(\d+)\b'
    allowAudioPlaybackCapture = [bool]($packageDump -match '\bALLOW_AUDIO_PLAYBACK_CAPTURE\b')
  }
}

function Get-DetailValue {
  param(
    [string]$Block,
    [string]$Key
  )

  return Get-FirstMatch -Text $Block -Pattern ('^\s*string {0} = "([^"]+)"' -f [regex]::Escape($Key))
}

function Test-NumericRangeContains {
  param(
    [string]$Range,
    [double]$Value
  )

  if (-not $Range -or $Range -notmatch '^([0-9.]+)-([0-9.]+)$') {
    return $false
  }
  return $Value -ge [double]$Matches[1] -and $Value -le [double]$Matches[2]
}

function Test-SizeRangeContains {
  param(
    [string]$Range,
    [int]$Width,
    [int]$Height
  )

  if (-not $Range -or $Range -notmatch '^(\d+)x(\d+)-(\d+)x(\d+)$') {
    return $false
  }
  return $Width -ge [int]$Matches[1] -and $Height -ge [int]$Matches[2] -and
    $Width -le [int]$Matches[3] -and $Height -le [int]$Matches[4]
}

function Test-ValueListContains {
  param(
    [string]$Ranges,
    [double]$Value
  )

  foreach ($entry in @($Ranges -split ',' | Where-Object { $_ })) {
    $trimmed = $entry.Trim()
    if ($trimmed -match '^([0-9.]+)-([0-9.]+)$') {
      if ($Value -ge [double]$Matches[1] -and $Value -le [double]$Matches[2]) {
        return $true
      }
    } elseif ($trimmed -match '^[0-9.]+$' -and $Value -eq [double]$trimmed) {
      return $true
    }
  }
  return $false
}

function Get-EncoderSnapshots {
  param(
    [string]$MediaDump,
    [string]$MimeType
  )

  $mimePattern = [regex]::Escape($MimeType)
  $sectionMatches = [regex]::Matches(
    $MediaDump,
    "(?ms)^Media type '$mimePattern':\s*(?<body>.*?)(?=^Media type '|^ No media recorder client|\z)"
  )
  if (-not $sectionMatches.Count) {
    return @()
  }

  $encoders = @()
  $blocks = @()
  foreach ($sectionMatch in $sectionMatches) {
    $blocks += @([regex]::Matches(
      $sectionMatch.Groups['body'].Value,
      '(?ms)^[ \t]*Encoder "(?<name>[^"]+)" supports\s*(?<body>.*?)(?=^[ \t]*(?:Encoder|Decoder) "|\z)'
    ))
  }
  foreach ($blockMatch in $blocks) {
    $block = $blockMatch.Groups['body'].Value
    $sizeRange = Get-DetailValue -Block $block -Key "size-range"
    $frameRateRange = Get-DetailValue -Block $block -Key "frame-rate-range"
    $bitrateRange = Get-DetailValue -Block $block -Key "bitrate-range"
    $sampleRateRanges = Get-DetailValue -Block $block -Key "sample-rate-ranges"
    $maxChannelCount = Get-DetailValue -Block $block -Key "max-channel-count"
    $hardwareAccelerated = [bool]($block -match 'hw-accelerated:\s*1')
    $surfaceInput = [bool]($block -match '\(Surface\)')
    $supportsTarget = if ($MimeType -eq 'video/avc') {
      $surfaceInput -and
        (Test-SizeRangeContains -Range $sizeRange -Width 960 -Height 540) -and
        (Test-NumericRangeContains -Range $frameRateRange -Value 24) -and
        (Test-NumericRangeContains -Range $bitrateRange -Value 1500000)
    } else {
      (Test-ValueListContains -Ranges $sampleRateRanges -Value 48000) -and
        $maxChannelCount -and ([int]$maxChannelCount -ge 2) -and
        (Test-NumericRangeContains -Range $bitrateRange -Value 96000)
    }

    $encoders += [pscustomobject]@{
      mimeType = $MimeType
      name = $blockMatch.Groups['name'].Value
      hardwareAccelerated = $hardwareAccelerated
      softwareOnly = [bool]($block -match 'software-only:\s*1')
      vendor = [bool]($block -match 'vendor:\s*1')
      surfaceInput = $surfaceInput
      sizeRange = $sizeRange
      frameRateRange = $frameRateRange
      bitrateRange = $bitrateRange
      sampleRateRanges = $sampleRateRanges
      maxChannelCount = if ($maxChannelCount) { [int]$maxChannelCount } else { $null }
      supportsPhase0Target = [bool]$supportsTarget
    }
  }
  return $encoders
}

function Get-TopProcessSnapshot {
  param(
    [string]$TopOutput,
    [string]$PackageName
  )

  $line = @($TopOutput -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($PackageName) }) | Select-Object -First 1
  if (-not $line) {
    return [pscustomobject]@{ running = $false; line = $null; cpuPercent = $null; memoryPercent = $null }
  }

  $match = [regex]::Match($line, '^\s*\d+\s+\S+\s+\S+\s+\S+\s+([0-9.]+)\s+([0-9.]+)\s+')
  [pscustomobject]@{
    running = $true
    line = $line.Trim()
    cpuPercent = if ($match.Success) { [double]$match.Groups[1].Value } else { $null }
    memoryPercent = if ($match.Success) { [double]$match.Groups[2].Value } else { $null }
  }
}

$deviceList = Invoke-AdbRaw -Arguments @("devices", "-l")
$deviceRows = @()
foreach ($line in ($deviceList -split "`r?`n")) {
  if ($line -match '^(\S+)\s+(device|offline|unauthorized)\b(.*)$') {
    $deviceRows += [pscustomobject]@{ serial = $Matches[1]; state = $Matches[2]; details = $Matches[3].Trim() }
  }
}

if ($Serial) {
  $selectedDevice = @($deviceRows | Where-Object { $_.serial -eq $Serial }) | Select-Object -First 1
  if (-not $selectedDevice) {
    throw "Requested device '$Serial' is not attached. Current devices:`n$deviceList"
  }
  if ($selectedDevice.state -ne 'device') {
    throw "Requested device '$Serial' is $($selectedDevice.state)."
  }
} else {
  $onlineDevices = @($deviceRows | Where-Object { $_.state -eq 'device' })
  if ($onlineDevices.Count -ne 1) {
    throw "Expected exactly one online device or pass -Serial. Current devices:`n$deviceList"
  }
  $Serial = $onlineDevices[0].serial
}
$script:Serial = $Serial

if (-not $OutputRoot) {
  $OutputRoot = Join-Path $repoRoot ".tmp\replay-phase0"
} elseif (-not [IO.Path]::IsPathRooted($OutputRoot)) {
  $OutputRoot = Join-Path $repoRoot $OutputRoot
}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$script:RunDirectory = Join-Path $OutputRoot (Join-Path $stamp $safeSerial)
New-Item -ItemType Directory -Force -Path $script:RunDirectory | Out-Null
Save-RawText -Name "adb-devices.txt" -Text $deviceList | Out-Null

$properties = [ordered]@{
  manufacturer = Get-DeviceProperty "ro.product.manufacturer"
  model = Get-DeviceProperty "ro.product.model"
  product = Get-DeviceProperty "ro.product.name"
  device = Get-DeviceProperty "ro.product.device"
  androidVersion = Get-DeviceProperty "ro.build.version.release"
  sdk = Get-DeviceProperty "ro.build.version.sdk"
  buildId = Get-DeviceProperty "ro.build.id"
  buildFingerprint = Get-DeviceProperty "ro.build.fingerprint"
}

$displayDump = Invoke-DeviceShell -Arguments @("dumpsys", "display") -AllowFailure
$windowDump = Invoke-DeviceShell -Arguments @("dumpsys", "window", "displays") -AllowFailure
Save-RawText -Name "display.txt" -Text $displayDump | Out-Null
Save-RawText -Name "window-displays.txt" -Text $windowDump | Out-Null

$game = Get-PackageSnapshot -PackageName $GamePackage
$assistant = Get-PackageSnapshot -PackageName $AssistantPackage

$mediaDump = Invoke-DeviceShell -Arguments @("dumpsys", "media.player") -AllowFailure
Save-RawText -Name "media-player.txt" -Text $mediaDump | Out-Null
$videoEncoders = @(Get-EncoderSnapshots -MediaDump $mediaDump -MimeType "video/avc")
$audioEncoders = @(Get-EncoderSnapshots -MediaDump $mediaDump -MimeType "audio/mp4a-latm")

$meminfo = Invoke-DeviceShell -Arguments @("dumpsys", "meminfo", $AssistantPackage) -AllowFailure
$topOutput = Invoke-DeviceShell -Arguments @("top", "-b", "-n", "1", "-o", "PID,USER,PR,NI,%CPU,%MEM,ARGS") -AllowFailure
$thermal = Invoke-DeviceShell -Arguments @("dumpsys", "thermalservice") -AllowFailure
$gfxInfo = Invoke-DeviceShell -Arguments @("dumpsys", "gfxinfo", $GamePackage, "framestats") -AllowFailure
Save-RawText -Name "assistant-meminfo.txt" -Text $meminfo | Out-Null
Save-RawText -Name "top.txt" -Text $topOutput | Out-Null
Save-RawText -Name "thermal.txt" -Text $thermal | Out-Null
Save-RawText -Name "game-gfxinfo-framestats.txt" -Text $gfxInfo | Out-Null

$metrics = [pscustomobject]@{
  assistantPssKb = Get-FirstMatch -Text $meminfo -Pattern '^\s*TOTAL PSS:\s*(\d+)\b'
  assistantRssKb = Get-FirstMatch -Text $meminfo -Pattern '\bTOTAL RSS:\s*(\d+)\b'
  assistantProcess = Get-TopProcessSnapshot -TopOutput $topOutput -PackageName $AssistantPackage
  gameProcess = Get-TopProcessSnapshot -TopOutput $topOutput -PackageName $GamePackage
  thermalStatus = Get-FirstMatch -Text $thermal -Pattern '^Thermal Status:\s*(\d+)\b'
  batteryCelsius = Get-FirstMatch -Text $thermal -Pattern '^\s*Temperature\{mValue=([0-9.]+), mType=2, mName=battery,'
  skinCelsius = Get-FirstMatch -Text $thermal -Pattern '^\s*Temperature\{mValue=([0-9.]+), mType=3, mName=skin,'
  gameFrameStatsAvailable = [bool]($gfxInfo -and $gfxInfo -notmatch 'Failure while dumping|No process found')
  gameFramesRendered = Get-FirstMatch -Text $gfxInfo -Pattern '^\s*Total frames rendered:\s*(\d+)\b'
}

$report = [ordered]@{
  schemaVersion = 1
  phase = "0-baseline"
  collectedAt = (Get-Date).ToString("o")
  git = [ordered]@{
    branch = (git -C $repoRoot branch --show-current | Out-String).Trim()
    commit = (git -C $repoRoot rev-parse HEAD | Out-String).Trim()
  }
  device = [ordered]@{
    serial = $Serial
    properties = $properties
  }
  packages = [ordered]@{
    game = $game
    assistant = $assistant
  }
  encoderTargets = [ordered]@{
    video = [ordered]@{ mimeType = "video/avc"; width = 960; height = 540; frameRate = 24; bitrate = 1500000 }
    audio = [ordered]@{ mimeType = "audio/mp4a-latm"; sampleRate = 48000; channels = 2; bitrate = 96000 }
  }
  encoders = [ordered]@{
    videoAvc = $videoEncoders
    audioAac = $audioEncoders
  }
  metrics = $metrics
  remainingManualChecks = @(
    "v1.1.0 recognition, rotation, floating menu, and stop regression",
    "10-second single-app capture isolation probe",
    "game audible, game muted, and other-app playback PCM comparison",
    "floating overlay to permission Activity and ColorOS notification fallback"
  )
}

$jsonPath = Join-Path $script:RunDirectory "report.json"
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdown = [Collections.Generic.List[string]]::new()
$markdown.Add("# Battle replay Phase 0 baseline")
$markdown.Add("")
$markdown.Add("- Collected: $($report.collectedAt)")
$markdown.Add("- Git: ``$($report.git.branch)`` / ``$($report.git.commit)``")
$markdown.Add("- Device: ``$Serial`` / $($properties.manufacturer) $($properties.model)")
$markdown.Add("- Android: $($properties.androidVersion) (API $($properties.sdk))")
$markdown.Add("")
$markdown.Add("## Packages")
$markdown.Add("")
$markdown.Add("| Role | Package | Version | UID | targetSdk | Playback capture flag |")
$markdown.Add("| --- | --- | --- | --- | --- | --- |")
$markdown.Add("| Game | ``$($game.packageName)`` | $($game.versionName) ($($game.versionCode)) | $($game.uids -join ', ') | $($game.targetSdk) | $($game.allowAudioPlaybackCapture) |")
$markdown.Add("| Assistant | ``$($assistant.packageName)`` | $($assistant.versionName) ($($assistant.versionCode)) | $($assistant.uids -join ', ') | $($assistant.targetSdk) | $($assistant.allowAudioPlaybackCapture) |")
$markdown.Add("")
$markdown.Add("## Relevant encoders")
$markdown.Add("")
$markdown.Add("| MIME | Encoder | Hardware | Surface | Phase 0 target |")
$markdown.Add("| --- | --- | --- | --- | --- |")
foreach ($encoder in @($videoEncoders + $audioEncoders)) {
  $markdown.Add("| ``$($encoder.mimeType)`` | ``$($encoder.name)`` | $($encoder.hardwareAccelerated) | $($encoder.surfaceInput) | $($encoder.supportsPhase0Target) |")
}
$markdown.Add("")
$markdown.Add("## Idle snapshot")
$markdown.Add("")
$markdown.Add("- Assistant PSS: $($metrics.assistantPssKb) KB")
$markdown.Add("- Assistant RSS: $($metrics.assistantRssKb) KB")
$markdown.Add("- Thermal status: $($metrics.thermalStatus)")
$markdown.Add("- Battery / skin: $($metrics.batteryCelsius) C / $($metrics.skinCelsius) C")
$markdown.Add("- Game frame stats available: $($metrics.gameFrameStatsAvailable)")
$markdown.Add("")
$markdown.Add("## Still requires a person or interactive device flow")
$markdown.Add("")
foreach ($item in $report.remainingManualChecks) {
  $markdown.Add("- [ ] $item")
}
$markdown.Add("")
$markdown.Add("Raw command output is stored beside this report for review.")

$markdownPath = Join-Path $script:RunDirectory "report.md"
$markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8

Write-Output "Replay Phase 0 baseline collected."
Write-Output "Device: $Serial ($($properties.model), Android $($properties.androidVersion) / API $($properties.sdk))"
Write-Output "Game: $($game.versionName), UID $($game.uids -join ', ')"
Write-Output "Hardware AVC target encoder: $([bool](@($videoEncoders | Where-Object { $_.hardwareAccelerated -and $_.supportsPhase0Target }).Count))"
Write-Output "Hardware AAC target encoder: $([bool](@($audioEncoders | Where-Object { $_.hardwareAccelerated -and $_.supportsPhase0Target }).Count))"
Write-Output "Report: $markdownPath"
Write-Output "JSON: $jsonPath"
