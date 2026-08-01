[CmdletBinding()]
param([string]$Target = '127.0.0.1:5555')

$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'verify-formal-ui-smoke.ps1'
foreach ($variant in @('standard', 'replay')) {
    & $helper -Target $Target -Variant $variant -Page HOME -EvidenceName 'pc-storage-formal'
    if ($LASTEXITCODE -ne 0) { throw "Formal $variant storage-entry smoke failed" }
}

Write-Host 'PASS_FORMAL_UI_SMOKE: both products launched through Index without a verification route.'
Write-Host 'BLOCKED_USER_DATA_FLOW: cross-variant persistence, rollback, low-space, and process-kill recovery require user-created data and destructive device scenarios; no fake seed was injected.'
