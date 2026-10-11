$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..')

function Assert-FileContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    if (-not $content.Contains($Pattern)) {
        throw $Message
    }
}

function Assert-FileNotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    if ($content.Contains($Pattern)) {
        throw $Message
    }
}

function Assert-FileNotMatch {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing file: $Path"
    }

    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    if ($content -match $Pattern) {
        throw $Message
    }
}

function Assert-Ordered {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string[]]$Patterns,
        [Parameter(Mandatory = $true)][string]$Message
    )

    $lastIndex = -1
    foreach ($pattern in $Patterns) {
        $index = $Text.IndexOf($pattern)
        if ($index -lt 0 -or $index -le $lastIndex) {
            throw $Message
        }
        $lastIndex = $index
    }
}

$launcherActivity = Join-Path $Root 'common/src/com/simon/doubaolauncher/CallLauncherActivity.java'
$shieldService = Join-Path $Root 'common/src/com/simon/doubaolauncher/TouchShieldService.java'
$videoManifest = Join-Path $Root 'apps/video/AndroidManifest.xml'
$voiceManifest = Join-Path $Root 'apps/voice/AndroidManifest.xml'
$plan = Join-Path $Root 'docs/refactor/2026-10-11-video-touch-shield-refactor-plan.md'
$review = Join-Path $Root 'docs/refactor/2026-10-11-video-touch-shield-plan-review.md'

Assert-FileContains $plan 'ResultReceiver' 'Touch-shield plan must define the ready callback.'
Assert-FileContains $plan 'FOREGROUND_SERVICE_SPECIAL_USE' 'Touch-shield plan must define the Android 14 special-use permission.'
Assert-FileContains $review 'P1-2' 'Touch-shield plan review must record the service readiness race.'
Assert-FileContains $review 'verify_touch_shield.ps1' 'Touch-shield plan review must require this gate.'

Assert-FileContains $videoManifest 'android.permission.SYSTEM_ALERT_WINDOW' 'Video manifest must request overlay permission.'
Assert-FileContains $videoManifest 'android.permission.FOREGROUND_SERVICE' 'Video manifest must request foreground-service permission.'
Assert-FileContains $videoManifest 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' 'Video manifest must request special-use foreground-service permission.'
Assert-FileContains $videoManifest 'android.permission.POST_NOTIFICATIONS' 'Video manifest must request notification permission.'
Assert-FileContains $videoManifest 'android:foregroundServiceType="specialUse"' 'Video service must declare specialUse foreground-service type.'
Assert-FileContains $videoManifest 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' 'Video service must declare a special-use subtype property.'
Assert-FileContains $videoManifest 'com.simon.doubaolauncher.TouchShieldService' 'Video manifest must register the touch-shield service.'
Assert-FileContains $videoManifest 'android:exported="false"' 'Touch-shield service must not be externally exported.'
Assert-FileNotContains $voiceManifest 'SYSTEM_ALERT_WINDOW' 'Voice manifest must not request overlay permission.'
Assert-FileNotContains $voiceManifest 'TouchShieldService' 'Voice manifest must not register the touch-shield service.'

Assert-FileContains $launcherActivity 'Settings.canDrawOverlays' 'Launcher must preflight overlay permission.'
Assert-FileContains $launcherActivity 'areNotificationsEnabled' 'Launcher must preflight notification visibility.'
Assert-FileContains $launcherActivity 'ResultReceiver' 'Launcher must wait for a service result.'
Assert-FileContains $launcherActivity 'startForegroundService' 'Launcher must use the foreground-service start path on Android O+.'
Assert-FileContains $launcherActivity 'TouchShieldService.createStartIntent' 'Launcher must send the service start action.'
Assert-FileContains $launcherActivity 'TouchShieldService.ACTION_STOP' 'Launcher must stop the service on failure or timeout.'
Assert-FileContains $launcherActivity 'READY' 'Launcher must handle the service ready result.'
Assert-FileContains $launcherActivity 'FAILED' 'Launcher must handle the service failed result.'
Assert-FileContains $launcherActivity 'OPENING_VOICE_MESSAGE = ' 'Launcher must keep an explicit voice opening message.'
Assert-FileContains $launcherActivity 'OPENING_VIDEO_MESSAGE = ' 'Launcher must keep an explicit video opening message.'
Assert-FileContains $launcherActivity '\u6b63\u5728\u542f\u52a8\u8c46\u5305\u8bed\u97f3' 'Voice opening message must be the short target sentence.'
Assert-FileContains $launcherActivity '\u6b63\u5728\u542f\u52a8\u8c46\u5305\u89c6\u9891' 'Video opening message must be the short target sentence.'
Assert-FileNotMatch $launcherActivity 'OPENING_VOICE_MESSAGE\s*=\s*VOLUME_READY_MESSAGE' 'Voice success message must not include the volume preamble.'
Assert-FileNotMatch $launcherActivity 'OPENING_VIDEO_MESSAGE\s*=\s*VOLUME_READY_MESSAGE' 'Video success message must not include the volume preamble.'

$launcherContent = Get-Content -Raw -Encoding UTF8 -LiteralPath $launcherActivity
$openCallStart = $launcherContent.IndexOf('private void openDoubaoCall(')
$openCallEnd = $launcherContent.IndexOf('    private void startVideoShieldAndOpen', $openCallStart)
if ($openCallStart -lt 0 -or $openCallEnd -lt 0) {
    throw 'Could not isolate openDoubaoCall for the ready-before-launch check.'
}
$openCallBody = $launcherContent.Substring($openCallStart, $openCallEnd - $openCallStart)
if ($openCallBody.Contains('startActivity(intent)')) {
    throw 'openDoubaoCall must not launch Doubao before the shield ready callback.'
}
Assert-FileContains $launcherActivity 'startVideoShieldAndOpen(mode, intent);' 'Video launch must hand off to the shield readiness path.'

Assert-FileContains $shieldService 'public class TouchShieldService extends Service' 'Touch-shield service must be an Android Service.'
Assert-FileContains $shieldService 'ACTION_START' 'Touch-shield service must define a start action.'
Assert-FileContains $shieldService 'ACTION_STOP' 'Touch-shield service must define a stop action.'
Assert-FileContains $shieldService 'ResultReceiver' 'Touch-shield service must return readiness to the launcher.'
Assert-FileContains $shieldService 'startForeground' 'Touch-shield service must enter foreground mode.'
Assert-FileContains $shieldService 'TYPE_APPLICATION_OVERLAY' 'Touch-shield service must use an application overlay on Android O+.'
Assert-FileContains $shieldService 'TYPE_PHONE' 'Touch-shield service must support the min API window type.'
Assert-FileContains $shieldService 'FLAG_NOT_FOCUSABLE' 'Touch-shield window must not take system key focus.'
Assert-FileNotContains $shieldService 'FLAG_NOT_TOUCHABLE' 'Touch-shield window must consume touches.'
Assert-FileContains $shieldService 'addView' 'Touch-shield service must add a window.'
Assert-FileContains $shieldService 'removeView' 'Touch-shield service must remove its window.'
Assert-FileContains $shieldService 'onTouchEvent' 'Touch-shield view must handle touch events.'
Assert-FileContains $shieldService 'return true' 'Touch-shield view must consume touch events.'
Assert-FileContains $shieldService 'PendingIntent' 'Touch-shield notification must expose a stop action.'
Assert-FileContains $shieldService 'FLAG_IMMUTABLE' 'Touch-shield stop PendingIntent must be immutable.'
Assert-FileContains $shieldService 'START_STICKY' 'Touch-shield service must define sticky restart behavior.'
Assert-FileContains $shieldService 'onDestroy' 'Touch-shield service must release resources on destroy.'
Assert-FileContains $shieldService 'stopForeground' 'Touch-shield service must remove foreground state when stopped.'

Write-Host 'Touch-shield contract verification passed.'
