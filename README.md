# Pokémon Champions Assistant（非官方）

这是一个面向 Android 13+ 的本地对战辅助项目，用于在用户主动操作后读取屏幕截图、确认双方宝可梦，并调用离线伤害引擎展示伤害范围、KO 结论和速度线。

项目不会修改 Pokémon Champions、注入或 Hook 游戏进程、读取游戏内存、拦截网络，也不会自动操作游戏。截图、队伍和对局状态保存在 App 私有目录；Android 应用不申请网络权限。

> 本项目与 Nintendo、Creatures、GAME FREAK、The Pokémon Company 或 Pokémon Champions 官方无关。Pokémon 及相关名称、角色和素材的权利归各自权利人所有。

## 当前能力

- 原生 Kotlin + Jetpack Compose Android 应用和可拖动悬浮面板。
- 我方输出 / 我方承伤双向离线伤害计算。
- 双方宝可梦、形态、招式、特性、道具、能力、天气、场地和常用战斗条件调整。
- 基于本地 JSON 的己方队伍和当前对局保存。
- 用户授权的 MediaProjection 截图流程和中文队伍 OCR。
- 可选的 OpenCV 队伍预览识别管线。
- 基于固定版本 Smogon damage-calc 的可复现 Android JavaScript 资源构建。

项目仍处于开发阶段。自动识别结果必须由用户确认，不应把计算假设当作对手的真实配置，也不应把本工具的结果视为官方赛事裁定。

## 为什么公开仓库没有截图和识别模板包

个人测试截图、保存队伍、评估数据、下载的第三方图片和由这些图片生成的识别特征均未纳入公开仓库。这样可以避免公开个人数据，也避免在素材再分发权利尚未复核时直接发布相关二进制资产。

因此，干净克隆可以构建并使用手动伤害计算、队伍管理、OCR 和悬浮流程；“队伍预览头像匹配”需要开发者自行准备有权使用的本地语料并生成：

- `dataset/labels.csv` 与 `dataset/real_train/`
- 可选的额外评估集
- 本地 `references/` 模板
- `src/data/recognition/android/team-preview-templates-v2.bin`

这些路径已加入 `.gitignore`。缺少模板包时 Android 构建仍可完成，但进入队伍预览头像匹配功能会显示资源缺失错误。

## 开始开发

需要 Git、Node.js 22+、npm 和 JDK 17。Android 构建还需要 Android SDK 36；Windows 可以让项目脚本安装到仓库外的本地工具目录。Python 3.12+ 仅在运行识别研究工具时需要。

克隆并准备依赖：

```powershell
git clone --recurse-submodules <repository-url>
cd pokemon-champions-assistant
npm.cmd ci
npm.cmd ci --prefix external/smogon-damage-calc
npm.cmd ci --prefix external/smogon-damage-calc/calc
npm.cmd test
```

如果克隆时没有带子模块：

```powershell
git submodule update --init --recursive
```

在 Windows 上准备并构建 Android debug APK：

```powershell
npm.cmd run android:setup
npm.cmd run android:doctor
npm.cmd run android:assemble
```

APK 输出到 `android-app/app/build/outputs/apk/debug/app-debug.apk`。

可选识别环境：

```powershell
python -m pip install -r requirements-recognition.txt
npm.cmd run recognition:android:templates
```

生成模板前必须先准备自己有权使用的本地语料。详细架构和数据契约见 `docs/`。

## 主要目录

- `android-app/`：Android 应用、悬浮服务、截图、存储和界面。
- `src/damage/`：稳定的伤害请求/结果契约和 Smogon 适配层。
- `src/recognition/`：ROI、OCR 和识别数据契约。
- `src/data/`：可公开的本地化、伤害预设、ROI 配置和来源元数据。
- `tools/android/`：资源构建、环境检查和 Node 回归测试。
- `tools/recognition/`：可选的本地识别评估与模板生成工具。
- `external/smogon-damage-calc/`：固定提交的上游 Git 子模块。

## 发布与许可证

第三方来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。公开范围和发布前检查见 [PUBLIC_RELEASE_CHECKLIST.md](PUBLIC_RELEASE_CHECKLIST.md)。

本仓库目前没有为原创代码指定开源许可证。在选择许可证前，默认版权规则适用；公开可见不等于获得复制、修改或再分发许可。
