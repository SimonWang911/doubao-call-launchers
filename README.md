# Doubao Call Launchers

Two small Android launcher APKs for opening Doubao voice and video calls directly:

- `豆包语音通话`
- `豆包视频通话`

The apps are intended for elderly or visually impaired users who can ask the phone voice assistant to open one of the launcher apps. Each app immediately routes into the corresponding Doubao call deep link.

## Purpose

The project deliberately avoids settings screens, accessibility services, and manual in-app choices. The normal flow is:

1. A family member installs and configures the phone once.
2. The elderly user asks the phone voice assistant to open `豆包语音通话` or `豆包视频通话`.
3. The launcher app maximizes audible volume and opens the matching Doubao call entry.

## Project Layout

- `apps/voice`: voice-call APK manifest and resources.
- `apps/video`: video-call APK manifest and resources.
- `common`: shared Android Java launcher code.
- `tests`: static project verification and optional ADB smoke checks.
- `build.ps1`: Android SDK CLI build script.

## Build

This project uses the local Android SDK command-line tools directly, without Gradle.

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Output APKs are written to `dist/`.

## Install

```powershell
adb install -r .\dist\doubao-voice-call.apk
adb install -r .\dist\doubao-video-call.apk
```

## Signing

The default build uses `build\doubao-launchers-debug.keystore`. This file is ignored by Git. Keep a backup if you need future overwrite installs on the same phone.

For a fixed keystore, set these environment variables before running `build.ps1`:

```powershell
$env:DOUBAO_KEYSTORE='C:\path\to\doubao.keystore'
$env:DOUBAO_KEY_ALIAS='doubao'
$env:DOUBAO_KEYSTORE_PASS='...'
$env:DOUBAO_KEY_PASS='...'
```

If the keystore is lost, a newly signed APK with the same package name cannot overwrite the old installation. Uninstall/reinstall would be required.

## Versioning

Increase `android:versionCode` before distributing an update. `android:versionCode` must only go up once a build has been installed or distributed. `android:versionName` is the human-readable version. If a phone already has a higher `versionCode`, installing a lower one requires uninstalling the app first.

## Volume Behavior

On every launch, the app intentionally maximizes media volume and tries to maximize voice-call volume. It also tries to unmute those streams. This is by design to protect elderly users from accidentally muted or very low volume.

Volume changes affect the corresponding Android audio streams globally, not only Doubao.

## Doubao Entry Maintenance

The Doubao package, Activity, and deep links are external implementation details. If launching stops working, re-check:

- Doubao package: `com.larus.nova`
- Doubao Activity: `com.larus.home.impl.alias.AliasActivity1`
- Voice/video shortcut deep links from the Doubao launcher shortcut or long-press menu

Current verified deep links:

```text
Voice:
sslocal://flow/realtime_chat?is_from_outer=true&bot_id=7234781073513644036&open_method=shortcuts&sec_scene=shortcuts_call&enter_method=shortcuts

Video:
sslocal://flow/realtime_chat?is_from_outer=true&bot_id=7234781073513644036&open_method=shortcuts&open_vlm=1&sec_scene=shortcuts_video_call&enter_method=shortcuts
```

## Remote Rule Hosting

The installed APKs load Doubao entry data from the public raw GitHub URL recorded in `rules/remote-rule-url.txt`.

Rule file:

`rules/doubao-call-rules.json`

Every rule update must increment `ruleVersion`. A lower `ruleVersion` must not overwrite a newer cached rule on phones.

The app tries rule URLs in this order:

1. `https://gh-proxy.com/` + raw URL
2. raw URL
3. `https://wget.la/` + raw URL
4. `https://ghfast.top/` + raw URL

Remote success updates the local cache. Remote failure uses the last valid cache. Remote failure with no valid cache does not open Doubao.

## Optional ADB Smoke Test

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1 -DeviceId 304b77ee
```

The smoke test checks device state, package installation, audio settings permission, and launcher resolution. It does not launch the apps unless `-LaunchVoice` or `-LaunchVideo` is explicitly passed.

## Test Scope

`tests\verify_project.ps1` is a structural/configuration guard. It verifies important code and manifest markers but does not prove TTS timing, actual system volume changes, or Doubao deep-link behavior on every ROM. Those behaviors require the build, optional ADB smoke test, and targeted manual launch checks when needed.
