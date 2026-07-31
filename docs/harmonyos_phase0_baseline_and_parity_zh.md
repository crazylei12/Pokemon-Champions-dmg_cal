# 鸿蒙移植阶段 0：Android 基线、功能对照与跨平台契约

## 1. 阶段状态

阶段 0 只冻结移植目标，不包含任何鸿蒙产品功能实现。鸿蒙工程骨架、系统能力探针、ArkTS 页面、截图、OCR、OpenCV、浮窗和录屏均未开始；功能矩阵中的状态因此统一为 `not_started`，只有最终阶段 10 的真实验收通过后才能改为 `accepted`。

本阶段的机器可读依据是：

- `config/harmonyos-phase0-baseline.json`：提交、用户工作区叠加规则、资源哈希、工具链和测试数量；
- `config/harmonyos-phase0-feature-matrix.json`：66 条逐入口、逐状态、逐变体的实现与验收映射；
- `test/fixtures/harmonyos-port/phase0/`：九份不含个人数据和截图的 JSON 黄金向量；
- `tools/harmonyos/phase0-contracts.test.mjs`：上述基线与固定 Android 伤害引擎的自动校验。

## 2. 冻结的源基线

| 产品线 | 分支 | 冻结提交 | 当前 JVM 测试数 | 说明 |
| --- | --- | --- | ---: | --- |
| 标准版 | `main` | `1e930cabcfb70605e9de7acd68f97674d8b694f5` | 112 | 包含百变怪单招保存规则；当前领先远端 1 个提交 |
| 录屏功能版 | `feature/battle-replay-phase-4` | `dd1fa47a6034f80b5cba3488a547f8d7dc56b252` | 147 | 包含同一规则；当前领先远端 1 个提交 |
| 鸿蒙移植 | `feature/harmonyos-port` | 起点 `3cedc164e2a863cd889c5ef5969eb2c7e51294c9` | 不适用 | 只在此分支保存阶段 0 产物 |

两个 Android 分支分别提交了相同的四文件改动，规定“百变怪在 OCR 修正保存前只需一个不同招式，其他物种仍需四个不同招式”。两个工作树的已跟踪文件均干净；录屏工作树另有用户所有的未跟踪 `artifacts/`，阶段 0 不暂存、不提交也不清理该目录。

固定运行数据包括 `champions-presets.json`、中文稳定 ID 词典、队伍预览 V2 特征包、元数据、SafeZone ROI 和 Android 伤害引擎。文件大小与 SHA-256 记录在基线 JSON 中；伤害引擎固定为 `pokemon-champions-smogon-0.11.0-3677e41`，Smogon 子模块固定为 `3677e41a5e75c2d4964bb30b9aed5d18a1f4ffae`。

## 3. 功能对照和变体边界

功能矩阵按以下 16 个领域覆盖 README 和当前源码：应用壳与隐私、首页、自由计算、伤害引擎、截图会话、我方两页 OCR、双方队伍预览、对局状态、完整面板、直接 HUD、用户对手预设、存储与备份、更新、录屏，以及最终相册验收。

每条矩阵项都包含：稳定 ID、用户可见能力、适用变体、Android 依据、鸿蒙计划阶段和可观察验收结果。矩阵是完整移植的范围清单；后续不得以“顺便优化”为由修改产品语义，也不得把计划、占位 UI 或构建成功记为实现或验收。

| 能力 | 标准版 | 录屏功能版 | 鸿蒙约束 |
| --- | --- | --- | --- |
| 首页、计算、识别、浮窗、HUD、存储、备份、更新 | 有 | 有 | 共用同一实现和同一回归门槛 |
| 截图会话 | 仅识别 | 识别可与录屏组合 | 共用单一捕获源；识别仍取原始帧 |
| 会话模式 | 普通识别 | 识别并录屏、仅识别、仅录屏 | 只在录屏适配层分叉 |
| 视频编码、内部音频、媒体库发布 | 无 | 有 | 独立模块，失败不能破坏共享识别会话 |
| 纯录屏延迟加载 | 不适用 | 有 | 不初始化 OCR、OpenCV 和伤害运行时 |

除上述录屏能力外，两版不得形成漂移的页面、状态模型或存储格式。Android 的录屏架构保持独立，鸿蒙端也应以共享核心加薄变体适配实现。

## 4. 必须保持的行为契约

1. 用户已经确认的我方实际招式优先于不完整的静态合法招式池，并继续标记其来源。
2. 用户保存的对手预设统一位于最上方分组，按保存顺序排列，不倒序。
3. 只有明确列入同一对局配置共享组的形态才共享预设；普通形态边界不能被扩大。
4. 成功确认新的队伍预览时创建新对局并清空旧局状态；失败、空帧或不可见帧不能覆盖上一次有效结果。
5. 手动修正和现场覆盖按槽位锁定，后续自动刷新不能悄悄覆盖用户选择。
6. 用户预设存储损坏时先保留原文件并阻止写入，只有复制备份或显式重置后才能继续。
7. 完整备份先整体校验再替换；旧备份没有 `userOpponentPresets` 字段时保留本地用户预设，新备份则始终携带一个经过校验的对象，即使列表为空。
8. 独立预设分享和完整备份分别使用 4 MB 与 16 MB 上限，导入必须校验种类、schema、数量、ID 和引用完整性。
9. 产品 UI 不显示“相册测试图、ROI、OCR、Top-3、内部文件名”等调试概念；相册只是验收输入，不是对外产品术语。
10. 应用保持本地、用户授权、外部辅助边界：不上传数据、不修改游戏、不注入、不读内存、不拦截网络、不自动操作。
11. 百变怪的 OCR 修正保存需要且只需要一个不同招式（变身）；其他物种需要四个不同招式。已经保存的手动队伍只要六项实际能力值、特性和至少一个招式齐全即可用于伤害计算，这是另一个既有规则，不能混为一谈。
12. 标准版与录屏功能版的共享行为必须一致，录屏的编码、内部音频、媒体发布和异常收尾保持独立。

## 5. JSON 契约与黄金向量

| 契约 | kind / schema | 黄金文件 | 关键校验 |
| --- | --- | --- | --- |
| 伤害请求与投影响应 | 引擎 API | `damage-request.json`、`damage-response-projection.json` | 方向、双方、配置、招式、伤害、百分比、击倒次数、警告完全一致 |
| 我方两页识别草稿 | `OwnTeamImportDraft` / 1 | `own-team-import-draft.json` | 两页各 6 槽、`2772×1240`、物种配对、42 字段统计 |
| 已保存我方队伍 | `SavedOwnTeam` / 1 | `saved-own-team.json` | 6 槽、稳定 ID、实际能力值、百变怪一招例外和来源 |
| 队伍预览候选 | `TeamPreviewRecognitionResult` / 1 | `team-preview-recognition.json` | 双方各 6 槽、Top-3、0.90/0.035 确认门槛、视口与性能字段 |
| 当前对局 | `BattleSession` / 6 | `battle-session.json` | 队伍引用、方向、现场覆盖、场地、速度线和 HUD 状态 |
| 用户对手预设 | `OpponentUserPresets` / 1 | `opponent-user-presets.json` | `user.` ID、最多 500 条、名称 1–24 字、保存顺序 |
| 独立预设分享 | `PokemonChampionsOpponentPresetShare` / 1 | `opponent-preset-share.json` | 包装类型、内嵌预设根对象、4 MB 导入门槛 |
| 完整备份 | `PokemonChampionsAssistantBackup` / 1 | `app-backup.json` | 最多 100 队、六只完整、会话引用、预设对象、更新频道、16 MB 门槛 |

这些向量全部是手工构造的合成数据，不包含真实玩家队伍、用户路径、原始游戏截图、下载图片或图像二进制。`app-backup.json` 同时保留最小合法旧队伍形态，用来冻结当前解析器的兼容边界。

## 6. 第三方依赖与许可证基线

完整归属与许可证副本仍以 `THIRD_PARTY_NOTICES.md` 和 `third_party/licenses/` 为准。本阶段固定的主要边界如下。

| 组件或数据 | 固定版本/来源 | 用途 | 许可证或权利边界 |
| --- | --- | --- | --- |
| Smogon damage-calc | 子模块 `3677e41` | Champions 伤害计算 | MIT |
| `@pkmn/dex`、`@pkmn/mods` | 0.10.11 | 生成物种、招式和配置数据 | MIT |
| 42arch 中文数据 | 仓库内固定来源 | 生成中文稳定 ID 映射 | MIT；来源站文本权利另行保留 |
| PokeAPI 元数据、sprites 索引 | 固定目录与声明 | 图标索引和识别特征来源 | BSD-3-Clause / CC0；宝可梦图像权利不随之清除 |
| OpenCV for Android | 4.13.0 | Android 队伍预览识别基线 | Apache-2.0 |
| ML Kit 中文/拉丁 OCR | 16.0.1 | Android OCR 基线 | ML Kit 条款及制品内声明 |
| AndroidX、Compose、Kotlin | 基线 JSON 中固定版本 | Android UI 和系统集成 | Apache-2.0 |
| sharp / esbuild / TypeScript | 0.35.2 / 0.28.1 / 6.0.3 | 资源生成和构建 | Apache-2.0 / MIT / Apache-2.0 |

阶段 0 没有为鸿蒙新增第三方库。任何后续依赖必须记录来源、版本、许可证和哈希，并确认其可在当前 OpenHarmony API 24 环境离线打包。

## 7. 阶段 0 验收命令与退出条件

在鸿蒙移植工作树执行：

```powershell
npm.cmd run harmonyos:phase0:check
git diff --check
```

在标准版和录屏功能版各自工作树执行：

```powershell
npm.cmd test
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/run-gradle.ps1 :app:testDebugUnitTest
```

退出阶段 0 前必须同时满足：

- 66 条功能项均有 Android 依据、鸿蒙计划阶段和验收句；
- 九份黄金文件通过结构、去隐私和固定资源/引擎校验；
- 两个 Android 基线在包含当前已提交百变怪规则的情况下通过既有 Node 与 JVM 回归；
- 标准版与录屏功能版的唯一产品差异已明确；
- 鸿蒙功能实现仍为零，没有新增需求或顺手改动。

阶段 0 完成后停下。阶段 1 只在下一次明确开始时创建系统能力探针，并优先证明相册单窗口捕获、`2772×1240` 原始帧、Core Vision 离线 OCR、浮窗和录屏/内部音频可行性。
