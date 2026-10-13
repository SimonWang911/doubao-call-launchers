# 前台通话会话重构：最终 Review 与验收

> 后续合并前复审发现权限持续失效与证据暂存边界缺口；本文件保留1.4.0的历史验收。1.4.1补修设计与结果见 [ENDING及证据边界](2026-10-13-ending-progress-evidence-plan.md)，不能将本文件作为这两项问题已覆盖的证明。

2026-10-13。对象为真实未提交工作区 `C:/Users/xuan1/Desktop/Github/doubao-call-launchers`。全程由主代理执行，没有子代理、提交、推送、PR 或发布。项目原有改动先保存到当前任务的 `work/refactor-baseline-20261013`，随后逐项核对源码、调用链、测试和一加真机。

## 根因与最终实现

根因是启动请求、Activity 生命周期、遮罩和挂断重试分别维护状态，缺少跨 APK 的唯一会话所有者。固定授权等待和通知时间无法证明通话就绪，失败留罩及旧回调又缺少归属边界；重复入口还可能仅把旧任务带回前台。

当前采用 `CallSession` 纯状态机、`ForegroundObserver` 公开事件适配，以及同签名 `SessionRegistryProvider` 登记。保持项目共享 Java + 双 APK + SDK CLI 构建结构，没有新增运行框架、数据库、事件总线或第三方库。

启动顺序为：规则与权限检查 → 可见 Activity 取得唯一令牌 → 准备 FGS、确认监听连接且没有旧通话 → 发起豆包并移交 → 确认豆包前台和可控通知 → 显示遮罩。Home、Recents、切换应用及返回后离开豆包，经约800ms确认结束；锁屏或熄屏立即撤罩。撤罩与挂断收尾分开，失败不能留罩，旧回调不能恢复遮罩或结束新会话。自然结束经600ms消失确认清理。

## 主要代码及职责

| 模块 | 当前职责与接口变化 |
| --- | --- |
| CallSession.java | PREPARING / WAITING / ACTIVE / ENDING / FINISHED；统一输入和 SHOW/HIDE/END/FINISH 效果，含早退、迟到通话、就绪超时和终态 |
| ForegroundObserver.java | 仅会话期间使用公开 UsageEvents；启动前事件过滤、2秒回看、去重、内部 Activity/SystemUI 处理；无关事件不推进前台时间戳 |
| SessionLease / SessionRegistry / SessionRegistryProvider | 原子登记、6秒租约、所有者心跳/释放、signature 与实际 UID 检查；双安装固定语音端，协议失败不能旁路 |
| TouchShieldService.java | 接受令牌限定的 PREPARE/ARM/ABORT/END；维护屏幕、权限和通知状态；执行先撤罩后有界结束，不自动恢复旧会话 |
| CallEndCoordinator / DoubaoNotificationListenerService / CallEndBridgeService | 可取消且一次完成的请求；每次动作验证令牌与通知 key；保留通知 action 和 RemoteViews 挂断；桥仅保留签名保护的 bound Messenger 路径 |
| CallLauncherActivity.java 与双 Manifest | 仅管理未移交准备请求；singleTop / onNewIntent 接收新显式请求，规则/TTS/完成回调按请求令牌隔离；新增使用情况访问权限 |
| tests 与 README | 保留规则、缓存、音量、资源和签名特征门禁；增加状态机、真实适配器/协调器测试与真机窗口验收；废止旧契约 |

已删除 `DoubleTapDetector.java`、`AuthorizationGraceGate.java`、其专属测试、`verify_double_tap.ps1` 和旧双击可靠性 runner。生产源码不再含双击、固定授权等待、END_FAILED 留罩或无令牌停止路径。8份旧遮罩/双击相关 MD 保留历史证据并标记被新方案取代。

规则类、规则 JSON、URIs、图标/资源、包名、音量拉满及短播报内容保持；`build.ps1` 的本轮前已有改动和原 keystore 哈希保持不变。新生成文件与既有工作区差异已单独核对，没有 reset 或覆盖无关改动。

## 问题、测试和证据矩阵

| ID | 修复证据 | 实际验收证据 |
| --- | --- | --- |
| R1 就绪与后台退出 | 旧源结构回归因旧等待、缺观察/权限失败；状态机32检查、真实观察器15检查 | 语音/视频各5轮，Home/Recents/锁屏/切换应用；浮窗锁屏；就绪到显示14场景最大30ms，交接场景37ms |
| R2 挂断失败留罩/仅停止保护 | 状态机先HIDE后END、失败终态、不恢复、重复结束；统一通知契约 | 完整日志先撤罩再SUCCESS；实际通知按钮 `launcher-button-voice` 通过；一轮语音锁屏日志中断仅以结束状态真值验收 |
| R3 旧请求与旧回调 | 真实 coordinator 并发回归在修复前失败；修复后9场景通过，含取消、归属变化、旧回调 | 本地/桥操作前令牌和目标校验；实际每个会话 UUID 不重复，旧通话结束后才发起下一次 |
| R4 双 APK 并发 | 租约13检查、语音实际 UID provider 7断言、非法 shell UID 权限拒绝 | `final-cross-entry`：语音SUCCESS 13:15:39.899，视频dispatch 13:15:39.940；两个遮罩未并存，末次正常清理 |
| R5 自然结束遗留 | 状态机自然结束稳定确认、未知重置及短暂消失测试；listener 持续快照 | `natural-final-video`：13:16:17.417 DOUBAO/ABSENT，13:16:18.037撤罩；没有启动器END结果，通知101消失 |
| R6 Activity 与服务耦合 | 移交后 Activity 不停止服务；未移交取消只作用本令牌；规则/TTS 回调隔离门禁 | 长通话跨过旧 Activity 的TTS收尾；真实重复入口及跨入口接力通过 |
| R7 错误测试契约 | 删除双击/失败留罩断言和强停 runner；实际窗口 surface/isOnScreen + 通知101检查 | 独立重新解析14场景原始证据全部通过；最终媒体0/通话1；临时测试 APK 已卸载且确认不存在 |
| R8 时钟/API23边界 | policy、单调超时和 coordinator 截止测试；移除 Math.addExact | minSdk23 双包真实编译、D8、原签名验证通过；API23没有真机测试，不外推为全ROM验收 |
| R9 快速重复入口 | 123336旧任务 brought-to-front 实机失败；singleTop/onNewIntent契约先红后绿；旧回调令牌检查 | 修复后各5轮快速入口通过；最终候选通知结束、自然结束、视频Home及真实跨包接力通过 |

最终 Review 额外发现的 R1 排序回归：无关事件时间9800挡住迟到的Launcher时间9700，真实类测试报 `unrelated event cannot hide delayed foreground exit: DOUBAO`。先记录、修订计划，再过滤无关类型；真实观察器15检查通过。WAITING超时回归也先报 `startup timeout cannot leave an owned controllable call: 8`，再修订为已有可控通话必须走有界END。

所有R1–R9都已在当前调用链中核对；没有以固定延时、强停进程或放宽就绪条件掩盖错误。基础状态、仲裁、挂断调用链、Android适配、旧路径清理和真机验收按依赖顺序实施，每阶段执行对应门禁。

## 实际执行结果与可信边界

- `verify_project.ps1`：规则/缓存/项目契约、状态机32、租约13、policy、真实观察器15、真实coordinator9场景、挂断/遮罩契约全部通过。Android边界使用小型stub，未在测试中复制核心状态机或协调器。
- 双APK：SDK CLI / javac / D8 / zipalign / apksigner构建成功；v1/v2/v3签名验证通过；PowerShell解析与 `git diff --check` 通过。Java8/弃用警告保留，不计为错误。
- 一加PJD110 / Android16 / 豆包15.3.0：两入口各5轮主循环通过；另有浮窗锁屏、最终候选视频Home、实际通知按钮、自然结束和跨入口交接。触摸遮罩实际有surface且在屏幕上；结束后真实Window记录与当前通话通知均消失。
- 最后排序补修发生在10轮主循环与浮窗锁屏之后；补修后补跑通知按钮、自然结束、视频Home和跨入口交接。没有把补修前10轮重复宣称为最终二进制完整复测。
- 124209语音锁屏时无线logcat中断，结束后的窗口/通知真值完整；浮窗锁屏有完整撤罩/挂断日志。离线、安全锁定、错误通知点击均保留记录，不计为目标场景通过。
- 单独视频Instrumentation探针返回 `Unknown authority`，没有计为接口通过，也没有因此修改生产权限。语音provider探针通过；视频使用真实正常入口及跨包交接验证。该探针上下文差异未查明，不能把它的失败解释为生产调用成功或失败。
- 音量复位时ROM忽略后台/Shell媒体控制，最终使用正常音量键和实际应用UID公共AudioManager；独立GET读回媒体0、通话1。临时探针安装、运行和卸载均仅用于开发端，未进入生产源码或交付APK。

原始证据在项目 `work/foreground-device` 及本任务 `work`；交付的 `device-evidence.md` / `evidence.zip` 保存相关窗口、通话标识和会话日志摘录，避免带入无关私人通知。

## 最终生成物与安装读回

两包均 1.4.0 / code 8，证书 SHA256：`878990517f92b7ee41dd14285e7cc81c4c9534dd5814c096e683a04dff6a9d40`。从手机读取已安装base.apk，SHA256与最终dist逐字节一致：

| APK | SHA256 |
| --- | --- |
| voice | 6C78A7F721BD9638FDA0BD600F1773C1C39FB6DBB26C97129E80F50DCEDE93F0 |
| video | 5C9B0E1EBA9C5BCE5BE017CFD6EAE50B81BDE302F3C5C0764E8F50B35B4CBE63 |

## 保留的业务边界、风险与回滚

浮窗/分屏自身几何不作为判断条件，这是用户已确认的范围；浮窗锁屏已实测通过。返回键若只弹豆包确认框而未离开包，遮罩保持；Home、锁屏和保护通知可结束。Recents仍按退出处理。没有待用户决定的新业务规则。

仅视频安装路径及API23做了源码/构建审查，没有卸载已配置语音包或另找旧设备进行真实验收。系统杀进程后不能保证继续挂断；服务不会自动恢复旧遮罩，登记过期后仍要求确认没有旧通话。OEM或豆包后续版本变动需要重做设备门禁。

回滚以本轮保存的真实未提交工作区基线为依据，恢复仅本轮修改，保留原有改动与原签名；旧APK保留，不自动执行降级安装。当前任务没有发布、提交或推送。

最终代码、文档、生成物和已执行证据一致；本轮确认问题的重构及验收完成，未将上述能力范围、测试工具差异和平台限制包装为已验证保证。
