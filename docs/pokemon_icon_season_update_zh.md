# 宝可梦图标资源与赛季更新流程

本文说明队伍预览头像识别当前使用的图标资源链路，以及以后赛季更新、新增宝可梦或新增形态时需要做什么。

## 当前资源链路

当前项目不是把网上下载的一堆 PNG 当作唯一入口直接散着使用，而是分成几层：

1. `src/data/pokemon-icons/source.manifest.json`
   - 记录上游来源优先级、远程 API、授权备注和本地模板目录约定。
   - 当前优先级是 52poke Pokemon Champions 普通头像、Bulbagarden Pokemon Champions shiny menu sprite，然后才是 PokeAPI Gen 8 icon、Gen 9 sprite、Home artwork 等兜底来源。

2. `src/data/pokemon-icons/assets/`
   - 这是 `node tools/pokemon-icons/sync-pokemon-icons.mjs --download` 下载下来的本地 PNG 缓存。
   - 该目录被 `.gitignore` 忽略，不是项目提交物。换机器或清理后需要重新下载。
   - 不要手动把这个目录整包纳入 git；公开发布前还需要单独确认素材授权和分发策略。

3. `src/data/pokemon-icons/catalog.pokeapi-composite.json`
   - 由 `tools/pokemon-icons/sync-pokemon-icons.mjs` 生成。
   - 这是项目里稳定提交的图标索引，记录每个 `canonicalId` / `showdownId` 对应的普通图标、闪光图标、来源、远程地址和本地 `localPath`。
   - 识别代码不应手写一堆图片路径，而应通过这个 catalog 或它生成出的 `references/` 使用图标。

4. `src/data/pokemon-icons/coverage.pokeapi-composite.json`
   - 同步脚本生成的覆盖率报告。
   - 重点看 `unmapped`、`userTemplateRequired`、`shinyMissing`、`fallbackUsed`。这些字段不为空时，说明新增宝可梦或形态还没完全进入可靠模板库。

5. `references/`
   - 由 `npm run recognition:vision:build-dataset -- --clear-output` 从 catalog 指向的本地图标复制生成。
   - 文件名带有 `showdownId`、中文名、`canonicalId`、PokeAPI ID、来源和普通/闪光标记，方便 `pokemon-vision-pipeline.py` 保留稳定物种 ID。
   - 当前优化后的主评估/预测管线默认从 `references/` 构建 catalog 模板，不直接扫描 `src/data/pokemon-icons/assets/`。

6. `src/data/recognition/template-cache/`
   - 运行 `pokemon-vision-pipeline.py evaluate/predict` 时读取或生成的特征缓存。
   - 这是正式提交的识别加速资源，保存 catalog 模板和真实截图 labeled ROI 模板的预处理结果。
   - `references/`、增强参数、ROI 配置、标注样本或模板版本变化后会自动生成新的缓存文件；确认评估通过后应把新的缓存文件一并提交。

补充说明：当前正式优化后的 dataset 评估/预测路径以 `references/` 和模板缓存为主；`tools/recognition/run-icon-match.mjs` 仅作为早期 icon-match 辅助入口保留。

## 赛季新增宝可梦时的处理步骤

### 1. 先补项目 ID 和中文名

新增物种或形态必须先进入本地物种数据，否则图标同步脚本不会把它纳入 catalog。

需要检查：

- `src/data/localization/zh-Hans.json`
  - 是否有 `entityType: "species"`。
  - `canonicalId` 是否稳定，例如 `species.raichumegax`。
  - `showdownId` 是否和计算器、识别标注、手工输入保持一致。
  - 中文名、英文名和常见别名是否足够覆盖人工标注写法。

如果新增宝可梦还涉及伤害计算，必须同步检查伤害计算数据、招式、特性、属性和种族值；不要只加图标，否则识别出了物种也不能可靠计算。

### 2. 补 Showdown 到上游素材 ID 的映射

运行同步前先检查：

- `src/data/pokemon-icons/showdown-pokeapi-overrides.json`

当本地 `showdownId` 和 PokeAPI identifier 不一致，或者新增形态是 Pokemon Champions 特有命名时，需要在这里加映射。

如果 52poke 或 Bulbagarden 的文件名后缀不能由脚本自动推断，还需要更新：

- `tools/pokemon-icons/sync-pokemon-icons.mjs`
  - `WIKI52POKE_CHAMPIONS_FORM_SUFFIX_OVERRIDES`
  - `BULBAGARDEN_FORM_SUFFIX_OVERRIDES`

### 3. 重新生成 catalog 和覆盖率

需要联网：

```sh
node tools/pokemon-icons/sync-pokemon-icons.mjs
```

检查输出和 `coverage.pokeapi-composite.json`：

- `unmapped` 应为 0，除非明确接受某个新形态暂时无法映射。
- `userTemplateRequired` 应为 0，除非确实没有可用公开图标，需要收集本地确认模板。
- `shinyMissing` 应为 0，或明确记录暂时没有闪光候选。
- `fallbackUsed` 如果出现，说明该物种没有拿到最接近队伍预览风格的 Champions/menu sprite，只能用较弱的兜底图，后续要重点验证。

### 4. 下载或刷新本地图标缓存

```sh
node tools/pokemon-icons/sync-pokemon-icons.mjs --download
```

这一步会把 catalog 指向的 PNG 下载到 `src/data/pokemon-icons/assets/`。该目录被 git 忽略，所以新机器、CI 或清理缓存后都需要重新执行。

如果下载失败，先不要手写 catalog 的 `localPath`。应修正映射、上游文件名规则或远程来源，再重新生成 catalog。

### 5. 重建 `references/` 和训练数据目录

```sh
npm run recognition:vision:build-dataset -- --clear-output
```

这一步会：

- 从 catalog 指向的本地图标复制出 `references/`。
- 更新 `references/_manifest.json`。
- 重建 `dataset/real_train/`、`dataset/labels.csv` 和 dataset manifest。

如果只是新增图标，没有新增人工截图标注，`references/` 会变，`dataset/labels.csv` 不一定变。

### 6. 补真实截图样本

新增赛季最容易出问题的不是 catalog 能否找到图，而是游戏内队伍预览头像和公开素材是否足够相似。

建议至少补：

- 新增宝可梦普通形态的队伍预览截图。
- 新增宝可梦闪光形态的队伍预览截图，如果赛季允许或常见。
- 容易混淆的相近形态截图，例如 Mega X/Y、地区形态、性别差异、姿态差异。

标注应进入现有样本来源，例如 `docs/pic/team_preview/preview.md` 或新的结构化 expected JSON。不要只把截图放进目录而不补答案。

### 7. 跑识别验证

主流程：

```sh
npm run recognition:vision:evaluate -- --timing-output .tmp/pokemon-vision-season-update-timing.json
```

如果新增了 ROI 或需要肉眼检查裁切质量：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate ^
  --output .tmp/pokemon-vision-season-update-diagnostics ^
  --full-scoring
```

如需复核 SafeZone ROI 和 catalog 兼容性，使用现行 `pokemon-vision` 管线重新评估。

如果只是更新 catalog，没有新增真实截图样本，评估结果可能不会覆盖新宝可梦。此时必须补一批包含新宝可梦的队伍预览截图，否则只能说明 catalog 构建成功，不能说明识别效果可靠。

### 8. 缓存处理

通常不需要手动删除 `src/data/recognition/template-cache/`。主 pipeline 的缓存 key 会包含 `references/` 文件内容指纹、增强参数、标注样本、ROI 配置和模板版本，新增或替换参考图后会自动生成新的缓存。

如果怀疑缓存异常，可以临时使用：

```sh
python tools/recognition/pokemon-vision-pipeline.py evaluate --refresh-template-cache
```

刷新后检查识别结果和耗时，如果新缓存有效，应提交 `src/data/recognition/template-cache/` 下的新 pkl。不要把 `.tmp` 作为缓存资源提交。

## 提交范围

赛季更新后通常需要提交：

- `src/data/localization/zh-Hans.json`
- `src/data/pokemon-icons/showdown-pokeapi-overrides.json`
- `src/data/pokemon-icons/catalog.pokeapi-composite.json`
- `src/data/pokemon-icons/coverage.pokeapi-composite.json`
- `references/` 和 `references/_manifest.json`
- 新增或更新的标注样本、`dataset/labels.csv`、dataset manifest
- `src/data/recognition/template-cache/` 下重新生成且验证通过的模板缓存
- 必要时更新 `tools/pokemon-icons/sync-pokemon-icons.mjs`
- 必要时更新识别或赛季说明文档

通常不要提交：

- `src/data/pokemon-icons/assets/`
- `.tmp/`
- `tools/recognition/__pycache__/`
- 未确认属于队伍预览界面的截图

## 发布前注意事项

图标素材涉及宝可梦相关版权和商标。当前项目可以把下载缓存作为本地开发资源，但公开发布、打包分发或把 PNG 放进安装包前，需要重新确认素材来源、授权、非官方声明和分发策略。

如果授权不明确，产品层可以保留 catalog 生成能力和用户本地下载步骤，而不是直接分发上游 PNG。
