# OCR 与伤害计算器接口说明

本接口位于 `src/damage`，作用是把 OCR、用户修正、队伍模板合并后的结构化数据传给伤害计算器，并把计算结果整理成 GUI 可直接展示的数据。

## 1. 输入接口

业务层只调用：

```ts
import {createDefaultDamageEngine} from '../src/damage';

const result = createDefaultDamageEngine().calculate(request);
```

`request` 类型是 `DamageRequest`。OCR 不应直接把中文字符串传进来，而是先经过名称映射，变成 `EntityRef`：

```ts
{
  entityType: 'species',
  canonicalId: 'species.pikachu',
  showdownId: 'Pikachu',
  displayName: '皮卡丘',
  originalText: '皮卡丘',
  confidence: 0.94,
  source: 'ocr'
}
```

第一版需要填：

- `calculationDirection`: 计算方向，`OWN_TO_OPPONENT` 表示我方输出，`OPPONENT_TO_OWN` 表示我方承伤。
- `attacker`: 当前攻击方配置。方向为我方输出时是我方完整配置；方向为我方承伤时是由对方身份、对方攻击模板和用户选择的对方招式合成出的攻击方配置。
- `defenderIdentity`: OCR 或用户选择确认的防守方身份，至少要有物种/形态。方向为我方承伤时，防守方可以是我方完整配置。
- `defenderProfileSet`: 防守方候选配置列表，例如无耐久、常规耐久、极限耐久、用户现场编辑配置。方向为我方输出时主要用于对方防守假设。
- `attackerProfileSet`: 可选。方向为我方承伤时，用于描述对方攻击候选配置，例如无攻击投入、常规攻击、极限物攻、极限特攻、用户现场编辑配置。
- `moveSelection`: 计算一个招式还是攻击方全部招式。方向为我方承伤时，主结果应使用用户从对方合法招式池中选择的一个招式。
- `battle`: 单打/双打、天气、场地、墙、守住、帮助、隐形岩、会心、范围招式等条件。
- `calculationMode`: 精确计算、模板计算、模板对比或包络输出。

当前代码里的字段名仍然是 `attacker` / `defender`，业务层不能再假设 `attacker` 一定是我方。后续类型扩展时建议显式加入 `calculationDirection`、`attackerSide`、`defenderSide`，让 GUI 能清楚标记结果方向。

我方承伤方向还需要额外的招式来源信息：

```ts
moveSelection: {
  mode: 'ONE_MOVE',
  moveId: 'move.waterfall',
  source: 'OPPONENT_LEGAL_MOVE_POOL',
  legalMovePoolVersion: 'champions-2026-06-30'
}
```

约束：

- `moveId` 必须来自数据模块中该对方宝可梦在当前规则集下的合法招式池。
- 如果用户临时手动添加数据库缺失的招式，接口可以接收，但必须带 `MANUAL_OVERRIDE` 来源并输出 warning。
- 伤害引擎不负责猜测对方本回合真实点击了哪个招式，只计算用户明确选择的对方招式。

Pokemon Champions 的最终面板能力值使用 `actualStats` 字段。OCR 导入我方保存队伍时，应优先填这个字段：

```ts
{hp: 189, atk: 141, def: 90, spa: 63, spd: 84, spe: 167}
```

如果后续来源能稳定提供 Stat Points，也可以作为可选字段传入 `statPoints`，键名推荐用：

```ts
{hp: 1, atk: 0, def: 0, spa: 32, spd: 0, spe: 32}
```

兼容外部预设时也可以传旧缩写：

```ts
{hp: 1, sa: 32, sp: 32}
```

适配层会把 `statPoints` 转换成 Smogon Champions 模式需要的 `evs` 输入；如果传入 `actualStats`，则会转换成一次性的能力值覆盖来计算。

## 2. 输出接口

返回值类型是 `DamageCalculationResult`，GUI 主要读取：

- `calculationDirection`: 当前结果方向，用于显示“我方输出”或“我方承伤”。
- `attackerSummary`: 当前攻击方摘要。
- `defenderIdentity`: 当前防守方身份。
- `selectedDefenderProfile`: 当前选中的防守模板。
- `selectedAttackerProfile`: 可选，当前选中的对方攻击模板。
- `moveResults`: 每个招式的展示结果。
- `warnings`: 输入缺失、未应用自定义条件、未知物种、未知招式或非法敌方招式等提示。

每个 `MoveDamageResult` 包含：

- `moveName`: GUI 展示的招式名。
- `moveSource`: 招式来源，例如我方配置、对方合法招式池、预设配置或临时手动覆盖。
- `selectedProfileRange`: 当前选中模板下的伤害范围。
- `profileRanges`: 每个防守模板的伤害明细。
- `envelopeRange`: 所有纳入包络模板的总最小/最大范围。
- `koSummary`: OHKO、2HKO 等击杀结论。
- `assumptions`: 本次结果基于哪些假设。

GUI 最常用字段：

```ts
moveResult.moveName
moveResult.moveSource
moveResult.selectedProfileRange.minPercent
moveResult.selectedProfileRange.maxPercent
moveResult.koSummary.text
moveResult.assumptions
```

`selectedProfileRange.description` 保留了底层计算器的完整英文描述，可用于调试或高级详情页；正式 GUI 可以优先使用百分比字段自行排版。

## 3. 当前边界

- 这层只接受已经确认或已经归一化的实体 ID，不负责 OCR 识别本身。
- 这层只验证和计算“用户选择的对方招式”是否能被数据模块和计算核心识别，不负责判断对方是否真的会使用该招式。
- `customFlags` 会原样保留在请求中，但当前适配器不会应用，会返回 `CUSTOM_FLAGS_NOT_APPLIED` warning。
- `actualStats` 会被转换成一次性的 base-stat override，用于让底层计算器接近指定实际能力值；OCR 保存队伍时优先使用 `actualStats`，`statPoints` 只作为可选校验/导入字段。
- 防守方没有配置时，适配器会生成一个空的 `MANUAL_CURRENT` 模板，保证 GUI 能得到明确 warning 而不是直接崩溃。
- 如果方向为我方承伤但没有选择对方招式，接口应返回明确输入错误或 warning，GUI 不能展示一个没有招式前提的承伤结果。
