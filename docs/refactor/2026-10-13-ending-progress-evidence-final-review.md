# 1.4.1 合并前问题修复：最终Review与验收

2026-10-13。真实项目为doubao-call-launchers，master未提交工作区；以本轮源码基线复核前次Review，未使用子代理、未提交/推送/创建PR。设计和开发前Review分别见同目录`2026-10-13-ending-progress-evidence-plan.md`、`2026-10-13-ending-progress-evidence-plan-review.md`。本文是1.4.1/code9的最终结果；此前1.4.0证据保留。

## 确认根因、范围与最终方案

**E1 / P1：权限失效切断ENDING的持续输入。** 旧Service在权限正常时调用observe，失效时反复调用一次性endRequested；后者在ENDING返回0，导致迟到通话和40秒截止都不再推进。真实旧CallSession编译复现：迟到可控通话expected END=4、actual=0；无通话截止Service旧路径0而直接observe为FINISH=8。问题可导致通话遗漏及FGS持续续租。

最终把权限可用性作为CallSession的第五类持续输入。Service每轮唯一调用五参数observe；ENDING先推进迟到通话/原截止，WAITING和ACTIVE随后处理权限及屏幕失效。没有新增超时、Service状态特例或第二套挂断逻辑。通知结束按钮及窗口异常仍使用显式endRequested事件；HIDE先于END，既有coordinator完成后FINISH，终态不恢复。旧四参数接口已删除。

**E2 / P2：验收生成物和通知采集缺乏边界。** 项目work原有1039个文件可暂存，其中32份通知dump；runner请求--noredact并保存完整通知。增加根目录/work/忽略规则，现有原件只忽略，不删除/重写。runner改为普通通知查询并使用共享转换器，只保存豆包101存在/不存在的固定标识。空dump及没有受支持通知列表头的非空文本拒绝解析，不推断通话已结束。有效记录匹配及既有summary.json字段保持。

**E3 / P2：与E2相关的真实读取失败边界。** 锁屏后ADB两次返回非零、部分或空输出；旧适配器只识别字面offline，且异常包含原dump。修改现有读取函数：仅失败的dumpsys/getprop查询对明确目标重连一次并重读同一查询，第二次失败停止，异常只含命令类别/退出码。Home、锁屏、启动等mutation绝不重发。这是验收工具修复，未修改生产业务状态机或挂断接口。

E1与E2/E3分别在状态归属和证据适配边界消除根因，不强行引入统一大框架。ForegroundObserver、CallEndCoordinator、notification listener、跨包桥/注册与租约协议、规则/缓存/URI、图标、音量与TTS业务均保持本轮基线。两Manifest及版本门禁同步1.4.1/code9。

## 实际代码与职责变化

| 位置 | 变化/职责 |
| --- | --- |
| common/src/.../CallSession.java | observe增加permissionsAvailable，ENDING推进不受权限分流；其余既有状态规则保持 |
| common/src/.../TouchShieldService.java | 删除permissions三元旁路，每tick向现有状态机提交全部输入 |
| tests/java/CallSessionTest.java | 迁移唯一接口，原32特征检查保留并增加30项权限/迟到/截止/恢复边界 |
| tests/foreground_adb.ps1 | 最小通知证据；限定只读失败恢复；原真实入口/窗口/结束流程保持 |
| tests/call_notification_evidence.ps1 | 共享纯转换函数，不操作设备，不保存私人通知字段 |
| tests/verify_session_inputs.ps1 | 验证真实Service持续调用契约及撤罩先于挂断 |
| tests/verify_evidence_boundary.ps1 | 验证实际Git忽略、真实共享转换器、非法格式和32份历史真值 |
| tests/verify_adb_read_recovery.ps1 | AST取得真实读取函数，用外部ADB响应stub验证5场景，无算法复制 |
| tests/verify_project.ps1、两Manifest、README、.gitignore | 总门禁、版本与生成物边界；没有迁移持久规则或公开接口 |

新代码与测试没有旧observe重载、备用检测方案或生产测试入口。与本轮基线比较，业务核心仅上述两Java文件发生预期修改；非允许修改的基线文件和原keystore哈希保持。

## 问题—测试—验收证据矩阵

交付证据ZIP中的文件名如下，均来自本轮实际执行或明确标记的实际工具输出转录。原失败证据不改为通过。

| 项目 | 修复前正确原因失败 | 最终证据/结论 |
| --- | --- | --- |
| E1迟到通话/截止 | red-real-session-repro.txt；red-verify_session_inputs.txt | final-verify-project.txt：62状态检查和Service契约通过；runtime/Voice-runtime.txt、Video-runtime.txt：真实安装APK各23项通过 |
| E1既有正确行为 | 原特征检查在修改前已通过，保留其断言 | 原32+新增30全通过；租约13、观察器15、真实协调器9场景通过 |
| E2暂存边界 | red-verify_evidence_boundary.txt：默认证据仍可暂存 | artifact-summary.json：work可暂存0；32原件哈希未变；源码/tests/docs可正常跟踪 |
| E2通知采集/格式 | red-format-review.txt：非空非法文本被假报无通话 | final-verify-project.txt：41项（9合成/格式+32历史）；device-minimal仅固定101标识，无私人通知正文 |
| E3部分/空失败读取 | red-adb-read-record.txt：旧真实函数停止并暴露stub文本；前两轮锁屏summary为false | 真实适配5场景通过；device-minimal/video-lock-final第三轮整轮通过，前两轮失败summary保留 |
| 错误双击方案清除 | 前次Review已清理，属于必须保持的门禁 | 生产Java/Manifest扫描无DoubleTap、GestureDetector、tapCount等；两最终DEX无DoubleTapDetector/onDoubleTap/AuthorizationGraceGate/END_FAILED/stopShield。测试禁用断言及历史文档的文本引用保留 |
| 签名/版本/安装一致 | 1.4.0原包及原keystore作为基线 | 双包v1/v2/v3签名验证通过、原证书一致；installed APK回读SHA与dist一致；版本1.4.1/code9 |

完整命令已执行：`pwsh -NoLogo -NoProfile -File tests/verify_project.ps1`；项目`build.ps1`双包构建；所有tests脚本PowerShell解析；`git diff --check`；apksigner签名验证；实际已安装APK回读、版本与DEX检查。规则/架构/挂断/遮罩契约门禁均通过。Java8目标及部分旧API产生编译警告，没有编译失败；不存在单独的Gradle/Lint门禁，不将契约检查称作Android Lint。

## 真实设备与Android生成物验证

设备为一加PJD110、Android16，豆包15.3.0；使用明确授权的手机和真实Activity入口，无强停豆包、无密码输入、无生产ADB依赖。

| 最终1.4.1场景 | 就绪→遮罩显示 | 真实验收 |
| --- | --- | --- |
| 语音→Home | 6ms | Surface shown且isOnScreen；退出后实际窗口和豆包101消失；当前session结束SUCCESS |
| 视频→多任务 | 35ms | 同上；当前session结束SUCCESS |
| 视频→锁屏 | 78ms | 同上；修复读取恢复后整轮summary通过；有当前session撤罩日志 |

physical-cycle-facts.json及device-minimal包含本轮session ID、窗口的必要字段、固定通知标识、实际原始窗口文件SHA和结果。锁屏重连中logcat尾部中断，没有完整SUCCESS日志；以成功读取的实际窗口消失和豆包通话通知消失验收，不伪造尾日志。前两轮锁屏因工具读取失败未通过，原件保留且不计入三轮通过。

临时测试APK在Android上加载两款已安装APK内的真实CallSession，每款23项，覆盖持续权限false、迟到CONTROLLABLE、原40秒截止、ACTIVE撤罩、权限恢复不复活和旧签名不存在。该测试向真实状态机传确定性输入，**没有实际撤销系统授权或制造豆包网络迟到通知**；生产代码没有测试开关。外部网络/系统权限组合不能据此声称全部物理端到端覆盖。临时APK已卸载并核验不存在，媒体音量恢复0、通话音量恢复1。

## 生成物与最终审查

| APK | SHA256 |
| --- | --- |
| doubao-voice-call-1.4.1.apk | 129D0FBF0FD724D3E0560A28B0E292873C3518538F9B2C9E4C7131C2239FB474 |
| doubao-video-call-1.4.1.apk | 2A92847AB6E1E675830C460A0C3498159387270A775CF9E07E223F0AE4ABDF63 |

两包原证书SHA256：878990517f92b7ee41dd14285e7cc81c4c9534dd5814c096e683a04dff6a9d40。原签名密钥没有导出到交付文件。

最终Review逐项确认：E1输入归属修复无旁路；E2/E3数据与失败边界一致；新旧状态逻辑没有并存；HIDE→END顺序、JSON字段和既有规则不变；生产及DEX没有错误双击路径；所有本轮确认问题都有红/绿或运行证据；文档与实际源及安装生成物一致。32份历史通知原件及工作区非本任务改动保持，没有清理用户资料。可以提交给合并审阅，本轮未执行Git提交、推送或合并。

## 尚存边界、风险与回滚

无待用户决定的业务规则。继续遵守已接受范围：使用情况访问不能识别浮窗/分屏几何变化；Home/真实退出、多任务、锁屏按前台和屏幕事件处理。Back若停在豆包自己的确认框、包仍在前台，则保持保护。API23真机和仅安装视频APK的跨包回退场景本轮未重测，不扩展为新的兼容保证。通知dump格式变化将使不支持的格式报错，需要更新验收转换器；不应通过忽略错误来判结束。

回滚按本轮基线恢复受影响文件，保留原用户修改、历史证据和keystore；旧1.4.0双包及源码ZIP仍留在本机，仅用户明确要求时降级。恢复旧Service会重新带回E1，不能将回滚视为修复。未改公开接口或数据，无数据迁移。
