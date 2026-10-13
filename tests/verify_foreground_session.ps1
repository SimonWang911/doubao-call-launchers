param([switch]$ContractOnly, [switch]$UnitOnly)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$serviceSource = Get-Content -LiteralPath (Join-Path $Root 'common/src/com/simon/doubaolauncher/TouchShieldService.java') -Raw -Encoding utf8
$coordinatorSource = Get-Content -LiteralPath (Join-Path $Root 'common/src/com/simon/doubaolauncher/CallEndCoordinator.java') -Raw -Encoding utf8
$manifestSource = Get-Content -LiteralPath (Join-Path $Root 'apps/voice/AndroidManifest.xml') -Raw -Encoding utf8
$failures = @()
if ($serviceSource.Contains('DoubleTapDetector')) { $failures += 'R1/R2: production gesture path still controls call termination.' }
if ($serviceSource.Contains('authorizationGraceGate')) { $failures += 'R1: fixed authorization grace still delays readiness.' }
if ($serviceSource.Contains('END_FAILED')) { $failures += 'R2: hangup failure still retains the touch shield.' }
if (-not $serviceSource.Contains('ForegroundObserver')) { $failures += 'R1: session has no foreground exit observation.' }
if (-not $coordinatorSource.Contains('SessionRegistry.isCurrent')) { $failures += 'R3/R4: hangup operation has no cross-APK session ownership check.' }
if (-not $manifestSource.Contains('PACKAGE_USAGE_STATS')) { $failures += 'R1: foreground usage permission is missing.' }
if (-not $manifestSource.Contains('android:launchMode="singleTop"')) { $failures += 'R9: repeated launcher intent can only bring the old task to front.' }
$activitySource = Get-Content -LiteralPath (Join-Path $Root 'common/src/com/simon/doubaolauncher/CallLauncherActivity.java') -Raw -Encoding utf8
if (-not $activitySource.Contains('onNewIntent')) { $failures += 'R9: reused entry does not receive a new session request.' }
if ($failures.Count -and -not $UnitOnly) { throw ($failures -join [Environment]::NewLine) }
if ($ContractOnly) { Write-Host 'Foreground session contracts passed.'; exit 0 }
$Classes = Join-Path $Root ('work/session-test/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Classes -Force | Out-Null
$Sources = @('common/src/com/simon/doubaolauncher/CallSession.java','common/src/com/simon/doubaolauncher/SessionLease.java','common/src/com/simon/doubaolauncher/EndRequestPolicy.java','tests/java/CallSessionTest.java','tests/java/SessionLeaseTest.java','tests/java/EndRequestPolicyTest.java') | ForEach-Object { Join-Path $Root $_ }
& javac -encoding UTF-8 -source 8 -target 8 -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw 'Session tests compilation failed.' }
foreach ($testName in @('CallSessionTest','SessionLeaseTest','EndRequestPolicyTest')) {
    & java -cp $Classes ('com.simon.doubaolauncher.' + $testName)
    if ($LASTEXITCODE -ne 0) { throw ($testName + ' failed.') }
}
Write-Host 'Foreground session verification passed.'
