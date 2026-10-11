# AI视频触摸遮罩与启动播报重构计划

## 1. 文档状态与范围

- 状态：已完成真实代码复核和独立开发前 Review，P1 缺口已修订，进入测试先行。
- 依据：当前 `master` 工作区、`CallLauncherActivity`、两个 Manifest、规则仓库、构建脚本、现有 CLI 测试和已验证的豆包运行入口。
- 目标：为 `AI视频` 增加跨应用触摸屏蔽会话，并将成功启动播报简化为固定短句；保证遮罩挂载成功后才打开豆包视频。
- 版本：由于当前 `versionCode=4` 已在设备上使用，本轮前向更新为 `versionCode=5`、`versionName=1.2.0`，避免同签名覆盖安装被系统拒绝。
- 保持：两个 APK、当前包名和应用名、远程规则 JSON、四路并发规则请求、2 秒/10 秒等待、缓存版本单调性、媒体音量和通话音量拉满、现有豆包 Intent 规则。
- 排除：不改远程规则格式，不把遮罩配置放进远程 JSON，不改 `AI语音` 的运行行为，不引入无障碍服务、设备所有者/kiosk 模式、Usage Access 或新的数据库。

## 2. 需求复核与已确认问题

### 2.1 R1：当前启动器没有可持续的跨应用遮罩承载者

证据：`common/src/com/simon/doubaolauncher/CallLauncherActivity.java` 当前通过 `startActivity(intent)` 打开豆包，TTS 完成或超时后执行 `finish()`；应用自身 Activity 随后退出，不能继续持有覆盖豆包的应用窗口。

影响：如果把遮罩放在当前 Activity 中，切换到豆包后遮罩会随 Activity 退出或退到后台，无法满足“视频通话期间持续拦截触摸”。

### 2.2 R2：当前视频 APK 没有悬浮窗和前台服务能力

证据：`apps/video/AndroidManifest.xml` 当前只有 `MODIFY_AUDIO_SETTINGS`、`INTERNET` 权限和一个 `CallLauncherActivity`，项目中没有 `Service`、`TYPE_APPLICATION_OVERLAY` 或 `WindowManager` 实现。

影响：普通 Activity 窗口不能稳定覆盖另一个应用；缺少悬浮窗特殊权限时，普通 APK 无法可靠阻止豆包收到触摸。

### 2.3 R3：成功启动播报包含不再需要的长提示

证据：`CallLauncherActivity` 当前将音量提示、正在打开提示和系统授权提醒拼接为 `OPENING_VOICE_MESSAGE`、`OPENING_VIDEO_MESSAGE`。

目标行为：成功路径只播报“正在启动豆包语音”和“正在启动豆包视频”。音量仍然执行，规则失败、豆包未安装和入口失败等错误播报保留。

### 2.4 既有正确行为和平台边界

- `RuleRepository` 继续负责规则拉取、最快有效新规则选择、缓存回写和超时边界；遮罩不是远程规则的一部分。
- `RuleCache` 已用进程级 `CACHE_WRITE_LOCK` 保护版本比较和提交，不在本轮重复重构。
- `CallLauncherActivity` 继续负责读取 Manifest 模式、拉满音量、读取规则和发送豆包 Intent。
- 普通 APK 不能绕过 Android/荣耀跨应用授权；悬浮窗也不能保证拦截 Home、返回手势、通知栏等系统级操作。
- 悬浮窗权限、前台服务存活和荣耀后台策略必须用真实设备验收，不能用静态测试冒充。
- 当前构建使用 target SDK 36；Android 14+ 的 `specialUse` 前台服务必须同时声明 `FOREGROUND_SERVICE_SPECIAL_USE` 和用途 property，Android 13+ 的通知动作依赖通知权限可见。

## 3. 根因与影响范围

### 3.1 统一根因

R1 和 R2 来自同一个架构边界错误：一次性跳转 Activity 被同时当作“启动入口”和“持续会话承载者”。Activity 退出后没有独立的生命周期组件继续管理触摸屏蔽窗口。

R3 是启动反馈文本与实际动作耦合过多，属于同一启动编排模块中的低风险文本问题，不需要新增抽象。

### 3.2 影响范围

- 运行时：`CallLauncherActivity` 增加视频遮罩会话编排；新增 `TouchShieldService` 管理跨应用窗口。
- 配置：仅视频 Manifest 增加悬浮窗、前台服务和服务声明；语音 Manifest 不变。
- 测试：新增遮罩契约测试，并扩展现有项目门禁和 ADB 真实场景测试。
- 文档：README、规则维护说明和本重构计划说明一次性设置、失败策略、解锁和 ROM 限制。

## 4. 可行路线比较

| 路线 | 结论 | 原因 |
| --- | --- | --- |
| 在当前 Activity 中放遮罩 | 不采用 | Activity 跳转豆包后退出，窗口不能稳定跨应用存活。 |
| 无障碍服务拦截触摸 | 不采用 | 需要额外无障碍授权，重启/荣耀策略存在已知风险，超出当前简洁启动器边界。 |
| 设备所有者/kiosk 模式 | 不采用 | 需要改变整机管理方式，部署和回滚成本过高，且不是普通 APK 通用能力。 |
| 视频专用前台悬浮窗服务 | 采用 | 只增加必要生命周期组件，能跨 Activity 保持遮罩，自动豆包页面跳转不依赖触摸，便于真机验证和关闭。 |

## 5. 目标架构与数据流

```text
AI视频 Launcher Activity
  -> maximizeAudibleVolume()
  -> RuleRepository.loadRule()
  -> 校验豆包包名/入口
  -> 检查悬浮窗权限和通知可见性
  -> startForegroundService(TouchShieldService, START + ResultReceiver)
  -> 等待 Service READY（有限超时）
  -> startActivity(豆包视频 Intent)
  -> 播报“正在启动豆包视频”
  -> Launcher Activity 完成 TTS 后退出

TouchShieldService
  -> startForeground(notification with immutable STOP PendingIntent)
  -> WindowManager.addView(full-screen touch-consuming view)
  -> 回传 READY；失败回传 FAILED 并统一清理
  -> 豆包内部 Activity 自动切换时保持窗口
  -> 通知动作 STOP、超时或异常时 removeView/stopForeground/stopSelf
```

视频遮罩采用透明触摸屏蔽层，保留家人查看视频画面的能力；它不是远程规则字段，也不修改豆包深链。服务会使用 `TYPE_APPLICATION_OVERLAY`（旧系统使用兼容窗口类型），不设置 `FLAG_NOT_TOUCHABLE`，由全屏 View 消费触摸事件。

## 6. 重构方案与选择理由

### 6.1 `TouchShieldService`

- 放置：`common/src/com/simon/doubaolauncher/TouchShieldService.java`。
- 只在视频 Manifest 注册和由视频模式启动；共享源码目录不代表语音 APK 会自动启用服务。
- 启动后立即调用 `startForeground()`，创建低重要性常驻通知，避免 Activity 退出后服务被普通后台限制回收。
- Service 完成通知和窗口挂载后通过 `ResultReceiver` 回传 `READY`；窗口或前台服务创建失败回传 `FAILED`，不得让 Activity 在未保护状态打开豆包。
- `ACTION_START` 幂等：重复启动只复用已有窗口，不重复 `addView()`。
- `ACTION_STOP`、窗口异常、Activity 就绪超时和 `onDestroy()` 都走同一清理路径，移除窗口、停止前台状态并释放引用。
- 使用 `START_STICKY` 保持服务会话在普通进程回收后的恢复能力；收到空 Intent 时尝试恢复通知和窗口，恢复失败立即清理并停止。用户/系统显式停止应用时不承诺继续存在。
- 通知提供家人使用的“解除遮罩”动作，使用显式不可变 PendingIntent。遮罩没有面向老人的解锁触摸手势，避免误触解除。

### 6.2 `CallLauncherActivity`

- 保留现有规则加载、音量设置和 Intent 生成。
- 视频入口在启动 Service 前检查悬浮窗权限和通知可见性；没有任一权限时失败关闭，不打开未保护的视频通话，并播报家人设置提示。
- 视频入口只有在收到 Service `READY` 回执后才调用现有 `startActivity()`；Service 失败、就绪超时或 Activity 已销毁时不调用豆包。
- 如果 `startActivity()` 抛出异常，立即发送 `ACTION_STOP`，避免留下无目标的触摸遮罩。
- 语音入口不启动服务。
- 成功播报改为两条固定短句“正在启动豆包语音”“正在启动豆包视频”；失败播报不改变，并为权限/遮罩失败保留清晰错误提示。

### 6.3 Manifest 与平台兼容

- 视频 Manifest 增加 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`，并按当前 target SDK 声明 `specialUse` 前台服务类型及 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 用途属性。
- 视频注册 `TouchShieldService`，`android:exported="false"`。
- 需要用 `Settings.canDrawOverlays()` 检查特殊权限；不能试图通过普通权限或 Intent 绕过。
- 需要用 `NotificationManager.areNotificationsEnabled()` 检查通知可见性；通知不可见时按失败关闭处理，不启动未提供家人解除路径的遮罩会话。
- 对 Android O 之前使用兼容窗口类型；当前主要验收 Android 新版本和荣耀目标设备。
- 通知权限/后台自启动/悬浮窗权限由家人一次性设置；本轮不增加设置页面。

## 7. 模块职责与接口变化

| 模块 | 重构后职责 | 接口变化 |
| --- | --- | --- |
| `CallLauncherActivity` | 启动编排、模式分流、音量、规则和豆包 Intent；仅视频启动遮罩服务。 | 保留 Activity 入口；增加内部服务启动/停止调用。 |
| `TouchShieldService` | 管理前台服务、悬浮窗、触摸消费、READY/FAILED 回执、通知停止动作和资源释放。 | 新增内部 Service Intent：`ACTION_START`、`ACTION_STOP`；START 携带 `ResultReceiver`。 |
| `RuleRepository`/`RuleCache` | 继续处理远程规则和缓存单调版本。 | 无变化。 |
| 视频 Manifest | 授予悬浮窗/前台服务能力并注册服务。 | 增加权限和服务声明。 |
| 语音 Manifest | 保持现有语音入口和权限。 | 不增加遮罩权限或服务。 |

## 8. 兼容及迁移方案

- 已安装的规则缓存、包名、版本和远程 JSON 完全兼容，不需要迁移缓存。
- 已安装旧版 `AI视频` 更新后，如果家人没有悬浮窗权限，视频入口将按失败关闭策略不打开豆包；家人需提前授予权限。
- `AI语音` 可独立继续安装和使用，不依赖悬浮窗权限。
- 系统跨应用授权仍由 Android/荣耀管理，重构不伪造授权、不清除授权。
- 远程规则更新不需要重新构建；遮罩逻辑和播报逻辑变更需要更新 APK。

## 9. 问题到代码、测试和验收追踪矩阵

| 问题/约束 | 代码 | 测试 | 验收证据 |
| --- | --- | --- | --- |
| R1 Activity 退出后遮罩消失 | `TouchShieldService` 独立生命周期；Activity 只编排启动 | `verify_touch_shield.ps1`；服务生命周期模型 | 真机启动后 Activity 退出，悬浮窗仍存在 |
| R2 视频缺少悬浮窗/前台服务能力 | 视频 Manifest 权限和 Service；Activity 权限检查 | Manifest 契约、服务源码契约、ADB smoke | `dumpsys window` 有视频服务窗口；权限已授予 |
| R3 播报过长 | `CallLauncherActivity` 两条固定成功消息 | 精确文本回归测试 | 代码只有目标成功短句，真机人工听感确认 |
| R4 Service 未就绪即打开豆包 | `ResultReceiver` READY/FAILED、Activity 超时 | 启动顺序契约、失败清理契约 | 日志/ADB 证明先有遮罩窗口再到达豆包 |
| R5 通知不可见导致无法解除 | 通知权限声明、可见性预检、不可变 STOP PendingIntent | Manifest/源码契约、权限缺失负向测试 | 通知动作可见并能停止服务；权限缺失时不启动 |
| 自动豆包页面跳转不被破坏 | 遮罩不改规则和 Intent，仅消费触摸 | 语音/视频入口回归、UIAutomator/ActivityManager | 豆包目标 Activity 和视频 UI 仍到达 |
| 语音行为不受影响 | 语音 Manifest 不注册服务，模式分流保持 | 语音 Manifest/源码契约、真机语音启动 | 语音无遮罩且成功进入豆包 |
| 失败不留下遮罩 | `startActivity` 异常发送 STOP | 静态调用链检查、真机错误场景 | 豆包不存在/入口失败后无残留悬浮窗 |
| 系统边界真实可见 | 权限检查和错误播报 | 目标 ROM ADB 检查 | 无悬浮窗权限时明确失败，不冒充已保护 |

## 10. 分阶段实施计划

### 阶段 0：文档和开发前 Review

- 修改位置：本计划、独立 Review 文档。
- 目标：确认服务生命周期、权限、失败关闭、READY 回执、解锁和测试边界。
- 禁止：修改 Java、Manifest、规则 JSON。
- 完成标准：`2026-10-11-video-touch-shield-plan-review.md` 中 P1 问题已纳入本计划，计划能映射到真实文件和验收证据。

### 阶段 1：测试先行

- 修改位置：`tests/verify_touch_shield.ps1`、`tests/verify_project.ps1`。
- 目标：精确锁定目标播报、视频服务注册、权限检查、READY 顺序、触摸消费和错误停止契约。
- 禁止：修改业务代码迎合测试；不增加远程规则测试协议。
- 完成标准：旧实现上测试因服务/Manifest/播报缺失而失败，并能指出正确原因。

### 阶段 2：基础服务实现

- 修改位置：新增 `TouchShieldService.java`。
- 目标：完成前台通知、窗口添加/移除、触摸消费、READY/FAILED 回执、START/STOP 幂等和资源释放。
- 禁止：修改规则选择、音量算法、Activity 的远程请求线程模型。
- 完成标准：服务源码契约通过，项目能编译。

### 阶段 3：迁移视频调用方与播报

- 修改位置：`CallLauncherActivity.java`、`apps/video/AndroidManifest.xml`、必要的测试和 README。
- 目标：视频模式在 READY 后启动豆包并安全处理失败/超时，语音模式不启动服务，成功播报改为短句。
- 禁止：把遮罩启动放进远程规则，不改变语音 APK 行为，不保留重复的旧成功播报逻辑。
- 完成标准：阶段测试通过，voice/video APK 均构建。

### 阶段 4：完整验证与文档收口

- 修改位置：README、进度日志和本计划。
- 目标：完成静态检查、规则测试、构建、签名、ADB smoke、真实启动和遮罩触摸验证。
- 禁止：提交、推送、创建 PR；不覆盖工作区其他修改。
- 完成标准：自动测试和可用设备证据逐项写入追踪矩阵；不可验证项明确列为风险。

## 11. 测试计划和验收标准

### 自动门禁

1. `verify_touch_shield.ps1` 单独通过。
2. `verify_project.ps1` 自动调用规则、重构和触摸遮罩门禁。
3. 目标成功播报精确为两条短句，旧成功播报片段不再存在。
4. 视频 Manifest 有悬浮窗、前台服务、`specialUse`、通知权限和服务，语音 Manifest 没有遮罩服务。
5. Service 源码不含 `FLAG_NOT_TOUCHABLE`，包含 READY/FAILED、显式不可变 STOP PendingIntent 和共享 START/STOP 清理路径。
6. Activity 只有在 READY 回执后才调用豆包 `startActivity()`，并有就绪超时清理。
7. 规则测试、既有重构测试和 `git diff --check` 通过。

### 构建门禁

- `build.ps1` 使用原 keystore 生成两个 APK。
- 包名、版本、应用名、权限和签名不回归。
- voice/video 两个 APK 证书摘要一致，v1/v2/v3 签名验证通过。

### 真实设备验收

- 目标设备授予悬浮窗权限后，启动 `AI视频`，确认豆包视频入口到达。
- Activity 退出后，`dumpsys window` 仍能看到服务的悬浮窗。
- 对视频画面执行普通触摸，不能触发豆包按钮；豆包自身自动状态/Activity 跳转不受影响。
- 通知“解除遮罩”后窗口消失，触摸恢复。
- 启动 `AI语音`，确认不出现遮罩且语音入口仍正常。
- 检查媒体和通话音量仍为设备最大值且未静音。

## 12. 风险与回滚方案

- 荣耀可能在后台限制悬浮窗或前台服务；必须记录目标 ROM 结果，不能承诺所有设备永久存活。
- 遮罩会阻止豆包的挂断和摄像头触摸操作；通知解锁是必要的家人逃生路径。
- 通知权限或悬浮窗权限被撤销时，视频入口会失败关闭；这是为了不进入没有解除路径的半保护状态。
- 系统 Home/返回/通知栏属于普通 APK 无法完全控制的边界。
- 悬浮窗权限被撤销时按失败关闭策略停止视频启动，避免产生“看似保护、实际未保护”的状态。
- 回滚：在提交前按本轮 diff 反向恢复新增服务、视频 Manifest、Activity 播报/调用和测试文档；保留先前用户修改，不对整文件执行恢复。
- 不改包名、不改规则缓存 key、不改远程规则格式，旧版 APK 可通过保留签名和 `versionCode` 规则回退或卸载重装。
