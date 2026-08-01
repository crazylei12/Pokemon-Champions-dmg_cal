[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'verify-formal-ui-smoke.ps1'
$entryText = [regex]::Unescape('\u8bc6\u522b\u53cc\u65b9\u9635\u5bb9')
foreach ($variant in @('standard', 'replay')) {
    & $helper -Target $Target -Variant $variant -Page BATTLE -ExpectedText @($entryText) `
        -EvidenceName 'pc-team-preview-formal'
    if ($LASTEXITCODE -ne 0) { throw "Formal $variant team-preview entry smoke failed" }
}

Write-Host 'PASS_FORMAL_UI_SMOKE: both products exposed the formal team-preview instructions.'
Write-Host 'BLOCKED_USER_PRIVACY_AND_REAL_PREVIEW: native 12-slot recognition, low-confidence review, floating setup, and confirmed-session creation require a user-selected capture target and real game frames. No synthetic session was seeded.'
