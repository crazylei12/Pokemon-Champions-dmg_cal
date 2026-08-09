# HarmonyOS 阶段 2：正式工程、双产品构建与资源打包记录

- 日期：2026-08-01
- 分支：`feature/harmonyos-port`
- 正式工程：`harmonyos/app`
- 应用身份：`com.crazylei12.pokemonchampionsassistant`
- 版本：`1.1.4`（versionCode 9）
- 阶段状态：**已完成**

本阶段只交付正式 Stage 模型工程、共享产品壳、Native 接口骨架、资源打包和可复现构建链。自由计算、队伍管理、对局、识别、浮窗和录屏的业务实现仍分别属于阶段 3–9；主界面的入口卡片不是这些后续功能已完成的声明。

## 1. 工程与变体

正式工程包含一个 `entry` 模块、一套 ArkTS 页面和一个 `libpcbridge.so` Native/C++ 模块。工程级 `build-profile.json5` 定义两个产品：

| 产品 | Hvigor product | `RELEASE_VARIANT` | `REPLAY_ENABLED` | 应用名称 |
| --- | --- | --- | --- | --- |
| 标准版 | `default` | `standard` | `false` | 宝可梦冠军助手 |
| 录屏功能版 | `replay` | `replay` | `true` | 宝可梦冠军助手（录屏版） |

两个产品共用 bundleName、versionCode、versionName、图标、模块、源码和资源，因此具备相同升级身份。变体常量由 Hvigor 生成的 `BuildProfile.ets` 直接导入：标准版不创建 `entry-replay`，录屏版创建该入口；其余入口和布局相同。Release 构建启用分支裁剪，为后续把纯录屏依赖限定在录屏产品保留构建边界。

`libpcbridge.so` 当前只提供版本化握手 `getBridgeInfo()`，同时构建 `arm64-v8a` 与 `x86_64`。模拟器日志中的 `APP_NATIVE_BRIDGE_READY` 证明 ArkTS 到 Native 的实际加载与调用成功；真正的存储、识别和媒体接口将在后续阶段扩展，不能把握手当作业务能力。

双产品配置遵循华为官方 `build-profile.json5` 的 products、`applyToProducts`、`output.artifactName` 与 `buildProfileFields` 机制：[官方配置说明](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-hvigor-build-profile-V5)。

## 2. 固定工具链

机器可读配置位于 `config/harmonyos-app-build.json`，环境检查拒绝不在 D 盘或版本不一致的工具链。

| 组件 | 固定值 |
| --- | --- |
| DevEco Studio | `D:\HarmonyOS\DevEcoStudio` / 6.1.1.300 |
| HarmonyOS SDK | 6.1.1(24)，实际编译 SDK 6.1.1.125 |
| Hvigor | 6.24.4 |
| ohpm | 6.1.2.285 |
| Node.js | 18.20.1（DevEco 内置） |
| Java | 21.0.8（DevEco JBR） |
| Native ABI | `arm64-v8a`、`x86_64` |

脚本显式设置 `DEVECO_SDK_HOME`、`JAVA_HOME` 和 PATH，打包时不会依赖 C 盘新装工具或未声明的手工复制文件。构建输出、Hvigor 缓存、Native 中间文件、生成的 rawfile 和 HAP 均已加入 `.gitignore`。

## 3. 资源打包和许可证

`tools/harmonyos/package-app-assets.mjs` 每次构建前清理并重建 `resources/rawfile/runtime`，校验源文件锁定哈希，复制后再次校验，并生成包内 `manifest.json`。当前共 7 项：

| 资源 | SHA-256 |
| --- | --- |
| Champions 预设 | `af5a95d07d91903d0bcb1912b5655090e0afbb49e1d3b3e68f7b27d1a45d4a2a` |
| 队伍预览 V2 特征包 | `0bde8c79d76b9e8ff55d77f45e2c8d974703c342faf12fc2bb9220b68e87460f` |
| 队伍预览 V2 元数据 | `399f3afc40163a61520b9951169262408c4c68fb62b7934219e720dd87dd912c` |
| SafeZone ROI | `bd0eb3e9e28118475daf9147fc16575d49b6e9891b9126dd2cb8262adb0e7d71` |
| 简体中文稳定 ID 词典 | `5818356b849ef28280c909592dd856c0661af9b8b9a04d390e3943b20aad41ad` |
| 固定伤害引擎 | `67c4990d6b57f7f3d1609bbfab3d1152f258f5b4f48b096b60ef799223ea2b89` |
| ArkWeb 引擎宿主页 | `dc18c4537dac98d749750c4ce0ac99f435410fc0fab078b77369e4c1d4832062` |

`verify-app-packages.ps1` 直接读取 HAP ZIP 条目，校验应用身份、版本、产品标签、构建模式、双 ABI Native 库、资源数量和每个资源的 SHA-256。`THIRD_PARTY_NOTICES.md` 已明确这些资源在 HarmonyOS 包内沿用现有来源、许可证和权利边界；阶段 2 没有新增第三方运行库。

## 4. 构建产物

Debug 与 Release 模式均完成标准版、录屏版的干净构建和包内容校验。HAP 是本机验收产物，按仓库规则不提交。

| 模式 | 产品 | 大小（bytes） | SHA-256 |
| --- | --- | ---: | --- |
| Debug | 标准版 | 25,210,981 | `ca5bc41896d820923e6563a6ec0603b8c064026e5a3c4d61f72596adf81369ac` |
| Debug | 录屏版 | 25,210,967 | `9d1235dcfea4bdf09341b34906d3b8fc6214bf1561e5a08306ce058aa2205bc5` |
| Release | 标准版 | 25,193,625 | `26fb9e439ba95af3b7e5cc05d326f775dad3b3f53aee843f96c628db6294d12b` |
| Release | 录屏版 | 25,193,619 | `b477a6b89c995d307ce65e1ea170e1ed5c3c5dccd7e54f8a231ac60c35b4cd21` |

Release 行表示 Release 编译模式和分支裁剪已验证，不表示已取得发布证书。当前四个 HAP 都是 unsigned；正式签名、升级签名连续性和真机安装属于阶段 10 发布门。

Hvigor 目前对本地 NAPI 类型声明给出“尚未验证”的提示，Release 混淆分析还会提示依赖扫描未找到 `libpcbridge.so`。实际 HAP 内两种 ABI 的库均经包校验存在，Debug 模拟器实际调用成功；这些提示没有被当作错误静默忽略，后续 Native 接口扩展时继续观察。

## 5. 模拟器运行验收

验收目标为 `127.0.0.1:5555`，HarmonyOS API 24，渲染分辨率 `1240×2772`。`verify-emulator-shell.ps1` 对每个产品执行覆盖安装、强制结束、启动、截图、UI 树抓取和 Native 日志检查。

| 检查 | 标准版 | 录屏功能版 |
| --- | --- | --- |
| 安装并启动 `EntryAbility` | PASS | PASS |
| Native 握手 | `APP_NATIVE_BRIDGE_READY variant=standard` | `APP_NATIVE_BRIDGE_READY variant=replay` |
| 共享入口 | 自由计算、对局助手、我的队伍、设置 | 相同 |
| 录屏入口 | 不存在 | 存在 |
| UI 调试术语 | 未显示 | 未显示 |

证据：

- 标准版截图：`harmonyos/app/evidence/pc-stage2-standard.png`；
- 录屏版截图：`harmonyos/app/evidence/pc-stage2-replay.png`；
- 同目录的 JSON 为 UI 树，LOG 为过滤后的 `PCApp` 运行日志。

当前模拟器最后安装的是录屏功能版。两个产品使用同一 bundleName 和版本号，`hdc install -r` 可在两者之间覆盖安装，符合后续共享数据升级关系的工程前提；真正的数据保留要在阶段 4 存储实现后验收。

## 6. 复现命令

在仓库根目录执行：

```powershell
git submodule update --init --recursive external/smogon-damage-calc
npm.cmd ci
npm.cmd run harmonyos:assets
npm.cmd run harmonyos:phase2:check
npm.cmd run harmonyos:doctor
npm.cmd run harmonyos:assemble
npm.cmd run harmonyos:verify
npm.cmd run harmonyos:emulator:verify

# 额外验证 Release 编译模式；产物仍未签名
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode release -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-packages.ps1 -BuildMode release
```

## 7. 阶段结论和后续门

阶段 2 的正式工程、工具链、双产品、双 ABI、资源哈希、包内容、许可证声明和模拟器启动门全部通过，可以进入阶段 3。

仍未关闭且不属于阶段 2 的项目：正式产品签名、真实 arm64 设备安装、阶段 1 的真实画面捕获/内部音频/MP4/媒体库真机门，以及阶段 3–9 的全部业务功能。没有真机不会阻塞接下来的模拟器可验证开发，但最终阶段 10 必须如实保留这些未完成门，不能用 x86_64 模拟器结果替代。
