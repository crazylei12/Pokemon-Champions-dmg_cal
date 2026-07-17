# Android 版本、检查更新与发布渠道

日期：2026-07-17

## 1. 当前实现

Android App 从 `package.json` 读取统一版本：

- `version`：用户可见的语义化版本，例如 `1.0.0`。
- `androidVersionCode`：Android 安装系统使用的正整数，每次发布必须严格递增。

当前正式版本为 `1.1.1 (6)`。App 设置页会显示这两个值，并在用户主动点击“检查更新”时访问下面的发布源：

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

例如从 `1.1.0 (5)` 提升到 `1.1.1 (6)`：

```powershell
npm.cmd run version:set -- 1.1.1 6
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
version = 1.1.1
tag     = v1.1.1
```

- 稳定版：创建普通 Release，不勾选 “Set as a pre-release”。
- 预览版：版本可使用 `0.3.0-beta.1`，标签使用 `v0.3.0-beta.1`，并勾选 Pre-release。
- 不要把 Draft 当作可测试更新；GitHub 公共接口不会向普通用户提供 Draft。
- 标准 APK 文件名采用 `Pokemon-Champions-Assistant-v1.1.1-arm64.apk`；可选录屏功能版采用 `Pokemon-Champions-Assistant-v1.1.1-replay-arm64.apk`。
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

## 8. 1.1.1 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.1>
- 标准资产：`Pokemon-Champions-Assistant-v1.1.1-arm64.apk`
- 标准 APK 大小：`STANDARD_APK_BYTES` 字节
- 标准 APK SHA-256：`STANDARD_APK_SHA256`
- 录屏功能版资产：`Pokemon-Champions-Assistant-v1.1.1-replay-arm64.apk`
- 录屏功能版 APK 大小：`REPLAY_APK_BYTES` 字节
- 录屏功能版 APK SHA-256：`REPLAY_APK_SHA256`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 源码：标准版使用 `v1.1.1`；录屏功能版使用 `replay-v1.1.1`，两个确切构建提交同时写入 Release 正文。
- 发布边界：两个公开资产均仅包含 `arm64-v8a`；应用内更新页按当前构建身份默认匹配同类资产，同时提供跨版本切换。

面向用户的完整选择说明、升级行为、验证结果和已知事项见 [Android 1.1.1 发布说明](android_1.1.1_release_notes_zh.md)。

## 9. 1.1.0 正式发布记录

- Release：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.0>
- 公开资产：`Pokemon-Champions-Assistant-v1.1.0-arm64.apk`
- APK 大小：`71,056,740` 字节
- APK SHA-256：`38E80B0169E6BB1E9CB4C411F4E3CC2330F92D49D0BEA5AB01F5FABD083E2EDE`
- 生产签名证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- 验证：`npm.cmd test`、Android 单元测试、release lint、许可证检查、依赖安全审计和 ARM64 APK 发布校验通过。
- 发布边界：本次只编译并上传 `arm64-v8a` APK，没有生成 `x86_64`、universal 或 32 位产物。

面向用户的完整变化、升级说明、已知事项和权利边界见 [Android 1.1.0 发布说明](android_1.1.0_release_notes_zh.md)。

## 10. 1.0.1 正式发布记录

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
