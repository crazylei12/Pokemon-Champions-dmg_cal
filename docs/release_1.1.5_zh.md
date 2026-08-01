# 1.1.5 发布说明（Android / HarmonyOS）

发布日期：2026-08-02

版本：`1.1.5 (10)`

正式版标签：`v1.1.5`

源码分支：

- Android 标准版：`main`
- Android 录屏功能版：`feature/battle-replay-phase-4`
- HarmonyOS 标准版与录屏功能版：`feature/harmonyos-port`

> **HarmonyOS 重要提醒：本次 HarmonyOS 标准版和录屏功能版均未经过任何真实设备安装、升级或功能测试。**
> 当前证据只覆盖源码检查、自动化逻辑测试、Release 构建、包结构、变体隔离与签名验签。浮窗、跨应用截屏、OCR、录屏、内部音频、相册写入、权限拒绝/撤销、横竖屏、后台恢复和覆盖升级仍必须以 HarmonyOS ARM64 真机结果为准。若遇到安装失败、闪退、权限、识别、录屏或界面问题，请及时反馈。

## 下载与选择

| 平台 | 版本 | 文件 | 适用场景 |
| --- | --- | --- | --- |
| Android | 标准版（推荐） | `Pokemon-Champions-Assistant-v1.1.5-arm64.apk` | 队伍识别、伤害计算、传统悬浮面板和战斗 HUD |
| Android | 录屏功能版（可选） | `Pokemon-Champions-Assistant-v1.1.5-replay-arm64.apk` | 在标准能力之外，还需要保存本机 MP4 |
| HarmonyOS | 标准版（预览） | `Pokemon-Champions-Assistant-v1.1.5-harmonyos-standard.hap` | HarmonyOS 标准助手能力；未经过实机测试 |
| HarmonyOS | 录屏功能版（预览） | `Pokemon-Champions-Assistant-v1.1.5-harmonyos-replay.hap` | HarmonyOS 助手与录屏能力；未经过实机测试 |

Android 公开 APK 仅包含 `arm64-v8a`。本地生成的 Android `x86_64` APK 只用于模拟器构建校验，不上传 Release。

HarmonyOS HAP 是包含 `arm64-v8a` 与 `x86_64` Native 库的签名通用包。它们使用项目首次建立的 HarmonyOS 发布证书和本地 OpenHarmony Release Provision Profile；这不代表已经通过应用市场审核，也不代表商业 HarmonyOS 设备一定接受该本地分发配置。

## 文件大小与 SHA-256

- Android 标准版：`71,359,266` 字节  
  SHA-256：`4CB6BEB02D9809609E2931B150EE8E1F422669D5C9704799E7F37EA69C5E0413`
- Android 录屏功能版：`71,523,158` 字节  
  SHA-256：`B7CE12465B86F6B316CB3E80C63C8F6E97B2DC4AE3D5F82F9631824B19443B77`
- HarmonyOS 标准版：`39,106,433` 字节  
  SHA-256：`27F79B15C4F0E2824F6C165974F0ADCCAD54CB43E9839221596FA2BD473EA5B8`
- HarmonyOS 录屏功能版：`39,232,004` 字节  
  SHA-256：`30887803EEB32FA86A0DE96F1B71906E2B7EABF8E40DE4CCB0CCCEEB2D1DB5CF`

Android 生产签名证书 SHA-256：

`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`

HarmonyOS 项目发布证书 SHA-256：

`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`

## 1.1.5 变化

### 百变怪队伍保存

- 百变怪现在按其唯一可学习招式处理：确认 `变身 / Transform` 后即可通过我方队伍保存校验。
- 百变怪没有招式时仍会被拦截。
- 其他所有宝可梦仍然要求四个不重复招式；本次例外没有扩大到其他物种。
- 人工核对页会为百变怪显示 `1/1`，普通宝可梦继续显示 `4/4`。

### 我方队伍 HUD 识别

- 战斗 HUD 的“识别我方”按钮现在显示识别中、等待和结果状态；进行中会禁用重复点击。
- 第二页采集会按照当前导入流程明确按“能力值页”解释，避免弱 OCR 证据把页面错误归类为招式页。
- 单页识别失败时仍会生成六个明确的人工核对槽位，不再留下无反馈或无法继续的流程。
- 新识别会清理不应沿用的旧对局状态；关闭识别执行器时会取消未完成任务，降低迟到回调覆盖当前界面的风险。

Android 与 HarmonyOS 代码都保留了百变怪一招例外和我方双页识别/人工核对语义；HarmonyOS 的实际相机、相册、截屏、OCR 与浮窗闭环仍未经过真机验证。

## 安装与升级

### Android

- 两个 Android 版本使用同一应用 ID、`versionCode=10` 和固定生产签名，不能同时安装，但可以互相覆盖并保留应用私有目录中的队伍、预设、会话、HUD 布局和设置。
- 最低系统仍为 Android 13（API 33）。录屏入口仍只在 Android 16（API 36）开放。
- 本轮已将 Android 录屏功能版正式 ARM64 APK 覆盖安装到 `RMX3820`，ADB 返回 `Success`；设备端显示 `1.1.5 (10)`，冷启动成功，设备内 APK SHA-256 与本地文件一致。
- 本轮没有把 Android 标准版再次安装到真机；标准版只完成了构建与包级校验。

### HarmonyOS

- 两个 HarmonyOS 版本使用同一 bundle、`versionCode=10` 和同一项目发布证书，设计为互相覆盖而不是同时安装。
- 目标与兼容 SDK 为 HarmonyOS `6.1.1(24)`，当前 module 声明的设备类型为 `phone`。
- **本轮没有 HarmonyOS 真机，因此没有验证 HAP 能否在商业设备安装、同签名覆盖升级或保留用户数据。**
- 如果系统拒绝本地 HAP，可能涉及设备开发者模式、分发 Profile、系统版本或厂商策略；请保留完整错误码，不要先卸载旧版或清除数据。

## 验证记录与边界

### Android

- 标准版：Node 回归 `11/11`、Android JVM `115/115`、`lintRelease`、许可证、依赖审计和 ARM64/x86_64 单 ABI Release 校验通过。
- 录屏功能版：Node 回归 `11/11`、Android JVM `150/150`、`lintRelease`、许可证、依赖审计和 ARM64/x86_64 单 ABI Release 校验通过。
- 两个分支的生产依赖审计均为 `0 vulnerabilities`。
- Android 标准版源码提交：`78a40d5b754b0dd76252ffa0a6e4d89cb259310b`。
- Android 录屏功能版源码提交：`623000c9dba697f64c59f389282384e6c4775199`。

### HarmonyOS

- 标准/录屏 Release 均完成 ArkTS、Native `arm64-v8a` / `x86_64` 编译、HAP 打包、代码签名和证书链验签。
- 包级校验确认版本、bundle、应用标签、17/18 个正式页面、权限最小化、9 个运行资源、标准版无录屏实现、录屏版包含录屏实现，且两变体的 Native 库并非字节相同。
- 共享 Node 回归 `11/11`、许可证检查与生产依赖审计通过，生产依赖为 `0 vulnerabilities`。
- HarmonyOS 综合测试共 82 项：80 项通过；旧的 220 项审计证据锁因 1.1.5 产品文件发生变化而按设计失败，另 1 项需要当前未连接的 HarmonyOS 模拟器 `127.0.0.1:5557`。这两项均不计为通过。
- HarmonyOS 源码提交：`cc9faf1b71843740d63a2187a8932d79bf108407`。
- **没有 E5 ARM64 HarmonyOS 真机证据，不能据此声称鸿蒙端已完成发布就绪验收。**

## 问题反馈

请在项目 Issue 中及时反馈，并尽量附上：

- 使用的文件名：HarmonyOS 标准版或录屏功能版；
- 设备型号、HarmonyOS 版本、CPU 架构；
- 安装、启动、授权、浮窗、识别、录屏或保存失败的完整步骤；
- 系统错误码、日志、截图或录屏；
- 是否从旧版覆盖安装、是否保留了旧数据；
- 问题是否能稳定复现。

项目地址：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal>

问题反馈：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/issues>

## 已知限制与权利声明

- HarmonyOS 两个版本均未经过实机测试；尤其不能把源码检查或 HAP 验签等同于跨应用截屏、OCR、浮窗和录屏可用。
- Android 录屏能否捕获游戏内部声音仍受 Android、设备厂商实现与游戏播放策略共同约束。
- 应用内更新只接受经过安全元数据校验、来自本仓库 GitHub Release 的匹配资产；Android 与 HarmonyOS 资产不会混用。
- 本项目是非官方工具。Pokémon、Pokémon Champions 及相关名称、角色、图像和商标属于各自权利人；项目不获得也不暗示任何官方认可。完整归属与许可证边界见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

