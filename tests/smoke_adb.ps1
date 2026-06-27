param(
    [string]$DeviceId,
    [switch]$LaunchVoice,
    [switch]$LaunchVideo
)

$ErrorActionPreference = 'Stop'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    if ($DeviceId) {
        $output = & adb -s $DeviceId @Args
    } else {
        $output = & adb @Args
    }

    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($Args -join ' ')"
    }

    return $output
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Text -notmatch [regex]::Escape($Pattern)) {
        throw $Message
    }
}

$devices = (& adb devices) -join "`n"
if ($DeviceId) {
    Assert-Contains $devices "$DeviceId`tdevice" "Device $DeviceId is not online."
} else {
    Assert-Contains $devices "`tdevice" 'No online adb device found. Pass -DeviceId if multiple devices are connected.'
}

$packages = (Invoke-Adb shell pm list packages com.simon.doubao) -join "`n"
Assert-Contains $packages 'package:com.simon.doubao.voicecall' 'Voice package is not installed.'
Assert-Contains $packages 'package:com.simon.doubao.videocall' 'Video package is not installed.'

$voiceDump = (Invoke-Adb shell dumpsys package com.simon.doubao.voicecall) -join "`n"
$videoDump = (Invoke-Adb shell dumpsys package com.simon.doubao.videocall) -join "`n"
Assert-Contains $voiceDump 'android.permission.MODIFY_AUDIO_SETTINGS: granted=true' 'Voice package audio settings permission is not granted.'
Assert-Contains $videoDump 'android.permission.MODIFY_AUDIO_SETTINGS: granted=true' 'Video package audio settings permission is not granted.'

$voiceResolve = (Invoke-Adb shell cmd package resolve-activity --brief com.simon.doubao.voicecall) -join "`n"
$videoResolve = (Invoke-Adb shell cmd package resolve-activity --brief com.simon.doubao.videocall) -join "`n"
Assert-Contains $voiceResolve 'com.simon.doubao.voicecall' 'Voice launcher activity does not resolve, or this ROM formats resolve-activity output differently.'
Assert-Contains $videoResolve 'com.simon.doubao.videocall' 'Video launcher activity does not resolve, or this ROM formats resolve-activity output differently.'

if ($LaunchVoice) {
    Invoke-Adb shell monkey -p com.simon.doubao.voicecall -c android.intent.category.LAUNCHER 1 | Out-Host
}

if ($LaunchVideo) {
    Invoke-Adb shell monkey -p com.simon.doubao.videocall -c android.intent.category.LAUNCHER 1 | Out-Host
}

Write-Host 'ADB smoke test passed.'
