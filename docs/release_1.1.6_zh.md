# 1.1.6 发布说明（Android / HarmonyOS）

发布日期：2026-08-02  
版本：`1.1.6 (11)`  
正式版标签：`v1.1.6`

对应源码：

- Android 标准版：`main`，构建提交 `94047cf8176c5e76f0493de40774a53c4dd4440a`
- Android 录屏功能版：`feature/battle-replay-phase-4`，构建提交 `3304eee0d7f9596bc558cdcf8c0414907bed763a`
- HarmonyOS 标准版与录屏功能版：`feature/harmonyos-port`，构建提交 `5ba0a9caa27bffd80d9e524ce34c47ff8a6d4fba`

`v1.1.5` 发布后才合入本轮我方队伍识别恢复性修复，因此本次递增为 `v1.1.6`，不移动或覆盖已经公开的旧标签。

> **HarmonyOS 重要提醒：本次 HarmonyOS 标准版和录屏功能版仍未经过真实设备安装、升级或功能测试。**
> 当前证据只覆盖源码/逻辑测试、Release 构建、包结构、变体隔离与签名验签。浮窗、跨应用截屏、OCR、录屏、内部音频、相册写入、权限拒绝/撤销、横竖屏、后台恢复和覆盖升级必须以 HarmonyOS ARM64 真机结果为准。

## 下载选择

| 平台 | 版本 | 文件 | 适用场景 |
| --- | --- | --- | --- |
| Android | 标准版（推荐） | `Pokemon-Champions-Assistant-v1.1.6-arm64.apk` | 队伍识别、伤害计算、传统悬浮面板和战斗 HUD |
| Android | 录屏功能版（可选） | `Pokemon-Champions-Assistant-v1.1.6-replay-arm64.apk` | 在标准能力之外，还需要保存本机 MP4 |
| HarmonyOS | 标准版（预览） | `Pokemon-Champions-Assistant-v1.1.6-harmonyos-standard.hap` | HarmonyOS 标准助手能力；未经过实机测试 |
| HarmonyOS | 录屏功能版（预览） | `Pokemon-Champions-Assistant-v1.1.6-harmonyos-replay.hap` | HarmonyOS 助手与录屏能力；未经过实机测试 |

Android 两个 APK 都只包含 `arm64-v8a`，使用同一应用 ID、版本号和生产签名，可互相覆盖并保留应用数据，但不能同时安装。`x86_64` APK 已完成本地构建校验，仅用于 Android Studio 模拟器，不上传 GitHub Release。

HarmonyOS 两个 HAP 是包含 `arm64-v8a` 与 `x86_64` Native 库的签名通用包，使用同一 bundle、版本号和项目发布证书。该本地 Release Provision Profile 不代表应用市场审核通过，也不保证所有商业 HarmonyOS 设备接受安装。

## 文件校验

- Android 标准版：`71,359,266` 字节  
  SHA-256：`FD546FEFC3F191C32AFE5171AF6051F7E069868B28EB8660B62114534D3B8EFE`
- Android 录屏功能版：`71,539,538` 字节  
  SHA-256：`1609E4B3C2637A34014AF997A48C3DD7258D1FA081AEFEA85FC6D9A580E20C4B`
- HarmonyOS 标准版：`39,113,600` 字节  
  SHA-256：`C2C3A6EDF6E3B94979916C1FE3F33F1FADB5996D4B3D9791A4DFDFAB1B572FFD`
- HarmonyOS 录屏功能版：`39,230,974` 字节  
  SHA-256：`053B8B7580BC6BA8701B2F42F0C50D18034882FD12BBF5357B390468F163F36B`

Android 生产签名证书 SHA-256：
`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`

HarmonyOS 项目发布证书 SHA-256：
`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`

## 1.1.6 变化

### 我方队伍识别可以安全重试

- 第一张“招式与道具”页完全没有识别到内容时，会结束并清理这次临时导入，回到可重新识别状态，不再用空白第一页推进到后续流程。
- “能力值”页识别不完整时仍会进入人工核对，避免已识别的第一张页面数据丢失。
- 人工核对页增加“放弃本次识别”，只清理 `pending-own-team` 与导入草稿；已保存队伍、当前对局和其他持久数据不会被删除。

### HUD 布局兼容

- “识别我方”按钮移到顶部操作区，减少对状态提示和伤害信息的遮挡。
- HUD 布局结构升级到版本 2；读取旧版布局时保留其他自定义部件位置，但让移动过的识别按钮采用新默认位置。
- Android 标准版、录屏功能版与 HarmonyOS 端保持同一恢复和迁移语义。

## 验证结果

### Android

- 标准版：Node/TypeScript 回归 `11/11`、Android JVM `118/118` 通过。
- 录屏功能版：Node/TypeScript 回归 `11/11`、Android JVM `153/153` 通过。
- 两个分支的 `lintRelease`、第三方许可证检查和 `npm.cmd audit --omit=dev --audit-level=high` 通过，依赖审计为 `0 vulnerabilities`。
- ARM64 与 x86_64 Release APK 分别通过版本、单 ABI、唯一生产签名、构建身份、识别特征包、许可证资源和网络权限检查。
- 本轮检测到 Android 设备 `6465e08`，但状态为 `offline`，因此没有执行 `1.1.6` 覆盖安装、冷启动或设备内 APK 哈希核对。

### HarmonyOS

- 标准版与录屏功能版均完成 Release 类型检查、ArkTS/Native 构建、HAP 签名和独立验签。
- 包级验证确认版本 `1.1.6 (11)`、同一 bundle/证书、预期页面与资源、标准/回放变体隔离，以及 `arm64-v8a,x86_64` ABI 集合。
- 综合测试共 83 项，81 项通过；旧的 220 项审计证据锁因产品文件与版本发生变化而按设计失败，x86 Native 预览测试因未连接 `127.0.0.1:5557` 模拟器而失败。
- 当前 `hdc` 目标列表为空；没有 E5 ARM64 HarmonyOS 真机证据，不能据此声称安装、权限、截屏/OCR、浮窗或录屏已经真机通过。

## 升级与反馈

- Android 用户可在应用“设置 → 检查更新”中打开本 Release，或直接下载与当前需求匹配的 ARM64 APK。请勿安装本地 x86_64 模拟器包。
- 标准版与录屏功能版使用同一应用 ID 和签名，覆盖安装应保留本地队伍、对局与设置；本轮未完成真机覆盖升级回归，重要数据建议先在设置中导出 JSON 备份。
- HarmonyOS 用户请把 HAP 文件名、设备型号、系统版本、CPU 架构、安装错误文本或可脱敏日志一并反馈；不要上传真实队伍截图、令牌、签名材料或个人备份。
- 项目是本地辅助工具，不修改、注入或 Hook 游戏进程，不读取游戏内存，不拦截网络，也不自动操作游戏。

## 已知边界

- Android `1.1.6` 未完成真机安装、覆盖升级、冷启动和真实游戏识别回归。
- HarmonyOS 两个版本均未经过真实设备安装、升级或功能测试；包级验签不等于设备可安装，也不等于跨应用截屏、OCR、浮窗或录屏可用。
- 自动识别结果仍必须由用户确认；伤害结果取决于当前填写的能力值、招式、特性、道具、场地和状态。
