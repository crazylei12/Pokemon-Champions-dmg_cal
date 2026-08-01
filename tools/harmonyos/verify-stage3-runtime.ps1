[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'verify-formal-ui-smoke.ps1'
$engineReady = [regex]::Unescape('\u672c\u5730\u4f24\u5bb3\u5f15\u64ce\u5df2\u5c31\u7eea')

& $helper -Target $Target -Variant standard -Page CALCULATOR -ExpectedText @($engineReady) `
    -EvidenceName 'pc-runtime-formal'
if ($LASTEXITCODE -ne 0) { throw 'Formal calculator runtime smoke failed' }

Write-Host 'PASS_FORMAL_UI_SMOKE: Index, local engine ready state, and calculator route were observed.'
Write-Host 'BLOCKED_DEVICE_PARITY: exact native/runtime performance and cross-device results require release hardware evidence.'
