param(
  [Parameter(Mandatory = $true)]
  [string]$InputPath,
  [double]$MinimumDurationSeconds = 179.0,
  [string]$FfprobePath = ''
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($FfprobePath)) {
  $localConfig = & (Join-Path $PSScriptRoot 'load-local-config.ps1') -RepositoryRoot $repositoryRoot
  $FfprobePath = $localConfig.FfprobePath
}
if ([string]::IsNullOrWhiteSpace($FfprobePath)) {
  throw 'Missing ffprobe path. Set HARMONY_FFPROBE_PATH or config/harmonyos-local.json tools.ffprobePath.'
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$resolvedFfprobe = (Resolve-Path -LiteralPath $FfprobePath).Path
$probeJson = & $resolvedFfprobe -v error -show_streams -show_format -of json -- $resolvedInput
if ($LASTEXITCODE -ne 0) {
  throw "ffprobe failed with exit code $LASTEXITCODE"
}

$probe = $probeJson | ConvertFrom-Json
$videoStreams = @($probe.streams | Where-Object { $_.codec_type -eq 'video' })
$audioStreams = @($probe.streams | Where-Object { $_.codec_type -eq 'audio' })
if ($videoStreams.Count -ne 1) {
  throw "Expected exactly one video stream, found $($videoStreams.Count)"
}
if ($audioStreams.Count -ne 1) {
  throw "Expected exactly one audio stream, found $($audioStreams.Count)"
}

$video = $videoStreams[0]
$audio = $audioStreams[0]
$formatDuration = [double]$probe.format.duration
$videoDuration = if ($null -ne $video.duration) { [double]$video.duration } else { $formatDuration }
$audioDuration = if ($null -ne $audio.duration) { [double]$audio.duration } else { $formatDuration }
$durationDrift = [Math]::Abs($videoDuration - $audioDuration)

if ($video.codec_name -ne 'h264') {
  throw "Expected H.264 video, found $($video.codec_name)"
}
if ($audio.codec_name -ne 'aac') {
  throw "Expected AAC audio, found $($audio.codec_name)"
}
if ([int]$audio.sample_rate -ne 48000) {
  throw "Expected 48000 Hz audio, found $($audio.sample_rate)"
}
if ([int]$audio.channels -ne 2) {
  throw "Expected stereo audio, found $($audio.channels) channels"
}
if ($formatDuration -lt $MinimumDurationSeconds) {
  throw "Recording is too short: $formatDuration seconds"
}
if ($durationDrift -gt 0.250) {
  throw "Audio/video duration drift is too large: $durationDrift seconds"
}

$result = [ordered]@{
  input = $resolvedInput
  bytes = (Get-Item -LiteralPath $resolvedInput).Length
  sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedInput).Hash
  format = $probe.format.format_name
  durationSeconds = $formatDuration
  video = [ordered]@{
    codec = $video.codec_name
    width = [int]$video.width
    height = [int]$video.height
    frameRate = $video.avg_frame_rate
    durationSeconds = $videoDuration
  }
  audio = [ordered]@{
    codec = $audio.codec_name
    sampleRate = [int]$audio.sample_rate
    channels = [int]$audio.channels
    durationSeconds = $audioDuration
  }
  audioVideoDurationDriftSeconds = $durationDrift
  pass = $true
}

$result | ConvertTo-Json -Depth 4
