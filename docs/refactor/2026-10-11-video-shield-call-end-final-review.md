# AI 通话遮罩与双击结束通话最终代码 Review

> 历史记录：以下结论不代表新前台会话方案的验收结果。双击方案已由 [2026-10-13 前台会话重构](2026-10-13-foreground-session-refactor-plan.md) 替代。

## 1. Review 范围

- Review 基线：当前 `master` 工作区和本轮生成的 `1.3.0` 两个 APK。
- 范围：共享启动链路、遮罩服务、双击状态机、通知挂断适配、跨 APK 回退、Manifest 安全边界、规则和音量兼容性、测试门禁及构建产物。
- 真实设备通话验收：用户已确认荣耀设备上的 AI 语音和 AI 视频均可通过遮罩双击结束通话。本轮不重复启动真实通话。

## 2. 最终结论

核心架构合理，语音和视频共用同一套保护链路，入口规则、音量保护和模式差异仍由原有启动器负责。最终代码 Review 中发现的桥接服务安全缺口已经按最小范围修复，当前没有新的发布阻塞项。

## 3. Review 发现与处理

### P1：跨 APK 桥接服务原先只信任调用方自报的包名

原实现将 `EXTRA_CALLER_PACKAGE` 作为调用方身份依据，再比较语音 APK 与该包名的签名；Intent extra 可以由任意应用填写，不能证明真实调用者就是视频 APK。原 Manifest 中桥接服务为导出服务且没有服务级权限。

处理结果：

- 语音 Manifest 声明 `com.simon.doubao.voicecall.permission.END_CALL`，保护级别为 `signature`。
- `CallEndBridgeService` 强制要求该签名权限。
- 视频 Manifest 请求该权限。
- 原有固定视频包名和 `checkSignatures()` 校验保留为第二道防线。

### P2：桥接路径原先没有测试和文档追踪

处理结果：

- `tests/verify_call_end.ps1` 现在检查桥接类、固定动作、签名校验、签名级权限和两个 Manifest 的声明。
- 重构计划新增跨 APK 回退的数据流、模块职责、追踪矩阵和测试门禁。
- README 记录桥接仅用于已签名 companion APK，不使用 ADB、进程终止或开放广播。

## 4. 当前数据流

```text
AI语音/AI视频 CallLauncherActivity
  -> 拉满媒体音量和通话音量
  -> 等待远程规则/缓存结果
  -> 检查悬浮窗、通知和通知监听授权
  -> 启动 TouchShieldService，等待 RESULT_READY
  -> 打开对应的豆包语音或视频入口

遮罩双击
  -> CallEndCoordinator
  -> 优先调用本 APK 的 DoubaoNotificationListenerService
  -> 视频本地监听不可用时，通过签名级权限调用语音 APK 的 CallEndBridgeService
  -> RemoteViews 挂断控件执行并确认豆包通话通知消失
  -> 成功才清理遮罩，失败则保留遮罩并允许重试
```

## 5. 验证证据

- `tests/verify_project.ps1`：通过。
- `tests/verify_call_end.ps1`：通过。
- `tests/verify_touch_shield.ps1`：通过。
- `tests/verify_rules.ps1`：通过。
- `tests/verify_double_tap.ps1`：由项目门禁执行并通过。
- `git diff --check`：通过。
- `build.ps1`：两个 APK 构建成功。
- 两个 APK 的 v1/v2/v3 签名验证通过。
- 两个 APK 的证书 SHA-256 摘要一致：
  `878990517f92b7ee41dd14285e7cc81c4c9534dd5814c096e683a04dff6a9d40`。
- 生成 APK 的 Manifest 已反查确认：桥接服务带签名级权限，视频 APK 请求该权限，TouchShieldService 仍为非导出服务。
- 用户真实验收确认：语音和视频均可通过双击结束通话。

## 6. 尚存边界

- 豆包通知的 `RemoteViews` 和 `hangup_btn` 属于外部实现细节。若豆包更新布局，适配器会失败关闭并保留遮罩，需要更新适配逻辑。
- 普通 APK 无法控制 Home、系统返回手势、锁屏和通知栏等系统级操作。
- 构建仍会输出 Java 8 选项和部分弃用 API 警告，但不影响本次编译和 APK 签名结果。
