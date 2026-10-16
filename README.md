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

`AI语音` and `AI视频` share the protection implementation and source code, while each APK owns its own complete call lifecycle. Version 1.4.2 (code 10) does not require the other launcher to be installed, started, enabled, authorized or on the same version. There is no companion provider, persistent lease, heartbeat or hangup bridge.

Each app checks its own overlay, notification, notification-access and usage-access permissions. Its private foreground service prepares the app's own listener and requires confirmed absence of an existing Doubao call before dispatch. Once Doubao is foreground with a controllable notification, protection appears within three seconds. No fixed authorization delay or double-tap gesture is used.

A process-local session token and a non-listening loopback OS binding serialize the two entry points. The binding sends no data and does not invoke either APK. It lasts from preparation through bounded call-end cleanup, and the OS releases it on process death. Only actual address contention is retried; other acquisition failures are reported immediately. Port 39471 is the stable device-wide coordination resource and must not be changed independently between compatible launcher versions.

Home, Back leaving Doubao, Recents and switching apps trigger exit after about 800ms of stable foreground change. Screen off or lock removes protection immediately and requests hangup. The overlay is removed before the bounded local notification hangup is confirmed. Natural absence is confirmed for 600ms. Unknown notification results are never treated as successful termination. The notification action `结束通话` ends call and protection. Returning or waking does not revive an old session.

Old tokens, notification keys, callbacks and Activity teardown cannot stop or release a later session. After service adoption, only service cleanup releases ownership. Local listener recovery retains the existing eight-second retry window and three-second confirmation bound; no request is sent to the other launcher.

Android usage access does not expose window geometry, so floating/split-screen size changes are not independently recognized. Notification shade and volume panels alone are not exits. Back that merely opens Doubao's confirmation while Doubao stays foreground keeps protection. The shield covers application content; Android Home and system gestures remain usable.

Keep package names, original signing keys, listener component names and rule/cache formats for overwrite upgrades. The old 1.4.1 video app itself still depends on the voice provider: migrate while idle, video first then voice, and do not claim strict mutual exclusion during mixed old/new versions. The completed 1.4.2 architecture supports separate updates without a matching companion version. Do not clear application data to migrate.

Design, development review and validation evidence: `docs/refactor/2026-10-16-independent-launchers-plan.md` and its plan-review/final-review documents. Android integration fixtures are identified separately from genuine Doubao and OEM validation.

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

`tests\verify_project.ps1` runs session and real ownership/kernel-adapter unit tests, the actual foreground observer and call-end coordinator with Android boundary stubs, and structural/configuration guards. It preserves rule/cache/audio/signing checks. Real phone tests use `tests\foreground_adb.ps1 -Serial <phone> -Mode voice -Exit home -LaunchCall` and require an unlocked, exclusively available phone. They verify the shown window surface, current Doubao call notification, window removal and notification disappearance; they never force-stop Doubao. TTS, OEM behavior and physical audio routing still require device evidence.

After building both production APKs, run `tests\verify_independent_apks.ps1 -EvidenceRoot <output>` to verify packaged versions, original certificate, manifests and DEX. For controlled Android integration, first run `tests\build_android_independence.ps1 -OutputRoot <test-apks>`, then `tests\android_independence.ps1 -Serial <emulator-serial> -TestApkRoot <test-apks> -EvidenceRoot <output>`. The runner only accepts a disposable emulator without existing target packages, installs the actual production APKs with an explicitly labelled Doubao fixture, and removes all packages it creates. `-LifecycleOnly` repeats quick reentry and cancellation before dispatch. These fixture results do not replace genuine Doubao/OEM phone validation.

The aggregate gate includes continuous session input and device evidence boundary checks. Generated `work/` is excluded from Git. The phone runner saves only the presence or absence of Doubao call notification 101, using a shared converter; it does not request unredacted notification text. Existing local raw evidence is preserved, while only filtered evidence should be shared.
