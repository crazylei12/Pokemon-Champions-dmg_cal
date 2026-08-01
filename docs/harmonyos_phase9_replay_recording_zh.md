# HarmonyOS 阶段 9：录屏功能版验收记录

日期：2026-08-01

状态：**实现与模拟器可验范围已完成；真实 MP4、内部音频、媒体库发布和 30 分钟稳定性真机门未关闭**

## 1. 阶段边界

本阶段只移植 Android 录屏功能版已有的录制状态与控制、单一画面源、内部应用音频、H.264/AAC-LC MP4 和媒体库保存。标准版与录屏功能版都从同一原主页面启动；标准版不出现任何录屏入口，两个产品继续共享其余业务实现和相同 bundle 身份。

正式页面没有新增测试入口，也没有向用户暴露 OCR、OpenCV、缓冲队列或内部文件名。Debug 验收入口只接受命令行参数，并且只准备编解码器，不启动屏幕捕获、不弹出或接受隐私授权。

## 2. 已实现内容

### 2.1 与 Android 一致的集成入口

- 录屏功能版不再进入专用模式选择页，也不提供我误加的“仅识别/仅录屏/识别并录屏”启动器；
- 它与标准版一样进入原主页面和原对局页，只在对局说明、普通悬浮菜单及 HUD 录像部件中显示 Android 已有的录制控制；
- 开始或结束录像是现有助手会话内的独立操作，不关闭识别、悬浮核对、伤害面板或 HUD；
- 录制中关闭识别或从识别会话追加录像都不创建第二个 AVScreenCapture 会话。

### 2.2 单一捕获源与视频路径

- AVScreenCapture 继续以 `OH_ORIGINAL_STREAM` 获取 `2772×1240` RGBA 原始帧；
- 同一个 `shared_ptr` 帧先进入有界录像队列，再按需要进入稳定帧识别；录像不会从系统重新抓第二份画面；
- 录像线程以最大 24 fps 接收帧，队列拥塞时丢弃旧帧，避免编码反压阻塞识别；
- RGBA 按比例居中转换到编码器实际接受的 NV12 画布，非 16:9 输入采用黑边保持比例，不拉伸或裁切；
- 严格保留 Android 的 H.264 Baseline/CBR 能力降级链：优先 `960×540 / 24 fps / 1.5 Mbps`，依次回退到 `854×480 / 20 fps / 1 Mbps`、`640×360 / 20 fps / 750 kbps`，关键帧间隔 2 秒，视频 PTS 单调递增。

### 2.3 内部音频与 MP4 收尾

- 捕获配置只启用 `OH_APP_PLAYBACK` 内部音频，48 kHz 双声道；麦克风 source 固定为 `OH_SOURCE_INVALID`，麦克风回调不进入录像器；
- PCM 以 S16LE 输入 AAC-LC，目标 96 kbps；记录非静音样本数和峰值只用于设备验收，不显示在正式产品界面；
- AAC 初始化失败或录像启动后持续没有内部播放音频信号时，产品明确显示“改为无声录制”和“取消本次录像”，不会静默丢弃音轨；选择无声继续时删除部分文件并在同一截图会话上重新开始无音轨 MP4；
- 视频、音频均写入应用私有目录中的 MPEG-4 muxer，分别维护时间戳、EOS 和编码输出计数；
- 正常停止只收尾一次；系统停止、通话/用户切换中断和捕获错误会关闭输入并保留已有帧，供用户完成收尾；
- 没有有效视频帧、编码失败、取消会话或未完成输出会删除私有临时文件，不发布损坏文件；
- 完成文件通过系统 `showAssetsCreationDialog` 请求保存到媒体库，只有系统返回目标 URI 且复制成功后才删除私有源；用户取消时保留“再次保存”入口。

实现依据采用 HarmonyOS 官方的 [AVCodec Buffer Mode 示例](https://gitee.com/harmonyos_samples/avcodec-buffer-mode/blob/master/README.en.md) 和 OpenHarmony 官方 [音视频封装开发说明](https://gitee.com/openharmony/docs/blob/2faa5b48479b16be679f55b49ad56ba9dcb0a2db/en/application-dev/media/avcodec/audio-video-muxer.md)，并以当前 API 24 SDK 头文件为实际编译契约。

## 3. 自动化、构建与模拟器结果

执行：

```powershell
npm.cmd run harmonyos:phase9:check
npm.cmd run harmonyos:phase9:emulator
node --test tools/harmonyos/phase0-contracts.test.mjs tools/harmonyos/phase2-scaffold.test.mjs tools/harmonyos/phase3-domain.test.mjs tools/harmonyos/phase4-storage.test.mjs tools/harmonyos/phase5-main-ui.test.mjs tools/harmonyos/phase6-own-team-ocr.test.mjs tools/harmonyos/phase7-team-preview.test.mjs tools/harmonyos/phase8-battle-overlay.test.mjs tools/harmonyos/phase9-replay-recording.test.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode release -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-packages.ps1 -BuildMode release
```

最终全阶段静态回归共 59 项全部通过；阶段 9 覆盖产品门、状态机、单源分流、集成入口和隐私边界。标准版和录屏功能版的 `arm64-v8a`、`x86_64` Native 代码与 ArkTS 均完成干净 Release 编译。

模拟器分别安装两个 Debug 包并验证：

- 录屏功能版直接进入原主页面，原对局页包含“开始录屏”、`960×540 / 24 fps` 和 HUD 入口说明；
- 标准版进入同一原主页面与对局页，但不包含任何录屏专属文案；
- 录屏 Profile 验收只调用 prepare，不调用 `startCapture`，因此没有出现或点击系统隐私授权；
- 模拟器对 H.264 encoder 返回 unavailable，结果固定记录为 `CodecPrepare=BLOCKED_BY_EMULATOR`；
- 两个变体均为 `Routes=PASS`、`ProductGate=PASS`、`PrivacyPromptClicked=False`。

可提交的 UI 层级证据为：

- `harmonyos/app/evidence/pc-stage9-replay-battle.json`；
- `harmonyos/app/evidence/pc-stage9-standard-battle.json`。

最终 Release 包校验结果：

| 变体 | 字节 | SHA-256 |
| --- | ---: | --- |
| standard | 38,925,439 | `10f66679bfe8c95420032edacf73d6a75b2c5af206f07bafa616f1c5994034d8` |
| replay | 38,926,409 | `41ef690e3c7caf2ab9c0d23f759d9b4ebfa57d239be348d8af657d916fff984f` |

两包包含 `arm64-v8a` 和 `x86_64`，包结构、资源和变体元数据校验通过；仍为未配置发布签名的本地产物，不能称为可直接发布的签名包。

## 4. 未关闭的真实设备门

当前 DevEco 模拟器既不输出 AVScreenCapture 视频帧，也不提供 H.264 编码器，因此无法生成任何真实 MP4。以下结论必须在可用 HarmonyOS 真机上关闭：

1. 在现有助手会话中分别验证不录像、开始录像和结束录像，确认录制不关闭识别、核对、面板或 HUD；
2. 生成 MP4 可在相册完整播放，方向、比例和时长正确；优先规格为 960×540 / 24 fps，不支持时必须只落入已声明的两个降级规格，音画无明显漂移；
3. AAC 轨有来自所选应用的非静音信号，静音片段正确，且不含麦克风、通知或其他应用声音；
4. 悬浮球、菜单、确认页、面板、HUD、系统栏和其他应用不进入单窗口录像；
5. 系统停止、撤销授权、编码失败、空间不足和进程被杀后没有损坏的公开媒体或残留临时文件；
6. 媒体库系统确认的允许、拒绝和取消分支均符合保留/清理规则；该确认必须由设备使用者本人决定；
7. 连续 30 分钟记录温度、帧率、内存、文件大小、丢帧和识别响应，确认性能在可接受范围。

这些门没有被 C++ 编译成功、静态状态机或模拟器的预期能力错误冒充为通过。

## 5. 阶段退出结论

阶段 9 的 Android 等价集成入口、单一原始帧源、H.264/AAC-LC/MP4 Native 实现、内部应用音频配置、媒体库发布、异常收尾、标准版隔离、双 ABI 构建和全部模拟器可验证门均已完成。真实文件、声音、隔离和长时间稳定性继续作为最终真机验收门。
