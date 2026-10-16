param([Parameter(Mandatory)][string]$Serial,[Parameter(Mandatory)][string]$TestApkRoot,[Parameter(Mandatory)][string]$EvidenceRoot,[string]$LegacyVoiceApk,[switch]$Smoke,[switch]$LifecycleOnly)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'call_notification_evidence.ps1')
function Invoke-Android([string[]]$Arguments) {
    $output = & adb.exe -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments[0]) $($Arguments[1]): $($output -join ' ')" }
    return ($output -join [Environment]::NewLine)
}
if ($Serial -notlike 'emulator-*' -or (Invoke-Android @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1') { throw 'Controlled fixture runner is emulator-only; never install it on a phone.' }
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
$EvidenceRoot = (Resolve-Path -LiteralPath $EvidenceRoot).Path
$installed = [Collections.Generic.List[string]]::new()
$results = [Collections.Generic.List[object]]::new()
$logProcess = $null
function Install-TestPackage([string]$Package,[string]$Apk) {
    Invoke-Android @('install','--no-incremental','-r',$Apk) | Out-Null
    if (-not $installed.Contains($Package)) { $installed.Add($Package) }
}
function Configure-Launcher([string]$Mode) {
    $package = "com.simon.doubao.${Mode}call"
    Invoke-Android @('shell','cmd','appops','set',$package,'SYSTEM_ALERT_WINDOW','allow') | Out-Null
    Invoke-Android @('shell','cmd','appops','set',$package,'GET_USAGE_STATS','allow') | Out-Null
    Invoke-Android @('shell','cmd','notification','allow_listener',($package + '/com.simon.doubaolauncher.DoubaoNotificationListenerService')) | Out-Null
    if ([int](Invoke-Android @('shell','getprop','ro.build.version.sdk')).Trim() -ge 33) { Invoke-Android @('shell','pm','grant',$package,'android.permission.POST_NOTIFICATIONS') | Out-Null }
    $probe = if ($Mode -eq 'voice') { 'VoiceIntegrationProbe' } else { 'VideoIntegrationProbe' }
    $result = Invoke-Android @('shell','am','instrument','-w','-r','-e','op','seed',('com.simon.doubao.independencetest/com.simon.doubaolauncher.'+$probe))
    if ($result -notmatch 'INSTRUMENTATION_CODE: -1') { throw $result }
}
function Call-State { ConvertTo-CallNotificationEvidence (Invoke-Android @('shell','dumpsys','notification')) }
function Launch-Count {
    [xml]$store = Invoke-Android @('shell','run-as','com.larus.nova','cat','shared_prefs/fixture.xml')
    $entry = @($store.map.int | Where-Object { $_.name -eq 'launches' }) | Select-Object -First 1
    if ($entry) { return [int]$entry.value }
    return 0
}
function Is-VisibleShield([string]$Window,[string]$Mode) {
    $title = 'DoubaoCallShield:com.simon.doubao.' + $Mode + 'call'
    foreach ($block in [regex]::Split($Window,'(?m)(?=^  Window #)')) {
        if ($block.Contains($title) -and $block -match 'Surface: shown=true' -and $block -match 'isOnScreen=true') { return $true }
    }
    return $false
}
function Wait-Ended {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $window = Invoke-Android @('shell','dumpsys','window','windows')
        $call = Call-State
        if ($window -notmatch 'DoubaoCallShield:com\.simon\.doubao\.' -and $call -eq 'No Doubao call notification 101.') { return }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Actual overlay/notification did not end.'
}
function Invoke-NotificationEnd {
    Invoke-Android @('shell','cmd','statusbar','expand-notifications') | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-Android @('shell','uiautomator','dump','/sdcard/independence-ui.xml') | Out-Null
    [xml]$ui = Invoke-Android @('shell','cat','/sdcard/independence-ui.xml')
    $node = @($ui.SelectNodes('//node') | Where-Object { $_.text -eq '结束通话' }) | Select-Object -First 1
    if (-not $node) { $node = @($ui.SelectNodes('//node') | Where-Object { $_.text -eq 'AI通话保护' }) | Select-Object -First 1 }
    if (-not $node -or $node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Actual protection notification control not found in UI.' }
    $x = [int](([int]$Matches[1]+[int]$Matches[3])/2)
    $y = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    Invoke-Android @('shell','input','tap',"$x","$y") | Out-Null
    Invoke-Android @('shell','cmd','statusbar','collapse') | Out-Null
}
function Run-Case([string]$Name,[string]$Mode,[string]$Exit,[scriptblock]$BeforeExit = {},[scriptblock]$AfterLaunch = {}) {
    $folder = Join-Path $EvidenceRoot $Name
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $record = [ordered]@{Name=$Name;Mode=$Mode;Exit=$Exit;Fixture=$true;VisibleShield=$false;CallNotification=$false;Dispatches=0;Ended=$false;Passed=$false;Error=$null}
    try {
        Invoke-Android @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
        Invoke-Android @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
        Wait-Ended
        $beforeCount = Launch-Count
        Invoke-Android @('shell','am','start','-W','-n',("com.simon.doubao.${Mode}call/com.simon.doubaolauncher.CallLauncherActivity")) | Set-Content -LiteralPath (Join-Path $folder 'launch.txt') -Encoding utf8
        & $AfterLaunch
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        do {
            $window = Invoke-Android @('shell','dumpsys','window','windows')
            if (Is-VisibleShield $window $Mode) { $record.VisibleShield=$true; break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
        $window | Set-Content -LiteralPath (Join-Path $folder 'before-window.txt') -Encoding utf8
        if (-not $record.VisibleShield) { throw 'Installed APK did not show an actual protection surface.' }
        $call = Call-State
        $record.CallNotification = $call -eq 'NotificationRecord(pkg=com.larus.nova id=101)'
        $call | Set-Content -LiteralPath (Join-Path $folder 'before-notification.txt') -Encoding utf8
        if (-not $record.CallNotification) { throw 'No controlled call notification before exit.' }
        $record.Dispatches = (Launch-Count) - $beforeCount
        if ($record.Dispatches -ne 1) { throw 'Repeated requests dispatched more than one controlled call.' }
        & $BeforeExit
        switch ($Exit) {
            'home' { Invoke-Android @('shell','input','keyevent','KEYCODE_HOME') | Out-Null }
            'back' { Invoke-Android @('shell','input','keyevent','KEYCODE_BACK') | Out-Null }
            'recents' { Invoke-Android @('shell','input','keyevent','KEYCODE_APP_SWITCH') | Out-Null }
            'app' { Invoke-Android @('shell','am','start','-a','android.settings.SETTINGS') | Out-Null }
            'lock' { Invoke-Android @('shell','input','keyevent','KEYCODE_SLEEP') | Out-Null }
            'natural' { Invoke-Android @('shell','am','broadcast','-n','com.larus.nova/com.simon.doubaotest.FixtureReceiver','-a','fixture.END') | Out-Null }
            'notification' { Invoke-NotificationEnd }
            default { throw 'Unknown test exit.' }
        }
        Wait-Ended
        $record.Ended=$true; $record.Passed=$true
        Call-State | Set-Content -LiteralPath (Join-Path $folder 'after-notification.txt') -Encoding utf8
    } catch { $record.Error=$_.Exception.Message; throw }
    finally {
        $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'result.json') -Encoding utf8
        $results.Add([pscustomobject]$record)
        Write-Output ($record | ConvertTo-Json -Compress)
    }
}
function Run-PreparationCancellation([string]$Mode) {
    $record = [ordered]@{Name=($Mode+'-home-during-preparation');Mode=$Mode;Fixture=$true;Dispatches=0;Passed=$false;Error=$null}
    try {
        Invoke-Android @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
        Wait-Ended
        $beforeCount = Launch-Count
        Invoke-Android @('shell','am','start','-W','-n',("com.simon.doubao.${Mode}call/com.simon.doubaolauncher.CallLauncherActivity")) | Out-Null
        Invoke-Android @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
        Start-Sleep -Seconds 4
        Wait-Ended
        $record.Dispatches = (Launch-Count) - $beforeCount
        if ($record.Dispatches -ne 0) { throw 'Cancelled preparation dispatched a background call.' }
        $record.Passed=$true
    } catch { $record.Error=$_.Exception.Message; throw }
    finally { $results.Add([pscustomobject]$record); Write-Output ($record | ConvertTo-Json -Compress) }
}
function Run-LateCancellation {
    $record = [ordered]@{Name='video-late-call-after-permission-withdrawal';Mode='video';Fixture=$true;Passed=$false;Error=$null}
    try {
        Invoke-Android @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
        Invoke-Android @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
        Wait-Ended
        Invoke-Android @('shell','am','broadcast','-n','com.larus.nova/com.simon.doubaotest.FixtureReceiver','-a','fixture.CONFIGURE','--ei','delay','5000') | Out-Null
        $logStart = (Get-Content -LiteralPath (Join-Path $EvidenceRoot 'runtime.txt')).Count
        Invoke-Android @('shell','am','start','-W','-n','com.simon.doubao.videocall/com.simon.doubaolauncher.CallLauncherActivity') | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            $window = Invoke-Android @('shell','dumpsys','window')
            if ($window -match 'mCurrentFocus=.*com\.larus\.nova/') { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($window -notmatch 'mCurrentFocus=.*com\.larus\.nova/') { throw 'Late-call fixture did not reach foreground.' }
        Invoke-Android @('shell','cmd','appops','set','com.simon.doubao.videocall','GET_USAGE_STATS','ignore') | Out-Null
        Start-Sleep -Seconds 7
        Wait-Ended
        $newLog = Get-Content -LiteralPath (Join-Path $EvidenceRoot 'runtime.txt') | Select-Object -Skip $logStart
        if (($newLog -join "`n") -notmatch 'Session end result=SUCCESS' -or ($newLog -join "`n") -match 'Shield shown') { throw 'Withdrawn session did not end late call without showing protection.' }
        $record.Passed=$true
    } catch { $record.Error=$_.Exception.Message; throw }
    finally {
        Invoke-Android @('shell','cmd','appops','set','com.simon.doubao.videocall','GET_USAGE_STATS','allow') | Out-Null
        Invoke-Android @('shell','am','broadcast','-n','com.larus.nova/com.simon.doubaotest.FixtureReceiver','-a','fixture.CONFIGURE','--ei','delay','0') | Out-Null
        $results.Add([pscustomobject]$record)
        $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'late-cancellation.json') -Encoding utf8
        Write-Output ($record | ConvertTo-Json -Compress)
    }
}
try {
    foreach ($package in @('com.larus.nova','com.simon.doubao.independencetest','com.simon.doubao.voicecall','com.simon.doubao.videocall')) {
        if ((Invoke-Android @('shell','pm','list','packages',$package)) -match ('package:'+ [regex]::Escape($package))) { throw "Refusing to replace existing package $package in test instance." }
    }
    Install-TestPackage 'com.larus.nova' (Join-Path $TestApkRoot 'fixture/test.apk')
    Invoke-Android @('shell','am','broadcast','-n','com.larus.nova/com.simon.doubaotest.FixtureReceiver','-a','fixture.CONFIGURE','--ei','delay','0') | Out-Null
    foreach ($mode in @('voice','video')) { Install-TestPackage ("com.simon.doubao.${mode}call") (Join-Path $root ("dist/doubao-$mode-call.apk")) }
    Install-TestPackage 'com.simon.doubao.independencetest' (Join-Path $TestApkRoot 'probe/test.apk')
    $logProcess = Start-Process -FilePath (Get-Command adb.exe).Source -ArgumentList @('-s',$Serial,'logcat','-T','1','-v','threadtime','-s','DoubaoCallLauncher:V','DoubaoTouchShield:V','DoubaoCallEndCoordinator:V','DoubaoSessionOwnership:V','DoubaoIntegrationFixture:V','AndroidRuntime:E') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $EvidenceRoot 'runtime.txt') -RedirectStandardError (Join-Path $EvidenceRoot 'logcat-error.txt')
    Configure-Launcher video
    Run-Case 'video-other-never-configured' video home
    if ($LifecycleOnly) {
        Configure-Launcher voice
        foreach ($iteration in @(1,2)) {
            Run-Case ('video-rapid-repeat-'+$iteration) video home {} {
                Invoke-Android @('shell','am','start','-n','com.simon.doubao.videocall/com.simon.doubaolauncher.CallLauncherActivity') | Out-Null
            }
        }
        foreach ($mode in @('voice','video')) { Run-PreparationCancellation $mode }
    } elseif (-not $Smoke) {
        Configure-Launcher voice
        foreach ($mode in @('voice','video')) {
            foreach ($exit in @('back','recents','app','lock','natural','notification')) { Run-Case ($mode+'-'+$exit) $mode $exit }
            $other = if ($mode -eq 'voice') { 'video' } else { 'voice' }
            Invoke-Android @('shell','am','force-stop',("com.simon.doubao.${other}call")) | Out-Null
            Run-Case ($mode+'-other-stopped') $mode home
            Invoke-Android @('shell','pm','disable-user','--user','0',("com.simon.doubao.${other}call")) | Out-Null
            try { Run-Case ($mode+'-other-disabled') $mode home } finally { Invoke-Android @('shell','pm','enable',("com.simon.doubao.${other}call")) | Out-Null }
            Run-Case ($mode+'-other-uninstalled-during-call') $mode home {
                Invoke-Android @('uninstall',("com.simon.doubao.${other}call")) | Out-Null
                $installed.Remove("com.simon.doubao.${other}call") | Out-Null
                Start-Sleep -Milliseconds 1200
                if (-not (Is-VisibleShield (Invoke-Android @('shell','dumpsys','window','windows')) $mode)) { throw 'Other package removal interrupted current protection.' }
            }
            Run-Case ($mode+'-alone') $mode home
            Install-TestPackage ("com.simon.doubao.${other}call") (Join-Path $root ("dist/doubao-$other-call.apk"))
            Configure-Launcher $other
        }
        foreach ($mode in @('voice','video')) {
            Run-Case ($mode+'-notification-shade') $mode home {
                Invoke-Android @('shell','cmd','statusbar','expand-notifications') | Out-Null
                Start-Sleep -Milliseconds 1200
                if (-not (Is-VisibleShield (Invoke-Android @('shell','dumpsys','window','windows')) $mode)) { throw 'Notification shade incorrectly ended protection.' }
                Invoke-Android @('shell','cmd','statusbar','collapse') | Out-Null
            }
        }
        Run-Case 'video-rapid-repeat' video home {} {
            Invoke-Android @('shell','am','start','-n','com.simon.doubao.videocall/com.simon.doubaolauncher.CallLauncherActivity') | Out-Null
        }
        Run-Case 'cross-entry-overlap' video home {} {
            Invoke-Android @('shell','am','start','-n','com.simon.doubao.voicecall/com.simon.doubaolauncher.CallLauncherActivity') | Out-Null
            Invoke-Android @('shell','am','start','-n','com.simon.doubao.videocall/com.simon.doubaolauncher.CallLauncherActivity') | Out-Null
        }
        Run-LateCancellation
        if ($LegacyVoiceApk) {
            Invoke-Android @('uninstall','com.simon.doubao.voicecall') | Out-Null
            $installed.Remove('com.simon.doubao.voicecall') | Out-Null
            Install-TestPackage 'com.simon.doubao.voicecall' $LegacyVoiceApk
            Run-Case 'video-with-old-voice-version' video home
        }
    }
} finally {
    if ($logProcess -and -not $logProcess.HasExited) { Stop-Process -Id $logProcess.Id }
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'results.json') -Encoding utf8
    foreach ($package in @($installed)) { Invoke-Android @('uninstall',$package) | Out-Null }
    Invoke-Android @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
    'Temporary packages removed from read-only emulator.' | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'cleanup.txt') -Encoding utf8
}
