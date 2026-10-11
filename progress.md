## 2026-10-11 - Task: repair environment after Windows reinstall

### What was done

- Synchronized the validated launcher rename to `AI语音` and `AI视频` and advanced both APKs to `versionCode=4` / `versionName=1.1.2`.
- Updated the build entrypoint to prefer `ANDROID_SDK_ROOT`, then `ANDROID_HOME`, then the new user's `%LOCALAPPDATA%\Android\Sdk` location.
- Updated project verification and README commands to use PowerShell 7 (`pwsh`) and removed the stale hard-coded ADB serial from the example.
- Pruned worktree metadata left behind under the pre-reinstall `C:\Users\Simon` path.

### Testing

- `pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tests\verify_project.ps1` -> passed.
- `pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\build.ps1` -> built both APKs from the canonical checkout.
- `apksigner verify --verbose --print-certs` -> voice and video APKs passed v1, v2, and v3 signature verification.
- APK metadata -> `com.simon.doubao.voicecall` / `com.simon.doubao.videocall`, both `versionCode=4`, `versionName=1.1.2`, labels `AI语音` / `AI视频`.
- APK SHA-256 -> voice `E49AAD1753B5C54B048762E1CBF457910FB2F7545566BB70B8A8AFEED06EE329`; video `F965B6896C6A07AE6956AB36204FEB4B476EEA7C40B457F5A80944F5EEB282A6`.
- Signing keystore SHA-256 -> `9226751C6EAE01F7B080DC98D7F366682F06CF6D70FF294F6FFF141A723D66A2`, matching the validated temporary build copy.
- `adb devices -l` -> one online `PJD110` device; target `NTH-AN00` was not connected, so no device installation or launch test was performed.

### Notes

- Modified files: `apps/voice/AndroidManifest.xml` and `apps/video/AndroidManifest.xml` for version metadata; both `strings.xml` files for launcher names; `build.ps1` for SDK discovery; `tests/verify_project.ps1` and `README.md` for the new names and PowerShell 7 commands.
- Rollback before commit: `git restore -- README.md build.ps1 apps/video/AndroidManifest.xml apps/video/res/values/strings.xml apps/voice/AndroidManifest.xml apps/voice/res/values/strings.xml tests/verify_project.ps1`; remove `progress.md` if the progress entry itself should also be discarded.

## 2026-10-11 - Task: execute architecture maintenance refactor

### What was done

- Replaced instance-local cache synchronization with a process-wide lock covering cache reload, version comparison, and deterministic commit.
- Changed the build gate to require the existing signing keystore and removed automatic replacement-key generation.
- Added the refactor contract test, connected it to the aggregate project verification entrypoint, and aligned README and refactor documentation with the fixed rule URL and signing behavior.

### Testing

- Before implementation, `tests/verify_refactor.ps1` failed on the missing `CACHE_WRITE_LOCK` contract as expected.
- After implementation, `verify_rules.ps1`, `verify_refactor.ps1`, and `verify_project.ps1` all passed.
- Missing-keystore negative build test returned exit code 1 and did not create a replacement keystore.
- `build.ps1` generated both APKs; `apksigner verify --verbose --print-certs` passed v1/v2/v3 for both, and both certificate SHA-256 digests matched the retained keystore.
- `smoke_adb.ps1` passed on the online `PJD110` device without launching either app. That device still has the earlier `1.1.1` packages; the target `NTH-AN00` was not online, so current `1.1.2` device launch, volume, authorization, and Doubao entry behavior remain unverified.

### Notes

- Modified files: `common/src/com/simon/doubaolauncher/RuleCache.java` for cross-instance cache serialization; `build.ps1` for signing-key enforcement; `tests/verify_refactor.ps1` for regression contracts; `tests/verify_project.ps1` for the aggregate gate and current documentation paths; `README.md` for signing and fixed-URL runtime behavior; `docs/refactor/2026-10-11-architecture-maintenance-refactor-plan.md` for final implementation evidence.
- Rollback point: keep `HEAD ba6d851` and the pre-existing working-tree changes; reverse only this round's RuleCache/build/test/document hunks after reviewing `git diff`, and remove the newly added `tests/verify_refactor.ps1` and `work/` temporary directory. Do not restore whole files because they contain earlier user changes.

## 2026-10-11 - Task: install and exercise the refactor build on the paired device

### What was done

- Paired the wireless ADB device at the user-provided endpoint, identified it as `M2007J1SC`, and installed both current APKs with `adb install -r`.
- Verified the installed package names, `versionCode=4`, `versionName=1.1.2`, permissions, and launcher activity resolution.
- Cold-launched both launchers. The voice route reached Doubao `RealtimeCallActivity`; the video route carried `open_vlm=1` and `sec_scene=shortcuts_video_call` and reached the video UI with camera controls and “视频通话” selected.
- Verified device audio after launch: media stream `150/150`, voice-call stream `11/11`, both unmuted. Audio logs showed both launcher packages applying the volume changes.

### Testing

- `tests/smoke_adb.ps1 -DeviceId <paired-device>` -> passed.
- `adb install -r` -> voice and video both returned `Success`.
- Voice and video launcher monkey starts -> returned successfully; Activity Manager and UIAutomator evidence confirmed the respective Doubao routes.

### Notes

- Modified files: `docs/refactor/2026-10-11-architecture-maintenance-refactor-plan.md` for the paired-device evidence; `progress.md` for this installation and test record.
- Rollback point: device-only rollback is `adb -s <paired-device> uninstall com.simon.doubao.voicecall` and `adb -s <paired-device> uninstall com.simon.doubao.videocall`; repository rollback remains the prior documented worktree diff, with no commit or push performed.

## 2026-10-11 - Task: install and test on Xiaomi Mi 10 Ultra

### What was done

- Paired the user-provided wireless ADB endpoint `192.168.2.9:41189` and verified the target as `Xiaomi / M2007J1SC / Android 13 (API 33)`.
- Covered-installed the rebuilt formal APKs with `adb install -r`; both returned `Success`.
- Granted the video app's `显示在其他应用的上层` and `允许通知` permissions through the Xiaomi Settings UI. Readback confirmed `SYSTEM_ALERT_WINDOW: allow` and `POST_NOTIFICATIONS: granted=true`.
- Verified both installed packages are `versionCode=5`, `versionName=1.2.0`; labels remain `AI语音` and `AI视频`.

### Device testing

- Video launch reached `com.larus.nova/com.larus.voicecall.impl.ui.RealtimeCallActivity` after the remote rule wait and logged the video route with `open_vlm=1` and `sec_scene=shortcuts_video_call`.
- The touch shield remained as a foreground `TouchShieldService` after the launcher Activity exited. WindowManager and InputManager showed a full app-area `APPLICATION_OVERLAY` with a touchable region `[0,90][1080,2210]`, `NOT_FOCUSABLE`, and no `NOT_TOUCHABLE` flag.
- A center-screen touch produced identical before/after UIAutomator XML hashes (`4f15b3458f88b91274cbde9a6196b519`) and the Doubao `RealtimeCallActivity` remained focused.
- The notification action `解除遮罩` stopped the foreground service, removed the overlay window, and removed the active shield notification while leaving Doubao open.
- Voice launch reached the same Doubao realtime call Activity without starting `TouchShieldService` or creating a launcher overlay window.
- `dumpsys audio` confirmed after launcher execution: `STREAM_MUSIC` `streamVolume=150`, `Max=150`, unmuted; `STREAM_VOICE_CALL` `streamVolume=11`, `Max=11`, unmuted.

### Testing

- `verify_rules.ps1` -> passed.
- `verify_refactor.ps1` -> passed.
- `verify_touch_shield.ps1` -> passed.
- `verify_project.ps1` -> passed.
- `git diff --check` -> passed with only existing LF/CRLF conversion warnings.
- `build.ps1` -> rebuilt both APKs successfully; `apksigner verify` passed v1, v2, and v3 for both.
- Both formal APKs were reinstalled to the Xiaomi device after the rebuild and their package/version/permission state was read back successfully.

### Notes

- This Xiaomi run is the current device evidence for the video shield, notification stop action, voice separation, rule-driven Doubao entry, and volume protection. No source code changes were needed during the device test.
