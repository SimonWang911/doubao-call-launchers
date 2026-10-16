# 开发前 Review：双启动器独立运行

日期：2026-10-16。主代理在设计后重新核对真实Activity/FGS/协调器/listener/Manifest/构建及测试，无子代理。

| 优先级 | 复审问题 | 修订 / 状态 |
| --- | --- | --- |
| P1 | 只把Registry改成本包会丢失跨入口互斥 | 先验证公开系统独占资源；两不同UID竞争必须一胜一负，持有至清理；S0未通过前禁止业务迁移 |
| P1 | LocalSocket IOException不能可靠分类，解析错误字符串违反设计 | 改公开Os.bind IPv4 loopback固定资源，EADDRINUSE和其它errno分开；无listen/连接/通信，minAPI21低于项目23 |
| P1 | Activity取消若先释放，晚到PREPARE/旧服务可干扰新会话 | 本地接管位+releaseUnclaimed；服务接管后由服务结束释放，所有清理按token；补接管取消回归 |
| P1 | 续租失败后强行END可能误挂新通话 | 消除远端租约失效来源；仍保留本地归属和通知key校验，禁止失权强挂断 |
| P1 | 新版与未修改1.4.1无法同时保证双向兼容和删除旧桥 | 明确旧混装非独立性验收对象；首次无通话顺序迁移；之后版本独立，无永久兼容组件 |
| P2 | 原测试把旧桥当正确行为 | 迁移本地协调器回归，删除桥特有测试并补视频本地超时不调用对方；不删除取消/串行/旧回调特征 |
| P2 | kernel资源失败可能FD泄漏或被误报忙 | 候选FD失败关闭；只有EADDRINUSE重试；其它错误明确失败；close问题不得释放另一会话句柄 |
| P2 | 构建版本门禁硬编码1.4.1 | 本轮同步1.4.2/code10，保留签名、包名、规则；不得生成新密钥 |
| P2 | 测试镜像不能证明荣耀ROM/真实豆包行为 | 内核实证、受控集成、正式APK验证、真实豆包分别记录；实机不可用时不得全量通过 |

问题覆盖R1-R4完整，模块只增加系统适配和本地所有权，保留当前CallSession和观察器，不新增框架。依赖顺序S0→S1→S2→S3→S4成立。高优先级设计已修订；当前只允许S0验证和S1测试，S0实证记录后方可业务开发。

S0完成：只读Android模拟器emulator-5580，测试包UID10171/10172；A持有时B得到EADDRINUSE(98)，A关闭后B成功，强停B后A成功，卸载B后A仍成功；绑定无效地址产生EADDRNOTAVAIL(99)，明确UNAVAILABLE。9条真实Android操作证据保存在任务work/independence-s0/device-result.json，两个临时包已卸载。没有listen/connect/accept或数据通信。它证明系统资源适配，不是荣耀或真实豆包通话验收。

S1完成：新增verify_independence -ContractOnly在真实旧代码上因Registry/Provider/lease/桥和双清单依赖失败，非编译缺类。原完整verify_project通过（含状态62、租约13、观察器15、coordinator9及规则/缓存/证据边界）。本地所有权测试已编写，覆盖接管取消、迟到PREPARE、旧token、内核占用/错误、FD关闭和16线程竞争。高优先级设计及基础验证已关闭，允许S2业务开发。

S4验收回修（先修订设计再改代码）：

- P1：实际APK连续两次am start时，singleTop交付onNewIntent前会经历onPause；旧Activity在onPause且尚未dispatch时直接finish，使新intent的规则回调因Activity已销毁被丢弃。受控Android矩阵video-rapid-repeat稳定出现两次launch mode且零dispatch/零遮罩，前23例通过。把“暂停即取消准备”与“离开即结束Activity”按生命周期职责拆开：onPause仍取消准备，onStop才结束未转交的入口。短暂停顿不会销毁接收新intent的实例，真正Home/切应用仍不允许后台启动。禁止延时猜测、重试旁路或改变规则仓库。
- P2：故障注入证实close失败保留归属，而服务已结束并丢弃token，后续永远BUSY。释放必须先撤销会话身份；仅对已结束资源保留待清理状态，下一次申请先重试关闭该句柄，失败报告UNAVAILABLE，成功才申请新资源。不得重试关闭仍活跃的会话。先补测试确认旧实现失败，再修改基础抽象。

补充验收门禁：真实APK快速重入、交叉入口竞争与准备期间真正离开；错误关闭后旧token失效、持续错误不误报BUSY、清理恢复及旧释放不影响新token。上述修订覆盖新发现的生命周期和资源清理边界，不新增框架。

- P2（测试门禁）：从仓库外运行完整门禁时，verify_rules虽预检了绝对路径，却在Python使用相对路径，且未检查Python退出码，FileNotFoundError后汇总仍显示成功。此前这部分“绿色”无效。修订为显式传入已经定位的规则文件并立即检查退出码；从仓库外重跑，另用临时非法规则验证非零退出，再重新确认全量结果。只修验证入口，不修改规则业务。
