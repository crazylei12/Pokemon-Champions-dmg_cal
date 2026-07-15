# Android 双方队伍 ROI 识别功能说明

日期：2026-07-13；更新：2026-07-15（Asia/Shanghai）

## 1. 功能概述

Android App 已在悬浮按钮菜单中加入“识别当前屏幕上的双方队伍”。用户在 Pokemon Champions 队伍预览画面主动触发后，App 会通过当前 MediaProjection 会话截取屏幕，按照共享的 `TEAM_PREVIEW` SafeZone ROI 裁出我方和对方各 6 个头像，并为每个槽位输出 Top-3 物种候选。

该功能的边界如下：

- 截图会话必须先由用户授权，只有点击悬浮按钮时才冻结、识别并保存当前帧；
- 识别完全在 Android 本机离线完成，不上传图片；
- 不安装、修改或自动操作游戏；
- 所有候选默认未确认，不会直接进入伤害计算；
- 对低置信度或低分差结果保留 Top-3，交给后续确认流程处理。

## 2. 使用步骤

### 2.1 启动截图会话

1. 打开 App，进入“对局”页。
2. 首次使用时授予悬浮窗权限。
3. 点击“授权屏幕截图并启动悬浮按钮”。
4. 在 Android 的 MediaProjection 授权对话框中选择“单个应用”或“整个屏幕”。选择“单个应用”时，必须选中当前要识别的相册或 Pokémon Champions；单应用会话不会跟随切换到另一个 App，切换相册/游戏时需要停止并重新授权。
5. 等待目标应用恢复且画面稳定、悬浮按钮出现后再识别；刚选中应用时的切换帧可能短暂不完整。

MediaProjection 是一次前台截图会话。应用被强制退出、重新安装或会话被系统终止后，必须由用户重新授权；App 不会在后台静默恢复截图权限。

### 2.2 识别队伍预览

1. 打开 Pokemon Champions 的双方队伍预览画面。
2. 确认画面没有被照片应用工具栏、状态栏或其他控件压缩。
3. 在 ColorOS 通知面板中若看到“应用内容已屏蔽”或“相册内容对方不可见”，先点击“解除屏蔽”。
4. 点击悬浮按钮。
5. 选择“识别当前屏幕上的双方队伍”。
6. 等待识别完成和“下次识别自动覆盖”的提示。

每次成功保存新的双方队伍识别结果时，App 会立即结束上一场对局并清除其全部计算状态；随后只有重新确认本局双方队伍，才会创建新的实战伤害会话。识别失败或系统返回不可见空帧时不会覆盖上一次有效识别结果。

一次操作会同时识别：

- `own.slot0` 至 `own.slot5`；
- `opponent.slot0` 至 `opponent.slot5`。

悬浮菜单弹出前，Service 会冻结最近一张完整屏幕帧。识别期间悬浮按钮自动隐藏，因此按钮和菜单不会进入 12 个头像 ROI。

## 3. 画面要求

当前共享 ROI v2 仍以 3392×2400 横屏画面为基准，但不再把 ROI 相对整张截图分别缩放 X/Y。运行时会先在基准图和当前截图中分别计算“居中的最大 16:9 游戏画布”，再把 12 个 SafeZone 从基准游戏画布映射到当前游戏画布。

这意味着手机和平板截图可以使用不同宽高比，只要 Pokemon Champions 的队伍预览 UI 仍按居中 16:9 安全区域排版：

- 3392×2400 基准图的游戏画布为 3392×1908，顶部偏移 246；
- 2772×1240 手机图的游戏画布约为 2204×1240，左侧偏移 284；
- 画布之外扩展的场馆背景不会再拉伸头像 ROI。

该映射解决的是“同一正确队伍预览界面在不同屏幕比例上的安全区差异”，不会把错误界面、其他游戏页面或被系统控件非等比压缩的画面变成有效输入。

以下情况会明显降低准确率：

- ColorOS 通知显示相册内容已屏蔽，且尚未点击“解除屏蔽”；
- 图片或游戏画面被上下系统栏压缩；
- Photos 的操作控件仍覆盖在图片上；
- 截图不是双方队伍预览场景；
- 游戏更新后头像位置、缩放或界面布局发生变化；
- 画面处于切换动画、黑帧或未稳定加载状态。

### 3.1 ColorOS 屏蔽的判断与处理

若出现“12 个槽位几乎都是相同候选”、画面明明正常但 App 提示不可见，优先按以下顺序处理，不要先修改 ROI：

1. 若使用“单个应用”，确认授权时选中的应用与当前画面一致；相册会话不能读取 Pokémon Champions，游戏会话也不能读取相册。若需要跨应用操作，重新授权另一个单应用会话或改选“整个屏幕”。
2. 展开通知面板；若看到“应用内容已屏蔽”或“相册内容对方不可见”，点击“解除屏蔽”。
3. 返回全屏队伍预览图，确认照片工具栏已经隐藏，再重新识别。
4. 若 App 提示“截图内容被系统屏蔽或画面不可见”，本次空帧已被拒绝，不会覆盖上一次有效结果；解除屏蔽后重试即可。

调试 JSON 中若 12 个 `colorMaskQuality` 全部为 `-10`，可直接判定为共享内容不可见；正常现场帧应至少有一个值高于 `-9.5`。只有在解除屏蔽后仍能取得真实画面但头像裁剪位置明显偏移时，才进入 ROI 坐标排查。

模拟器测试应先准备固定样本：

```powershell
npm.cmd run android:emulator:roi-fixtures
```

若 Photos 控件可见，应先点击图片中心隐藏控件，再触发识别。测试结束后可恢复 Photos 的系统栏策略：

```powershell
adb shell settings delete global policy_control
```

## 4. 临时结果

成功识别后只保留一份当前结果：

```text
files/battle-session/current-team-preview.json
```

该文件位于 App 私有目录，不写入下载目录。下一次双方队伍识别会原位替换它，因此不会按时间戳累积历史文件。升级到该版本时，App 会清理旧版生成的 `files/team-preview-results/` 和由本 App 写入下载媒体库的 `android-team-preview-*.json`。

JSON 的主要结构如下：

```json
{
  "kind": "TeamPreviewRecognitionResult",
  "sceneType": "TEAM_PREVIEW",
  "imageSize": { "width": 3392, "height": 2400 },
  "backend": "android_opencv_4.13.0",
  "templateAsset": "team-preview-templates-v2.bin",
  "roiMapping": {
    "asset": "team-preview.safe-zone-roi.zh-Hans.v2.json",
    "mode": "largest_centered_aspect",
    "gameViewport": { "left": 0, "top": 246, "width": 3392, "height": 1908 }
  },
  "confirmed": false,
  "performance": {},
  "ownTeamCandidates": [],
  "opponentTeamCandidates": []
}
```

每个槽位包含：

- `side`、`slotIndex`、`roiId`；
- `selectedCandidate`；
- `candidates` Top-3；
- `canonicalId`、`showdownId`、`displayName`；
- `confidence`、`score`、`scoreMargin`；
- `source`、`visualVariant`、`isShiny`；
- `confirmed: false` 和 `requiresConfirmation`。

当前确认规则为：首选置信度低于 `0.90`，或首选与第二名分差低于 `0.035` 时，设置 `requiresConfirmation: true`。这只是候选风险标记，不代表 App 已自动确认物种。

## 5. 模板和首次加载机制

Android 不会在手机上运行 PC 端 Python 识别脚本，也不会现场生成图鉴模板。模板已经在开发阶段导出并随 APK 打包：

- `team-preview-templates-v2.bin`：Android 直接读取的二进制特征；
- `team-preview-templates-v2.json`：模板数量、权重、二进制哈希、v2 ROI 哈希和验证结果元数据。

V2 当前包含：

- 650 个图鉴参考模板；
- 366 个真实截图槽位模板；
- 合计 1016 个模板；
- 96×96 完整特征；
- 16×16 灰度粗排特征。

截图 Service 启动后，会在单线程后台完成 OpenCV 初始化、模板读取、ROI 读取和 GrabCut 预热。模板解析结果保存在当前 Service 进程内，因此：

- 同一次截图会话中的后续识别不再重复读取模板；
- 第一次识别通常也能直接使用后台准备好的结果；
- 强制退出 App 或进程被系统回收后，下次启动仍需重新解析一次，但解析发生在后台；
- PC 手动脚本生成的 `.pkl` 缓存不属于 Android 运行时依赖，不需要复制进 APK。

最终冷启动测试中，后台准备耗时 1,402 ms；用户点击识别时模板读取阶段仅为 0.010 ms。

## 6. 速度优化原理

### 6.1 粗排后再精排

每个槽位先对同侧约 818 个模板执行低成本粗排，再只保留 Top-24 物种进入完整 OpenCV 精排。完整精排仍使用灰度相关性、边缘 IoU、HSV 直方图、pHash 和同槽真实模板加权。

最终 15 图回归中，每张图平均完整精排约 1,096 次，而优化前为 9,816 次。

### 6.2 自适应前景分割

识别器先尝试颜色距离前景 mask：

- mask 质量足够时直接走快速路径；
- mask 质量不足时执行 GrabCut；
- 快速路径的首选分差低于 `0.02` 时，再自适应补跑一次 GrabCut 并重新排序。

最终回归只有 5/180 个槽位触发自适应补算，在速度和 Top-3 稳定性之间取得平衡。

### 6.3 截图内存复用

MediaProjection 持续更新一张可复用 Bitmap，不再为每个屏幕帧创建多张大尺寸临时图。用户打开悬浮菜单时，Service 单独冻结最后一张完整帧，避免 Photos 在菜单消失过程中短暂输出黑帧或过渡帧。

### 6.4 Android 16 单应用横竖屏切换

Android 16 的“单个应用”投影会保留被选任务自己的显示方向。若用户在竖屏授权后打开横屏游戏，只把 `VirtualDisplay` 和 `ImageReader` 改成回调报告的横屏尺寸，会在部分 ColorOS 设备上继续沿用竖屏镜像变换，结果是大面积黑屏或横倒、裁切的游戏画面。

当前实现同时处理尺寸和旋转：

- `onCapturedContentResize()` 报告的宽高作为最终识别画面的方向尺寸；
- `VirtualDisplay` 与 `ImageReader` 使用旋转前的基础尺寸，90°/270° 时交换宽高；
- Android 16 通过 `VirtualDisplay.setRotation()` 同步所选任务的显示旋转；
- 取帧时先去掉 `ImageReader` 行对齐填充，再反向旋转一次，最终仍向 OCR 和 ROI 识别器提供正常方向的横屏 Bitmap；
- Android 15 及以下没有 `setRotation()`，继续使用原有的尺寸调整路径，不改变既有行为。

2026-07-15 在 RMX3820 / ColorOS 16 / Android 16 上按“竖屏授权 Pokémon Champions 单应用 → 游戏横屏队伍配置页”实测：内部冻结帧恢复为完整 `2772×1240` 六卡片画面，能力值页识别结果为 `41/42`，不再出现“实际检测到 0 个”。

## 7. 最终性能与准确率

Android 模拟器使用 `dataset/0707/1.jpg` 至 `15.jpg`，共 15 张图片、180 个头像槽位进行回归。

| 指标 | 优化前 | 最终版本 |
| --- | ---: | ---: |
| Top-1 | 178/180（98.89%） | 176/180（97.78%） |
| Top-3 | 180/180（100%） | 180/180（100%） |
| 平均识别引擎耗时 | 17,672 ms | 4,013 ms |
| 引擎最短/最长 | 16,616 / 18,779 ms | 3,368 / 4,735 ms |

平均引擎耗时下降 77.29%，Top-1 下降 1.11 个百分点，Top-3 保持 100%。

最终真实悬浮菜单冷启动测试：

- App Activity 冷启动：3,871 ms；
- 后台准备：1,402 ms；
- 菜单点击至私有 JSON 保存：5,129 ms；
- 其中识别引擎：4,772 ms；
- 特征提取：2,411 ms；
- 候选排序/精排：2,244 ms。

4 个 Top-1 差异的正确答案都仍在 Top-3：

- `7.jpg own.slot3`：`Annihilape` 为第 3 名；
- `9.jpg opponent.slot3`：`Sneasler` 为第 2 名；
- `10.jpg`、`11.jpg own.slot2`：`Rotom-Wash` 为第 2 名。

其中洛托姆形态使用相同或非常接近的基础头像，不能仅依赖当前头像可靠区分，因此必须保留人工确认，不应按图片编号或固定队伍组合硬编码。

### 7.1 手机宽高比回归

2026-07-14 使用一张 2772×1240 的真实手机队伍预览截图验证 v2 画布映射。该截图未加入模板库，也没有按图片名或队伍答案写识别特判。

| 映射方式 | Top-1 | Top-3 |
| --- | ---: | ---: |
| 旧版整张截图分别缩放 X/Y | 0/12 | 2/12 |
| v2 居中 16:9 游戏画布 | 12/12 | 12/12 |

v2 识别结果为：我方 `Charizard`、`Azumarill`、`Steelix`、`Whimsicott`、`Gengar`、`Drampa`；对方 `Tyranitar`、`Arcanine`、`Whimsicott`、`Drampa`、`Aggron`、`Sylveon`。用户确认属于错误界面的 3136×1440 图片不属于支持目标，也未进入训练、模板导出或本轮回归。

## 8. 性能诊断字段

输出 JSON 的 `performance` 同时记录整图和每个槽位的计时；顶层 `roiMapping.gameViewport` 记录本次识别实际使用的游戏画布，排查不同设备坐标时应先核对该字段。

整图字段包括：

- `captureHideWaitMs`、`frameCopyMs`、`executorQueueMs`；
- `openCvInitMs`、`templateLoadMs`、`roiConfigLoadMs`；
- `bitmapToBgrMs`、`featureTotalMs`、`rankTotalMs`；
- `engineWallMs`、`engineThreadCpuMs`；
- `eligibleTemplateEvaluations`、`refinedTemplateEvaluations`。

每槽字段包括：

- `cropMs`、`featureMs`、`rankMs`；
- `strictColorMaskMs`、`relaxedColorMaskMs`；
- `grabCutMaskMs`、`maskSelectionMs`、`colorMaskQuality`；
- `adaptiveGrabCutFallback`；
- `eligibleTemplates`、`refinedTemplates`。

排查速度时，优先观察：

1. `templateLoadMs` 是否在同一进程内反复升高；
2. `grabCutMaskMs` 是否集中在某些槽位；
3. `rankTotalMs` 和 `refinedTemplateEvaluations` 是否同时上升；
4. `frameCopyMs` 是否因截图分辨率或设备内存压力异常增大。

## 9. 开发和回归入口

主要文件：

| 职责 | 文件 |
| --- | --- |
| 悬浮菜单、MediaProjection、帧复用 | `android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/OverlayCaptureService.kt` |
| ROI 裁切、特征、粗排/精排、JSON | `android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/TeamPreviewRecognition.kt` |
| 16:9 游戏画布映射 | `android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant/TeamPreviewViewportMapping.kt` |
| SafeZone ROI | `src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json` |
| Android V2 模板 | `src/data/recognition/android/team-preview-templates-v2.bin` |
| 模板元数据 | `src/data/recognition/android/team-preview-templates-v2.json` |
| 模板导出和留一图验证 | `tools/android/export-team-preview-templates.py` |
| 模拟器样本准备 | `tools/android/prepare-team-preview-emulator.ps1` |

模板、ROI 或评分逻辑变化后，至少执行：

```powershell
python tools/recognition/test_team_preview_viewport.py
npm.cmd run recognition:android:templates
npm.cmd run check
npm.cmd run android:test-engine
npm.cmd run android:assemble
```

模板导出验证必须保持整图留一验证 Top-3 通过，并检查元数据中的二进制 SHA-256、v2 ROI SHA-256 与 APK 内资产一致。Android 端还必须重新跑 15 张全屏样本，不能只用 PC 结果代替 Android 回归。

Debug APK 提供非导出的批量触发 action。它只在 App 可调试、截图 Service 已启动且 MediaProjection 已授权时有效：

```powershell
$pkg = 'com.crazylei12.pokemonchampionsassistant'
adb shell "run-as $pkg am startservice --user 0 -n $pkg/.OverlayCaptureService -a $pkg.DEBUG_RECOGNIZE_TEAM_PREVIEW"
```

正式功能仍必须通过悬浮按钮验证至少一次，避免批量入口绕过菜单冻结帧后掩盖实际问题。

## 10. 维护约束

- 更新模板时必须同时提交 V2 `.bin` 和 `.json`，不能只改其中一个。
- 更新 ROI 后必须确认 3392×2400 基准坐标、居中 16:9 画布映射和手机/平板两类回归没有退化。
- 不允许为了通过回归把错误界面或非 `TEAM_PREVIEW` 画面加入有效样本集。
- 不允许按样本文件名、槽位答案或固定队伍组合写识别特判。
- Top-1 不是自动确认依据；未确认候选不能进入伤害计算请求。
- 新游戏版本若改变队伍页布局，应先更新样本和 ROI，再重新建立 Android 基线。
- 批量回归中的 Top-3 下降、黑帧、截图尺寸变化或模板哈希不一致都属于阻断问题。
- Android 16 单应用投影必须覆盖“竖屏授权后进入横屏游戏”的真机回归，并确认内部识别帧方向正确、六张卡片完整可见。
- 若 12 个 ROI 都没有检测到前景，App 会把它视为系统屏蔽或不可见画面，直接提示重试，不保存候选 JSON。
- 双方队伍预览是当前对局的临时状态，不得恢复为按时间戳永久保存；需要调试样本时应由开发者通过明确的测试流程另行导出。

实现提交：`ece1b61 perf: accelerate Android team preview recognition`。

详细实验数据和逐阶段对照见：`docs/android_team_preview_roi_debug_record_2026-07-13_zh.md`。
