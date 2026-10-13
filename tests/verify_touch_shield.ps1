$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
foreach ($mode in @('voice','video')) {
    [xml]$manifest = Get-Content -LiteralPath (Join-Path $root "apps/$mode/AndroidManifest.xml") -Raw -Encoding utf8
    $permissions = @($manifest.manifest.'uses-permission' | ForEach-Object { $_.GetAttribute('name','http://schemas.android.com/apk/res/android') })
    foreach ($required in @('android.permission.SYSTEM_ALERT_WINDOW','android.permission.PACKAGE_USAGE_STATS','android.permission.POST_NOTIFICATIONS','android.permission.FOREGROUND_SERVICE_SPECIAL_USE')) {
        if ($required -notin $permissions) { throw "$mode missing permission: $required" }
    }
    $services = @($manifest.manifest.application.service)
    $shield = $services | Where-Object { $_.GetAttribute('name','http://schemas.android.com/apk/res/android').EndsWith('.TouchShieldService') }
    if ($shield.GetAttribute('exported','http://schemas.android.com/apk/res/android') -ne 'false') { throw 'Session service must remain private.' }
    $provider = $manifest.manifest.application.provider
    if ($provider.GetAttribute('permission','http://schemas.android.com/apk/res/android') -ne 'com.simon.doubao.voicecall.permission.END_CALL') { throw 'Session registry requires signature protection.' }
}
$service = Get-Content -LiteralPath (Join-Path $root 'common/src/com/simon/doubaolauncher/TouchShieldService.java') -Raw -Encoding utf8
foreach ($required in @('START_NOT_STICKY','Intent.ACTION_SCREEN_OFF','PowerManager','isKeyguardLocked','ForegroundObserver','SessionRegistry.renew','removeViewImmediate','FLAG_NOT_FOCUSABLE','结束通话','DoubaoCallShield:')) {
    if (-not $service.Contains($required)) { throw "Missing shield integration contract: $required" }
}
foreach ($obsolete in @('DoubleTapDetector','authorizationGraceGate','END_FAILED')) {
    if ($service.Contains($obsolete)) { throw "Obsolete shield path: $obsolete" }
}
Write-Host 'Touch-shield contract verification passed.'
