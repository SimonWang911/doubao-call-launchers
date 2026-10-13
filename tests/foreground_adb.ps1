param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('voice','video')][string]$Mode = 'voice',
    [ValidateSet('home','recents','back','lock','app')][string]$Exit = 'home',
    [switch]$LaunchCall,
    [string]$EvidenceRoot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'call_notification_evidence.ps1')
if (-not $LaunchCall) { throw 'Real call requires explicit -LaunchCall.' }
if ($Serial -like 'emulator-*') { throw 'Use the explicitly authorized phone.' }
if (-not $EvidenceRoot) { $EvidenceRoot = Join-Path $PSScriptRoot ('../work/foreground-device/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Mode + '-' + $Exit) }
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
$EvidenceRoot = (Resolve-Path -LiteralPath $EvidenceRoot).Path
$pkg = 'com.simon.doubao.' + $Mode + 'call'
function Invoke-Adb([string[]]$Arguments) {
    $output = & adb -s $Serial @Arguments 2>&1
    $isRead = ($Arguments[0] -eq 'shell' -and $Arguments[1] -in @('dumpsys','getprop'))
    if ($LASTEXITCODE -ne 0 -and $isRead) {
        & adb disconnect $Serial | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Read-only transport disconnect failed.' }
        & adb connect $Serial | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Read-only transport reconnect failed.' }
        $output = & adb -s $Serial @Arguments 2>&1
    }
    if ($LASTEXITCODE -ne 0) { throw "ADB $($Arguments[0]) $($Arguments[1]) failed (exit $LASTEXITCODE)." }
    return ($output -join [Environment]::NewLine)
}
function Window-State { return Invoke-Adb @('shell','dumpsys','window','windows') }
function Notification-State { return ConvertTo-CallNotificationEvidence (Invoke-Adb @('shell','dumpsys','notification')) }
function Has-Call([string]$Text) { return $Text -match 'NotificationRecord\([^\r\n]*pkg=com\.larus\.nova[^\r\n]*id=101\b' }
function Has-Shield([string]$Text) { return $Text -match ('Window #\d+ Window\{[^\r\n]*DoubaoCallShield:' + [regex]::Escape($pkg)) }
function Has-VisibleShield([string]$Text) {
    foreach ($block in [regex]::Split($Text, '(?m)(?=^  Window #)')) {
        if ((Has-Shield $block) -and $block -match 'Surface: shown=true' -and $block -match 'isOnScreen=true') { return $true }
    }
    return $false
}
if ((Invoke-Adb @('shell','getprop','ro.product.model')).Trim() -ne 'PJD110') { throw 'Target model differs from authorized OnePlus.' }
$before = Notification-State
if (Has-Call $before) { throw 'An existing Doubao call must be ended normally before launching.' }
$logFile = Join-Path $EvidenceRoot 'live-logcat.txt'; $errorFile = Join-Path $EvidenceRoot 'logcat-error.txt'
$logProcess = Start-Process -FilePath (Get-Command adb).Source -ArgumentList @('-s',$Serial,'logcat','-v','threadtime','-s','DoubaoTouchShield:I','DoubaoCallLauncher:I','DoubaoCallEnd:I') -WindowStyle Hidden -PassThru -RedirectStandardOutput $logFile -RedirectStandardError $errorFile
$result = [ordered]@{ Mode=$Mode; Exit=$Exit; Passed=$false; ActualWindow=$false; CallNotification=$false; Removed=$false; CallEnded=$false }
try {
    Invoke-Adb @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb @('shell','am','start','-n',($pkg + '/com.simon.doubaolauncher.CallLauncherActivity')) | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'launch.txt') -Encoding utf8
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $window = Window-State
        if (Has-VisibleShield $window) { $result.ActualWindow = $true; break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    $window | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'before-window.txt') -Encoding utf8
    if (-not $result.ActualWindow) { throw 'Actual shield window never appeared.' }
    $notification = Notification-State
    $notification | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'before-notification.txt') -Encoding utf8
    $result.CallNotification = Has-Call $notification
    if (-not $result.CallNotification) { throw 'Shield appeared without real call notification.' }
    Invoke-Adb @('shell','dumpsys','activity','activities') | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'before-activity.txt') -Encoding utf8
    switch ($Exit) {
        'home' { Invoke-Adb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null }
        'recents' { Invoke-Adb @('shell','input','keyevent','KEYCODE_APP_SWITCH') | Out-Null }
        'back' { Invoke-Adb @('shell','input','keyevent','KEYCODE_BACK','KEYCODE_BACK') | Out-Null }
        'lock' { Invoke-Adb @('shell','input','keyevent','KEYCODE_SLEEP') | Out-Null }
        'app' { Invoke-Adb @('shell','am','start','-a','android.settings.SETTINGS') | Out-Null }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $window = Window-State; $notification = Notification-State
        $result.Removed = -not (Has-Shield $window); $result.CallEnded = -not (Has-Call $notification)
        if ($result.Removed -and $result.CallEnded) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    $window | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'after-window.txt') -Encoding utf8
    $notification | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'after-notification.txt') -Encoding utf8
    Invoke-Adb @('shell','dumpsys','usagestats') | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'after-usagestats.txt') -Encoding utf8
    $result.Passed = $result.Removed -and $result.CallEnded
    if (-not $result.Passed) { throw 'Exit did not remove both actual shield and call notification.' }
} finally {
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'summary.json') -Encoding utf8
    if (-not $logProcess.HasExited) { Stop-Process -Id $logProcess.Id }
    Write-Output ($result | ConvertTo-Json -Compress)
    Write-Output $EvidenceRoot
}
