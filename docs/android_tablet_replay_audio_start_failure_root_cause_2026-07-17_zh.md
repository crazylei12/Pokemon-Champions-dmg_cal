# Android 平板录屏准备后返回悬浮球问题根因与修复说明（2026-07-17）

## 1. 文档目的

本文专门记录 OPPO 平板 OPD2409 上“点击开始录屏，经过录屏准备后没有开始录制，直接回到对局助手悬浮球”的问题，包括：

- 用户实际看到的现象；
- 手机正常、平板失败的设备差异；
- 如何证明声音预检、画面编码和单应用投屏并未失败；
- `AudioRecord`、AudioFlinger 和应用状态机之间的完整故障链；
- 为什么只增加等待和重试仍然无效；
- 最终代码如何把预检与正式录屏彻底分离；
- 自动测试、平板真机 MP4、相册发布和双机安装证据；
- “相册第一张图”提示为何没有被并入已确认根因；
- 后续长录、声音能量和异常场景仍需补充的范围。

本文是本次设备差异问题的根因档案。当前录屏操作说明仍以 [Android 对局回放录屏 AI 使用与维护指南](android_battle_replay_ai_usage_zh.md) 为准；阶段范围以 [Android 对局回放录屏方案](android_battle_replay_recording_plan_zh.md) 为准。

## 2. 结论摘要

本次问题不是以下原因造成的：

- 不是游戏没有声音：平板预检读到了大量非零 PCM；
- 不是录音权限被拒绝：播放捕获实例能够成功开始预检；
- 不是游戏 UID 不正确：捕获白名单命中 Pokémon Champions 的 UID `10348`；
- 不是 H.264 编码器无法创建：视频编码器和 EGL 输入 Surface 已准备成功；
- 不是单应用投屏授权失效：同一个 MediaProjection 会话持续存在，失败后识别 Surface 也能恢复；
- 不是存储空间或相册发布失败：失败发生在正式时间线启动前，损坏的 pending 项按设计被丢弃。

应用层已确认的根因是：

> 旧实现让声音预检和正式 AAC 录制复用同一个 `AudioRecord`。OPD2409 / ColorOS 16 上，这个播放捕获实例一旦完成预检并执行 `stop()`，随后便无法再次启动；AudioFlinger 在重建播放捕获音频 Patch 时持续返回 `-38`。RMX3820 可以容忍这一生命周期，因此问题只在平板暴露。

最终修复采用两层保护：

1. 声音预检使用独立的短生命周期 `GameAudioCapture`，预检完成后立即 `close()` 和 `release()`；
2. 正式 MP4 使用全新的 `GameAudioCapture`，并在启动工作线程前同步确认 `AudioRecord.recordingState`，短暂系统竞争只允许有界重试。

修复提交：

`799434f9c5153e36a4245d41eaf52ebd833678d7 fix: restart replay audio capture on ColorOS`

## 3. 用户现象与影响范围

### 3.1 用户可见现象

用户报告：

- 手机上录屏功能正常；
- 平板点击“开始录屏”后会显示录屏准备过程；
- 随后没有出现红点和录屏计时；
- 界面回到“对局助手”悬浮按钮；
- 另有一次出现了疑似相册第一张图相关的系统提示。

原始失败后的 App 状态为：

- 对局助手 Service 仍运行；
- MediaProjection 仍运行；
- 悬浮球仍存在；
- 录屏状态回到“未录制”；
- pending MP4 被清理，没有发布损坏文件；
- 主界面提示为“回放保存失败；损坏的 pending 文件已清理；对局助手继续运行”。

因此这是“录屏子功能无法在平板启动”，不是整个对局助手崩溃或投屏会话退出。

### 3.2 设备对照

| 项目 | 手机 | 平板 |
| --- | --- | --- |
| 序列号 | `6465e08` | `f522cec8` |
| 型号 | RMX3820 | OPD2409 |
| Android | 16 / API 36 | 16 / API 36 |
| ABI | `arm64-v8a` | `arm64-v8a` |
| App 包名 | `com.crazylei12.pokemonchampionsassistant.debug` | 同左 |
| App 版本 | `1.1.0-debug (5)` | 同左 |
| 原始结果 | 正常开始并保存 | 预检后正式音频启动失败 |

相同 APK、相同 API、相同游戏包和相同播放捕获逻辑在两台设备上结果不同，使排查重点从权限和业务状态转向 OEM 音频生命周期差异。

平板系统属性为 `ro.build.version.oplusrom=V16.0.0`，构建标识为 `OPD2409_16.0.3.502(CN01)`。

### 3.3 平板测试时的关键环境

| 项目 | 值 |
| --- | --- |
| 屏幕捕获规格 | `3392 × 2400`，density `420`，rotation `3` |
| 游戏包名 | `jp.pokemon.pokemonchampions` |
| 游戏 UID | `10348` |
| 助手 UID | `10361` |
| 投屏模式 | Android 16 单应用投屏，仅选择 Pokémon Champions |
| 音频来源 | `AudioPlaybackCaptureConfiguration`，按游戏 UID 和 GAME/MEDIA/UNKNOWN usage 过滤 |
| 麦克风 | 未配置、未读取 |
| 输出 | H.264 960×540、目标 24 fps；有声路径使用 AAC-LC 48 kHz |

## 4. 修复前的录屏启动链路

修复前 `ReplayRecorder.prepare()` 的音频链路为：

```text
创建一个 GameAudioCapture
  -> AudioRecord.startRecording()
  -> 读取约 2.5 秒 PCM，判断游戏内部声音是否非静音
  -> AudioRecord.stop()
  -> 保留同一个 GameAudioCapture
  -> 创建 pending MP4、muxer、H.264 编码器和 EGL 路由
  -> 同一个 AudioRecord 再次 startRecording()
  -> 启动 PCM 读取线程和 AAC 编码线程
```

这套设计的原意是：预检和正式录制使用完全相同的 UID、usage、声道数和播放捕获配置，避免预检通过后正式录制切换到不同音频来源。

问题在于它隐含了一个设备假设：

> 同一个播放捕获 `AudioRecord` 在 `start -> stop` 后仍可以再次 `start`。

RMX3820 上该假设成立，OPD2409 上不成立。

## 5. 原始问题的精确复现

### 5.1 操作步骤

1. 用 `adb -s f522cec8` 明确选择 OPD2409；
2. 启动 Debug App 的对局助手；
3. 在系统投屏页选择“单个应用 -> Pokémon Champions”；
4. 保持游戏大厅前台并确保游戏持续播放音乐或音效；
5. 点击悬浮球；
6. 点击“开始录屏”；
7. 等待录屏准备完成；
8. 观察悬浮球是否变为红色计时状态；
9. 同步抓取应用、`AudioRecord` 和 `AudioFlinger` 日志。

### 5.2 稳定复现结果

- 音频预检约 2.5 秒后完成；
- pending MP4 已创建；
- H.264 编码器和 EGL Surface 已准备；
- 正式音频启动失败；
- 录屏没有进入稳定运行状态；
- 原识别 Surface 恢复；
- 悬浮助手继续存在。

## 6. 原始失败的证据链

### 6.1 声音预检成功，不是“游戏无声”

原始失败时间线中的预检摘要为：

```text
07-17 18:59:30.814 I/ReplayRecorder: Audio preflight:
uid=10348, channels=2, samples=241664,
nonZeroRatio=0.9656961731991526,
peak=6381, dbfs=-29.911730234973692
```

证据含义：

- `uid=10348` 与平板上 Pokémon Champions 的当前 UID 一致；
- 预检读取了 `241,664` 个采样；
- 非零比例约 `96.57%`；
- 峰值 `6381`；
- `dbfs` 为有限值而不是负无穷。

因此预检阶段实际取得了非静音的游戏播放 PCM。问题发生在预检之后。

### 6.2 视频管线成功，不是 H.264 或 EGL 初始化失败

同一次操作继续出现：

```text
Capture surface switched to replay:
CaptureBufferSpec(width=3392, height=2400, densityDpi=420, rotation=3)

Replay pipeline prepared:
codec=c2.qti.avc.encoder, audio=true, channels=2, gameUid=10348
```

这证明：

- 唯一 VirtualDisplay 已成功从识别 Surface 切换到录屏 Surface；
- Qualcomm H.264 编码器 `c2.qti.avc.encoder` 已创建；
- EGL 路由已准备接收单应用画面；
- muxer 预期视频和 AAC 双轨；
- 失败点不在画面编码准备阶段。

### 6.3 正式音频第二次启动在 AudioFlinger Patch 层失败

正式录制尝试再次启动同一个 `AudioRecord` 时，系统日志为：

```text
07-17 18:59:31.020 AudioRecord start
07-17 18:59:31.027 AudioFlinger::PatchPanel createAudioPatch status -38
07-17 18:59:31.028 AudioFlinger startInput failed, status -38
07-17 18:59:31.030 AudioRecord start status -38
```

关键点是 `AudioRecord.startRecording()` 的 Java 调用没有把该系统状态直接抛成异常。旧实现随后仍然启动了 PCM 读取线程。

### 6.4 读取线程得到 `-3`，把启动失败表现成运行时失败

PCM 线程第一次读取得到：

```text
java.lang.IllegalStateException: AudioRecord read failed: -3
    at GameAudioCapture.captureLoop(GameAudioCapture.kt:184)
```

`-3` 对应 `AudioRecord.ERROR_INVALID_OPERATION`。此时 AAC 线程和视频收尾逻辑已经被拉起，导致用户看到的是“准备后又失败”，而不是一个同步的“音频无法启动”。

### 6.5 失败后清理和识别恢复正常

日志随后出现：

```text
Replay runtime failed
Replay finalization failed
Capture surface restored to recognition:
CaptureBufferSpec(width=3392, height=2400, densityDpi=420, rotation=3)
```

因此原有解耦边界是有效的：

- pending 文件没有被错误发布；
- MediaProjection 没有被停止；
- VirtualDisplay 没有被释放；
- 悬浮球和识别能力可以恢复。

需要修复的是“正式音频采集如何启动”，不是回退录屏与识别的解耦设计。

## 7. 根因判定

### 7.1 已确认的应用层根因

`GameAudioCapture` 内部只持有一个 `AudioRecord`。`preflight()` 对其执行：

```text
startRecording -> blocking read -> stop
```

`start()` 随后再次对同一个对象执行：

```text
startRecording -> capture thread -> AAC encoder thread
```

OPD2409 上第二次 `startRecording()` 无法重新建立播放捕获 Patch。应用没有在启动线程前检查 `recordingState`，所以系统启动失败被推迟到第一次 `read()` 才暴露。

应用层根因可以表述为：

> 录屏实现错误地把“预检用采集实例可在停止后直接复用”当成跨设备稳定条件，并且没有同步验证正式音频启动结果。

### 7.2 对 ColorOS 内部机制的有限推断

从系统日志可以确认：

- 第一次启动预检时 `createAudioPatch status 0`；
- 预检停止时播放捕获 Patch 被释放；
- 同一对象的后续启动始终在 `PatchPanel` 返回 `-38`；
- 换成新对象后，新的 `createAudioPatch status 0`。

因此可以合理推断 ColorOS 16 的播放捕获实现存在“停止后的实例无法可靠重启”的状态限制或 OEM 缺陷。

但项目没有 ColorOS AudioFlinger 私有实现源码，所以本文不把更深层的厂商内部原因写成已证实事实。工程修复只依赖可重复验证的外部行为：旧对象不可重启，新对象可启动。

### 7.3 为什么手机没有暴露问题

RMX3820 对同一实例的 `start -> stop -> start` 流程能够正常工作，因此旧实现长期看似正确。

这说明：

- 手机成功不能证明该生命周期在所有 ColorOS 设备上都稳定；
- JVM 单元测试无法模拟厂商 AudioFlinger Patch 行为；
- 播放捕获功能必须在每种目标真机上至少完成一次“预检 -> 正式录制 -> 保存”的闭环。

## 8. 第一轮尝试：增加冷却和有界重试

### 8.1 修改目的

第一轮先补上同步启动确认，并尝试处理可能的 AudioFlinger 释放竞态：

- 记录预检 `stop()` 完成时间；
- 正式启动前至少等待到 750 ms 冷却窗口；
- 检查 `record.recordingState == RECORDSTATE_RECORDING`；
- 失败后再等待 500 ms 和 1,000 ms，共最多 3 次；
- 三次均失败时在 `ReplayRecorder.start()` 内同步抛错，不启动 PCM/AAC 工作线程。

这一步修正了“失败发现太晚”的问题，但没有解决平板本身的不可重启状态。

### 8.2 平板实测结果

第一轮修复的关键时间线为：

```text
07-17 19:07:12.468 Audio preflight succeeded
07-17 19:07:13.219 start attempt 1
07-17 19:07:13.233 start status -38
07-17 19:07:13.735 start attempt 2
07-17 19:07:13.748 start status -38
07-17 19:07:14.752 start attempt 3
07-17 19:07:14.768 start status -38
07-17 19:07:14.775 Playback capture did not enter the recording state after 3 attempts
07-17 19:07:14.814 Capture surface restored to recognition
```

三次重试跨越约 1.55 秒，距离预检停止已超过 2 秒，结果仍然完全一致。

### 8.3 结论

这个结果排除了“只需要再等几百毫秒”的简单竞态解释：

- 有界重试应该保留，用于处理真正的瞬时系统竞争；
- 但不能继续复用已经完成预检的 `AudioRecord`；
- 最终方案必须更换正式录制实例。

## 9. 最终修复设计

### 9.1 预检实例和正式实例分离

`ReplayRecorder.prepare()` 现在执行：

```text
创建 preflightCapture
  -> 执行约 2.5 秒 PCM 预检
  -> finally 中 close/release preflightCapture
  -> 如果没有声音，返回 AudioUnavailable
  -> 如果检测到声音，创建全新的 production GameAudioCapture
  -> 创建 pending MP4、muxer、视频编码器和 EGL 路由
```

正式开始时只有新的 production 实例进入：

```text
AudioRecord start + 状态确认
  -> PCM capture thread
  -> AAC encoder thread
  -> EGL router startRecording
  -> 录屏状态变为 STARTED
```

预检仍然使用相同游戏 UID、usage 白名单、采样率和声道候选；分离的是系统采集对象生命周期，不是声音来源。

### 9.2 正式启动必须同步确认

`GameAudioCapture.start()` 不再仅调用一次 `record.startRecording()` 就假定成功，而是：

1. 调用 `startRecording()`；
2. 立即读取 `recordingState`；
3. 只有进入 `RECORDSTATE_RECORDING` 才允许启动工作线程；
4. 未进入录制状态时停止残留状态并按有限延迟重试；
5. 所有尝试失败后同步抛出 `IllegalStateException`。

这样系统错误不会再拖延到 `captureLoop()` 的第一次 `read()`。

### 9.3 启动失败的状态边界

如果新的正式实例仍无法启动：

- AAC 和 PCM 线程不进入运行状态；
- EGL 正式录制时间线不开始；
- pending MediaStore 项被丢弃；
- 识别 Surface 恢复；
- 录屏状态回到未录制；
- 对局助手 Service、MediaProjection、悬浮球和识别功能继续运行。

这符合“录屏是悬浮助手中的独立子功能”的产品边界。

### 9.4 修复前后对比

| 项目 | 修复前 | 修复后 |
| --- | --- | --- |
| 预检采集对象 | 与正式录制共用 | 独立对象，完成后释放 |
| 正式采集对象 | 已执行过 start/stop | 全新创建 |
| 正式启动判定 | 默认调用成功 | 同步检查 recordingState |
| 瞬时系统竞争 | 无处理 | 有界重试 |
| 启动失败发现点 | PCM 线程第一次 read | 工作线程启动前 |
| 失败后的 MP4 | 进入失败收尾后丢弃 | 正式时间线前直接丢弃 |
| 对局助手 | 保持运行 | 保持运行 |

## 10. 实际代码改动

| 文件 | 改动 |
| --- | --- |
| `android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/replay/ReplayRecorder.kt` | 预检实例在 `finally` 中关闭；检测到声音后重新创建正式采集实例 |
| `android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/replay/GameAudioCapture.kt` | 新增启动状态确认、冷却计算、有界重试和失败日志；工作线程只在确认录制状态后启动 |
| `android-app/app/src/test/java/com/crazylei12/pokemonchampionsassistant/replay/ReplayCoreTest.kt` | 新增“重试后成功”和“达到次数上限后同步失败”测试 |
| `docs/android_battle_replay_ai_usage_zh.md` | 增加 OPD2409 故障摘要、排障方法和当前验证边界 |

相关提交：

- `87ad37a3c00934edbc6afba05a39aa65a88b4e87`：录屏与识别功能解耦；
- `799434f9c5153e36a4245d41eaf52ebd833678d7`：修复 ColorOS 播放捕获重启失败。

## 11. 自动验证

### 11.1 单元测试

执行：

```powershell
cd D:\crazylei12\pokemon-champions-assistant\android-app
.\gradlew.bat :app:testDebugUnitTest
```

结果：

- `85` 项测试；
- `0` 失败；
- `0` 错误；
- `0` 跳过。

新增两项核心回归：

- `audio capture start waits and retries until the recording state is confirmed`：第一次未进入录制状态，第二次成功，验证等待、重试和 reset 次数；
- `audio capture start fails synchronously after bounded retries`：三次均失败时同步抛错，验证不会无限等待。

单元测试只能证明重试控制流和状态门槛，不能模拟 OPD2409 的 AudioFlinger，最终判定仍以真机为准。

### 11.2 双 ABI Debug 构建

执行：

```powershell
cd D:\crazylei12\pokemon-champions-assistant
npm.cmd run android:assemble
```

构建和项目 APK 门禁通过：

| ABI | 文件 | 大小 | SHA-256 | 用途 |
| --- | --- | ---: | --- | --- |
| `arm64-v8a` | `app-arm64-v8a-debug.apk` | 81,673,902 bytes | `DB32A76260B5977F4FEFFD409411FFAB773BDD95D9272DE12595F610863F1268` | 手机和平板 |
| `x86_64` | `app-x86_64-debug.apk` | 117,465,143 bytes | `C29E99236E7FBEB3C07FCE6838D825AEF100FB20E28B602F84097AD565CC43D7` | 模拟器静态产物，本轮未安装 |

ARM64 包使用两台物理设备原有的 Debug 证书覆盖安装，证书 SHA-256 为：

`F3BE0F6C4C45AB36EB2C42E8BBC090D8C18C5AFBB1F3464964113D582721CFD7`

## 12. OPD2409 最终真机验证

### 12.1 第一次正式录屏启动成功

最终修复后的关键日志为：

```text
07-17 19:09:17.644 ReplayRecorder: Audio preflight:
samples=241664, nonZeroRatio=0.9659816935911016,
peak=7562, dbfs=-25.30941120419071

07-17 19:09:17.645 create fresh production AudioRecord
07-17 19:09:17.775 Replay pipeline prepared
07-17 19:09:17.874 production AudioRecord start
07-17 19:09:17.997 AudioRecord start return status 0
```

与失败时间线相比，正式采集使用新实例后第一次启动即返回 `0`，没有触发重试，也没有出现 `read failed: -3`。

### 12.2 同一投屏会话重复启停

平板在同一个 MediaProjection 会话内完成两次录屏：

| 次数 | MediaStore ID | 文件名 | 时长 | 大小 | pending | 路径 |
| ---: | ---: | --- | ---: | ---: | ---: | --- |
| 1 | `1000012863` | `pokemon-champions_20260717_190917.mp4` | 111,511 ms | 8,940,540 bytes | `0` | `DCIM/Pokemon Champions Replays/` |
| 2 | `1000012867` | `pokemon-champions_20260717_191235.mp4` | 16,906 ms | 1,365,200 bytes | `0` | `DCIM/Pokemon Champions Replays/` |

第二次启动日志再次证明“预检对象关闭 -> 新正式对象启动”的路径可重复：

```text
07-17 19:12:35.919 Audio preflight succeeded
nonZeroRatio=0.957714016154661, peak=7183, dbfs=-23.760202402346362

07-17 19:12:36.055 Replay pipeline prepared
07-17 19:12:36.179 production AudioRecord start
07-17 19:12:36.318 AudioRecord start return status 0
```

第二次结束后：

- Capture Surface 再次恢复到 recognition；
- MediaProjection 仍由助手持有；
- 悬浮球仍存在；
- 可以继续打开识别和伤害面板；
- 没有新的 pending 垃圾文件。

### 12.3 首段 MP4 成品检查

使用 Debug 成品检查器分析 MediaStore ID `1000012863`，输出目录：

```text
/sdcard/Android/data/com.crazylei12.pokemonchampionsassistant.debug/files/
replay-probe/artifact-inspection-1784286715111/
```

`inspection.json` 结果：

| 项目 | 结果 |
| --- | --- |
| `ok` | `true` |
| 容器 MIME | `video/mp4` |
| 容器时长 | `111,511 ms` |
| 视频 | H.264 / AVC，960×540 |
| 视频样本数 | `2,614` |
| 视频首/末 PTS | `0 / 111,416,666 us` |
| 音频 | AAC / `audio/mp4a-latm`，48 kHz，双声道，96 kbps |
| 音频样本数 | `5,227` |
| 音频首/末 PTS | `0 / 111,489,666 us` |
| 音视频末 PTS 差 | `73,000 us`，即 `73 ms` |
| 五点帧抽取 | 开始、1/4、1/2、2/3、接近结尾均成功 |

五张抽取帧都只包含 Pokémon Champions 画面。测试期间真实屏幕上曾展开悬浮菜单和伤害面板，但对应成品帧没有助手 UI，证明单应用隔离仍有效。

### 12.4 声音证据边界

本轮已确认：

- 预检 PCM 明确非静音；
- 正式播放捕获实例成功进入录制状态；
- MP4 中存在连续 AAC 双声道轨道和 `5,227` 个音频样本；
- 音频时间线从 `0` 延伸到文件末尾附近。

但 Debug 检查器不会把 AAC 解码成 PCM，本机当时也没有 FFmpeg，因此本轮没有独立计算成品 AAC 的音频能量，也没有把“存在 AAC 轨”写成“已经人工试听确认可听”。

完整声音验收仍应补至少一项：

- 在平板相册中实际播放并人工试听；或
- 用 FFmpeg 解码后检查 `max_volume` 不是 `-inf dB`。

## 13. 识别、伤害面板和录屏解耦验证

本轮实测中曾在录屏期间打开伤害面板，随后收起并继续录制。结果为：

- 伤害面板正常显示；
- 录屏计时继续；
- 收起面板后录屏仍继续；
- 结束录屏只保存 MP4，没有结束对局助手；
- 成品五点帧中没有伤害面板或悬浮菜单。

这同时验证：

1. 录屏和伤害功能在状态机上独立；
2. 助手 Overlay 没有被录入单应用视频；
3. 结束录屏后唯一 VirtualDisplay 能切回识别 Surface；
4. 本次音频修复没有破坏上一提交的深度解耦行为。

## 14. “相册第一张图”提示的调查结论

### 14.1 当前能确认的事实

对 App 源码检索了以下入口：

- `ACTION_PICK`；
- `ACTION_GET_CONTENT`；
- `ACTION_OPEN_DOCUMENT`；
- `MediaStore.Images`；
- `image/*`；
- 相册或照片选择 Activity。

录屏开始路径没有上述调用，也不会读取系统相册中的第一张图片。

App 与相册/播放器相关的主动行为只有：

- 使用 `MediaStore.Video` 把最终 MP4 发布到 `DCIM/Pokemon Champions Replays/`；
- 保存完成后创建一条通知；
- 只有用户点击该通知时，才通过 `ACTION_VIEW` 和 MIME `video/mp4` 打开刚保存的回放 URI。

通知不会在录屏开始时执行，也不会自动打开相册。

### 14.2 日志关联结果

排查日志中能看到历史系统条目涉及 `CapturePhotoActivity`，但其：

- PID 不是助手进程；
- 时间早于本次 18:59 的录屏失败；
- 没有与“点击开始录屏 -> AudioRecord -38”形成同一时间线；
- 本轮修复后两次录屏均未再次出现该提示。

### 14.3 当前判定

“相册第一张图”暂时作为未复现的系统侧/其他进程现象保留，不能写成已确认的 App 缺陷，也不能把它当作 `AudioRecord -38` 的原因。

如果后续再次出现，应立即保留：

- 出现提示的完整截图；
- 提示窗口的包名、Activity 和 bounds；
- 前后至少 30 秒的全缓冲区 logcat；
- 当时是否刚进入系统单应用投屏选择器；
- 是否点击过“回放已保存”通知。

只有这些证据能把提示准确归到助手、ColorOS 系统 UI、相册 App 或其他进程。

## 15. 安装与设备最终状态

修复后的 ARM64 Debug APK 已覆盖安装到两台物理设备，并保留原应用数据：

| 设备 | 安装结果 | 包版本 | 已安装 base.apk SHA-256 |
| --- | --- | --- | --- |
| `6465e08` / RMX3820 | `Success` | `1.1.0-debug (5)` | `DB32A76260B5977F4FEFFD409411FFAB773BDD95D9272DE12595F610863F1268` |
| `f522cec8` / OPD2409 | `Success` | `1.1.0-debug (5)` | `DB32A76260B5977F4FEFFD409411FFAB773BDD95D9272DE12595F610863F1268` |

平板测试结束时：

- 前台停留在 Pokémon Champions 主界面；
- 录屏已结束；
- 对局助手和 MediaProjection 仍运行；
- 两个 MP4 已发布到相册目录；
- `stay_on_while_plugged_in=7`，充电时保持唤醒。

## 16. 后续回归清单

任何修改 `ReplayRecorder`、`GameAudioCapture`、MediaProjection Surface 路由或停止逻辑的提交，都应至少验证：

### 16.1 启动

- [ ] 游戏有声时，预检能得到非静音 PCM；
- [ ] 预检实例完成后被释放；
- [ ] 正式实例进入 `RECORDSTATE_RECORDING` 后才启动线程；
- [ ] OPD2409 不出现 AudioFlinger `startInput failed, status -38`；
- [ ] 悬浮球显示红点和计时后才算正式开始。

### 16.2 停止与恢复

- [ ] “结束录屏并保存 MP4”只停止录屏；
- [ ] `is_pending=0` 后相册目录可见；
- [ ] 悬浮球、识别和伤害面板继续运行；
- [ ] 同一 MediaProjection 会话允许再次开始录屏；
- [ ] 启动失败或运行时失败不发布损坏文件。

### 16.3 成品

- [ ] H.264 为 960×540，可 seek；
- [ ] 有声路径存在 AAC 48 kHz 轨；
- [ ] 解码 PCM 或人工试听证明不是数字静音；
- [ ] 五点帧全部可解码；
- [ ] 悬浮球、菜单和伤害面板不进入 MP4；
- [ ] 音视频末 PTS 差不超过 100 ms。

### 16.4 设备矩阵

- [x] RMX3820 既有有声录屏正常；
- [x] OPD2409 完成两次短录启停和保存；
- [ ] OPD2409 完成 10 分钟有声录屏；
- [ ] OPD2409 完成 30 分钟 Phase 5 性能录屏；
- [ ] OPD2409 完成游戏静音与其他 UID 对照；
- [ ] Release ARM64 包完成两设备最终验收。

## 17. 复查命令

### 17.1 过滤关键日志

```powershell
adb -s f522cec8 logcat -c
# 在平板上点击“开始录屏”并等待结果
adb -s f522cec8 logcat -d -v threadtime |
  Select-String -Pattern "ReplayRecorder|GameAudioCapture|OverlayCaptureService|AudioRecord|AudioFlinger"
```

### 17.2 查询已发布回放

```powershell
adb -s f522cec8 shell content query `
  --uri content://media/external/video/media `
  --projection _id:_display_name:relative_path:duration:is_pending:_size
```

合格项至少满足：

- `relative_path=DCIM/Pokemon Champions Replays/`；
- `duration > 0`；
- `_size > 0`；
- `is_pending=0`。

### 17.3 运行 Debug 成品检查器

```powershell
$replayId = "<MediaStore ID>"
$package = "com.crazylei12.pokemonchampionsassistant.debug"

adb -s f522cec8 shell am start -W `
  -n "$package/com.crazylei12.pokemonchampionsassistant.replayprobe.ReplayProbeActivity" `
  -a "$package.VERIFY_REPLAY_ARTIFACT" `
  -d "content://media/external_primary/video/media/$replayId"
```

### 17.4 核对投屏和充电常亮

```powershell
adb -s f522cec8 shell dumpsys media_projection
adb -s f522cec8 shell settings get global stay_on_while_plugged_in
```

## 18. 尚未完成的边界

本次文档不能被解读为 Phase 3 或 Phase 5 已整体退出，仍未完成：

- OPD2409 10 分钟有声长录；
- 独立启停架构下的 30 分钟性能复测；
- 游戏静音、其他 UID 播放和显式无声降级对照；
- 成品 AAC 解码能量或人工试听记录；
- 低空间、严重热状态、锁屏、系统投屏芯片停止和进程重建；
- Release 签名 APK 的最终双设备验收。

本次可以确认的范围只有：

> OPD2409 上“录屏准备后返回悬浮球”的确定性启动故障已经找到根因并修复；同一投屏会话完成两次正式启动、停止和 MediaStore 发布，成品轨道与五点画面检查通过，对局助手没有被录屏启停连带结束。

## 19. 最终结论

这个问题表面上像“录屏准备随机失败”，实际是明确的设备生命周期兼容问题：声音预检成功后，OPD2409 无法再次启动同一个播放捕获 `AudioRecord`。旧实现又缺少同步状态确认，使底层 `-38` 被延迟包装成读取线程 `-3` 和 MP4 收尾失败。

只增加等待和重试不能修复该设备；真正有效的边界是：

1. 预检实例用完即释放；
2. 正式录屏使用全新实例；
3. 启动状态在工作线程前同步确认；
4. 重试必须有上限；
5. 失败只回滚录屏子状态，不结束对局助手；
6. 最终结论必须由真实 MP4、MediaStore 状态和真机日志共同证明。

当前代码、单元测试、两次平板短录、成品检查器和双机安装结果均与这一结论一致。
