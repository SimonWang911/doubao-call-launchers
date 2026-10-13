param([switch]$ContractOnly)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& git -C $root check-ignore --no-index --quiet -- 'work/foreground-device/review-sample/before-notification.txt'
if ($LASTEXITCODE -eq 1) { throw 'E2: default raw device evidence remains eligible for staging.' }
if ($LASTEXITCODE -ne 0) { throw 'Git ignore check failed.' }
$tracked = @(& git -C $root ls-files -- work)
if ($LASTEXITCODE -ne 0) { throw 'Git tracked evidence check failed.' }
if ($tracked.Count -gt 0) { throw 'Generated work files are already tracked; review their removal from the index separately.' }
foreach ($sourcePath in @('common/src/com/simon/doubaolauncher/CallSession.java','tests/foreground_adb.ps1','docs/refactor/2026-10-13-ending-progress-evidence-plan.md')) {
    & git -C $root check-ignore --no-index --quiet -- $sourcePath
    if ($LASTEXITCODE -ne 1) { throw "Required source is unexpectedly ignored: $sourcePath" }
}
if ($ContractOnly) { Write-Host 'Evidence staging contract passed.'; exit 0 }
. (Join-Path $PSScriptRoot 'call_notification_evidence.ps1')
$checks = 0
function Assert-Evidence([string]$dump,[string]$expected) {
    $actual = ConvertTo-CallNotificationEvidence $dump
    $script:checks++
    if ($actual -ne $expected) { throw 'Notification evidence did not preserve the exact minimal call signal.' }
}
$present = 'NotificationRecord(pkg=com.larus.nova id=101)'
$absent = 'No Doubao call notification 101.'
$sample = @'
Current Notification List:
  NotificationRecord(aaa: pkg=com.example.chat user=UserHandle{0} id=101 tag=null)
    extras={android.text=private-message-secret}
  NotificationRecord(bbb: pkg=com.larus.nova user=UserHandle{0} id=101 tag=null)
    extras={android.title=private-call-title}
  NotificationRecord(ccc: pkg=com.larus.nova user=UserHandle{0} id=102 tag=null)
'@
Assert-Evidence $sample $present
Assert-Evidence ($sample.Replace('id=101 tag=null)','id=102 tag=null)')) $absent
Assert-Evidence 'Current Notification List: no records' $absent
Assert-Evidence ($sample + [Environment]::NewLine + $sample) $present
Assert-Evidence ($sample.Replace('pkg=com.larus.nova ', 'pkg=com.larus.nova.clone ')) $absent
Assert-Evidence 'Current Notification List: extras={android.text=NotificationRecord(pkg=com.larus.nova id=101)}' $absent
$rejectedEmpty = $false
try { ConvertTo-CallNotificationEvidence ' ' | Out-Null } catch { $rejectedEmpty = $true }
if (-not $rejectedEmpty) { throw 'Empty dump must not be treated as call absence.' }
$checks++
foreach ($invalid in @('notification service unavailable', 'NotificationRecord(pkg=com.larus.nova id=101)')) {
    $rejected = $false
    try { ConvertTo-CallNotificationEvidence $invalid | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Nonempty unrecognized dump must not infer call presence or absence.' }
    $checks++
}
$pattern = 'NotificationRecord\([^\r\n]*pkg=com\.larus\.nova[^\r\n]*id=101\b'
$historyRoot = Join-Path $root 'work/foreground-device'
$historical = if (Test-Path -LiteralPath $historyRoot) { @(Get-ChildItem -LiteralPath $historyRoot -File -Filter '*-notification.txt' -Recurse) } else { @() }
foreach ($file in $historical) {
    $raw = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
    $minimal = ConvertTo-CallNotificationEvidence $raw
    if (($raw -match $pattern) -ne ($minimal -match $pattern)) { throw "Real notification signal changed: $($file.Name)" }
    if ($minimal -ne $present -and $minimal -ne $absent) { throw 'Private fields leaked into evidence.' }
    $checks++
}
$runner = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'foreground_adb.ps1') -Raw -Encoding utf8
if ($runner.Contains('--noredact') -or -not $runner.Contains('ConvertTo-CallNotificationEvidence')) { throw 'Device runner must use the shared minimal evidence conversion.' }
Write-Host "Evidence boundary verification passed ($checks checks; $($historical.Count) real historical dumps)."
