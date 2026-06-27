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
$ruleUrlCandidates = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleUrlCandidates.java'
$callEntry = Join-Path $Root 'common/src/com/simon/doubaolauncher/CallEntry.java'
$doubaoRule = Join-Path $Root 'common/src/com/simon/doubaolauncher/DoubaoRule.java'
$ruleValidationException = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleValidationException.java'
$doubaoRuleParser = Join-Path $Root 'common/src/com/simon/doubaolauncher/DoubaoRuleParser.java'
$ruleCache = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleCache.java'
$ruleFetchResult = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleFetchResult.java'
$ruleRepository = Join-Path $Root 'common/src/com/simon/doubaolauncher/RuleRepository.java'
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
$concurrentRulePlan = Join-Path $Root 'docs/superpowers/plans/2026-06-28-concurrent-rule-refresh-plan.md'
$ruleFile = Join-Path $Root 'rules/doubao-call-rules.json'
$remoteRuleUrl = Join-Path $Root 'rules/remote-rule-url.txt'
$verifyRules = Join-Path $Root 'tests/verify_rules.ps1'
$adbSmokeTest = Join-Path $Root 'tests/smoke_adb.ps1'
$gitignore = Join-Path $Root '.gitignore'
$readme = Join-Path $Root 'README.md'
$voiceAppName = "$([char]0x8c46)$([char]0x5305)$([char]0x8bed)$([char]0x97f3)$([char]0x901a)$([char]0x8bdd)"
$videoAppName = "$([char]0x8c46)$([char]0x5305)$([char]0x89c6)$([char]0x9891)$([char]0x901a)$([char]0x8bdd)"

Assert-FileContains $voiceManifest 'package="com.simon.doubao.voicecall"' 'Voice manifest must use the voice package name.'
Assert-FileContains $videoManifest 'package="com.simon.doubao.videocall"' 'Video manifest must use the video package name.'
Assert-FileContains $voiceManifest 'android:versionCode="3"' 'Voice APK must declare versionCode 3.'
Assert-FileContains $voiceManifest 'android:versionName="1.1.1"' 'Voice APK must declare versionName 1.1.1.'
Assert-FileContains $videoManifest 'android:versionCode="3"' 'Video APK must declare versionCode 3.'
Assert-FileContains $videoManifest 'android:versionName="1.1.1"' 'Video APK must declare versionName 1.1.1.'
Assert-FileContains $voiceStrings "<string name=`"app_name`">$voiceAppName</string>" 'Voice app name must be exact.'
Assert-FileContains $videoStrings "<string name=`"app_name`">$videoAppName</string>" 'Video app name must be exact.'
Assert-FileContains $voiceManifest 'com.simon.doubaolauncher.MODE' 'Voice manifest must pass a launch mode.'
Assert-FileContains $voiceManifest 'voice' 'Voice manifest must pass voice mode.'
Assert-FileContains $videoManifest 'com.simon.doubaolauncher.MODE' 'Video manifest must pass a launch mode.'
Assert-FileContains $videoManifest 'video' 'Video manifest must pass video mode.'
Assert-FileContains $launcherActivity 'getActivityInfo' 'Launcher must read manifest meta-data from ActivityInfo.'
Assert-FileContains $launcherActivity 'metaData.getString(EXTRA_MODE' 'Launcher must read the mode from manifest meta-data.'
Assert-FileContains $launcherActivity 'new Thread' 'Launcher must load remote rules off the main thread.'
Assert-FileContains $launcherActivity 'RuleRepository' 'Launcher must use RuleRepository.'
Assert-FileContains $launcherActivity 'runOnUiThread' 'Launcher must return to main thread before UI/Activity work.'
Assert-FileContains $launcherActivity 'RULE_LOAD_FAILED_MESSAGE' 'Launcher must speak a rule-load failure message.'
Assert-FileContains $launcherActivity 'entryForMode' 'Launcher must select voice/video entry from the loaded rule.'
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
Assert-FileContains $voiceManifest 'android.permission.INTERNET' 'Voice manifest must allow network rule fetches.'
Assert-FileContains $videoManifest 'android.permission.INTERNET' 'Video manifest must allow network rule fetches.'
Assert-FileContains $voiceIcon '@drawable/ic_launcher_art' 'Voice adaptive icon must use the generated voice artwork.'
Assert-FileContains $voiceRoundIcon '@drawable/ic_launcher_art' 'Voice round adaptive icon must use the generated voice artwork.'
Assert-FileContains $videoIcon '@drawable/ic_launcher_art' 'Video adaptive icon must use the generated video artwork.'
Assert-FileContains $videoRoundIcon '@drawable/ic_launcher_art' 'Video round adaptive icon must use the generated video artwork.'
Assert-Exists $voiceIconArt
Assert-Exists $videoIconArt
Assert-Exists $buildScript
Assert-Exists $refactorPlan
Assert-Exists $concurrentRulePlan
Assert-Exists $ruleFile
Assert-Exists $remoteRuleUrl
Assert-Exists $verifyRules
Assert-Exists $adbSmokeTest
Assert-Exists $ruleUrlCandidates
Assert-Exists $callEntry
Assert-Exists $doubaoRule
Assert-Exists $ruleValidationException
Assert-Exists $doubaoRuleParser
Assert-Exists $ruleCache
Assert-Exists $ruleFetchResult
Assert-Exists $ruleRepository
Assert-FileContains $ruleFile '"schemaVersion": 1' 'Rule JSON must declare schemaVersion 1.'
Assert-FileContains $ruleFile '"ruleVersion": 1' 'Initial rule JSON must declare ruleVersion 1.'
Assert-FileContains $ruleFile '"doubaoPackage": "com.larus.nova"' 'Rule JSON must target Doubao package only.'
Assert-FileContains $ruleFile '"voice"' 'Rule JSON must contain a voice entry.'
Assert-FileContains $ruleFile '"video"' 'Rule JSON must contain a video entry.'
Assert-FileContains $verifyRules 'Rule verification passed.' 'Rule verification script must check rule semantics.'
$remoteRuleUrlContent = (Get-Content -Raw -Encoding ASCII $remoteRuleUrl).Trim()
if ($remoteRuleUrlContent -notmatch '^https://raw\.githubusercontent\.com/.+/doubao-call-launchers/master/rules/doubao-call-rules\.json$') {
    throw 'remote-rule-url.txt must contain the real public raw GitHub rule URL.'
}
if ($remoteRuleUrlContent.Contains('<') -or $remoteRuleUrlContent.Contains('>')) {
    throw 'remote-rule-url.txt must not contain placeholders.'
}
Assert-FileContains $readme 'Remote Rule Hosting' 'README must document remote rule hosting.'
Assert-FileContains $readme 'rules/remote-rule-url.txt' 'README must document the remote rule URL file.'
Assert-FileContains $readme 'concurrently' 'README must document concurrent rule fetching.'
Assert-FileContains $readme '2 seconds' 'README must document the cached foreground wait.'
Assert-FileContains $readme '10 seconds' 'README must document the no-cache remote wait.'
Assert-FileContains $ruleUrlCandidates 'https://gh-proxy.com/' 'First rule URL candidate must use gh-proxy.com.'
Assert-FileContains $ruleUrlCandidates 'https://wget.la/' 'Rule URL candidates must include wget.la.'
Assert-FileContains $ruleUrlCandidates 'https://ghfast.top/' 'Rule URL candidates must include ghfast.top.'
Assert-FileContains $ruleUrlCandidates 'appendTimestamp' 'Rule URL candidates must append a timestamp cache buster.'
$ruleUrlContent = Get-Content -Raw -Encoding UTF8 $ruleUrlCandidates
$ghProxyIndex = $ruleUrlContent.IndexOf('add(urls, GH_PROXY_PREFIX + cleanRawUrl, nowMillis)')
$rawIndex = $ruleUrlContent.IndexOf('add(urls, cleanRawUrl, nowMillis)')
$wgetIndex = $ruleUrlContent.IndexOf('add(urls, WGET_LA_PREFIX + cleanRawUrl, nowMillis)')
$ghfastIndex = $ruleUrlContent.IndexOf('add(urls, GHFAST_TOP_PREFIX + cleanRawUrl, nowMillis)')
if (-not ($ghProxyIndex -ge 0 -and $rawIndex -gt $ghProxyIndex -and $wgetIndex -gt $rawIndex -and $ghfastIndex -gt $wgetIndex)) {
    throw 'Rule URL candidates must be ordered as gh-proxy, raw, wget.la, ghfast.top.'
}
Assert-FileContains $doubaoRuleParser 'SUPPORTED_SCHEMA_VERSION = 1' 'Rule parser must declare supported schema version.'
Assert-FileContains $doubaoRuleParser 'ALLOWED_DOUBAO_PACKAGE = "com.larus.nova"' 'Rule parser must restrict Doubao package.'
Assert-FileContains $doubaoRuleParser 'Uri.parse' 'Rule parser must parse rule URIs with Android Uri.'
Assert-FileContains $doubaoRuleParser 'getScheme()' 'Rule parser must require a non-empty URI scheme.'
Assert-FileContains $doubaoRuleParser 'ruleVersion' 'Rule parser must validate ruleVersion.'
Assert-FileContains $ruleCache 'SharedPreferences' 'Rule cache must use SharedPreferences.'
Assert-FileContains $ruleCache 'KEY_RULE_JSON' 'Rule cache must store the last valid JSON.'
Assert-FileContains $ruleCache 'commit()' 'Rule cache must use deterministic commit writes.'
Assert-FileContains $ruleCache 'synchronized boolean saveIfNewer' 'Rule cache must synchronize version-aware writes.'
Assert-FileContains $ruleCache 'rule.ruleVersion <= cached.ruleVersion' 'Rule cache must reject equal or lower ruleVersion overwrites.'
Assert-FileContains $ruleCache 'load()' 'Rule cache must load cached rules.'
Assert-FileContains $ruleRepository 'HttpURLConnection' 'Rule repository must fetch remote rules with HttpURLConnection.'
Assert-FileContains $ruleRepository 'setConnectTimeout' 'Rule repository must use a connect timeout.'
Assert-FileContains $ruleRepository 'setReadTimeout' 'Rule repository must use a read timeout.'
Assert-FileContains $ruleRepository 'RuleUrlCandidates.build' 'Rule repository must use ordered URL candidates.'
Assert-FileContains $ruleRepository 'CONNECT_TIMEOUT_MILLIS = 2000' 'Rule repository must use 2 second connect timeouts.'
Assert-FileContains $ruleRepository 'READ_TIMEOUT_MILLIS = 2000' 'Rule repository must use 2 second read timeouts.'
Assert-FileContains $ruleRepository 'CACHE_FOREGROUND_WAIT_MILLIS = 2000' 'Rule repository must use a 2 second foreground wait when cache exists.'
Assert-FileContains $ruleRepository 'NO_CACHE_WAIT_MILLIS = 10000' 'Rule repository must wait up to 10 seconds when no cache exists.'
Assert-FileContains $ruleRepository 'RuleRequestCoordinator' 'Rule repository must coordinate concurrent remote rule requests.'
Assert-FileContains $ruleRepository 'startRequest' 'Rule repository must start concurrent per-candidate requests.'
Assert-FileContains $ruleRepository 'awaitRemoteLaunchResult' 'Rule repository must await a bounded remote launch decision.'
Assert-FileContains $ruleRepository 'markForegroundDecisionMade' 'Rule repository must mark cache/failure foreground decisions.'
Assert-FileContains $ruleRepository 'launchDecisionMade' 'Rule repository must prevent duplicate foreground decisions.'
Assert-FileContains $ruleRepository 'refreshCacheOnly' 'Late remote rules must refresh cache only after cache launch or failure.'
Assert-FileContains $ruleRepository 'cache.saveIfNewer' 'Rule repository must save remote rules through version-aware cache writes.'
Assert-FileContains $ruleRepository 'RuleFetchResult.fromCache' 'Rule repository must fall back to valid cache.'
Assert-FileContains $ruleRepository 'raw.githubusercontent.com' 'Rule repository must contain the public raw GitHub rule URL.'
$ruleRepositoryContent = Get-Content -Raw -Encoding UTF8 $ruleRepository
if ($ruleRepositoryContent.Contains('raw-url-placeholder')) {
    throw 'RuleRepository must not contain placeholder URL text.'
}
$javaSourceRoot = Join-Path $Root 'common/src'
$forbiddenPatterns = @('shortcuts_call', 'shortcuts_video_call', 'open_vlm=1')
foreach ($pattern in $forbiddenPatterns) {
    $match = Get-ChildItem $javaSourceRoot -Recurse -Filter '*.java' | Select-String -Pattern $pattern -SimpleMatch
    if ($match) {
        throw "Framework Java must not contain hardcoded Doubao deep-link fragment: $pattern"
    }
}
Assert-FileContains $buildScript "Get-ChildItem `$Classes -Recurse -Filter '*.class'" 'Build script must pass every compiled class, including anonymous inner classes, to d8.'
Assert-FileContains $buildScript "Get-ChildItem (Join-Path `$Root 'common\src') -Recurse -Filter '*.java'" 'Build script must compile all common Java sources.'
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
Assert-FileContains $concurrentRulePlan 'synchronized boolean saveIfNewer' 'Concurrent rule plan must require synchronized cache writes.'
Assert-FileContains $concurrentRulePlan 'markForegroundDecisionMade' 'Concurrent rule plan must close the timeout race before cache launch/failure.'
Assert-FileContains $concurrentRulePlan '2 seconds' 'Concurrent rule plan must document cached 2 second foreground wait.'
Assert-FileContains $concurrentRulePlan '10 seconds' 'Concurrent rule plan must document no-cache 10 second wait.'
Assert-FileContains $concurrentRulePlan 'first valid higher-version remote' 'Concurrent rule plan must preserve fastest valid higher-version foreground behavior.'
Assert-FileContains $adbSmokeTest 'LaunchVoice' 'ADB smoke test must require an explicit opt-in to launch voice.'
Assert-FileContains $adbSmokeTest 'LaunchVideo' 'ADB smoke test must require an explicit opt-in to launch video.'
Assert-FileContains $adbSmokeTest 'MODIFY_AUDIO_SETTINGS' 'ADB smoke test must verify audio settings permission.'
Assert-FileContains $adbSmokeTest 'android.permission.INTERNET' 'ADB smoke test must verify internet permission.'
Assert-FileContains $adbSmokeTest 'resolve-activity' 'ADB smoke test must verify launcher activity resolution.'
Assert-FileContains $adbSmokeTest 'ROM formats resolve-activity output differently' 'ADB smoke test must explain ROM-dependent resolve output failures.'

powershell -ExecutionPolicy Bypass -File $verifyRules
Write-Host 'Project verification passed.'
