$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$listener = Get-Content -LiteralPath (Join-Path $root 'common/src/com/simon/doubaolauncher/DoubaoNotificationListenerService.java') -Raw -Encoding utf8
foreach ($required in @('SessionOwnership.isCurrent','key.equals(target.getKey())','snapshot(String expectedKey)','CallSnapshot.unknown()','cancelHangup','performClick','SystemClock.elapsedRealtime()','confirmHangup')) {
    if (-not $listener.Contains($required)) { throw "Missing notification control contract: $required" }
}
& pwsh -NoLogo -NoProfile -File (Join-Path $root 'tests/verify_coordinator.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Real coordinator verification failed.' }
Write-Host 'Call-end contract verification passed.'
