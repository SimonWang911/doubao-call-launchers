# 架构维护重构计划开发前 Review

## Review 范围

- Review 对象：`docs/refactor/2026-10-11-architecture-maintenance-refactor-plan.md`。
- 对照对象：当前 `RuleCache`、`RuleRepository`、`CallLauncherActivity`、`build.ps1`、现有测试和两个 Manifest。
- Review 边界：只读检查，不修改业务代码。

## 发现

### P1：测试阶段没有明确把新增契约测试接入现有总入口

计划要求新增 `tests/verify_refactor.ps1`，但当前 `tests/verify_project.ps1` 并不会自动调用它。若开发者只运行 README 中已有的 `verify_project.ps1`，R1 和 R2 的新门禁可能被跳过。

证据：计划第 101 行定义了新脚本，第 181 至 186 行列出单独命令，但没有要求 `verify_project.ps1` 调用它，也没有要求 README 的标准校验命令覆盖它。

处理：计划必须明确修改 `verify_project.ps1`，在现有规则校验后调用 `verify_refactor.ps1`；README 的标准校验命令保持只调用 `verify_project.ps1`，这样一个入口即可覆盖全部静态和重构门禁。

### P1：契约模型不能证明 Android `SharedPreferences` 的真实并发行为

计划已经承认模型测试不是设备端证明，但“R1 已修复”的验收表述仍然容易把模型结果理解为真实缓存行为已经通过。

处理：将验收拆成两层：

1. 契约层：源码检查共享锁、版本单调写入，并用确定性旧竞态模型证明测试场景。
2. 运行层：正常 APK 构建后，在可用设备上通过连续启动/规则更新观察缓存和入口；没有目标设备时必须标记为未完成，不能把模型结果升级为设备证据。

当前项目没有 Gradle/JUnit/Robolectric 测试运行时。引入完整 Android 测试框架会扩大范围，因此本轮保留现有 CLI 结构，明确这个验证边界。

### P2：签名验收需要验证两 APK 的证书一致性

计划要求 `apksigner` 通过，但单独的签名通过并不能证明 voice/video 使用同一签名身份。

处理：正常构建验收同时提取两个 APK 的 signer certificate SHA-256，要求摘要相同，并与当前保留的 keystore 公钥摘要核对。缺失 keystore 的负向检查仍需非零退出。

### P2：固定远程地址的文档修订位置需要明确

当前 Java 代码固定使用项目 raw URL，`rules/remote-rule-url.txt` 只用于审计和校验。计划已经选择保留固定仓库，但没有列出 README 的具体修改内容。

处理：把 README 的运行机制改为“APK 内置固定仓库地址；`remote-rule-url.txt` 用于人工核对和脚本验证”，并保留规则 JSON 的远程更新流程。

## 结论

- 缓存共享锁方案、签名缺失即失败方案和不改变运行时规则选择的约束成立。
- 系统授权、加速源信任和设备端音量/跳转验证属于明确的外部边界，不伪造为代码已解决。
- 补充总入口、证书一致性和分层验收后，计划可以进入测试阶段。

## 修订动作

计划文件需补充以下内容后方可开始业务代码修改：

1. `verify_project.ps1` 必须调用 `verify_refactor.ps1`。
2. 设计“契约验证”和“设备运行验证”两个独立证据层级。
3. 正常构建必须核对 voice/video 证书摘要一致。
4. 明确 README 中固定 URL 文件的真实职责。
