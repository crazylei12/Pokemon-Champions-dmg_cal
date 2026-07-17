# Pokemon Champions Android 对局回放录制修订方案

日期：2026-07-17

状态：Phase 2 已完成；Phase 3 的实现和 RMX3820 验收已完成，OPD2409 不在线，因此 Phase 3 的双设备退出条件仍待补；Phase 4 的实现和 RMX3820 单应用验收已完成

基线：`f2a84e3`（`v1.1.0`）

面向 AI 的实际用途、操作与成品验收说明见：[Android 对局回放：AI 使用与验收说明](android_battle_replay_ai_usage_zh.md)。

## 1. 修订目的

早期方案形成时，Android App 已有 MediaProjection 和悬浮窗雏形，但此后项目已经完成或强化了：

- 单应用 MediaProjection 的横竖屏旋转适配；
- 捕获 Surface 动态调整和安全释放；
- 悬浮面板的焦点、拖动、输入和关闭行为；
- 双方阵容识别、我方双页 OCR、人工确认和伤害面板；
- 前台服务停止时取消待执行截图，避免旧回调继续访问已释放资源；
- Android 16 真机上的稳定签名、更新和发布流程。

因此，本次修订不再把录屏描述成一套独立于现有 App 的通用录像器，而是把它定义为现有“对局助手会话”的一种模式，并明确哪些稳定路径必须原样保留、哪些部分才允许新增。

本次主要修订：

| 旧方案 | 本次修订 |
| --- | --- |
| 直接描述完整三模式实现 | 先以画面隔离和真实 PCM 两个探针作为开工闸门 |
| 统一改造成一条新帧管线 | `仅识别` 保留现有 ImageReader；只有录屏模式引入 EGL |
| 模式入口与投屏创建顺序不够明确 | 先取得授权、悬浮球选模式、再创建唯一 VirtualDisplay |
| `仅录屏` 仍可能随 Service 初始化识别组件 | OCR、模板、伤害运行时和识别悬浮层全部按模式延迟加载 |
| 默认按支持单应用共享的设备开放 | 首发明确只开放 Android 16 / API 36 |
| 只依赖用户正确选择单个应用 | 增加悬浮标记隔离自检，检测到整屏捕获就拒绝录制 |
| 对进程异常后的 MP4 恢复过于乐观 | 可控停止尽量收尾；强杀/断电只清理坏的 pending 文件 |
| 先列组件、后统一验收 | 每个 Phase 都有独立产物和退出条件 |

## 2. 已确认需求

以下产品要求保持不变：

- 用于弥补 Pokemon Champions 没有内置对局回放的问题，让玩家在对局后复盘。
- 画质不追求高，优先降低卡顿、发热、耗电和文件体积。
- 只录用户在系统授权页中选择的 Pokemon Champions 单个应用画面。
- 只录 Pokemon Champions 的应用播放声音。
- 不录麦克风、环境声、通知声或其他应用的声音。
- 不把本助手的悬浮球、悬浮菜单、OCR 核对页或伤害面板录进视频。
- 悬浮球在新会话开始时提供三种模式：
  - `识别并录屏`
  - `仅识别`
  - `仅录屏`
- 录屏功能只新增最终 MP4 回放，不新增逐帧截图、操作流水、对局 JSON、字幕、遥测或云端上传。
- 不修改游戏 APK，不 Hook，不读游戏内存，不拦截网络，不自动操作游戏。

“只保存回放”不改变现有助手本来就有的队伍配置和本局识别状态；它的含义是录屏模块本身不再额外持久化一套战报数据。

## 3. 当前项目事实

### 3.1 当前捕获链路

`MainActivity.startOwnTeamCapture()` 在 Phase 1 后负责：

1. 检查悬浮窗权限；
2. 串行请求通知权限，回调完成后才打开投屏授权，避免两个系统授权页互相覆盖；
3. 使用 `MediaProjectionConfig.createConfigForUserChoice()` 打开系统投屏授权页；
4. 把授权结果交给 `OverlayCaptureService`。

`OverlayCaptureService` 在 Phase 1 后负责：

- 以前台服务形式持有 `MediaProjection`；
- 先持有投屏 token，等用户在首次悬浮球菜单选择模式后才调用一次 `createVirtualDisplay()`；
- 用 `ImageReader(RGBA_8888)` 持续维护一张可复用的最新画面；
- 在用户点击悬浮菜单后复制一张识别帧；
- 在菜单打开前冻结最后一帧，并在截图前隐藏悬浮球；
- 处理 `onCapturedContentResize()`、配置变化和 Android 16 `VirtualDisplay.setRotation()`；
- 仅在包含识别的模式中延迟创建 `RecognitionFeatureHost`，由它管理双方阵容识别、我方 OCR、核对窗口、伤害面板和统一释放；
- 在包含录屏的模式中先完成权限和生命周期编排，但 Phase 1 尚不编码或保存 MP4。

这条识别链路已经经历真机修复，不能为了录屏直接替换掉。

### 3.2 与旧方案相比的新约束

1. Phase 1 已把 OCR、队伍预览模板、伤害运行时和两个悬浮控制器移入 `RecognitionFeatureHost`；后续不得把这些依赖重新放回 `OverlayCaptureService.onCreate()` 提前初始化。
2. 当前 `VirtualDisplay` 的唯一输出 Surface 是 `ImageReader.surface`。录屏不能再建第二个 `VirtualDisplay`；组合模式需要在同一个输入 Surface 后做硬件帧分流。
3. 当前识别依赖原始捕获分辨率。组合模式可以把视频压到 540p，但不能把给 OCR 的截图也降到 540p。
4. Android 16 的 `VirtualDisplay.setRotation()` 已被当前项目用于修复单应用捕获旋转。录屏画布和 EGL 变换必须复用同一套方向语义。
5. Phase 1 状态机和服务停止入口已经幂等；后续录制停止、编码器 EOS 和 MP4 收尾必须接入同一入口，不能另起一套互相竞争的清理逻辑。

### 3.3 2026-07-17 真机快照

当前在线设备：

- 设备：RMX3820；
- Android：16；
- API：36；
- Pokemon Champions：`1.1.4`；
- 游戏包名：`jp.pokemon.pokemonchampions`；
- 游戏 `targetSdk`：36；
- 系统包信息包含 `ALLOW_AUDIO_PLAYBACK_CAPTURE`。

当前助手自身为 `minSdk 33 / targetSdk 36 / compileSdk 36`。录屏只开放 API 36 不影响 Android 13～15 上已有的识别和手动计算能力。

这说明系统和 Manifest 策略没有直接禁止播放音频捕获。2026-07-17 的 Debug 真机探针进一步在 RMX3820 上取得了游戏有声时的非静音 PCM，并通过了游戏静音和其他 UID 发声两组负对照；OPD2409 仍需独立验证。

OPD2409 平板本次未在线，开始实现前需要重新核对系统版本、游戏版本和同一组能力。

## 4. 修订后的结论

### 4.1 可行性

该功能仍然可做，但风险应分成三层：

| 能力 | 判断 | 风险 |
| --- | --- | --- |
| 仅录低清游戏视频 | Android 官方链路成熟 | 中低 |
| 录游戏内部声音 | RMX3820 的 UID 白名单 PCM 已通过；OPD2409 待测 | 中 |
| 录屏同时保留当前原分辨率识别 | 需要 EGL 单输入双路输出 | 中高 |

因此不应一次完成三种模式后再测试，而应按“画面/音频探针 -> 纯录屏 -> 有声录屏 -> 识别并录屏”逐层推进。

### 4.2 首发系统边界

录屏 MVP 只在 Android 16 / API 36 开放：

- 当前两台目标设备原计划均按 Android 16 验证；
- 当前旋转修复依赖 API 36 的 `VirtualDisplay.setRotation()`；
- 可以避免 Android 14 QPR2 版本识别困难和 Android 13 不支持单应用共享的问题；
- Android 13～15 继续保留现有“仅识别”能力，不因新增录屏而退化。

后续只有在 Android 15 或 Android 14 QPR2 真机完成同等隐私、旋转和收尾验证后，才扩大录屏支持范围。

### 4.3 两个前置闸门

正式功能实现前必须先通过：

1. 画面隔离闸门：单应用共享时，悬浮球、悬浮菜单、伤害面板、状态栏、通知和其他应用都不进入捕获画面。
2. 音频闸门：Pokemon Champions 实际对局声音能够被捕获为非静音 PCM，并且静音游戏后探针也回到静音。

画面隔离失败时，不允许退回“整个屏幕录制”；音频失败时，不使用麦克风伪装成内部声音。

截至 2026-07-17，RMX3820 已通过悬浮标记隔离，以及“游戏有声 / 游戏静音 / 只有探针 UID 发声”三组 10 秒 PCM 对照。该结论只覆盖当前手机和 Debug 探针，不代替 OPD2409、完整系统 UI 隔离矩阵或最终 MP4 验收。

## 5. 修订后的用户流程

### 5.1 启动会话

```text
App“对局助手”页
  -> 点击“启动对局助手”
  -> 系统 MediaProjection 授权页
  -> 用户选择“单个应用”
  -> 用户选择 Pokemon Champions
  -> 前台服务持有 MediaProjection，但暂不创建 VirtualDisplay
  -> 显示悬浮球
  -> 用户点击悬浮球选择本次会话模式
```

把 `createVirtualDisplay()` 延后到模式确定之后，可以保留“三种模式从悬浮球选择”的需求，同时保证一个授权 token 只创建一次捕获会话。

### 5.2 三种模式

| 模式 | 捕获链路 | 识别组件 | 输出 |
| --- | --- | --- | --- |
| 识别并录屏 | EGL 帧路由 -> H.264；按需读回原始分辨率识别帧 | 延迟初始化 | 有声 MP4 + 现有识别/计算行为 |
| 仅识别 | 保留当前 ImageReader/Bitmap 链路 | 延迟初始化 | 不创建 MP4 |
| 仅录屏 | EGL 帧路由 -> H.264 | 不初始化 | 有声 MP4 |

模式一旦开始，本次 MediaProjection 会话内不允许切换。用户要更换模式时：

1. 结束当前会话；
2. 录屏模式先完成 MP4 收尾；
3. 重新发起系统授权；
4. 在新悬浮球菜单中选择新模式。

这是 Android 14+“一个 token 只能创建一次 VirtualDisplay”约束下最清晰、最可靠的行为。

### 5.3 音频权限

`RECORD_AUDIO` 是播放音频捕获的系统必需权限，但 Android 会把它展示为录音相关权限。不能在只用识别时提前索取。

录屏模式被点击后：

- 如果权限已授予，继续启动录制；
- 如果未授予，由一个只负责权限说明的 `ReplayPermissionActivity` 请求；
- 如果 ColorOS 阻止从悬浮球直接打开权限 Activity，则通知栏显示“继续录屏授权”，由用户点击后打开；
- 用户拒绝后明确提供“取消”或“录制无声视频”，不能静默降级；
- 代码始终使用播放捕获配置，不创建麦克风输入源。

### 5.4 录制中

- 悬浮球显示红点和录制时长。
- `识别并录屏` 保留当前“录入我的队伍、识别双方阵容、打开伤害面板”等菜单。
- `仅录屏` 只显示录制状态、结束并保存，不加载 OCR/OpenCV 和伤害运行时。
- 前台通知显示当前模式、时长和“结束并保存”。
- 系统状态栏投屏芯片始终由系统显示，用户可以从芯片停止。

### 5.5 结束

正常结束顺序：

1. 禁止新的识别和写入请求；
2. 停止向视频和音频编码器提交新数据；
3. 发送并排空 EOS；
4. 写完 MP4 音视频轨道；
5. 停止并释放 `MediaMuxer`；
6. 将 MediaStore 条目从 pending 改为可见；
7. 释放 EGL、Surface、VirtualDisplay、MediaProjection 和线程；
8. 显示回放时长、大小和“打开回放”。

## 6. 目标架构

### 6.1 会话所有权

`OverlayCaptureService` 继续是唯一的会话所有者：

```text
OverlayCaptureService
  -> ProjectionSession（唯一 MediaProjection / VirtualDisplay）
  -> CaptureSessionStateMachine
  -> RecognitionFeatureHost（仅识别相关模式延迟创建）
  -> ReplayRecorder（仅录屏相关模式创建）
       -> EglProjectionRouter
       -> ReplayVideoEncoder
       -> GameAudioCapture
       -> Mp4MuxerCoordinator
       -> ReplayMediaStore
```

服务负责串联，不继续把编码器、音频和 MediaStore 细节堆进现有近千行文件。

### 6.2 会话状态

建议状态：

```kotlin
enum class CaptureSessionMode {
    RECOGNIZE_AND_RECORD,
    RECOGNIZE_ONLY,
    RECORD_ONLY,
}

enum class CaptureSessionState {
    IDLE,
    PREPARING_PROJECTION,
    AWAITING_MODE,
    AWAITING_AUDIO_PERMISSION,
    STARTING,
    RUNNING,
    STOPPING,
    SAVED,
    FAILED,
}
```

状态机必须是纯 Kotlin 逻辑并有单元测试。`CaptureUiState` 只镜像服务状态给 Compose，不作为真正的生命周期来源。

### 6.3 三种 Surface 管线

#### 仅识别

完整保留当前：

```text
VirtualDisplay
  -> ImageReader RGBA_8888
  -> reusable latestBitmap
  -> 用户点击后复制一帧
  -> OCR / 图像识别
```

录屏实现不得顺手重写这条已经验证的路径。

#### 仅录屏

```text
VirtualDisplay
  -> SurfaceTexture / OES texture
  -> EGL
  -> 960 x 540 MediaCodec input Surface
  -> H.264
```

不经过 Bitmap、OpenCV 或 CPU RGBA -> YUV 转换。

#### 识别并录屏

```text
VirtualDisplay
  -> SurfaceTexture / OES texture
  -> EGL frame router
       -> 960 x 540 MediaCodec input Surface（持续、最多 24 fps）
       -> 原捕获尺寸 FBO（仅在用户点击识别时渲染并读回一帧）
```

关键原则：

- 视频降到 540p，识别帧保持当前原始捕获尺寸；
- `glReadPixels` 只在用户主动识别时执行；
- 编码帧和识别读回不在主线程；
- 识别超时或失败不能阻塞视频和音频编码；
- 输入尺寸变化只更新 SurfaceTexture、viewport 和矩阵，编码画布固定为 960 x 540。

### 6.4 帧率和方向

- 输入可以按设备刷新率到达，EGL 路由最多向编码器提交 24 fps，多余帧主动丢弃。
- 使用 `SurfaceTexture.getTimestamp()` 和 `eglPresentationTimeANDROID` 传递单调视频时间戳。
- 复用 `CaptureBufferSpec` 的方向语义和 API 36 `VirtualDisplay.setRotation()`。
- `onCapturedContentResize()` 更新输入尺寸和显示矩阵，不重建 MediaProjection 或第二个 VirtualDisplay。
- 画面按比例完整放入 16:9 编码画布；比例不同时加黑边，不拉伸、不裁掉游戏 UI。

## 7. 编码与音频

### 7.1 第一版固定参数

| 项目 | 目标值 |
| --- | --- |
| 容器 | MP4 |
| 视频 | H.264/AVC 硬件编码 |
| 输出 | 960 x 540 |
| 帧率 | 24 fps |
| 视频码率 | 1.2～1.5 Mbps，真机探针后定值 |
| 关键帧间隔 | 2 秒 |
| 音频 | AAC-LC |
| 采样率 | 48 kHz |
| 声道 | 优先立体声，不支持时单声道 |
| 音频码率 | 96 kbps |

预计约 10～13 MB/分钟；10 分钟约 100～130 MB。以最终设备编码器输出为准。

如果某台目标设备不接受 960 x 540 / 24 fps，允许显式降到 854 x 480 / 20 fps；不自动升到 720p 或 1080p。

### 7.2 游戏音频白名单

Manifest 需要新增：

- `android.permission.RECORD_AUDIO`；
- `<queries>` 中声明 `jp.pokemon.pokemonchampions`，用于可靠查询游戏 UID。

每次录制前重新查询游戏的当前 UID，再构造：

- `AudioPlaybackCaptureConfiguration`；
- `addMatchingUid(gameUid)`；
- `USAGE_GAME`、`USAGE_MEDIA`、`USAGE_UNKNOWN`。

不使用麦克风，不加入 `microphone` 前台服务类型，不混合其他 UID。

即使系统包标志允许捕获，也必须以实际 PCM 能量和编码后的 AAC 为准。连续一段时间只有数字零或低于静音阈值时，应在开始阶段报“未取得游戏内部声音”，而不是生成看似正常的无声回放。

### 7.3 音视频同步

- 录制开始时建立一个单调会话时钟。
- 视频 PTS 来自 SurfaceTexture 帧时间。
- 音频 PTS 以已读取 PCM 样本数和采样率计算，并锚定到同一会话时钟。
- `Mp4MuxerCoordinator` 等待所需轨道格式全部就绪后再启动 muxer。
- 编码回调只做缓冲搬运和轨道写入，不执行 OCR、Compose 更新、文件扫描或耗时日志。
- 所有输出 PTS 必须单调递增，停止时只允许一次 EOS 和一次 muxer stop。

## 8. 画面隔离与隐私自检

Android 的公开 MediaProjection API 不能替用户预选 Pokemon Champions，也不会向捕获 App 暴露用户最终选择的包名。因此产品必须清楚提示用户在系统页选择“单个应用 -> Pokemon Champions”。

为避免用户误选“整个屏幕”后把助手悬浮层录进去，录屏模式启动前增加本地隔离自检：

1. 临时显示一个只属于本助手悬浮层的高对比度小标记；
2. 从尚未写入正式 MP4 的捕获帧中检测该标记；
3. 如果检测到标记，说明捕获包含助手悬浮层，拒绝开始录屏；
4. 提示用户结束会话并重新选择 Pokemon Champions 单个应用；
5. 如果未检测到，再进入正式录制。

这个自检只能证明助手悬浮层被排除，不能证明用户选择的一定是 Pokemon Champions。游戏目标仍需要用户确认；音频 UID 白名单可避免录入其他应用声音。

Release 录屏模块只保存最终 MP4：

- 不保存隔离探针帧；
- 不保存原始 PCM；
- 不保存识别截图；
- 不新增回放旁车 JSON；
- 不上传、同步或自动分享；
- Debug 性能信息只写 Logcat，不写回放目录。

## 9. 文件与异常策略

### 9.1 MediaStore

保存到：

```text
Movies/Pokemon Champions Replays/
```

文件名：

```text
pokemon-champions_yyyyMMdd_HHmmss.mp4
```

流程：

- 创建时设置 `IS_PENDING=1`；
- muxer 正常停止后才设为 `0`；
- 使用 Content Uri 打开，不申请传统外部存储权限；
- App 启动时清理本应用遗留、已过期且不可播放的 pending 项。

### 9.2 可恢复边界

必须区分两类异常：

- 可控停止：用户停止、系统投屏芯片停止、锁屏触发 `onStop()`、空间不足、过热。服务仍有机会发送 EOS，应尽量生成可播放 MP4。
- 进程被强杀或设备断电：标准 `MediaMuxer` 可能来不及写 MP4 尾部，不能承诺部分文件可播放。下次启动应删除损坏的 pending 项，而不是发布坏文件。

旧方案中“任何进程退出都保存当前有效 MP4”的表述过于乐观，本次修订以上述边界为准。

### 9.3 统一停止

现有 `requestStop()` 扩展为单一幂等入口：

```text
requestStop(reason)
  -> state = STOPPING
  -> cancel pending recognition
  -> stop replay input
  -> drain encoders with timeout
  -> finalize or discard pending MediaStore item
  -> close recognition overlays
  -> detach/release Surface on capture thread
  -> release VirtualDisplay and MediaProjection
  -> stop service
```

多个停止来源同时发生时，后续调用只记录原因，不重复停止编码器或 muxer。

### 9.4 锁屏、可见性、空间和温度

- Android 15 QPR1+ 锁屏会自动停止 MediaProjection；`onStop()` 必须触发有界收尾。
- 实现 `onCapturedContentVisibilityChanged(false)`；正式录制不把其他应用画面替换进视频。设备实际输出黑帧、冻结帧还是停止帧，以真机为准并记录。
- 启动录制前检查可用空间；建议至少保留 512 MB。
- 录制中定期检查空间，低于安全线时自动结束并收尾。
- 使用 `PowerManager.OnThermalStatusChangedListener`。达到严重热状态时提示并结束，不继续硬撑。
- 编码器异常、Surface 丢失和音频读取错误都必须回到同一停止状态机。

## 10. 实施阶段与退出条件

### Phase 0：当前基线和两台真机探针

当前进度（2026-07-17）：已完成“可重复设备基线采集”和 Debug 专用的 10 秒画面/音频探针。RMX3820 已明确通过单应用悬浮标记隔离，并证实游戏有声可捕获、游戏静音为数字零、只有其他 UID 发声时仍为数字零。Phase 1 又在 RMX3820 上完成了仅识别回归、权限 Activity 和通知兜底验证；Phase 2/3 已进一步产出并检查真实 MP4。OPD2409、完整系统 UI 隔离矩阵和无录屏性能基线仍未完成，因此 Phase 0 仍未退出。实现与验证方法见 [android_battle_replay_phase0_progress_zh.md](android_battle_replay_phase0_progress_zh.md)。

工作：

- [ ] 记录 `v1.1.0` 下两台目标设备的识别、旋转、悬浮菜单和停止基线。（RMX3820 的 Phase 1 仅识别回归已完成，OPD2409 待测）
- [ ] 重新核对 RMX3820、OPD2409 的 Android、游戏版本和游戏 UID。（RMX3820 已完成，OPD2409 待测）
- [ ] 查询 H.264/AAC 编码器能力和硬件实现。（RMX3820 已完成，OPD2409 待测）
- [ ] 做 10 秒单应用视频探针，检查悬浮球、菜单、面板、状态栏、通知和其他应用是否被排除。（RMX3820 悬浮标记自检已通过，其余矩阵和 OPD2409 待测）
- [ ] 做游戏音频 PCM 探针：游戏有声、游戏静音、其他应用播放三组对照。（RMX3820 三组已通过，OPD2409 待测）
- [ ] 验证从悬浮球打开权限 Activity；ColorOS 不允许时验证通知点击兜底。（RMX3820 已完成，OPD2409 待测）
- [ ] 记录当前无录屏基线的 PSS、CPU、帧时间和热状态。

退出条件：

- 两台设备画面隔离结论明确；
- 至少明确游戏音频“可捕获”或“不可捕获”，不能停留在 Manifest 推测；
- 编码器支持目标参数，或已经确定降级参数；
- 当前仅识别路径无回归。

如果 OPD2409 暂时不在线，可以先完成 RMX3820 探针，但不能据此结束双设备验收。

### Phase 1：会话模式与延迟初始化

完成状态（2026-07-17）：已完成。代码、测试和 RMX3820 真机证据见 [android_battle_replay_phase1_progress_zh.md](android_battle_replay_phase1_progress_zh.md)。后续 Phase 2/3 已能生成正式 MP4，详见 [android_battle_replay_phase2_phase3_progress_zh.md](android_battle_replay_phase2_phase3_progress_zh.md)；Phase 0 的双设备验收仍未结束。

工作：

- [x] 新增模式和状态机及其单元测试。
- [x] 把 `startProjection()` 拆为“取得投屏对象”和“按模式创建唯一 VirtualDisplay”两步。
- [x] 首次悬浮球菜单显示三种模式。
- [x] 模式开始后锁定，不支持同会话切换。
- [x] 把 OCR、模板、伤害运行时和悬浮控制器移入 `RecognitionFeatureHost` 延迟初始化。
- [x] 加入 `ReplayPermissionActivity` 和通知兜底。
- [x] 默认路径仍可进入“仅识别”，行为和当前 `v1.1.0` 相同。

退出条件：

- [x] 所有现有 Android 单元测试通过；
- [x] 仅识别真机流程、从竖屏授权页进入横屏游戏和结束服务不回归；
- [x] 仅录屏模式在尚未实现编码时不会错误初始化识别组件；
- [x] 一个 token 始终只调用一次 `createVirtualDisplay()`。

### Phase 2：无声纯视频纵切

完成状态（2026-07-17）：已完成。RMX3820 已通过 10 分钟长录、三点解码、旋转/后台/锁屏、正常和异常收尾、pending 清理以及仅识别回归。证据见 [android_battle_replay_phase2_phase3_progress_zh.md](android_battle_replay_phase2_phase3_progress_zh.md)。

工作：

- [x] 实现 `EglProjectionRouter`。
- [x] 实现 960 x 540 / 24 fps H.264 Surface 编码。
- [x] 实现 `ReplayMediaStore` pending 写入。
- [x] 实现视频单轨 `Mp4MuxerCoordinator`。
- [x] 实现录制时长、通知停止和悬浮球“结束并保存”。
- [x] 实现画面隔离自检。
- [x] 覆盖正常停止、系统芯片停止、锁屏、旋转、游戏退到后台。

退出条件：

- [x] RMX3820 连录 10 分钟，MP4 可播放、可拖动、方向正确；
- [x] 回放不出现助手悬浮层和系统 UI；
- [x] 进程正常停止后无 pending 垃圾；
- [x] 强杀后不会发布损坏文件，下次启动能清理；
- [x] 仅识别路径仍通过原回归。

### Phase 3：游戏内部音频

当前状态（2026-07-17）：代码实现和 RMX3820 验收已完成，并已提供可复用的 Phase 3 设备验收脚本。OPD2409 当前不在线，按本方案定义，未取得第二台目标设备的明确音频结论前不能宣布 Phase 3 整体退出。

工作：

- [x] Manifest 新增 `RECORD_AUDIO` 和游戏包查询。
- [x] 按游戏 UID 和允许的 usage 创建 `AudioPlaybackCaptureConfiguration`。
- [x] 实现 `AudioRecord` PCM 环形缓冲和 AAC 编码。
- [x] 把音频轨道接入 muxer 协调器。
- [x] 实现开录前非静音检测和显式无声降级。
- [x] 验证其他应用和麦克风声音不进入回放。（RMX3820 已完成；OPD2409 待补设备结论）
- [x] 固化按显式序列号采集三组 PCM、MediaStore MP4、三点解码和音画 PTS 的验收脚本。

退出条件：

- [ ] 两台目标设备都得到明确音频结论。（RMX3820 已通过；OPD2409 不在线）
- [x] 可捕获设备连续 10 分钟音画同步误差不超过 100 ms。（RMX3820 为 78 ms）
- [x] 游戏静音时回放静音，其他应用播放时回放仍不出现其声音。（RMX3820）
- [x] 音频不可捕获时，用户在开始前得到明确提示。（RMX3820 已验证显式无声降级）

### Phase 4：识别并录屏

完成状态（2026-07-17）：代码实现和 RMX3820 单应用验收已完成。最终按约定只在游戏大厅录制，不进入正式对战；录制期间实际展开悬浮菜单和伤害面板，保存后的 MP4 在对应时间点只含游戏画面。实现、测试和证据见 [android_battle_replay_phase4_progress_zh.md](android_battle_replay_phase4_progress_zh.md)。正式对战中的识别准确率和人工修正由用户后续手动验收，不作为本次录屏管线退出条件。

工作：

- [x] 在 EGL 路由中增加按需原分辨率 FBO 读回。
- [x] 把读回 Bitmap 接到现有 `captureFrame()` 后半段识别逻辑。
- [x] 保留菜单冻结、悬浮球隐藏、人工核对和伤害面板行为。
- [x] 识别时编码持续运行，识别失败不影响录屏。
- [x] 给读回、像素转换、OCR/图像匹配和编码设置独立线程/队列。

退出条件：

- [x] 完整走通“识别双方阵容 -> 核对 -> 打开伤害面板 -> 结束并保存”；
- [x] 回放不出现核对页、伤害面板、悬浮菜单或悬浮球；
- [x] OCR 输入保持投屏原分辨率，现有 JVM 识别回归通过；
- [x] 识别期间录制继续，抽取的时间线检查点无黑帧，最终 AAC/H.264 轨完整且音画末 PTS 差为 63 ms。

### Phase 5：异常、性能和长时稳定

工作：

- [ ] 所有停止来源归一到幂等 `requestStop(reason)`。
- [ ] 增加空间和热状态监控。
- [ ] 10 分钟和 30 分钟三模式回归。
- [ ] 记录 PSS、CPU、游戏帧时间、编码丢帧、音画漂移、温度和文件大小。
- [ ] 根据真机数据固定码率；必要时加入 480p/20 fps 明确降级。
- [ ] 验证服务停止、App 返回、系统更新权限和进程重建后的状态。

退出条件：

- 30 分钟无崩溃、无持续内存增长、无不可播放文件；
- 游戏帧时间 P95 相比同场景无录屏基线增长不超过 10%；
- 10 分钟音画同步误差不超过 100 ms；
- 视频时间戳间隔统计的丢帧率低于 1%；
- `仅录屏` 的助手进程 PSS 峰值不超过 200 MB；
- `识别并录屏` 的助手进程 PSS 峰值不超过 300 MB，单次识别完成后能回落；
- `仅录屏` 不初始化 ML Kit、队伍模板和伤害 WebView；
- 设备不进入严重热状态；达到严重热状态时能自动收尾。

### Phase 6：产品化与发布

工作：

- [ ] 更新 App 内隐私说明、权限文案和录屏支持范围。
- [ ] 加入最近一次回放入口，只读取 MediaStore，不建立私有回放数据库。
- [ ] 更新 README、更新说明、第三方声明和发布检查清单。
- [ ] 跑 Node/TypeScript、Android 单元测试、lint、双 ABI Debug 构建。
- [ ] 在 ARM64 Release APK 上完成 RMX3820 和 OPD2409 最终验收。
- [ ] 检查升级安装、签名连续性和回放文件在升级后仍可访问。

退出条件：

- 三种模式在两台真机完成一局真实流程；
- 正式 APK 权限、版本、签名、ABI 和哈希全部核对；
- 文档不把尚未支持的 Android 13～15 录屏写成已支持；
- 只有真实通过的能力进入发布说明。

## 11. 验证矩阵

### 11.1 自动测试

- 状态机合法/非法转换和重复停止。
- 模式解析和 Service action。
- 固定画布的比例、黑边和旋转矩阵。
- 视频帧节流和 PTS 单调性。
- 音频样本数到 PTS 的换算。
- muxer 等待轨道、单轨/双轨、EOS 和异常状态。
- MediaStore pending 发布/清理策略。
- 捕获隔离标记检测。
- 现有 `CaptureBufferSpecTest`、截图取消和悬浮窗口状态回归。

### 11.2 编码器设备测试

- 用合成 EGL 帧生成 30 秒 MP4。
- 用 `MediaMetadataRetriever` 核对宽高、时长、轨道数量和可 seek。
- 编码器启动失败、输出格式延迟和 codec error。
- 旋转前后画面方向与比例。

### 11.3 真机手测

每台设备至少覆盖：

- 三种模式；
- 正常结束、通知结束、系统投屏芯片结束、锁屏；
- 游戏前后台切换、打开其他应用；
- 悬浮球、菜单、双方阵容核对、伤害面板；
- 游戏有声/静音、其他应用同时播放；
- 10 分钟和 30 分钟；
- 空间不足模拟和热状态观察；
- 回放在系统相册和至少一个第三方播放器中打开、拖动。

模拟器只用于纯 Kotlin、UI 和合成编码器回归；Pokemon Champions 安装、单应用隔离、游戏音频策略和最终性能必须以真机为准。

## 12. 最终验收

### 功能

- [ ] 悬浮球在创建 VirtualDisplay 前提供三种已确认模式。
- [ ] `仅识别` 不创建 MP4，不请求音频权限。
- [ ] `仅录屏` 不初始化 OCR/OpenCV、模板、伤害 WebView。
- [ ] `识别并录屏` 保持现有识别、核对和伤害计算闭环。
- [ ] 录屏停止后生成可播放、可 seek 的本地 MP4。

### 画面与声音

- [ ] 视频只包含用户选择的单个应用内容。
- [ ] 悬浮球、菜单、核对页、伤害面板、状态栏和通知不进入视频。
- [ ] 切到其他应用时不录入其他应用画面。
- [ ] 音频只来自 Pokemon Champions UID。
- [ ] 不录麦克风、通知或其他应用声音。

### 数据与隐私

- [ ] 录屏模块只发布最终 MP4。
- [ ] 不保存探针帧、原始 PCM、识别截图或旁车日志。
- [ ] 不上传、不自动同步、不自动分享。
- [ ] 损坏或未收尾的 pending 文件不进入系统相册。

### 稳定性

- [ ] 用户停止、系统芯片停止和锁屏都能有界收尾。
- [ ] 重复停止不会重复释放或崩溃。
- [ ] 旋转和尺寸变化不创建第二个 VirtualDisplay。
- [ ] 30 分钟无崩溃、持续泄漏和明显音画漂移。
- [ ] 录屏实现不降低现有截图 OCR 和悬浮面板可用性。

## 13. 主要风险与处理

| 风险 | 当前认识 | 处理 |
| --- | --- | --- |
| 游戏播放器禁止播放捕获 | RMX3820 已取得非静音游戏 PCM；设备差异仍可能存在 | OPD2409 继续跑 Phase 0 探针；不 Hook、不改用麦克风 |
| 用户误选整个屏幕 | 公共 API 不能预选或读取目标包名 | 明确引导 + 悬浮标记隔离自检；失败即拒绝录屏 |
| 一个 token 只能建一次 VirtualDisplay | Android 14+ 硬约束 | 模式确定后再创建；切换模式重新授权 |
| 组合模式破坏现有 OCR | 当前识别依赖原分辨率 ImageReader | 仅组合模式使用 EGL 按需原分辨率读回；仅识别原路径不动 |
| 当前服务职责过重 | 已接近千行且拥有多个控制器 | 新增 ReplayRecorder/RecognitionFeatureHost，Service 只编排 |
| ColorOS 阻止悬浮球拉起权限页 | 尚未验证 | 显式用户点击 Activity；失败时通知栏 PendingIntent 兜底 |
| 强杀后 MP4 无尾部 | MediaMuxer 固有限制 | pending 不发布，下次清理；不承诺强杀恢复 |
| 长时录制发热/掉帧 | 需真机数据 | 540p/24 fps、硬件编码、主动丢帧、热状态自动停止 |
| Android 13～15 行为差异 | 当前旋转修复以 API 36 最可靠 | 首发只开放 API 36，后续单独扩展 |

## 14. 预计改动范围

现有文件：

- `android-app/app/src/main/AndroidManifest.xml`
  - 增加 `RECORD_AUDIO`、游戏包查询和权限 Activity。
- `MainActivity.kt`
  - 更新录屏说明、状态、最近回放和权限回调。
- `OverlayCaptureService.kt`
  - 模式选择、延迟创建 VirtualDisplay、统一状态机和停止编排。
- `CaptureBufferSpec.kt`
  - 复用并补充录制画布变换，保持现有截图语义。

建议新增：

- `CaptureSessionMode.kt`
- `CaptureSessionStateMachine.kt`
- `RecognitionFeatureHost.kt`
- `ReplayPermissionActivity.kt`
- `replay/ReplayRecorder.kt`
- `replay/EglProjectionRouter.kt`
- `replay/ReplayVideoEncoder.kt`
- `replay/GameAudioCapture.kt`
- `replay/Mp4MuxerCoordinator.kt`
- `replay/ReplayMediaStore.kt`
- 对应 JVM 单元测试和 Android 设备测试。

## 15. 推荐执行顺序

1. 先做 Phase 0，不先写完整录屏功能。
2. 保住“仅识别”当前行为后再引入状态机。
3. 先得到可靠无声 MP4，再接游戏音频。
4. 纯录屏稳定后才做 EGL 原分辨率识别读回。
5. 最后做 30 分钟性能和发布验收。

这个顺序能在每一阶段都得到一个可独立判断的结果，避免音频、EGL、OCR、悬浮窗和 MP4 收尾同时出问题后无法定位。

## 16. Android 官方约束参考

- [App screen sharing](https://developer.android.com/about/versions/14/features/app-screen-sharing)
- [Media projection](https://developer.android.com/media/grow/media-projection)
- [Capture video and audio playback](https://developer.android.com/media/platform/av-capture)
- [AudioPlaybackCaptureConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)
- [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [MediaStore.MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)
- [Android 15 media projection status chip and auto stop](https://developer.android.com/about/versions/15/features#media-projection-status-bar-chip)
