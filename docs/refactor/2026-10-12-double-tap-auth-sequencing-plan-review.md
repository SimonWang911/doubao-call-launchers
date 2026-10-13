# 双击结束可靠性与授权时序计划 Review

> 历史记录：当前设计以 [2026-10-13 前台会话重构](2026-10-13-foreground-session-refactor-plan.md) 为准，以下为已废止双击方案的 Review 证据。

## 2026-10-13 独立复核追加（替代原固定等待可直接挂窗结论）

重新从 Android 16 target 36、拒绝授权、未操作超过 10 秒、Activity 销毁和 bridge 全时限审查：原计划仍有两个 P1：后台启动 FGS 无合法豁免；固定延迟后仍可覆盖授权层。接手修订用前台无窗口准备 + 已授权 listener 的新通话通知作为激活证据，同时保留 10 秒宽限期，不依赖 OEM UI 检测。没有新通知时 fail closed，不声称有活动通话或挂断成功。

职责 Review：Activity 仅负责可见时准备、dispatch 和失败播报；服务负责准备超时/宽限/激活/资源释放；listener 只新增读取既有通知适配结果的只读接口；coordinator 负责重连和一次性结果。规则、音量、入口 URI、双击算法、签名保护不改。

时限 Review：8 秒重试在最后一刻可能进入 3 秒挂断确认，因此 bridge 只等 9 秒仍不足；明确 12 秒。给每次本地尝试独立编号，防止上一回调重复导致当前尝试提前完成。确定性测试必须执行真实 coordinator，而不是只检测常量字符串。

bridge IPC Review：原 `startService` 会在语音 companion 后台时重现已记录的 `BackgroundServiceStartNotAllowedException`；仅调整重试时机没有修复它。签名保护的同一服务改用 bound Messenger，无需后台启动豁免；服务核验消息 UID 属于 AI视频且签名匹配。客户端完成后解绑并拒绝迟到连接；一条连接只发送一条请求。

启动 Review：准备阶段先清理旧窗口和旧 timer；准备就绪后才打开豆包；dispatch 错误停止服务；未 arm 3 秒截止、arm 后 40 秒截止；stop/destroy 取消任务并移除通知。新通知按 `getPostTime >= 本次 dispatch 前记录的时间` 过滤，避免上一通话遗留通知自动盖住新授权弹窗。

复核结论：接手修订通过，先补预期失败回归，再依赖顺序实施。额度不足时不能据 `ALREADY_ENDED` 报告真实挂断，须分别保留触摸识别与真实通话验收边界。

## 1. Review 范围和结论

Review 对象：

`docs/refactor/2026-10-12-double-tap-auth-sequencing-refactor-plan.md`

以及当前真实代码、测试脚本和一加现场证据目录：

`work/double-tap-reliability/20261012-123815/`

结论：初稿路线正确，但有 4 个需要在开发前关闭的问题。修订后采用同一路线，不扩大到规则、音量或通知适配器重写。

## 2. 发现的问题

### P1：固定宽限期不能证明系统授权已经完成

初稿中“宽限期结束后不再允许授权对话框阻塞主流程”的表述过强。普通 APK 没有跨 ROM、无需无障碍或 Usage Stats 授权的通用接口确认 `com.oplus.securitypermission`、荣耀安全确认层等 OEM 对话框已经消失。

修订：

- 10 秒只定义为“应用不消费触摸的授权宽限期”，不宣称系统授权完成。
- 宽限期内不创建遮罩；宽限期结束仍无法确认授权时，应用可以按既定流程启动保护，但设备验收必须把授权层仍存在分类为 `authorization-blocked`，不能分类为双击失败。
- 文档明确说明：应用不能自动点击、绕过或永久授权；需要长期消除系统确认只能由家人完成 ROM 的授权设置。
- 生产逻辑不引入 OEM 包名/窗口文字判断，避免一加路径污染荣耀和小米。

### P1：结束重试的单次性和回调生命周期还不够具体

初稿写了“每 200ms 重绑、最多 8 秒”，但没有规定监听连接后如何停止轮询、超时后如何阻止迟到回调，也没有明确重复双击和 `END_FAILED` 重试的边界。

修订：

- 每次 `requestEnd` 创建唯一的请求对象，包含截止时间、完成标志和一次性 callback。
- 轮询发现本地监听器后只调用一次 `requestHangup`，立即停止轮询；通知服务的最终结果只能回调一次。
- 截止时间到达后标记请求完成，后续监听连接不得再触发该请求；结果为 `UNAVAILABLE` 或 `TIMEOUT`，遮罩保持。
- `TouchShieldService` 仍用 `ENDING` 屏蔽重复双击；进入 `END_FAILED` 后只重置 detector，下一次双击创建新请求。
- bridge 只在本地监听重试窗口结束后作为已有 fallback 尝试，不能与本地请求并发，也不能覆盖本地结果。

### P1：没有稳定的“双击已回调”证据

当前证据依赖异常调用栈。日志中虽然能看到 `DoubleTapDetector.onUp`，但成功路径没有固定日志，设备脚本无法稳定区分“已回调但挂断失败”和“没有收到第二次点击”。

修订：

- 在 `TouchShieldService` 的 listener 回调入口增加一条固定、无隐私数据的 `double tap detected; requesting call end` 日志。
- 设备脚本只把该日志作为 `double-tap-callback` 证据之一，并同时读取结束结果和服务状态；不以日志单项代替真实结果。
- 不记录坐标、通知正文、规则内容或通话内容。

### P2：设备测试脚本的授权阶段与音量门禁仍需明确

初稿要求采集 UI，但没有说明每轮如何保证系统弹窗不被测试点击误触，也没有说明一加通话音量最小值不是 `0`。

修订：

- 每轮启动后先静音，再等待并采集一次 UI；若 UI 根包是系统授权包，记录 `authorization-blocked`，不发送双击。
- 只有确认遮罩服务已就绪且授权层不再是当前可见根 UI 时，才发送两次点击。
- 媒体音量设 `0`；通话音量读取设备允许的最小值并设到该值，一加的实测最小有效 index 为 `1`。
- 测试结束后再次写入并读回两个静音值。

## 3. 依赖和调用方复核

### 已覆盖的调用链

```text
CallLauncherActivity.launchDoubaoCall
  -> RuleRepository.loadRule
  -> openDoubaoCall
  -> startDoubaoThenArmShield
  -> startActivity(Doubao)
  -> authorization grace
  -> TouchShieldService ACTION_START
  -> ShieldView.onTouchEvent
  -> DoubleTapDetector.onUp
  -> CallEndCoordinator.requestEnd
  -> DoubaoNotificationListenerService.requestHangup
```

视频本地监听未连接时仍会经过 `CallEndBridgeService`，但该 fallback 必须在本地重试之后串行执行。语音和视频共享 `common` 源码，两个 Manifest 的服务声明和权限是唯一模式相关差异。

### 未发现需要扩大范围的调用方

- `RuleRepository`、`RuleCache`、`DoubaoRuleParser` 和 `RuleUrlCandidates` 不参与遮罩/结束时序。
- `DoubaoNotificationListenerService` 的固定包名和 `hangup_btn` 适配已经有独立 Review，本轮只消费其连接状态和结果接口。
- `CallEndBridgeService` 的签名权限和调用方校验不改；本轮只调整调用时机和串行约束。
- `build.ps1`、Manifest 版本、签名和 APK 发布路径不需要改变，除非编译验证发现必要的版本递增要求。

## 4. 计划修改后的开发门禁

### 阶段 1：测试先行

- 新增一个最小、无 Android 依赖的授权宽限期状态模型测试，覆盖未 dispatch、宽限期内、边界时刻、重置。
- 扩展结束协调器的可测试策略，覆盖监听延迟、截止时间、重复 callback 和迟到连接。
- 更新 `verify_double_tap.ps1` 和设备脚本，增加启动顺序契约、固定双击日志和授权根 UI 分类。
- 在旧业务代码下运行，测试必须因缺少这些契约而失败，失败原因要指向授权顺序/重试边界，而不是编译环境。

### 阶段 2：启动时序

- `CallLauncherActivity` 先分发豆包 Intent，再由单个 Handler 任务等待 `10_000ms` 启动遮罩。
- 成功播报、Activity 销毁、启动失败和超时都必须结束或取消同一任务；禁止同时保留“旧的先遮罩后启动”分支。
- 增加固定双击检测日志，不把授权判断做成 OEM 特判。

### 阶段 3：结束重试

- `CallEndCoordinator` 使用唯一请求对象串行管理本地监听轮询、一次挂断和 bridge fallback。
- 连接成功后立即停止轮询；超时后拒绝迟到结果。
- `TouchShieldService` 只按 coordinator 结果转移 `ENDING/END_FAILED`，不在 UI 层重复实现重试。

### 阶段 4：验证

- 先跑全部本地门禁和正式构建，再覆盖安装到一加。
- 语音和视频分别连续 5 次；每轮包含启动、静音读回、授权阶段采样、遮罩就绪、双击、日志/服务结果采集。
- 任何一轮出现 `authorization-blocked` 时，单独记录授权失败，不将其与双击可靠性合并；任何出现 `double-tap-callback` 但 `END_FAILED` 的轮次都回到结束重试根因，不直接增加点击次数或特殊分支。

## 5. Review 结论

修订后的计划满足以下条件后方可改业务代码：

- 已确认系统授权不能被 APK 绕过，遮罩必须后置且授权阶段不拦截触摸。
- 已确认双击回调与结束请求失败是两个不同问题，测试和状态机分别证明。
- 已限定监听重试为单次、有限、可取消的协调器职责，没有无限后台任务。
- 已覆盖语音和视频两个调用方，未保留旧启动顺序旁路。
- 已定义本地门禁、设备集成和构建验收证据。

计划 Review 通过，先实施测试门禁，再按依赖顺序修改业务代码。
