# Doubao Call Launchers

Two small Android launcher APKs for opening Doubao voice and video calls directly:

- `AI语音`
- `AI视频`

The apps are intended for elderly or visually impaired users who can ask the phone voice assistant to open one of the launcher apps. After one-time family setup, each app opens its rule-driven entry and protects the corresponding Doubao call once foreground and call readiness are confirmed.

## Purpose

The project deliberately avoids settings screens, accessibility services, and manual in-app choices. The normal flow is:

1. A family member installs and configures the phone once.
2. The elderly user asks the phone voice assistant to open `AI语音` or `AI视频`.
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
pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Output APKs are written to `dist/`.

## Install

```powershell
adb install -r .\dist\doubao-voice-call.apk
adb install -r .\dist\doubao-video-call.apk
```

## Signing

The default build requires `build\doubao-launchers-debug.keystore`. This file is ignored by Git. Keep a backup if you need future overwrite installs on the same phone. If the keystore is missing, the build fails and does not generate a replacement signing key.

For a fixed keystore, set these environment variables before running `build.ps1`:

```powershell
$env:DOUBAO_KEYSTORE='C:\path\to\doubao.keystore'
$env:DOUBAO_KEY_ALIAS='doubao'
$env:DOUBAO_KEYSTORE_PASS='...'
$env:DOUBAO_KEY_PASS='...'
```

If the keystore is lost, a newly signed APK with the same package name cannot overwrite the old installation. Restore the original keystore; uninstall/reinstall would otherwise be required.

## Versioning

Increase `android:versionCode` before distributing an update. `android:versionCode` must only go up once a build has been installed or distributed. `android:versionName` is the human-readable version. If a phone already has a higher `versionCode`, installing a lower one requires uninstalling the app first.

## Volume Behavior

On every launch, the app intentionally maximizes media volume and tries to maximize voice-call volume. It also tries to unmute those streams. This is by design to protect elderly users from accidentally muted or very low volume.

Volume changes affect the corresponding Android audio streams globally, not only Doubao.

## Call Touch Shield

`AI语音` and `AI视频` share the protection implementation: a semi-transparent, touch-consuming foreground overlay. Version 1.4.1 (code 9) leases a single session across both APKs, prepares the foreground service while the launcher is visible, and opens Doubao only after the authorized listener confirms there is no existing call. Protection appears within three seconds after Doubao is observed in the foreground with a controllable call notification. There is no fixed authorization delay or touch gesture to end the call.

Before first use, a family member must grant each launcher:

1. Display over other apps permission.
2. Notification permission, with the launcher touch-shield notification channel enabled.
3. Notification access permission so the launcher can invoke and confirm Doubao's call control.
4. Usage access permission to observe foreground changes through Android's public usage events.

Home, Back leaving Doubao, Recents and switching apps trigger exit after about 800ms of stable foreground change. Screen off or lock triggers exit immediately. The overlay is removed first, then a bounded notification hangup request is confirmed. A failed hangup leaves input available and reports the failure. The notification action `结束通话` ends both call and protection. A naturally ended call removes protection after a short confirmation; notification query failures are not treated as success. Returning or waking does not restore an old session. Pending authorization, network failure or lack of call evidence never creates a shield.

On the tested OnePlus, Back can show Doubao's own hangup confirmation while Doubao remains in the foreground. Protection stays in that case; Home, lock or the protection notification can end the call. Repeated explicit launcher requests use `singleTop` / `onNewIntent`, while ordinary Activity resume does not start another call.

Runtime permissions are continuous state-machine inputs. If permission is revoked while a call is still starting, the withdrawn session keeps observing late call evidence and its original startup deadline. It ends a late controllable call without displaying protection, or finishes when the deadline expires. Restoring permission does not revive a withdrawn session.

Video can bind to the signed voice companion for a disconnected local listener, retaining the bounded eight-second local retry and twelve-second bridge window. Every operation carries the session token and notification key and checks current ownership before acting. Old callbacks, abort commands and notification actions cannot stop a later session. When both APKs are installed, both must support this session protocol; updating only one APK is not supported. Video alone uses its own registry.

Android usage access does not expose Doubao's window geometry. Floating-window or split-screen mode is not independently detected, including when already active before launch. A foreground change through Recents or another app is still an exit. Notification shade and volume panels alone are not exits.

If the video process cannot use its own live notification listener, it may request the voice APK's listener through an explicit internal bridge. The bridge is protected by a signature-level permission and accepts only the signed companion APK; it is not an ADB or process-kill path.

The shield covers application content only. Android system actions such as Home, system Back gestures, and the notification shade are outside the control of a normal APK. The two launchers share the protection implementation but keep separate package processes and voice/video entry rules.

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

The installed APKs load Doubao entry data from the fixed public raw GitHub URL built into `RuleRepository.java`. `rules/remote-rule-url.txt` is an audit record used by humans and verification scripts; the installed APK does not read this file at runtime.

Rule file:

`rules/doubao-call-rules.json`

Every rule update must increment `ruleVersion`. A lower `ruleVersion` must not overwrite a newer cached rule on phones.

The app tries rule URLs in this order, and fetches the candidates concurrently:

1. `https://gh-proxy.com/` + raw URL
2. raw URL
3. `https://wget.la/` + raw URL
4. `https://ghfast.top/` + raw URL

When a valid local cache exists, startup waits up to 2 seconds for a newer valid remote rule. If no newer remote rule arrives in that window, the app launches with the cached rule. Late remote results may update the cache for the next launch, but they never trigger a second Doubao launch in the current run.

When no valid local cache exists, startup waits up to 10 seconds for a valid remote rule. If all remote candidates fail or no valid rule arrives by then, the app shows/speaks the rule-load failure message and does not open Doubao.

Rule validation intentionally checks structure and the fixed Doubao package, not Doubao's internal URI parameters. The APK requires `schemaVersion`, a positive `ruleVersion`, `doubaoPackage` equal to `com.larus.nova`, a non-empty `doubaoActivity`, and non-empty voice/video URIs with a URI scheme.

## Remote Rule Update Procedure

When Doubao changes shortcut or deep-link behavior:

1. Re-check the working Doubao package, Activity, voice URI, and video URI.
2. Edit `rules/doubao-call-rules.json`.
3. Increment `ruleVersion`.
4. Commit and push to GitHub.
5. Confirm the raw GitHub URL in `rules/remote-rule-url.txt` returns the updated JSON.
6. Confirm at least one accelerated URL returns the updated JSON.

The installed APK does not need to be rebuilt for normal Doubao rule updates. Rebuild APKs only when framework code, permissions, package names, app icons, signing, or rule URL hosting changes.

## Optional ADB Smoke Test

```powershell
pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tests\smoke_adb.ps1
```

The smoke test checks device state, package installation, audio settings permission, and launcher resolution. It does not launch the apps unless `-LaunchVoice` or `-LaunchVideo` is explicitly passed.

## Test Scope

`tests\verify_project.ps1` runs session and lease unit tests, the actual foreground observer and call-end coordinator with Android boundary stubs, and structural/configuration guards. It preserves rule/cache/audio/signing checks. Real phone tests use `tests\foreground_adb.ps1 -Serial <phone> -Mode voice -Exit home -LaunchCall` and require an unlocked, exclusively available phone. They verify the shown window surface, current Doubao call notification, window removal and notification disappearance; they never force-stop Doubao. TTS, OEM behavior and physical audio routing still require device evidence.

The aggregate gate includes continuous session input and device evidence boundary checks. Generated `work/` is excluded from Git. The phone runner saves only the presence or absence of Doubao call notification 101, using a shared converter; it does not request unredacted notification text. Existing local raw evidence is preserved, while only filtered evidence should be shared.
