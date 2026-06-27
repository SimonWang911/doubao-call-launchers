# Doubao Call Launchers Refactor Maintenance Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the existing two-APK Doubao launcher project so TTS lifecycle, versioning, signing, tests, and maintenance docs are safer without changing the core launch behavior.

**Architecture:** Keep the current project layout: `apps/voice` and `apps/video` hold APK-specific manifests/resources, `common` holds the shared launcher Activity, `tests` holds PowerShell verification, and `build.ps1` builds both APKs with Android SDK CLI tools. Changes are deliberately small and commit-sized; the project remains a no-Gradle, no-UI launcher pair.

**Tech Stack:** Android Java, Android SDK command-line tools (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`), PowerShell tests, ADB for optional smoke checks.

---

## Overall Goal

This refactor fixes reliability and maintenance risks found during review:

- TTS lifecycle can race with delayed shutdown.
- APK versioning is implicit and not suitable for long-term updates.
- Signing behavior is local-machine dependent and underdocumented.
- Static tests do not cover versioning, TTS lifecycle structure, or device install state.
- README does not yet document operational tradeoffs and maintenance rules.

It does not change the user-facing workflow: an elderly or visually impaired user asks the phone voice assistant to open one of two apps, and that app automatically opens the corresponding Doubao voice or video call entry.

## Non-Negotiable Constraints

- Keep two independent APKs:
  - `com.simon.doubao.voicecall`
  - `com.simon.doubao.videocall`
- Keep forced volume maximization on launch:
  - Maximize media volume.
  - Try to maximize voice-call volume.
  - Try to unmute relevant streams.
  - Log failures without blocking Doubao launch.
- Do not add an accessibility service.
- Do not depend on ADB for final user operation.
- Do not add a settings screen or complex UI.
- Do not merge the two launchers into one mode-selecting APK.
- Do not migrate to Gradle in this refactor.
- Do not commit private signing keys or keystores.

## Phase 1: Make TTS Lifecycle Safe

**Goal:** Make spoken prompts more reliable and prevent `TextToSpeech` callbacks from touching a released engine.

**Review correction:** The timeout arm and the actual finish request must be separate state transitions. Arming a timeout is not the same as requesting finish. `finishRequested` must not prevent the timeout from being armed, and the timeout callback must become harmless if `finishSoon()` already ran.

**Files:**

- Modify: `common/src/com/simon/doubaolauncher/CallLauncherActivity.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add failing static checks for the safer TTS lifecycle**

Add assertions to `tests/verify_project.ps1` after the existing prompt assertions:

```powershell
Assert-FileContains $launcherActivity 'UtteranceProgressListener' 'Launcher must wait for or observe TTS completion.'
Assert-FileContains $launcherActivity 'shutdownTts' 'Launcher must centralize TextToSpeech shutdown.'
Assert-FileContains $launcherActivity 'onDestroy' 'Launcher must release TextToSpeech from onDestroy.'
Assert-FileContains $launcherActivity 'activityDestroyed' 'Launcher must guard async TTS callbacks after destroy.'
Assert-FileContains $launcherActivity 'finishRequested' 'Launcher must keep finish request state separate from timeout arm state.'
Assert-FileContains $launcherActivity 'timeoutFinishRunnable' 'Launcher must keep a cancellable timeout finish runnable.'
Assert-FileContains $launcherActivity 'armTtsTimeout' 'Launcher must arm a TTS timeout without marking finish requested.'
Assert-FileContains $launcherActivity 'finishSoon' 'Launcher must finish soon after TTS completion or failure.'
Assert-FileContains $launcherActivity 'cancelTimeoutFinish' 'Launcher must cancel timeout finish when TTS finishes first.'
```

- [ ] **Step 2: Run test and verify it fails for missing TTS lifecycle structure**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: FAIL with one of the new TTS lifecycle messages, such as `Launcher must wait for or observe TTS completion.`

- [ ] **Step 3: Refactor TTS lifecycle in `CallLauncherActivity.java`**

Implement this structure:

```java
import android.speech.tts.UtteranceProgressListener;
```

Add fields/constants:

```java
private static final long SHORT_FINISH_DELAY_MILLIS = 1200L;
private static final long MAX_FINISH_DELAY_MILLIS = 6000L;
private static final String STATUS_UTTERANCE_ID = "doubao_call_launcher_status";

private TextToSpeech tts;
private boolean activityDestroyed;
private boolean finishRequested;
private Runnable timeoutFinishRunnable;
```

Change `speakAndToast` to create a local engine and only operate on the current active instance:

```java
private void speakAndToast(final String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();

    final TextToSpeech[] engineHolder = new TextToSpeech[1];
    TextToSpeech engine = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            TextToSpeech activeEngine = engineHolder[0];
            if (activityDestroyed || activeEngine == null || activeEngine != tts) {
                if (activeEngine != null) {
                    activeEngine.shutdown();
                }
                return;
            }

            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed with status=" + status);
                finishSoon();
                return;
            }

            activeEngine.setLanguage(Locale.CHINA);
            activeEngine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.i(TAG, "TTS started.");
                }

                @Override
                public void onDone(String utteranceId) {
                    finishSoon();
                }

                @Override
                public void onError(String utteranceId) {
                    finishSoon();
                }
            });

            int result = activeEngine.speak(message, TextToSpeech.QUEUE_FLUSH, null, STATUS_UTTERANCE_ID);
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "TextToSpeech speak failed.");
                finishSoon();
            }
        }
    });

    engineHolder[0] = engine;
    tts = engine;
    armTtsTimeout();
}
```

Add centralized finish/shutdown helpers:

```java
private void armTtsTimeout() {
    cancelTimeoutFinish();
    timeoutFinishRunnable = new Runnable() {
        @Override
        public void run() {
            timeoutFinishRunnable = null;
            if (!activityDestroyed && !finishRequested) {
                finishNow();
            }
        }
    };
    getWindow().getDecorView().postDelayed(timeoutFinishRunnable, MAX_FINISH_DELAY_MILLIS);
}

private void finishSoon() {
    if (activityDestroyed || finishRequested) {
        return;
    }
    finishRequested = true;
    cancelTimeoutFinish();
    finishAfterDelay(SHORT_FINISH_DELAY_MILLIS);
}

private void finishAfterDelay(long delayMillis) {
    getWindow().getDecorView().postDelayed(new Runnable() {
        @Override
        public void run() {
            finishNow();
        }
    }, delayMillis);
}

private void finishNow() {
    if (activityDestroyed) {
        return;
    }
    finishRequested = true;
    cancelTimeoutFinish();
    shutdownTts();
    finish();
}

private void cancelTimeoutFinish() {
    if (timeoutFinishRunnable != null) {
        getWindow().getDecorView().removeCallbacks(timeoutFinishRunnable);
        timeoutFinishRunnable = null;
    }
}

private void shutdownTts() {
    if (tts != null) {
        tts.shutdown();
        tts = null;
    }
}

@Override
protected void onDestroy() {
    activityDestroyed = true;
    cancelTimeoutFinish();
    shutdownTts();
    super.onDestroy();
}
```

Update current call sites explicitly:

- Doubao not installed path:
  - Keep `speakAndToast(DOUBAO_NOT_INSTALLED_MESSAGE);`
  - Remove the immediately following standalone `finishAfterDelay();`
- Normal launch path:
  - Keep `speakAndToast(video ? OPENING_VIDEO_MESSAGE : OPENING_VOICE_MESSAGE);`
  - Keep `startActivity(intent);`
  - Remove the immediately following standalone `finishAfterDelay();`
- `ActivityNotFoundException` path:
  - Keep `speakAndToast(ENTRY_UNAVAILABLE_MESSAGE);`
  - Remove the immediately following standalone `finishAfterDelay();`
- `RuntimeException` path:
  - Keep `speakAndToast(OPEN_FAILED_MESSAGE);`
  - Remove the immediately following standalone `finishAfterDelay();`

All paths that speak a prompt must let `speakAndToast(...)` own the TTS completion/failure/timeout finish flow. Only future no-TTS paths may call `finishAfterDelay(SHORT_FINISH_DELAY_MILLIS)` directly.

- [ ] **Step 4: Run static verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: `Project verification passed.`

- [ ] **Step 5: Build both APKs**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: both `doubao-voice-call.apk` and `doubao-video-call.apk` are built and pass apksigner verification.

- [ ] **Step 6: Commit**

Run:

```bash
git add common/src/com/simon/doubaolauncher/CallLauncherActivity.java tests/verify_project.ps1
git commit -m "fix: make TTS lifecycle safe"
```

**Risk:** TTS completion may delay Activity finish more than before.

**Rollback:** Revert this commit. Do not revert any later forced-volume behavior.

## Phase 2: Clarify Audio Volume Handling Without Changing Behavior

**Goal:** Keep forced volume maximization exactly as a requirement, but make the code easier to inspect and test.

**Files:**

- Modify: `common/src/com/simon/doubaolauncher/CallLauncherActivity.java`
- Modify: `tests/verify_project.ps1`

- [ ] **Step 1: Add failing static checks for clearer volume helpers**

Add these assertions to `tests/verify_project.ps1` near the existing AudioManager checks:

```powershell
Assert-FileContains $launcherActivity 'maximizeMusicVolume' 'Launcher must keep explicit media-volume maximization.'
Assert-FileContains $launcherActivity 'maximizeVoiceCallVolume' 'Launcher must keep explicit voice-call volume maximization.'
Assert-FileContains $launcherActivity 'unmuteAndMaximizeStream' 'Launcher must centralize stream unmute and maximize logic.'
```

- [ ] **Step 2: Run test and verify it fails for missing helper names**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: FAIL with `Launcher must keep explicit media-volume maximization.`

- [ ] **Step 3: Refactor the existing audio methods**

Replace the current `maximizeAudibleVolume` and `maximizeStream` layout with:

```java
private void maximizeAudibleVolume() {
    Object audioService = getSystemService(AUDIO_SERVICE);
    if (!(audioService instanceof AudioManager)) {
        Log.w(TAG, "AudioManager is unavailable.");
        return;
    }

    AudioManager audioManager = (AudioManager) audioService;
    maximizeMusicVolume(audioManager);
    maximizeVoiceCallVolume(audioManager);
}

private void maximizeMusicVolume(AudioManager audioManager) {
    unmuteAndMaximizeStream(audioManager, AudioManager.STREAM_MUSIC, "music");
}

private void maximizeVoiceCallVolume(AudioManager audioManager) {
    unmuteAndMaximizeStream(audioManager, AudioManager.STREAM_VOICE_CALL, "voice_call");
}

private void unmuteAndMaximizeStream(AudioManager audioManager, int streamType, String label) {
    try {
        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0);
    } catch (SecurityException ex) {
        Log.w(TAG, "No permission to unmute stream " + label + ".", ex);
    } catch (RuntimeException ex) {
        Log.w(TAG, "Could not unmute stream " + label + ".", ex);
    }

    try {
        int maxVolume = audioManager.getStreamMaxVolume(streamType);
        audioManager.setStreamVolume(streamType, maxVolume, 0);
        Log.i(TAG, "Set stream " + label + " volume to " + maxVolume + ".");
    } catch (SecurityException ex) {
        Log.w(TAG, "No permission to set stream " + label + " volume.", ex);
    } catch (RuntimeException ex) {
        Log.w(TAG, "Could not set stream " + label + " volume.", ex);
    }
}
```

The call to `maximizeAudibleVolume()` must remain before reading mode and launching Doubao.

- [ ] **Step 4: Run static verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: `Project verification passed.`

- [ ] **Step 5: Build both APKs**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: both APKs build and verify.

- [ ] **Step 6: Commit**

Run:

```bash
git add common/src/com/simon/doubaolauncher/CallLauncherActivity.java tests/verify_project.ps1
git commit -m "refactor: clarify audio volume handling"
```

**Risk:** Static tests may need updates if helper names differ.

**Rollback:** Revert this commit. The original forced-volume behavior must remain present after rollback or reimplementation.

## Phase 3: Add Versioning and Keystore Configuration

**Goal:** Make update/install behavior and signing strategy explicit and reproducible. `android:versionCode` is the install-order authority and must only increase after distribution. `android:versionName` is human-readable only. Downgrading after a higher `versionCode` is installed requires uninstall/reinstall.

Rule: versionCode must only go up after a build is installed or distributed.

**Files:**

- Modify: `apps/voice/AndroidManifest.xml`
- Modify: `apps/video/AndroidManifest.xml`
- Modify: `build.ps1`
- Modify: `tests/verify_project.ps1`
- Modify: `README.md`

- [ ] **Step 1: Add failing static checks for versioning and keystore configuration**

Add these variables to `tests/verify_project.ps1`:

```powershell
$gitignore = Join-Path $Root '.gitignore'
```

Add these assertions:

```powershell
Assert-FileContains $voiceManifest 'android:versionCode="1"' 'Voice APK must declare versionCode 1.'
Assert-FileContains $voiceManifest 'android:versionName="1.0.0"' 'Voice APK must declare versionName 1.0.0.'
Assert-FileContains $videoManifest 'android:versionCode="1"' 'Video APK must declare versionCode 1.'
Assert-FileContains $videoManifest 'android:versionName="1.0.0"' 'Video APK must declare versionName 1.0.0.'
Assert-FileContains $buildScript 'DOUBAO_KEYSTORE' 'Build script must support external keystore path.'
Assert-FileContains $buildScript 'DOUBAO_KEY_ALIAS' 'Build script must support external keystore alias.'
Assert-FileContains $buildScript 'DOUBAO_KEYSTORE_PASS' 'Build script must support external keystore password.'
Assert-FileContains $buildScript 'DOUBAO_KEY_PASS' 'Build script must support external key password.'
Assert-FileContains $gitignore '*.keystore' 'Git ignore rules must keep keystores out of source control.'
```

- [ ] **Step 2: Run test and verify it fails for missing versioning**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: FAIL with `Voice APK must declare versionCode 1.`

- [ ] **Step 3: Add explicit versions to both manifests**

Update the root `<manifest>` in `apps/voice/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.simon.doubao.voicecall"
    android:versionCode="1"
    android:versionName="1.0.0">
```

Update the root `<manifest>` in `apps/video/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.simon.doubao.videocall"
    android:versionCode="1"
    android:versionName="1.0.0">
```

- [ ] **Step 4: Add keystore environment-variable support to `build.ps1`**

Replace the fixed keystore variables near the top with:

```powershell
$DefaultKeyStore = Join-Path $Root 'build\doubao-launchers-debug.keystore'
$KeyStore = if ($env:DOUBAO_KEYSTORE) { $env:DOUBAO_KEYSTORE } else { $DefaultKeyStore }
$KeyAlias = if ($env:DOUBAO_KEY_ALIAS) { $env:DOUBAO_KEY_ALIAS } else { 'doubao' }
$KeyStorePass = if ($env:DOUBAO_KEYSTORE_PASS) { $env:DOUBAO_KEYSTORE_PASS } else { 'android' }
$KeyPass = if ($env:DOUBAO_KEY_PASS) { $env:DOUBAO_KEY_PASS } else { 'android' }
$OutDir = Join-Path $Root 'dist'
```

Change keystore generation so only the default local debug keystore is auto-generated:

```powershell
if (-not (Test-Path $KeyStore)) {
    if ($env:DOUBAO_KEYSTORE) {
        throw "Specified DOUBAO_KEYSTORE does not exist: $KeyStore"
    }

    Write-Warning "Generating a local debug keystore. Keep this file if you need future overwrite installs."
    keytool -genkeypair `
        -keystore $KeyStore `
        -storepass $KeyStorePass `
        -keypass $KeyPass `
        -alias $KeyAlias `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=Doubao Call Launcher,O=Local,C=CN" | Out-Null
}
```

Update signing:

```powershell
& $ApkSigner sign `
    --ks $KeyStore `
    --ks-key-alias $KeyAlias `
    --ks-pass "pass:$KeyStorePass" `
    --key-pass "pass:$KeyPass" `
    --out $FinalApk `
    $AlignedApk
```

- [ ] **Step 5: Run static verification and build**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Expected: test passes; both APKs build and verify.

- [ ] **Step 6: Verify APK badging**

Run:

```powershell
$Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$BuildTools = (Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Aapt = Join-Path $BuildTools 'aapt.exe'
& $Aapt dump badging .\dist\doubao-voice-call.apk | Select-String -Pattern '^package|application-label'
& $Aapt dump badging .\dist\doubao-video-call.apk | Select-String -Pattern '^package|application-label'
```

Expected: both package lines show `versionCode='1' versionName='1.0.0'`, and labels are `豆包语音通话` / `豆包视频通话`.

- [ ] **Step 7: Commit**

Run:

```bash
git add apps/voice/AndroidManifest.xml apps/video/AndroidManifest.xml build.ps1 tests/verify_project.ps1 README.md
git commit -m "chore: add app versioning and keystore configuration"
```

**Risk:** Upgrading from an already installed versionCode `0` package to `1` is normal. After this lands, do not decrease `versionCode`.

**Rollback:** Avoid rolling back versionCode after installing version `1`; uninstall/reinstall would be required for downgrade. Losing the keystore also prevents overwrite installation of the same package name; keep the keystore backed up outside Git.

## Phase 4: Add Optional ADB Smoke Test

**Goal:** Add an explicit device-level check that does not launch real Doubao calls by default.

**Files:**

- Modify: `tests/verify_project.ps1`
- Create: `tests/smoke_adb.ps1`
- Modify: `README.md`

- [ ] **Step 1: Add static check for the smoke script**

Add to `tests/verify_project.ps1`:

```powershell
$adbSmokeTest = Join-Path $Root 'tests/smoke_adb.ps1'
Assert-Exists $adbSmokeTest
Assert-FileContains $adbSmokeTest 'LaunchVoice' 'ADB smoke test must require an explicit opt-in to launch voice.'
Assert-FileContains $adbSmokeTest 'LaunchVideo' 'ADB smoke test must require an explicit opt-in to launch video.'
Assert-FileContains $adbSmokeTest 'MODIFY_AUDIO_SETTINGS' 'ADB smoke test must verify audio settings permission.'
Assert-FileContains $adbSmokeTest 'resolve-activity' 'ADB smoke test must verify launcher activity resolution.'
```

- [ ] **Step 2: Run test and verify it fails because `tests/smoke_adb.ps1` is missing**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: FAIL with `Missing expected path: ...tests\smoke_adb.ps1`.

- [ ] **Step 3: Create `tests/smoke_adb.ps1`**

Create this file:

```powershell
$ErrorActionPreference = 'Stop'

param(
    [string]$DeviceId,
    [switch]$LaunchVoice,
    [switch]$LaunchVideo
)

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    if ($DeviceId) {
        & adb -s $DeviceId @Args
    } else {
        & adb @Args
    }
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($Args -join ' ')"
    }
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
```

- [ ] **Step 4: Run static verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: `Project verification passed.`

- [ ] **Step 5: Run smoke test only if a device is online**

Run:

```powershell
adb devices
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee
```

Expected: `ADB smoke test passed.` It must not open either launcher unless `-LaunchVoice` or `-LaunchVideo` is passed.

- [ ] **Step 6: Commit**

Run:

```bash
git add tests/verify_project.ps1 tests/smoke_adb.ps1 README.md
git commit -m "test: add project and adb smoke checks"
```

**Risk:** Some Android versions or OEM ROMs may format `dumpsys`/`resolve-activity` output differently. Treat this as a smoke-test compatibility issue unless installation and manual launcher visibility also fail.

**Rollback:** Revert this commit. Core APK build remains unaffected.

## Phase 5: Expand README Maintenance Guidance

**Goal:** Document the real operating model so this project remains maintainable after the initial build.

**Files:**

- Modify: `README.md`

- [ ] **Step 1: Add README sections**

Update `README.md` to include:

```markdown
## Purpose

These two APKs help elderly or visually impaired users open Doubao voice/video calls by asking the phone voice assistant to open the corresponding launcher app.

## Project Layout

- `apps/voice`: voice-call APK manifest and resources.
- `apps/video`: video-call APK manifest and resources.
- `common`: shared Android Java launcher code.
- `tests`: static and optional ADB verification scripts.
- `build.ps1`: Android SDK CLI build script.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

## Install

```powershell
adb install -r .\dist\doubao-voice-call.apk
adb install -r .\dist\doubao-video-call.apk
```

## Signing

The default build uses `build\doubao-launchers-debug.keystore`. This file is ignored by Git. Keep a backup if you need future overwrite installs on the same phone.

For a fixed keystore, set:

```powershell
$env:DOUBAO_KEYSTORE='C:\path\to\doubao.keystore'
$env:DOUBAO_KEY_ALIAS='doubao'
$env:DOUBAO_KEYSTORE_PASS='...'
$env:DOUBAO_KEY_PASS='...'
```

## Versioning

Increase `android:versionCode` before distributing an update. `android:versionCode` must only go up once a build has been installed or distributed. `android:versionName` is the human-readable version. If a phone already has a higher `versionCode`, installing a lower one requires uninstalling the app first.

## Volume Behavior

On every launch, the app intentionally maximizes media volume and tries to maximize voice-call volume. This is by design to protect elderly users from accidentally muted or very low volume.

## Doubao Entry Maintenance

The Doubao package/activity/deep links are external implementation details. If launching stops working, re-check:

- Doubao package: `com.larus.nova`
- Doubao activity: `com.larus.home.impl.alias.AliasActivity1`
- Voice/video shortcut deep links from the Doubao launcher shortcut or long-press menu

## Optional ADB Smoke Test

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee
```

This does not launch the apps unless `-LaunchVoice` or `-LaunchVideo` is explicitly passed.
```

- [ ] **Step 2: Run static verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
```

Expected: `Project verification passed.`

- [ ] **Step 3: Commit**

Run:

```bash
git add README.md
git commit -m "docs: document build install and maintenance workflow"
```

**Risk:** Low. Documentation-only.

**Rollback:** Revert this commit.

## Final Verification

After all phases:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
adb devices
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee
git status --short --ignored
```

Expected:

- Static project verification passes.
- Both APKs build and pass apksigner verification.
- Optional ADB smoke test passes when a device is connected.
- `git status --short --ignored` only shows ignored build outputs such as `build/` and `dist/`.

`tests/verify_project.ps1` is a structural/configuration guard. It verifies important code and manifest markers but does not prove TTS timing, actual system volume changes, or Doubao deep-link behavior on every ROM. Those behaviors require the build, optional ADB smoke test, and targeted manual launch checks when needed.

## Recommended Commit Order

1. `fix: make TTS lifecycle safe`
2. `refactor: clarify audio volume handling`
3. `chore: add app versioning and keystore configuration`
4. `test: add project and adb smoke checks`
5. `docs: document build install and maintenance workflow`

## Explicitly Out of Scope

- No Gradle migration.
- No single-APK mode switcher.
- No accessibility service.
- No settings page.
- No optional volume toggle.
- No weakening of forced volume maximization.
- No automatic extra tapping inside Doubao.
- No committed private keystore.
- No ADB dependency for normal elderly-user operation.
