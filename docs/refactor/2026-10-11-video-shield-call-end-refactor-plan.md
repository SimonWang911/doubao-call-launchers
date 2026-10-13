# AI通话半透明遮罩与双击结束通话重构计划

> 历史记录：本计划已由 [2026-10-13 前台会话重构](2026-10-13-foreground-session-refactor-plan.md) 替代，双击、固定授权等待和失败留罩契约不再适用。

## 1. 文档状态、范围和基线

- 状态：开发前计划，基于 `master` 提交 `3a95e98` 的真实代码制定。
- 基线：`TouchShieldService` 已经能够跨 Activity 保持透明触摸遮罩；基线版本只有视频启动等待 `RESULT_READY` 后才打开豆包，语音仍直接打开；远程规则和音量保护链路已有门禁和真机证据。
- 本轮目标：将两个 APK 的遮罩统一改为半透明并显示大字提示，在 3 秒内完成两次有效点击时，通过普通 APK 可用的通知监听通道结束豆包当前通话；只有通话结束得到确认后才清理遮罩。
- 版本：两个 APK 已提升为 `versionCode=6`、`versionName=1.3.0`，避免同签名覆盖安装被系统拒绝。
- 保持不变：两个 APK、包名、应用名、固定豆包包名 `com.larus.nova`、远程规则 JSON、加速 URL 顺序、并发规则请求、缓存版本单调性、缓存存在最多等待 2 秒、无缓存最多等待 10 秒、媒体音量和通话音量拉满、既有豆包 Intent 和成功短播报。
- 范围：AI语音和 AI视频都使用同一套结束操作、半透明遮罩、通知监听和触摸结束手势；仅入口 URI、模式值和成功播报按模式保留差异。
- 明确排除：ADB、`forceStopPackage`、直接杀豆包进程、无障碍服务、设备管理员、坐标注入、远程规则新增遮罩/通知字段、数据库和设置页面。

### 1.1 启动时序修订

荣耀实测发现，通知监听“已授权”不等于监听服务已经完成系统绑定；重装、强停或 ROM 回收后，绑定可能超过启动窗口。遮罩窗口和监听绑定因此必须解耦：

- 启动器仍然要求通知读取授权已开启，防止在明确没有挂断通道时放行。
- `TouchShieldService` 只以“前台服务成功、悬浮窗成功挂载”为 `RESULT_READY` 条件，立即打开对应语音或视频入口。
- `DoubaoNotificationListenerService` 异步重连；双击时若尚未连接，返回 `UNAVAILABLE`，状态进入 `END_FAILED`，遮罩保留并允许再次双击。
- 监听连接延迟不再被误报为“通话保护启动失败”，语音和视频使用同一时序。

## 2. 已确认问题和证据

### R1：当前遮罩没有视觉提示

- 证据：`common/src/com/simon/doubaolauncher/TouchShieldService.java:243-254` 的 `ShieldView` 背景是透明，只消费事件，没有文字或半透明绘制。
- 影响：老人或家人无法从画面判断保护状态，也不知道可用的结束操作。

### R2：当前遮罩不能结束豆包通话

- 证据：`TouchShieldService.java:124-145` 的通知动作只调用 `ACTION_STOP`；`ACTION_STOP` 只移除遮罩和前台状态。主仓库不存在 `NotificationListenerService`、`RemoteViews` 或豆包挂断调用。
- 影响：即使加入双击计数，也只能停止本 APK 的遮罩，豆包通话可能继续。

### R3：普通 APK 缺少稳定的通话控制边界

- 证据：临时探针曾在荣耀 `NTH-AN00`、豆包 `com.larus.nova` 14.9.0 上，通过系统通知监听服务应用豆包 `contentView`/`bigContentView`，查找 `hangup_btn` 并调用 `performClick()`，实测语音和视频均挂断成功；该实现不在主仓库。
- 影响：能力已被设备验证，但通知布局属于豆包外部实现细节，必须固定包名过滤、限制候选通知、捕获异常并确认结果，不能复制探针的开放广播或仅依赖通知 ID `101`。

### R4：通知监听授权和连接状态没有进入双模式启动链路

- 证据：基线提交 `3a95e98` 的两个 Manifest 只有悬浮窗、前台服务和通知权限，没有 `BIND_NOTIFICATION_LISTENER_SERVICE` 服务声明；启动器只检查自己的通知是否可见。
- 影响：用户可能看到遮罩但没有可用的豆包挂断能力，形成半保护状态；监听服务断开时也没有状态反馈。

### R5：当前没有双击状态机和回归测试

- 证据：当前 `ShieldView.onTouchEvent()` 对所有事件直接返回 `true`；现有 `verify_touch_shield.ps1` 只验证源码契约，不能验证 3 秒边界、滑动/长按负例、挂断确认或半透明视觉。
- 影响：新增逻辑容易误触发、重复触发或在失败后误清理遮罩。

### R6：现有 sticky 重启路径可能产生孤立遮罩

- 证据：当前 `TouchShieldService.onStartCommand()` 对 `null` Intent 也按启动处理，并在成功后返回 `START_STICKY`；系统如果在没有有效视频会话的情况下重启服务，可能重新创建悬浮窗，但没有对应的启动 Activity、豆包 Intent 或结果接收者。
- 影响：会出现“遮罩仍在、通话已不存在、没有正常会话收尾”的孤立状态，和本轮要建立的会话状态机冲突。

## 3. 根因和影响范围

统一根因是一次性启动 Activity 和持续通话保护之间缺少明确的会话控制层。`TouchShieldService` 只拥有自己的悬浮窗生命周期，不能触达豆包内部的通话状态；触摸事件、结束请求、通知适配和清理结果也没有共同状态机。

影响范围限定为：

- 两个模式共用的 `CallLauncherActivity` 启动前能力检查和启动编排。
- 两个 APK Manifest 的通知监听与前台遮罩服务注册。
- 共享 Java 源码中的双击识别、会话协调和通知适配。
- `TouchShieldService` 的视觉层、输入层和结束结果处理。
- 测试门禁、README、进度记录和正式版本号。

规则仓库、`RuleCache`、音量算法和豆包 Intent 构造不属于根因范围，本轮禁止顺带重写；语音 APK 只增加与视频相同的保护声明和调用链。

## 4. 技术路线和选择

| 路线 | 结论 | 选择理由 |
| --- | --- | --- |
| ADB、强杀进程 | 拒绝 | 不属于普通 APK 运行时能力，且会终止整个豆包进程，不等价于挂断当前通话。 |
| 无障碍服务点击控件 | 拒绝 | 需要长期授权，ROM 可能重置；不符合当前免维护目标。 |
| 设备管理员/Kiosk | 拒绝 | 不能直接取得豆包挂断入口，部署和回滚成本过高。 |
| 遮罩内坐标模拟 | 拒绝 | 遮罩会消费触摸，普通 APK 不能向豆包窗口注入事件。 |
| 只停止遮罩或本 APK 服务 | 拒绝 | 豆包通话可能继续，不能满足需求。 |
| 两 APK 共用 `NotificationListenerService` + 豆包通知挂断控件 | 采用 | 已有荣耀实测证据覆盖语音和视频，使用系统授权通知通道和豆包自己的 PendingIntent；可通过固定包名、控件证据、结果确认和失败保持控制风险。 |

## 5. 目标架构

```text
AI语音/AI视频 CallLauncherActivity
  -> 拉满音量、加载远程规则
  -> 检查悬浮窗、自己的通知权限、通知监听授权
  -> 启动 TouchShieldService
  -> 遮罩窗口挂载成功即收到 RESULT_READY
  -> 打开对应的豆包语音或视频入口；通知监听在后台完成绑定

TouchShieldService
  -> 前台通知和半透明全屏窗口
  -> 居中大字“ 双击屏幕，结束当前通话 ”
  -> 消费所有触摸，DoubleTapDetector 识别有效双击
  -> 通过 CallEndCoordinator 发起一次结束请求
  -> ENDING 期间继续拦截触摸
  -> 只有 SUCCESS/ALREADY_ENDED 才移除遮罩

DoubaoNotificationListenerService
  -> 由系统绑定，维护连接状态
  -> 只检查固定包名 com.larus.nova 的活动通知
  -> 优先检查已验证的 RemoteViews 挂断控件 hangup_btn
  -> 必要时支持明确可识别的标准挂断 Action
  -> 触发豆包自己的点击回调并等待通话通知消失

CallEndCoordinator
  -> 维护通知监听连接和遮罩结束请求之间的边界
  -> 管理单次结束请求和结果回调
  -> 统一处理成功、未找到、未连接、超时和失败

视频 APK 的本地监听器不可用时
  -> 通过签名级权限保护的 CallEndBridgeService 请求语音 APK 的本地监听器
  -> 仍使用同一套通知适配和结果确认，不改变遮罩状态机
```

## 6. 模块职责和接口变化

### 6.1 `DoubleTapDetector`

- 新文件：`common/src/com/simon/doubaolauncher/DoubleTapDetector.java`。
- 无 Android 依赖；输入为 `DOWN`、`UP`、`MOVE`、`CANCEL` 和显式时间/坐标。
- 只有两个完成且未明显移动的点击才计数；滑动超过 touch slop、长按、取消或超过 3000ms 都重置。
- 两次点击时间差 `<= 3000ms` 触发，不增加最小间隔。
- 触发后进入一次性锁定，由服务在结果返回后重置或结束会话。

### 6.2 `CallEndCoordinator`

- 新文件：`common/src/com/simon/doubaolauncher/CallEndCoordinator.java`。
- 维护已连接的 `DoubaoNotificationListenerService` 实例；连接/断开只影响后续结束请求。
- 提供通知监听授权检查、`requestRebind()`、`requestEnd()` 和公开 `Result` 枚举。
- 只允许一个结束请求在途；重复请求被忽略或返回 `UNAVAILABLE`，不产生多次点击。
- 当视频 APK 的本地监听器未连接时，按固定包名调用语音 APK 的 `CallEndBridgeService`；桥接服务由 `signature` 级自定义权限保护，并继续校验预期的视频包名和同签名关系。
- 不执行系统设置写入、ADB、杀进程或坐标点击。

### 6.3 `DoubaoNotificationListenerService`

- 新文件：`common/src/com/simon/doubaolauncher/DoubaoNotificationListenerService.java`。
- 在两个 Manifest 注册，声明系统要求的 `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` 服务权限和 `android.service.notification.NotificationListenerService` Intent action。
- 仅处理 `com.larus.nova`；不保存或输出通知正文。
- 候选通知优先级：当前版本通知 ID `101` 且含挂断证据，其次为其他豆包通知中含挂断证据者；ID 不是唯一条件。
- RemoteViews 顺序：`contentView`、`bigContentView`、`headsUpContentView`。应用豆包上下文后查找资源名 `hangup_btn`，控件不存在、禁用或应用失败均返回失败。
- 对标准 `Notification.Action` 只接受明确的“挂断/结束通话”标题；任意 Action 不得盲目触发。
- 点击后以目标通知移除且不存在其他可验证挂断通知作为成功证据；超时、断开或异常返回失败，不能提前清理遮罩。
- 通知监听服务自身不作为前台服务，不增加第二套常驻通知。

### 6.4 `TouchShieldService`

- 保留已有前台服务、`ResultReceiver` 就绪回执、显式不可变 STOP PendingIntent 和统一资源清理。
- 启动 Intent 必须显式为 `ACTION_START`；`null`/未知 action 不恢复会话，立即清理并返回 `START_NOT_STICKY`，避免系统 sticky 重启生成孤立遮罩。正常语音和视频启动成功后同样返回 `START_NOT_STICKY`，前台服务由当前有效会话持有。
- 根 View 改为 `FrameLayout`/等价自定义容器，背景使用约 35% 到 45% 黑色透明度；中心显示白色、粗体、大字号、高对比度提示。
- 通过父容器拦截所有触摸，提示子 View 不得穿透；不设置 `FLAG_NOT_TOUCHABLE`。
- 维护 `ACTIVE`、`ENDING`、`END_FAILED` 等最小状态；挂断失败时保留遮罩和家人通知动作。
- 服务启动的 `RESULT_READY` 只要求遮罩已挂载；通知监听服务连接异步完成，不因连接延迟关闭启动流程。双击时若监听器仍未连接，返回 `UNAVAILABLE` 并保留遮罩。

### 6.5 `CallLauncherActivity` 和 Manifest

- 两个模式启动前都检查：悬浮窗权限、自己的通知可见性、通知监听授权。缺失任一能力时播报设置提示并不打开豆包。
- 两个 Manifest 都注册通知监听服务和遮罩前台服务，并申请对应权限；语音 Manifest 额外声明签名级桥接权限和桥接服务，视频 Manifest 申请该权限；共享 Activity 统一调用保护链路。
- 版本号统一提升到 `6/1.3.0`；不改包名和签名。

## 7. 状态、数据流和失败策略

| 状态 | 进入条件 | 允许动作 | 退出条件 |
| --- | --- | --- | --- |
| `STARTING` | 语音或视频启动器请求服务 | 建立前台通知和窗口；请求通知监听重绑 | 遮罩挂载成功为 `ACTIVE`；窗口/前台服务失败为 `STOPPED` |
| `ACTIVE` | `RESULT_READY` 已回传并打开豆包 | 消费触摸；双击发起一次结束请求 | 请求发送为 `ENDING`；监听断开仍保持 `ACTIVE` 但标记不可用 |
| `ENDING` | 第二次有效点击完成 | 继续消费触摸，忽略重复请求 | 结束确认进入 `ENDED`；失败/超时进入 `END_FAILED` |
| `END_FAILED` | 未找到控件、服务断开或确认超时 | 保留遮罩，允许再次双击和家人通知解除 | 重试成功为 `ENDED`；显式解除为 `STOPPED` |
| `ENDED` | 豆包通话结束得到证据 | 移除窗口和前台通知 | `STOPPED` |

通知监听授权未启用时，语音和视频启动都失败关闭。授权已启用但监听器尚未连接时，语音和视频仍可建立遮罩并打开豆包；双击暂时返回 `UNAVAILABLE`，遮罩保持并允许重试。通话过程中断开时同样保留遮罩，不假报成功。现有通知“解除遮罩”仍是家人逃生路径，语义仍只解除本 APK 保护，不冒充豆包挂断确认。

## 8. 兼容、迁移和回滚

- 规则缓存、规则 JSON、固定包名、缓存 key 和远程 URL 不变；正常规则更新不需要重新安装 APK。
- 已安装旧版 AI语音或 AI视频更新到 `1.3.0` 后，需要家人分别为两个 APK 额外开启一次“通知使用权/通知读取权限”和悬浮窗/通知权限；没有该授权时对应入口按失败关闭。
- 悬浮窗和本 APK 通知权限继续沿用旧版要求。
- 通知监听授权由系统设置管理，应用不绕过、不过期伪造，也不自动修改。
- 回滚优先恢复旧版 APK；代码回滚只涉及新增协调器、通知服务、双击检测、两个 Manifest/共享 Activity/遮罩和测试文档，不恢复或覆盖无关文件。

## 9. 追踪矩阵

| 问题 | 实施位置 | 回归测试 | 真实验收证据 |
| --- | --- | --- | --- |
| R1 透明无提示 | `TouchShieldService` 根 View | `verify_touch_shield.ps1` 检查颜色/文字/布局 | 荣耀、小米截图或 UIAutomator 文字，确认视频仍可见 |
| R2 双击不能挂断 | `DoubleTapDetector`、`CallEndCoordinator`、NLS | 纯 Java 双击边界测试、结束状态测试 | 两次点击后豆包通知消失、通话 Activity 退出、遮罩清理 |
| R3 挂断入口版本依赖 | NLS 候选选择和 RemoteViews 适配器 | 固定包名、无控件、非豆包通知负例 | 两台设备实际挂断；未知布局时保持遮罩 |
| R4 授权/连接竞态 | Activity 授权预检、Coordinator 连接状态、服务 READY 时序 | 授权缺失、连接延迟、双击未连接负例 | 禁用通知读取后不打开任一模式；连接延迟仍打开遮罩和入口；未连接双击保持遮罩，重绑后可重试 |
| R4b 跨 APK 监听回退 | `CallEndCoordinator`、`CallEndBridgeService`、语音/视频 Manifest | `verify_call_end.ps1` 检查签名级权限、固定组件和签名校验 | 视频本地监听不可用时可由已签名语音桥接执行；未授权或非 companion 调用被拒绝 |
| R5 误触/重复请求 | `DoubleTapDetector` 和 Service 状态 | 单击、滑动、长按、3 秒边界、重复点击 | 真机触摸负例保持通话和遮罩 |
| R6 sticky 孤立遮罩 | Service action 校验和 `START_NOT_STICKY` | null/未知 Intent 负例 | 服务重启/进程异常后不出现无会话遮罩 |
| 语音与视频保护统一 | 两个模式此前只有视频进入遮罩链路 | 两个 Manifest、共享 Activity 和 TouchShieldService | 两个模式均显示遮罩、支持双击挂断和成功清理 |
| 既有规则行为 | 不修改 `RuleRepository` 等文件 | `verify_rules`、项目门禁 | 正常规则入口和缓存等待逻辑不变 |

## 10. 分阶段实施和完成标准

### 阶段 0：文档和开发前 Review

- 修改：本计划、独立计划 Review 文档。
- 禁止：修改 Java、Manifest、规则、版本和构建脚本。
- 完成：所有 P1 问题有根因级方案，授权/连接/结果确认和测试证据已明确。

### 阶段 1：测试先行

- 修改：新增 `tests/java/DoubleTapDetectorTest.java`、`tests/verify_double_tap.ps1`、`tests/verify_call_end.ps1`，扩展 `verify_touch_shield.ps1`、`verify_project.ps1`；结束控制门禁同时覆盖跨 APK 桥接的签名级权限。
- 目标：先锁定双击时间模型、挂断结果状态、Manifest 隔离、提示视觉和禁止旁路实现。
- 禁止：为了让测试通过而修改业务代码；不接触规则和音量实现。
- 完成：旧代码下新增回归测试因缺少目标能力而失败，失败原因准确指向缺失能力。

### 阶段 2：纯 Java 基础抽象

- 修改：`DoubleTapDetector.java`、`CallEndCoordinator.java` 的可测试部分和测试。
- 目标：完成时间边界、单次请求、结果枚举和通知监听连接边界。
- 完成：纯 Java 测试全部通过，未引入 Android 隐藏 API。

### 阶段 3：通知监听适配器和双模式权限门禁

- 修改：`DoubaoNotificationListenerService.java`、`CallEndBridgeService.java`、两个 Manifest、`CallLauncherActivity.java` 必要预检。
- 目标：建立系统绑定、固定包名过滤、RemoteViews 挂断和通知移除确认；连接延迟不得阻断遮罩启动。
- 完成：编译通过；任一模式授权缺失都不打开对应豆包入口；无控件/点击失败不报告成功。

### 阶段 4：遮罩视觉和双击接入

- 修改：`TouchShieldService.java`、相关测试和 README。
- 目标：两个模式统一半透明提示、双击结束、ENDING/失败保持和清理。
- 完成：READY 顺序不回归；两个模式都启用保护；成功/失败状态分别符合表格。

### 阶段 5：完整构建和设备验收

- 执行：全部 PowerShell 门禁、`git diff --check`、正式构建、签名验证、ADB smoke 和两台设备真实场景。
- 完成：版本/包名/证书、规则、音量、语音与视频遮罩、双击挂断、失败保持和模式分流均有证据；未能验证的 ROM 行为单独标注风险。

## 11. 测试计划和验收标准

### 单元/纯 Java

- 空序列、单击、两次点击间隔 `2999ms`、`3000ms`、`3001ms`。
- 两次点击无最小间隔。
- `MOVE` 超过 touch slop、长按、`CANCEL` 不触发。
- 触发后不重复回调；失败后可重新开始。

### 集成/接口

- Coordinator 未连接返回 `UNAVAILABLE`，不会调用豆包适配器。
- 监听服务只接受固定豆包包名。
- 通知 ID 101 缺失但其他通知含有效 `hangup_btn` 时仍可识别；非豆包通知永不点击。
- `RemoteViews` 应用异常、控件缺失、PendingIntent 失效、通知未消失均返回失败并保持遮罩。
- 通知移除确认后只清理当前启动器会话，不影响另一 APK 的独立进程。

### 结构/构建

- `verify_double_tap.ps1`、`verify_call_end.ps1`、`verify_touch_shield.ps1`、`verify_rules.ps1`、`verify_refactor.ps1`、`verify_project.ps1` 全部通过。
- `build.ps1` 构建两个 `1.3.0` APK；`apksigner verify --verbose --print-certs` 通过，两个 APK 证书摘要相同。
- `git diff --check` 通过；不产生 ADB/强杀进程/无障碍旁路代码。

### 真实设备

- 荣耀 `NTH-AN00` Android 14：分别授权后启动语音和视频，检查半透明文字、对应豆包入口、单击/滑动负例、3 秒双击、通知消失、通话 Activity 退出、遮罩和前台服务清理。
- 小米 Mi 10 Ultra `M2007J1SC` Android 13：重复两个模式的关键路径，记录通知监听重连和悬浮窗行为。
- 禁用通知读取权限和悬浮窗权限分别验证两个入口失败关闭，不打开未保护通话。

## 12. 风险和验收边界

- 豆包通知布局可能更新，`hangup_btn` 不是公开稳定契约；应用只能失败关闭，不能保证未来版本无需更新 APK。
- 通知监听授权属于高敏感特殊访问，必须由家人通过系统设置开启；普通 APK 无法绕过。
- 荣耀/小米可能在重启、后台限制或重新安装后延迟/断开监听；只要授权仍在，连接延迟不阻断遮罩和入口，双击在未连接时保持遮罩并允许重试，通话中断开时同样保持遮罩。
- 半透明悬浮窗不能阻止 Home、系统返回、锁屏、通知栏或系统语音助手等系统级操作。
- “结束通话”不等于强制杀死豆包进程；验收以豆包通话结束、通话通知消失/无挂断控件、通话 Activity 退出和本 APK 服务清理为准。
