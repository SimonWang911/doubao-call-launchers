# 双启动器独立运行：最终 Review 与验收

日期：2026-10-16。基线提交：3daf735a5fac5e942a2771fbf4beb6c0d83cfbb7。候选版本：1.4.2 / code 10。全程由主代理执行，未使用子代理，未提交、推送或创建 PR。

## 1. 根因与最终实现

旧实现把视频会话的申请、续租、身份检查、释放与结束桥部署在语音 APK 内。包已安装并不能证明其 Provider、监听器或协议可用；动态重新选择 authority 也使另一包安装/卸载改变现有会话的归属。因此启动阻塞、保护丢失、结束受阻来自同一架构根因。

最终每个 APK 包含自己的完整 Activity → 私有 FGS → 本包通知监听 → 有界挂断链。SessionOwnership 只持有本进程 token、接管位和系统句柄；KernelCallLock 用公开 Os.bind 在 loopback 39471 上取得独占资源，不监听、连接、传输数据，也不调用另一 APK。只对实际占用返回 BUSY；系统错误为 UNAVAILABLE。资源持续覆盖准备、通话和结束清理，进程死亡由系统释放。

删除 SessionRegistry、Provider、Lease、CallEndBridgeService、桥消息/重试/解码、旧心跳、两包自定义 END_CALL 权限和另一包 queries，以及对应旧测试桩。没有兼容桥、另一 APK fallback 或旁路锁。两包仍共享源码和现有 SDK CLI 构建。

## 2. 最终代码 Review

主代理在设计 Review 后重新检查最终真实源码、调用链、清单、测试及实际 APK。此次“独立 Review”是独立检查轮次，不是另一位代理或外部审查。

| 问题 | 最终修正 | 复核结果 |
| --- | --- | --- |
| R1 跨 APK 启动依赖 | Activity 只申请本地所有权，本包服务接管；清单无对方组件/权限 | 单装、未配置、停止、禁用、通话中卸载另一包均通过 |
| R2 续租/桥影响保护和结束 | 服务/协调器/listener 只验证自身 token；保持通知 key、取消、attempt 及一次完成语义 | 本地断连恢复/截止、退出结束、旧回调隔离通过 |
| R3 错误合并与误导日志 | Acquisition 三态；只有 BUSY 有界重试；prepared 与 dispatched 分开 | 内核占用/非占用故障、无效申请与实际 dispatch 证据通过 |
| R4 测试证明旧架构、未执行真实所有权 | 真实所有权及 OS 适配测试；实装 APK 的受控集成、实际产物检查；删除桥专用用例 | 原实现真实独立性测试5项失败；新结构及行为验证通过 |
| R5/P1 快速重入销毁新请求 | onPause 取消准备；onStop 才结束未转交入口，保留 onNewIntent token 隔离 | 修复前快速重入零 dispatch；最终矩阵快速重入与交叉入口各仅一次 dispatch |
| R6/P2 close 失败造成永久 BUSY | 释放先撤销身份，保留已结束资源待清理；下次申请先清理，失败明确 UNAVAILABLE | 修复前身份撤销断言失败；修复后故障、恢复及迟到释放隔离通过 |
| R7/P2 规则门禁假绿 | 绝对路径传入 Python；检查 native 退出码 | 仓库外真实规则通过；临时 ruleVersion=0 必须因业务校验以1退出 |

未修改规则/缓存/URI、图标、音量/TTS策略、CallSession 或 ForegroundObserver 的业务实现。Home/离开豆包、多任务稳定800ms退出、锁屏立即撤罩、自然ABSENT确认600ms、UNKNOWN不当成功等既有规则保留。源码及两份最终 DEX 均无 DoubleTapDetector、onDoubleTap、AuthorizationGraceGate、END_FAILED；历史文档和负向回归断言继续保留。

## 3. 问题—代码—测试—验收追踪

| 问题 | 主要代码 | 测试与实际证据 |
| --- | --- | --- |
| R1 | SessionOwnership / KernelCallLock / Activity / 两清单 | Android 27场景中的 video-other-never-configured、两模式 other-stopped/disabled/uninstalled-during-call/alone；video-with-old-voice-version；S0两个不同UID 9操作 |
| R2 | TouchShieldService / CallEndCoordinator / listener | coordinator 9场景；Android双模式 back/recents/app/lock/natural/notification；一加视频Home、语音Home、视频锁屏 |
| R3 | Acquisition / Activity日志 | 所有权39检查；S0 BUSY errno98、UNAVAILABLE errno99；运行日志分别记录prepared和dispatch |
| R4 | verify_independence / verify_independent_apks / Android runner | 旧Registry 9项中5项失败；旧源码结构门禁红；实际 APK 清单/DEX/证书检查通过 |
| R5 | Activity 生命周期 | 修复前完整矩阵前23场景通过、快速重入失败；修复后同一测试及交叉入口通过；追加生命周期复测见证据清单 |
| R6 | SessionOwnership释放状态 | 测试直接编译生产类；先因released identity断言失败，再39检查全部通过，含16线程竞争、候选FD回收、系统错误、旧token隔离 |
| R7 | verify_rules.ps1 | 完整门禁实际输出 Rule verification passed；非法规则负向进程返回码1，原因ruleVersion must be a positive integer |

## 4. 实际执行结果与证据边界

- 完整项目门禁：状态机62检查、真实观察器15检查、真实coordinator 9场景、真实所有权及内核适配39检查、结束策略、规则/缓存/签名/构建契约、证据边界41检查（32份历史dump）、ADB读取恢复5场景通过。最初规则测试相对路径报错却返回成功的部分已作废；最终从仓库外重新执行成功，见 independence-verified-final-project.txt。
- S0 Android API30：两个不同UID真实绑定竞争、正常释放、强停持有进程后恢复、卸载另一包后单独取得资源、非占用错误分类，9操作通过。生产 KernelCallLock 与实测适配源码 hash 相同。
- 最终生产 APK Android API30 集成：27场景全部通过。受控 com.larus.nova 夹具接收生产深链并提供真实通知 PendingIntent；使用实际生产 Activity/FGS/观察器/listener。验证实际 shown Surface/isOnScreen、通知101、发起次数及正常结束。通知结束通过系统UI实际点击；没有用直接调用内部方法代替。
- 生命周期追加复测5项全部通过：另一包未配置时的视频Home、连续两轮快速重入均只dispatch一次；语音/视频准备期按Home均为零dispatch，无后台通话或遮罩。
- 一加 PJD110，真实豆包：视频Home、语音Home、视频锁屏3项全部通过。视频首轮就绪后7ms显示遮罩、锁屏轮就绪后9ms显示；均有实际窗口/通知及撤罩/通知消失证据。首轮因安全锁屏未进入豆包的尝试不计通过；用户解锁后重新完成。
- 一加安装后从手机拉取两份自身 APK，SHA256 与最终构建逐字节一致；未卸载/清数据。测试后确认无通话101、无遮罩；恢复媒体音量160、通话音量9、锁屏时间120000ms。
- 构建：原签名1.4.2/code10两包成功，非debuggable、非testOnly；v1/v2/v3校验通过。keystore前后hash一致；实际清单无旧Provider/桥/对方权限；DEX无旧类、双击路径及测试夹具。git diff --check通过。

证据交付目录为本任务 outputs/independent-launchers-1.4.2-20261016/evidence/，只收录本次相关摘要、过滤后的自身日志和验证报告，不交付手机完整通知或其他应用窗口清单。测试 runner 与 fixture/probe 源码保留在 tests/，夹具 runner 明确拒绝手机及任何已有同名包的模拟器。

## 5. 产物标识

| APK | SHA256 |
| --- | --- |
| AI语音 1.4.2 | 321B14E17C2A92A113A400BC1D8EFB59C1A12B6E2AE26F4B2D0BFE90560F02E1 |
| AI视频 1.4.2 | 1C4039E538CA8C5B8597DF813814AB9AD12A8EC39FE76DD5AA02064952B41F8D |

两包证书SHA256：878990517f92b7ee41dd14285e7cc81c4c9534dd5814c096e683a04dff6a9d40。

## 6. 兼容、风险与回滚

无需新权限，不改缓存格式，不清理旧SharedPreferences，不新增生产测试入口。首次由1.4.1迁移时，空闲状态先升级视频再升级语音；不能替尚未升级的旧1.4.1承诺独立运行或严格混装互斥。新版运行不要求另一包存在、授权、可用或版本匹配。

荣耀当前ADB不可用，未复测该ROM真实豆包；不能把一加和受控Android证据外推为荣耀已通过。浮窗/分屏只变几何但仍上报豆包前台无法独立识别，保留已确认功能范围。真实语音音质/对方听感不由通知及窗口证据证明。

loopback资源被外部进程占用时只报告有界占用，不换端口绕过；持锁活跃进程卡死不安全强抢。稳定协调资源是版本间互斥契约，不得随版本任意更换。公开API低于minSdk23，但实装验证为API30及一加，未声称全Android/ROM覆盖。

回滚基线3daf735；本轮没有提交，无git reset或覆盖用户改动。可在单独目录从该提交构建，并用原密钥及更高versionCode生成回退包，确认空闲后覆盖；不能降级安装或卸载清数据。回退也会恢复已知跨包依赖缺陷。最终源代码、文档与已验证APK的hash记录一并保留。
