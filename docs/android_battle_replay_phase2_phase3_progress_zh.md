# Android 对局回放 Phase 2 / Phase 3 实现与验收记录

日期：2026-07-17

分支：`feature/battle-replay-phase-2-3`

状态：Phase 2 已完成；Phase 3 的代码实现和 RMX3820 验收已完成。OPD2409 当前不在线，因此计划要求的“双设备都有明确音频结论”仍待补，不能宣布 Phase 3 整体退出。

## 1. 本阶段交付范围

本阶段只开放“仅录屏”纵切，继续沿用 Phase 1 的单次授权、模式锁定、唯一 `VirtualDisplay` 和统一停止入口。`识别并录屏` 仍明确留在 Phase 4，不能误认为已经开放。

已完成的正式录制链路：

```text
单应用 MediaProjection
  -> 唯一 VirtualDisplay
  -> OES SurfaceTexture
  -> EglProjectionRouter
  -> 固定 960 x 540 / 24 fps H.264 Surface 编码
  -> Mp4MuxerCoordinator
  -> MediaStore pending MP4
  -> 正常收尾后发布到 Movies/Pokemon Champions Replays/

Pokemon Champions UID 播放音频
  -> AudioPlaybackCaptureConfiguration
  -> AudioRecord PCM 环形缓冲和开录前信号检测
  -> 48 kHz AAC-LC
  -> Mp4MuxerCoordinator
```

### 1.1 视频和画布

- `EglProjectionRouter` 只消费同一个投屏 Surface，不创建第二个 `VirtualDisplay`。
- 编码画布固定为 `960 x 540`、24 fps、H.264、约 1.5 Mbps。
- 横竖屏内容按比例居中，以黑边补足固定画布，不裁剪画面。
- 输入 30 fps 时由时间槽节流稳定输出 24 fps；首版实机样本暴露的 15 fps 节流错误已修复并加入单元测试。
- `onCapturedContentResize()` 和 Android 16 `VirtualDisplay.setRotation()` 继续复用现有方向语义，编码器和 muxer 不因旋转重建。
- EGL 路由带悬浮标记隔离自检；检测到整屏捕获或自检超时时拒绝开始录制。

### 1.2 音频和显式降级

- 只按 `jp.pokemon.pokemonchampions` 当前 UID 建立播放音频白名单。
- 只允许 `USAGE_GAME`、`USAGE_MEDIA` 和设备可能使用的 `USAGE_UNKNOWN`；没有创建麦克风输入路径。
- PCM 先进入有界环形缓冲，约 2.5 秒预检确认存在非静音信号后才正式开始。
- 有声路径编码为 AAC-LC、48 kHz、优先双声道、约 96 kbps；双声道创建失败时才尝试单声道。
- 预检为数字零或 AudioPlaybackCapture 不可用时，录制不会静默继续。权限 Activity 会明确提示，用户只有“取消”或“录制无声视频”两种选择。
- 无声降级重新准备纯视频 muxer，最终 MP4 不包含伪造或空白音轨。

### 1.3 文件发布和异常收尾

- `ReplayMediaStore` 先用 `IS_PENDING=1` 创建目标项，只有编码器、muxer 和文件收尾全部成功才发布。
- 正常停止、通知停止、悬浮球“结束并保存”、系统投屏芯片停止和锁屏停止都进入同一个幂等收尾入口。
- 强杀或断电不能保证补写 MP4 尾部，因此不会发布损坏文件；下次服务启动会查询并删除本应用遗留的 pending 项。
- 录制中悬浮球显示时长，前台通知可直接结束并保存；保存完成后通知给出文件名和时长。

## 2. 自动测试

新增或扩展的 JVM 测试覆盖：

- 画布等比适配、黑边和旋转映射；
- 30 fps 输入到 24 fps 输出的时间槽节流和单调 PTS；
- PCM 采样数到音频 PTS 的换算；
- 静音/非静音信号判定；
- 悬浮标记隔离判定；
- muxer 双轨启动门闩；
- 有界 PCM 环形缓冲；
- 音频预检失败到显式无声降级的状态转换；
- 原有会话模式、幂等停止和唯一 VirtualDisplay 约束。

验证命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/run-gradle.ps1 :app:testDebugUnitTest :app:assembleDebug -PandroidAbis=arm64-v8a
```

结果：15 个测试套件、77 项 Debug JVM 单元测试全部通过，0 failure、0 error、0 skipped。随后运行项目规定的双 ABI 构建器，产物目录恰好只有以下两个 APK：

| APK | 大小 | 唯一原生 ABI | SHA-256 |
| --- | ---: | --- | --- |
| `app-arm64-v8a-debug.apk` | 81,571,643 bytes | `arm64-v8a` | `2884662963FF02D04759B0247D468122A91E0905F2D93B13D71614E01A5670A4` |
| `app-x86_64-debug.apk` | 117,415,991 bytes | `x86_64` | `B9D7879647B539FFE2C55DD7929FD849257FB3D191CB3B65531FEA400533038B` |

两包的签名证书 SHA-256 都是 `f3be0f6c4c45ab36eb2c42e8bbc090d8c18c5afbb1f3464964113d582721cfd7`。最终 ARM64 包用 `adb -s 6465e08 install -r -d` 覆盖安装，没有卸载或清空应用数据；再从设备拉回已安装的 `base.apk`，其 SHA-256 与 ARM64 构建产物逐字节一致。设备包信息为 `versionName=1.1.0-debug`、`versionCode=5`、`targetSdk=36`、`primaryCpuAbi=arm64-v8a`。

Debug 包另外提供 `ReplayArtifactVerifier`，用 `MediaExtractor` 枚举轨道和样本时间戳，并用 `MediaMetadataRetriever` 解码五个时间线检查点。该入口只存在于 Debug source set，不进入 Release 产品 UI。

## 3. RMX3820 真机验收

设备：RMX3820，Android 16 / API 36，ADB 序列号 `6465e08`。游戏包为 `jp.pokemon.pokemonchampions`，设备显示游戏版本 `1.1.4`。

### 3.1 正常有声短录

修复 24 fps 节流后，MediaStore 项 `1000012265` 的检查结果：

| 项目 | 结果 |
| --- | --- |
| 容器时长 | 48,747 ms |
| 视频 | H.264，960 x 540，24 fps，1,169 个样本 |
| 视频末 PTS | 48,666,666 us |
| 音频 | AAC-LC，48 kHz，双声道，96 kbps，2,285 个样本 |
| 音频末 PTS | 48,725,333 us |
| 末 PTS 差 | 58.667 ms |
| 可拖动性 | 首、中、尾三帧均成功解码 |

最早的 `1000012263` 样本真实暴露了 15 fps 问题，它作为失败证据保留在设备中，但不代表修复后的当前实现。

### 3.2 10 分钟有声长录

文件 `pokemon-champions_20260717_113647.mp4`，MediaStore 项 `1000012273`：

| 项目 | 结果 |
| --- | --- |
| 容器时长 | 640,683 ms（10 分 40.683 秒） |
| 文件大小 | 128,232,407 bytes |
| 视频 | H.264，960 x 540，24 fps，15,375 个样本 |
| 视频首/末 PTS | 0 / 640,583,333 us |
| 音频 | AAC-LC，48 kHz，双声道，96 kbps，30,032 个样本 |
| 音频首/末 PTS | 0 / 640,661,333 us |
| 末 PTS 差 | 78 ms，低于计划的 100 ms 上限 |
| 可拖动性 | 0%、50%、接近结尾三处均成功解码 960 x 540 帧 |
| MediaStore 状态 | `is_pending=0` |

整段会话始终只有一个 `VirtualDisplay`。录制前临时调长的亮屏和屏幕超时设置已恢复为原值：不保持唤醒、屏幕超时 300,000 ms。

### 3.3 旋转、后台、锁屏和系统停止

- 在另一段 369,515 ms 的录制中执行 Home、重新进入游戏和横竖屏内容变化，日志中始终是同一 recorder 和同一个 `VirtualDisplay`。
- 内容尺寸变化只更新 EGL 输入规格和方向，不重建 H.264 编码器或 muxer。
- 自动锁屏触发系统结束 MediaProjection 后，服务安全收尾并发布可解析文件，`dumpsys media_projection` 回到 `null`。
- 通知和悬浮球停止均进入同一保存路径；重复停止不会再次释放或发布。

### 3.4 画面隔离

- EGL 悬浮标记自检通过；标记没有出现在被捕获帧中。
- Debug 检查器解码的首、中、尾帧方向正确，必要处为黑边，没有助手悬浮球、标记或系统 UI。
- 单应用捕获没有改成整屏捕获，也没有为了录制创建第二个投屏。

### 3.5 音频隔离和无声回退

Phase 0 的同一 UID 白名单探针给出的 RMX3820 原始结论继续成立：

| 场景 | 10 秒 PCM 结论 |
| --- | --- |
| 游戏有声 | 10.033 秒，`nonZeroRatio=0.98036`，`-23.254 dBFS` |
| 游戏静音 | 全部数字零 |
| 其他 UID 播放测试音 | 测试音确实播放，但游戏 UID 捕获仍全部数字零 |

正式录制复用同一 UID/usage 白名单，音频来源没有麦克风配置。随后把游戏内语音、音效和音乐三项都临时调为 0，预检得到 241,664 个全零样本并弹出明确降级提示；选择“录制无声视频”后生成 MediaStore 项 `1000012286`：

- 时长 58,625 ms，大小 3,464,385 bytes；
- 只有一条 H.264 960 x 540 / 24 fps 视频轨，1,407 个视频样本；
- 不含 AAC 或其他音频轨；
- 首、中、尾三帧均成功解码；
- `is_pending=0`。

验收后已把游戏内语音、音效和音乐三项恢复为 10，并在游戏设置页目视确认。

ColorOS 第一次由权限 Activity 返回游戏时出现“允许打开 Pokémon Champions”系统确认，选择“30 天内允许”后后续流程正常。这是 OEM 跨应用启动确认，不是麦克风或投屏授权；如果该授权过期，用户需再次确认。

### 3.6 强杀和 pending 清理

在有声录制中强制停止助手后，磁盘只留下约 11 MB 的 `.pending-*` 文件，MediaStore 没有发布为普通视频。Android 默认查询会排除 pending 项，因此清理逻辑改为显式包含 pending 记录。

安装修复版后再次启动服务，日志显示：

```text
Removed 1 stale pending replay item(s)
```

对应物理文件已删除，回放目录只保留 5 个 `is_pending=0` 的已发布测试视频，没有损坏视频被错误发布。

### 3.7 仅识别回归

最终版本重新运行“仅识别”模式：

- 日志出现 `RecognitionFeatureHost initialized for mode=RECOGNIZE_ONLY`；
- 只创建一套原分辨率捕获 Surface；
- 悬浮菜单仍包含“录入我的队伍”“识别双方阵容”“结束对局助手”；
- 停止后投屏释放，无残留 pending 文件。

因此 Phase 2 没有把 OCR 或伤害运行时提前初始化到“仅录屏”，也没有替换掉现有仅识别路径。

## 4. Phase 退出结论

### 4.1 Phase 2

- [x] RMX3820 连录超过 10 分钟，MP4 可播放、可拖动、方向正确。
- [x] 回放未出现助手悬浮层和系统 UI。
- [x] 正常停止后没有 pending 垃圾。
- [x] 强杀不会发布损坏文件，下次启动能清理遗留 pending。
- [x] 仅识别路径通过原回归。

结论：Phase 2 已退出。

### 4.2 Phase 3

- [x] RMX3820 得到明确音频结论。
- [x] RMX3820 连续 10 分钟音画末 PTS 差为 78 ms，不超过 100 ms。
- [x] RMX3820 游戏静音为静音，其他 UID 播放不进入游戏 UID 捕获，麦克风未被配置。
- [x] 无法得到非静音游戏信号时，开始前给出明确提示，并能显式选择纯视频。
- [ ] OPD2409 得到明确音频结论。

结论：Phase 3 实现完成，但双设备退出条件未满足。OPD2409 上线后必须至少执行：设备/游戏版本和 UID 核对、编码器能力、游戏有声/静音/其他 UID 三组对照、显式无声降级、真实 MP4 轨道检查和一段长录音画同步检查。未完成前不得把 RMX3820 结论扩大为双设备或 Release 结论。

为减少第二台设备上线后的手工取证，新增：

- `tools/android/verify-replay-phase3-acceptance.ps1`：强制使用显式 ADB 序列号；默认复用 Phase 0 基线采集器核对设备、游戏 UID 和 H.264/AAC 编码器；从 Debug 私有目录选择三种场景各自最新的 PCM 探针；从 MediaStore 选择指定或最新的已发布回放；调用设备内 `ReplayArtifactVerifier` 枚举轨道、扫描样本 PTS 并解码五个时间线检查点；最后输出逐项 PASS/FAIL 的 `report.md` 和机器可读 `acceptance.json`。
- `manual-test-scripts/verify-replay-phase3-acceptance.cmd`：Windows 直接入口。

RMX3820 的既有长录通过了脚本的 33 项检查；同一脚本也以 `-ExpectSilent` 验证了纯视频回退文件。完整有声验收命令：

```powershell
manual-test-scripts\verify-replay-phase3-acceptance.cmd `
  -Serial <adb序列号> `
  -ReplayId <十分钟有声回放的MediaStore ID>
```

默认要求回放至少 600,000 ms。纯视频文件另用 `-ExpectSilent -MinimumDurationMs <下限>`；该模式验证没有 AAC 轨，不替代实际查看“未检测到游戏内部声音”的显式选择框。脚本启动检查器前会确认录制或 PCM 探针服务已经停止，避免为了分析成品而强停正在写入的回放。

OPD2409 上线后应先安装当前 ARM64 Debug 包，依次运行 `audible-game`、`muted-game`、`other-app-tone` 三个 10 秒探针，再完成一段至少 10 分钟的有声回放并运行：

```powershell
manual-test-scripts\verify-replay-phase3-acceptance.cmd `
  -Serial f522cec8 `
  -ReplayId <OPD2409长录的MediaStore ID>
```

只有该报告整体为 PASS，并人工确认显式无声降级对话框后，才能勾选 OPD2409 音频结论。

## 5. 后续边界

- `识别并录屏` 仍禁用，属于 Phase 4；当前仅录屏路径不会初始化 `RecognitionFeatureHost`。
- 30 分钟性能、温度、低空间、Release 签名/权限文案和完整发布验收属于 Phase 5/6，不在本阶段冒充完成。
- 设备上的 5 个测试 MP4 是本次真机验收证据，均位于 `Movies/Pokemon Champions Replays/`。
