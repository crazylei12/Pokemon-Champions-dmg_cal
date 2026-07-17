# Android 对局回放 Phase 1 完成记录

日期：2026-07-17

分支：`feature/battle-replay-phase-1`

状态：Phase 1 已完成；Phase 0 的 OPD2409/完整隔离矩阵仍待补，Phase 2 的 H.264、EGL、MediaStore 和 MP4 尚未实现

## 1. 本阶段完成范围

Phase 1 只建立“投屏授权后选择模式，再按模式启动唯一捕获 Surface”的可靠会话边界。它没有提前实现正式录屏，也不会生成 MP4。

本阶段完成：

- 新增 `CaptureSessionMode`、`CaptureSessionState` 和纯 Kotlin `CaptureSessionStateMachine`；
- 首次悬浮球菜单固定显示 `识别并录屏`、`仅识别`、`仅录屏` 三种模式；
- 一个投屏 token 内模式一旦选定就锁定，必须结束并重新授权才能切换；
- 把 MediaProjection 获取与 VirtualDisplay 创建拆开，选择模式前没有捕获 Surface；
- 用状态机和服务双重守卫保证一个 token 最多创建一次 VirtualDisplay；
- 把 OCR、模板、伤害运行时和识别悬浮控制器移入 `RecognitionFeatureHost`，只在包含识别的模式中延迟初始化；
- 新增 `ReplayPermissionActivity`，只在用户选择包含录屏的模式后请求游戏内部声音权限；
- 权限 Activity 同时提供前台通知点击兜底；拒绝权限后必须明确选择取消或继续无声，绝不静默降级，也不改录麦克风；
- 通知权限与系统投屏授权改为串行请求，避免 ColorOS 上两个系统授权页互相覆盖；
- 保留现有仅识别的原分辨率 `ImageReader`、截图、阵容识别和悬浮面板路径。

## 2. 会话边界

当前主路径是：

```text
MainActivity 检查悬浮权限
  -> 串行完成通知权限
  -> 系统单应用投屏授权
  -> Service 持有 MediaProjection，显示“选择模式”悬浮球
  -> 用户选择本次模式
  -> 录屏模式先完成声音权限决策
  -> 按锁定模式创建唯一 VirtualDisplay
  -> RUNNING
  -> 任一停止来源进入幂等停止和统一释放
```

三个模式在 Phase 1 的行为：

| 模式 | 识别组件 | 声音权限 | 捕获 Surface | Phase 1 输出 |
| --- | --- | --- | --- | --- |
| `仅识别` | 延迟初始化 | 不请求 | 原有 ImageReader | 现有识别和悬浮面板，不生成 MP4 |
| `仅录屏` | 不初始化 | 请求或明确无声继续 | Phase 1 占位 ImageReader，帧只被及时丢弃 | 会话骨架，不生成 MP4 |
| `识别并录屏` | 延迟初始化 | 请求或明确无声继续 | Phase 1 仍使用识别 ImageReader | 现有识别能力，不生成 MP4 |

后两种模式的 UI 会明确写出“Phase 1 暂不生成 MP4”。真正的视频编码、固定 540p 画布、帧路由和 MediaStore 发布属于 Phase 2，不能把当前占位 Surface 当成录屏完成。

## 3. 自动测试

新增状态机测试覆盖：

- 仅识别不进入声音权限状态；
- 录屏模式等待声音权限决策；
- 已有权限时跳过等待；
- 权限取消进入停止；
- 模式锁定，第二次选择被拒绝；
- 一个会话只允许标记一次 VirtualDisplay；
- 重复停止保持幂等；
- VirtualDisplay 创建前不能进入运行态；
- 三种模式 wire name 解析。

验证命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/run-gradle.ps1 :app:testDebugUnitTest -PandroidAbis=arm64-v8a
```

结果：全部 Android Debug JVM 单元测试通过。随后运行项目的双 ABI 构建脚本，同时再次执行测试并核对 APK：

| APK | 大小 | 唯一原生 ABI | SHA-256 |
| --- | ---: | --- | --- |
| `app-arm64-v8a-debug.apk` | 81,440,513 bytes | `arm64-v8a` | `14DE77D96846728AADB7CA8C2699B031BC7C7FD7C8D0C224FE420E35CC775628` |
| `app-x86_64-debug.apk` | 117,284,861 bytes | `x86_64` | `AD6F124DC1E11C7DCB46FC7641B16E9A6F0BAA31930E716DE823C939D9DA34C6` |

输出目录只有这两个 APK，没有 universal、`armeabi-v7a` 或 `x86` 产物。

## 4. RMX3820 真机验证

设备：RMX3820，Android 16 / API 36，ADB 序列号 `6465e08`。

### 4.1 选择模式前不创建 VirtualDisplay

系统单应用投屏授权完成后：

- 日志只有 `Projection token acquired; waiting for a capture mode`；
- 悬浮球显示“选择模式”；
- 首次菜单可见且只列出三种确认模式和取消；
- 尚未出现 `Capture surface ready`。

这证明系统 token 与实际捕获 Surface 已按设计分成两步。

### 4.2 仅录屏不初始化识别

选择 `仅录屏` 后：

- 日志只出现一次 `Capture surface ready: mode=RECORD_ONLY`；
- 整个会话没有 `RecognitionFeatureHost initialized`；
- 活动菜单没有 OCR、双方阵容识别或伤害面板入口；
- 菜单明确显示 Phase 1 会话骨架和“暂不生成 MP4”；
- 结束后 `dumpsys media_projection` 为 `null`。

### 4.3 声音权限 Activity 和通知兜底

通过卸载并重新安装 Debug APK 把通知与声音权限恢复为首次安装状态后，实测：

- 通知权限先完成，之后才出现系统投屏授权页；
- 选择 `仅录屏` 后直接打开 `ReplayPermissionActivity`；
- 前台通知同时显示“仅录屏等待游戏内部声音授权”，并提供“继续录屏授权”和“结束助手”操作；
- Activity 明确说明 Android 会把播放音频捕获标记为录音权限，但助手只筛选 Pokemon Champions UID，不读取麦克风；
- 拒绝系统权限后出现“取消”和“录制无声视频”两个明确选项；
- 选择无声继续后 `RECORD_AUDIO` 仍为未授权，捕获会话可启动，没有静默改用麦克风。

### 4.4 仅识别回归和停止

选择 `仅识别` 后：

- 日志先出现 `RecognitionFeatureHost initialized for mode=RECOGNIZE_ONLY`，随后只创建一次对应 Surface；
- 原悬浮菜单仍包含“录入我的队伍”“识别双方阵容”“结束对局助手”；
- 实际点击“识别双方阵容”后，原有 `TeamPreviewRecognitionEngine` 完成一帧处理并输出性能记录；
- 从竖屏授权流程进入 Pokemon Champions 固定横屏后，Surface 规格为 `2772 x 1240`、`rotation=1`；
- 从悬浮菜单结束后，服务释放投屏，`dumpsys media_projection` 为 `null`，日志无崩溃。

测试产生的临时阵容结果已从 Debug 私有目录清除。为首次权限验证而重装前备份的 7 份 Phase 0 探针 JSON 已全部恢复。

### 4.5 最终 ARM64 安装核对

最终双 ABI 构建完成后，把 `app-arm64-v8a-debug.apk` 安装到 RMX3820。设备上先前的 Debug 包来自另一份 Android Debug 证书，覆盖安装被系统以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 正确拒绝；在确认正式版包不受影响、Phase 0 私有报告已有校验备份后，只卸载并重装 Debug 包。

最终设备状态：

- 包名 `com.crazylei12.pokemonchampionsassistant.debug`；
- `versionName=1.1.0-debug`、`versionCode=5`、`targetSdk=36`；
- `primaryCpuAbi=arm64-v8a`；
- 已安装 APK 证书 SHA-256 为 `f3be0f6c4c45ab36eb2c42e8bbc090d8c18c5afbb1f3464964113d582721cfd7`，与本次构建 APK 一致；
- ColorOS 悬浮窗权限已恢复为 `allow`；
- 7 份 Phase 0 探针 JSON 已恢复并逐项列出确认。

## 5. Phase 1 退出条件

- [x] 所有现有 Android 单元测试通过。
- [x] 仅识别真机截图/识别、竖屏授权进入横屏游戏和结束服务不回归。
- [x] 仅录屏在尚未实现编码时不初始化识别组件。
- [x] 一个 token 只创建一个 VirtualDisplay。
- [x] 声音权限只在模式选定后请求，拒绝后不静默降级。
- [x] ColorOS 上直接权限 Activity 与通知点击兜底均可到达。

## 6. 仍未完成

Phase 1 完成不改变以下状态：

- OPD2409 不在线，Phase 0 的第二台设备基线、PCM 对照和权限回归仍待执行；
- RMX3820 的状态栏、通知、其他应用、悬浮菜单和伤害面板完整画面隔离矩阵仍待补；
- 还没有 EGL 帧路由、H.264/AAC 编码、音视频时间戳、MediaMuxer、MediaStore 或可播放 MP4；
- 还没有 10/30 分钟性能、温度、空间不足和异常收尾验收；
- Release 权限和产品文案尚未进入发布验收。

下一实现阶段是 Phase 2 的无声纯视频纵切。它必须复用 Phase 1 的模式锁定、唯一 VirtualDisplay 和幂等停止边界。
