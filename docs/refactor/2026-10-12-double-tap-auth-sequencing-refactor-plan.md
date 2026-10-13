# 双击结束可靠性与豆包授权时序重构计划

> 历史记录：双击和固定等待逻辑已删除，当前设计以 [2026-10-13 前台会话重构](2026-10-13-foreground-session-refactor-plan.md) 为准；本文件仅保留旧方案调查证据。

## 2026-10-13 接手修订（优先于下方原计划）

复核发现固定 10 秒后由后台 Activity 启动前台服务，违反 Android 15+ 的可见悬浮窗前提；固定等待结束仍不能证明 OEM 授权已通过。原计划第 3～5 节的启动职责调整为：

1. Activity 可见时启动 `TouchShieldService ACTION_PREPARE`，进入前台服务但不创建窗口；3 秒内收到 `RESULT_PREPARED` 才 dispatch 豆包。准备阶段通知只显示等待通话，不声称遮罩已启用。
2. dispatch 前记录本次启动的墙钟时间；成功返回后发 `ACTION_START`，由已经运行的服务持有 10 秒宽限期、200ms 就绪检查及总计 40 秒的激活截止时间。Activity 不再在后台启动新的前台服务。
3. 仅在宽限期结束且用户授权的本地 listener 发现本次启动之后发布、包含已验证挂断控件的豆包通知时挂窗。旧通话通知不满足本次启动证据；拒绝/未授权/离开/额度不足/监听未连接均不会盲目挂窗。40 秒内仍无证据则返回失败并清理服务。准备后尚未 arm 的服务 3 秒自动清理。
4. 销毁、dispatch 异常、准备失败取消未完成服务；准备、激活、停止都取消旧任务。两个模式共享此路径，不读 OEM 包名或隐藏窗口 API，不引入额外权限。
   设备首轮发现安装后权限仍启用但 listener 未实际绑定。激活等待阶段每 2 秒在未连接时请求系统重绑，同时记录无隐私的连接状态；宽限期未到时不反复膨胀豆包 RemoteViews。保留 40 秒总截止，先复核绑定/后台设置再计设备成功。
5. bridge 客户端时限必须覆盖 8 秒监听等待 + 3 秒通知挂断确认 + 1 秒 IPC 余量，共 12 秒。串行尝试给每次本地回调加编号，拒绝重复/迟到结果；一次完成后取消任务。
   同时把既有签名级 bridge 的普通 `startService` 改为 `bindService(BIND_AUTO_CREATE)` + 单次 Messenger 请求；这是已有日志中后台服务启动被拒的直接修复。保留原服务/权限/显式目标/调用包校验，增加实际发送 UID 校验；完成或超时解绑，旧连接到达后不得发送。无新后台服务或生产 ADB。
6. 单元回归使用纯 Java gate 与真实 coordinator + 确定性 Android 测试桩，覆盖延迟连接、截止边界、重复/迟到回调、bridge 迟到、完整 12 秒桥接等待。先在当前实现运行并保存预期失败。
7. 设备脚本分开报告授权、触摸回调、挂断请求、通知确认、实际 UI、遮罩清理；只有 `SUCCESS` + 遮罩移除 + UI 通话结束才算真实挂断。`ALREADY_ENDED`/额度不足只证明结束状态清理。各模式至少连续 5 次；每次先静音并在启动后持续静音，通话建立后立即双击节省额度。

| 补充问题 | 实施职责 | 验证 |
| --- | --- | --- |
| 后台延迟 FGS 启动被系统拒绝 | Activity 准备前台服务，服务仅延迟窗口 | 准备先于 dispatch；准备/授权期无窗口；Android 16 实测 |
| 10 秒后仍挡授权/拒绝/离开 | 服务宽限期与新通话通知双条件门禁 | gate false/true 边界；未授权超过 10 秒无窗口；拒绝后清理 |
| bridge 3 秒先于 8+3 秒返回 | 12 秒共享策略常量 | 真 coordinator 回归，11 秒返回可接受、12 秒截止、迟到忽略 |
| 重复旧回调干扰下一次尝试 | coordinator attempt 编号 | 回调两次/跨 attempt 迟到不影响在途请求 |

依据：[Android 15 FGS 限制](https://developer.android.com/about/versions/15/behavior-changes-15)、[NotificationListenerService 可用时机](https://developer.android.com/reference/android/service/notification/NotificationListenerService)。

保留当前工作树及已有文档，阶段顺序为计划独立复核 → 回归预期失败 → 启动修复 → 结束修复 → 全门禁/签名构建 → 授权与连续设备验证 → 最终 Review/progress。没有本轮真机证据前不宣称完成。

## 1. 状态、范围和真实基线

- 状态：2026-10-13 接手复核中；保留既有未提交实现，完成生命周期与回归门禁后再正式构建。
- 目标版本基线：两个 APK `1.3.0`、`versionCode=6`，包名和签名不变。
- 本轮范围：修复系统授权确认层遮挡、通知监听连接竞态和双击结束的错误失败表现；语音和视频必须共用同一套时序和结束请求逻辑。
- 保持不变：远程规则 JSON、加速 URL 顺序、缓存等待策略、固定豆包包名 `com.larus.nova`、启动时拉满媒体/通话音量、现有半透明遮罩视觉、通知挂断适配和双击窗口规则。
- 禁止：ADB、无障碍、坐标注入、杀豆包进程、绕过系统授权、修改规则协议、扩大到无关模块重写。

### 1.1 设备和测试证据

本轮一加 `PJD110` (`192.168.1.51:5555`) 已安装两个 `1.3.0` APK，悬浮窗、通知和通知监听权限已恢复。原始证据目录：

`work/double-tap-reliability/20261012-123815/`

- `video-01-before-tap-ui.xml` 的根包为 `com.oplus.securitypermission`，显示 `“AI视频”想要打开“豆包”`，选项为 `30天内允许`、`仅本次允许`、`取消`。
- 同一 XML 证明系统授权层可能在豆包通话入口真正建立前存在；此时不能创建消费全屏触摸的遮罩。
- `video-02-before-tap-logcat.txt` 记录了 `TouchShieldService$ShieldView$1.onDoubleTap` 和 `DoubleTapDetector.onUp` 调用栈，证明 ADB 双击已经进入业务回调，不是触摸完全丢失。
- 同一日志随后记录 `BackgroundServiceStartNotAllowedException`、`Could not end the Doubao call: UNAVAILABLE`，并在稍后才记录 `Doubao notification listener connected.`，证明结束请求早于监听连接。
- `voice-03-before-tap-logcat.txt` 也记录遮罩已就绪后返回 `UNAVAILABLE`，说明该竞态不应只按视频分支处理。
- 之前测试脚本把窗口转储中的 `TouchShieldService` 字面量作为就绪条件；一加窗口名称只显示 `APPLICATION_OVERLAY`，造成了测试误报。该测试门槛已在工作区脚本中修正，后续验收必须使用服务转储和日志的稳定信号。

## 2. 已确认问题、根因和影响

### P1：系统授权确认层被保护遮罩抢先覆盖

当前真实调用链是：

```text
CallLauncherActivity
  -> startShieldAndOpen()
  -> TouchShieldService 挂载全屏可触摸窗口
  -> RESULT_READY
  -> startActivity(Doubao Intent)
  -> 系统 OEM 弹出“AI语音/AI视频想要打开豆包”确认层
```

`TouchShieldService` 的 `ShieldView` 会拦截触摸；因此系统授权尚未完成时，用户点击可能被保护层消费。该链路还可能在豆包通话通知产生前触发双击结束请求。

影响：授权无法完成、豆包入口未真正建立、双击结束没有可操作的通话通知，启动和双击均表现为偶发失败。

### P1：双击已识别，但结束请求早于通知监听连接

当前 `CallLauncherActivity` 只调用 `CallEndCoordinator.requestRebind()`，随后等待遮罩挂载，不等待 `NotificationListenerService.onListenerConnected()`。`CallEndCoordinator.requestEnd()` 在本地监听为空时立即尝试跨 APK bridge；一加日志证明视频后台调用普通 `startService()` 被 Android 拒绝。

影响：双击回调已发生，但 `TouchShieldService` 进入 `END_FAILED`，遮罩保持，用户误以为双击没有响应。该问题同时影响语音和视频的本地监听重连窗口。

### P2：启动完成条件与通话控制完成条件混在一次启动序列中

`RESULT_READY` 目前只代表悬浮窗已经挂载，却被当作可以立即打开豆包并接受结束操作的完整会话条件。系统授权、豆包入口建立、通知监听连接和遮罩激活没有独立状态边界。

影响：无法针对授权阶段、通话保护阶段和结束请求阶段分别测试，也容易在一个阶段失败时留下错误状态。

### P2：设备测试门禁无法稳定区分“未就绪”和“已触发但结束失败”

现有 `work/run_double_tap_reliability.ps1` 已修正为以服务转储判定遮罩就绪，但仍需补充授权层采样、双击回调日志和结束结果分类。没有这些证据，测试会把系统授权阻塞、监听竞态和触摸识别混成一个失败。

## 3. 选择的重构路线

采用“启动阶段显式分层 + 结束请求有限重试”的单一编排路线：

```text
规则/权限检查
  -> 先 dispatch Doubao Intent
  -> 授权宽限期内不创建悬浮遮罩
  -> 宽限期结束后启动 TouchShieldService
  -> 双击触发 CallEndCoordinator
  -> 在有限窗口内重绑并等待本地通知监听
  -> 本地监听可用后执行一次挂断
  -> 通知确认成功才移除遮罩
```

选择理由：

1. 普通 APK 无法通用地读取或绕过荣耀/一加的 OEM 授权层，先让系统对话框获得输入权是跨 ROM 的唯一可靠路径。
2. 固定授权宽限期不依赖 OEM 包名、窗口文字或隐藏 API，不把系统设置 UI 写入业务代码。
3. 结束请求重试解决已确认的监听连接竞态，避免把“触摸已识别”误报为“结束失败”；有限截止时间避免无限等待。
4. 两个 APK 继续共用共享启动器、遮罩和通知适配，模式差异仍只保留入口 URI、播报文本和包名身份。

## 4. 目标架构和状态

### 4.1 启动状态

```text
RULE_READY
  -> DOUBAO_DISPATCHED
  -> AUTHORIZATION_GRACE
  -> SHIELD_STARTING
  -> SHIELD_ACTIVE
```

- `DOUBAO_DISPATCHED`：`startActivity()` 已成功返回；不创建触摸遮罩。
- `AUTHORIZATION_GRACE`：默认等待 `10_000ms`，给系统授权确认层和豆包入口建立时间；这段时间启动器不消费全屏触摸。普通 APK 无法跨 ROM 确认 OEM 授权层已消失。
- `SHIELD_STARTING`：授权宽限期结束后启动前台遮罩服务；如果系统授权层仍存在，设备测试必须分类为 `authorization-blocked`，不能分类为双击失败。
- `SHIELD_ACTIVE`：遮罩已挂载并可接受双击；应用不再尝试判断或绕过启动阶段的 OEM 授权层。

授权宽限期只延迟遮罩，不绕过授权。用户未在宽限期内完成授权时，系统行为仍由系统决定；应用不能伪造“已授权”，也不通过 OEM 包名、窗口文字或隐藏 API 判断授权结果。

### 4.2 结束状态

```text
ACTIVE
  -> ENDING
  -> ENDED / END_FAILED
```

- `ACTIVE` 的第二次有效点击只允许产生一个在途结束请求。
- `ENDING` 内由 `CallEndCoordinator` 为本次请求每 `200ms` 重绑/检查本地监听，最多 `8_000ms`；监听出现后执行一次真实通知挂断并停止轮询。
- `SUCCESS` 或 `ALREADY_ENDED` 才进入 `ENDED` 并移除遮罩。
- `UNAVAILABLE`、`TIMEOUT` 或点击/确认异常进入 `END_FAILED`，保留遮罩并重置双击识别器，允许再次尝试。
- bridge 继续作为明确的 companion fallback，但不再作为第一次触发时的即时路径；本地请求截止后将请求标记完成，bridge 不得与本地挂断并发，也不得覆盖本地结果。

## 5. 模块职责和代码变化

### `CallLauncherActivity.java`

- 保留规则加载、音量拉满、权限预检和错误播报。
- 将“先启动遮罩再打开豆包”改为“先 `startActivity()`，再通过主线程定时任务启动遮罩”。
- 增加唯一的授权宽限期常量 `AUTHORIZATION_GRACE_MILLIS = 10_000L`，只允许一个激活任务存在；销毁、启动失败或重试时必须取消任务。该时间只是“不消费触摸”的授权宽限期，不能证明 OEM 授权层已经消失。
- Activity 在遮罩启动回执完成前不能因成功播报提前结束；错误路径仍按原有 TTS 超时结束。
- 遮罩双击入口记录固定的 `double tap detected; requesting call end` 日志，不记录坐标、通知正文、规则内容或通话内容。
- 不增加 OEM 包名判断、不读取系统隐藏状态、不改变 Intent URI 或规则字段。

### `CallEndCoordinator.java`

- 保留本地监听优先、固定 companion bridge 和签名边界。
- 将本地监听为空时的即时失败改为有限重试：每 `200ms` 请求重绑并检查当前监听实例，达到 `8_000ms` 后才进入 bridge/失败路径。
- 每个结束请求拥有独立截止时间和一次性回调，避免重复双击导致多次挂断或多次回调。
- 监听连接/断开继续通过现有静态协调器更新；不添加无限重试、后台轮询服务或新的持久通知。

### `TouchShieldService.java`

- 保持遮罩只有在启动器完成授权宽限期后才启动。
- 保持 `ACTIVE/ENDING/END_FAILED` 状态和成功确认后清理规则。
- 不在遮罩层中增加授权按钮或坐标点击；遮罩只承担通话已进入保护阶段后的触摸消费。

### 测试脚本和文档

- `work/run_double_tap_reliability.ps1` 接受设备序列号，启动后立即将媒体音量设为 `0`、通话音量设为设备允许的最小值，并采集授权层、服务、窗口、UI、日志和音量。
- 每轮先采集授权层；若系统授权层仍是当前可见根 UI，不发送双击并分类为 `authorization-blocked`。只有保护服务已就绪且授权层不再可见时才发送双击。
- 测试分类必须至少包括：`authorization-blocked`、`shield-ready`、`double-tap-callback`、`end-request-retried`、`shield-removed`、`end-failed`。
- 不以截图或 UI 文案单独判断双击；以 `DoubleTapDetector.onUp/onDoubleTap` 日志、结束结果日志和遮罩状态组合判断。

## 6. 兼容和迁移

- 不改两个 APK 包名、应用名称、豆包包名、签名、规则缓存键、远程规则 URL 或 Intent URI。
- 用户已授予的悬浮窗、通知和通知读取权限继续有效；系统授权确认的有效期由 ROM 控制，应用只提供启动宽限期。
- 更新 APK 后首次启动仍可能出现系统“允许打开豆包”确认，必须由家人完成；应用不能自动点击或永久授权。
- 原有通知“解除遮罩”继续只解除本 APK 保护，不伪造豆包挂断成功。

## 7. 追踪矩阵

| 问题 | 根因级修改 | 回归测试 | 真实验收证据 |
| --- | --- | --- | --- |
| 授权层被遮罩抢先覆盖 | `CallLauncherActivity` 先 dispatch，10 秒后才启动遮罩 | 启动顺序门禁；授权层 UI 采样；无授权时不应有 `APPLICATION_OVERLAY` | 一加授权弹窗可点击，授权后才出现遮罩 |
| 双击回调但结束失败 | `CallEndCoordinator` 本地监听有限重试并重绑 | 监听延迟/断开回归；重复请求只回调一次 | 日志出现回调、重试后监听连接，遮罩成功清理 |
| 语音/视频时序不一致 | 共享 `CallLauncherActivity` 和共享 coordinator | 两包结构门禁；各 5 次设备测试 | 语音、视频各 5/5 双击回调 |
| 测试把阶段混淆 | 修正测试就绪和分类信号 | 脚本自检；授权阻塞与结束失败分开报告 | 每轮保存 UI、服务、日志、音量证据 |
| 无关行为回归 | 不触碰规则、音量算法、通知适配和 URI | `verify_rules`、`verify_refactor`、项目门禁 | 构建、签名、已安装版本和启动入口保持不变 |

## 8. 分阶段实施和门禁

### 阶段 0：计划 Review

- 修改：本计划和独立 Review 文档。
- 禁止：修改 Java、Manifest、规则和构建脚本。
- 门禁：P1 计划问题全部关闭，授权时序、监听重试上限和失败状态明确。

### 阶段 1：测试先行

- 修改：纯 Java 时序/重试策略测试、`verify_double_tap.ps1`、设备测试脚本和必要门禁。
- 先在当前旧代码上运行，确认授权顺序和监听竞态测试因正确原因失败。
- 禁止：为让测试通过先改业务代码。

### 阶段 2：启动时序重构

- 修改：`CallLauncherActivity.java`，必要时新增最小纯 Java 时序抽象。
- 目标：先打开豆包，授权宽限期内无遮罩，宽限期结束后一次性启动遮罩。
- 门禁：Java 编译、静态顺序测试、启动错误路径测试。

### 阶段 3：结束请求重构

- 修改：`CallEndCoordinator.java` 和其测试；不改变通知适配器。
- 目标：监听未连接时有限重绑/重试，连接后单次挂断，超时保留遮罩。
- 门禁：纯 Java/结构测试、项目验证、无重复 bridge 请求。

### 阶段 4：正式构建和一加复测

- 执行：全部项目门禁、`git diff --check`、正式构建、签名校验、覆盖安装。
- 语音和视频各连续 5 次；每次启动后立即静音，先确认授权/入口阶段，再在遮罩出现后发送双击。
- 失败时保存现场，回到根因、计划 Review 和测试门禁，不直接叠加条件分支。

## 9. 测试和验收标准

### 单元/结构

- 授权宽限期：`0ms`、`9_999ms` 不允许激活，`10_000ms` 允许激活；重置后不可误激活。
- 监听重试：监听在第 1、N、截止时间前连接时只执行一次挂断；超过截止时间返回失败；重复结束请求不重复调用。
- 现有双击测试全部通过：单击、滑动、长按、取消、`2999/3000/3001ms` 边界和回调后重置。

### 设备集成

- 未授权状态：系统 `com.oplus.securitypermission` 确认层可见且可点击，遮罩服务尚未出现。
- 授权完成后：对应豆包入口打开，授权宽限期结束后出现半透明遮罩。
- 遮罩阶段：两次触摸均进入 `onDoubleTap`，监听延迟时在有限窗口内重绑，成功后遮罩清理；失败则遮罩保留。
- 语音和视频各连续 5 次，目标为 `5/5` 双击回调；每轮记录失败分类，不把授权阻塞计为双击失败。
- 每轮启动后媒体音量为 `0`，通话音量为设备读回的最小有效值；全轮结束保持静音。

### 构建和完整门禁

- `tests/verify_double_tap.ps1`、`verify_call_end.ps1`、`verify_touch_shield.ps1`、`verify_rules.ps1`、`verify_refactor.ps1`、`verify_project.ps1` 全部通过。
- `build.ps1` 成功生成两个正式 APK；签名 v1/v2/v3 和两个 APK 证书摘要一致。
- `git diff --check` 通过；不创建提交、推送或 PR。

## 10. 风险、回滚和未解决边界

- ROM 授权确认有效期由系统控制，固定 10 秒只能让用户有机会处理，不能绕过或永久延长授权。
- 豆包通知布局是外部实现细节；若布局更新，挂断适配可能失败，遮罩必须保持而不是假报成功。
- 如果一加通知监听永久无法绑定，有限重试后会保留遮罩并记录失败；本轮不引入无限后台服务或 ADB 兜底。
- 回滚优先安装上一份已验证 APK；代码回滚只撤销本轮新增/修改的启动时序、协调器、测试和文档内容，不覆盖工作区其他用户修改。
