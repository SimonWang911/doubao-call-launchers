$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..')

function Assert-FileContains {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not (Test-Path $Path)) {
        throw "Missing file: $Path"
    }

    $content = Get-Content -Raw -Encoding UTF8 $Path
    if ($content -notmatch [regex]::Escape($Pattern)) {
        throw $Message
    }
}

function Assert-Exists {
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )

    if (-not (Test-Path $Path)) {
        throw "Missing expected path: $Path"
    }
}

$voiceManifest = Join-Path $Root 'apps/voice/AndroidManifest.xml'
$videoManifest = Join-Path $Root 'apps/video/AndroidManifest.xml'
$launcherActivity = Join-Path $Root 'common/src/com/simon/doubaolauncher/CallLauncherActivity.java'
$voiceStrings = Join-Path $Root 'apps/voice/res/values/strings.xml'
$videoStrings = Join-Path $Root 'apps/video/res/values/strings.xml'
$voiceIcon = Join-Path $Root 'apps/voice/res/mipmap-anydpi-v26/ic_launcher.xml'
$voiceRoundIcon = Join-Path $Root 'apps/voice/res/mipmap-anydpi-v26/ic_launcher_round.xml'
$videoIcon = Join-Path $Root 'apps/video/res/mipmap-anydpi-v26/ic_launcher.xml'
$videoRoundIcon = Join-Path $Root 'apps/video/res/mipmap-anydpi-v26/ic_launcher_round.xml'
$voiceIconArt = Join-Path $Root 'apps/voice/res/drawable-nodpi/ic_launcher_art.png'
$videoIconArt = Join-Path $Root 'apps/video/res/drawable-nodpi/ic_launcher_art.png'
$buildScript = Join-Path $Root 'build.ps1'
$refactorPlan = Join-Path $Root 'docs/superpowers/plans/2026-06-27-refactor-maintenance-plan.md'
$gitignore = Join-Path $Root '.gitignore'
$voiceAppName = "$([char]0x8c46)$([char]0x5305)$([char]0x8bed)$([char]0x97f3)$([char]0x901a)$([char]0x8bdd)"
$videoAppName = "$([char]0x8c46)$([char]0x5305)$([char]0x89c6)$([char]0x9891)$([char]0x901a)$([char]0x8bdd)"

Assert-FileContains $voiceManifest 'package="com.simon.doubao.voicecall"' 'Voice manifest must use the voice package name.'
Assert-FileContains $videoManifest 'package="com.simon.doubao.videocall"' 'Video manifest must use the video package name.'
Assert-FileContains $voiceManifest 'android:versionCode="1"' 'Voice APK must declare versionCode 1.'
Assert-FileContains $voiceManifest 'android:versionName="1.0.0"' 'Voice APK must declare versionName 1.0.0.'
Assert-FileContains $videoManifest 'android:versionCode="1"' 'Video APK must declare versionCode 1.'
Assert-FileContains $videoManifest 'android:versionName="1.0.0"' 'Video APK must declare versionName 1.0.0.'
Assert-FileContains $voiceStrings "<string name=`"app_name`">$voiceAppName</string>" 'Voice app name must be exact.'
Assert-FileContains $videoStrings "<string name=`"app_name`">$videoAppName</string>" 'Video app name must be exact.'
Assert-FileContains $voiceManifest 'com.simon.doubaolauncher.MODE' 'Voice manifest must pass a launch mode.'
Assert-FileContains $voiceManifest 'voice' 'Voice manifest must pass voice mode.'
Assert-FileContains $videoManifest 'com.simon.doubaolauncher.MODE' 'Video manifest must pass a launch mode.'
Assert-FileContains $videoManifest 'video' 'Video manifest must pass video mode.'
Assert-FileContains $launcherActivity 'com.larus.nova' 'Launcher must target the Doubao package.'
Assert-FileContains $launcherActivity 'com.larus.home.impl.alias.AliasActivity1' 'Launcher must target the verified Doubao alias activity.'
Assert-FileContains $launcherActivity 'getActivityInfo' 'Launcher must read manifest meta-data from ActivityInfo.'
Assert-FileContains $launcherActivity 'metaData.getString(EXTRA_MODE' 'Launcher must read the mode from manifest meta-data.'
Assert-FileContains $launcherActivity 'shortcuts_call' 'Launcher must contain the verified voice-call URI.'
Assert-FileContains $launcherActivity 'shortcuts_video_call' 'Launcher must contain the verified video-call URI.'
Assert-FileContains $launcherActivity 'open_vlm=1' 'Video URI must enable VLM/video mode.'
Assert-FileContains $launcherActivity 'AudioManager' 'Launcher must use AudioManager to protect call audibility.'
Assert-FileContains $launcherActivity 'STREAM_MUSIC' 'Launcher must maximize media volume before launching Doubao.'
Assert-FileContains $launcherActivity 'STREAM_VOICE_CALL' 'Launcher must attempt to maximize voice-call volume before launching Doubao.'
Assert-FileContains $launcherActivity 'ADJUST_UNMUTE' 'Launcher must try to unmute relevant audio streams.'
Assert-FileContains $launcherActivity 'maximizeMusicVolume' 'Launcher must keep explicit media-volume maximization.'
Assert-FileContains $launcherActivity 'maximizeVoiceCallVolume' 'Launcher must keep explicit voice-call volume maximization.'
Assert-FileContains $launcherActivity 'unmuteAndMaximizeStream' 'Launcher must centralize stream unmute and maximize logic.'
Assert-FileContains $launcherActivity 'VOLUME_READY_MESSAGE' 'Launcher must speak a clear volume status prompt.'
Assert-FileContains $launcherActivity 'AUTHORIZATION_HELP_MESSAGE' 'Launcher must explain the first-run system authorization prompt.'
Assert-FileContains $launcherActivity 'UtteranceProgressListener' 'Launcher must wait for or observe TTS completion.'
Assert-FileContains $launcherActivity 'shutdownTts' 'Launcher must centralize TextToSpeech shutdown.'
Assert-FileContains $launcherActivity 'onDestroy' 'Launcher must release TextToSpeech from onDestroy.'
Assert-FileContains $launcherActivity 'activityDestroyed' 'Launcher must guard async TTS callbacks after destroy.'
Assert-FileContains $launcherActivity 'finishRequested' 'Launcher must keep finish request state separate from timeout arm state.'
Assert-FileContains $launcherActivity 'timeoutFinishRunnable' 'Launcher must keep a cancellable timeout finish runnable.'
Assert-FileContains $launcherActivity 'armTtsTimeout' 'Launcher must arm a TTS timeout without marking finish requested.'
Assert-FileContains $launcherActivity 'finishSoon' 'Launcher must finish soon after TTS completion or failure.'
Assert-FileContains $launcherActivity 'cancelTimeoutFinish' 'Launcher must cancel timeout finish when TTS finishes first.'
Assert-FileContains $voiceManifest 'android.permission.MODIFY_AUDIO_SETTINGS' 'Voice manifest must declare permission to change audio settings.'
Assert-FileContains $videoManifest 'android.permission.MODIFY_AUDIO_SETTINGS' 'Video manifest must declare permission to change audio settings.'
Assert-FileContains $voiceIcon '@drawable/ic_launcher_art' 'Voice adaptive icon must use the generated voice artwork.'
Assert-FileContains $voiceRoundIcon '@drawable/ic_launcher_art' 'Voice round adaptive icon must use the generated voice artwork.'
Assert-FileContains $videoIcon '@drawable/ic_launcher_art' 'Video adaptive icon must use the generated video artwork.'
Assert-FileContains $videoRoundIcon '@drawable/ic_launcher_art' 'Video round adaptive icon must use the generated video artwork.'
Assert-Exists $voiceIconArt
Assert-Exists $videoIconArt
Assert-Exists $buildScript
Assert-Exists $refactorPlan
Assert-FileContains $buildScript "Get-ChildItem `$Classes -Recurse -Filter '*.class'" 'Build script must pass every compiled class, including anonymous inner classes, to d8.'
Assert-FileContains $buildScript '--lib $AndroidJar' 'Build script must pass android.jar to d8 as a library to avoid platform-class warnings.'
Assert-FileContains $buildScript 'DOUBAO_KEYSTORE' 'Build script must support external keystore path.'
Assert-FileContains $buildScript 'DOUBAO_KEY_ALIAS' 'Build script must support external keystore alias.'
Assert-FileContains $buildScript 'DOUBAO_KEYSTORE_PASS' 'Build script must support external keystore password.'
Assert-FileContains $buildScript 'DOUBAO_KEY_PASS' 'Build script must support external key password.'
Assert-FileContains $gitignore '*.keystore' 'Git ignore rules must keep keystores out of source control.'
Assert-FileContains $refactorPlan 'armTtsTimeout' 'Refactor plan must separate TTS timeout arming from finish requests.'
Assert-FileContains $refactorPlan 'finishSoon' 'Refactor plan must finish soon after TTS completion or failure.'
Assert-FileContains $refactorPlan 'cancelTimeoutFinish' 'Refactor plan must cancel timeout callbacks when TTS finishes first.'
Assert-FileContains $refactorPlan 'finishRequested' 'Refactor plan must track finish requests separately from timeout arming.'
Assert-FileContains $refactorPlan 'Remove the immediately following standalone `finishAfterDelay();`' 'Refactor plan must explicitly remove duplicate post-TTS finish calls.'
Assert-FileContains $refactorPlan 'versionCode must only go up' 'Refactor plan must document monotonic versionCode upgrades.'
Assert-FileContains $refactorPlan 'ROM formats resolve-activity output differently' 'Refactor plan must document ROM-dependent ADB smoke-test output risk.'
Assert-FileContains $refactorPlan 'structural/configuration guard' 'Refactor plan must state static checks are not full behavior tests.'

Write-Host 'Project verification passed.'
