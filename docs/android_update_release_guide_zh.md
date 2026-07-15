# Android 版本、检查更新与发布渠道

日期：2026-07-15

## 1. 当前实现

Android App 从 `package.json` 读取统一版本：

- `version`：用户可见的语义化版本，例如 `1.0.0`。
- `androidVersionCode`：Android 安装系统使用的正整数，每次发布必须严格递增。

首个稳定版本为 `1.0.0 (3)`。App 设置页会显示这两个值，并在用户主动点击“检查更新”时访问下面的发布源：

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

两个频道都只在用户点击按钮时联网。App 不保存 GitHub Token，也不会上传截图、队伍、对局状态或伤害计算输入。GitHub 公共 REST API 的未认证请求有频率限制，因此界面不自动轮询，遇到限制时提示稍后重试。

## 3. 用户更新流程

```text
设置 -> 选择更新频道 -> 检查更新
  -> 没有 Release：显示尚未发布
  -> 已是最新版：显示当前版本
  -> 有新版本：显示标签、标题、版本说明和下载入口
```

正式 Release 只提供文件名含 `arm64` / `arm64-v8a` 的 APK。项目不再构建或发布 x86/x86_64、32 位 ARM 或 universal APK。

下载交给系统浏览器，安装交给 Android 系统确认。App 不静默下载、不静默安装；如果 Release 没有 APK，用户仍可打开 Release 页面查看文件和说明。

## 4. 准备新版本

例如从首个正式版继续提升到 `1.0.1 (4)`：

```powershell
npm.cmd run version:set -- 1.0.1 4
npm.cmd run check
```

`version:set` 会同步修改 `package.json` 和 `package-lock.json`，并拒绝不递增的 `androidVersionCode`。随后至少执行：

```powershell
npm.cmd test
npm.cmd run android:assemble-release
```

正式构建只生成一个 64 位 ARM 真机包：

```text
android-app/app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

`android:assemble-release` 会先运行许可证检查、生成离线资源、执行 Android 单元测试和 release lint，再构建并验证 APK 的版本、签名、ABI 与打包许可证。开发调试可运行 `npm.cmd run android:assemble`，它也只生成 `app-arm64-v8a-debug.apk`。

构建完成后应使用 Android SDK 构建工具核对 APK 本身的版本和 ABI；不要仅根据文件名判断：

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump badging android-app/app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## 5. 创建 GitHub Release

发布标签必须与 App 版本一致：

```text
version = 1.0.1
tag     = v1.0.1
```

- 稳定版：创建普通 Release，不勾选 “Set as a pre-release”。
- 预览版：版本可使用 `0.3.0-beta.1`，标签使用 `v0.3.0-beta.1`，并勾选 Pre-release。
- 不要把 Draft 当作可测试更新；GitHub 公共接口不会向普通用户提供 Draft。
- APK 文件名固定采用 `Pokemon-Champions-Assistant-v1.0.1-arm64.apk` 形式。
- Release 正文应至少说明主要变化、数据迁移、已知问题和最低 Android 版本。

## 6. 发布签名是硬性要求

Android 只有在 `applicationId` 相同且新旧 APK 使用同一签名证书时，才能覆盖升级并保留 App 私有队伍与对局数据。

首个稳定版开始使用仓库外固定保存的签名 keystore，并校验证书 SHA-256 指纹。release 构建在密钥缺失或指纹不一致时会直接失败；所有后续 Release 必须持续使用同一证书。不要把 keystore、密码或签名环境文件提交到仓库。

如果正式签名丢失，用户将无法在原安装上直接升级，只能卸载重装，并可能丢失 App 私有数据。因此需要长期备份签名密钥，并在后续允许安装验证的发布周期执行“旧版安装 -> 新版覆盖 -> 私有队伍仍存在”的真机回归。

## 7. 维护边界

- 更新检查访问 `api.github.com`，发布页和 APK 下载使用 `github.com`。
- GitHub 仓库地址集中定义在 `AppUpdateConfig`，以后若正式发布仓库改变，只改这一处并重新构建。
- 版本比较遵循语义化版本的核心号和预发布标识；构建元数据不会改变版本先后。
- GitHub `404`、网络不可用、超时和频率限制都有用户可读提示。
- 伤害引擎、识别和本地存储不依赖网络；GitHub 暂时不可用不会影响核心功能。

参考：

- GitHub Releases REST API：<https://docs.github.com/en/rest/releases/releases>
- GitHub REST API 频率限制：<https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api>
