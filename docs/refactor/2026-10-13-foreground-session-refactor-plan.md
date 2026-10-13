# 前台通话会话重构（2026-10-13）

## 当前范围与实施状态

本方案基于当前未提交工作区，不以 HEAD 代替真实代码。原始源码、测试、文档、已有 diff 和签名文件哈希已保存在本任务 work/refactor-baseline-20261013。禁止 reset、重新克隆、换签名、提交、推送、PR、子代理或生产端 ADB/无障碍/坐标控制。

用户本轮确认保持原权限范围，不增加无障碍服务。Home、返回导致豆包退到后台、多任务、切换应用和锁屏触发退出；分屏及浮窗不再是独立判断条件。进入多任务时豆包卡片可见也触发退出。通知栏、音量面板等瞬时系统窗口不单独触发退出。返回或唤醒不恢复旧会话。

状态：S0–S6 已实施并完成本轮验收；两款 1.4.0 / code 8 APK 已按原签名覆盖安装，一加实机及最终构建通过。完整结果、失败记录和验证边界见 [最终 Review](2026-10-13-foreground-session-final-review.md)，验收证据只记录实际已执行项目。

## 复核问题、根因和影响范围

| ID | 已确认的重构前工作区事实（source-before.zip） | 影响及根因 |
| --- | --- | --- |
| R1 | TouchShieldService.awaitNewCall 仅使用固定授权等待和通知 postTime；无使用情况访问权限、屏幕或前台判断 | 用户提前回桌面仍可能显示全屏遮罩；就绪定义不完整 |
| R2 | TouchShieldService.requestEnd/handleEndResult 在挂断成功后才移除窗口，失败进入 END_FAILED 留罩；ACTION_STOP 仅清理服务 | 用户退出行为与通话结束割裂，遮罩和挂断生命周期混在一起 |
| R3 | CallEndCoordinator 每次重试调用当前 listener.requestHangup；EndRequest 无会话令牌和撤销；handleEndResult 仅判断 ENDING | 延迟请求或回调可作用到后来通话；缺少会话归属和操作前校验 |
| R4 | 两款 APK 各有静态 coordinator、服务和 listener；桥只转发挂断，不仲裁启动 | 单进程会话编号无法隔离另一 APK；缺少跨应用唯一会话所有者 |
| R5 | listener.onNotificationRemoved 仅在 pendingHangup 存在时确认；服务 ACTIVE 不再查询通话 | 自然结束后遗留遮罩；通知存在、未知、消失未形成持续状态输入 |
| R6 | Activity 持有启动完成、待启用和通话会话布尔状态，onDestroy 可 stopShield | 后台 Activity 生命周期与长时通话服务耦合 |
| R7 | tests 的结构断言要求双击和失败留罩；设备 runner 以服务存在判定显示，并 force-stop 豆包及安装器 | 验收可能证明错误契约；强停不能作为挂断成功 |
| R8 | EndRequestPolicy 使用 Math.addExact，确认计时使用 wall clock；minSdk=23 且手工 D8 未配置库反糖 | API 23 兼容和墙钟变更边界需要修正；超时应用单调时钟 |
| R9 | 实机连续第二轮 am start 返回 current task has been brought to the front；原 Activity 还在 TTS 收尾，standard 入口不会收到新的启动回调 | 入口请求与页面实例未分离；属于 R6 的生命周期根因，不能靠 runner 延时或清任务掩盖 |

共同根因：没有一个持久于 Activity、且能跨 APK 仲裁的会话控制层。启动、遮罩、重试各自维护状态，无法表达“该动作属于哪个会话、当前是否允许执行、窗口与挂断分别何时终止”。R7 是测试契约仍绑定旧架构的关联问题。

## 能力验证与排除误报

一加 PJD110 / Android 16：正常多任务菜单产生 com.android.launcher/.Launcher 的 ACTIVITY_RESUMED；豆包正常主页与浮窗均产生 com.larus.nova/com.larus.home.impl.MainActivity 的 RESUMED。浮窗仍是前台，不能据此判断全屏；用户已收窄范围，因此不引入窗口尺寸推测、反射或包名特殊补丁。

证据目录：本任务 work/foreground-probe-20261013-112058（多任务）、112252（豆包浮窗），activity/window/usagestats 为开发端真值；生产端仅公开 UsageStatsManager.queryEvents 和 PowerManager/屏幕广播。旧 bridge 的签名和 sendingUid 校验是正确行为，不是应删缺陷；可见 Activity 预启动 FGS 也应保留。

官方参考：
- https://developer.android.com/develop/ui/views/layout/support-multi-window-mode
- https://developer.android.com/reference/android/app/usage/UsageStatsManager
- https://developer.android.com/about/versions/15/behavior-changes-15#fgs-launch-restrictions

## 当前与目标架构及路线比较

重构前：Activity → PREPARE FGS → startActivity → 固定延时/通知时间 → 遮罩内双击 → coordinator → 本地通知或语音桥 → 成功才撤罩。没有后台/锁屏观察，也没有跨 APK 启动互斥。以下方案 B 已成为当前实现。

方案 A（拒绝）：在旧 Service 中增加若干包名分支，仍沿用旧等待、双击和 END_FAILED；无法消除 R2-R6。

方案 B（选用）：纯 Java 会话状态机 + Android 输入适配 + 同签名跨 APK 会话登记。保留现有 Activity、FGS、通知挂断适配及桥，不新增运行框架、第三方库或常驻全局监控。

方案 C（不采用）：无障碍全局窗口检测；可补充窗口几何但违反用户最终权限范围。

## 模块职责、数据流及接口

- CallLauncherActivity：仍负责规则加载、模式、最大音量和 TTS；在可见期间启动 PREPARE，并在服务准备成功后发起 Doubao。只拥有未移交的准备请求，移交后 onDestroy 不终止会话。权限不足给出明确设置入口。
- CallSession：平台无关状态机，状态 PREPARING/WAITING/ACTIVE/ENDING/FINISHED；提供前台、通话可控/消失/未知、屏幕、结束事件；发出 SHOW/HIDE/END/FINISH 效果。无双击、无授权固定等待。
- ForegroundObserver：仅会话期间查询公开使用情况事件；不把旧事件当新会话，保留一批事件中观察到的稳定离开；内部 Activity 切换与短暂 SystemUI 不作为独立退出。屏幕关闭或锁屏立即退出，Home/多任务/其它应用约 800ms 稳定确认。
- TouchShieldService：持有会话状态和单调时钟，执行窗口/通知及挂断效果，等待当前可控通话和豆包前台，观察就绪后立即建窗（<=3秒）。窗口移除后保留服务完成有界挂断，再清理；START_NOT_STICKY，重启不恢复旧罩。
- SessionRegistryProvider / SessionRegistry：同签名保护的唯一会话登记，双方安装时以语音包为仲裁端；仅视频安装时使用自身端。可见 Activity 先原子取得令牌，再 PREPARE 移交服务；旧会话忙时 Activity 有界等待，不提前启动新 FGS 或发起通话。登记 sessionId/owner/期限；准备、挂断和桥执行前验证令牌；结束前不允许新通话发起。租约失效不能跳过现有可控通话检查。
- DoubaoNotificationListenerService：区分 UNKNOWN / ABSENT / CONTROLLABLE，保留 RemoteViews/通知 action 挂断适配；每次实际点击前验证会话令牌和目标 key，查询失败不视为已结束；持续通话观测不依赖 pendingHangup。准备前已有可控通话拒绝接管。
- CallEndCoordinator：返回可取消请求；本地重试、跨 APK 桥、回调均带 sessionId/notificationKey；结果一次完成；取消使未执行动作失效。保留既有重试上限，禁止无界重试。
- CallEndBridgeService：仅保留签名保护的 bound Messenger 路径及 UID 校验，传递令牌和目标 key；无调用的 started-service 兼容入口删除。

数据流：权限和规则 → 可见 Activity 原子登记会话 → 准备服务并确认 listener 可用、无已有通话 → 发起豆包并移交 → 公开前台/屏幕事件 + 通知快照 → 状态机 → 窗口效果或有界挂断 → 结果确认 → 释放登记。

S3后复核调整（S4前）：登记 acquisition 放在可见 Activity 的准备边界，以免同包服务在旧 ENDING 时收到新 PREPARE 覆盖自身。Service 仅接受当前令牌；Activity 只在移交前负责 abort，旧 abort/通知动作都必须携带令牌，不能停止新会话。已 dispatch 但提前退出、尚无通知时，服务留在无窗口 ENDING 至启动截止，发现迟到通话只挂断不显示，避免新通知晚于取消时出现孤儿通话。

S4适配复核：前台事件查询回看2秒并对已处理事件去重，避免 OEM 延迟上报落在旧游标之前；不倒退到更早的前台事件。最近窗口停止后从仍运行的 Activity 恢复前台判断。被撤销的 PREPARE 到达空服务时立即 stopSelf，避免未进入前台的服务超时。运行中检查通知监听权限；权限撤销撤罩并走有界结束，查询暂时未知不伪装成通话消失。对应测试覆盖迟到事件、重复查询、SystemUI 返回、内部 Activity 切换和权限丢失。

最终 Review 补充 R1 事件排序边界：非 RESUMED/STOPPED 的使用情况事件不能推进前台状态的时间戳，否则晚到的 Home/多任务事件可能被无关事件挡住。先在真实观察器测试中复现，再仅过滤无关事件；事件来源、回看窗口及模块职责不变。

活动通知的 key 不等于豆包提供的唯一通话 ID。保证针对这两个启动器控制的顺序会话：新启动必须在旧控制请求终止且旧通话消失后执行。外部已存在通话不接管。不得以通知 postTime 更新冒充新通话。

## 兼容、迁移和旧路径清理

规则 JSON、远端加载/缓存、两个包名、入口 Activity、图标、音量、短播报、签名和 minSdk 不改变。新增 PACKAGE_USAGE_STATS，通过系统设置由用户授权，不能使用 appops 代替用户授权。版本号在生成交付 APK 时同步递增，两包证书一致。

先建立并验证状态机和仲裁，再迁移服务、Activity、listener/bridge，最后删除 DoubleTapDetector、AuthorizationGraceGate、相关专属测试和旧设备 runner。EndRequestPolicy 的有效测试移入新门禁；规则和已有正确行为门禁保留。旧文档保留为历史证据并标废止，README 与当前测试不再引用旧双击契约。

## 分阶段实施及门禁

| 阶段 | 修改位置与目标 | 完成标准及禁止范围 |
| --- | --- | --- |
| S0 | 真实代码复核、设备能力探针、设计与开发前 Review、重构前源码/签名基线 | 解决高优先级设计问题；不修改业务代码 |
| S1 | tests 增加新会话行为回归门禁；保留规则/缓存/签名/音量特征门禁 | 新契约在旧代码上因固定等待、缺观察和错误留罩失败；非编译错误或环境失败 |
| S2 | CallSession 及会话登记抽象和单元测试 | 时序、提前退出、重复事件、跨所有者互斥、旧令牌拒绝可执行验证；不改规则层 |
| S3 | coordinator/listener/bridge | 取消及动作前验证、目标 key、未知状态、有界重试；立即运行真实类测试 |
| S4 | Service/ForegroundObserver/Activity/manifest | 完成生命周期移交、权限、退出观察、通知统一结束；移除双击路径并运行集成门禁 |
| S5 | README/新设备 runner/旧门禁迁移、APK 构建 | 所有相关单元、结构、静态检查、签名及双 APK 构建通过 |
| S6 | 一加实际入口验收、最终代码 Review、追踪矩阵回填 | 每款至少5次完整启动退出；所有证据是实际显示/移除/通知结束，失败继续修复 |

阶段发现设计变更：先更新本文件和开发前 Review，再修改代码。禁止附带改写 RuleRepository、规则格式、资源设计或引入新应用框架。

## 追踪矩阵与验收标准

| 问题 | 代码责任 | 回归/特征测试与运行验收 | 当前证据 |
| --- | --- | --- | --- |
| R1 | CallSession/ForegroundObserver/Service | ready≤3s；无可控通话不显示；提前退出撤销；Home/多任务/切换/锁屏 | 状态机32、观察器15检查；双入口各5轮及浮窗锁屏；排序回归先红后绿；返回确认框边界另列 |
| R2 | 状态机/Service | HIDE 再 END；失败不留罩/不恢复；通知统一结束；有界收尾 | 状态机失败结束/重复结束测试；实机先撤罩后 SUCCESS；final-notification-launcher-button-voice 实际按钮通过 |
| R3 | coordinator/listener | 取消后不执行动作；旧回调无效；动作前令牌/目标校验；一次结果 | 实际 coordinator 9场景；并发回归旧实现失败、新实现通过；操作前验证和监听契约通过 |
| R4 | registry/provider/两包 | 同时 claim 一个成功；旧令牌不能释放；唯一仲裁端 | 租约13检查；真实语音 UID provider 7断言；非法 shell UID被拒；final-cross-entry 旧通话 SUCCESS 后视频才 dispatch；单视频路径源码审查，未作真机单安装验收 |
| R5 | listener/状态机 | 自然结束后清罩；未知不视为结束；短暂消失不立即结束 | 短暂消失/未知重置测试；final-notification-natural-final-video 实机 DOUBAO/ABSENT 后620ms清罩，无启动器 END 结果 |
| R6 | Activity/Service | 生命周期移交；准备失败只清本令牌；重复入口隔离 | Activity/服务契约及核心测试；长通话跨越旧 Activity TTS 收尾；快速重复启动和跨入口实测通过 |
| R7 | tests/runner/README | 删除旧契约；显示 surface 真值 + 通话消失；无强停；音量实际读回 | verify_project 全部通过；已装 APK 与构建 SHA256一致；最终媒体0/通话1；临时探针已卸载 |
| R8 | policy/listener/会话计时 | 单调超时、取消、截止边界、API23兼容 | policy及截止测试、状态机截止红绿回归、minSdk23双包构建/签名通过；未声称API23真机验收 |
| R9 | Activity/onNewIntent/两包manifest | 新请求令牌；旧回调不能关闭新请求；无 onResume 重开 | 123336真实失败及结构红绿回归；singleTop同步、旧回调令牌门禁；后续各5轮快速重复入口通过 |

单元场景还包括：旋转/内部 Activity 转换、短 SystemUI、失去权限/监听断开、锁屏/自动熄屏、已结束时用户结束、唤醒/返回不恢复、就绪回调到达旧会话、失败后新启动被已有通话阻止。

实机修订：WAITING 截止发现已有可控通话时执行有界挂断，不能直接 FINISH 留下已启动通话；未知或启动失败截止给出保护未就绪提示。返回键若只弹出豆包确认框而没有离开包，保持保护；主页或锁屏仍是确定退出方式。视频第二轮未就绪的证据在 work/foreground-device/20261013-122631-video-recents，定位期间不计为通过。

R9 复核证据：work/foreground-device/20261013-123336-video-recents/launch.txt 明确旧任务带回前台，live-logcat 没有新入口执行记录；该轮不是新通话就绪失败。两款入口使用 singleTop，onNewIntent 是新的显式启动请求：取消本 Activity 未移交的旧准备、清理旧播报收尾、创建新令牌并复用唯一启动流程。不能在 onResume 重开通话，也不搬迁 TTS 或改播报内容。已有活跃服务继续按自己的旧令牌结束，新请求仍经唯一登记等待。回归为连续快速再次启动、旧收尾回调不能 finish 新请求、同包与跨包接力。

真实验证：明确设备序列号与包版本；不触碰 emulator-5580。开发端 ADB 只用于安装、公开设置操作、输入和读取窗口/通知/logcat真值，不进入生产代码。测试后媒体/通话音量分别回到0/1并读回；若系统未实际应用，记录未通过并修正，不能依据命令退出码声称静音。权限通过正常设置开启。设备验收不得以进程强停、服务记录或固定等待冒充结果。

## 风险、回滚及发布边界

OEM 可能延迟 UsageEvents 或抑制 listener/FGS；以真实事件与连接状态验收。权限失效时撤罩并报告挂断结果，未知状态不能报告成功。系统杀进程时无法保证继续执行挂断，但不得自动恢复遮罩；持久登记租约与旧通话检查防止错误接管。

浮窗/分屏几何检测明确不承诺；它们经多任务/切换入口造成已确认后台事件时仍按后台规则结束。旧通知 key 重用不提供通话唯一身份，跨启动器顺序交接和已有通话拒绝接管是必要边界。

回滚仅恢复本轮修改到保存的当前工作区基线，保留所有用户原有修改和原签名；不恢复 git HEAD、不覆盖其他任务文件、不清理全部 work。旧 APK 留档供安装回退，版本降级只在用户明确需要时执行。此任务不提交、不推送、不发布。
