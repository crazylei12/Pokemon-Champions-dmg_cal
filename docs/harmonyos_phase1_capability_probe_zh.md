# HarmonyOS 阶段 1：系统能力探针记录

- 日期：2026-08-01
- 分支：`feature/harmonyos-port`
- 探针包名：`com.crazylei12.pokemonchampions.capabilityprobe`
阶段状态：**模拟器范围已完成；真机验收门延期，不代表真机能力已通过**

本文只记录《HarmonyOS 全量移植方案》阶段 1 的真实结果。探针不承载产品功能；任何模拟器不支持的媒体能力都保留为真机验收门，不能据此删除 Android 原功能或把未测项写成通过。

2026-08-01，用户确认当前没有 HarmonyOS 真机，并明确要求先在模拟器继续后续阶段。因此阶段 1 按“模拟器范围完成、真机债务保留”封存，允许进入阶段 2；这项推进决定只调整阶段顺序，不降低最终完成定义，也不允许以模拟器结果代替真实设备结论。

模拟器录屏限制依据：[华为官方《录屏》使用约束](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-screen-recording)（明确说明模拟器不支持录屏）。

## 1. 探针工程和固定环境

- 探针工程：`harmonyos/probe`；
- DevEco Studio：`D:\HarmonyOS\DevEcoStudio`；
- SDK：OpenHarmony API 24 / 6.1.1.125；
- 当前模拟器系统：`emulator 6.1.0.126(SP1DEVC00E120R4P11)`；
- 当前模拟器目标：`127.0.0.1:5555`；
- 当前竖屏渲染尺寸：`1240×2772`，最终相册验收仍需旋转为 `2772×1240`；
- OpenCV 源码：`D:\HarmonyOS\ThirdParty\opencv-4.13.0`，提交 `fe38fc608f6acb8b68953438a62305d8318f4fcd`；
- OpenCV x86_64 构建：`D:\HarmonyOS\Build\opencv-4.13.0-x86_64`；
- OpenCV arm64-v8a 构建：`D:\HarmonyOS\Build\opencv-4.13.0-arm64-v8a`。
- MP4 验收工具：`D:\HarmonyOS\Tools\ffmpeg\bin\ffprobe.exe`，版本 `8.1.2-essentials_build`；安装包 SHA-256 与发布方校验值均为 `DB580001CAA24AC104C8CB856CD113A87B0A443F7BDF47D8C12B1D740584A2EC`。

项目同时打包 `arm64-v8a` 和 `x86_64` 的 `libpcprobe.so`。2026-08-01 的双架构 `assembleHap` 已通过，HAP 信息如下：

| 项目 | 值 |
| --- | --- |
| 文件 | `harmonyos/probe/entry/build/default/outputs/default/entry-default-unsigned.hap` |
| 大小 | `24,173,400` bytes |
| SHA-256 | `F0332DFCE1A201B0C01CA1740E86931166D623CEA9C15B8465513A84B4A26B8D` |
| 签名 | 当前为模拟器安装用 unsigned HAP；发布签名不是本阶段已验证结论 |

现有队伍预览模板以原文件打包，没有重生成：

| 项目 | 值 |
| --- | --- |
| 文件大小 | `12,550,272` bytes |
| 源文件 SHA-256 | `0BDE8C79D76B9E8FF55D77F45E2C8D974703C342FAF12FC2BB9220B68E87460F` |
| 包内文件 SHA-256 | `0BDE8C79D76B9E8FF55D77F45E2C8D974703C342FAF12FC2BB9220B68E87460F` |

## 2. 已实现的探针

探针界面目前提供以下独立入口：

- 创建、点击和移除 `TYPE_FLOAT` 悬浮窗；
- 对固定 ArkUI 文本组件调用 Core Vision 文字识别；
- 由 ArkWeb 加载包内 `rawfile/arkweb_probe.html` 并执行固定 JavaScript；
- 应用沙箱文件写入、读回和备份扩展注册；
- 系统相册 `PhotoViewPicker`；
- Native `OH_AVScreenCapture_Create/Release`；
- OpenCV 4.13 读取包内模板二进制并执行确定性 `matchTemplate`；
- 原始 RGBA 帧捕获准备、启动、窗口选择、帧/音频/时间戳统计、按需保存下一帧和统一释放；
- H.264/AAC-LC/MP4 文件录制准备、启动、停止和统计。
- 模拟器诊断专用的“指定 mission ID”原始流和 MP4 路径；当前相册任务为 mission `35`。正式产品仍使用系统窗口选择器，不写死任务 ID。
- 通过系统媒体库授权弹窗把沙箱 MP4 发布到相册，再由相册播放验收。

原始帧回调记录视频帧数、内部音频 buffer 数、麦克风 buffer 数、首帧大小、首帧哈希、首尾时间戳、状态码和错误码。探针还可以把指定的下一帧完整保存为 RGBA 文件，并报告字节数、行跨度、完整帧哈希和时间戳；`tools/harmonyos/inspect_probe_rgba.py` 负责校验 `stride×height`、裁出可见像素并生成 PNG 证据。MP4 配置显式使用 H.264、AAC-LC、48 kHz 双声道内部应用音频，不启用麦克风；`tools/harmonyos/inspect_probe_mp4.ps1` 用 ffprobe 强制检查单 H.264 视频轨、单 AAC 立体声音轨、48 kHz、最小时长和音视频时长差。两份检查工具均已用合成 RGBA/MP4 输入完成自检。

## 3. 当前结果

| 能力 | 当前结果 | 证据/说明 |
| --- | --- | --- |
| `TYPE_FLOAT` 创建 | 通过（模拟器） | 能创建、移动和显示，主页面报告 `PROBE_FLOAT_PASS` |
| 悬浮窗交互 | 通过（模拟器） | 点击后文本变为 `FLOAT_WINDOW_INTERACTION_PASS`；见 `pc-stage1-float-interaction.png/json` |
| 悬浮窗移除 | 通过（模拟器） | `PROBE_FLOAT_REMOVED`；见 `pc-stage1-float-removed.png` |
| ArkWeb 包内页面 | 通过（模拟器） | 固定返回 `{"value":73,"source":"local-fixed-js-v1"}`；见 `pc-stage1-runtime-pass.png` |
| 应用沙箱写读 | 通过（模拟器） | 文件为 `/data/storage/el2/base/haps/entry/files/stage1-probe.json` |
| 备份扩展注册 | 已编译/已安装，尚未做系统备份恢复 | 不能用沙箱读写替代系统备份验收 |
| 系统相册选择器 | 通过（模拟器） | 能打开、选择一张媒体并返回 `PROBE_PHOTO_PICKER count=1`；为避免提交验收图片缩略图，不保留选择器图库截图 |
| AVScreenCapture 对象 | 通过（模拟器） | Create/Release 返回 code `0`；见 `pc-stage1-runtime-pass.png` |
| OpenCV x86_64 | 通过 | 模板头 `PTVFEAT2`、version `2`、`96×16`、1016 条记录、匹配分数 `1.0` |
| OpenCV arm64-v8a | 构建通过，真机运行未测 | `libtegra_hal.a` 已按 arm64 架构链接；真机仍是阶段门 |
| 原始捕获初始化 | 通过（模拟器） | code `0`，当前竖屏配置 `1240×2772`；见 `pc-stage1-raw-prepared.json` |
| 屏幕捕获用户授权 | 通过（模拟器） | 用户亲自点击允许后，状态回调为 `OH_SCREEN_CAPTURE_STATE_STARTED`（code `0`）；指定相册 mission `35` 的初始化、策略设置和启动均为 code `0`。授权界面见 `capture-consent-mission35.jpeg` |
| 相册单窗口连续 20 帧 | 模拟器不支持，真机门未关闭 | 指定 mission `35` 的会话运行约 111 秒，内部音频回调累计 5558 个 buffer，但视频回调始终为 0 帧；中途统计见 `probe-stats.json`。华为官方 DevEco 录屏文档明确写明“模拟器不支持录屏”，因此不再重复请求无效授权，必须转到真机验证 |
| 助手浮层不入捕获帧 | 未测 | 需在单窗口帧流启动后创建浮窗，并比较帧哈希/导出帧 |
| Core Vision OCR | 模拟器不支持，真机门未关闭 | API 可编译，但模拟器运行时报 `Cannot read property recognizeText of undefined`；见 `pc-stage1-ocr.png/json` |
| 断网二次 OCR | 未测 | 必须在支持 Core Vision 的真实 HarmonyOS 设备完成 |
| 3 分钟 H.264/AAC-LC MP4 | 模拟器不可验，真机门未关闭 | 官方限制为模拟器不支持录屏；需在真机生成文件，再用 `inspect_probe_mp4.ps1` 检查并发布到相册 |
| 内部音频且无麦克风 | 部分通过（模拟器），真机门未关闭 | 原始流会话有 5558 个内部音频 buffer、麦克风为 0；仍需用真机 MP4 的实际 AAC 音轨和有声/静音片段核对，不能仅凭 callback 数量判定最终通过 |
| 拒绝/撤销/旋转/编码失败清理 | 未测 | 阶段出口要求全部无残留窗口、会话和公开损坏文件 |
| 更新下载与系统安装确认 | 未测 | 只验证能力，不在探针中伪造产品更新链路 |

## 4. 已确认的技术决定

1. 悬浮窗采用系统 `TYPE_FLOAT`，当前普通调试包在模拟器可用，但目标发布签名仍须真机验证。
2. 截图和录屏统一基于 Native AVScreenCapture。识别用 `OH_ORIGINAL_STREAM` 原始 RGBA 帧，录像用 `OH_CAPTURE_FILE` 的 H.264/AAC-LC/MP4 路径。
3. 单窗口选择必须在用户完成系统屏幕捕获授权之后发起；授权前调用 `PresentPicker` 会返回 `operation not permitted`，不再把该失败当成产品错误。
   当前 API 24 手机模拟器在授权后调用 `SetPickerMode` 和 `PresentPicker` 仍返回 code `2`，故探针额外提供指定 mission ID 的诊断入口；该入口只用于确认问题归因，不能替代正式产品的系统窗口选择器。
4. OpenCV 固定在 4.13.0，并同时构建 x86_64 与 arm64-v8a；现有模板二进制按原哈希打包。
5. Core Vision 在当前模拟器不可运行，因此 OCR 结论不得写成通过，也不得换成较弱功能来删减原应用能力。
6. 最终端到端仍采用用户指定的相册全屏方案：横屏渲染和图片均为 `2772×1240`，先完成 1:1 门槛再测非 1:1 映射。

## 5. 阶段出口剩余执行顺序

1. 连接支持 Core Vision 的真实 HarmonyOS 设备；当前 `hdc list targets` 只有 `127.0.0.1:5555` 模拟器，因此这是阶段 1 的实际外部阻塞点；
2. 在真机安装双 ABI 探针，重新取得相册 mission ID，并先验证正式系统窗口选择器；指定 mission ID 入口只作对照诊断；
3. 将真机旋转到横屏 `2772×1240`，打开固定图片并隐藏相册工具栏；
4. 由用户在系统隐私弹窗作出授权选择后，连续取得至少 20 帧，核对尺寸、方向、非黑帧、时间戳和哈希稳定性；
5. 在捕获运行时显示悬浮窗，确认捕获帧不含助手浮层；
6. 录制 3 分钟固定有声/静音视频，停止后发布到媒体库并在相册检查可播放性、方向、时长和音画同步；
7. 依次执行拒绝、撤销、旋转、系统停止、编码失败和进程被杀清理用例；
8. 完成 Core Vision 中英文字、数字、坐标、断网二次运行、arm64 OpenCV、系统备份恢复、更新安装确认和发布签名检查；
9. 真机可用后补齐上述证据；在此之前本文件不得改成“真机通过”。本次按用户明确的模拟器继续决定进入阶段 2，最终阶段 10 仍必须把这些项目列为未关闭验收门。

## 6. 构建命令

在 `harmonyos/probe` 下执行：

```powershell
$env:DEVECO_SDK_HOME='D:\HarmonyOS\DevEcoStudio\sdk'
$env:OHOS_SDK_HOME='D:\HarmonyOS\DevEcoStudio\sdk'
$env:NODE_HOME='D:\HarmonyOS\DevEcoStudio\tools\node'
$env:JAVA_HOME='D:\HarmonyOS\DevEcoStudio\jbr'
$env:PATH='D:\HarmonyOS\DevEcoStudio\jbr\bin;D:\HarmonyOS\DevEcoStudio\tools\node;D:\HarmonyOS\DevEcoStudio\tools\hvigor\bin;' + $env:PATH
& 'D:\HarmonyOS\DevEcoStudio\tools\hvigor\bin\hvigorw.bat' --no-daemon clean assembleHap
```

通过标准：命令退出码为 `0`，并在同一次构建中生成 arm64-v8a、x86_64 两份 `libpcprobe.so` 以及 `entry-default-unsigned.hap`。
