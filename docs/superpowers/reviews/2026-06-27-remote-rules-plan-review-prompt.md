# Remote Rules Refactor Plan Review Prompt

Use this prompt to review the remote-rules refactor plan before implementation.

```text
你是资深 Android 架构师、代码审查工程师和发布流程审查者。请对当前项目和远程规则重构计划做开发前 review，目标是确认计划是否完整覆盖需求、技术路径是否可执行、风险是否被提前锁住。

项目目录：
C:\Users\Simon\Desktop\GitHub\doubao-call-launchers

重点审查文档：
docs/superpowers/plans/2026-06-27-remote-rules-refactor-plan.md

项目背景：
这是一个面向老人/盲人的 Android 工具项目，有两个独立 APK：
1. com.simon.doubao.voicecall
2. com.simon.doubao.videocall

用户通过手机语音助手打开对应 APK。APK 启动后必须自动拉满媒体音量和通话音量，然后直接打开豆包语音或视频通话入口。老人侧不能有点击、选择、设置、确认流程。

新需求：
把 APP 框架和豆包入口规则分离。APP 不再内置固定 voice/video deep link，也不以内置旧 deep link 作为兜底。每次启动必须先等待远程 GitHub JSON 规则结果，再决定是否打开豆包。

必须满足的行为：
1. 启动后先强制拉满媒体音量和通话音量。
2. 然后按候选 URL 顺序请求远程规则。
3. 远程规则成功且校验通过：使用远程规则，并写入本地缓存。
4. 远程全部失败：使用本地缓存。
5. 远程失败且无缓存：不打开豆包，Toast/TTS 提示“规则加载失败，请家人检查网络或规则文件”。
6. 远程 JSON 解析失败、校验失败、版本不合法，不能覆盖已有缓存。
7. 远程成功且合法时，不能继续使用旧缓存。
8. 不允许内置豆包 deep link fallback。
9. 不使用无障碍服务。
10. 不添加设置页或复杂 UI。
11. 保持 Android SDK CLI 构建，不迁移 Gradle。
12. 因当前已发布/安装 versionCode=1，最终 APK versionCode 必须递增。

规则 URL 顺序必须是：
1. https://gh-proxy.com/ + 原始 raw URL
2. 原始 raw URL
3. https://wget.la/ + 原始 raw URL
4. https://ghfast.top/ + 原始 raw URL

远程规则文件必须纳入计划：
- 在仓库中创建 rules/doubao-call-rules.json。
- 将仓库推送到 GitHub。
- 使用 public raw GitHub URL 作为规则原始地址。
- README 必须说明如何更新规则、递增 ruleVersion、验证 raw URL 和加速 URL。

建议 JSON 结构：
{
  "schemaVersion": 1,
  "ruleVersion": 1,
  "updatedAt": "2026-06-27",
  "doubaoPackage": "com.larus.nova",
  "doubaoActivity": "com.larus.home.impl.alias.AliasActivity1",
  "voice": {
    "uri": "sslocal://..."
  },
  "video": {
    "uri": "sslocal://..."
  }
}

请执行以下 review：

1. 先阅读真实项目文件，不要只读计划：
   - README.md
   - build.ps1
   - apps/voice/AndroidManifest.xml
   - apps/video/AndroidManifest.xml
   - common/src/com/simon/doubaolauncher/CallLauncherActivity.java
   - tests/verify_project.ps1
   - tests/smoke_adb.ps1
   - docs/superpowers/plans/2026-06-27-remote-rules-refactor-plan.md

2. 审查计划是否覆盖完整开发生命周期：
   - 本地规则 JSON 创建
   - GitHub 仓库创建或远端配置
   - 规则文件推送
   - raw URL 确认
   - 加速 URL 顺序确认
   - Android manifest 权限
   - versionCode/versionName 递增
   - build.ps1 多 Java 文件编译
   - 规则模型、解析、校验
   - 本地缓存
   - 远程优先和缓存兜底
   - Activity 编排重构
   - 静态验证更新
   - ADB smoke test 更新
   - README 运维文档
   - release APK 构建、安装、设备验收

3. 审查计划的架构拆分是否合理：
   - CallLauncherActivity 是否仍然过重？
   - RuleUrlCandidates 是否只负责 URL 顺序和时间戳？
   - DoubaoRuleParser 是否同时承担了解析和必要校验，是否过度？
   - RuleCache 是否只缓存已验证规则？
   - RuleRepository 是否清楚表达“远程成功优先、远程失败再缓存”？
   - 是否有必要增加更小的接口或是否会过度设计？

4. 审查安全边界是否足够：
   - package 是否限制为 com.larus.nova？
   - URI 是否限制为 sslocal://？
   - 是否防止远程 JSON 启动任意第三方应用？
   - 是否防止低 ruleVersion 覆盖高版本缓存？
   - 是否避免将私有 keystore 或 APK 构建产物提交到 Git？
   - GitHub 仓库如果是 private，手机是否无法访问 raw 文件？

5. 审查失败路径是否自洽：
   - 网络不可用
   - 第一个加速前缀失败
   - 原始 raw URL 失败
   - 所有代理失败
   - HTTP 非 2xx
   - JSON 格式错误
   - schemaVersion 不支持
   - ruleVersion 小于缓存
   - voice/video 任一缺失
   - doubaoPackage 不合法
   - URI scheme 不合法
   - 缓存为空
   - 缓存损坏
   - 豆包未安装
   - Activity 不存在
   - startActivity 抛异常
   - TTS 初始化失败

6. 审查测试策略是否足够：
   - verify_project.ps1 是否能防止 hardcoded deep link 回归？
   - 是否检查 INTERNET 权限？
   - 是否检查 URL 顺序？
   - 是否检查 raw URL placeholder 不能进入 release？
   - 是否检查规则 JSON 语法？
   - 是否检查 ADB smoke test 不默认启动真实通话？
   - 是否需要增加一个纯 PowerShell 或 Java 小型 parser 验证脚本？
   - 当前静态测试是否有误报/漏报风险？

7. 审查计划中的每个阶段是否可独立提交、可验证、可回滚。

8. 特别检查计划里是否存在这些问题：
   - “TODO / TBD / 后续再说 / 适当处理”等占位表达。
   - 文件路径不准确。
   - 命令无法在 Windows PowerShell 中运行。
   - 先后顺序错误，例如在 GitHub URL 还不存在时就要求 final build。
   - 忘记将 `RuleRepository.java` 中的 `<owner>` placeholder 替换为真实 raw URL。
   - 忘记把仓库推送到 GitHub，导致手机无法远程拉规则。
   - 仍然允许 APK 内置旧 deep link fallback。

输出格式：
请按严重程度排序：

P0：会导致需求无法实现、APK 无法联网、无法发布规则、老人无法使用。
P1：会导致远程规则机制不可靠、缓存策略错误、更新后仍可能失效。
P2：维护性、测试覆盖、边界条件或计划执行风险。
P3：文档、命名、轻微改进。

每条 finding 必须包含：
- 文件/计划位置
- 问题说明
- 为什么影响需求
- 建议修正方向

最后给出明确结论：
1. 计划是否可以进入开发？
2. 是否必须先修改计划？
3. 最应该优先修正的 3 个点是什么？
4. 是否建议保持当前仓库重构，而不是重新开发？

注意：
本次只做 review，不要直接改代码，不要执行实现计划，不要构建 APK，除非只是为了验证计划里的命令是否存在明显错误。
```
