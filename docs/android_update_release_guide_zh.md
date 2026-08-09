# Android 版本、检查更新与发布渠道

日期：2026-08-09

## 1. 当前实现

Android App 从 `package.json` 读取统一版本：

- `version`：用户可见的语义化版本，例如 `1.0.0`。
- `androidVersionCode`：Android 安装系统使用的正整数，每次发布必须严格递增。

当前正式版本为 `1.1.8 (13)`。App 设置页会显示这两个值，并在用户主动点击“检查更新”时访问下面的发布源：

```text
https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases
```

这里的地址只是 App 的更新来源。它不会修改、替换或新增当前本地仓库的 Git 远端。

## 2. 更新频道

### 稳定版

- 默认频道。
- 查询 GitHub 的 latest release。
- 只接收已发布、非 Draft、非 Pre-release 的正式版本。

### 预览版

- 用户可在设置页手动切换，选择会保存在 App 本机。
- 读取最近的 Releases，同时接受正式版本和标记为 Pre-release 的版本。
- 按语义化版本比较并选择最高版本；Draft 和无法解析的标签不会进入更新候选。

两个频道都只在用户点击按钮时联网。App 不保存 GitHub Token，也不会主动上传截图、队伍、对局状态或伤害计算输入；用户系统启用 Android 加密云备份时，系统服务可按备份规则同步所选 JSON 数据，这不经过 App 的 GitHub 更新通道。GitHub 公共 REST API 的未认证请求有频率限制，因此界面不自动轮询，遇到限制时提示稍后重试。

## 3. 用户更新流程

```text
设置 -> 选择更新频道 -> 检查更新
  -> 没有 Release：显示尚未发布
  -> 已是最新版：显示当前版本
  -> 有新版本：显示标签、标题、版本说明和下载入口
```

项目支持两个严格分离的单 ABI 目标：`arm64-v8a` 用于真机和正式 Release，`x86_64` 仅用于 Android Studio 模拟器。正式 Release 只上传 ARM64 APK；不构建 32 位 ARM、x86 或 universal APK。只发布手机版时应使用 ARM64 专用命令，避免额外编译模拟器包。

同一个 Release 可以提供标准版和明确命名的可选功能变体，例如录屏功能版。两个分支通过 `config/android-release-variant.txt` 分别写入 `standard` / `replay` 构建身份。应用内更新页同时解析两种资产：当前安装标准版时默认项是标准 APK，当前安装录屏功能版时默认项是录屏 APK，同时始终保留另一个版本的切换入口。如果两个变体使用相同的应用 ID、版本号和生产签名，它们可以互相覆盖并保留数据，但不能同时安装。每个变体必须分别记录构建提交、文件大小、SHA-256、ABI 和签名校验结果。

下载交给系统浏览器，安装交给 Android 系统确认。App 不静默下载、不静默安装；如果 Release 没有 APK，用户仍可打开 Release 页面查看文件和说明。

## 4. 准备新版本

例如从 `1.1.7 (12)` 提升到 `1.1.8 (13)`：

```powershell
npm.cmd run version:set -- 1.1.8 13
npm.cmd run check
```

`version:set` 会同步修改 `package.json` 和 `package-lock.json`，并拒绝不递增的 `androidVersionCode`。随后至少执行：

```powershell
npm.cmd test
npm.cmd run android:assemble-release-arm64
```

手机版专用命令只生成：

```text
android-app/app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

`android:assemble-release-arm64` 会先运行许可证检查、生成离线资源、执行 Android 单元测试和 release lint，再只构建并验证 ARM64 APK 的版本、生产签名、单一 ABI、队伍识别核心特征包与打包许可证。确实需要同时验证本地模拟器产物时，维护者仍可运行 `android:assemble-release`；开发调试可运行 `android:assemble`，默认生成分别用于真机与模拟器的两个 debug APK。

构建完成后应使用 Android SDK 构建工具核对 APK 本身的版本和 ABI；不要仅根据文件名判断：

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump badging android-app/app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## 5. 创建 GitHub Release

发布标签必须与 App 版本一致：

```text
version = 1.1.8
tag     = v1.1.8
```

- 稳定版：创建普通 Release，不勾选 “Set as a pre-release”。
- 预览版：版本可使用 `0.3.0-beta.1`，标签使用 `v0.3.0-beta.1`，并勾选 Pre-release。
- 不要把 Draft 当作可测试更新；GitHub 公共接口不会向普通用户提供 Draft。
- 标准 APK 文件名采用 `Pokemon-Champions-Assistant-v1.1.8-arm64.apk`；可选录屏功能版采用 `Pokemon-Champions-Assistant-v1.1.8-replay-arm64.apk`。
- 一个 Release 含多个 APK 时，标准版必须保持无变体标记的固定名称，录屏/实验变体必须带明确标记；还要分别验证标准 APK 默认标准资产、录屏 APK 默认录屏资产，并确认双方都能选择另一个。
- Release 正文应至少说明主要变化、数据迁移、已知问题和最低 Android 版本。

## 6. 发布签名是硬性要求

Android 只有在 `applicationId` 相同且新旧 APK 使用同一签名证书时，才能覆盖升级并保留 App 私有队伍与对局数据。

`1.0.1` 在尚无真实用户的前提下切换为独立的 4096 位生产证书；debug 包恢复使用 Android 调试证书，不再与 release 共用签名。生产 keystore 固定保存在仓库外，随机强密码由 Windows 当前用户的 DPAPI 文件保护，Gradle 不再提供 `android`、`androiddebugkey` 或任何其他弱默认值。release 构建在密钥、四项签名环境变量或证书指纹缺失/不一致时都会直接失败；所有后续 Release 必须持续使用同一证书。证书公开指纹记录在 `config/release-signing-certificate.sha256`，keystore、密码和 DPAPI 文件不得提交到仓库。

新开发机首次初始化或在正式发布前轮换尚未投入使用的证书时执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/provision-release-signing.ps1
```

初始化脚本只负责创建强密钥并保护本机凭据。发布前还必须把 `.p12` 复制到独立的离线介质，并把密码保存到独立密码管理器；不能把同机 DPAPI 副本当成唯一灾备。

可以先用下面的命令生成带指纹和文件哈希的备份目录，再把整个目录复制到离线介质：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/backup-release-signing.ps1 -Destination E:\pokemon-champions-signing-backup
```

其中 `release-signing.clixml` 只能由同一台 Windows 上的同一用户解密，因此灾难恢复仍必须依赖独立密码管理器中的 PKCS12 密码。

如果正式签名丢失，用户将无法在原安装上直接升级。应用现已同时启用 Android 加密云备份/设备迁移，并在设置页提供不含截图的 JSON 整包导出与恢复；但这不能替代签名密钥灾备。后续发布周期仍要执行“旧版安装 -> 新版覆盖 -> 私有队伍仍存在”的真机回归。

## 7. 维护边界

- 更新检查访问 `api.github.com`，发布页和 APK 下载使用 `github.com`。
- GitHub 仓库地址集中定义在 `AppUpdateConfig`，以后若正式发布仓库改变，只改这一处并重新构建。
- 版本比较遵循语义化版本的核心号和预发布标识；构建元数据不会改变版本先后。
- GitHub `404`、网络不可用、超时和频率限制都有用户可读提示。
- 伤害引擎、识别和本地存储不依赖网络；GitHub 暂时不可用不会影响核心功能。

## 8. 1.1.8 Android / HarmonyOS 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.8>
- Android 标准版：`Pokemon-Champions-Assistant-v1.1.8-arm64.apk`，`71,410,954` 字节，SHA-256 `B4726C1EDABC66DD224584225D34253E15A3EBEDBBF86231EF8C8CDF7F2BB749`。
- Android 录屏功能版：`Pokemon-Champions-Assistant-v1.1.8-replay-arm64.apk`，`71,574,846` 字节，SHA-256 `91BE187750D912F98B2069F78DB9F80D1EA04A858977C25B8F16D802E64EEE83`。
- HarmonyOS 标准版：`Pokemon-Champions-Assistant-v1.1.8-harmonyos-standard.hap`，`39,242,187` 字节，SHA-256 `849BD8679F4BA5E89B555F55402292F5936ABBB1D0169323D519A4283E0E3973`。
- HarmonyOS 录屏功能版：`Pokemon-Champions-Assistant-v1.1.8-harmonyos-replay.hap`，`39,367,756` 字节，SHA-256 `1A0B05CC66052C52F7AF160FA5B05AF0351E70A7D69D8570DE07E5D8C56AB3CC`。
- Android 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`；HarmonyOS 项目发布证书 SHA-256：`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`。
- 源码：Android 标准版由 `main` 的提交 `d0d1f7de5d5abadc5de5a87492f7b0d875f7019f` 构建；Android 录屏功能版由 `feature/battle-replay-phase-4` 的提交 `61ba09bd8e9b1cc8c8447cbdc3cf891b622645ab` 构建；HarmonyOS 双版本由 `feature/harmonyos-port` 的提交 `a520ab3c334e4e064da4bf9dd5504aa1b1a0120f` 构建。
- 验证：Android 标准版 Node `11/11`、JVM `124/124`，录屏功能版 Node `11/11`、JVM `159/159`；两边的 `lintRelease`、许可证、依赖审计（`0 vulnerabilities`）、生产签名和双单 ABI Release 校验通过。HarmonyOS 综合测试 `82/84` 通过，双版本完成 Release 构建、签名验签、包结构、变体隔离和双 ABI 校验；另外两项分别因旧审计证据锁按设计失效、未连接 HarmonyOS x86 目标而不通过。
- Android 录屏功能版已在物理 `RMX3820`（ADB `6465e08`）从 `1.1.7 (12)` 原地覆盖升级到 `1.1.8 (13)`，设备内 APK 哈希与本地产物一致并完成冷启动。Android `x86_64` APK 只用于本地模拟器构建校验，不上传 Release。
- 发布截图来自物理手机相册第一张图，原始 MediaStore ID `1000012630`；公开资产 `docs/assets/readme/type-matchup-hud-v1.1.8.jpg` 只遮挡双方游戏昵称。
- **HarmonyOS 标准版和录屏功能版均未经过真实设备安装、升级或功能测试；若遇到安装、权限、识别、浮窗、录屏或界面问题，请及时反馈。没有 E5 ARM64 真机证据，不得把源码或包级校验写成实机 PASS。**

完整选择、变化、安装边界、验证结果和反馈信息见 [1.1.8 发布说明（Android / HarmonyOS）](release_1.1.8_zh.md)。

## 9. 1.1.7 Android / HarmonyOS 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.7>
- Android 标准版：`Pokemon-Champions-Assistant-v1.1.7-arm64.apk`，`71,359,270` 字节，SHA-256 `87035DDFE44DA332D3C0A8499A1EB92A10E70EB8ADEB5E83299F02F852CA4714`。
- Android 录屏功能版：`Pokemon-Champions-Assistant-v1.1.7-replay-arm64.apk`，`71,539,542` 字节，SHA-256 `43E5406E574E7C2ED199FEA8A5113BFA9330F4CE59A8BE13B211C98695EA173F`。
- HarmonyOS 标准版：`Pokemon-Champions-Assistant-v1.1.7-harmonyos-standard.hap`，`39,112,883` 字节，SHA-256 `AB45B76D9E782248F0A282097AE8811770E2D9832121082217E75A27C67CFDF7`。
- HarmonyOS 录屏功能版：`Pokemon-Champions-Assistant-v1.1.7-harmonyos-replay.hap`，`39,230,264` 字节，SHA-256 `684E0FAE5BD5C3EAF2EB222DE79E9703AC562B72EC3740F994E61813D0D12703`。
- Android 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`；HarmonyOS 项目发布证书 SHA-256：`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`。
- 源码：Android 标准版由 `main` 的提交 `2ba8284b76c74fd610fe0b4a81ba4e649530e2a3` 构建；Android 录屏功能版由 `feature/battle-replay-phase-4` 的提交 `8ee17dc266f10de437ea98fae7be9ea42b7cffec` 构建；HarmonyOS 双版本由 `feature/harmonyos-port` 的提交 `1386899a721b6a2db7325d2739a2d6f6b90db138` 构建。
- 验证：Android 标准版 Node `11/11`、JVM `118/118`，录屏功能版 Node `11/11`、JVM `153/153`；两边的 `lintRelease`、许可证、依赖审计、生产签名和双单 ABI Release 校验通过。HarmonyOS 综合测试 `81/83` 通过，双版本完成 Release 构建、签名验签、包结构、变体隔离和双 ABI 校验；另外两项分别因旧审计证据锁按设计失效、未连接 HarmonyOS 模拟器而不通过。
- Android 真机发布前验证由用户确认完成；按用户要求，本轮没有重复执行 ADB 安装、版本、设备内 APK 哈希或冷启动采证。Android `x86_64` APK 只用于本地模拟器构建校验，不上传 Release。
- **HarmonyOS 标准版和录屏功能版均未经过真实设备安装、升级或功能测试；若遇到安装、权限、识别、浮窗、录屏或界面问题，请及时反馈。没有 E5 ARM64 真机证据，不得把源码或包级校验写成实机 PASS。**

完整选择、变化、安装边界、验证结果和反馈信息见 [1.1.7 发布说明（Android / HarmonyOS）](release_1.1.7_zh.md)。

## 10. 1.1.6 Android / HarmonyOS 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.6>
- Android 标准版：`Pokemon-Champions-Assistant-v1.1.6-arm64.apk`，`71,359,266` 字节，SHA-256 `FD546FEFC3F191C32AFE5171AF6051F7E069868B28EB8660B62114534D3B8EFE`。
- Android 录屏功能版：`Pokemon-Champions-Assistant-v1.1.6-replay-arm64.apk`，`71,539,538` 字节，SHA-256 `1609E4B3C2637A34014AF997A48C3DD7258D1FA081AEFEA85FC6D9A580E20C4B`。
- HarmonyOS 标准版：`Pokemon-Champions-Assistant-v1.1.6-harmonyos-standard.hap`，`39,113,600` 字节，SHA-256 `C2C3A6EDF6E3B94979916C1FE3F33F1FADB5996D4B3D9791A4DFDFAB1B572FFD`。
- HarmonyOS 录屏功能版：`Pokemon-Champions-Assistant-v1.1.6-harmonyos-replay.hap`，`39,230,974` 字节，SHA-256 `053B8B7580BC6BA8701B2F42F0C50D18034882FD12BBF5357B390468F163F36B`。
- Android 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`；HarmonyOS 项目发布证书 SHA-256：`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`。
- 源码：Android 标准版由 `main` 的提交 `94047cf8176c5e76f0493de40774a53c4dd4440a` 构建；Android 录屏功能版由 `feature/battle-replay-phase-4` 的提交 `3304eee0d7f9596bc558cdcf8c0414907bed763a` 构建；HarmonyOS 双版本由 `feature/harmonyos-port` 的提交 `5ba0a9caa27bffd80d9e524ce34c47ff8a6d4fba` 构建。
- 验证：Android 标准版 Node `11/11`、JVM `118/118`，录屏功能版 Node `11/11`、JVM `153/153`；两边的 `lintRelease`、许可证、依赖审计、生产签名和双单 ABI Release 校验通过。HarmonyOS 综合测试 `81/83` 通过，双版本完成 Release 构建、签名验签、包结构、变体隔离和双 ABI 校验；另外两项分别因旧审计证据锁按设计失效、未连接 HarmonyOS 模拟器而不通过。
- 发布边界：Android `x86_64` APK 只用于模拟器构建校验，不上传 Release；HarmonyOS HAP 同时包含 `arm64-v8a` 与 `x86_64`。本轮 Android 真机 `6465e08` 处于离线状态，HarmonyOS 没有连接目标，因此没有新增安装、覆盖升级或真机功能 PASS。
- **HarmonyOS 标准版和录屏功能版均未经过真实设备安装、升级或功能测试；若遇到安装、权限、识别、浮窗、录屏或界面问题，请及时反馈。没有 E5 ARM64 真机证据，不得把源码或包级校验写成实机 PASS。**

完整选择、变化、安装边界、验证结果和反馈信息见 [1.1.6 发布说明（Android / HarmonyOS）](release_1.1.6_zh.md)。

## 11. 1.1.5 Android / HarmonyOS 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.5>
- Android 标准版：`Pokemon-Champions-Assistant-v1.1.5-arm64.apk`，`71,359,266` 字节，SHA-256 `4CB6BEB02D9809609E2931B150EE8E1F422669D5C9704799E7F37EA69C5E0413`。
- Android 录屏功能版：`Pokemon-Champions-Assistant-v1.1.5-replay-arm64.apk`，`71,523,158` 字节，SHA-256 `B7CE12465B86F6B316CB3E80C63C8F6E97B2DC4AE3D5F82F9631824B19443B77`。
- HarmonyOS 标准版：`Pokemon-Champions-Assistant-v1.1.5-harmonyos-standard.hap`，`39,106,433` 字节，SHA-256 `27F79B15C4F0E2824F6C165974F0ADCCAD54CB43E9839221596FA2BD473EA5B8`。
- HarmonyOS 录屏功能版：`Pokemon-Champions-Assistant-v1.1.5-harmonyos-replay.hap`，`39,232,004` 字节，SHA-256 `30887803EEB32FA86A0DE96F1B71906E2B7EABF8E40DE4CCB0CCCEEB2D1DB5CF`。
- Android 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`；HarmonyOS 项目发布证书 SHA-256：`087C36F8FBCAB8EAE749E01BA11D9312D1C7347547D5548FDC32E40E56DB55FB`。
- 源码：Android 标准版由 `main` 的提交 `78a40d5b754b0dd76252ffa0a6e4d89cb259310b` 构建；Android 录屏功能版由 `feature/battle-replay-phase-4` 的提交 `623000c9dba697f64c59f389282384e6c4775199` 构建；HarmonyOS 双版本由 `feature/harmonyos-port` 的提交 `cc9faf1b71843740d63a2187a8932d79bf108407` 构建。
- 验证：Android 两个分支的 Node、JVM、lint、许可证、依赖审计和签名单 ABI Release 校验通过；录屏功能版已在 `RMX3820` 完成覆盖安装、版本、哈希和冷启动核对。HarmonyOS 双版本完成 Release 构建、签名验签、包结构、变体隔离和双 ABI 校验，综合测试 `80/82` 通过。
- 发布边界：Android `x86_64` APK 只用于模拟器构建校验，不上传 Release；HarmonyOS HAP 同时包含 `arm64-v8a` 与 `x86_64`。
- **HarmonyOS 标准版和录屏功能版均未经过真实设备安装、升级或功能测试；若遇到安装、权限、识别、浮窗、录屏或界面问题，请及时反馈。没有 E5 ARM64 真机证据，不得把源码或包级校验写成实机 PASS。**

完整选择、变化、安装边界、验证结果和反馈信息见 [1.1.5 发布说明（Android / HarmonyOS）](release_1.1.5_zh.md)。

## 12. 1.1.4 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.4>
- 标准资产：`Pokemon-Champions-Assistant-v1.1.4-arm64.apk`
- 标准 APK 大小：`71,342,882` 字节
- 标准 APK SHA-256：`1832809DA4F069A41B4E8E4303349DCBD764389C9C7C6EE517060F1866A76A11`
- 录屏功能版资产：`Pokemon-Champions-Assistant-v1.1.4-replay-arm64.apk`
- 录屏功能版 APK 大小：`71,523,158` 字节
- 录屏功能版 APK SHA-256：`6101690CE6C0A4263A8BF805BCAB703E5FF1208D40C4A371404B7A2F75DCF1BB`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 源码：标准版由 `main` 的提交 `8fd69bc113bad637a1c4548bfa108d851c5ae2be` 构建，正式标签使用 `v1.1.4`；录屏功能版由 `feature/battle-replay-phase-4` 的提交 `4f04a351144d413770d49c760ba2efe78cde887d` 构建。
- 验证：标准版 Node `11/11`、Android JVM `111/111`，录屏功能版 Node `11/11`、Android JVM `146/146`；两个分支的 `lintRelease`、许可证检查、依赖安全审计及签名单 ABI APK 发布校验均通过。
- 发布边界：两个公开资产均仅包含 `arm64-v8a`；本地 `x86_64` 产物只用于模拟器构建验证，不上传 Release。

面向用户的完整选择说明、对手配置管理与分享、形态继承、招式候选、升级行为和已知事项见 [Android 1.1.4 发布说明](android_1.1.4_release_notes_zh.md)。

## 13. 1.1.3 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.3>
- 标准资产：`Pokemon-Champions-Assistant-v1.1.3-arm64.apk`
- 标准 APK 大小：`71,252,478` 字节
- 标准 APK SHA-256：`B3467FE917E43B069F5FE254A6B71ED36D42F1BE182B5F3668DB9309C7576447`
- 录屏功能版资产：`Pokemon-Champions-Assistant-v1.1.3-replay-arm64.apk`
- 录屏功能版 APK 大小：`71,383,602` 字节
- 录屏功能版 APK SHA-256：`3B2EEB7B2A64C6DABDD856BD45519A0B5246A00ACDD7A0028D50C42986002AE5`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 源码：标准版由 `main` 的提交 `54734a9c32f0805f49572b51f10f8e74357f41e8` 构建，正式标签使用 `v1.1.3`；录屏功能版由 `feature/battle-replay-phase-4` 的提交 `ab2f72236ac4337af775198360c5c7910adc54d1` 构建。
- 验证：标准版 Node `11/11`、Android JVM `87/87`，录屏功能版 Node `11/11`、Android JVM `122/122`；两个分支的 `lintRelease`、许可证检查、依赖安全审计及签名单 ABI APK 发布校验均通过。
- 发布边界：两个公开资产均仅包含 `arm64-v8a`；本地 `x86_64` 产物只用于模拟器构建验证，不上传 Release。

面向用户的完整选择说明、单打 HUD、伤害公式修复、性能优化、升级行为和已知事项见 [Android 1.1.3 发布说明](android_1.1.3_release_notes_zh.md)。

## 14. 1.1.2 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.2>
- 标准资产：`Pokemon-Champions-Assistant-v1.1.2-arm64.apk`
- 标准 APK 大小：`71,220,990` 字节
- 标准 APK SHA-256：`3890B5E21C6CAAF7FFE309D0624A8B4006D60AE82D869FE100163D0B06ADC969`
- 录屏功能版资产：`Pokemon-Champions-Assistant-v1.1.2-replay-arm64.apk`
- 录屏功能版 APK 大小：`71,368,498` 字节
- 录屏功能版 APK SHA-256：`27AED011A3BA37073B2A176025C057B0F0C56D9046A309EA3384F35DA2930C0B`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 源码：标准版构建提交 `9bd46bb3a42420643b1262a41dd16749761ddc03`，正式标签使用 `v1.1.2`；录屏功能版由 `feature/battle-replay-phase-4` 的提交 `00b62074a6a566329b5ce45d08e73cc63190fa6d` 构建。
- 验证：标准版 Node `10/10`、Android JVM `84/84`，录屏功能版 Node `10/10`、Android JVM `119/119`；两个分支的 `lintRelease`、许可证检查、依赖审计及签名单 ABI APK 发布校验均通过。
- 发布边界：两个公开资产均仅包含 `arm64-v8a`；本地 `x86_64` 产物只用于模拟器构建验证，不上传 Release。

面向用户的完整选择说明、HUD 变化、升级行为和已知事项见 [Android 1.1.2 发布说明](android_1.1.2_release_notes_zh.md)。

## 15. 1.1.1 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.1>
- 标准资产：`Pokemon-Champions-Assistant-v1.1.1-arm64.apk`
- 标准 APK 大小：`71,089,918` 字节
- 标准 APK SHA-256：`CED8F2786BF41CC4FB9DF3F588A8B4CE9198B2BD44E59E893F56859FA4FEA12B`
- 录屏功能版资产：`Pokemon-Champions-Assistant-v1.1.1-replay-arm64.apk`
- 录屏功能版 APK 大小：`71,253,810` 字节
- 录屏功能版 APK SHA-256：`2BC5550262744097301C05BC1C8E53334A10B4C068242EC175081A780F8314F3`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 源码：标准版构建提交 `cdcf60f71135aff40dbdd51fa53bb28804ca0cb5`，正式标签使用 `v1.1.1`；录屏功能版由 `feature/battle-replay-phase-4` 的提交 `53ae684f1e1cf21988395b344bd5ab861c134ab8` 构建。
- 发布边界：两个公开资产均仅包含 `arm64-v8a`；应用内更新页按当前构建身份默认匹配同类资产，同时提供跨版本切换。

面向用户的完整选择说明、升级行为、验证结果和已知事项见 [Android 1.1.1 发布说明](android_1.1.1_release_notes_zh.md)。

## 16. 1.1.0 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.0>
- 公开资产：`Pokemon-Champions-Assistant-v1.1.0-arm64.apk`
- APK 大小：`71,056,740` 字节
- APK SHA-256：`38E80B0169E6BB1E9CB4C411F4E3CC2330F92D49D0BEA5AB01F5FABD083E2EDE`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 验证：`npm.cmd test`、Android 单元测试、release lint、许可证检查、依赖安全审计和 ARM64 APK 发布校验通过。
- 发布边界：本次只编译并上传 `arm64-v8a` APK，没有生成 `x86_64`、universal 或 32 位产物。

面向用户的完整变化、升级说明、已知事项和权利边界见 [Android 1.1.0 发布说明](android_1.1.0_release_notes_zh.md)。

## 17. 1.0.1 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.0.1>
- 公开资产：`Pokemon-Champions-Assistant-v1.0.1-arm64.apk`
- APK SHA-256：`B0DD01CE7B82C6DDC363907B571A0D394701B86CDADB3BA0A267B2BA769E3876`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 验证：Node 10/10、Android 单元测试 41/41、release lint、许可证检查、依赖安全审计和双 ABI APK 发布校验通过；OPD2409 / Android 16 实机验收解除原 P0 阻断。
- 发布边界：GitHub Release 只上传 `arm64-v8a` APK；`x86_64` 成品仅保留为本地模拟器构建，不上传。

面向用户的完整变化、迁移步骤、已知说明和权利边界见 [Android 1.0.1 发布说明](android_1.0.1_release_notes_zh.md)。

参考：

- GitHub Releases REST API：<https://docs.github.com/en/rest/releases/releases>
- GitHub REST API 频率限制：<https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api>
