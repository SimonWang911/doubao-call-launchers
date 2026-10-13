# AI视频触摸遮罩重构计划开发前 Review

> 历史 Review：2026-10-13 已由 [当前设计及实施结果](2026-10-13-foreground-session-refactor-plan.md) 取代。下文关于先挂罩、旧版本和旧范围的结论不适用于当前代码。

## Review 范围

- Review 对象：`docs/refactor/2026-10-11-video-touch-shield-refactor-plan.md`。
- 对照代码：当前 `CallLauncherActivity`、两个 Manifest、`build.ps1`、规则加载链路和现有 PowerShell 门禁。
- Review 目标：确认视频跨应用触摸遮罩、短播报和既有语音/规则行为能够在当前无 Gradle 的构建结构中实现，并且测试能够证明关键不变量。
- Review 边界：本阶段只修改开发文档和 Review 文档，不修改 Java、Manifest、规则 JSON 或构建脚本。

## 已确认的代码事实

1. `CallLauncherActivity.openDoubaoCall()` 在当前 `CallLauncherActivity.java:99-109` 直接播报并调用 `startActivity(intent)`；成功播报完成后 Activity 会在 `finishSoon()` 中退出。项目中不存在 `TouchShieldService`。
2. `apps/video/AndroidManifest.xml:7-34` 当前只有音频设置、网络权限和启动 Activity，没有悬浮窗权限、前台服务权限或 Service 声明；语音 Manifest 同样没有服务声明。
3. `build.ps1:64-65` 使用 `minSdk=23`、`targetSdk=36`，因此 Android 14+ 前台服务类型声明、Android 13+ 通知可见性和 Android 8+ 前台启动路径都必须纳入实现和测试。
4. 当前构建脚本直接编译 `common/src` 的全部 Java；新增 Service 会进入两个 APK 的 DEX，但只有视频 Manifest 注册它，必须依靠 Manifest 和 Activity 模式分流保证语音 APK 不启用遮罩。
5. 当前 `RuleRepository`、`RuleCache`、远程规则格式、固定豆包包名和 Intent 入口均已在上一轮重构中验证，本轮没有证据要求改变它们。

## 发现与处理

### P1-1：前台服务与通知权限契约不完整

**问题**：原计划提到 `FOREGROUND_SERVICE` 和 `specialUse`，但没有明确 `FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`specialUse` property 以及通知不可见时的运行策略。Android 13+ 如果通知权限被拒绝，通知动作可能不可见；这会让已经覆盖豆包的遮罩没有家人可用的解除入口。Android 14+ 使用 `specialUse` 还需要对应的专用前台服务权限和用途属性。

**修订**：视频 Manifest 必须声明：

- `SYSTEM_ALERT_WINDOW`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS`

Service 必须声明 `android:foregroundServiceType="specialUse"` 和 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`。视频入口在启动 Service 前同时检查 `Settings.canDrawOverlays()` 和 Android 7.0+ 的 `NotificationManager.areNotificationsEnabled()`；任一不满足时失败关闭，不打开豆包视频，并播报家人设置提示。这样不能绕过系统授权，也不会进入“已打开视频但没有解除路径”的半保护状态。语音 APK 不增加这些 Manifest 声明。

### P1-2：`startForegroundService()` 后立即 `startActivity()` 存在遮罩尚未挂载的竞态

**问题**：Service 启动调用返回并不等于 `onStartCommand()` 已执行、前台通知已建立且 `WindowManager.addView()` 已成功。若 Activity 立即打开豆包，切换初期可能存在短暂无遮罩窗口，不能证明“先保护、后打开”。

**修订**：采用显式就绪回执，不引入绑定服务或额外进程：

1. Activity 将 `ResultReceiver` 和 START action 传给视频 Service。
2. Service 完成前台通知创建和全屏触摸消费 View 挂载后回传 `READY`。
3. 只有收到 `READY`，Activity 才调用现有豆包 `startActivity()`。
4. Service 挂载失败回传 `FAILED` 并自行清理；Activity 不打开豆包并播报失败。
5. Activity 对就绪回执设置有限超时；超时发送 STOP、取消等待并播报失败。Activity 销毁后忽略回执并发送 STOP，避免异步回调再次启动豆包。

### P1-3：通知解除路径和 Service 重启语义需要明确

**问题**：原计划提出通知动作，但没有规定 PendingIntent 的安全形式、通知撤销行为和 `START_STICKY` 收到空 Intent 时的处理。如果只在 START action 中创建窗口，系统回收后 sticky 重启可能变成无窗口服务；如果没有可见通知，家人也无法解除。

**修订**：

- STOP 通知动作使用指向本 Service 的显式、不可变 `PendingIntent`，动作执行统一走 `ACTION_STOP`。
- 通知设为 ongoing，停止 Service 时移除通知并移除遮罩。
- `START_STICKY` 的空 Intent 明确定义为“尝试恢复同一遮罩会话”：重新建立通知和窗口；任何失败都清理并停止 Service。
- `onDestroy()`、窗口添加异常、Activity 就绪超时、豆包 `startActivity()` 异常都必须走同一清理路径。
- 不承诺拦截 Home、返回手势、通知栏等系统级操作；通知解除是家人的显式逃生路径。

### P2-1：触摸窗口参数必须同时满足“可触摸”和“不抢系统按键焦点”

**修订**：使用 Android O+ 的 `TYPE_APPLICATION_OVERLAY`，API 23-25 使用兼容窗口类型；设置 `FLAG_NOT_FOCUSABLE` 和屏幕布局标志，不设置 `FLAG_NOT_TOUCHABLE`。透明全屏 View 的 `onTouchEvent()` 固定返回 `true`，普通触摸被消费，豆包内部自动 Activity/状态跳转不依赖触摸，因此不被修改。

### P2-2：现有正确行为和测试边界必须显式保留

**修订**：测试必须分别证明：

- 成功播报精确为“正在启动豆包语音”和“正在启动豆包视频”，不再拼接音量或授权说明。
- 语音 Manifest 不注册遮罩 Service，语音分支不调用 Service 启动路径。
- 现有规则拉取、固定包名、Intent 构造、音量拉满和 Activity TTS 清理契约不变。
- 静态契约测试只能证明源码结构；遮罩是否真实覆盖、普通触摸是否被拦截、豆包视频是否仍能自动跳转、荣耀后台策略是否允许 Service 存活，必须由 ADB/人工运行证据单独记录。

## 方案结论

采用“视频专用前台悬浮窗 Service + 就绪回执 + 通知 STOP 动作”的路线。它只增加一个跨 Activity 生命周期组件，保留当前规则和 Intent 架构；就绪回执解决保护窗口竞态，权限预检和失败关闭解决半保护状态，统一清理解决异常和重启边界。无需引入无障碍服务、Usage Access、设备所有者或数据库。

本 Review 的 P1 问题已通过修订主计划关闭后，才允许进入测试先行阶段；在主计划完成上述修订前不得修改业务代码。

## 主计划必须同步的修订项

1. 增加四项视频权限、`specialUse` property、通知预检和失败关闭。
2. 将“启动 Service 后立即跳转”改为 Service `READY` 回执后跳转，并定义超时和 Activity 销毁行为。
3. 定义 `ACTION_START`、`ACTION_STOP`、sticky 空 Intent、统一清理和不可变通知 PendingIntent。
4. 在测试阶段增加上述契约的旧代码失败证明，并把 `verify_touch_shield.ps1` 接入 `verify_project.ps1`。
5. 在最终验收中区分自动契约证据、APK 构建证据和真实设备证据，不把静态检查冒充遮罩行为验证。

## Review 结论

- 修订前：不通过，P1-1、P1-2、P1-3 会导致实现存在不可接受的保护缺口。
- 按本文件修订主计划后：通过，可进入测试先行和分阶段开发。
