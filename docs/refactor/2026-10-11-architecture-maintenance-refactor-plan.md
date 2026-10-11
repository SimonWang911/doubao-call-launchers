# 架构维护重构计划

## 1. 文档状态与范围

- 状态：已完成重构开发、自动验证和构建；设备端当前仅完成既有安装包的只读 smoke 检查。
- 依据：当前 `master` 工作区、现有 Java、Manifest、规则文件、PowerShell 测试和构建脚本。
- 目标：修复已确认的缓存并发写入和正式构建签名门禁问题，补足能证明修复有效的测试，修正文档对固定规则仓库的描述。
- 保持：两个 APK、公共 `CallLauncherActivity`、远程规则 JSON、四个并发 URL 候选、2 秒缓存等待、10 秒无缓存等待、固定 `com.larus.nova` 包名、媒体音量和通话音量拉满行为。
- 排除：不改变豆包 URI 业务参数、不引入无障碍服务、不改包名、不改变规则 JSON 格式、不增加规则签名系统、不创建新进程、不迁移 Gradle。

## 2. Review 复核结论

### 2.1 已确认并纳入重构的问题

| 编号 | 结论 | 证据 | 影响 |
| --- | --- | --- | --- |
| R1 | 缓存的“读取当前版本并提交新版本”没有跨 `RuleCache` 实例的共享锁。 | `RuleCache.saveIfNewer()` 使用实例级 `synchronized`；`CallLauncherActivity` 每次启动都新建 `RuleRepository`，每个仓库都新建 `RuleCache`。 | 两次启动的迟到响应可能出现 v5 先写入、v4 后写入，缓存降级到旧规则。 |
| R2 | 构建脚本在默认 keystore 缺失时自动生成新签名。 | `build.ps1` 当前包含 `keytool -genkeypair` 自动生成路径。 | 构建表面成功，但无法覆盖手机中由旧签名安装的 APK。 |
| R3 | 核心规则和缓存行为没有真实执行 Java 代码的回归门禁。 | `verify_project.ps1` 主要做源码标记检查，`verify_rules.ps1` 是独立 Python 模型；没有缓存实例并发、Activity 生命周期或实际规则请求测试。 | R1 这类实现级竞态可以在现有测试全部通过时继续存在。 |

### 2.2 复核后不作为代码缺陷处理的事项

| 事项 | 复核结论与处理 |
| --- | --- |
| 系统跨应用授权 | `startActivity()` 依赖 Android/荣耀系统授权，普通 APK 无法绕过或延长授权期限。保留现有路径，在文档和验收中明确这是平台边界，不伪造代码修复。 |
| 固定远程仓库地址 | 仓库地址按当前业务约束固定在本项目。`rules/remote-rule-url.txt` 是审计记录和测试输入，不是安装后由 APK 动态读取的配置；只修正文档表述，不引入生成配置体系。 |
| 加速源可信性 | 当前需求明确使用加速源并取最快有效返回。规则校验继续保持已确认的最小字段集合，不增加签名、投票或多源一致性协议；将其记录为发布链路的信任边界。 |
| 迟到请求不取消 | 迟到结果只更新缓存、不再次启动豆包是既定行为。保留后台刷新，不为取消线程增加额外生命周期复杂度。 |

## 3. 根因与影响范围

### 3.1 根因

R1 的根因是共享状态和同步边界不一致：规则缓存由同一 APK 进程内的多个 Activity 使用，但锁属于每个 `RuleCache` 对象，而不是属于共享缓存本身。修复必须把“版本比较到 commit 完成”放进进程级同一把锁，不能只添加更多版本判断分支。

R2 的根因是构建脚本同时承担开发便利和正式发布职责，却没有把签名身份作为正式构建前置条件。修复必须移除自动生成路径，让签名身份缺失时构建失败。

R3 的根因是测试层只验证源码形状和规则算法模型，没有把关键不变量变成明确的重构门禁。修复必须增加能在重构前失败、在重构后通过的契约测试，并保留现有正确行为检查。

### 3.2 影响范围

- 运行时：`RuleCache`、`RuleRepository`、`CallLauncherActivity` 的规则加载链路。
- 构建时：`build.ps1` 的 keystore 选择和缺失处理。
- 测试时：`tests/verify_project.ps1`、`tests/verify_rules.ps1` 以及新增重构契约测试。
- 文档时：README 的远程规则地址说明、构建签名说明和测试范围说明。
- 不影响：规则 JSON 字段、两个包名、Manifest 模式、音量行为、Intent URI 和 APK 入口结构。

## 4. 当前架构与目标架构

### 4.1 当前调用链

```text
Launcher Activity
  -> maximizeAudibleVolume()
  -> readLaunchMode()
  -> new RuleRepository(applicationContext)
  -> RuleCache.load()
  -> four concurrent HTTP requests
  -> RuleRequestCoordinator
  -> RuleCache.saveIfNewer()
  -> Intent.setComponent() / startActivity()
```

### 4.2 目标调用链

```text
Launcher Activity
  -> RuleRepository(applicationContext)
  -> RuleCache.load()
  -> concurrent requests and foreground decision
  -> RuleCache.saveIfNewer()
       -> process-wide CACHE_WRITE_LOCK
       -> reload current cache
       -> compare ruleVersion
       -> commit
  -> existing volume and Doubao dispatch path
```

目标架构只改变缓存写入的同步归属和构建签名门禁，不改变规则选择策略。最快的有效高版本规则仍用于当前启动；迟到的更高版本只写缓存。

## 5. 重构方案与选择理由

### 5.1 缓存并发写入

在 `RuleCache` 增加类级别的进程内锁，例如 `CACHE_WRITE_LOCK`。`saveIfNewer()` 去掉实例级同步，改为在共享锁内完成以下完整序列：

1. 重新读取当前缓存。
2. 比较远程规则与当前缓存的 `ruleVersion`。
3. 仅当远程版本更高时执行 `commit()`。

不增加新的缓存格式，不增加数据库，不改变 `SharedPreferences` key。这样可以覆盖同一 APK 内多个 Activity、多个 RuleRepository 和所有迟到网络线程。

### 5.2 正式构建签名

保留 `DOUBAO_KEYSTORE`、alias 和密码环境变量接口，保留默认 `build\doubao-launchers-debug.keystore` 路径，但删除自动生成 keystore 的代码。指定路径或默认路径不存在时立即失败，并明确要求恢复原签名文件。

该方案不把私钥写入仓库，也不改变现有 keystore 内容；当前环境中已验证的 keystore 继续使用。

### 5.3 测试门禁

新增 `tests/verify_refactor.ps1` 作为当前项目 CLI 测试体系中的重构契约门禁，并由现有 `tests/verify_project.ps1` 在规则校验后自动调用。README 的标准校验命令继续只调用 `verify_project.ps1`，避免遗漏新门禁。新脚本检查：

- RuleCache 使用进程级共享锁保护比较和提交。
- 旧的实例级同步契约不再作为唯一保护。
- 构建脚本在 keystore 缺失时失败，且不再自动生成签名。
- 用确定性的版本写入模型重现“v5 后被 v4 覆盖”的旧竞态，并验证共享锁策略保留 v5。

该测试明确标注为契约/回归测试。契约证据只能证明源码同步边界和确定性旧竞态模型；真实 Android `SharedPreferences`、Activity 生命周期、音量和豆包跳转必须单独记录设备运行证据，不能把模型测试描述成设备端证明。

## 6. 模块职责与接口变化

| 模块 | 重构后职责 | 接口变化 |
| --- | --- | --- |
| `CallLauncherActivity` | 保持模式读取、音量、TTS、最终 Intent 调度。 | 无公开接口变化。 |
| `RuleRepository` | 保持并发请求、最快有效规则选择、迟到缓存刷新。 | 无行为接口变化。 |
| `RuleCache` | 负责解析后的规则缓存和版本单调写入；同步边界提升到进程级。 | `saveIfNewer(DoubaoRule)` 保持方法语义，内部同步实现变化。 |
| `DoubaoRuleParser` | 保持当前最小规则校验。 | 无变化。 |
| `RuleUrlCandidates` | 保持 URL 顺序和并发候选生成。 | 无变化。 |
| `build.ps1` | 负责 SDK 构建和固定签名输入校验。 | 缺失 keystore 从自动生成改为立即失败。 |
| `tests/verify_refactor.ps1` | 验证重构不变量和回归场景。 | 新增测试入口。 |

## 7. 兼容与迁移方案

- 已有 `SharedPreferences` 中的 `rule_json` 不迁移，旧缓存可直接按原 JSON 读取。
- `schemaVersion`、`ruleVersion`、URI、固定包名和 Activity 字段不变。
- 版本升级仍使用相同包名和相同 keystore；应用名沿用当前工作区的 `AI语音`、`AI视频`。
- 构建前必须确认原 keystore 存在；缺失时停止构建，不生成替代签名。
- 系统跨应用授权状态由 Android/荣耀系统管理，APK 更新不会伪造或清除该授权。

## 8. 追踪矩阵

| 问题/约束 | 代码 | 测试 | 验收证据 |
| --- | --- | --- | --- |
| R1 缓存版本不可降级 | `RuleCache.java` 共享锁和版本比较 | `verify_refactor.ps1` 竞态契约、`verify_rules.ps1` 版本模型 | 双实例竞态模型最终保留最高已提交版本；结构校验通过 |
| R2 签名身份不可自动漂移 | `build.ps1` keystore 缺失即失败 | `verify_refactor.ps1` 脚本契约；缺失路径负向执行 | 缺失 keystore 命令非零退出；正常构建的 voice/video 证书摘要相同，并与保留 keystore 的公钥摘要一致 |
| R3 核心不变量有门禁 | `tests/verify_refactor.ps1` | 现有项目/规则测试加新契约测试 | 所有脚本通过，APK 构建并签名验证通过 |
| 固定仓库地址说明准确 | README、远程 URL 记录文件说明 | `verify_project.ps1` 文档检查 | 文档明确 APK 内置固定地址，URL 文件只用于人工核对和脚本验证 |
| 系统授权边界明确 | 现有 `startActivity` 路径不变 | ADB/人工启动记录 | 记录目标 ROM 授权状态，不能用静态测试替代 |

## 9. 分阶段实施计划

### 阶段 0：文档和门禁 Review

- 修改位置：`docs/refactor/`、测试计划文档。
- 目标：完成计划、独立 Review、追踪矩阵和验收标准。
- 禁止：修改 Java、Manifest、规则 JSON 和构建脚本。
- 完成标准：高优先级计划缺口关闭，测试能明确在旧代码上失败。

### 阶段 1：先添加回归测试

- 修改位置：`tests/verify_project.ps1`、`tests/verify_refactor.ps1`。
- 目标：把共享锁、不可自动生成 keystore、版本竞态模型变成门禁。
- 禁止：修改业务实现以迎合测试。
- 完成标准：在当前旧实现上因缺少共享锁/仍自动生成 key 而失败，并能说明失败原因；`verify_project.ps1` 自动调用新门禁。

### 阶段 2：修复缓存同步根因

- 修改位置：`common/src/com/simon/doubaolauncher/RuleCache.java`。
- 目标：跨多个 RuleCache 实例保护读取、比较、提交完整序列。
- 禁止：改变规则选择、超时、缓存 JSON 格式或版本比较方向。
- 完成标准：契约测试通过，`verify_rules.ps1` 和项目结构测试通过。

### 阶段 3：收紧签名构建门禁

- 修改位置：`build.ps1`、`tests/verify_project.ps1`、README。
- 目标：缺少签名文件时构建失败，存在原签名时正常构建。
- 禁止：生成新 key、修改现有 keystore、改变包名或签名算法。
- 完成标准：缺失路径负向测试非零退出，正式构建 v1/v2/v3 验证通过。

### 阶段 4：修正文档和完整验证

- 修改位置：README、`docs/refactor/`、`progress.md`。
- 目标：文档准确反映固定仓库、系统授权边界和最终代码。
- 禁止：添加未实现的兼容承诺。
- 完成标准：静态校验、规则测试、契约测试、构建、双 APK 证书一致性、可用 ADB 设备检查全部有结果记录；设备不可用时明确记录缺口。

## 10. 测试计划与验收标准

### 必须通过的自动检查

1. `pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tests\verify_project.ps1`，该入口必须自动包含规则测试和重构契约测试。
2. `pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tests\verify_rules.ps1`，单独运行规则模型测试。
3. `pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tests\verify_refactor.ps1`，单独运行重构契约测试。
4. 使用不存在的 `DOUBAO_KEYSTORE` 执行构建，命令必须非零退出且不能生成替代 key。
5. 正常 keystore 下执行 `build.ps1`，两 APK 均生成。
6. `apksigner verify --verbose --print-certs` 对两 APK 通过 v1/v2/v3，且两个 signer certificate SHA-256 相同。

### 运行与真实入口检查

- 检查 APK 包名、版本、应用名和签名证书摘要。
- 若目标 ADB 设备在线，运行现有 `smoke_adb.ps1`，再由人工确认启动入口、系统授权、音量和豆包页面。
- 若目标设备不在线，必须明确记录设备端入口、荣耀授权有效期和真实音量仍未验证，不能用静态测试代替。

开发前 Review 文档：`docs/refactor/2026-10-11-architecture-maintenance-plan-review.md`。该 Review 的 P1/P2 修订项已并入本计划。

## 11. 风险与回滚

- 风险：共享锁只解决同一 APK 进程内并发；不同 APK 的缓存本来就是隔离的，不共享状态。
- 风险：Android/荣耀授权过期仍可能阻断跨应用跳转，当前重构不改变系统策略。
- 风险：签名门禁会让缺少 keystore 的新环境主动失败，需要从安全备份恢复原文件。
- 回滚：在提交前可恢复本轮修改文件，并删除新增文档和测试；不得删除或覆盖已有 keystore。
- 版本回滚：保留当前 `versionCode` 单调规则；若 APK 已分发，不使用低版本覆盖安装。

## 12. 实施结果与证据

- R1：`RuleCache.saveIfNewer()` 已改为使用进程级 `CACHE_WRITE_LOCK`，并在锁内重新读取缓存、比较 `ruleVersion`、执行 `commit()`。
- R2：`build.ps1` 在签名文件缺失时立即失败，已移除 `keytool -genkeypair` 自动生成路径。
- R3：`tests/verify_refactor.ps1` 已接入 `tests/verify_project.ps1`；契约测试包含旧竞态模型和序列化后保留高版本的模型。
- 自动测试：`verify_rules.ps1`、`verify_refactor.ps1`、`verify_project.ps1` 均通过；缺失 keystore 负向构建测试以非零退出且未生成替代文件。
- 构建产物：`dist/doubao-voice-call.apk` 和 `dist/doubao-video-call.apk` 均生成，两个 APK 的 v1/v2/v3 签名验证通过，证书 SHA-256 摘要一致，并与当前 keystore 证书摘要一致。
- 设备验证：通过无线调试配对的 `M2007J1SC` 已安装本轮 `versionCode=4`、`versionName=1.1.2` 两个 APK，`smoke_adb.ps1` 通过。语音冷启动进入豆包 `RealtimeCallActivity`；视频冷启动的 Activity 链包含 `open_vlm=1` 和 `sec_scene=shortcuts_video_call`，UI 出现视频背景、摄像头控件并将“视频通话”设为选中。设备音频实测 `STREAM_MUSIC=150/150`、`STREAM_VOICE_CALL=11/11`，均未静音，且日志记录了两个 APK 分别执行音量拉满。另一个在线设备 `PJD110` 仍是旧的 `1.1.1`，目标荣耀设备 `NTH-AN00` 不在当前 ADB 列表中；因此荣耀系统授权有效期仍未在目标机复验。
