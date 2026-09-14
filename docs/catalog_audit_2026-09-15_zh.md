# 标准版实体目录与形态关联审计

## 范围与结论

继招式缺项修复后，以已验证的游戏 1.2.0 / Master Data v18 导出为基准，检查客户端记录 → 编号映射 → 中文目录 → 可选配置 → 打包计算引擎，并检查图标与识别目录的实体及显示名称一致性。

| 范围 | 核对结果 |
| --- | --- |
| 开放招式 | 512 个，沿用上一轮修复与精确学习表检查 |
| 实际分配特性 | 215 个，无缺项；纠正 2 个形态多收的旧特性 |
| 持有道具 | 166 个，无缺项；不以 `unlock` 字段过滤 |
| 客户端形态 | 396 条，核对属性、种族值、体重、特性槽、编号及学习表 |
| 内置配置 | 160 个物种、408 套配置，纠正 6 套不合法特性 |
| 性格与属性 | 25 个性格及加减能力、18 个对战属性均完整 |
| 本地物种目录 | 359 个实体的选择入口、中文、图标及识别记录均覆盖 |

`tokusei.json` 的 320 条记录包含休眠条目，不能全算作当前可用特性。216 个兼容特性编号仍保留，其中 Battle Bond 用于旧配置兼容；当前客户端可选特性由 `personal.json` 的实际分配确定。

## 已修复

1. 特性池不再合并上游历史形态特性：甲贺忍蛙排除牵绊变身，超级戟脊龙排除冰冻之躯。
2. 内置配置中超级喷火龙 X 的猛火、超级袋兽的胆量，以及皮可西的魔法镜、永恒之花花叶蒂的妖精气场、两套普通长耳兔的胆量，改为其当前形态的客户端默认特性。原上游值保留在 `excludedUpstreamAbility` 供审计；超级形态切换仍使用该形态自己的特性。
3. 队伍码 `681:0` 从虚拟 Aegislash-Both 改为 Aegislash-Shield；客户端盾牌攻击/特攻为 50，不能导入成虚拟双形态的 140。虚拟双形态计算选项仍保留。
4. `925:0` 改为 Maushold（三只家庭，2.3 kg），`925:1` 改为 Maushold-Four（四只家庭，2.8 kg）。生成器加入体重匹配，移除颠倒的硬编码。
5. 补齐 19 条明确的中文形态规则，消除 14 处英文后缀漏译并调整两处超级超能妙喵名称顺序。三个帕底亚肯泰罗规则的输出与旧名称一致。同步 16 个图标标签和 32 条识别记录标签。

形态纠错列在 `champions-form-corrections.v18.json`，包含前后映射和客户端证据；旧 v17 基线文件保持原样，兼容检查只允许这三项明确纠错。已有用户保存配置不做猜测性迁移；受旧错误影响的队伍需重新解析并核对后保存。

中文形态名称采用现有术语体系；来悲粗茶“杰作的样子”参照[官方介绍](https://www.pokemon.co.jp/ex/sv_dlc/sc/pokemon/231207_01/)，彩粉蝶“幻彩花纹”参照[官方介绍](https://www.pokemon.co.jp/ex/sv/tc/features/230228_02/)。英文实体 ID 保持不变。

## 防复发与重建

- 新增 `champions-roster.v18.json`，记录客户端全部形态参数和实际分配特性；`sync-champions-roster.mjs` 先校验 personal/tokusei 来源 SHA-256，拒绝缺项和未记录的编号含义变化。
- `validate-champions-catalogs.mjs` 从客户端清单反向检查所有关联，不依赖过滤后的目录证明完整。已接入 `android:assets` 和本地化资源更新流程。
- 导出任何实体缺少中文即失败；本地化生成遇到未明确翻译的形态 fallback 即失败；校验中文名称中残留英文后缀及图标、识别标签过期。
- `sync-catalog-labels.mjs` 只改识别记录的显示名称，不改编号、顺序、来源或图像特征。

当前 v18 重建顺序（`<client-dir>` 为仓库外的已验证导出）：

```powershell
node tools/team-code-resolver/generate-species-map.mjs <client-dir>/personal.json
node tools/team-code-resolver/sync-champions-roster.mjs <client-dir>
node tools/team-code-resolver/sync-champions-moves.mjs <client-dir>
node tools/android/prepare-champions-calc.mjs
node tools/localization/sync-zh-hans.mjs
node tools/team-code-resolver/import-master-data.mjs <client-dir>
npm.cmd test
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/run-gradle.ps1 :app:testDebugUnitTest
```

版本更新须重新审核来源、版本约束与映射，而非把未来原始数据直接当 v18 导入。原始客户端文件不提交。

## 验证与边界

- Node：35 项引擎/目录回归、8 项协议回归通过；TypeScript、版本、许可证检查通过。
- Android：151 项单元测试通过，0 失败、0 跳过；包含三处形态编号导入回归。
- 对 215 个特性和 166 个道具逐个执行攻守双方的打包引擎调用，均能返回有效结果。该检查证明计算入口可用，不代表穷举所有战斗机制组合。
- 识别二进制与修复前逐记录比较：1,084 条记录的图像特征全部字节一致，包括 366 条私人标注记录；仅 32 个显示名称变化。未重新进行私人截图语料识别准确率测试。
- 场地种子仍完全使用用户手动能力等级，原防自动叠加回归通过。
- 本次未构建、安装或发布 APK，尚无设备端本轮验收。


## 2026-09-15 手机验收补充

已在 RMX3820 真机完成代表性界面流程验证，PS 往返测试中补修性别转换。范围、结果及未覆盖部分见[手机验收记录](phone_acceptance_ps_catalogs_2026-09-15_zh.md)。上文未安装说明为该文初次提交时的状态。
