# 开发前 Review：前台通话会话重构

2026-10-13。主代理在设计完成后重新核对真实调用方、清单、构建和测试；遵守用户禁止子代理的要求。此 Review 不等于新功能或真实设备验收通过。

| 优先级 | 发现 | 修订方案 |
| --- | --- | --- |
| P1 | UsageEvents 无法判断浮窗几何，已有浮窗中启动会误判全屏 | 一加真实豆包验证后，用户确认不增加无障碍，不独立判断分屏/浮窗；保留 Home/返回后台/多任务/切换/锁屏 |
| P1 | postTime 更新不证明新通话 | 准备阶段要求 listener 连接且明确无已有通话，再 dispatch；就绪要求本次启动后豆包前台和可控通知 |
| P1 | 各 APK sessionId 或只过滤回调不能阻止实际误挂断 | 同签名 provider 唯一所有者，每次本地及桥操作前检查令牌和目标 key；旧请求终止前不 dispatch 新通话 |
| P1 | 双仲裁端或版本不一致会造成并发 | 双安装固定语音端；语音存在但 provider/签名/协议不匹配则失败，禁止改用视频端；仅无语音安装才用视频自身端 |
| P1 | 撤罩后 stopSelf 中断挂断；租约失效可能接管旧通话 | HIDE/FINISH 分离，ENDING 保持 FGS 至有界结果；租约回收后仍检查既有通话，不能直接 dispatch |
| P1 | 控件消失或读取异常不一定是通话结束 | UNKNOWN/ABSENT/CONTROLLABLE 区分；归属 key 仍在但无控件为 UNKNOWN；自然结束稳定确认，查询失败不报告成功 |
| P1 | Activity.onDestroy 仍可停止已移交会话 | 仅取消未移交准备请求；ARM 后服务独立管理，不由后台 Activity 等待就绪 |
| P2 | provider 接口安全面与迁移 | signature 权限加实际 caller/签名校验；heartbeat/release 仅 owner；新协议不允许退回旧无令牌路径 |
| P2 | 删除双击门禁会误删有效挂断测试 | 迁移 EndRequestPolicy 测试，保留规则/缓存/音量/签名特征和真实 coordinator 测试，扩展取消/令牌/目标 key |
| P2 | API23 Math.addExact 与墙钟超时 | 有界期限加法，确认/租约/状态机使用单调时间，事件墙钟仅过滤启动前记录 |
| P2 | runner 强停豆包及服务存在判断伪造成功 | 新 runner 验证实际窗口和通知；不强停豆包/安装器；音量设置必须实际读回 |

依赖：red 回归门禁 → 状态机与登记 → 挂断调用链 → 观察/服务/Activity → 旧路径清理及完整构建 → 双 APK 实机。禁止先删正确通知适配，禁止从已退后台的 Activity 启动新的 FGS。

规则、缓存格式、URIs、图标、包名、签名、音量与短播报不变。登记是解决跨 APK 并发的最小接口，不加数据库、事件总线、第三方库或全局常驻监控。使用情况权限由正常设置授权。

高优先级设计问题已有方案，浮窗独立识别由用户收窄范围解决。进入 S1 测试；red 测试必须因错误行为失败，特征基线通过后才改业务代码。

基线 verify_project.ps1 本轮已通过，含规则、缓存、旧 detector/gate/policy 和真实 coordinator 五场景。它只证明既有契约，不能代替新需求的测试、构建和实机证据。

S4前再次Review：发现新 PREPARE 不能覆盖仍在 ENDING 的同包 Service，因此 acquisition 改由可见 Activity 执行有界等待，取到唯一令牌后才启动 PREPARE；服务验证令牌，所有 abort/end 也按令牌限定。提前退出但通知尚未来到时保留无窗口控制到启动截止，防止迟到调用泄漏。两项已修订设计，基础登记接口不变。S1结构回归按正确错误契约失败；真实 coordinator 新并发场景修复前失败，S2纯状态/租约/policy及S3真实coordinator九场景已通过。允许进入S4。

S4适配审查（实现前修订）：P2 游标单向推进会漏掉延迟 usage event；查询回看2秒、已处理事件去重并拒绝更早状态覆盖。P2 SystemUI 停止需恢复仍在运行的豆包 Activity，内部切换旧 Activity 停止不能替代新前台。P2 撤销准备后空 FGS 收到旧 PREPARE 应 stopSelf。P1 监听权限撤销需与其它权限一起触发撤罩及有界结束，查询 UNKNOWN 保持未知。设计已补齐，使用真实 ForegroundObserver 配合公开事件边界 stub 验证，不在测试中复制生产分类逻辑。

S6实机发现（未预判根因）：视频第二轮未显示保护，真实前台为豆包且通知101存在；当前代码会在 WAITING 截止直接 FINISH，可能留下已 dispatch 的可控通话。P1 修订截止契约：若已可控，截止必须走 END；若不可控，明确报告保护未就绪。仅增加状态变化日志定位 Android 输入，禁止放宽就绪为“通知存在”或加固定延时掩盖失败。返回键实际弹出豆包确认框、包仍前台，属于保留保护的正常边界，不能把该轮记为返回退出通过。

R9 根因复核：123336 的 launch.txt 显示旧任务 brought to front，且无新的 launch mode/dispatch 日志，证明快速连续入口复用了仍在 TTS 收尾的旧页面，不能在 runner 加等待。P1 修订为 singleTop + onNewIntent 显式请求，复用现有 launchDoubaoCall 流程及登记；禁止 onResume 自动重开。旧 finishAfterDelay 与 TTS callback 同样需请求令牌校验，否则旧播报可结束新入口。两包清单同步迁移，真实连续入口是必需门禁；已审查后台 FGS 仍只从可见新请求准备，无新增框架或旁路。

最终自审 P2：ForegroundObserver 把所有事件的时间都写入 lastEventTime，造成非前台事件挡住延迟到达的有效退出事件。归入 R1，禁止另建判断旁路；先增加真实类回归，确认错误原因为退出被保留成 DOUBAO，再在统一事件入口过滤无关类型。对内部停止与重复查询的既有测试继续保留。

完成记录：上述回归在修复前按预期报 DOUBAO，修复后真实观察器15检查通过；最终完整项目门禁、双APK构建、实际通知按钮、自然结束、视频Home和跨包交接通过。临时视频Instrumentation上下文无法解析仲裁authority，不作为通过证据；用真实双入口交接核验生产调用，并保持测试失败记录。最终范围与证据边界见最终Review。
