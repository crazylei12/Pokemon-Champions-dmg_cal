# Pokemon Champions 图像识别流程设计

日期：2026-06-30；更新：2026-07-14

## 1. 模块目标

图像识别模块负责把用户授权截取到的 Pokemon Champions 安卓端画面，转换成可供队伍管理、对局状态和伤害计算使用的结构化信息。

第一版识别目标分成四类截图：

1. 我方队伍配置-招式道具页。
2. 我方队伍配置-能力值页。
3. 对战队伍预览页。
4. 对战场地页。

核心思路是：先用前两类截图把用户自己的队伍完整导入并保存，之后每局只需要识别对方队伍和当前场上宝可梦。这样我方配置不需要在对战中临时猜测，伤害计算可以直接读取已确认的 `KnownPokemonBuild`。

图像识别模块只回答这些问题：

- 当前截图属于哪一种画面？
- 这张截图中有哪些可裁剪的固定区域？
- OCR 或图像匹配得到哪些实体候选？
- 这些候选能否映射为稳定的宝可梦、招式、特性、道具、属性或能力值字段？
- 哪些位置需要用户确认或手动修正？

它不负责：

- 计算伤害。
- 推测对方隐藏努力值、道具、特性。
- 读取游戏内存或网络数据。
- 自动点击、自动选择招式或自动控制游戏。

## 2. 四类截图

### 2.1 我方队伍配置-招式道具页

这是用户导入我方队伍的第一张截图。当前 `docs/pic` 示例图属于这一类。

这类画面通常有 6 个固定队伍卡片，每个卡片展示：

- 宝可梦名称。
- 性别和属性图标，可作为辅助校验。
- 特性。
- 道具。
- 四个招式。
- 招式属性图标，可作为辅助校验。
- 队伍栏位、队伍 ID、用户昵称等队伍元数据。

识别输出应写入一个待确认的 `OwnTeamImportDraft`。每个宝可梦槽位至少需要得到：

```text
OwnTeamMoveItemSlotDraft
  slotIndex
  speciesCandidates: EntityCandidate[]
  abilityCandidates: EntityCandidate[]
  itemCandidates: EntityCandidate[]
  moveCandidates: EntityCandidate[4]
  typeIconHints
  genderHint optional
  confidence
  warnings
```

本页不负责读取最终能力值。识别完成后，App 应提示用户继续截取“能力值页”，或者允许用户先保存一个缺少能力值的草稿。

Android 悬浮导入把招式/道具页视为一轮双页识别的明确起点。识别器会同时检查能力值数字证据和特性、道具、招式实体证据；每次确认当前画面为 `OWN_TEAM_MOVE_ITEM` 时，必须清空上一轮尚未完成的招式/道具页与能力值页缓存，再保存当前页。这样用户中断上一支队伍后直接打开另一支队伍的招式/道具页，不会把新队伍与旧能力值页错误合并。

### 2.2 我方队伍配置-能力值页

这是用户导入我方队伍的第二张截图。当前版本只要求读取界面展示的最终能力值，不再要求 OCR 识别右侧加点数字或通过颜色判断性格。

预期识别内容包括：

- 宝可梦名称或槽位标识，用于和招式道具页的 6 个槽位对齐。
- 等级。
- 实际能力值：HP、攻击、防御、特攻、特防、速度。
- 当前属性、特殊形态或其他会影响伤害计算的配置项，如果界面有展示。

识别输出应合并到同一个 `OwnTeamImportDraft`：

```text
OwnTeamStatSlotDraft
  slotIndex
  speciesCandidates: EntityCandidate[]
  levelCandidate
  actualStatCandidates
  formCandidates
  confidence
  warnings
```

合并规则：

1. 优先按槽位顺序合并。
2. 槽位顺序不可靠时，用宝可梦名称候选做二次匹配。
3. 如果两张图对同一槽位识别到不同宝可梦，必须让用户确认。
4. 即使两张图的所有字段都被识别器判定为完整，也必须先展示同一个确认/手动修正窗口，不能直接进入命名界面。
5. 用户确认后生成可持久化的 `SavedOwnTeam`。

### 2.3 对战队伍预览页

这类截图用于建立本局对方候选池。它不替代我方队伍配置导入。

重要约束：当前样例中的对方队伍卡片不显示宝可梦名字，只显示头像、属性图标和性别图标。因此对方 6 只不能走“名称 OCR -> 词典匹配”的主路径，必须走“头像 ROI -> 图像匹配 -> 物种候选 -> 用户确认”的路径。我方左侧卡片仍有名称和道具文本，可继续用 OCR 识别并和保存队伍做校验。

需要识别：

- 对方 6 只宝可梦头像对应的物种候选。
- 我方当前选择的 6 只宝可梦，主要用于校验用户选择的 `SavedOwnTeam` 是否匹配。
- 单打或双打模式，如果画面中可以判断。
- 每个位置的 Top-3 宝可梦候选。
- 对方槽位中的属性图标和性别图标，作为头像候选的辅助校验。
- 是否存在未识别位置。

不要求第一版识别：

- 对方道具。
- 对方特性。
- 对方加点。
- 对方招式。
- 精确出场顺序。

队伍预览确认后，写入 `BattleSession.opponentTeam`。我方队伍优先来自用户选择的 `SavedOwnTeam`；如果队伍预览中识别到的我方 6 只和保存队伍不一致，界面应提示用户切换保存队伍或手动确认。

### 2.4 对战场地页

这类截图用于判断当前参与计算的场上宝可梦。

单打需要识别：

- 我方当前宝可梦。
- 对方当前宝可梦。
- 双方当前 HP 条可见时的大致 HP 百分比，第一版可选。

双打需要识别：

- 我方左侧宝可梦。
- 我方右侧宝可梦。
- 对方左侧宝可梦。
- 对方右侧宝可梦。
- 每个槽位是否为空、倒下或正在切换，第一版可以只做人工确认。

本页识别结果只更新 `activeOwnSlots` 和 `activeOpponentSlots`。它不能静默覆盖用户已经手动选择的伤害计算攻击方或防守方。

不要求第一版识别：

- 动画中的临时遮挡。
- 所有状态图标。
- 场地、天气、墙的全部图标。
- 对方真实招式。

## 3. 输入和输出

### 3.1 输入

每次识别的输入是一张屏幕截图和少量上下文：

- 截图 bitmap。
- 屏幕宽高、方向、像素密度。
- 用户选择的游戏语言，例如中文或英文。
- 当前识别意图，例如导入我方队伍、识别队伍预览或识别对战场地。
- 当前已有的 `SavedOwnTeam` 列表。
- 当前 `BattleSession`，例如是否已经确认过对方队伍。

### 3.2 输出

识别模块输出 `RecognitionResult`：

```text
RecognitionResult
  sceneType:
    OWN_TEAM_MOVE_ITEM
    OWN_TEAM_STATS
    TEAM_PREVIEW
    BATTLE_FIELD
    UNKNOWN
  sideMode: SINGLE | DOUBLE | UNKNOWN
  ownTeamImportDraft optional
  ownTeamCandidates: TeamSlotCandidate[]
  opponentTeamCandidates: TeamSlotCandidate[]
  activeOwnSlots: ActiveSlotCandidate[]
  activeOpponentSlots: ActiveSlotCandidate[]
  fieldHints: FieldHint[]
  warnings: RecognitionWarning[]
```

每个识别候选项都必须尽量映射为实体，而不是只返回一段中文字符串或一块图像：

```text
EntityCandidate
  rawText
  roiId optional
  normalizedText
  language
  entityType: SPECIES | MOVE | ITEM | ABILITY | TYPE | NATURE | STAT
  canonicalId optional
  showdownId optional
  displayName
  confidence: 0.0 - 1.0
  source: OCR_TEXT | POKEMON_ICON_MATCH | TYPE_ICON_HINT | TEAM_CONTEXT | USER_LOCKED
```

## 4. 保存队伍数据

用户完成两张我方队伍配置图识别并确认后，App 保存 `SavedOwnTeam`：

```text
SavedOwnTeam
  savedTeamId
  teamSlotName
  gameTeamId optional
  displayName optional
  gameLanguage
  sourceScreenshots
  pokemon: KnownPokemonBuild[6]
  importConfidence
  userConfirmed
  createdAt
  updatedAt
```

PC 调试脚本会在 OCR 完成后写出同样语义的 JSON 队伍文件：

```powershell
npm run recognition:rapidocr-own-team -- --team-name "Michi队"
```

默认保存到 `data/saved-teams/<队伍名或队伍ID>-<时间戳>.json`，同时在 `.tmp/rapidocr-own-team/summary.json` 里记录 `outputs.savedTeamFile`。每个成员都包含 `build` 字段，可直接用于后续拼装伤害计算器的 `KnownPokemonBuild`。

每个 `KnownPokemonBuild` 应尽量包含：

- 宝可梦和形态。
- 等级。
- 实际能力值，作为伤害计算的主要能力输入。
- Stat Points 或 Stat Alignment，如果未来界面版本能稳定读取，可作为可选校验字段，不作为 OCR 必需项。
- 特性。
- 道具。
- 四个招式。
- 当前属性或特殊形态信息，如果界面有展示。

保存队伍的用途：

1. 对战中选择我方攻击方时，直接读取完整配置。
2. 我方承伤时，直接读取我方防守方完整配置。
3. 队伍预览页识别到我方 6 只后，用来推荐用户切换到匹配的保存队伍。
4. 用户可以在 App 内切换队伍，不需要每局重新输入招式、道具和最终能力值。

如果导入时缺少能力值页，保存队伍可以处于 `INCOMPLETE` 状态，但伤害计算界面必须提示“我方能力值未完整导入”。

## 5. 识别流水线

### 5.1 截图采集

使用 Android MediaProjection API，由用户授权后截取单张屏幕。

第一版推荐交互：

1. 用户在 App 中选择识别任务。
2. App 提示用户切换到指定游戏界面。
3. 用户点击通知栏快捷按钮或悬浮按钮。
4. App 截一张当前屏幕。
5. App 立即停止本次采集。
6. 截图只在本地用于识别和用户确认。

导入我方队伍时，App 应按顺序引导：

```text
选择或新建保存队伍
  -> 提示截取招式道具页
  -> 提示截取能力值页
  -> 展示识别结果
  -> 用户确认或修正
  -> 保存 SavedOwnTeam
```

双页不完整时的补全与确认应继续停留在系统悬浮窗口中；窗口外的游戏区域保持可见、可操作，窗口本身支持拖动和缩放。宝可梦、道具和招式搜索统一按中文显示名、英文 Showdown ID 与规范 ID 匹配，并忽略英文大小写、空格、连字符及全角/半角差异。

### 5.2 画面归一化

对截图做基础处理：

- 记录原始分辨率。
- 按横屏/竖屏分类。
- 缩放到内部标准宽度用于识别。
- 保留原图和缩放图之间的坐标映射。
- `TEAM_PREVIEW` 使用居中的最大 16:9 游戏画布作为 SafeZone 坐标映射边界，不把手机或平板额外显示的场馆背景计入 ROI 拉伸。
- 对文字 ROI 做亮度、对比度、锐化、二值化等轻量预处理。
- 对图标 ROI 保留颜色信息，用于属性、招式类型或宝可梦头像辅助匹配。

### 5.3 场景分类

先判断画面属于哪类，再选择不同 ROI。

场景类型：

- `OWN_TEAM_MOVE_ITEM`：我方队伍配置-招式道具页。
- `OWN_TEAM_STATS`：我方队伍配置-能力值页。
- `TEAM_PREVIEW`：对战队伍预览页。
- `BATTLE_FIELD`：对战场地页。
- `UNKNOWN`：无法确认。

判断依据可以组合使用：

- 固定 UI 文本 OCR，例如“能力”“状态”“队伍 ID”等。
- 固定区域颜色和布局特征。
- 6 个队伍卡片的网格布局。
- 是否存在双方队伍预览槽位。
- 是否存在场上 HP 条、槽位或战斗 UI。

如果场景分类置信度低，进入人工选择场景。导入我方队伍时，用户已经明确选择任务，场景分类可以作为校验，而不是强行自动判断。

### 5.4 ROI 裁剪

ROI 配置应独立成表，按场景、分辨率、横竖屏和 UI 版本管理。

我方队伍配置-招式道具页：

```text
teamHeader
slot[0..5].speciesName
slot[0..5].typeIcons
slot[0..5].abilityName
slot[0..5].itemName
slot[0..5].moveName[0..3]
slot[0..5].moveTypeIcon[0..3]
```

我方队伍配置-能力值页：

```text
teamHeader
slot[0..5].speciesName or slotMarker
slot[0..5].level
slot[0..5].actualStats.hp/atk/def/spa/spd/spe
slot[0..5].formOrTypeHints
```

对战队伍预览页：

```text
ownPreviewSlot[0..5].speciesName
ownPreviewSlot[0..5].pokemonIcon optional
opponentPreviewSlot[0..5].pokemonIcon
opponentPreviewSlot[0..5].typeIcons
opponentPreviewSlot[0..5].genderIcon optional
battleTypeHint
```

对战场地页：

```text
activeOwnSlot[SINGLE|LEFT|RIGHT].speciesName or icon
activeOpponentSlot[SINGLE|LEFT|RIGHT].speciesName or icon
hpBar[own/opponent/position]
fieldIconHints optional
```

当前 `TEAM_PREVIEW` SafeZone v2 以 3392×2400 基准图维护 12 个头像矩形，并通过基准图和查询图各自的居中 16:9 游戏画布统一映射。不同设备分辨率或整图宽高比不再需要单独手写坐标表，但画面仍必须是同一正确队伍预览布局；错误页面、非等比压缩或游戏 UI 改版必须拒绝或重新建立回归基线。

### 5.5 OCR 名称识别

OCR 用于读取宝可梦、招式、道具、特性和数值文本。

处理方式：

- 我方队伍导入页使用 RapidOCR，不使用旧 Tesseract 路线。
- PC 调试使用 `rapidocr` + `onnxruntime`，安卓端使用 RapidOcrAndroidOnnx。
- PC 与安卓端尽量共享同一批 det、rec、cls ONNX 模型。
- 对固定队伍卡片可以先检测卡片区域，再在卡片相对坐标中提取字段。
- 针对游戏语言加载对应名称词典。
- OCR 结果先做规范化，例如去空格、统一全角半角、处理常见误识别。
- 用词典做模糊匹配，输出 Top-3 候选。
- 对数值字段做范围校验，例如等级、能力值和 Stat Points 不应超出合理范围。

中文名称识别模块负责把“风妖精”“月亮之力”“巨金怪进化石”等文本映射成内部 `canonicalId`。伤害计算模块只接收已经归一化的实体 ID。

当前 PC 端已验证的主入口：

```powershell
npm run recognition:rapidocr-own-team -- --output-dir .tmp/rapidocr-own-team --python .\.tmp\rapidocr-venv\Scripts\python.exe
```

该命令批量读取：

- `docs/pic/own_team_move_item/`
- `docs/pic/own_team_stats/`

输出内容直接面向伤害计算器需要的队伍配置：

- 宝可梦、特性、道具、四个招式。
- 实际能力值 `actualStats`。

数值页需要特别处理 RapidOCR 的合并 token。例如界面中的最终能力值和右侧加点可能被整行识别为 `18929`，后处理必须优先使用 word box 或按字符位置拆分，再按卡片相对坐标只落到最终能力值格子。右侧加点和性格颜色不进入 OCR 主路径；如果最终能力值低置信度或缺失，应进入 warnings 并等待用户确认。

如果 OCR 漏掉单个 `0` 或把少数字符误读成不可能的能力值，可以用已识别的物种、加点和能力修正做公式一致性校验。凡是通过公式补齐的字段必须写入 `inferences`，确认页应能提示用户这是推断值，而不是直接 OCR 读到的值。

如果整图 OCR 漏掉某个字段，可以裁剪对应队伍卡片放大后再用同一个 RapidOCR 模型二次识别。二阶段补齐字段必须写入 `ocrFallbacks`，便于回归和排错。

注意：`TEAM_PREVIEW` 中对方 6 只宝可梦不显示名称文本，不能调用文字 OCR 当主路径。该场景必须走头像 ROI 图像识别；OCR 只可用于我方名称、按钮提示或场景辅助校验。

### 5.6 图标或头像匹配

图标匹配通常用于补充 OCR，但在队伍预览页的对方槽位里是第一版的主要来源，因为游戏界面不显示对方宝可梦名称。

当前推荐方案与“截图裁剪 + 图像匹配 / 特征匹配”的思路一致，不先训练深度学习模型。差异在于实现时不要只依赖单一模板匹配分数，而是把多个传统视觉信号融合成可回归的 Top-N 候选。

对方队伍预览的头像识别流程：

1. 按 `team_preview.opponent.slot0..5.pokemon_icon` 裁出头像区域。ROI 应略大于可见宝可梦主体，优先保证尾巴、翅膀、耳朵和底部身体不被裁掉。
2. 对 crop 做多版本预处理：
   - 原始彩色图：保留游戏内颜色和透明边缘。
   - 前景 mask：去掉红色卡片背景、分割线、选中/等待遮罩。
   - 灰度边缘图：削弱颜色差异，增强轮廓。
   - HSV 直方图：保留颜色分布，用于区分轮廓接近的宝可梦。
   - pHash/dHash：作为快速粗筛和明显不匹配过滤。
3. 模板库从 `src/data/pokemon-icons/catalog.pokeapi-composite.json` 加载：
   - `icon`：普通视觉模板，优先来自 52poke Champions sprite 或 PokeAPI Gen 8 icons。
   - `iconVariants`：额外视觉模板，例如 `visualVariant: shiny` 的闪光形态。
   - `USER_LABELED_SCREENSHOT`：用户确认过的游戏内裁剪模板，后续应作为最高优先级来源。
4. 候选检索分两层：
   - 粗筛：pHash/dHash、颜色直方图、尺寸比例和前景面积先筛出 Top-30 到 Top-50。
   - 精排：对粗筛结果计算 OpenCV `matchTemplate()`、边缘相似度、mask IoU、颜色相似度，必要时增加 ORB/AKAZE 特征匹配。
5. 评分输出至少包含 `canonicalId`、`showdownId`、`displayName`、`confidence`、`visualVariant`、`isShiny`、`templatePath`、`source` 和 `roiId`。
6. 用同槽位的属性图标、性别图标做一致性加分或扣分，但不能只靠属性反推物种。
7. 如果 Top-1 和 Top-2 分差不足，或整体置信度低于阈值，确认页必须显示裁剪图、Top-3 候选和搜索入口。
8. 没有授权可靠的头像参考集时，MVP 必须退化为“裁出头像 + 用户搜索选择”，不能假装已经识别出名字。

可用参考源初查：

- 这类队伍小头像通常可称为 `box sprite`、`menu icon` 或 `pokemon icon`。Pokemon Champions 队伍预览里的对方头像尺寸和 Gen 8 风格 68x56 box sprite 很接近。
- `PokeAPI/sprites` 有 `sprites/pokemon/versions/generation-viii/icons/{pokemon_id}.png`，样例文件为 68x56，可通过 `PokeAPI/pokeapi` 的 `pokemon.csv` 和 `pokemon_forms.csv` 把形态映射到编号。当前检查到该目录有 1230 个 png，覆盖普通形态、官方 Mega、部分特殊形态和性别差异。
- `PokeAPI/sprites` 还提供 `sprites/pokemon/other/home/shiny/{pokemon_id}.png` 和 `sprites/pokemon/shiny/{pokemon_id}.png`。这些可作为闪光形态候选，但 Home artwork 风格和队伍小头像不同，第一版应把它们当成兜底模板或确认页参考，不应和游戏内裁剪模板同权。
- `msikma/pokesprite` 有 `pokemon-gen8/regular/{slug}.png` 和 `data/pokemon.json`，也是 68x56 box sprite，文件名更接近英文物种 slug，适合做名称到图片路径的人工可读索引。
- 本仓库当前的 `src/data/localization/zh-Hans.json` 有 324 个 `species`，其中 76 个 Mega 形态；部分是 Pokemon Champions / Pokemon Legends: Z-A 新增 Mega，例如 `Dragonite-Mega`、`Chandelure-Mega`、`Raichu-Mega-X`。这些应优先查 52poke 的 Pokemon Champions sprite 或官方/百科资料，而不是当作本地自定义形态。
- `42arch/pokemon-dataset-zh` 的上游 README 提到 `data/images` 包含 official、dream、home 图片，但这些更像立绘或 Home 图片，不是队伍预览里的 68x56 小头像，只适合做详情展示或辅助搜索，不适合作为第一优先的头像匹配模板。

参考源使用建议：

1. 第一版对 Mega 形态优先用 52poke 上的 `Champions_...._Sprite.png`，对普通形态用 PokeAPI Gen 8 icons 或 PokéSprite Gen 8 regular icons 建官方/通用形态基线库。
2. 闪光形态先使用 PokeAPI shiny 资源进入 `iconVariants`，候选中保留 `visualVariant` 和 `isShiny`。如果游戏内闪光小头像和公开图差异明显，应优先收集用户确认截图模板。
3. 为公开源缺失形态建立 `USER_LABELED_SCREENSHOT` 模板来源：从用户确认过的队伍预览截图裁头像，用户选择物种后保存为本机模板。
4. 参考图和从参考图生成的特征向量都要记录来源、下载日期、原始 URL 或截图文件 ID。公开发布前必须重新确认版权和分发策略。

注意事项：

- 不直接打包 Pokemon Champions 游戏资源。
- 如果需要图标参考集，必须确认素材来源和授权。
- 公开发布前要检查宝可梦相关素材的版权和商标风险。

### 5.7 候选融合

同一个槽位可能同时来自 OCR、图标匹配、保存队伍和本局候选池。

融合规则建议：

1. 用户手动锁定结果优先级最高。
2. 我方配置导入时，两张队伍配置图互相校验；名称、属性、招式类型一致时提高置信度。
3. 队伍预览中，我方识别结果优先用于匹配已有 `SavedOwnTeam`，不直接覆盖保存队伍。
4. 队伍预览中，对方槽位没有名称 OCR，头像匹配是主信号；属性和性别只能作为辅助信号。
5. 同一物种的普通和闪光模板应合并到同一个物种候选下展示，确认页显示 `普通/闪光` 外观标记，避免把闪光外观误当成不同宝可梦。
6. 对战中，我方当前宝可梦必须优先从已选择的 `SavedOwnTeam` 中匹配。
7. 对战中，对方当前宝可梦必须优先从本局已确认的 `opponentTeam` 中匹配。
8. OCR 与图标匹配冲突时保留多个候选，让用户确认。
9. 低置信度结果不能直接进入伤害计算，除非用户明确选择一个候选。

## 6. 用户确认机制

识别模块不能假装永远正确。所有低置信度结果都要给用户一个快速修正入口。

### 6.1 置信度分级

建议阈值：

- `>= 0.90`：自动填入，但仍允许用户点开修改。
- `0.60 - 0.89`：展示候选，建议用户确认。
- `< 0.60`：标记为未确认，必须用户手动选择。

队伍导入时，即使所有槽位都高于 0.90，也应让用户至少查看一次确认页，因为保存队伍会在后续多局复用。

### 6.2 我方队伍导入确认

确认页展示 6 个完整配置：

```text
队伍: TD6REUP...

1. 风妖精 / 恶作剧之心 / 妖精之羽
   月亮之力 / 顺风 / 守住 / 再来一次
   加点: 待导入或 1-0-0-32-0-32
   能力值: 待导入或 155-70-100-140-95-168

2. 巨金怪 / 恒净之躯 / 巨金怪进化石
   铁头 / 冰冻拳 / 跺脚 / 守住
   ...

[确认保存] [修改错误项] [重新截图]
```

用户确认后写入 `SavedOwnTeam`。后续对战中默认使用这个保存队伍，而不是要求用户重新填写我方招式、道具和能力值。

### 6.3 队伍预览确认

队伍预览确认页展示本局双方队伍：

```text
我方队伍: 已匹配 保存队伍 A
1. 风妖精    匹配
2. 巨金怪    匹配
...

对方队伍
1. 暴鲤龙    94% 图标   [修改]
2. 烈咬陆鲨  91% 图标   [修改]
3. 未确认              [选择]
...

[确认本局队伍] [切换我方保存队伍] [重新识别]
```

确认页显示的对方名字是 App 根据头像候选映射出来的实体名，不是从游戏界面 OCR 得到的文本。用户确认后，对方 6 个槽位写入当前 `BattleSession`。

### 6.4 对战中快速确认

对战中面板只展示最必要信息：

```text
当前识别
我方: 风妖精      来自保存队伍 A
对方: 暴鲤龙 ?    [换成对方队伍候选]
```

如果识别结果在已确认队伍候选池内，用户应该能一键改成同队伍中的其他宝可梦。用户手动锁定计算对象后，后续识别只能提示“场上宝可梦变化”，不能静默覆盖当前伤害计算选择。

## 7. 与队伍和伤害计算模块的接口

图像识别模块不直接调用伤害公式，而是更新队伍库和对局状态。

我方队伍导入流程写入：

```text
SavedOwnTeam
  pokemon: KnownPokemonBuild[6]
```

对战识别流程写入：

```text
BattleSession
  selectedOwnTeamId
  ownTeam: BattlePokemonSlot[6]
  opponentTeam: BattlePokemonSlot[6]
  activeOwnSlots
  activeOpponentSlots
  calculationSelection
  recognitionHistory
```

伤害计算模块读取：

- 我方攻击方或防守方：来自 `SavedOwnTeam.pokemon` 的完整 `KnownPokemonBuild`。
- 对方身份：来自队伍预览或对战场地识别确认后的 `OpponentPokemonIdentity`。
- 对方防守或攻击配置：来自模板、合法招式池和用户选择。
- 当前场上对象：来自 `activeOwnSlots` 和 `activeOpponentSlots` 初始化，但用户可以改选。

未确认的 OCR 文本不能直接传入伤害计算器。所有宝可梦、招式、特性、道具都必须先映射成 `EntityRef`。

## 8. MVP 验收标准

第一版识别模块完成以下能力即可：

1. 用户能授权截取单张屏幕。
2. App 能引导用户截取我方队伍配置-招式道具页。
3. App 能从招式道具页识别 6 个槽位的宝可梦、特性、道具和四个招式候选。
4. App 能引导用户截取我方队伍配置-能力值页，并合并到同一个队伍草稿。
5. 用户能手动修正并保存 `SavedOwnTeam`。
6. 用户能在对战前或对战中切换保存队伍。
7. App 能用队伍预览页中的对方头像识别或手动选择 6 只宝可梦候选，不能依赖对方名称 OCR。
8. 用户能确认本局对方队伍。
9. App 能在对战场地页中从已确认队伍池里识别或选择当前场上宝可梦。
10. 识别结果能初始化伤害计算页面，并且不会覆盖用户已经手动锁定的计算对象。

## 9. 后续增强

- 支持更多语言。
- 支持更多分辨率和比例。
- 识别天气、场地、墙、异常状态图标。
- 识别招式选择画面，用于校验我方当前可用招式或快捷选择。
- 识别 HP 条百分比并写入当前 HP。
- 保存本地截图样本和用户标注，用于后续改进。
- 增加离线模型，提高头像识别准确率。

## 10. 风险

### 10.1 能力值页布局变化

解决策略：优先维护最终能力值 ROI；右侧加点和性格颜色不进入第一版 OCR 主路径。第一版代码应允许招式道具页先导入，队伍状态标记为 `INCOMPLETE`。

### 10.2 分辨率差异

解决策略：ROI 配置独立成表。队伍预览使用居中 16:9 游戏画布映射，并同时绑定平板基线、真实手机截图和识别回归结果；其他场景在没有稳定画布或锚点前仍按明确支持范围管理。

### 10.3 OCR 语言差异

解决策略：第一版只支持一种语言；名称词典按语言拆分；界面中明确当前识别语言。

### 10.4 动画和特效遮挡

解决策略：对战场地页只在用户主动点击时识别，并提示用户尽量在稳定画面截图；低置信度时进入手动确认。

### 10.5 素材授权

解决策略：识别模型和参考图来源必须可追溯；公开发布前不直接打包未授权游戏资源。

### 10.6 队伍预览对方无名称文本

解决策略：对方队伍预览以头像 ROI 匹配为主，属性/性别图标只做辅助校验。MVP 如果没有可靠头像参考集，就在确认页展示裁剪头像和搜索框，让用户快速手动选择，确认后的结果写入本局 `opponentTeam` 并用于后续场上识别。

### 10.7 闪光形态和公开素材风格差异

风险：闪光形态在对战中常见，但公开 shiny 图片可能是 Home artwork 或默认 sprite，不一定等同于 Pokemon Champions 队伍预览小头像。直接把普通模板和 shiny artwork 同权匹配，可能让颜色接近但物种错误的候选得分偏高。

解决策略：候选结果必须保留 `visualVariant`、`isShiny` 和模板来源。第一版可以把 PokeAPI shiny 资源作为兜底候选和确认页参考；用户确认过的游戏内闪光裁剪模板应写入本机模板库，并在后续匹配中优先于公开 artwork。回归报告要单独统计普通和闪光样本的 Top-1 / Top-3 命中率。

## 11. 当前建议

图像识别第一阶段应从“我方队伍导入”开始，而不是从对战场地全自动识别开始。

推荐实现顺序：

1. 固定 ROI 解析我方队伍配置-招式道具页。
2. 补充我方队伍配置-能力值页 ROI，只读取最终能力值。
3. 完成 `SavedOwnTeam` 保存、确认和切换。
4. 识别对战队伍预览页，用 OpenCV 多信号头像匹配或人工选择建立本局对方候选池；队伍预览样本需要先建立人工答案，用于验证 ROI、普通/闪光模板和 Top-3 候选。
5. 识别对战场地页，从保存队伍和本局对方队伍中匹配当前场上宝可梦。

这个顺序能最快得到可用闭环：我方配置准确保存，对方身份由队伍预览确认，战斗中只需要识别当前是谁，然后交给伤害计算模块计算。
