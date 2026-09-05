# Pokémon Champions 游戏版本、资源与赛季更新流程

本文是游戏版本或赛季更新后的项目维护入口，说明需要复核的功能面、队伍预览头像资源链路，以及新增宝可梦或形态时的处理步骤。不要因为某一项测试通过，就默认其他数据、协议或界面仍兼容。

## 每次游戏版本更新的总检查

先记录新的游戏客户端版本、Master Data 版本、更新日期和维护所依据的官方客户端构建，再逐项判断是否需要更新：

| 功能面 | 重点检查 | 相关入口 |
| --- | --- | --- |
| 公开队伍码 | 官方客户端/Unity 参数、登录与查询协议、Master Data 版本、数字 ID 映射、匿名游客身份是否仍可登录 | 本文“公开队伍码协议与数字 ID 映射更新”、`docs/team_code_resolver_contract_zh.md` |
| 宝可梦与计算数据 | 新增物种/形态、招式、特性、道具、属性、种族值、合法招式池以及中文名称 | `src/data/localization/`、`src/data/damage/`、`external/smogon-damage-calc/` |
| 队伍预览识别 | 图标 catalog、普通/闪光模板、真实截图样本、识别缓存和覆盖率 | 本文“赛季新增宝可梦时的处理步骤”、`docs/image_recognition_pipeline_zh.md` |
| 游戏画面布局 | 安全区、双方头像 ROI、分辨率/横竖屏变化以及 HUD 对齐 | `docs/android_team_preview_roi_usage_zh.md`、`docs/battle_state_and_user_adjustment_zh.md` |
| App 发布 | App 版本号、Android `versionCode`、双分支、双变体、签名、ABI、升级保留数据和发布说明 | `docs/android_update_release_guide_zh.md` |

即使更新公告只写“平衡调整”，也至少要实际查询一个仍有效的旧队伍码并打开一张当前版本队伍预览；服务端协议、Master Data 或 UI 资源可能在未单独公告的情况下变化。

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

## 公开队伍码协议与数字 ID 映射更新

### 1. 先区分两个版本号和两层映射

公开队伍码功能有两套不能混用的版本：

- 游戏客户端版本：作为官方请求中的 `clV`。它不等于本项目 `package.json` 的 App 版本，不能跟随 App 发版号机械修改。
- 官方 Master Data 版本：作为请求中的 `mdV`，也决定队伍码数字映射文件的版本后缀。

当前已验证基线是官方客户端参数 `1.1.5`、Master Data `v17`；最终表包含 `361` 个宝可梦形态、`500` 个招式、`200` 个特性、`148` 个道具和 `25` 个性格。数字映射分为：

- `tools/team-code-resolver/data/champions-species-forms.v17.json`
  - 保存官方 `(pokemonNumber, formNumber)` 到项目 `speciesId` 的映射。
  - 对应队伍响应成员中的宝可梦编号 `b0` 和形态编号 `b1`。
  - 由新版官方 `personal` 主数据生成，是形态映射的可审查源文件。
- `tools/team-code-resolver/data/champions-entity-map.v17.json`
  - 是构建时打入 APK 的最终表。
  - 除宝可梦形态外，还包含招式 `bf`、特性 `b5`、道具 `itms[].i` 和性格 `b8` 的数字编号映射。
  - 由前一个形态表、`@pkmn/dex` 和 `src/data/localization/zh-Hans.json` 生成，不应手工只补一个触发报错的编号。

如果新版出现未知编号，正确处理方式是取得完整的新 Master Data、更新项目实体数据并重建整张表；不要猜编号，也不要降低“未知编号拒绝保存”的运行时保护。

### 2. 确认官方协议参数是否变化

在仓库外的临时目录中，用已获授权的测试/游客账号核对新版官方客户端请求。不要把原始抓包、解密响应、令牌、Cookie、会话 ID 或游客 UUID 提交到仓库。

检查 `OfficialTeamCodeProtocol.kt` 中：

- `OFFICIAL_CLIENT_VERSION`
- `OFFICIAL_UNITY_VERSION` 和对应 `OFFICIAL_USER_AGENT`
- `OFFICIAL_ASNV`
- 官方 HTTPS 主机和 `/auth/get-token`、`/auth/login`、`/api/trainingcode/search` 三条路径
- 请求/响应字段、哈希、gzip 与 AES-CBC 封装是否仍和新版一致

只有在新版客户端证据表明确实变化时才修改这些常量。匿名游客身份不需要每次游戏升级都更换；仅当官方拒绝该身份且已确认不是协议或服务故障时，才通过 `-PteamCodeGuestUuid=...` 或 `POKEMON_CHAMPIONS_TEAM_CODE_GUEST_UUID` 更换，并继续禁止持久化临时令牌和 Cookie。

### 3. 取得新 Master Data 并更新项目实体

从新版官方客户端的已授权本地分析结果导出 `personal` 主数据为仓库外 JSON。先更新并验证：

- `src/data/localization/zh-Hans.json` 中新增的物种、形态、招式、特性和道具；
- `external/smogon-damage-calc/` / `@pkmn/dex` 是否已经包含对应 canonical/Showdown 实体和数字编号；
- 本文前半部分所述图标、伤害数据和识别资源。

如果新增实体尚未进入项目本地化或计算器，形态生成器应失败；不要先绕过检查生成一个无法显示或计算的队伍。

### 4. 重建形态表和 APK 实体表

假设新版 `mdV` 是 `18`，先把脚本、输出文件名和版本锁从旧版本调整到 `v18`，再执行：

```powershell
node tools/team-code-resolver/generate-species-map.mjs D:\private\champions-v18-personal.json tools/team-code-resolver/data/champions-species-forms.v18.json
npm.cmd run team-code:assets
```

第一条命令的输入路径只是示例；实际输入必须位于仓库外且不能提交。生成后检查形态键无重复、每个形态都唯一映射到项目实体，且数量等于新版官方 `personal` 表的有效行数。数量不一定继续是 `361`，不得为了沿用旧断言而丢掉新行。

用下面的检索找齐所有旧版本/旧数量锁，逐处核对后再改：

```powershell
rg -n "champions-(species-forms|entity-map)\.v|masterDataVersion|speciesForms|361|OFFICIAL_(CLIENT_VERSION|UNITY_VERSION|USER_AGENT|ASNV)|TEAM_CODE_ENTITY_MAP_ASSET" tools/team-code-resolver android-app docs
```

至少会涉及：

- `tools/team-code-resolver/generate-species-map.mjs`
- `tools/team-code-resolver/entity-map.mjs`
- `tools/team-code-resolver/generate-android-entity-map.mjs`
- `tools/team-code-resolver/protocol.test.mjs`
- `android-app/app/build.gradle.kts`
- `OfficialTeamCodeProtocol.kt`
- `TeamCodeImportTest.kt`
- `tools/android/check-apk-release.ps1`
- `docs/team_code_resolver_contract_zh.md`

旧的 `v17` 文件只有在仍需要复现旧版测试时才保留；正式构建只能同步并校验当前选定的一个映射版本，避免 APK 悄悄继续打包旧表。

### 5. 验证旧码、新码和未知编号保护

至少准备两类仍有效且允许用于测试的公开码：

- 一个更新前已经验证过的旧码，用于回归协议、映射和存储兼容性；
- 一个包含新版新增物种、形态、招式、特性或道具的新码，用于证明新增数字 ID 真正可解析。

测试夹具必须只保留队伍解析所需的脱敏内容，不得包含令牌、Cookie、会话 ID、游客 UUID 或其他账号数据。执行：

```powershell
npm.cmd run team-code:test
powershell -NoProfile -ExecutionPolicy Bypass -File tools/android/run-gradle.ps1 :app:testDebugUnitTest
```

最后在游戏进程关闭、Android 全局代理为 `:0`、ADB reverse 为空的设备上，用安装后的 App 分别解析旧码和新码，核对六只宝可梦的形态、招式、特性、道具、性格和能力点，并至少保存/重新读取一支队伍。发布构建还必须通过 APK 校验，确认新实体表确实被打包。

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
- 新版 `tools/team-code-resolver/data/champions-species-forms.v<mdV>.json`
- 新版 `tools/team-code-resolver/data/champions-entity-map.v<mdV>.json`
- 为新版本调整过的队伍码生成器、协议常量、构建校验和测试夹具
- `docs/team_code_resolver_contract_zh.md` 中的当前客户端/Master Data 基线
- 必要时更新识别或赛季说明文档

通常不要提交：

- `src/data/pokemon-icons/assets/`
- `.tmp/`
- `tools/recognition/__pycache__/`
- 未确认属于队伍预览界面的截图
- 从官方客户端导出的原始/解密 Master Data、抓包、账号身份、令牌、Cookie 和会话材料

## 发布前注意事项

图标素材涉及宝可梦相关版权和商标。当前项目可以把下载缓存作为本地开发资源，但公开发布、打包分发或把 PNG 放进安装包前，需要重新确认素材来源、授权、非官方声明和分发策略。

如果授权不明确，产品层可以保留 catalog 生成能力和用户本地下载步骤，而不是直接分发上游 PNG。
