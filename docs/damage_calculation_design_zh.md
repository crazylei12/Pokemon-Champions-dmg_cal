# Pokemon Champions 伤害计算模块设计

日期：2026-06-30

## 1. 模块目标

伤害计算模块负责在用户确认的宝可梦、招式和对战条件下，计算任意一方使用某个招式打到另一方时的伤害范围。默认场景是“我方打对方”，但必须同样支持“对方打我方”，用于判断我方换入、留场或承受对方招式时会掉多少 HP。

它需要做到：

- 结果和主流宝可梦伤害计算器在同配置下保持一致。
- 所有关键假设都能被用户看到和修改。
- 信息未知时不假装精确，而是明确显示“基于当前假设”。
- 支持单打和双打的目标选择。
- 支持我方输出伤害和我方承受伤害两个计算方向。

它不负责：

- 自动识别画面。
- 推断对方真实隐藏配置。
- 自动选择最优招式。
- 自动推断对方本回合实际会使用哪个招式。
- 自动操作 Pokemon Champions。

## 2. 计算入口

用户点开伤害计算界面时，App 应先触发一次当前画面识别，读取双方当前场上宝可梦，并把它们作为默认攻击方和默认防守方。默认计算方向是“我方输出”，即我方当前宝可梦攻击对方当前宝可梦；用户必须能切换到“我方承伤”，即选择对方宝可梦的某个招式来打我方宝可梦。

默认值不等于锁定值。用户必须能从开局确认的双方阵容中重新选择攻击方和防守方，用来模拟换人、换入抵挡伤害、预读对方换入等实战场景。

用户在界面上最终选择：

- 计算方向：我方输出，或我方承伤。
- 攻击方：由计算方向决定，可以是我方某只宝可梦，也可以是对方某只宝可梦。
- 防守方：由计算方向决定，可以是对方某只宝可梦，也可以是我方某只宝可梦。
- 招式：攻击方的某个招式，或一次性计算攻击方已知招式；当攻击方是对方宝可梦时，主结果必须由用户从数据库中该宝可梦可使用的招式里选择。
- 对战状态：天气、场地、墙、能力变化等。

攻击方和防守方的选择范围：

- 默认显示当前场上识别结果。
- 我方输出方向下，攻击方可从我方开局确认阵容中选择，防守方可从对方开局确认阵容中选择。
- 我方承伤方向下，攻击方可从对方开局确认阵容中选择，防守方可从我方开局确认阵容中选择。
- 如果跳过队伍预览，则允许从手动列表或全图鉴中选择，但界面要提示“未绑定本局阵容”。
- 用户手动选择后，当前计算结果以用户选择为准，不能被下一次截图识别静默覆盖。

双打时需要明确槽位：

```text
我方左 -> 对方左
我方左 -> 对方右
我方右 -> 对方左
我方右 -> 对方右
对方左 -> 我方左
对方左 -> 我方右
对方右 -> 我方左
对方右 -> 我方右
```

双打中也要支持把防守方从真实场上槽位切换为任意一方后排宝可梦，用于估算换入承伤。此时界面应标记为“假设换入目标”，避免和真实场上槽位混淆。

## 3. 计算输入

计算模块的输入分成三层：

```text
DamageCalculationInput
  direction: OWN_TO_OPPONENT | OPPONENT_TO_OWN
  attackerSide: OWN | OPPONENT
  attackerBuild optional
  attackerIdentity optional
  attackerProfileSet optional
  defenderSide: OWN | OPPONENT
  defenderBuild optional
  defenderIdentity optional
  defenderProfileSet optional
  localizationContext: LocalizationContext
  moveSelection: ONE_MOVE | ALL_ATTACKER_MOVES
  battleCondition: BattleCondition
  outputMode: SELECTED_PROFILE | PROFILE_COMPARE | ENVELOPE
```

我方宝可梦通常是完整已知的 `KnownPokemonBuild`。对方宝可梦不管作为攻击方还是防守方，通常只有物种和形态来自 OCR/队伍预览识别，具体能力值、Stat Points、特性、道具、招式等未知信息由候选模板提供。

两种方向的数据来源不同：

- 我方输出：`attackerBuild` 使用我方完整配置，`defenderIdentity` 和 `defenderProfileSet` 描述对方防守候选。
- 我方承伤：攻击方是对方宝可梦，应由 `attackerProfileSet` 或等价的对方攻击模板提供攻击/特攻、特性、道具、能力等级等假设；防守方使用我方完整配置。

计算模块内部不能直接使用 OCR 识别到的中文文本。所有宝可梦、招式、道具、特性都必须先映射为稳定的内部 ID，再转换成 `@smogon/calc` 或其它计算核心需要的 ID。

### 3.1 攻击方配置

攻击方可能是我方宝可梦，也可能是对方宝可梦。

当攻击方是我方宝可梦时，配置应该尽量完整可编辑：

- 宝可梦种类和形态。
- 等级。
- 实际能力值，作为 OCR 保存队伍和伤害计算的主要能力输入。
- Stat Points，可作为手动编辑、外部预设或未来校验字段，不作为能力页 OCR 必需输入。
- Stat Alignment 或等价的性格修正，可作为可选信息；如果已经有最终能力值，伤害计算不依赖它。
- 特性。
- 道具。
- 招式。
- 当前属性变化，例如太晶或其他机制导致的类型变化。
- 攻击/特攻能力等级。
- 异常状态，例如烧伤会影响物理伤害。

传统 EV/IV 字段只作为导入兼容存在，例如从 Showdown 文本或旧 VGC 配置导入时使用。App 内部第一优先级应使用 Pokemon Champions 的最终实际能力值；Stat Points 在能稳定获得时再作为可选训练信息保存。

当攻击方是对方宝可梦时，不能假装知道完整配置。第一版应使用“对方攻击模板”：

- 默认攻击模板，例如无攻击投入、常规攻击、极限攻击/特攻。
- 开源预设或常见配置中的攻击项。
- 用户本局临时观察到的特性、道具、能力等级、异常状态。
- 用户选择的对方招式。

对方攻击模板只代表计算假设，结果区必须展示模板名称和关键前提。

### 3.2 防守方配置

防守方可能是对方宝可梦，也可能是我方宝可梦。对战开始的队伍选择界面只能看到对方有哪些宝可梦，看不到对方的能力值、Stat Points、特性、道具、招式等隐藏配置。

因此当防守方是对方宝可梦时，输入要拆成两部分：

```text
OpponentPokemonIdentity
  speciesId
  formId optional
  sourceTeamSlotIndex
  recognitionConfidence

DefenderProfile
  profileId
  profileName
  source: OPEN_SOURCE_PRESET | GENERATED_TEMPLATE | USER_CUSTOM | MANUAL_CURRENT
  level
  statPoints optional
  actualStats optional
  statAlignment optional
  ability optional
  item optional
  moves optional
  typeOverride optional
  notes
```

可见身份信息：

- 宝可梦种类和形态。
- 等级。

候选配置可以提供：

- Stat Points 模板。
- 实际能力值，作为高级覆盖输入。
- Stat Alignment 或等价的性格修正。
- 特性候选。
- 道具候选。
- 当前属性变化。
- 防御/特防能力等级。
- 当前 HP 百分比，若可识别或用户手动输入。
- 异常状态。

对方作为防守方时，不能要求用户一开始就填全。默认应该提供可切换模板。我方作为防守方时，优先使用用户保存的完整配置和当前 HP；如果我方配置缺失，也要提示用户补全关键防御参数。

### 3.3 招式配置

每个招式需要：

- 招式名称。
- 属性。
- 分类：物理、特殊、变化。
- 威力。
- 命中率。
- 是否接触。
- 是否范围招式。
- 是否有特殊伤害规则。
- 是否受天气、场地、道具、特性影响。

变化招式通常不输出伤害，界面可以显示为“无直接伤害”。

当攻击方是对方宝可梦时，招式选择必须来自数据模块维护的合法招式池，而不是让用户随意输入一个任意招式。建议数据结构：

```text
LegalMovePool
  speciesId
  formId optional
  rulesetVersion
  learnableMoves: MoveRef[]
  source: SHOWDOWN_DATA | CHAMPIONS_SNAPSHOT | USER_PATCH
  dataDate
```

界面要求：

- 只展示该宝可梦在当前规则集和形态下可使用的招式。
- 支持搜索，并优先排序常见预设招式、已经观察到的招式、本系攻击招式、高威力攻击招式。
- 上述限制用于对手未知配置。对于我方保存队伍或用户已经确认的实际配置，应保留其招式并以 `OWN_BUILD` 或 `MANUAL_OVERRIDE` 参与计算，即使静态快照暂未收录；静态池只能补充可选项，不能删除实际配置。
- 常见预设只能调整静态合法池内招式的排序；旧预设里的池外招式不得混入默认对手候选。
- 变化招式可以保留在列表中，但结果显示为“无直接伤害”或提示需要特殊处理。
- 如果数据库缺少该宝可梦的招式池，不能静默放开全招式选择；应提示“招式数据缺失”，允许用户临时手动添加并在结果中产生 warning。
- 用户选择的对方招式是一个明确假设，不代表 App 已经知道对方真实配招。

### 3.4 对战场地配置

需要允许用户修改：

- 单打或双打。
- 天气。
- 场地。
- 我方墙和对方墙，例如反射壁、光墙、极光幕。
- 是否处于帮助、保护、换入等特殊状态，第一版可先不支持全部。
- 招式是否为范围伤害。
- 是否会心。
- 是否属性一致加成。
- 是否存在属性免疫。

### 3.5 中文名称和计算核心 ID 映射

开源计算器仓库和 `@smogon/calc` 主要使用英文名称或 Showdown ID；用户的 Pokemon Champions 客户端可能是中文。OCR 读到的“十万伏特”“讲究眼镜”“威吓”不能直接传给计算核心。

需要增加本地多语言映射层：

```text
LocalizationContext
  gameLanguage: zh-Hans | zh-Hant | en | ja | ...
  displayLanguage: zh-Hans | zh-Hant | en | ...

LocalizedNameEntry
  entityType: SPECIES | MOVE | ITEM | ABILITY | NATURE | TYPE
  canonicalId
  showdownId
  englishName
  localizedNames
  aliases
```

处理流程：

```text
OCR 中文文本
  -> 文本规范化
  -> 中文名称/别名字典匹配
  -> canonicalId
  -> showdownId / @smogon/calc 输入
  -> 计算结果
  -> canonicalId
  -> 中文显示名称
```

规则：

- App 内部主键使用 `canonicalId`，不要使用中文名或英文名做主键。
- 对接 `@smogon/calc` 时再转换成它需要的 `showdownId` 或名称。
- UI 显示可以使用中文，但存档和计算请求都保存 ID。
- OCR 只输出候选文本和置信度，最终进入计算前必须完成 ID 归一化。
- 一词多义、形态差异、地区形态、Mega、特殊形态必须在映射层处理。
- 中文简体、中文繁体、英文别名应放在同一条实体记录里。
- 如果 OCR 识别到的中文名称无法映射，不能进入计算，只能让用户从候选列表手动选择。

示例：

```text
十万伏特 -> move.thunderbolt -> showdownId: thunderbolt
讲究眼镜 -> item.choicespecs -> showdownId: choicespecs
威吓 -> ability.intimidate -> showdownId: intimidate
暴鲤龙 -> species.gyarados -> showdownId: gyarados
```

这个映射层同时服务图像识别、队伍编辑、伤害计算和结果显示。

## 4. 用户可手动调整项

### 4.1 高频快捷项

这些应该放在悬浮面板或底部弹层里，一两次点击能改：

- 天气：无、晴、雨、沙暴、雪。
- 场地：无、电气、青草、薄雾、精神。
- 墙：反射壁、光墙、极光幕。
- 能力等级：攻击、特攻、防御、特防。
- 太晶/类型变化。
- 烧伤。
- 双打范围伤害。
- 目标保护状态，后续支持。

### 4.2 中频编辑项

这些可以放在完整编辑页：

- 实际能力值。
- Stat Points，可选。
- Stat Alignment，可选。
- 特性。
- 道具。
- 招式列表。
- 等级。
- 对方配置模板。

### 4.3 低频高级项

这些可以后续再做：

- 复杂特性联动。
- 复杂道具联动。
- 多回合击杀概率。
- 场上伙伴特性影响。
- 先制、速度线和行动顺序判断。
- 招式附加效果期望收益。

## 5. 对方未知配置的处理

防守方信息未知是实战中最常见的问题。第一版不要只给一个默认数字，而要把对方配置当成候选集合处理。

候选集合来源：

1. 开源计算器仓库自带的预设配置，如果该宝可梦有 Champions 可用配置，优先加入。
2. App 自己生成的基础模板，例如默认、常规耐久、极限物耐、极限特耐。
3. 用户手动保存的自定义配置，例如某个对手常见配置。
4. 本局临时手动配置，例如用户刚刚观察到对方道具或特性。

第一版至少提供三种生成模板：

### 5.1 默认模板

适合快速估算：

- 等级使用当前规则默认等级。
- Stat Points 使用 0 投入或常用默认模板，具体随当前规则确认。
- Stat Alignment 使用中性。
- 道具未知。
- 特性默认使用常见特性或不启用特性修正。

### 5.2 常规耐久模板

适合保守估计：

- HP 和相关防御项给常见 Stat Points 投入。
- Stat Alignment 按常见耐久修正。
- 用于回答“如果对方偏肉，我还能不能打掉”。

### 5.3 极限耐久模板

适合上限判断：

- HP 和对应防御项最大化。
- Stat Alignment 强化对应防御。
- 用于回答“最硬情况下大概打多少”。

界面上要明确写出当前使用的模板，不能只显示一个数字。

### 5.4 开源预设配置

如果 `@smogon/calc` / Pokemon Showdown Champions 计算器提供该宝可梦的预设配置，应作为敌方候选配置来源。

使用原则：

- 预设配置只作为“可能配置”，不是对方真实配置。
- 每个预设都要保留名称和来源，例如 `Showdown Champions preset`。
- 如果同一宝可梦有多个预设，默认进入多模板对比模式。
- 如果预设缺少 Champions 必要字段，要在适配层补齐或标记不可用。
- 预设数据版本要和计算核心版本一起固定，避免结果随依赖更新悄悄变化。

### 5.5 遍历配置和伤害包络

当防守方有多个候选配置时，计算模块应遍历每个 `DefenderProfile`：

```text
for each move in attacker.moves:
  for each defenderProfile in defenderProfileSet:
    calculate damage range
```

输出时不要只显示一个总的最小值和最大值。总包络有价值，但单独看会太粗。

推荐输出三层：

1. 当前选中配置的精确伤害范围。
2. 各个候选配置的伤害范围列表。
3. 所有候选配置合并后的总包络范围。

示例：

```text
十万伏特 -> 暴鲤龙

当前选中: Showdown 常规耐久
伤害: 82.4% - 97.1%

候选配置:
无耐久模板          93.2% - 110.1%
Showdown 常规耐久   82.4% - 97.1%
极限特耐模板        68.0% - 80.2%

总包络: 68.0% - 110.1%
说明: 包络基于 3 个候选配置，不代表对方真实配置一定在其中。
```

默认 UI 可以先显示“当前选中配置 + 总包络”，点击后展开全部候选配置。

### 5.6 敌方配置列表

预设配置、生成模板、自定义配置、现场编辑配置都必须放在同一个敌方配置列表里。用户不应该在不同入口之间来回找。

列表示例：

```text
暴鲤龙配置

(*) A 预设：Showdown 常规耐久      [编辑副本] [纳入包络]
( ) B 预设：Showdown 进攻型        [编辑副本] [纳入包络]
( ) 生成：极限物耐                 [编辑副本] [纳入包络]
( ) 自定义：我保存的天梯常见型      [编辑]     [纳入包络]
( ) 现场编辑：本局临时配置          [编辑]     [纳入包络]

[新增自定义] [从当前预设复制]
```

列表规则：

- 单选项表示“当前选中配置”，结果主视图默认展示它的伤害。
- `纳入包络` 表示这个配置是否参与总包络范围计算。
- 开源预设默认只读，用户点击编辑时应先复制成自定义配置，避免修改原始预设。
- 用户现场编辑对方属性、能力值、特性、道具时，会生成或更新一个 `MANUAL_CURRENT` 配置。
- 用户保存现场编辑后，可以把它变成长期 `USER_CUSTOM` 配置。
- 如果用户只想看某一个配置，可以取消其它配置的包络勾选。
- 如果所有配置都未纳入包络，App 应自动把当前选中配置纳入包络，避免输出空结果。

这样 A 预设、B 预设、自定义、现场编辑在数据结构和 UI 上都是同一种东西：`DefenderProfile`。

## 6. 输出结果

### 6.1 基础输出

每个招式展示：

```text
方向: 我方输出
攻击方: 皮卡丘
目标: 暴鲤龙
十万伏特
伤害: 82.4% - 97.1%
随机数: 16 档
OHKO: 0%
2HKO: 99.6%
前提: 对方默认耐久模板，无光墙，无天气修正
```

### 6.2 多招式列表

我方输出方向下，当用户选择攻击方和防守方后，默认计算我方该宝可梦的全部可用伤害招式：

```text
十万伏特      82.4% - 97.1%
伏特替换      54.2% - 64.0%
电光一闪       8.1% - 10.0%
守住          无直接伤害
```

如果防守方不是当前场上识别到的宝可梦，而是从对方阵容里手动选择的后排宝可梦，结果区域要显示这个前提：

```text
目标: 暴鲤龙
来源: 对方阵容手动选择，假设换入承伤
```

当用户切换到我方承伤方向后，主结果不默认遍历对方全合法招式，因为合法招式池可能很大，且容易制造噪音。第一版主流程应要求用户给对方选择一个招式：

```text
方向: 我方承伤
攻击方: 对方右  暴鲤龙
对方招式: 瀑布  [从暴鲤龙可用招式中选择]
目标: 我方左  皮卡丘
伤害: 61.3% - 72.5%
前提: 对方常规攻击模板，我方当前配置，无反射壁
```

高级视图可以提供“对方常见招式伤害列表”或“全部可用攻击招式扫描”，但必须和主结果区分，避免用户误以为这些都是对方已知配招。

### 6.3 关键提示

如果某些条件强烈影响结果，需要提示：

- 对方可能有属性免疫。
- 当前招式受烧伤影响。
- 当前招式在双打中按范围伤害修正。
- 当前结果假设对方没有抗性道具。
- 当前结果未考虑特性，因为特性未知。

### 6.4 模块输出结构

计算模块输出 `DamageCalculationResult`：

```text
DamageCalculationResult
  direction
  attackerSide
  attackerSummary
  defenderSide
  defenderIdentity
  selectedDefenderProfile optional
  selectedAttackerProfile optional
  moveResults: MoveDamageResult[]
  warnings: DamageWarning[]

MoveDamageResult
  moveId
  moveName
  moveSource: OWN_BUILD | OPPONENT_LEGAL_MOVE_POOL | PROFILE_PRESET | MANUAL_OVERRIDE
  selectedProfileRange: DamageRange
  profileRanges: ProfileDamageRange[]
  envelopeRange: DamageRange
  koSummary
  assumptions

ProfileDamageRange
  defenderProfileId
  defenderProfileName
  profileSource
  includedInEnvelope
  damageRange
  koChance
  notes

DamageRange
  minPercent
  maxPercent
  rolls
```

`selectedProfileRange` 用于快速显示当前配置。`profileRanges` 用于展开查看所有候选配置。`envelopeRange` 用于快速回答“在纳入包络的候选配置里，大概最少打多少、最多打多少”。

## 7. 计算模式

### 7.1 精确模式

所有参数都已知，输出单一配置下的准确伤害范围。

适合：

- 我方宝可梦。
- 用户手动录入过的对方配置。
- 测试用例。

### 7.2 假设模板模式

对方信息未知，按一个模板计算。

适合：

- 实战中快速估算。
- 刚识别出对方宝可梦但不知道 Stat Points、能力值、特性、道具。

### 7.3 多模板对比模式

同时展示多个防守模板：

```text
十万伏特
无耐久:     93.2% - 110.1%
常规耐久:   82.4% - 97.1%
极限耐久:   68.0% - 80.2%
```

这个模式对实战判断很有价值，可以作为 MVP 后的增强功能。

### 7.4 包络模式

对方信息未知，按多个候选配置计算，并输出总伤害包络。

适合：

- 对方只有物种已知，其它配置未知。
- 对方可能换入承伤，需要快速知道最硬和最脆情况下的大致差异。
- 用户想先看风险边界，再决定是否展开配置明细。

包络模式不能替代配置明细。UI 必须能展开查看每个候选配置的具体伤害，避免用户误以为总包络就是一个精确预测。

## 8. 计算核心实现建议

### 8.1 结论

结论：借用成熟开源伤害计算核心，不从零手写完整伤害公式；但 App 的交互、对局状态、识别结果接入、模板选择和本地数据封装由我们自己写。

不要把某个网页计算器直接嵌进 App 作为最终产品。网页计算器的 UI、状态管理、输入方式不适合我们的“截图识别 + 对局内快速切换目标”场景。

### 8.2 现有方案判断

目前已经存在支持 Pokemon Champions 的计算器生态：

- `calc.pokemonshowdown.com/champions.html?mode=champions` 是 Pokemon Showdown damage calculator 的 Champions 模式页面。
- 该页面底部链接到 GitHub repository；`smogon/damage-calc` README 声明它是 Pokemon Showdown damage calculator 的官方仓库。
- `smogon/damage-calc` 同时包含官方 UI 和核心包 `@smogon/calc`，许可证是 MIT。
- NCP VGC Damage Calculator 等项目已经加入 Champions 支持，也使用 MIT 许可证。
- Pikalytics、Game8、Pokebase、aseraz 等网页工具可作为交互和模板参考，但不一定适合作为可直接复用的代码依赖。

因此 `@smogon/calc` 或同源 MIT 代码可以作为第一版首选计算核心。

这里的重点不是怀疑它是否适配 Champions，而是工程上要确认开源代码如何被集成到安卓 App。该页面对应的仓库是开源的，且 `@smogon/calc` 被设计成可供其他 UI 或应用复用的计算包。我们的工程决策应该是“复用这个已经适配 Champions 的开源计算核心，再包一层适合本 App 的接口”，而不是把网页 UI 原样嵌进 App。

落地前必须完成：

- 固定依赖版本和数据快照。
- 抽象 `DamageEngineFacade`，避免业务层直接依赖 Smogon 数据结构。
- 用 Pokemon Showdown Champions 模式本身建立回归测试样例，确保我们封装前后的输入输出一致。
- 用其他 Champions 计算器或实机记录做少量交叉检查，主要用于发现版本快照和输入假设差异。
- 保留以后更新 `@smogon/calc` 版本或局部覆盖数据的能力。

### 8.3 推荐实现路径

推荐分三层：

```text
Android UI / 对局状态
  -> DamageEngineFacade
    -> Smogon/Showdown 计算核心适配层
      -> Pokemon / Move / Ability / Item / Champions 规则数据
```

`DamageEngineFacade` 是我们自己的稳定接口。它负责把 App 内部的 `DamageRequest` 转成底层计算核心需要的输入，再把底层结果转成 App UI 要展示的百分比、随机数档位、OHKO/2HKO 结论和前提说明。

业务层只能依赖 `DamageEngineFacade`，不能直接散落调用 `@smogon/calc`。这样后续更新 `@smogon/calc`、替换 JS runtime、或补充 Champions 数据映射时，只需要改适配层。

底层实现第一版建议使用 JavaScript/TypeScript 计算核心：

- 优先使用 `@smogon/calc` Champions 模式或基于它的 MIT 版本。
- 用 npm/Node 先在本地建立回归测试。
- 再把打包后的 JS 作为安卓本地资源运行。
- 安卓端可以通过 WebView JS bridge、QuickJS、J2V8 或其他 JS runtime 调用。

如果后续性能、包体或维护要求变高，再考虑把核心公式移植到 Kotlin。移植只能在有足够回归测试后进行。

### 8.4 不推荐路线

不推荐第一版从零手写完整公式，原因是：

- 宝可梦伤害公式有大量边角修正。
- Champions 还有 Stat Points、Mega、规则集和新招式/道具/特性的变化。
- 手写公式很容易在 95% 场景正确、5% 关键场景错误。
- 一旦公式错，图像识别和 UI 做得再好也会失去可信度。

也不推荐依赖线上网页或第三方 API 实时计算：

- 网络不稳定会影响对战中使用。
- 第三方网页结构随时可能变。
- 用户截图和队伍信息不应无必要上传。
- 授权边界不清楚。

### 8.5 数据策略

静态数据分三类处理：

1. 基础规则数据：宝可梦、招式、属性、特性、道具、形态、Mega 等，优先来自开源计算核心配套数据或 Pokemon Showdown / pkmn 系数据。
2. Champions 配置数据：Stat Points、当前规则集、可用宝可梦、可用招式、可用道具等，需要按版本快照保存，并标记数据日期。
3. 常见模板数据：对方常见耐久、常见道具、常见招式等，只作为便捷假设，不作为官方事实。
4. 多语言名称数据：中文、英文、别名、形态名与 `canonicalId/showdownId` 的映射，用于 OCR 和计算核心之间的适配。

App 内部应原生保存实际能力值，并允许补充 Stat Points：

- `actualStats`：OCR 队伍导入的主要能力输入，直接表示游戏界面展示的最终能力值。
- `statPoints`：可选训练输入，用于手动编辑、外部预设或校验，不作为当前能力页 OCR 必需项。
- `legacyEvs` / `legacyIvs`：仅用于导入兼容，不作为 Champions 第一输入。

### 8.6 许可证和署名

只复用明确开源授权的代码。MIT 代码可以使用、修改和分发，但需要保留版权声明和许可证文本。

没有明确许可证的网页、仓库或数据源不能直接复制代码和数据。可以观察交互思路，也可以用公开页面做人工结果对照，但不能把代码当依赖。

不建议一开始完全凭记忆重写全部公式。宝可梦伤害公式里有大量细节修正，直接手写容易出现隐蔽误差。

## 9. 数据模型草案

```text
DamageRequest
  direction: OWN_TO_OPPONENT | OPPONENT_TO_OWN
  attackerSide: OWN | OPPONENT
  attacker optional
  attackerIdentity optional
  attackerProfileSet optional
  defenderSide: OWN | OPPONENT
  defender optional
  defenderIdentity optional
  defenderProfileSet optional
  localization: LocalizationContext
  moveSelection: ONE_MOVE | ALL_ATTACKER_MOVES
  battle: BattleCondition
  calculationMode: EXACT | TEMPLATE | COMPARE_TEMPLATES | ENVELOPE

KnownPokemonBuild
  speciesId
  formId
  level
  actualStats
  statPoints optional
  statAlignment optional
  legacyEvs optional
  legacyIvs optional
  ability
  item
  moves
  typeOverride
  statStages
  status
  currentHpPercent optional

OpponentPokemonIdentity
  speciesId
  formId optional
  sourceTeamSlotIndex
  recognitionConfidence

DefenderProfileSet
  defenderSpeciesId
  selectedProfileId
  profiles: DefenderProfile[]

DefenderProfile
  profileId
  profileName
  source: OPEN_SOURCE_PRESET | GENERATED_TEMPLATE | USER_CUSTOM | MANUAL_CURRENT
  baseProfileId optional
  isSelected
  includedInEnvelope
  editable
  level
  statPoints optional
  actualStats optional
  statAlignment optional
  ability optional
  item optional
  moves optional
  typeOverride optional
  notes

OpponentAttackerProfileSet
  attackerSpeciesId
  selectedProfileId
  profiles: OpponentAttackerProfile[]

OpponentAttackerProfile
  profileId
  profileName
  source: OPEN_SOURCE_PRESET | GENERATED_TEMPLATE | USER_CUSTOM | MANUAL_CURRENT
  level
  statPoints optional
  actualStats optional
  statAlignment optional
  ability optional
  item optional
  moves optional
  statStages optional
  status optional
  notes

LegalMovePool
  speciesId
  formId optional
  rulesetVersion
  learnableMoves
  source
  dataDate

LocalizationContext
  gameLanguage
  displayLanguage

LocalizedNameEntry
  entityType
  canonicalId
  showdownId
  englishName
  localizedNames
  aliases

BattleCondition
  battleType: SINGLE | DOUBLE
  weather
  terrain
  attackerSideConditions
  defenderSideConditions
  isCritical
  isSpreadMove
  customFlags
```

## 10. 测试策略

伤害计算模块必须有自动测试。

测试来源：

- 与 Pokemon Showdown / Smogon Champions 模式人工对照出的样例。
- 与其他 Champions 计算器人工对照出的样例。
- 同一攻击方遍历多个敌方候选配置时的包络范围样例。
- 对方作为攻击方、我方作为防守方时的承伤样例。
- 对方招式从合法招式池选择时的 ID 映射和非法招式 warning 样例。
- 常见属性克制样例。
- 天气、场地、墙、烧伤、能力等级等关键修正样例。
- 双打范围招式样例。
- 特性和道具样例。
- 中文名称到计算核心 ID 的映射样例，例如中文招式、中文道具、中文特性。
- Stat Points 转实际能力值样例。
- Mega、当前规则集新增招式/道具/特性的样例。

每次改公式都要跑回归测试。伤害计算错了，识别做得再好也没有意义。

## 11. MVP 验收标准

第一版伤害计算模块完成以下能力即可：

1. 用户能手动录入一只我方宝可梦配置。
2. 用户打开计算界面时，App 能把当前场上识别结果作为默认攻击方和防守方。
3. 用户能在“我方输出”和“我方承伤”之间切换。
4. 用户能从开局确认的我方阵容中切换我方宝可梦。
5. 用户能从开局确认的对方阵容中切换对方宝可梦，用于模拟换入承伤或对方换人攻击。
6. 我方承伤时，用户能从数据库中该对方宝可梦可使用的招式里选择对方招式。
7. 用户能选择或编辑双方等级、Stat Points、实际能力值、Stat Alignment、特性、道具、招式。
8. 对方配置未知时，App 能为该宝可梦生成或读取多个防守模板和攻击模板。
9. 预设配置、自定义配置、现场编辑配置在同一个敌方配置列表里展示。
10. 用户能在敌方候选配置之间切换，并决定哪些配置纳入包络。
11. App 能遍历候选配置并输出每个招式的当前配置伤害、配置列表伤害和总包络范围。
12. App 能把中文 OCR 结果映射到内部 ID 和 `@smogon/calc` 可接受的 ID。
13. 用户能切换天气、场地、墙、能力等级等核心修正项。
14. 至少 20 个回归样例与主流计算器结果一致，其中包含我方输出和我方承伤样例。

## 12. 当前建议

先做“手动精确计算器”，再接识别结果。伤害计算模块只接受结构化输入，不关心这些输入来自截图识别还是用户手动选择。这样模块边界清楚，后续调试也简单。

第一版的核心策略是：首选使用 `@smogon/calc` 或同源 MIT 计算核心 + 我们自己的 `DamageEngineFacade` + Champions 原生 Stat Points 数据模型。这样既能借到已经适配 Champions 的成熟公式，又不会把我们的安卓体验绑死在别人的网页 UI 上。

敌方配置未知是第一版就必须处理的核心场景。默认做法是：OCR 只提供对方物种，伤害模块为该物种加载开源预设配置和生成模板。对方作为防守方时遍历候选防守配置，对方作为攻击方时遍历或选择候选攻击配置，并要求对方招式来自该宝可梦当前规则集下的合法招式池。

## 13. 参考资料

- Pokemon Showdown Champions Damage Calculator：https://calc.pokemonshowdown.com/champions.html?mode=champions
- Smogon damage-calc 官方仓库：https://github.com/smogon/damage-calc
- Smogon damage-calc 许可证：https://github.com/smogon/damage-calc/blob/master/LICENSE
- NCP VGC Damage Calculator：https://github.com/nerd-of-now/NCP-VGC-Damage-Calculator
- Pikalytics Champions Damage Calculator：https://www.pikalytics.com/damage-calculator
- aseraz Champions in-battle calculator 说明：https://www.smogon.com/forums/threads/aseraz-in-battle-damage-calculator-for-pok%C3%A9mon-champions-pc-browser.3782059/
- VGC Damage Calc for Champions 安卓端参考：https://play.google.com/store/apps/details?id=io.github.thecano.mobile_vgc_damage_calculator
