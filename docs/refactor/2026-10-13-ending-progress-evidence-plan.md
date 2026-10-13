# ENDING 持续推进与验收证据边界重构

2026-10-13。基于 master 真实未提交工作区、最新合并前 Review 与直接复现。全程无子代理、提交、推送、PR、无关重写或生产端 ADB/无障碍。基线在当前任务 `work/ending-fix-baseline-20261013`，包含源码/测试/文档、已有diff、原keystore哈希及1.4.0两包。

## 已确认问题、根因和范围

| ID | 真实代码证据 | 影响 |
| --- | --- | --- |
| E1 / P1 | TouchShieldService.monitor 按 permissions 选择 observe 或 endRequested；CallSession.endRequested 在 ENDING 立即返回0；迟到通知/40秒截止仅在 observe 中 | WAITING 期间权限失效后永远不推进ENDING；迟到可控通话不挂断，无通话也不清理，持续续租阻挡新入口 |
| E2 / P2 | foreground_adb 默认写项目work，使用 notification --noredact；.gitignore 未排除work；实际1039个可暂存work文件含32份通知转储 | 原始通知和历史测试资料进入批量暂存范围；未来生成不必要的私人通知内容 |
| E3 / P2（实测补充） | 两轮锁屏后dumpsys返回非零且输出为空/不完整；Invoke-Adb仅匹配offline文本才恢复，并把原始输出放进异常 | 验收停在状态读取，可能误判业务失败；异常通道输出完整转储，属于E2采集边界的关联问题 |

E1直接编译未修改的CallSession复现：迟到通话 expected END=4 / actual=0；截止 service path=0 / observe path=8。不是环境或编译失败。关联边界包含ABSENT与UNKNOWN、权限持续失效/恢复、屏幕同时关闭、ACTIVE撤罩、重复结束和终态。E2以实际Git check-ignore返回1、真实历史数据与新runner数据流为依据。

E1与E2不是同一业务根因。E1来自持续状态输入与一次性结束事件在Android适配层分流；E2及关联E3来自生成物的版本控制、数据采集与读取失败边界。分别在各自边界收敛，不引入统一大型框架。原双击检测、授权固定等待及失败留罩已从生产和DEX清除，禁止重新引入。

## 当前与目标架构、方案选择

E1当前：Service在权限正常时tick状态机，失效时重复发送结束事件，导致ENDING不再接收时间/通知。目标：每轮统一调用CallSession.observe，传入前台、通话状态、屏幕、权限和单调时间。状态机在WAITING/ACTIVE把失效条件转换成结束；ENDING始终先处理迟到通话和截止，终态不会恢复。明确通知按钮和显示失败仍是一次性endRequested事件。

选用内部observe参数增加permissionsAvailable，并迁移唯一生产调用方及测试；删除旧签名，不保留兼容重载。没有公开API、规则或持久数据变化。拒绝在Service增加ENDING特例、重复挂断、补计时器或旁路coordinator；这些会再次拆散归属和状态控制。

E2目标：根目录/work/忽略规则，保留现有原件；runner不再请求--noredact，共享小函数把真实通知dump压缩成豆包101存在/不存在的标识，不输出其他通知或文本。既有summary.json字段、Has-Call判定语义和历史raw证据读取保持；before/after通知文件变为最小证据。空dump报错，不能推断已经结束。拒绝仅删本地资料、忽略所有txt或复制脱敏逻辑到测试。

## 职责、数据流和接口

- CallSession：统一五类持续输入；ENDING推进与可用性判断在同一状态机内。endRequested继续用于显式事件，不增加重试逻辑。
- TouchShieldService：每轮采集真实输入并执行效果，HIDE仍先于END；coordinator、listener、桥、租约与释放不改。
- call_notification_evidence.ps1：仅解析当前通知记录头，生成固定通话标识；不调用设备、不写文件。
- foreground_adb.ps1：正常dump →共享筛选→保存最小证据；窗口与通话结束判定不变。
- .gitignore与verify_evidence_boundary：约束生成目录和实际Git暂存语义；保留历史资料，不移动/删除用户文件。

数据流：Android输入 → observe(...screenAvailable, permissionsAvailable, now) →单一状态转移→Service窗口/结束效果。测试证据流：真实dump →最小标识→既有判定/JSON→忽略目录或明确输出目录。

## 兼容、迁移与禁止范围

语音/视频均升级1.4.1 / code9，保持原包名与证书、四种权限、远程规则、音量拉满、TTS和使用情况观察策略。不要改ForegroundObserver、CallEndCoordinator、listener/bridge、租约协议、资源、规则仓库或build.ps1的已有修改。

现有raw通知资料只忽略、不覆盖；最终交付仅选取最小证据。此前Review保留为发现记录，最终结果追加到项目新文档，不能悄悄修改之前失败证据。

## 分阶段实施与门禁

| 阶段 | 位置/目标 | 验证和完成标准 |
| --- | --- | --- |
| S0 | 复核、基线、本文及独立设计Review | 高优先级设计缺口解决；业务代码未改 |
| S1 | 新Service持续输入契约、实际Git忽略回归 | 在旧源因权限旁路和默认证据未忽略而失败；原真实状态复现两项失败保留 |
| S2 | CallSession内部接口及所有状态测试调用 | 原32特征检查保持，新增权限组合行为测试通过；不改Android适配或挂断层 |
| S3 | Service唯一observe调用；共享证据函数/runner/.gitignore | 契约、Git语义、脱敏合成与真实历史dump回放通过；原件哈希不变 |
| S4 | 两包版本、README和完整门禁/构建 | 规则/缓存/协调器/观察器无回归；原签名双包构建；旧检测符号缺失 |
| S5 | 最终APK/实际入口/Android运行检查及追踪矩阵 | 已执行证据逐项回填，未执行边界明确；最终源码、文档与生成物一致 |

阶段发现计划错误时先修订设计及Review，禁止边实现边添加未经审查的新策略。

## 测试计划与验收矩阵

| 问题/行为 | 测试 | 验收标准 | 当前证据 |
| --- | --- | --- | --- |
| E1迟到通话 | 状态机权限持续失效下UNKNOWN/ABSENT→CONTROLLABLE；Service连续输入契约 | END一次，无SHOW，完成后FINISH | 旧真实路径red；62状态检查及Service契约green；两款实际APK Android运行各23项通过 |
| E1无通话截止 | 权限失效且ABSENT/UNKNOWN至40秒 | FINISH，不持续租约 | 旧路径0而observe8的red保留；新状态机及两款APK运行通过 |
| E1既有正确行为 | 原32状态检查；ACTIVE失效、屏幕同时失效、权限恢复不复活 | HIDE先于END；重复事件不重复挂断；终态不恢复 | 原32+新增30通过；租约13、观察器15、真实协调器9通过 |
| E2暂存边界 | git check-ignore真实路径、git ls-files已跟踪work检查 | 默认生成资料被忽略，源码/tests/docs仍可跟踪 | 忽略前red；最终work可暂存数0，32份原通知哈希未变 |
| E2最小采集及非法格式 | 真实共享函数混合通知/文本负例、空/无头dump拒绝、32份历史raw回放 | 仅豆包101标识，原Has-Call结果不变，无私人文本；无效读取不推断结束 | 41项通过（9合成/格式边界+32历史）；无头格式red记录保留；最终真机通知证据为最小标识 |
| E3读取恢复 | AST执行真实Invoke-Adb，5种外部ADB响应场景 | 只对失败只读查询恢复一次，二次失败停止、mutation不重发、异常无原始dump | 旧真实函数red记录；5场景green；修复后视频锁屏整轮通过 |
| 旧双击清理 | 全生产源与两包DEX符号/文件扫描 | 无DoubleTapDetector/onDoubleTap/旧等待/END_FAILED可执行路径 | 最终生产源零匹配、1.4.1两包DEX无旧符号；测试中的禁用断言和历史文档保留 |
| 正常入口 | 最终两APK原签名构建与实际入口退出 | 就绪显示、退出清罩和通知消失 | 语音Home、视频多任务、视频锁屏通过；就绪→显示6/35/78ms；安装包回读SHA与dist一致 |

必要集成证据为真实状态机测试+唯一Service输入契约、真实Git语义和历史数据回放；不复制生产状态算法。Android运行与入口证据必须说明实际验证范围；不为制造网络迟到加入生产测试开关或强停豆包，不把无法稳定操控的外部网络时序当成确定性回归。

清洁checkout无需历史work目录即可运行门禁：Git语义和合成筛选测试必跑，当前本机32份历史通知在存在时回放。增加相似包名和正文中的伪记录负例，不能因最小输出而放宽真实记录头判定。

S5实测修订：E3只修改现有Invoke-Adb读取适配。读取失败时对明确目标disconnect/connect一次，再重读原查询；任何部分输出均不作为成功证据，第二次失败仍报错。启动、Home、锁屏和安装不重发。异常只输出命令类别和退出码，不能泄露原始dump。用PS AST取得真实函数执行，ADB边界使用排队响应stub；验证部分/空响应恢复、二次失败、mutation不重试、重连失败停止。先red后修复，不改业务时序或增加固定等待。

最终Review补充E2解析边界：非空的错误文本也不能代表通知不存在。32份真实历史dump均有`Notification List:`头；共享转换器先验证该服务列表头（兼容`Current Notification List:`），再生成最小标识。没有识别头的输出一律报错，异常不包含原dump。新增非空错误文本与只有记录但没有列表头的负例，先在当前转换器上确认失败；不修改生产通话检测或既有有效dump的匹配语义。

## 风险与回滚

增加内部参数须全量迁移调用方并真实构建。通知dump格式可能变化，测试用历史真值回放及新设备入口验证；解析异常/空响应不能假报无通话。/work/规则仅根目录生成物，不误忽略新源码。没有删除或移动历史数据的回滚负担。

回滚仅恢复本轮基线中受影响文件，保留原用户修改及keystore。旧1.4.0 APK保留，不自动降级。浮窗尺寸识别、返回确认框、API23及单视频安装的既有边界不改变；不因本次修复扩大权限或框架。

## 实施完成与证据索引

S0—S5已按依赖顺序完成；新增E3及格式边界均先更新本文和独立自审、证明red，再改验收工具。最终完整`tests/verify_project.ps1`、双包`build.ps1`、脚本解析、`git diff --check`、签名/安装包哈希和DEX审查通过。证据归档见同目录`2026-10-13-ending-progress-evidence-final-review.md`，交付包包含red记录、最终门禁、设备最小证据和Android实际APK运行输出。

Android运行的权限false/迟到通知输入是向已安装APK真实CallSession注入确定性状态值，未实际撤销系统授权，也未伪造豆包网络通知；生产无测试开关。真机锁屏首两轮因ADB读取失败被保留为失败，第三轮通过；第三轮重连中logcat尾部中断，依据成功读取的实际窗口和通话通知验收，不声称存在完整SUCCESS日志。API23设备、仅视频APK安装场景未重做，不纳入本轮新增通过范围。原用户修改、95文件基线中的非允许修改文件、签名keystore及32份历史通知原件均已核验保持。
