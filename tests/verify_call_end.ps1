$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$listener = Get-Content -LiteralPath (Join-Path $root 'common/src/com/simon/doubaolauncher/DoubaoNotificationListenerService.java') -Raw -Encoding utf8
$bridge = Get-Content -LiteralPath (Join-Path $root 'common/src/com/simon/doubaolauncher/CallEndBridgeService.java') -Raw -Encoding utf8
foreach ($required in @('SessionRegistry.isCurrent','key.equals(target.getKey())','snapshot(String expectedKey)','CallSnapshot.unknown()','cancelHangup','performClick','SystemClock.elapsedRealtime()','confirmHangup')) {
    if (-not $listener.Contains($required)) { throw "Missing notification control contract: $required" }
}
foreach ($required in @('trustedUid(message.sendingUid)','EXTRA_SESSION_ID','EXTRA_NOTIFICATION_KEY','SessionRegistry.isCurrent','onUnbind','checkSignatures')) {
    if (-not $bridge.Contains($required)) { throw "Missing signed bridge contract: $required" }
}
if ($bridge.Contains('onStartCommand')) { throw 'Legacy started bridge must be removed.' }
& pwsh -NoLogo -NoProfile -File (Join-Path $root 'tests/verify_coordinator.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Real coordinator verification failed.' }
Write-Host 'Call-end contract verification passed.'
