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

## 2026-10-13 - Task: integrate reviewed 1.4.1 refactor and deliver formal APKs

### What was done

- 完成已Review前台会话重构的发布范围核验，源码、测试和开发文档准备集成到当前master，远端基线一致。
- 重新构建1.4.1/code9正式签名语音及视频APK；保留原签名和已实测可执行内容，排除debug/test标记。
- 生成正式构建记录；不把本地原始验收资料、签名或APK纳入源码提交。

### Testing

- tests/verify_project.ps1完整通过：CallSession 62、SessionLease 13、ForegroundObserver 15、实际CallEndCoordinator 9场景、通知证据边界41、真实ADB读取适配5场景，以及规则/配置/挂断/遮罩门禁。
- build.ps1双包构建成功；apksigner v1/v2/v3与原证书校验通过；二进制Manifest及aapt2元数据确认1.4.1/code9、非debug/test。
- 新两包全部非签名ZIP条目与此前真机验收1.4.1逐项哈希一致；构建期间源文件和keystore哈希未变；无旧双击路径DEX符号。
- 所有tests PowerShell脚本解析和git diff --check通过。真实手机结果及未重测边界沿用项目最终Review，不声称本轮新增真机测试。

### Notes

- .gitignore：忽略本地work验收生成物，保留历史原件。
- apps/video/AndroidManifest.xml：同步1.4.1版本、会话组件及签名权限配置。
- apps/voice/AndroidManifest.xml：同步1.4.1版本、会话组件及签名权限配置。
- build.ps1：保留经Review的SDK定位与限定构建目录清理。
- common/src/com/simon/doubaolauncher/CallEndBridgeService.java：实现同签名跨包挂断接口和会话归属校验。
- common/src/com/simon/doubaolauncher/CallEndCoordinator.java：统一有截止的通知挂断及跨包回退。
- common/src/com/simon/doubaolauncher/CallLauncherActivity.java：迁移统一会话准备和启动流程，保留规则、音量及TTS。
- common/src/com/simon/doubaolauncher/CallSession.java：集中生命周期和ENDING持续推进。
- common/src/com/simon/doubaolauncher/DoubaoNotificationListenerService.java：提供通知状态及受当前会话约束的结束操作。
- common/src/com/simon/doubaolauncher/EndRequestPolicy.java：统一结束请求归属和一次完成判断。
- common/src/com/simon/doubaolauncher/ForegroundObserver.java：通过公开使用事件观察前台切换。
- common/src/com/simon/doubaolauncher/SessionLease.java：定义跨包会话租约状态。
- common/src/com/simon/doubaolauncher/SessionRegistry.java：访问统一会话注册与归属。
- common/src/com/simon/doubaolauncher/SessionRegistryProvider.java：提供同签名进程间租约注册。
- common/src/com/simon/doubaolauncher/TouchShieldService.java：统一前台/屏幕/权限状态输入、先撤罩后结束。
- docs/refactor/2026-10-11-video-shield-call-end-final-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-11-video-shield-call-end-plan-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-11-video-shield-call-end-refactor-plan.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-11-video-shield-call-end-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-11-video-touch-shield-plan-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-11-video-touch-shield-refactor-plan.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-12-double-tap-auth-sequencing-plan-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-12-double-tap-auth-sequencing-refactor-plan.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-13-1.4.1-formal-release.md：记录正式包签名、哈希、验收范围和回滚点。
- docs/refactor/2026-10-13-ending-progress-evidence-final-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-13-ending-progress-evidence-plan-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-13-ending-progress-evidence-plan.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-13-foreground-session-final-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-13-foreground-session-plan-review.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- docs/refactor/2026-10-13-foreground-session-refactor-plan.md：纳入对应设计/Review记录及最终证据，旧方案保留弃用标记。
- progress.md：追加本次正式构建、验证和提交范围记录。
- README.md：同步前台状态退出、四项授权、1.4.1及证据边界说明。
- tests/call_notification_evidence.ps1：共享纯通知证据转换并拒绝非法格式。
- tests/foreground_adb.ps1：验证真实窗口/通知，最小通知采集及限定只读恢复。
- tests/java/CallSessionTest.java：保留原特征并覆盖权限撤销、迟到、截止和恢复边界。
- tests/java/coordinator/android/content/ComponentName.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/content/Context.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/content/Intent.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/content/ServiceConnection.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/Build.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/Bundle.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/Handler.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/IBinder.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/Looper.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/Message.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/Messenger.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/RemoteException.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/ResultReceiver.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/os/SystemClock.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/provider/Settings.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/service/notification/NotificationListenerService.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/android/util/Log.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/com/simon/doubaolauncher/CallEndBridgeService.java：实现同签名跨包挂断接口和会话归属校验。
- tests/java/coordinator/com/simon/doubaolauncher/CallEndCoordinatorTest.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/coordinator/com/simon/doubaolauncher/DoubaoNotificationListenerService.java：提供通知状态及受当前会话约束的结束操作。
- tests/java/coordinator/com/simon/doubaolauncher/SessionRegistry.java：访问统一会话注册与归属。
- tests/java/EndRequestPolicyTest.java：验证结束请求归属和重复完成边界。
- tests/java/foreground/android/app/AppOpsManager.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/foreground/android/app/usage/UsageEvents.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/foreground/android/app/usage/UsageStatsManager.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/foreground/android/content/Context.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/foreground/android/os/Process.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/foreground/com/simon/doubaolauncher/ForegroundObserverTest.java：提供实际协调器/观察器测试所需的最小Android边界夹具。
- tests/java/SessionLeaseTest.java：验证租约及跨包归属边界。
- tests/verify_adb_read_recovery.ps1：执行真实读取函数验证5种外部ADB响应。
- tests/verify_call_end.ps1：验证挂断接口、归属及桥契约。
- tests/verify_coordinator.ps1：运行实际协调器的Android边界场景。
- tests/verify_evidence_boundary.ps1：验证实际Git边界、最小采集及历史真值。
- tests/verify_foreground_observer.ps1：运行实际观察器的使用事件场景。
- tests/verify_foreground_session.ps1：运行生命周期/租约/结束策略并禁止旧路径。
- tests/verify_project.ps1：纳入全部回归门禁及1.4.1配置。
- tests/verify_session_inputs.ps1：约束Service连续输入和撤罩顺序。
- tests/verify_touch_shield.ps1：迁移到统一前台会话契约并禁止旧检测路径。
- 可执行回滚：以提交前3a95e98741e2d08d070a79301a6da96fa9831273为基线，发布后用git log定位本次refactor提交，git revert <发布提交SHA>。不reset/强推、不删历史证据、不更换签名。实际推送结果及提交SHA记录于最终交付release-receipt.md。
