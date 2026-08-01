[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'verify-formal-ui-smoke.ps1'
$recordingText = [regex]::Unescape('\u5f00\u59cb\u5f55\u5c4f')

& $helper -Target $Target -Variant standard -Page BATTLE -ForbiddenText @($recordingText) `
    -EvidenceName 'pc-recording-gate-formal'
if ($LASTEXITCODE -ne 0) { throw 'Formal standard product-gate smoke failed' }
& $helper -Target $Target -Variant replay -Page BATTLE -ExpectedText @($recordingText) `
    -EvidenceName 'pc-recording-gate-formal'
if ($LASTEXITCODE -ne 0) { throw 'Formal replay product-gate smoke failed' }

Write-Host 'PASS_FORMAL_PRODUCT_GATE_SMOKE: recording guidance was absent from standard and present in replay.'
Write-Host 'BLOCKED_REAL_CAPTURE_CODEC_AND_MEDIA: AVScreenCapture, internal audio, H.264/AAC/MP4, rotation, failure recovery, and media-library publication require explicit user decisions and real-device evidence. No privacy or save confirmation was automated.'
