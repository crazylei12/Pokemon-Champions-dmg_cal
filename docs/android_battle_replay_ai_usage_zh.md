# Android 对局回放：AI 使用与验收说明

日期：2026-07-17

适用仓库：`D:\crazylei12\pokemon-champions-assistant`

适用实现：`feature/battle-replay-phase-4` 当前工作树及其后续提交

面向对象：需要替用户操作、验证、排障或继续维护录屏功能的 AI。本文描述当前可用功能，不是未来开发计划。

## 1. AI 先记住这几条

1. 录屏用于弥补 Pokémon Champions 没有内置对局回放的问题，主要产物是保存在本机相册中的 MP4。
2. 系统投屏授权页必须选择“单个应用 -> Pokémon Champions”，不能选择整个屏幕。
3. 基础录屏验收不需要进入正式对战。留在游戏大厅开始录制、展开悬浮菜单、保持一段时间、结束并保存即可。
4. 不要把助手悬浮球、菜单、核对页或伤害面板出现在真实屏幕上误认为失败；验收目标是它们不能出现在保存的 MP4 中。
5. 投屏授权后识别助手直接可用；录屏是悬浮菜单里的独立开关。只有悬浮球显示红点和计时、状态提示“录屏已开始”后，正式 MP4 时间线才开始。
6. 正常停止录屏要使用悬浮菜单中的“结束录屏并保存 MP4”，并等待“回放已保存”。该动作不能结束识别、核对、伤害面板或整个对局助手。
7. AAC 音轨和 AAC 样本存在只证明文件中有音频轨，不证明内容真的可听。必须播放试听，或把音频解码后检查非静音信号。
8. ADB 操作前先运行 `adb devices -l`；手机、平板或模拟器同时在线时，每条命令都要带 `adb -s <序列号>`。
9. 不要为了替 AI 验证录屏而自动进入排位或正式对战。正式对战中的识别准确率和人工修正由用户自行验收。
10. 不得通过录麦克风、整屏录制、修改游戏、Hook、读内存、拦截网络或自动操作游戏来绕过失败。

## 2. 这个功能有什么用

### 2.1 给玩家的用途

- 保存本机对局画面和游戏内部声音，供对局后复盘。
- 回看选人、换人、招式、伤害和关键回合，而不依赖游戏提供内置回放。
- 在使用助手识别、核对和伤害面板时继续录制游戏画面。
- 通过系统相册或播放器手动查看、拖动和分享最终 MP4。

### 2.2 给开发和排障 AI 的用途

- 复现方向、黑边、掉帧、音画不同步、保存失败等录屏问题。
- 验证悬浮菜单和伤害面板是否被单应用捕获正确排除。
- 为识别问题保留用户当时看到的游戏画面背景，但录屏本身不保存 OCR 结果或旁车 JSON。
- 用 Debug 检查器枚举 MP4 轨道、样本时间戳并抽取时间线帧。

### 2.3 它不是什么

- 不是整机通用录屏器。
- 不是自动对战、自动点击或游戏机器人。
- 不会修改 Pokémon Champions APK。
- 不会录麦克风、环境声、通知声或其他应用的播放声音。
- 不会把回放上传到云端，也不会自动分享。
- 不会为回放新增逐帧截图、操作流水、字幕、遥测或对局数据库。

## 3. 当前独立录屏行为

对局助手不再提供`识别并录屏`、`仅识别`、`仅录屏`三种互斥模式。投屏授权完成后，悬浮菜单直接提供现有识别功能和独立录屏动作：

- 未录屏时显示“开始录屏”；
- 录屏时显示“结束录屏并保存 MP4”；
- 开始或结束录屏不会关闭“录入我的队伍”“识别双方阵容”“打开伤害面板”等功能；
- “结束对局助手”始终是另一个明确的独立菜单项；
- 旧的“核对双方阵容并开始对局”悬浮菜单项已经移除，阵容识别完成后的核对流程仍由识别结果直接打开。

录屏当前固定使用：

| 项目 | 当前值 |
| --- | --- |
| 容器 | MP4 |
| 视频 | H.264/AVC |
| 画布 | 960 x 540，保持比例，必要时加黑边 |
| 帧率 | 最高 24 fps |
| 视频码率 | 约 1.5 Mbps |
| 音频 | AAC-LC，48 kHz，约 96 kbps |
| 声道 | 优先双声道，不支持时单声道 |
| 保存目录 | `DCIM/Pokemon Champions Replays/` |
| 文件名 | `pokemon-champions_yyyyMMdd_HHmmss.mp4` |

## 4. 使用前提

- 录屏首发只在 Android 16 / API 36 开放；更低系统仍可使用识别和伤害功能。
- 设备必须安装 Pokémon Champions，当前游戏包名为 `jp.pokemon.pokemonchampions`。
- 助手需要悬浮窗权限和通知权限。
- 每次新投屏会话都要经过系统 MediaProjection 授权。
- 有声录制需要 Android 展示的“录音”权限。这里的权限用于应用播放声音捕获，代码不会创建麦克风输入。
- 开始有声录制时，游戏必须正在产生可检测的声音。建议停留在有背景音乐的大厅，并确认游戏内语音、音效或音乐不是全部关闭。

包名不要混淆：

| 变体 | 包名 | 说明 |
| --- | --- | --- |
| Debug | `com.crazylei12.pokemonchampionsassistant.debug` | 可与正式版并存；包含 `ReplayArtifactVerifier` 调试入口 |
| Release | `com.crazylei12.pokemonchampionsassistant` | 正式产品包；不包含 Debug 检查入口 |

## 5. 用户界面的完整操作流程

### 5.1 启动会话

1. 打开助手，进入“对局助手”页。
2. 如果“悬浮窗权限”未授予，点击“授予悬浮窗权限”，完成系统设置后返回。
3. 点击“启动对局助手”。
4. 按提示完成通知权限。
5. 在系统投屏页选择“单个应用”，再选择 Pokémon Champions。
6. 返回游戏后等待“对局助手”悬浮球出现。此时识别功能已经可用，还没有正式录制。
7. 点击悬浮球可直接选择识别功能或“开始录屏”。

如果误选了整个屏幕，录屏开始前的悬浮标记隔离自检应拒绝启动，并提示重新授权。不要尝试绕过自检继续整屏录制。

### 5.2 处理声音授权

点击“开始录屏”后：

1. 如果声音权限尚未授予，助手会说明 Android 为什么显示“录音”权限。
2. 点击“继续授权”，完成系统权限页。
3. 如果 ColorOS 没有直接打开权限页，从通知栏点击“继续录屏授权”。
4. 授权后助手会在开始前检测约 2.5 秒 Pokémon Champions 内部声音。
5. 如果检测到声音，继续准备画面隔离并开始有声录制。
6. 如果没有检测到声音，界面只允许：
   - 取消，检查游戏音量后重新开始；
   - 明确选择“录制无声视频”。

助手不会在失败后偷偷改录麦克风。选择无声录制后，最终 MP4 应只有视频轨，不应包含伪造的静音 AAC 轨。

### 5.3 判断何时真正开始

启动期间可能依次看到：

- “正在检测 Pokémon Champions 内部声音…”；
- “正在确认悬浮层不会进入回放…”；
- “录屏已开始”。

正式开始的可见证据：

- 悬浮球变为红色录制状态；
- 悬浮球显示`●`和递增时长；
- 前台通知显示录屏时长和保存提示。

在这些证据出现前，不要开始计算验收时间。

### 5.4 录制中怎么用

- 点击悬浮球后，助手会先按需冻结一帧原分辨率游戏画面，再打开同一个功能菜单；不要连续重复点击。
- 菜单同时保留“录入我的队伍”“识别双方阵容”“打开伤害面板”和“结束录屏并保存 MP4”等独立入口。
- 菜单不再显示“核对双方阵容并开始对局”。
- 识别读取、核对窗口和伤害面板失败时，录屏应继续；结束录屏后这些功能也应继续运行。
- 保存的视频仍为 960 x 540；给识别功能的帧保持投屏原始尺寸，不使用 540p 回放帧冒充识别输入。

### 5.5 正常结束和保存

优先使用以下任一入口：

1. 结束录屏：使用悬浮菜单中的“结束录屏并保存 MP4”；
2. 结束整个助手：使用独立的“结束对局助手”、前台通知结束操作或 Android 系统投屏芯片。

停止后：

1. 等待“正在结束编码并保存 MP4…”；
2. 不要立即强制停止 App、拔线、重启或杀进程；
3. 等待“回放已保存：<时长>，<大小>”；
4. 保存后悬浮球恢复“对局助手”，识别和伤害功能继续可用；
5. 点击“对局回放已保存”通知可以直接打开视频；
6. 也可以在系统相册的 `DCIM/Pokemon Champions Replays/` 中查找。

只有编码器、muxer 和 MediaStore 全部正常收尾后，文件才会从 `IS_PENDING=1` 变为公开可见。强杀或断电后的未完成 MP4 不保证可恢复；下次服务启动只会清理遗留 pending 文件，不会把损坏文件冒充成正常回放。

## 6. AI 推荐的低成本验收流程

这是验证整个录屏功能的默认方法。除非用户明确要求，不进入正式对战。

### 6.1 基础录屏验收

1. 确认设备是 Android 16，并让 Pokémon Champions 停留在有声音的大厅。
2. 启动对局助手，在系统页选择 Pokémon Champions 单个应用。
3. 点击悬浮球并选择“开始录屏”。
4. 等待红点和计时出现。
5. 先保持游戏大厅约 8 秒。
6. 点击悬浮球，展开录制菜单并保持约 10～15 秒。真实屏幕上应能看到菜单。
7. 从菜单选择“结束录屏并保存 MP4”。
8. 等待保存完成，不要用 ADB `force-stop` 收尾。
9. 打开同一个 MP4，确认：
   - 文件存在、可播放、可拖动；
   - 能听到这段时间里实际播放的游戏声音；
   - 对应菜单展开时段的视频仍然只有游戏画面，没有悬浮球或菜单。

建议总时长至少 25～30 秒，并让菜单覆盖视频中点。这样 Debug 检查器抽取的 1/2 时间点更容易直接命中菜单真实展开的时段。

### 6.2 录屏与识别并行验收

需要验证录屏与识别深度解耦时执行：

1. 仍停留在游戏大厅，不进入正式对战。
2. 先确认识别菜单可用，再点击“开始录屏”并等待正式开始。
3. 展开识别悬浮菜单，保持一段时间。
4. 如果当前已有可用会话，可打开伤害面板并保持一段时间；没有现成会话时不要为了生成面板自动进入对战。
5. 收起面板，继续录几秒，然后只结束录屏并保存。
6. 再次打开悬浮菜单，确认识别和伤害入口仍然存在，悬浮球没有退出。
7. 检查菜单或面板实际展开的对应时间点，MP4 中只能出现游戏画面。

正式对战中的队伍识别准确率、候选修改和人工确认不属于这次低成本录屏验收，留给用户手动完成。

## 7. AI 如何收集可复核证据

### 7.1 先锁定设备和应用

```powershell
adb devices -l

$deviceSerial = "<目标设备序列号>"
adb -s $deviceSerial shell getprop ro.build.version.sdk
adb -s $deviceSerial shell getprop ro.product.model
adb -s $deviceSerial shell dumpsys package com.crazylei12.pokemonchampionsassistant.debug |
  Select-String -Pattern 'versionCode=|versionName=|lastUpdateTime=|primaryCpuAbi='
```

如果操作 Release 包，把包名改为 `com.crazylei12.pokemonchampionsassistant`。不要根据以前的设备记录假定当前序列号，必须读取本次 `adb devices -l`。

### 7.2 查询已发布回放

```powershell
adb -s $deviceSerial shell content query `
  --uri content://media/external_primary/video/media `
  --projection _id:_display_name:duration:_size:is_pending:relative_path
```

找到 `relative_path=DCIM/Pokemon Champions Replays/` 的最新一行，并记录：

- `_id`；
- `_display_name`；
- `duration`；
- `_size`；
- `is_pending`。

合格成品必须满足：

- `is_pending=0`；
- 时长和文件大小都大于零；
- 文件名符合 `pokemon-champions_*.mp4`；
- 该行的时间和用户刚完成的录制相符。

不要只看到相册里“有一个视频”就默认它是本次新文件，也不要把历史失败样本当成本次成品。

### 7.3 使用 Debug 成品检查器

Debug APK 提供五点时间线检查器。先确保本段录屏已经结束并发布；对局助手 Service 可以继续运行。再对上一步的 MediaStore ID 执行：

```powershell
$replayId = "<MediaStore _id>"
$replayUri = "content://media/external_primary/video/media/$replayId"
$debugPackage = "com.crazylei12.pokemonchampionsassistant.debug"
$probeActivity = "$debugPackage/com.crazylei12.pokemonchampionsassistant.replayprobe.ReplayProbeActivity"

adb -s $deviceSerial shell am start -W `
  -n $probeActivity `
  -a "$debugPackage.VERIFY_REPLAY_ARTIFACT" `
  -d $replayUri
```

检查器会在以下目录生成 `inspection.json` 和五张 JPEG：

```text
/sdcard/Android/data/com.crazylei12.pokemonchampionsassistant.debug/files/
  replay-probe/artifact-inspection-<时间>/
```

查找并拉回最新检查目录：

```powershell
$inspectionDirectory = (
  adb -s $deviceSerial shell ls -dt `
    /sdcard/Android/data/com.crazylei12.pokemonchampionsassistant.debug/files/replay-probe/artifact-inspection-*
  | Select-Object -First 1
).Trim()

adb -s $deviceSerial pull $inspectionDirectory .tmp/replay-check/
```

`inspection.json` 应至少证明：

- `ok=true`；
- 容器 MIME 为 `video/mp4`；
- 视频为 H.264、960 x 540，视频样本数大于零；
- 有声录制存在 AAC 轨、48 kHz、1 或 2 声道，音频样本数大于零；
- 无声录制不含音频轨；
- 开始、1/4、1/2、2/3 和接近结尾五张帧都能解码。

用图像查看工具逐张打开 JPEG。重点检查刻意展开悬浮菜单或伤害面板的时间点；不能只检查第一帧和最后一帧。

### 7.4 怎样证明“真的有声音”

Debug 检查器当前不会把 AAC 解码成 PCM，因此以下证据都不够：

- `hasAudio=yes`；
- 文件存在 AAC 轨；
- AAC 样本数大于零。

至少采用一种更强证据：

1. 在系统相册或播放器中播放本次 MP4，由用户确认能听到录制时的游戏声音；
2. 把 MP4 拉到电脑，用可用的媒体工具解码音频并检查不是数字静音；
3. 若环境有 FFmpeg，可运行：

```powershell
ffmpeg -hide_banner -i "<本地 MP4>" -map 0:a:0 -af volumedetect -f null NUL
```

`max_volume: -inf dB` 表示数字静音。非 `-inf` 仍要结合预期游戏声音和实际播放判断，不能只凭轨道存在宣布“有声音”。

### 7.5 怎样证明悬浮层没有进入画面

最可靠的做法是提前设计时间线：

- 记录悬浮菜单打开和关闭的大致时刻；
- 让菜单持续覆盖 1/2 或 2/3 检查点；
- 打开对应 JPEG；
- 真实屏幕现场有菜单，而抽取帧中只有游戏画面，才构成隔离证据。

自动隔离探针只负责在开录前拒绝明显的整屏捕获，不能代替成品时间线检查。

## 8. 完成标准

| 检查项 | 合格证据 | 不合格或证据不足 |
| --- | --- | --- |
| 保存成功 | 新 MediaStore 项，`is_pending=0`，时长和大小大于零 | 只有通知、旧文件或 pending 项 |
| 视频可用 | MP4 可播放、可拖动，五点帧可解码 | 只有文件扩展名或大小 |
| 画面正确 | 960 x 540、方向正确、保持比例、不裁掉游戏 UI | 拉伸、倒置、裁切、持续黑帧 |
| 悬浮层隔离 | 菜单/面板现场展开时，对应 MP4 帧只有游戏 | 只看首尾帧，或没有记录展开时段 |
| 有声录制 | 实际试听有游戏声，或解码 PCM 明确非静音 | 只看到 AAC 轨或样本数 |
| 音频隔离 | 游戏静音时不应录入其他应用或麦克风声音 | 用麦克风替代内部声音 |
| 正常收尾 | 录屏保存完成后助手继续运行，无新 pending 垃圾 | 结束录屏时连同悬浮助手一起退出 |
| 功能解耦 | 开始/结束录屏不影响识别、核对和伤害面板 | 仍通过互斥模式决定功能，或保存后悬浮球消失 |

## 9. 常见问题处理

### 9.1 没有“开始录屏”选项

- 先检查 API：`adb -s <序列号> shell getprop ro.build.version.sdk`。
- 当前只有 API 36 会启用独立的“开始录屏”菜单项。
- Android 13～15 保留识别和伤害功能，不要修改系统限制强行开放录屏。

### 9.2 看不到悬浮球

- 检查助手悬浮窗权限。
- 检查是否已完成系统投屏授权。
- 检查前台服务通知是否存在。
- 不要反复创建多个投屏会话；先结束旧会话再重试。

### 9.3 声音授权页没有打开

- 展开通知栏，点击“继续录屏授权”。
- 如果刚拒绝权限，按界面明确选择取消或无声录制。
- 不要通过系统或代码改录麦克风。

### 9.4 提示“未检测到游戏内部声音”

- 确认游戏正在前台并实际播放音乐或音效。
- 检查游戏内语音、音效、音乐设置。
- 取消后重新开始，让游戏在约 2.5 秒预检期间持续发声。
- 用户明确接受时才能选择“录制无声视频”。

### 9.5 提示悬浮层进入捕获画面

- 结束会话并重新授权。
- 确认系统页选择“单个应用 -> Pokémon Champions”，不是整个屏幕。
- 当前颜色探测器存在误报可能；如果游戏画面恰好包含大量高纯度品红和青色，可换到颜色更稳定的大厅画面后重试并记录证据。
- 不得为了绕过误报退回整屏捕获。

### 9.6 保存时间较长

- 停止后需要排空视频和音频编码器、停止 muxer 并发布 MediaStore 项。
- 等待“回放已保存”或明确失败提示。
- 不要在“正在结束编码并保存 MP4…”期间杀进程。

### 9.7 强杀后找不到视频

- 这是预期安全边界：没有完整 MP4 尾部的 pending 文件不会发布到相册。
- 下次服务启动会清理本应用遗留的旧 pending 项。
- 不要把“损坏文件未发布”描述成正常保存失败之外的数据恢复能力。

### 9.8 结束录屏后悬浮球消失

- 这是功能耦合回归。正常行为是只完成编码器、muxer 和 MediaStore 收尾，然后把同一个 VirtualDisplay 切回识别 Surface。
- “结束对局助手”才允许释放 MediaProjection、VirtualDisplay、识别控制器和悬浮球。

### 9.9 平板在“录屏准备”后退回悬浮球

- 先按同一次操作的时间戳检查 `ReplayRecorder`、`GameAudioCapture`、`AudioRecord` 和 `AudioFlinger` 日志，不要只凭界面判断成声音预检失败。
- OPD2409 / ColorOS 15 的已确认故障是：声音预检能读到非静音游戏 PCM，但停止预检后复用同一个 `AudioRecord` 会让正式启动持续返回系统错误 `-38`；画面编码器和单应用隔离检查本身均已成功。
- 正确边界是预检实例完成后彻底释放，正式 MP4 使用全新的播放捕获实例，并在启动编码线程前同步确认其进入录制状态；短暂系统竞争可做有界重试。
- 如果所有重试仍失败，应在开始阶段丢弃 pending 项并恢复识别 Surface，不能先显示录屏已开始，也不能结束整个对局助手。

## 10. 当前已确认范围和未完成边界

截至 2026-07-17：

- RMX3820 / Android 16 已验证单应用画面隔离、有声/静音 UID 捕获、10 分钟有声回放、异常 pending 清理和录屏期间使用识别/伤害面板的大厅验收。
- 大厅验收中，悬浮菜单和伤害面板真实展开时，对应 MP4 时间点仍只有游戏画面。
- OPD2409 / Android 16 已验证上述 `AudioRecord -38` 修复后的两次同会话有声启停：111.511 秒和 16.906 秒成品均发布到 `DCIM/Pokemon Champions Replays/`；首段成品检查为 H.264 960×540、AAC 48 kHz 双声道、五点可解码。该设备的 10/30 分钟长录、静音/其他 UID 对照和最终 Phase 3/5 退出结论仍未完成。
- 已生成并发布 30 分钟有声回放，但该样本早于本次独立启停改造；改造后的 30 分钟复测、空间不足、温度监控、完整性能阈值和最终 Release 产品化仍属于 Phase 5/6，不能写成已完成。
- 当前 Debug 检查器验证轨道、时间戳范围和五点解码，但不验证解码后的音频能量。
- 当前悬浮隔离颜色检测没有验证标记的完整位置和形状，存在被游戏自身品红/青色画面误触发的风险。
- 正式对战中的识别候选准确率和人工修正由用户手动验收；AI 不应继续自动排位来替代用户。

## 11. 维护时的代码地图

| 文件 | 责任 |
| --- | --- |
| `MainActivity.kt` | 对局助手入口、权限串行请求、页面状态和用户说明 |
| `CaptureSessionMode.kt` | 长期投屏会话状态、独立录屏子状态和声音决策 |
| `ReplayPermissionActivity.kt` | 声音权限说明、拒绝后的显式无声选择 |
| `OverlayCaptureService.kt` | 唯一投屏所有者、同级悬浮菜单、识别/录屏 Surface 切换、独立停止和保存通知 |
| `RecognitionFeatureHost.kt` | 管理 OCR、模板、核对页和伤害相关组件，不受录屏开始/结束控制 |
| `replay/ReplayRecorder.kt` | 串联视频、音频、EGL、muxer 和 MediaStore 生命周期 |
| `replay/EglProjectionRouter.kt` | 单应用帧路由、固定 540p 编码、原分辨率按需读回和隔离探针 |
| `replay/ReplayVideoEncoder.kt` | H.264 Surface 编码和视频 EOS |
| `replay/GameAudioCapture.kt` | Pokémon Champions UID 播放捕获、PCM 预检、AAC 编码和音频 PTS |
| `replay/Mp4MuxerCoordinator.kt` | 单轨/双轨门闩、样本写入、停止和释放 |
| `replay/ReplayMediaStore.kt` | DCIM pending 创建、正常发布、旧 Movies 回放迁移、失败丢弃和遗留项清理 |
| `src/debug/.../ReplayArtifactVerifier.kt` | Debug 成品轨道检查和五点帧抽取 |

AI 修改或排障时应以当前代码为准，再用以下文档理解历史和证据：

- [总体方案](android_battle_replay_recording_plan_zh.md)
- [Phase 0 探针记录](android_battle_replay_phase0_progress_zh.md)
- [Phase 1 会话记录](android_battle_replay_phase1_progress_zh.md)
- [Phase 2/3 视频与音频记录](android_battle_replay_phase2_phase3_progress_zh.md)
- [Phase 4 识别并录屏记录](android_battle_replay_phase4_progress_zh.md)

阶段文档会保留当时的历史状态。若早期文档写着“尚未生成 MP4”或“识别并录屏未开放”，必须继续阅读后续 Phase 记录和当前代码，不能把历史限制误当成现状。
