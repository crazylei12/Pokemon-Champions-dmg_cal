# 1.1.7 发布说明（Android / HarmonyOS）

发布日期：2026-08-02

版本：`1.1.7 (12)`

正式版标签：`v1.1.7`

对应源码：

- Android 标准版：`main`，构建提交 `2ba8284b76c74fd610fe0b4a81ba4e649530e2a3`
- Android 录屏功能版：`feature/battle-replay-phase-4`，构建提交 `8ee17dc266f10de437ea98fae7be9ea42b7cffec`
- HarmonyOS 标准版与录屏功能版：`feature/harmonyos-port`，构建提交 `1386899a721b6a2db7325d2739a2d6f6b90db138`

`v1.1.6` 发布后才合入本轮 HUD 横竖屏恢复修复，因此本次递增为 `v1.1.7`，不移动或覆盖已经公开的旧标签。

> **HarmonyOS 重要提醒：本次 HarmonyOS 标准版和录屏功能版仍未经过真实设备安装、升级或功能测试。**
> 当前证据只覆盖源码/逻辑测试、Release 构建、包结构、变体隔离与签名验签。浮窗、跨应用截屏、OCR、录屏、内部音频、相册写入、权限拒绝/撤销、横竖屏、后台恢复和覆盖升级必须以 HarmonyOS ARM64 真机结果为准。

## 下载选择

| 平台 | 版本 | 文件 | 适用场景 |
| --- | --- | --- | --- |
| Android | 标准版（推荐） | `Pokemon-Champions-Assistant-v1.1.7-arm64.apk` | 队伍识别、伤害计算、传统悬浮面板和战斗 HUD |
| Android | 录屏功能版（可选） | `Pokemon-Champions-Assistant-v1.1.7-replay-arm64.apk` | 在标准能力之外，还需要保存本机 MP4 |
| HarmonyOS | 标准版（预览） | `Pokemon-Champions-Assistant-v1.1.7-harmonyos-standard.hap` | HarmonyOS 标准助手能力；未经过实机测试 |
| HarmonyOS | 录屏功能版（预览） | `Pokemon-Champions-Assistant-v1.1.7-harmonyos-replay.hap` | HarmonyOS 助手与录屏能力；未经过实机测试 |

Android 两个 APK 都只包含 `arm64-v8a`，使用同一应用 ID、版本号和生产签名，可互相覆盖并保留应用数据，但不能同时安装。`x86_64` APK 已完成本地构建校验，仅用于 Android Studio 模拟器，不上传 GitHub Release。

HarmonyOS 两个 HAP 是包含 `arm64-v8a` 与 `x86_64` Native 库的签名通用包，使用同一 bundle、版本号和项目发布证书。该本地 Release Provision Profile 不代表应用市场审核通过，也不保证所有商业 HarmonyOS 设备接受安装。

## 文件校验

- Android 标准版：`71,359,270` 字节；SHA-256：`87035DDFE44DA332D3C0A8499A1EB92A10E70EB8ADEB5E83299F02F852CA4714`
- Android 录屏功能版：`71,539,542` 字节；SHA-256：`43E5406E574E7C2ED199FEA8A5113BFA9330F4CE59A8BE13B211C98695EA173F`
- HarmonyOS 标准版：`39,112,883` 字节；SHA-256：`AB45B76D9E782248F0A282097AE8811770E2D9832121082217E75A27C67CFDF7`
- HarmonyOS 录屏功能版：`39,230,264` 字节；SHA-256：`684E0FAE5BD5C3EAF2EB222DE79E9703AC562B72EC3740F994E61813D0D12703`

Android 生产签名证书 SHA-256：
`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`

HarmonyOS 项目发布证书 SHA-256：
`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`

## 1.1.7 变化

### 横竖屏切换时保留战斗 HUD

- Android 战斗 HUD 现在会对已经挂载的窗口执行原地 `resize/move`，不再为了屏幕方向或安全区域变化销毁并重建整套 HUD，减少闪烁、焦点丢失和操作中断。
- 自定义 HUD 位置会按新的可用区域重新夹取；速度面板会按新的区域高度调整，编辑模式的拖动和缩放也使用切换后的实时边界。
- 显示变化回调会合并为一次立即重排和一次系统窗口稳定后的重排，避免同一轮旋转触发重复重建或回调风暴；服务销毁时会取消仍在等待的回调。

### HarmonyOS 同步恢复语义

- HarmonyOS 标准版与录屏功能版同样合并重复显示变化计时器，并在销毁协调器时取消未执行的重排。
- 每个 HUD 元素独立调整大小和位置；单个窗口调整失败只记录脱敏错误，不再阻断其他窗口继续恢复。
- 自动化契约明确禁止旋转恢复路径销毁并重建 HUD 窗口。

## 验证结果

### Android

- 标准版：Node/TypeScript 回归 `11/11`、Android JVM `118/118` 通过。
- 录屏功能版：Node/TypeScript 回归 `11/11`、Android JVM `153/153` 通过。
- 两个分支的 `lintRelease`、第三方许可证检查和 `npm.cmd audit --omit=dev --audit-level=high` 通过，依赖审计为 `0 vulnerabilities`。
- ARM64 与 x86_64 Release APK 分别通过版本、单 ABI、唯一生产签名、构建身份、识别特征包、许可证资源和网络权限检查。
- 用户确认已在 Android 真机完成发布前验证并授权直接发布。按用户要求，本轮没有重复执行 ADB 安装、包版本、设备内 APK 哈希或冷启动采证，因此这里不提供新的独立 ADB 证据。

### HarmonyOS

- 标准版与录屏功能版均完成 Release 类型检查、ArkTS/Native 构建、HAP 签名和独立验签。
- 包级验证确认版本 `1.1.7 (12)`、同一 bundle/证书、预期页面与资源、标准/回放变体隔离，以及 `arm64-v8a,x86_64` ABI 集合。
- 综合测试共 83 项，81 项通过；旧的 220 项审计证据锁因产品文件与版本发生变化而按设计失败，x86 Native 预览测试因未连接 `127.0.0.1:5557` 模拟器而失败。
- 当前没有 E5 ARM64 HarmonyOS 真机证据，不能据此声称安装、权限、截屏/OCR、浮窗、录屏或横竖屏恢复已经真机通过。

## 升级与反馈

- Android 用户可在应用“设置 → 检查更新”中打开本 Release，或直接下载与当前需求匹配的 ARM64 APK。请勿安装本地 x86_64 模拟器包。
- 标准版与录屏功能版使用同一应用 ID、`versionCode=12` 和生产签名，可互相覆盖并保留本地队伍、对局与设置；重要数据仍建议先在设置中导出 JSON 备份。
- HarmonyOS 用户请把 HAP 文件名、设备型号、系统版本、CPU 架构、安装错误文本或可脱敏日志一并反馈；不要上传真实队伍截图、令牌、签名材料或个人备份。
- 项目是本地辅助工具，不修改、注入或 Hook 游戏进程，不读取游戏内存，不拦截网络，也不自动操作游戏。

## 已知边界

- Android 真机验收结论来自用户确认；本轮自动化记录不含 ADB 安装、版本、设备内 APK 哈希或冷启动日志。
- HarmonyOS 两个版本均未经过真实设备安装、升级或功能测试；包级验签不等于设备可安装，也不等于跨应用截屏、OCR、浮窗、录屏或横竖屏恢复可用。
- 自动识别结果仍必须由用户确认；伤害结果取决于当前填写的能力值、招式、特性、道具、场地和状态。
