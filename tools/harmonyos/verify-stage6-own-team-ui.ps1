[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'verify-formal-ui-smoke.ps1'
$entryText = [regex]::Unescape('\u542f\u52a8\u5bf9\u5c40\u52a9\u624b')
foreach ($variant in @('standard', 'replay')) {
    & $helper -Target $Target -Variant $variant -Page BATTLE -ExpectedText @($entryText) `
        -EvidenceName 'pc-own-team-formal'
    if ($LASTEXITCODE -ne 0) { throw "Formal $variant own-team entry smoke failed" }
}

Write-Host 'PASS_FORMAL_UI_SMOKE: both products exposed the formal battle-assistant entry.'
Write-Host 'BLOCKED_USER_PRIVACY_AND_REAL_OCR: capture selection, the two real team pages, OCR correction, save, and persistence require a user privacy decision and real Pokémon Champions frames. No privacy prompt was clicked.'
