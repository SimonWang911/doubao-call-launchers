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

## 2026-10-16 - Task: 双启动器独立性设计复核和开发门禁
### What was done
- 核对基线3daf735及真实启动、续租、挂断和清单，确认R1-R4同属跨APK运行依赖；编写开发文档和独立开发前Review。
- 修正LocalSocket错误分类假设，采用公开Os.bind的仅loopback绑定独占资源；无监听、通信、常驻服务或新增权限。
- 在只读Android测试实例运行不同UID互斥和故障恢复；先建立能在旧实现因真实依赖失败的结构回归。
### Testing
- S0两个不同UID的Android包9条操作通过：互斥、正常释放、强停持有者后恢复、卸载另一包后单独运行及系统错误分类；临时包已卸载。证据：任务work/independence-s0/device-result.json。
- 真实旧verify_independence -ContractOnly按跨包Registry/Provider/lease/桥/权限失败；非编译错误。
- 完整旧verify_project通过，日志任务work/independence-baseline-project.txt。Android系统适配与真实豆包/OEM验收明确区分。
### Notes
- docs/refactor/2026-10-16-independent-launchers-plan.md：根因、目标、接口、迁移、追踪矩阵、阶段与回滚。
- docs/refactor/2026-10-16-independent-launchers-plan-review.md：独立复审和高优先级修订、S0/S1证据。
- tests/verify_independence.ps1：先红的独立性门禁；所有权测试随后纳入。
- tests/java/ownership/：针对真实所有权和系统适配的Android边界及回归用例。
- 回滚：只移除本轮新增文档和测试即可返回业务基线3daf735，不reset工作区；本阶段未修改业务代码、签名或Git历史。

## 2026-10-16 - Task: S2本地所有权与S3完整控制链迁移
### What was done
- 新增系统独占资源适配和本进程所有权，Activity申请、FGS接管至结束清理；删除Registry/Provider/租约、跨包心跳和挂断桥，迁移全部调用方与清单。
- 保留原有会话状态、通知key、取消/一次完成、前台观察、规则缓存、URI、图标、音量/TTS和原签名。
### Testing
- S2真实所有权和系统适配35检查通过，生产适配与S0 Android实测源码hash一致。
- S3会话62、观察器15、真实coordinator9场景及结构契约通过。初次汇总中的规则子测试从仓库外运行存在假绿，S4发现后已撤销该部分通过结论并修正、完整重跑。
### Notes
- 本阶段主要改动为common控制链、两清单与对应tests；逐文件清单统一列于下面S4，避免遗漏删除的旧测试桩。
- 回滚点3daf735a5fac5e942a2771fbf4beb6c0d83cfbb7；不reset、不覆盖其他修改、不提交或发布。

## 2026-10-16 - Task: S4最终Review、实际APK构建与真实/受控验收
### What was done
- 在实际APK矩阵中发现并复现singleTop暂停导致新请求被finish的问题；先修订设计，再按onPause取消/onStop结束的生命周期职责修正。
- close失败后身份仍有效、后续永久BUSY的故障测试先红后绿；释放先撤销身份，仅重试已结束资源清理。
- 修正规则验证相对路径及未传递退出码的假绿，迁移遗留历史桥文档断言到当前架构文档。
- 更新设计、开发前Review、最终Review及README；构建原签名1.4.2/code10两包并在一加覆盖安装。
### Testing
- 最终verify_project完整通过：真实所有权39检查、状态62、观察器15、coordinator9、证据边界41（32历史dump）、ADB读取恢复5及规则/缓存/音量/签名等契约。
- 规则非法ruleVersion=0从临时根目录执行，因正确业务原因返回1；真实规则从仓库外验证通过。
- 最终生产APK Android API30受控矩阵27/27；追加快速重入两轮、准备期Home等5/5。临时fixture/probe/两应用均清理。夹具结果不冒充真实豆包或荣耀验证。
- 一加PJD110真实豆包：视频Home、语音Home、视频锁屏3/3；实际遮罩及通知出现、撤罩与通知消失均通过。安装包从手机拉取SHA256与最终构建一致；测试后无通话/遮罩，恢复音量160/9和锁屏120000ms。
- 两包原证书一致、v1/v2/v3签名通过；非debuggable/testOnly；清单/DEX无旧跨包组件、双击检测和测试夹具；keystore hash未变；git diff --check通过。
- 主要证据：任务outputs/independent-launchers-1.4.2-20261016/evidence/及最终Review内逐项矩阵。荣耀当前不可连接，未复测；浮窗几何和音质等边界如最终Review。
### Notes
- apps/video/AndroidManifest.xml：移除对方查询、Provider/桥/自定义权限，版本改1.4.2/code10。
- apps/voice/AndroidManifest.xml：移除对方查询、Provider/桥/自定义权限，版本改1.4.2/code10。
- common/src/com/simon/doubaolauncher/CallEndBridgeService.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- common/src/com/simon/doubaolauncher/CallEndCoordinator.java：只通过自身监听恢复并结束，删除跨包桥。
- common/src/com/simon/doubaolauncher/CallLauncherActivity.java：改本地归属、错误分类、明确dispatch日志和重入生命周期。
- common/src/com/simon/doubaolauncher/DoubaoNotificationListenerService.java：实际挂断动作改为本地当前token校验。
- common/src/com/simon/doubaolauncher/EndRequestPolicy.java：删除仅用于旧桥的超时常量。
- common/src/com/simon/doubaolauncher/KernelCallLock.java：新增公开Os.bind的独占资源适配，不监听或通信。
- common/src/com/simon/doubaolauncher/SessionLease.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- common/src/com/simon/doubaolauncher/SessionOwnership.java：管理本地token、服务接管、释放与已结束资源清理。
- common/src/com/simon/doubaolauncher/SessionRegistry.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- common/src/com/simon/doubaolauncher/SessionRegistryProvider.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- common/src/com/simon/doubaolauncher/TouchShieldService.java：接管本地会话并移除远端续租，保留有界结束顺序。
- docs/refactor/2026-10-16-independent-launchers-final-review.md：记录真实代码证据、设计复审、修订及最终问题验收映射。
- docs/refactor/2026-10-16-independent-launchers-plan-review.md：记录真实代码证据、设计复审、修订及最终问题验收映射。
- docs/refactor/2026-10-16-independent-launchers-plan.md：记录真实代码证据、设计复审、修订及最终问题验收映射。
- progress.md：按阶段追加本次实施、验证与回滚记录。
- README.md：同步各APK独立控制、迁移限制和真实/受控测试入口。
- tests/android_independence.ps1：实装生产APK的27项矩阵及5项生命周期复验，仅限临时模拟器。
- tests/android/fixture/AndroidManifest.xml：受控豆包入口和通知挂断夹具，仅安装到任务模拟器。
- tests/android/fixture/FixtureActivity.java：受控豆包入口和通知挂断夹具，仅安装到任务模拟器。
- tests/android/fixture/FixtureReceiver.java：受控豆包入口和通知挂断夹具，仅安装到任务模拟器。
- tests/android/probe/AndroidManifest.xml：用实际生产解析器/缓存预置规则，无正式包测试入口。
- tests/android/probe/IntegrationProbe.java：用实际生产解析器/缓存预置规则，无正式包测试入口。
- tests/android/probe/VideoIntegrationProbe.java：用实际生产解析器/缓存预置规则，无正式包测试入口。
- tests/android/probe/VoiceIntegrationProbe.java：用实际生产解析器/缓存预置规则，无正式包测试入口。
- tests/build_android_independence.ps1：构建明确标记的通知夹具和规则缓存探针，不进入正式包。
- tests/java/coordinator/android/content/Context.java：迁移真实协调器到本地归属，保留取消、截止与旧回调验证。
- tests/java/coordinator/android/content/Intent.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/content/ServiceConnection.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/os/Bundle.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/os/IBinder.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/os/Message.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/os/Messenger.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/os/RemoteException.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/android/os/ResultReceiver.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/com/simon/doubaolauncher/CallEndBridgeService.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/coordinator/com/simon/doubaolauncher/CallEndCoordinatorTest.java：迁移真实协调器到本地归属，保留取消、截止与旧回调验证。
- tests/java/coordinator/com/simon/doubaolauncher/SessionOwnership.java：迁移真实协调器到本地归属，保留取消、截止与旧回调验证。
- tests/java/coordinator/com/simon/doubaolauncher/SessionRegistry.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/java/ownership/android/system/ErrnoException.java：提供真实所有权/内核适配的最小Android边界与故障/并发回归。
- tests/java/ownership/android/system/Os.java：提供真实所有权/内核适配的最小Android边界与故障/并发回归。
- tests/java/ownership/android/system/OsConstants.java：提供真实所有权/内核适配的最小Android边界与故障/并发回归。
- tests/java/ownership/android/util/Log.java：提供真实所有权/内核适配的最小Android边界与故障/并发回归。
- tests/java/ownership/com/simon/doubaolauncher/SessionOwnershipTest.java：提供真实所有权/内核适配的最小Android边界与故障/并发回归。
- tests/java/SessionLeaseTest.java：删除被本地所有权替代的旧类或旧桥专用测试桩。
- tests/verify_call_end.ps1：迁移独立控制架构的结构与行为门禁，保留无关特征检查。
- tests/verify_foreground_session.ps1：迁移独立控制架构的结构与行为门禁，保留无关特征检查。
- tests/verify_independence.ps1：编译真实所有权与系统适配并禁止旧依赖和双击路径。
- tests/verify_independent_apks.ps1：检查实际APK版本、原签名、清单和DEX残留。
- tests/verify_project.ps1：迁移独立控制架构的结构与行为门禁，保留无关特征检查。
- tests/verify_rules.ps1：规则绝对路径入参并检查Python退出码，消除假绿。
- tests/verify_touch_shield.ps1：迁移独立控制架构的结构与行为门禁，保留无关特征检查。
- 回滚点：3daf735a5fac5e942a2771fbf4beb6c0d83cfbb7；设备回退须原签名和更高versionCode独立构建，不能卸载清数据；不reset或强推。

## 2026-10-16 - Task: 将独立运行重构合入master并推送
### What was done
- 用户在完整重构及验收后明确授权“合并推送”；当前已在master，远端与基线一致，直接提交已审核的53个文件并普通快进推送，不创建额外合并提交。
- 提交前逐项校验最终同步hash、生产源码与已验收APK来源一致；APK、keystore、work和原始手机证据不纳入Git。
### Testing
- 沿用本轮已通过的完整项目门禁、原签名构建、27项Android集成、5项生命周期和一加真实豆包3项结果；交付39个文件hash及29份Android/3份一加精确遮罩窗口重新核对通过。
- fetch确认HEAD与origin/master无差异；提交前再次检查暂存差异和禁止产物，推送后核对远端提交SHA及工作区。
### Notes
- progress.md：追加本次用户授权和提交推送记录，其余52个文件为上一阶段逐项列出的原验收范围，生产代码未再修改。
- 系统Git HTTPS库加载失败，使用已安装的Codex附带Git正常获取远端；不修改认证配置。
- 回滚点3daf735a5fac5e942a2771fbf4beb6c0d83cfbb7；若需撤销已推送变更应git revert本次提交，禁止reset或强推。实际提交SHA和远端确认写入任务交付目录push-receipt。
