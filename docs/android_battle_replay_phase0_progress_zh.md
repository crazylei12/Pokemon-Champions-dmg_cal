# Android 对局回放 Phase 0 当前实现与验证

日期：2026-07-17

分支：`feature/battle-replay-phase-0`

计划来源：[`android_battle_replay_recording_plan_zh.md`](android_battle_replay_recording_plan_zh.md)

## 1. 本次完成范围

本次只完成 Phase 0 的第一小段：把设备、安装包、游戏 UID、目标编解码器和无录屏资源快照做成可重复运行的真机基线探针。尚未开始正式录屏链路，也没有提前修改 MediaProjection、音频权限或悬浮菜单。

新增内容：

- `tools/android/collect-replay-phase0-baseline.ps1`
  - 未指定序列号时只接受恰好一台在线设备；多设备时必须传 `-Serial`，避免采错手机或模拟器。
  - 记录设备型号、Android/API、构建指纹和显示/窗口原始信息。
  - 记录 Pokemon Champions 和助手的版本、versionCode、UID、minSdk、targetSdk 以及 `ALLOW_AUDIO_PLAYBACK_CAPTURE` 标志。
  - 从 `dumpsys media.player` 解析 H.264/AVC 和 AAC 编码器，分别核对 `960 x 540 / 24 fps / 1.5 Mbps / Surface` 与 `48 kHz / 双声道 / 96 kbps` 目标。
  - 记录助手 PSS/RSS、助手与游戏进程 CPU/内存快照、系统热状态、电池/机身温度和游戏帧统计可用性。
  - 每次运行在 `.tmp/replay-phase0/<时间>/<序列号>/` 生成 `report.md`、`report.json` 和全部原始系统输出。
- `manual-test-scripts/collect-replay-phase0-baseline.cmd`
  - 提供 Windows 下直接运行的入口，并把参数原样传给 PowerShell 探针。
- `android-app/app/build.gradle.kts`
  - 默认双 ABI 时使用显式的 `include("arm64-v8a", "x86_64")`，让项目的确定性双 ABI 构建器能够核对配置。
  - 传入 `-PandroidAbis=arm64-v8a` 时仍保留单 ABI Release 构建能力；没有生成 universal APK。

## 2. RMX3820 实测结果

最终基线在 RMX3820（序列号 `6465e08`）上采集，助手处于 v1.1.0 普通前台空闲状态，未启动 MediaProjection 和录屏。

| 项目 | 结果 |
| --- | --- |
| 设备系统 | realme RMX3820，Android 16 / API 36 |
| Pokemon Champions | v1.1.4，versionCode 3144，UID 10112，targetSdk 36 |
| 游戏播放捕获标志 | 包信息包含 `ALLOW_AUDIO_PLAYBACK_CAPTURE`；这只说明策略允许，不能代替真实 PCM 探针 |
| 助手 | v1.1.0，versionCode 5，targetSdk 36 |
| H.264 | `c2.qti.avc.encoder`，硬件加速，支持 Surface，目标参数通过 |
| AAC | `c2.qti.aac.hw.encoder`，硬件加速，48 kHz / 2 声道 / 96 kbps 目标通过 |
| 空闲 PSS / RSS | 79,102 KB / 289,736 KB |
| 热状态 | 0；电池约 31.1 C，机身约 31.86 C |
| 游戏帧统计 | 本次 `dumpsys gfxinfo` 返回应用转储失败，报告保留原始输出并标记为不可用 |

PSS、RSS、CPU 和温度只是单次无录屏快照，不是 Phase 5 的性能结论，也不能用于承诺长时间录制开销。

为保证基线版本正确，本次还完成了以下检查：

- 双 ABI Debug 构建和 Android 单元测试通过。
- `arm64-v8a` Debug APK 为 77.57 MiB，`x86_64` Debug APK 为 111.76 MiB；每个 APK 只含自己的单一 ABI。
- 手机上原 v1.0.1 使用正式签名，未用 Debug 包强制覆盖，也未卸载用户数据。
- 改用已经发布的同签名 v1.1.0 ARM64 APK 做保留数据升级；APK SHA-256 为 `38e80b0169e6bb1e9cb4c411f4e3cc2330f92d49d0bea5ab01f5fabd083e2ede`，签名证书 SHA-256 为 `671b45190a9dac81a2747355cb9f10703503f1302eaf3e59582a282dd827eef8`。
- 安装后 `dumpsys package` 确认 `versionName=1.1.0`、`versionCode=5`。

## 3. 如何验证当前实现

### 3.1 采集真机基线

在仓库根目录连接并解锁 Android 设备，然后运行：

```powershell
manual-test-scripts\collect-replay-phase0-baseline.cmd -Serial <adb序列号>
```

只有一台在线设备时也可以省略 `-Serial`。如果同时连接手机、平板或模拟器，脚本会拒绝自动选择。

成功输出必须包含：

- 设备型号、Android/API、游戏版本和游戏 UID；
- `Hardware AVC target encoder: True`；
- `Hardware AAC target encoder: True`，或者明确显示设备不满足；
- `report.md` 与 `report.json` 的绝对路径。

打开本次目录并检查：

- `report.md`：适合人工阅读的摘要；
- `report.json`：后续自动比较两台设备或多次快照的数据；
- `media-player.txt`：完整编码器能力原文；
- `package-*.txt`：游戏和助手包信息原文；
- `assistant-meminfo.txt`、`top.txt`、`thermal.txt`：资源和温度原文；
- `game-gfxinfo-framestats.txt`：游戏帧统计或失败原因。

### 3.2 验证 Android 测试与双 ABI 产物

运行项目的双 ABI Debug 构建：

```powershell
npm.cmd run android:assemble
```

然后确认只生成：

```text
android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
android-app/app/build/outputs/apk/debug/app-x86_64-debug.apk
```

两个 APK 都不应是 universal，也不应包含 `armeabi-v7a` 或 `x86`。

## 4. 尚未实现与下一段

以下项目仍保持未完成，不能把当前分支描述成“已经支持对局录屏”：

- OPD2409 的同一套设备、包、UID、编码器和空闲基线；
- v1.1.0 识别、横竖屏、悬浮菜单和停止流程的完整真机回归记录；
- 10 秒单应用画面探针，以及悬浮球、菜单、面板、系统栏、通知和其他应用是否被排除的结论；
- 游戏有声、游戏静音、其他应用播放三组真实 PCM 对照；
- 从悬浮层打开权限 Activity，以及 ColorOS 阻止时的通知点击兜底；
- MP4、EGL、MediaCodec、AudioRecord、MediaStore、三种会话模式和录制 UI。

下一小段应优先实现“10 秒单应用画面隔离探针 + 游戏 UID 白名单 PCM 探针”。只有这两个前置闸门在 RMX3820 上得到真实结论后，才进入 Phase 1 会话模式和延迟初始化。
