# 双启动器独立运行重构开发文档

日期：2026-10-16。基线：3daf735a5fac5e942a2771fbf4beb6c0d83cfbb7，1.4.1/code9。禁止子代理、提交、推送及 PR。工作区基线干净，原签名与非本任务文件保持。

## 1. 已确认问题及证据

| ID | 问题与代码证据 | 影响 / 验证边界 |
| --- | --- | --- |
| R1/P1 | SessionRegistry.java:21-29 根据语音包安装状态选择 authority；每次 acquire/renew/check/release 重新选择 | 视频依赖语音的组件、签名、协议和安装状态。荣耀既有日志出现 Unknown authority com.simon.doubao.voicecall.sessions，堆栈停在 Activity.awaitSessionLease，未 dispatch |
| R2/P1 | TouchShieldService.java:63 续租失败直接 finishSession；cleanup 取消结束请求；Coordinator.java:112/187/220 与 listener 每次实际动作调用相同远端登记 | 另一包异常可中断本包保护和挂断。Coordinator:191-275 还有语音桥，Manifest 引用语音 END_CALL 权限，不能只改启动端 |
| R3/P2 | Registry 所有异常/拒绝均 false；Activity:143-157 统一 200ms 重试至 25 秒 | 不可恢复错误被误报占用；starting Doubao intent 仅为构造日志，实际 startActivity 在197行 |
| R4/P2 | coordinator 测试替换 Registry；verify_touch_shield/verify_call_end 强制旧 Provider、心跳和桥 | 现有 62 状态检查、13 租约检查、9 coordinator 场景通过，真实 Registry 的9项独立性边界检查5项失败。模拟不是设备证据 |

根因：为跨入口原子互斥，将会话所有权部署在另一个 APK 的生命周期之内。组件可见、已安装和服务可用被错误等同。停止状态是此前故障的伴随现场，不能外推所有 ROM 的停止行为。新手机日志不可获得，最后一次用户故障不宣称已再次复现。

双击复核：生产 Java / Manifest / 构建入口无 DoubleTapDetector、onDoubleTap、AuthorizationGraceGate、END_FAILED。正式1.4.1两包DEX相同符号检查均无匹配。历史文字和禁止回归的测试保留。

## 2. 当前与目标架构、路线选择

当前：Activity → 跨包登记/租约 → 自身服务 → 豆包 → 远端续租/校验 → 本地结束或语音桥。

目标：各 APK 的 Activity → 本进程 SessionOwnership → 本包服务/监听 → 豆包 → 本包有界结束 → 释放系统独占资源。共享 Java 和 SDK CLI 构建不变，不导出会话组件，不调用另一 APK。

路线A：进程内所有权 + 系统独占资源。路线B：本包私有 Provider + 相同系统独占资源。两者都不需另一 APK；当前没有 android:process 多进程需求，B仍增加不必要调用和协议，因此选A。拒绝双端fallback、去掉互斥、保留兼容桥、固定授权延时。

开发前修订：LocalSocket.bind 将原生失败封装为 IOException，不能靠错误文字可靠区别占用与系统错误。改为公开 android.system.Os.socket/bind/close（API21，项目min23），仅绑定 IPv4 loopback 的固定协调端口39471，不 listen/connect/accept、不收发数据，不创建常驻服务。EADDRINUSE为BUSY；其它Errno/权限/IO为UNAVAILABLE。已有 INTERNET 权限保持，无新增授权。该端口是稳定的系统资源标识，不含APK版本，不是网络业务协议。地址冲突准确报告占用，禁止换端口旁路互斥。

公开依据：https://developer.android.com/reference/android/system/Os 、https://developer.android.com/reference/android/system/ErrnoException 。开发前必须由两个不同UID测试包验证互斥、释放和停止进程后恢复。模拟器或手机结果分别标注，不把JVM桩当作内核证明。

系统资源本身不以对方安装/权限/版本为前提。对方正在持有真实通话时的互斥属于正确行为。进程存活但卡死时不能安全强抢独占资源；任何占用等待有界，不承诺任意系统故障下立即接管。当前产品为同一手机的通话独占，不增加多用户并行通话功能。

## 3. 职责、接口、状态与数据流

- KernelCallLock：单一系统适配，申请/关闭FD；失败的候选FD立即关闭，无SO_REUSEADDR，无监听和数据通信。
- SessionOwnership：本进程唯一token、资源句柄及是否已被服务接管。acquire(token)返回ACQUIRED/BUSY/UNAVAILABLE；adopt(token)、isCurrent(token)、releaseUnclaimed(token)、release(token)。没有Context、包查询、IPC、协议号、持久租约、心跳。
- Activity：保留规则/权限/音量/TTS；仅BUSY保留已有25秒有界等待，UNAVAILABLE立即明确失败；准备回调和旧TTS仍按token隔离。日志分为intent prepared与startActivity dispatched。
- 服务：PREPARE接管本地token；保留CallSession/ForegroundObserver；无远端renew；HIDE→END→有界结果→取消回调→释放资源。只有本次token能释放。
- 协调器：只重绑自身listener、重试和确认；保留串行、取消、一次完成、attempt编号；删除桥和ResultReceiver跨包路径。
- listener：保留通知key和本地当前token检查，以及action/RemoteViews适配、UNKNOWN/ABSENT区别。

接管边界：Activity取消先发本token ABORT；releaseUnclaimed只释放尚未接管的申请。服务已接管时必须由服务清理释放，不能Activity先释放后旧服务误清理新会话。未接管的迟到PREPARE必须拒绝。锁从准备覆盖至结束/失败清理，不能只保护dispatch瞬间。

S4实证修订：Activity的onPause只取消未转交准备，onStop才finish未转交入口，避免singleTop交付onNewIntent前的短暂pause销毁新请求；真正离开仍取消且结束。资源释放先使token失效，close失败保留“已释放、待清理”状态；后续申请只重试清理该已结束资源，失败UNAVAILABLE，成功再申请。活跃会话仍BUSY，不能强抢。

终止不变：就绪后3秒内显示；OTHER稳定800ms退出；锁屏/熄屏立即撤罩；自然ABSENT稳定600ms清理；UNKNOWN不能等同结束；已有通话不得被新入口认领；晚到通话沿原启动期限处理；旧回调不能操作新会话。归属确实失效时不能绕过校验强挂断。

## 4. 删除、保留及迁移

删除SessionRegistry、SessionRegistryProvider、SessionLease、CallEndBridgeService及其测试桩；Coordinator桥状态、Messenger、跨包ResultReceiver和EndRequestPolicy桥超时；两清单Provider/自定义END_CALL权限/另一包queries。保留系统BIND_NOTIFICATION_LISTENER_SERVICE、豆包queries、私有FGS、组件名与singleTop。

同步更新verify_foreground_session、verify_call_end、verify_touch_shield、verify_coordinator、verify_project及README；历史设计不重写，以本文件替代其跨包契约。旧租约SharedPreferences忽略，不清数据。规则/缓存/URI/图标/音量/原签名不变。不增加生产测试入口。

新版本1.4.2/code10供验证，分别可安装/升级；不提交或发布。旧1.4.1仍有已知依赖，不能让未升级旧代码自动独立。首次迁移无通话时先更新视频、再更新语音；迁移间不承诺新旧混装严格互斥。新架构不要求两个版本相同。保留旧桥会违反本任务目标，因此不留旧接口。

## 5. 分阶段实施与禁止范围

| 阶段 | 修改位置 / 目标 | 测试与完成标准 |
| --- | --- | --- |
| S0 | 文档与独立临时Android验证包；不改业务代码 | 两不同UID互斥/正常关闭/进程停止释放；失败分类准确。设计高优先级关闭后才进入生产代码 |
| S1 | tests；保留特征测试，建立失败门禁 | 真实旧Registry独立性红；生产旧跨包路径结构红。原状态/规则/观察/策略特征绿；不得因编译错误冒充red |
| S2 | KernelCallLock、SessionOwnership | 真实所有权配合最小OS边界测试申请、接管、取消、旧token、错误、并发；Android系统适配验证 |
| S3 | Activity、Service、Coordinator、listener；然后清除旧类/清单 | 无另一APK访问；本地重试/超时/一次完成/旧回调绿；完整项目门禁绿。禁止改规则、缓存、URI及无关状态机业务 |
| S4 | 文档、版本、构建及设备验证runner | 双包原签名构建与DEX/清单检查；单独安装、停止/禁用/卸载另一包、并发、退出及异常。真实豆包场景与受控测试分开 |

## 6. 测试、追踪矩阵和验收

| 问题 | 代码 | 必需证据 |
| --- | --- | --- |
| R1 | Ownership/KernelLock/Activity/Manifest | 单装任一；双装；另一包未启动/停止/禁用/卸载/独立升级；不同UID互斥；本包无对方组件调用 |
| R2 | 服务/Coordinator/listener | 控制链无远端renew/桥；锁持有至有界结束；旧token拒绝；本地监听断连/恢复/超时；Home/锁屏/自然结束 |
| R3 | acquire结果/Activity日志 | BUSY有界等待；系统错误立即UNAVAILABLE；不将prepared日志当dispatch |
| R4 | 项目门禁/真实类测试/设备runner | 新测试旧实现因正确原因红；旧特征绿；新实现绿；构建产物不含旧组件及双击符号 |

本地：状态机、规则缓存、真实观察器、真实coordinator、真实Ownership；清单及构建入口静态检查。Android：使用正式入口或真实生产类验证，不用桩替代其仲裁；测试包和模拟豆包只证明受控输入，不代替真实豆包协议/ROM/音频。真实豆包验收必须有实际前台窗口、可控通知、显示/撤罩与通知结束证据；不强停豆包、不用服务存在冒充通话成功。

## 7. 风险、回滚及证据记录

剩余风险：协调端口被其它进程占用；持锁进程卡死；旧版本混装；ROM监听重绑与真实豆包通知布局。均禁止旁路或宣称无条件恢复。S0/S4真实运行证据写入最终Review。

回滚基线3daf735；不reset用户改动。设备回退必须原签名加更高versionCode重新构建，不能降级覆盖/卸载清数据；回退旧行为仍带已知独立性缺陷。原keystore不复制、不修改、不输出秘密。临时测试包仅卸载本任务明确包名；只读模拟器退出不保存数据。

阶段状态与结果由同目录plan-review/final-review记录，progress.md仅追加。未完成的设备项保持未验证，不以超时或模拟代替通过。

实施完成记录：S0系统资源9操作、S1独立性红灯、S2真实所有权39检查、S3调用方迁移及旧路径删除、S4实际APK 27场景和追加生命周期5项、一加真实豆包3项均完成。S4发现的快速重入生命周期、释放故障及规则测试假绿已先修订Review，再按红绿测试修复。最终证据及荣耀未验证等风险见2026-10-16-independent-launchers-final-review.md；没有提交或发布。
