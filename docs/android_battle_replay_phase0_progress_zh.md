# Android 对局回放 Phase 0 当前实现与验证

日期：2026-07-17

分支：`feature/battle-replay-phase-0`

计划来源：[`android_battle_replay_recording_plan_zh.md`](android_battle_replay_recording_plan_zh.md)

## 1. 本次完成范围

当前已完成 Phase 0 的前两小段：可重复设备基线采集，以及 Debug 专用的“10 秒单应用画面隔离 + 游戏 UID 播放音频”探针。RMX3820 已得到三组真机结论；尚未开始正式 MP4、编码器、MediaStore、会话模式或现有 `OverlayCaptureService` 的重构。

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
  - Debug 包使用 `.debug` applicationId 和 `-debug` versionName 后缀，可与正式签名的 v1.1.0 共存，不覆盖正式版数据。
- `android-app/app/src/debug/AndroidManifest.xml`
  - 只给 Debug 探针声明 `RECORD_AUDIO`、游戏包查询、探针 Activity 和 `mediaProjection` 前台服务；Release Manifest 尚未新增录音权限。
- `ReplayProbeActivity` / `ReplayProbeService`
  - 每次由用户在系统页选择“单个应用 -> Pokémon Champions”，建立一次 10 秒 `MediaProjection`。
  - 用高对比度品红/青色悬浮标记自检：标记显示在真实屏幕上，但不应出现在捕获帧中。
  - 只对白名单 UID `jp.pokemon.pokemonchampions` 建立 `AudioPlaybackCaptureConfiguration`，匹配 `GAME`、`MEDIA`、`UNKNOWN` usage；不创建麦克风输入源。
  - 提供“游戏有声、游戏静音、仅探针播放 440 Hz 对照音”三种场景。ColorOS 不接受静态 `AudioTrack`，当前使用 100 ms PCM 小块的流式循环播放。
  - 只在应用私有目录写入 JSON 统计，不保存捕获帧或原始 PCM，也不生成 MP4。
- `ReplayProbeAnalysisTest`
  - 覆盖数字静音、非静音 PCM、隔离标记检测和场景 wire name。

## 2. RMX3820 实测结果

### 2.1 无录屏基线

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
- 当前 `arm64-v8a` Debug APK 为 77.62 MiB，SHA-256 为 `ae2fdbadccb9e666f95d0ba4cb73906dc8b90ce43b8c2719e5b64867a45a95d6`；`x86_64` Debug APK 为 111.80 MiB，SHA-256 为 `3c5a2ada8331158da51f3f544f15f42b2e2308b16d4081d444b88f150bfdf510`。每个 APK 只含自己的单一 ABI。
- 手机上原 v1.0.1 使用正式签名，未用 Debug 包强制覆盖，也未卸载用户数据。
- 改用已经发布的同签名 v1.1.0 ARM64 APK 做保留数据升级；APK SHA-256 为 `38e80b0169e6bb1e9cb4c411f4e3cc2330f92d49d0bea5ab01f5fabd083e2ede`，签名证书 SHA-256 为 `671b45190a9dac81a2747355cb9f10703503f1302eaf3e59582a282dd827eef8`。
- 安装后 `dumpsys package` 确认 `versionName=1.1.0`、`versionCode=5`。

### 2.2 10 秒隔离和音频对照

探针使用独立 Debug 包 `com.crazylei12.pokemonchampionsassistant.debug`。游戏 UID 为 `10112`；设备媒体音量为 `4/16`。三次均选择 Pokémon Champions 单应用，结果如下：

| 场景 | 时长 | 画面 | 游戏 UID PCM | 结论 |
| --- | ---: | --- | --- | --- |
| 游戏有声，游戏内叫声/音效/音乐均为 10 | 10,033 ms | 348 帧；分析 48 帧；标记像素 0；隔离通过 | 954,368 样本；98.04% 非零；峰值 8,580；-23.25 dBFS | 游戏内部声音可捕获 |
| 游戏静音，三项均为 0 | 10,022 ms | 348 帧；分析 47 帧；标记像素 0；隔离通过 | 954,368 样本；全部为数字零 | 静音对照通过 |
| 游戏静音，Debug 探针 UID 播放 440 Hz | 10,005 ms | 308 帧；分析 46 帧；标记像素 0；隔离通过 | 950,272 样本；全部为数字零；`otherAppTonePlayed=true` | 其他 UID 声音未混入 |

这三组数据明确了 RMX3820 上的两个前置闸门：单应用共享排除了助手悬浮层，Pokemon Champions 的播放声音可以按游戏 UID 捕获，并且游戏静音或只有其他 UID 发声时不会误收。

这不是正式回放验收：探针读取 RGBA 帧并统计 PCM 能量，没有编码 H.264/AAC，也没有生成 MP4。状态栏、通知、其他应用、悬浮菜单和伤害面板仍要在后续设备矩阵中逐项验证。

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

### 3.2 运行 Debug 真机探针

先构建 ARM64 Debug 包并安装到指定手机：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/run-gradle.ps1 :app:testDebugUnitTest :app:assembleDebug -PandroidAbis=arm64-v8a
adb -s <adb序列号> install -r android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s <adb序列号> shell am start -n com.crazylei12.pokemonchampionsassistant.debug/com.crazylei12.pokemonchampionsassistant.replayprobe.ReplayProbeActivity
```

首次运行需要：

1. 允许通知和录音权限；录音权限只用于 Android 播放音频捕获，不读取麦克风。
2. 点击 `Open overlay permission`，在 ColorOS 全局列表中找到 `Replay Phase 0 Probe` 并允许显示在其他应用上层。
3. 在游戏“标题画面 -> 菜单 -> 选项 -> 声音”中设置本次场景：有声时三项为 10，静音时三项为 0。
4. 点击对应场景，在系统投屏页选择“单个应用”，再选择 Pokémon Champions，保持 10 秒。
5. 横屏时系统确认框按钮可能在屏幕下方；在确认框内向上滑动即可显示“取消/下一步”。

读取最近一次 JSON：

```powershell
adb -s <adb序列号> shell run-as com.crazylei12.pokemonchampionsassistant.debug cat files/replay-probe/latest.json
adb -s <adb序列号> shell run-as com.crazylei12.pokemonchampionsassistant.debug ls -lt files/replay-probe
```

每组成功条件：

- `durationMs` 约为 10,000；
- `video.framesAnalyzed > 0`、`markerShown=true`、`markerDetected=false`、`singleAppIsolationPass=true`；
- 有声组 `audio.signalDetected=true`；
- 静音组 `audio.signalDetected=false`；
- 其他应用组 `audio.otherAppTonePlayed=true` 且 `audio.signalDetected=false`；
- JSON 不含 `error` 字段。

探针报告保存在 Debug 应用私有目录。卸载 Debug 包会删除这些报告，但不会影响正式版助手数据。

### 3.3 验证 Android 测试与双 ABI 产物

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
- OPD2409 上的同一套 10 秒画面隔离和三组 PCM 对照；
- RMX3820 上悬浮菜单、伤害面板、状态栏、通知和切换其他应用的逐项隔离矩阵；
- 从悬浮层打开权限 Activity，以及 ColorOS 阻止时的通知点击兜底；
- MP4、EGL、MediaCodec、AudioRecord、MediaStore、三种会话模式和录制 UI。

下一小段可以开始 Phase 1 的“会话模式/状态机 + 按模式延迟初始化”纯 Kotlin 和服务边界工作；同时保留 OPD2409 及 RMX3820 完整隔离矩阵为 Phase 0 的未完成验收项。未完成双设备验证前，不结束 Phase 0，也不把探针权限直接并入 Release。
