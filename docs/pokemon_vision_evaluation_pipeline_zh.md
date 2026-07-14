# 宝可梦图像识别评估管线

本项目新增 `tools/recognition/pokemon-vision-pipeline.py`，用于把队伍预览头像识别从“单批样本调试”改成可评估的数据管线。脚本不针对单张失败截图写特判，也不要求按某一批图片固定阈值。

## 目录约定

默认输入：

```text
references/
dataset/
  real_train/
  labels.csv
  real_test/
```

`references/` 放所有宝可梦参照图，文件名需要包含 `pokemon_id` 或名字，例如：

```text
references/006_charizard.png
references/raichu.png
references/烈咬陆鲨.png
```

`dataset/labels.csv` 必须包含字段：

```csv
image,side,pokemon
1.jpg,own.slot0,incineroar
1.jpg,opponent.slot3,garchomp
```

`side` 必须能定位到单个头像 ROI。推荐格式是 `own.slot0..5` 或 `opponent.slot0..5`，也支持完整 ROI id，例如 `team_preview.opponent.slot0.pokemon_icon`。

队伍预览头像 ROI 的正式来源是 `src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json`。旧的主 ROI 配置不再维护 `team_preview.*.pokemon_icon`，`src/data/recognition/roi.pokemon-champions.zh-Hans.v1.json` 只保留场景标记、文本、属性图标等非头像区域。

识别模板缓存的正式目录是 `src/data/recognition/template-cache/`。该目录保存 catalog 模板和 labeled ROI 模板的预处理特征，用于让日常 `evaluate/predict` 直接复用模板侧数据，而不是每次重新裁剪、增强和提取特征。

## 从项目素材构建数据集

当前项目已经可以直接从现有素材生成标准目录：

```sh
npm run recognition:vision:build-dataset -- --clear-output
```

生成来源：

- `references/`：来自 `src/data/pokemon-icons/catalog.pokeapi-composite.json` 指向的本地图标素材，包括普通 Champions sprite 和 shiny menu sprite。
- `dataset/real_train/`：来自 `docs/pic/team_preview/preview.md` 中有人工答案的截图；同时会从旧 `docs/pic/2` 报告补充非重复标注截图。
- `dataset/labels.csv`：由人工答案展开成 `image,side,pokemon`。
- `dataset/real_test/`：默认保持为空。只有确认图片确实是队伍预览界面后，才用 `--real-test-source` 显式导入；不要把非队伍预览截图放进最终测试集。
- `dataset/manifest.json`、`dataset/real_train_manifest.csv`、`dataset/real_test_manifest.csv`：记录每个生成文件的来源。

`preview.md` 中少量本地写法会在构建时归一化到稳定 ShowdownId，例如“炽焰咆啸虎”到 `Incineroar`、“大纽拉”到 `Sneasler`、“仆刀将军”到 `Kingambit`。这是答案文本标准化，不是对识别结果写特判。

## 评估命令

```sh
npm run recognition:vision:evaluate
```

等价于：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate ^
  --references references ^
  --images-dir dataset/real_train ^
  --labels dataset/labels.csv ^
  --output .tmp/pokemon-vision-eval
```

`evaluate` 的项目默认配置是 SafeZone ROI、真实训练 ROI 模板、模板缓存、`augment-count=1`、`same-side` labeled 模板范围、`labeled-template-bonus=0.04`，并对 opponent 侧使用 `phash=0,edge=0.4,color=0.2,template=0.4`。默认同时启用快速检测，只计算 `combined` TopK，并跳过耗时的 ROI contact sheet、overlay、质量报告和失败样本调试图。

默认输出：

- `src/data/recognition/template-cache/`：默认读取这里已经提交的模板缓存；缓存不存在或使用 `--refresh-template-cache` 时，会在这里生成新的 pkl 缓存文件。
- `.tmp/pokemon-vision-eval/metrics.json`：Top1、Top3、Top5 accuracy。
- `.tmp/pokemon-vision-eval/side_metrics.json` / `side_metrics.csv`：按 `all`、`own`、`opponent` 和每个槽位拆分的 Top1、Top3、Top5 accuracy。
- `.tmp/pokemon-vision-eval/method_metrics.json` / `method_metrics.csv`：默认只记录快速检测使用的 `combined`。
- `.tmp/pokemon-vision-eval/predictions.csv`：每条样本的 Top5 候选。
- `.tmp/pokemon-vision-eval/failed_cases.csv`：Top1 失败样本，包含真实答案、Top5 和各项分数。

需要重新检查 ROI 裁切质量或比较各单项评分时，用 `--full-scoring` 打开诊断输出：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate ^
  --output .tmp/pokemon-vision-eval-diagnostics ^
  --full-scoring
```

诊断模式会额外写出：

- `.tmp/pokemon-vision-eval-diagnostics/debug_roi/`：每条标注裁出的 ROI。
- `.tmp/pokemon-vision-eval-diagnostics/debug_roi_contact_sheet.jpg`：ROI 裁剪总览图。
- `.tmp/pokemon-vision-eval-diagnostics/debug_roi_overlay/`：在原始截图上画出的 ROI 矩形。
- `.tmp/pokemon-vision-eval-diagnostics/roi_quality.json` / `roi_quality.csv`：每个 ROI 的坐标、前景占比、前景 bbox 比例和贴边情况。
- `.tmp/pokemon-vision-eval-diagnostics/method_metrics.json` / `method_metrics.csv`：`combined`、`phash`、`edge`、`color`、`template` 的 Top1/Top3/Top5 对比。

## 最终测试预测

```sh
npm run recognition:vision:predict
```

默认会对 `dataset/real_test/` 中每张图裁剪 12 个队伍预览头像槽位，并输出：

```text
.tmp/pokemon-vision-eval/predictions.csv
.tmp/pokemon-vision-eval/debug_roi/
```

也可以只预测指定槽位：

```sh
python tools/recognition/pokemon-vision-pipeline.py predict --side opponent.slot0 --side opponent.slot1
```

当前 `dataset/real_test/` 不应使用，直到重新提供一批确认属于队伍预览界面的测试截图。现阶段训练和调参只使用 `dataset/real_train + dataset/labels.csv`。

## 权重搜索

为了避免靠单张失败图手调权重，可以在 `real_train + labels.csv` 上做通用网格搜索：

```sh
npm run recognition:vision:tune
```

常用参数：

```sh
python tools/recognition/pokemon-vision-pipeline.py tune-weights ^
  --references references ^
  --images-dir dataset/real_train ^
  --labels dataset/labels.csv ^
  --output .tmp/pokemon-vision-tune ^
  --grid-step 0.1
```

输出：

- `best_weights.json`：当前训练标注集上 Top1/Top3/Top5 排序最好的权重。
- `weight_search.csv`：所有权重组合的 accuracy 排名。
- `weight_search.json`：完整搜索结果。
- `debug_roi_contact_sheet.jpg`：本次搜索使用的 ROI 裁剪总览。

注意：`tune-weights` 只能用于训练集调参。真正判断是否泛化，仍要用未参与调权的 `dataset/real_test/` 或单独验证集。

## 按图片分组交叉验证

比单纯训练集调权更可靠的做法，是按截图分组做交叉验证：

```sh
npm run recognition:vision:cross-validate
```

等价示例：

```sh
python tools/recognition/pokemon-vision-pipeline.py cross-validate ^
  --references references ^
  --images-dir dataset/real_train ^
  --labels dataset/labels.csv ^
  --output .tmp/pokemon-vision-cv ^
  --folds 5 ^
  --grid-step 0.1
```

这个命令会把同一张截图的所有槽位放进同一折，避免同一截图的背景、压缩和 UI 状态同时出现在训练与验证中。每一折只用训练折搜索权重，再在没见过的截图折上评估。

输出：

- `cross_validation.json`：每折训练最佳权重和验证结果。
- `cross_validation.csv`：每折验证 Top1/Top3/Top5 与对应权重。
- `debug_roi_contact_sheet.jpg`：本次参与交叉验证的 ROI 裁剪总览。

如果 `cross-validate` 明显低于 `evaluate`，通常说明当前 reference、增强策略或综合权重对新截图泛化不足，不应只看训练集指标。

## ROI 资源与诊断

`TEAM_PREVIEW` 头像裁切只使用 `src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json`。该文件记录基准截图尺寸和 12 个头像安全区的完整图像像素坐标，运行时按当前截图分辨率等比缩放。

旧的 `roi-config` 头像裁切和 `calibrate-roi` 队伍预览校准路径已移除。对 `TEAM_PREVIEW` 执行 `calibrate-roi` 会直接退出，并提示头像 ROI 由 SafeZone 资源管理。以后如果确认需要调整队伍预览头像 ROI，应直接更新 `team-preview.safe-zone-roi.zh-Hans.v1.json`，再用 `evaluate --full-scoring` 生成 contact sheet、overlay 和 ROI 质量报告做整批复核。

## 预处理与评分

每个 ROI 和 reference 都会经过同一套 OpenCV 预处理：

- ROI 裁剪后写入 `debug_roi/`。
- 前景 mask 与背景分离。
- resize 到内部固定尺寸。
- 颜色归一化。
- 灰度图与边缘图生成。
- HSV 颜色直方图生成。
- 感知哈希生成。

每个候选会输出四类相似度：

- `phash`：感知哈希相似度。
- `edge`：边缘图 IoU。
- `color`：颜色直方图相似度。
- `template`：模板匹配相似度。

综合分数默认权重：

```text
phash=0.22, edge=0.24, color=0.24, template=0.30
```

opponent 侧默认覆盖为：

```text
phash=0, edge=0.4, color=0.2, template=0.4
```

默认快速检测只计算 `combined`。使用 `--full-scoring` 时会写出 `method_metrics.json` 和 `method_metrics.csv`，用于比较不同相似度方法：

```text
combined
phash
edge
color
template
```

也可以用 `--weights` 额外实验新的综合权重：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate --weights phash=1,edge=0,color=0,template=0
python tools/recognition/pokemon-vision-pipeline.py evaluate --weights phash=0,edge=1,color=0,template=0
python tools/recognition/pokemon-vision-pipeline.py evaluate --weights phash=0,edge=0,color=1,template=0
python tools/recognition/pokemon-vision-pipeline.py evaluate --weights phash=0,edge=0,color=0,template=1
```

如果 own/opponent 两侧截图风格不同，可以用 `--side-weights` 对某一侧或某个槽位覆盖综合权重。匹配规则是具体槽位优先，其次侧别，最后回退到 `--weights`：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate ^
  --side-weights opponent:phash=0,edge=0.4,color=0.2,template=0.4
```

这仍然是训练集级别的通用参数，不允许按单张失败图片写 `if` 特判。

真实截图 ROI 模板也可以设置一个统一来源先验，只影响 `combined` 排名，不改变 `phash`、`edge`、`color`、`template` 单项评估：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate --labeled-template-bonus 0.04
```

该参数只看模板来源是否为 labeled ROI，不看具体图片名或宝可梦名。

## 真实训练 ROI 模板

纯 `references/` 图标和真实截图之间可能有渲染、压缩、背景、缩放差异。当前实测中，修正 shiny 素材后，纯 catalog reference 的基线仍只有：

```text
Top1=0.1510, Top3=0.1927, Top5=0.2031
```

加入新的前景 mask 后，纯 catalog reference 提升到：

```text
Top1=0.1667, Top3=0.2448, Top5=0.2656
```

因此管线默认从 `dataset/real_train + labels.csv` 自动裁出已标注 ROI，作为 screenshot 风格模板参与匹配：

```sh
npm run recognition:vision:evaluate
```

显式写全关键参数时等价于：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate ^
  --references references ^
  --images-dir dataset/real_train ^
  --labels dataset/labels.csv ^
  --crop-mode team-preview-safe-zone ^
  --use-labeled-roi-templates ^
  --labeled-template-scope same-side ^
  --augment-count 1 ^
  --side-weights opponent:phash=0,edge=0.4,color=0.2,template=0.4 ^
  --labeled-template-bonus 0.04
```

评估时默认会排除同一张截图裁出来的 labeled ROI 模板，避免“自己匹配自己”的泄漏。当前实测：

```text
Top1=0.6146, Top3=0.6615, Top5=0.6615
```

按侧别拆分后，当前瓶颈集中在 opponent 槽位：

```text
same-side labeled ROI, no augment
own:      Top1=0.7708, Top3=0.7812, Top5=0.7812
opponent: Top1=0.4583, Top3=0.5312, Top5=0.5417
```

对完整 `real_train` 启用 `team-preview-safe-zone` 固定裁切、`augment-count=1`、真实标注 ROI 模板、opponent 侧权重 `phash=0,edge=0.4,color=0.2,template=0.4` 后，当前 real_train 内评估为：

```text
all:      Top1=1.0000, Top3=1.0000, Top5=1.0000
own:      Top1=1.0000, Top3=1.0000, Top5=1.0000
opponent: Top1=1.0000, Top3=1.0000, Top5=1.0000
```

当前修正点是通用图像管线，而不是单张失败图特判：固定安全区裁切；BGR+HSV 背景差异前景 mask；基于边缘密度判断 GrabCut 扩张是否属于真实主体；移除贴边 UI 高光；颜色归一化只增强亮度通道并保留色相；labeled ROI 来源先验只对同槽位模板加分，避免跨槽位模板压过更强的 catalog reference。

对 `dataset/real_test/` 输出候选时，可以使用全部训练 ROI 模板：

```sh
npm run recognition:vision:predict:labeled
```

输出仍在 `.tmp/pokemon-vision-eval/`，建议另行指定输出目录：

```sh
python tools/recognition/pokemon-vision-pipeline.py predict ^
  --references references ^
  --images-dir dataset/real_test ^
  --output .tmp/pokemon-vision-real-test-predict ^
  --crop-mode team-preview-safe-zone ^
  --use-labeled-roi-templates ^
  --labeled-template-labels dataset/labels.csv ^
  --labeled-template-images-dir dataset/real_train ^
  --labeled-template-scope same-side ^
  --augment-count 1 ^
  --side-weights opponent:phash=0,edge=0.4,color=0.2,template=0.4 ^
  --labeled-template-bonus 0.04
```

## 数据增强

评估和预测默认会基于 `references/` 自动生成增强模板，并保留原始 `pokemon_id`：

- 随机缩放。
- 随机偏移。
- 亮度变化。
- 对比度变化。
- 模糊。
- JPEG 压缩。
- 背景混合。

可调参数：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate --augment-count 12 --seed 12345
```

只生成增强模板：

```sh
npm run recognition:vision:augment -- --references references --output .tmp/augmented-references
```

## 使用顺序

1. 日常速度评估直接跑 `npm run recognition:vision:evaluate`，必要时加 `--timing-output` 记录步骤耗时。
2. 需要复核 ROI 时再跑 `evaluate --full-scoring`，打开 `debug_roi_contact_sheet.jpg`、`debug_roi_overlay/` 和 `debug_roi/`，人工确认每个槽位裁剪正确。
3. 查看 `roi_quality.csv`，重点关注前景 bbox 是否贴边、前景占比是否明显异常。这个文件只提供通用诊断指标，不应拿单张失败图写特判。
4. 如果 ROI 裁剪整体偏移，直接调整 `src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json`，再重复整批诊断评估。
5. ROI 正确后，先跑 `cross-validate`，看按截图分组的泛化表现。
6. 需要调权时再看 `method_metrics.csv` 与 `weight_search.csv`，比较 `phash`、`edge`、`color`、`template` 单项和综合评分。
7. 观察 `failed_cases.csv`，决定是补充 reference、调整通用预处理，还是改综合权重。
8. 禁止针对单张失败截图写 `if image == ...` 之类的特判。
