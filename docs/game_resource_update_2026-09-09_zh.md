# 2026-09-09 标准版离线资源更新记录

## 版本与来源

- 标准版起点：`eabc6ad`。用户最终指定仅完成标准版；回放版 `dcce238` 及其原有 `artifacts/` 保持不动。
- 先更新 Smogon 核心到 `111407c919c2c886688db704ae97376e768b72e4`，再应用本项目补充数据。子模块源文件保持原样。
- Showdown 数据提交：`3ab832905b012da47c355009e141b1660fa36808`。固定快照记录在 `src/data/damage/champions-showdown-snapshot.json`，保留来源和 MIT 许可证。
- 雷电客户端已通过 Google Play 从 1.1.5（3191）更新为 1.2.0（3762，r130104）。登录仍报连接错误 1001；按用户要求暂停设备处理与依赖它的验收，交由用户回去处理。未获取新版 Master Data；原有 v17 数字映射保持原样。

## 已核对的计算变更

| 形态/招式 | 旧值 | 本次数据 |
| --- | --- | --- |
| 超级阿勃梭鲁 Z | 魔法镜 | 锋锐 |
| 超级烈咬陆鲨 Z | 沙之力 | 漂浮 |
| 超级路卡利欧 Z | 适应力 | 波导防护 |
| 超级具甲武者 | 危险回避 | 硬爪 |
| 流星突刺 | 150 威力 | 170 威力 |
| 劈开 | 70 威力 | 80 威力 |
| 吸取力量、祈愿 | 基础 PP 10 | 基础 PP 5 |

Showdown 来源文件：`data/pokedex.ts`、`data/mods/champions/{moves,learnsets,formats-data,items,abilities}.ts`。快照更新了合法形态、招式池和道具等数据，不只改动四个默认特性。所有当前合法形态都必须有本地实体、计算定义和非空招式池。

流星突刺在 Showdown 基础招式表仍带有 `Past` 标签，但 Champions 模块已有 170 威力覆盖。计算资源显式补入这一招式；葱游兵的当前游戏招式池仍需与客户端核对，不能仅凭伤害测试推断完整可用性。

上游最新常用配招从原来的数量变为 160 物种、408 预设。导出保留来源数量，并将 `Aegislash` 对齐计算器的盾牌形态。上游配招中不属于当前招式池的招式保存在 `excludedUpstreamMoves`，不作为合法招式推荐；用户自行确认的保存队伍不受此筛选影响。

## 构建与资源链路

1. `node tools/generate-champions-snapshot.mjs <干净的 Showdown 仓库>` 生成可追溯快照。
2. `node tools/android/prepare-champions-calc.mjs` 清除计算器增量编译索引、重编译原始核心，再只对生成目录中的 Champions 数据应用补充。不会改动第九世代数据。
3. `node tools/localization/sync-zh-hans.mjs --refresh-sources` 刷新中文名称；检查覆盖率和具体形态译名。
4. `npm.cmd run android:assets` 重建 Android 引擎和预设；`moveMetadata` 同步威力、基础 PP、命中和优先度。
5. `node tools/pokemon-icons/sync-pokemon-icons.mjs --download` 刷新索引及缓存。GitHub 按配置中的素材目录读取，拒绝截断的目录；52Poké 缺失的普通头像优先使用 Bulbagarden Champions menu sprite。
6. 本次缺少三套私有标注截图，使用 `python tools/android/refresh-catalog-features.py` 重建全部 718 条图标特征，逐字节保留原有 366 条截图特征。二进制共 1084 条，359 个形态均有普通/闪光特征。脚本验证原文件 SHA-256、格式参数、实体覆盖和保留记录，拒绝不兼容输入。
7. 原始截图恢复后，仍需执行完整 `export-team-preview-templates.py --verify` 和识别评估；`catalogRefresh.previousCorpusVerification` 仅保存历史结果，本次 `verification` 明确为 null。

队伍码的 Node 工具现直接读取已验证的版本化数字表，不再从可变化的中文词库重建数字编号。v17 的 361 个形态、500 个招式、200 个特性、148 个道具保持不变。此修复只防止离线资源更新污染旧映射，不代表新版队伍码兼容性已验收。

## 本次验证

- `npm.cmd test`：TypeScript/版本、21 项引擎/预设/新 Mega 回归、6 项队伍码离线协议测试、许可证检查通过。
- `node --test tools/update-resources.test.mjs`：7 项通过。
- `python tools/android/test_catalog_features.py`：2 项通过，包括二进制截断拒绝、359 个形态普通/闪光特征覆盖。
- `:app:testDebugUnitTest --rerun`：标准版 144 项 Android 单元测试通过。
- 中文词库 1277 条，缺失 0；图标 359/359 普通、359/359 闪光，未映射 0。
- 四个 Mega 特性在生成的 Android 引擎验证；流星突刺 170 威力也验证了实际伤害结果，第九世代仍为 150。

## 用户暂缓的验收

- 新版 Master Data 与整张数字映射、旧/新公开码的在线请求。
- 新版本游戏画面、识别精度与布局；私有标注原图缺失，不能把旧评估结果视为当前结果。
- 葱游兵当前游戏内招式池核对：Showdown Champions 的池中尚无流星突刺；本次已支持该招式的 170 威力计算，未伪造上游学习来源。
- 回放版同步按用户要求不执行。本次不发布或安装 APK。

标准版离线更新与上述自动检查完成；此记录不代表完整游戏版本实机验收通过。
