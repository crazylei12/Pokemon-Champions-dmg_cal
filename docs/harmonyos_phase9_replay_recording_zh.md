# HarmonyOS 阶段 9：录屏功能版验收记录

日期：2026-08-01

状态：**实现与模拟器可验范围已完成；真实 MP4、内部音频、媒体库发布和 30 分钟稳定性真机门未关闭**

## 1. 阶段边界

本阶段只移植 Android 录屏功能版已有的三种使用方式、单一画面源、内部应用音频、H.264/AAC-LC MP4 和媒体库保存。标准版仍从原主页面启动，不出现任何录屏入口；两个产品继续共享其余业务实现和相同 bundle 身份。

正式页面没有新增测试入口，也没有向用户暴露 OCR、OpenCV、缓冲队列或内部文件名。Debug 验收入口只接受命令行参数，并且只准备编解码器，不启动屏幕捕获、不弹出或接受隐私授权。

## 2. 已实现内容

### 2.1 三种模式与按需加载

- “识别并录屏”使用完整助手；启动对局助手时一次性准备原始帧捕获和录像；
- “仅识别”行为与标准版一致，不创建 MP4，之后可从悬浮入口单独开始录像；
- “仅录屏”停留在独立轻量页面，不进入完整助手，不加载识别、图鉴和伤害计算功能；
- 录屏功能版由 `REPLAY_ENABLED` 进入专用模式选择页，标准版在相同代码基线上直接进入原主页面；
- 录制中关闭识别或从识别会话追加录像都不创建第二个 AVScreenCapture 会话。

### 2.2 单一捕获源与视频路径

- AVScreenCapture 继续以 `OH_ORIGINAL_STREAM` 获取 `2772×1240` RGBA 原始帧；
- 同一个 `shared_ptr` 帧先进入有界录像队列，再按需要进入稳定帧识别；录像不会从系统重新抓第二份画面；
- 录像线程以最大 24 fps 接收帧，队列拥塞时丢弃旧帧，避免编码反压阻塞识别；
- RGBA 按比例居中转换到 `960×540` NV12，非 16:9 输入采用黑边保持比例，不拉伸或裁切；
- 使用 H.264 Baseline、CBR 4 Mbps、24 fps、2 秒关键帧间隔，视频 PTS 单调递增。

### 2.3 内部音频与 MP4 收尾

- 捕获配置只启用 `OH_APP_PLAYBACK` 内部音频，48 kHz 双声道；麦克风 source 固定为 `OH_SOURCE_INVALID`，麦克风回调不进入录像器；
- PCM 以 S16LE 输入 AAC-LC，目标 128 kbps；记录非静音样本数和峰值只用于设备验收，不显示在正式产品界面；
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

阶段 9 新增 5 项模式、状态机、单源分流、轻量入口和隐私边界测试；阶段 0、2–9 共 55 项测试全部通过。标准版和录屏功能版的 `arm64-v8a`、`x86_64` Native 代码与 ArkTS 均完成干净 Release 编译。

模拟器分别安装两个 Debug 包并验证：

- 录屏功能版可到达“识别并录屏”“仅识别”“仅录屏”三个正式入口；
- “仅录屏”进入独立控制页，标准版仍进入原主页面且没有录屏入口；
- 录屏 Profile 验收只调用 prepare，不调用 `startCapture`，因此没有出现或点击系统隐私授权；
- 模拟器对 H.264 encoder 返回 unavailable，结果固定记录为 `CodecPrepare=BLOCKED_BY_EMULATOR`；
- 两个变体均为 `Routes=PASS`、`ProductGate=PASS`、`PrivacyPromptClicked=False`。

可提交的 UI 层级证据为：

- `harmonyos/app/evidence/pc-stage9-replay-launch.json`；
- `harmonyos/app/evidence/pc-stage9-replay-record-only.json`；
- `harmonyos/app/evidence/pc-stage9-standard-home.json`。

最终 Release 包校验结果：

| 变体 | 字节 | SHA-256 |
| --- | ---: | --- |
| standard | 38,862,971 | `1a15150daf994914813686eba05f14d3ae903ee0fbddfe398f79a5d20b06c9d3` |
| replay | 38,863,633 | `ce83daae6df78602b34bd08aab1c750c6db932a5711a6595aff92a31a6018ee0` |

两包包含 `arm64-v8a` 和 `x86_64`，包结构、资源和变体元数据校验通过；仍为未配置发布签名的本地产物，不能称为可直接发布的签名包。

## 4. 未关闭的真实设备门

当前 DevEco 模拟器既不输出 AVScreenCapture 视频帧，也不提供 H.264 编码器，因此无法生成任何真实 MP4。以下结论必须在可用 HarmonyOS 真机上关闭：

1. 三种模式分别对相册本地测试视频执行真实捕获，“仅识别”不产出文件，“仅录屏”不加载完整助手，“识别并录屏”不中断识别；
2. 生成 MP4 可在相册完整播放，方向、比例、时长、24 fps 和 960×540 正确，音画无明显漂移；
3. AAC 轨有来自所选应用的非静音信号，静音片段正确，且不含麦克风、通知或其他应用声音；
4. 悬浮球、菜单、确认页、面板、HUD、系统栏和其他应用不进入单窗口录像；
5. 系统停止、撤销授权、编码失败、空间不足和进程被杀后没有损坏的公开媒体或残留临时文件；
6. 媒体库系统确认的允许、拒绝和取消分支均符合保留/清理规则；该确认必须由设备使用者本人决定；
7. 连续 30 分钟记录温度、帧率、内存、文件大小、丢帧和识别响应，确认性能在可接受范围。

这些门没有被 C++ 编译成功、静态状态机或模拟器的预期能力错误冒充为通过。

## 5. 阶段退出结论

阶段 9 的产品入口、三模式、单一原始帧源、H.264/AAC-LC/MP4 Native 实现、内部应用音频配置、媒体库发布、异常收尾、标准版隔离、双 ABI 构建和全部模拟器可验证门均已完成，可以进入阶段 10。真实文件、声音、隔离和长时间稳定性继续作为最终真机验收门。
