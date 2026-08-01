# HarmonyOS 共享修复、变体和证据映射

日期：2026-08-01

用途：关闭全量审计的共享修复账本与测试证据分类缺口。本文只记录当前真实源码和工作树；不修改 Android，也不把旧 Stage、旧 HAP、旧截图当作当前验收。

## 1. 当前基线与工作树

| 对象 | 分支 | 当前提交 | 工作树 |
| --- | --- | --- | --- |
| Android standard | `main` | `7cfb0b048572b48b02c45b649f2dcde272b3a61c` | 干净，较远端 ahead 2 |
| Android replay | `feature/battle-replay-phase-4` | `5650e88f16db466a7167f01ea26ebe8d32b86651` | 仅用户既存未跟踪 `artifacts/` |
| HarmonyOS | `feature/harmonyos-port` | `aa28e8f90dfd900cf595905dd952052c2bc5b81f` | 产品修复已提交；本文件和最终矩阵属于其后的审计快照 |

Android 两分支共同 merge-base 为 `e035943eafd2de67995bccf1daab44716e184085`。按提交主题核对，merge-base 后有 39 组 standard/replay 同主题共享提交、0 个 main-only 主题，以及 13 个 replay-only 主题。主题对应只用于建立账本；行为结论还以当前文件哈希、HarmonyOS 入口和可执行测试为证据。

HarmonyOS 与 main 的分叉点是 `49d9b5e9291abbc5bd0b22c25b5e37812cb8b232`。已提交的主要移植入口为：

- `12ce9e53`：standard/replay 工程和产品骨架；
- `efaa70a0`：领域模型与固定伤害引擎；
- `ccb46cf2`：存储、备份和迁移；
- `7324026a`：正式主界面；
- `37cd5ac9`：我方识别；
- `eda6d3c1`：队伍预览识别；
- `cc7b030e`：对局面板和 HUD；
- `b80d8b12`：replay 录屏变体；
- `2436b73e`、`f39d1d9d`：UI/横屏和旋转返工。

第三轮产品修复固定为 `bed0bb30fa92d01a285311d1d16846fd410c43c1`；第四轮预览、文档 URI 与异步生命周期补强后的当前产品快照为 `aa28e8f90dfd900cf595905dd952052c2bc5b81f`。每个结论仍同时指向路径、可执行测试或明确的 E3/E5 blocker，不以提交主题代替行为证据。

## 2. Android 当前文件同一性复核

下列当前关键 Kotlin 文件在 main/replay 的 SHA-256 相同，证明这些行为属于共享真值而非 replay 特例：

| 文件 | SHA-256 前 12 位 | 两分支相同 |
| --- | --- | --- |
| `BattleModels.kt` | `5e1dd93fe6cf` | 是 |
| `OwnTeamRecognition.kt` | `c20945d0438f` | 是 |
| `OwnTeamCorrection.kt` | `66880e25b44f` | 是 |
| `AppDataBackup.kt` | `119c37e02368` | 是 |
| `OpponentUserPresetStore.kt` | `612ad02c7b67` | 是 |
| `BattleDirectOverlay.kt` | `c0cf5f747e45` | 是 |
| `BattleOverlayController.kt` | `069050d39231` | 是 |
| `DamageEngineRuntime.kt` | `63a4960d6ef3` | 是 |
| `UpdateManager.kt` | `20587de71b5e` | 是 |

## 3. 39 组共享提交逐项映射

以下结论来自逐提交读取 Android 真实 diff（不仅是提交主题）后，再核对当前 HarmonyOS 工作树。状态含义：`已移植` 表示 Android 修复的产品语义已有平台等价实现；`不适用` 表示提交只处理 Android API、APK 或 Android 专属文档；`仍缺失` 表示已定位到具体未迁入语义。E5 缺失只限制设备验收，不会把已经存在的实现误写成“仍缺失”。

| Android main | Android replay | 提交主题 | HarmonyOS 当前入口 | 语义结论（真实 diff 复核） |
| --- | --- | --- | --- | --- |
| `7cfb0b04` | `5650e88f` | fix(android): clarify own-team HUD recognition | `OwnTeamCaptureCoordinator.ets`、`OwnTeamRecognitionService.ets`、`OwnTeamRecognition.ts`、`BattleHudElement.ets`、Phase 6/8 | **已移植**：coordinator 从草稿推导并向 service 传递 `expectedType`；捕获或识别失败时用 `blankOwnTeamPage` 保留两页流程并进入人工核对；HUD 有独立识别按钮、忙碌状态、30/90 秒门、generation 和“继续核对”文案。Phase 6 执行空白页续接，Phase 8 核对正式 HUD 接线；正式帧正确性仍需 E5。 |
| `1e930cab` | `dd1fa47a` | fix(android): allow Ditto teams with one move | `OwnTeamRecognition.ts`、`phase6-own-team-ocr.test.mjs` | **已移植**：`requiredOwnTeamMoveCount` 仅给 Ditto 返回 1，并强制唯一招式为 Transform；Phase 6 同时证明其他物种仍需四招。 |
| `49d9b5e9` | `f18fbd62` | docs: finalize Android 1.1.4 artifacts | `config/harmonyos-app-build.json`、`verify-app-packages.ps1` | **不适用**：提交只封存 Android 1.1.4 APK/发布文档；HarmonyOS 使用 HAP 清单与独立发布文档。当前 HAP 签名/覆盖升级缺失属于 BUILD/UPDATE 证据边界，不是漏移 APK 文档。 |
| `8fd69bc1` | `4f04a351` | release: prepare Android 1.1.4 variants | `build-profile.json5`、`AppScope/app.json5`、`UpdateService.ets`、Phase 2/5 | **已移植**：1.1.4 版本、同 bundle 双 target、standard/replay 更新资产筛选均有平台等价实现；正式签名 HAP 和覆盖升级仍需 E3/E5。 |
| `b994ae10` | `d69ac157` | feat: share in-battle form configurations | `PresetLogic.ts`、`AppUiModels.ts`、Phase 3/5 | **已移植**：`configurationShareGroupId` 优先组和跨形态 profile 适配均在当前运行时执行；不是依据同名字段推断。 |
| `6dbba2b2` | `f772d176` | feat: share opponent configs with mega forms | `PresetLogic.ts`、`AppUiModels.ts`、Phase 3/5 | **已移植**：Mega 后缀归组、能力合法性回退、目标形态能力值重算及 manual override 适配有 E2 用例。 |
| `479e28b9` | `8f4a811f` | fix: finish single-line keyboard input | `Index.ets` 的 ArkUI `TextInput` | **不适用**：Android diff 修复自建 `ImeInput`、Compose/View 焦点和 IME action；HarmonyOS 没有该桥，正式字段直接使用单行 `TextInput`。键盘/焦点仍需 E4，但不存在待移植的 Android `ImeInput` 代码路径。 |
| `6f003282` | `cc8405e2` | feat: create opponent presets from home | `Index.ets`、`AppUiModels.ts`、Phase 5 | **已移植**：首页可新建空白 draft，保存时重算能力值并写入用户预设；Phase 5 检查受保护 mutation 和完整字段。 |
| `e51d963c` | `71b70ea8` | fix: protect and validate opponent preset data | `StorageContracts.ts`、`AppStorageRepository.ts`、`Index.ets`、Phase 4/5 | **已移植**：损坏文件停止覆盖、保留副本再重置、schema/上限/字段验证和 UI 禁写门均有当前实现与 E2 测试。 |
| `ef483212` | `30cfea8d` | feat: share and migrate opponent presets | `DocumentTransferService.ets`、`StorageContracts.ts`、`AppStorageRepository.ts`、Phase 4/5 | **已移植**：4 MB 上限、kind/schema、按 profileId 幂等合并、导入统计和文档选择器均已实现；并修复 DocumentPicker URI 被误当路径读取的问题，现通过 URI fd 分块读取、严格 UTF-8 解码并保证关闭。正式系统文件交互仍需 E4/E5。 |
| `623aca6c` | `fd918a04` | feat: manage saved opponent presets | `Index.ets`、`AppStorageRepository.ts`、`removeOpponentPresetReferences`、Phase 4/5 | **已移植**：列表、编辑、复制、删除、存储刷新和删除后的槽位/manual override 引用清理均有执行测试。 |
| `b328d911` | `155b398f` | feat: save custom opponent presets | `Index.ets`、`StorageContracts.ts`、`AppStorageRepository.ts`、Phase 4/5 | **已移植**：用户 ID、名称/等级/能力点/招式验证、排序和原子持久化已映射；正式 UI 交互另需 E4。 |
| `02486d29` | `6ed8523b` | Test confirmed move request serialization | `MoveSelection.ts`、`BattleSession.ts`、Phase 3/8 | **已移植**：当前测试直接构造请求并验证确认招式被序列化，不以源码字符串代替。 |
| `c361200e` | `cf6ce559` | Prefer confirmed team moves over snapshot data | `MoveSelection.ts`、`BattleSession.ts`、Phase 3/5/8 | **已移植**：`confirmedMoves` 先于 snapshot/合法池，且不被不完整合法池过滤；双向请求有 E2。 |
| `e54d6c38` | `e7e40d20` | Use Champions learnsets for damage move pools | `RuntimeDataRepository.ts`、打包的 `runtime/damage/champions-presets.json`、Phase 0/3 | **已移植**：HarmonyOS 消费固定 Champions 数据资产并由正反例/黄金伤害测试约束，不回退到通用 Gen 9 池。 |
| `664183cc` | `55ba139c` | docs: finalize Android 1.1.3 artifacts | HarmonyOS 独立发布资料 | **不适用**：Android 1.1.3 APK 封存文档已被当前 1.1.4 HAP 基线取代，无产品语义需要迁入。 |
| `54734a9c` | `ab2f7223` | release: prepare Android 1.1.3 variants | 当前 1.1.4 `build-profile.json5`、`config/harmonyos-app-build.json` | **不适用**：该提交只准备已过期 Android 1.1.3 包和文档；当前 HarmonyOS 版本身份由 1.1.4 清单复核。 |
| `a1e70ef4` | `c5eb95fd` | feat: support single battle HUD layout | `BattleSession.ts`、`BattleOverlayCoordinator.ts`、Phase 8 | **已移植**：单打每侧一槽、双打每侧两槽、切换时 spread/helping hand 默认和速度线数量均由 E2 执行；窗口形态仍需 E5。 |
| `0b95d6e7` | `62a3e15c` | fix: update Smogon Champions damage engine | 固定 `runtime/damage` 资产、`DamageEngineRuntime.ets`、Phase 0/3 | **已移植**：固定引擎、双向结果、战场修正和 100 次离线黄金投影均执行通过。 |
| `3367f617` | `0d9be7d7` | perf: reduce floating battle panel overhead | `BattleOverlayCoordinator.ts`、`BattleOverlay.ets`、`AppUiModels.ts`、Phase 5/8 | **已移植**：panel 复用单个 ArkUI window；`snapshotCache` 共享对局上下文并在 mutation 后失效；`StringLruCache(24)` 以去除 `requestId` 的请求指纹复用伤害结果，generation/指纹复核丢弃迟到结果。Phase 5 执行 LRU 命中/淘汰，Phase 8 执行稳定请求键和正式接线检查。 |
| `d1f41355` | `04cf4b55` | docs: finalize Android 1.1.2 artifacts | HarmonyOS 独立发布资料 | **不适用**：Android 1.1.2 APK 封存文档不是 HarmonyOS 产品语义。 |
| `9bd46bb3` | `00b62074` | fix: validate cached Android release metadata | `config/harmonyos-app-build.json`、`AppScope/app.json5`、`verify-app-packages.ps1`、Phase 2 | **已移植**：验证脚本从 HAP 解包 manifest，并把 bundle/versionCode/versionName 与当前配置逐项比较；Android Gradle provider 实现本身不适用。 |
| `c8414627` | `95011785` | release: prepare Android 1.1.2 variants | 当前 1.1.4 HarmonyOS 清单 | **不适用**：只准备已过期 Android 1.1.2 APK、截图和文档。 |
| `e8a97a84` | `00bf8055` | perf: reduce battle HUD update overhead | `BattleOverlayCoordinator.ts`、`BattleHudElement.ets`、`AppUiModels.ts`、Phase 5/8 | **已移植**：ArkUI 状态通知原位刷新，只有结构变化才重建窗口；HUD 使用 `StringLruCache(12)` 和去 `requestId` 指纹避免重复进入 ArkWeb，并用 `AsyncRequestGate`、90 秒门及二次指纹比较丢弃迟到/过期结果。Phase 5 执行 LRU，Phase 8 验证四招请求和 HUD 正式调用链。 |
| `bcf8b2f8` | `e9bae633` | fix: remember opponent presets per slot | `BattleSession.ts`、`StorageContracts.ts`、Phase 3/4/8 | **已移植**：`opponentPresetIds` 按槽保存、旧 `selectedPresetId` 迁移和删除引用清理均有 E2。 |
| `e0568d7c` | `4db1225e` | fix: open HUD mode without floating bubble | `Index.ets`、`OwnTeamCaptureCoordinator.ets`、`BattleOverlayCoordinator.ts`、Phase 8 | **已移植**：HUD mode 启动不创建 FloatAssistant，直接创建分布式 HUD；源码门和正式页面入口均已核对，真实系统窗口需 E5。 |
| `5c44b9f9` | `6a9651af` | feat: add HUD opponent preset picker | `BattleHudAssumption.ets`/`BattleHudElement.ets`、`BattleOverlayCoordinator.ts`、Phase 8 | **已移植**：HUD 耐久配置控件读取当前槽 profiles，并把选择写回该槽；按槽记忆由 `BattleSession.ts` 支撑。 |
| `96c63284` | `05f5d848` | fix: retire bubble while battle HUD is active | `OwnTeamCaptureCoordinator.ets`、`BattleOverlayCoordinator.ts`、Phase 8 | **已移植**：HUD 入口不会创建 bubble，mode 切换时销毁互斥窗口集合；需 E5 排除窗口残留。 |
| `c7e2efb0` | `a2686276` | feat: add editable HUD layout | `StorageContracts.ts`、`AppStorageRepository.ts`、`BattleOverlayCoordinator.ts`、Phase 4/8 | **已移植**：portrait/landscape layout、拖动/缩放、边界夹取、保存/重置和非法 placement 丢弃均有代码与 E2；真实拖动需 E5。 |
| `7bde448a` | `f2d41841` | feat: add opt-in battle HUD controls | `Index.ets`、`FloatAssistant.ets`、`BattleOverlayCoordinator.ts`、Phase 8 | **已移植**：普通悬浮面板与 HUD 为明确用户选择，HUD 显示开关和关闭会话不再隐式启用。 |
| `e1085dfa` | `8e4e761f` | fix: separate direct HUD status actions | `BattleHudStatus.ets`、`BattleHudAssumption.ets`、`BattleHudSpeed.ets`、Phase 8 | **已移植**：状态、耐久配置和速度线是不同 HUD 元素/页面与 action，不共用 Android 旧按钮状态。 |
| `04a9f1eb` | `77cfb0a7` | feat: add direct battle HUD overlay | `BattleOverlayCoordinator.ts`、`BattleHud*.ets`、`BattleSession.ts`、Phase 8 | **已移植**：分布式 TYPE_FLOAT HUD、四招伤害、双方槽、状态/速度/详细入口已有实现和 E2 状态测试；系统合成/点击需 E5。 |
| `c7c4cfc8` | `3a9f47c1` | fix: preserve battle panel position across pages | `BattleOverlayCoordinator.ts`、`BattleOverlay.ets`、`AppStorageRepository.ts` | **已移植**：HarmonyOS 四个子页在同一 panel window 中切换，窗口 bounds 按方向存储且切页不重建；真实旋转恢复需 E4/E5。 |
| `0b8cdfe5` | `82998256` | fix: restore collapsed damage panel page | `BattlePanelNavigation`、`BattleOverlayCoordinator.ts`、`BattleOverlay.ets`、Phase 5/8 | **已移植**：用户收起后 `reopen()` 恢复原子页；新阵容 `showSetup()` 调用 `resetForTeamRecognition()` 并把 section 重置为 `DAMAGE`。Phase 5 执行 SPEED 收起/恢复和新阵容重置，Phase 8 核对正式接线。 |
| `85da306b` | `f486951a` | fix: allow collapsing damage subpanels | `BattleOverlay.ets`、`BattleOverlayCoordinator.ts`、`BattlePanelNavigation`、Phase 5/8 | **已移植**：正式标题栏提供 `battle-overlay-collapse`“收起”按钮；条件、速度、对手等任意子页调用 `collapsePanel()` 销毁 panel window 但保留导航页，用户可从入口恢复。纯逻辑导航与正式按钮均有 E2。 |
| `12cb74ec` | `3ad8d696` | docs: record replay build branch | `entry/build-profile.json5`、standard/replay source set | **不适用**：Android 分支说明文档无需搬入；其产品意图已由 HarmonyOS 双 target 和 replay-only source set 实现，双 HAP 隔离仍需 E3/E5。 |
| `7c474102` | `14125133` | docs: finalize Android 1.1.1 artifacts | HarmonyOS 独立发布资料 | **不适用**：Android 1.1.1 APK 封存文档不是当前 HarmonyOS 产品输入。 |
| `cdcf60f7` | `cbfe0f89` | release: prepare Android 1.1.1 variants | 当前 `UpdateService.ets` 与 1.1.4 HAP 配置 | **不适用**：该提交的 Android APK/Gradle/UpdateManager 1.1.1 包准备已过期；HarmonyOS 更新语义由当前 HAP 实现单独覆盖。 |
| `30a944af` | `cc43e56e` | fix: keep overlays inside the current safe area | `AppUiModels.ts`、`OwnTeamCaptureCoordinator.ets`、`BattleOverlayCoordinator.ts`、Phase 5/8 | **已移植**：system/cutout/gesture/keyboard avoid area、旋转重排、边界夹取和吸边均已实现；coordinator 读取 `getCurrentFoldCreaseRegion()`，`avoidWindowOcclusions` 会把中间 fold/hinge crease 当成不可用区域。Phase 5 执行中间折痕避让，Phase 8 核对正式 display 接线；真实折叠设备仍需 E5。 |

结论：39 组均已有明确语义结论，其中 30 组已移植、9 组为 Android 专属或过期发布材料而不适用、0 组仍缺失。最后六组缺口已经由预期页/空白回退、Panel/HUD LRU、收起恢复状态机和 fold/hinge 不可用区域实现关闭，并有 Phase 5/8 E2 用例。因此 `BASE-002` 可按当前提交的逐项账本判为 **PASS（E1）**；真实窗口、识别和折叠屏表现仍由各自 E5 条目约束。

## 4. 13 个 replay-only 主题

这些提交按 replay-only 处理；`不适用` 仅用于 Android MediaProjection/AudioRecord/ColorOS 或 Android 调试脚本，不代表 HarmonyOS replay 功能可以免验收。

| Android replay | 提交主题 | HarmonyOS 当前入口 | 语义结论（真实 diff 复核） |
| --- | --- | --- | --- |
| `53ae684f` | release: mark replay build variant | `entry/build-profile.json5`、standard/replay source set、`PC_REPLAY_ENABLED` | **已移植**：replay target 独立启用 replay source 和 Native gate，standard stub 不加载媒体库/编码器入口；双 HAP 解包和签名仍需 E3/E5。 |
| `aa13559e` | fix: harden replay startup across devices | `ReplaySession.ts`、replay `ReplayRecordingCoordinator.ets`、`napi_init.cpp`、`replay_recorder.cpp`、Phase 9 | **已移植**：三档 H.264 profile、启动失败回滚、内容不可见暂停、目标不可用中止、旋转 resize pause/resume 和失败清理已有平台实现；真实设备编码器/授权仍需 E5。 |
| `e6aa18a8` | docs: document tablet replay audio failure | Android/ColorOS 故障记录 | **不适用**：提交只记录 Android 平板 AudioRecord/AAC 启动故障；HarmonyOS 使用 OH_AVScreenCapture 内部音频和 OH_AudioCodec，不迁入 Android 根因结论。对应 HarmonyOS PCM/AAC 仍需 E5。 |
| `799434f9` | fix: restart replay audio capture on ColorOS | replay `ReplayRecordingCoordinator.ets`、`replay_recorder.cpp` | **不适用**：diff 是 ColorOS `AudioRecord` restart workaround；HarmonyOS 没有该 API。当前实现以 AAC 准备失败显式无声重试、运行 5 秒无内部 PCM 提示用户无声继续，不能把 Android restart 代码照搬。 |
| `87ad37a3` | fix: decouple replay recording from battle assistant | `ReplaySession.ts`、replay/standard `ReplayRecordingCoordinator.ets`、`OwnTeamCaptureCoordinator.ets`、Phase 9 | **已移植**：`RECORD_ONLY` 不初始化识别流程，`RECOGNIZE_ONLY` 不准备 recorder，`RECOGNIZE_AND_RECORD` 共用现有 capture；取消录制不会清除识别会话。E5 验证仍缺。 |
| `2c5d99be` | docs: add battle replay AI usage guide | Android replay 使用文档 | **不适用**：只新增 Android 操作/诊断指南，不包含需移植的产品逻辑；HarmonyOS 用户文案由正式页面和当前审计文档维护。 |
| `69df1958` | fix: harden battle replay resource handling | `napi_init.cpp`、`replay_recorder.cpp`、replay `ReplayRecordingCoordinator.ets`、Phase 9 | **已移植**：编码/复用 capture 的 stop/cancel 分流、线程 join、codec/muxer destroy、失败私有文件清理、媒体库复制回滚和源文件保留策略均已实现；异常系统回调仍需 E5。 |
| `34795e79` | feat: complete battle replay phase 4 integration | replay `ReplayRecordingCoordinator.ets`、`BattleHudRecording.ets`、Native bridge、Phase 9 | **已移植**：录制入口已集成现有 HUD/识别会话，Native stats 驱动状态，完成后才进入显式发布；固定 MP4 可播放性和系统生命周期仍需 E5。 |
| `a9f4d2b9` | test: automate replay phase 3 device acceptance | `verify-stage9-replay-ui.ps1`、Phase 9 隐私门 | **不适用**：Android ADB/UIAutomator 脚本不能迁入 HDC；HarmonyOS 脚本只准备/采证，不自动接受隐私授权，正式设备验收保持 E5。 |
| `69ff007a` | feat: implement battle replay video and game audio | `replay_recorder.cpp`、`napi_init.cpp`、replay `ReplayRecordingCoordinator.ets`、Phase 9 | **已移植**：H.264 三档、NV12 等比缩放、内部 PCM→AAC-LC、单调时间戳、MP4 muxer 和无麦克风契约已接入；真实非静音 PCM/AAC 和音画同步需 E5。 |
| `419a87a2` | feat: complete replay session phase 1 | `ReplaySession.ts`、`ScreenCaptureService.ets`、standard/replay coordinator、Phase 9 | **已移植**：模式状态机、单授权 capture、recognition/recording feature gate、停止/取消状态与 standard stub 已实现。 |
| `9de33d6e` | feat: add replay phase zero capture probes | `inspect_probe_mp4.ps1`、Native stats、历史调试证据策略 | **不适用**：提交新增 Android Debug Activity/Service 和 probe analyzer；HarmonyOS 不携带这些 Android 调试组件，当前 Native stats/检查脚本只作为平台替代诊断，不计正式产品证据。 |
| `07bb276d` | feat: add replay phase zero baseline probe | `inspect_probe_mp4.ps1`、环境/验收脚本 | **不适用**：Android baseline ADB 采集脚本没有跨平台产品语义；HarmonyOS 使用 HDC/Native stats 的独立基线流程，旧 probe 结果仍只作历史证据。 |

13 个 replay-only 主题均已明确为平台等价实现或 Android 专属不适用；本次未从这些提交中定位到新的确定代码缺口。standard 不应包含 replay 入口、权限、Native 编码器或媒体发布能力；当前源码使用 standard/replay target、replay source set 和 Native gate，最终隔离与媒体行为仍需当前双 HAP 解包及签名真机，不能仅凭源码通过。

## 5. Node 测试证据分类

机器可读逐测试分类位于 `config/harmonyos-node-test-evidence.json`。当前共 81 个 Node test：

| 证据类型 | 数量 | 最高可支持 | 当前含义 |
| --- | ---: | --- | --- |
| `LOGIC_EXECUTION` | 49 | E2 | 通过 esbuild/Node 执行当前领域、存储、状态机或固定引擎逻辑 |
| `SOURCE_ASSERTION` | 11 | E1 | 只检查源码字符串、入口或调用结构 |
| `STATIC_CONTRACT` | 19 | E1 | 只检查 JSON、哈希、文件、报告和静态清单 |
| `NATIVE_EXECUTION` | 2 | E2 | 用 HarmonyOS SDK Clang 分别编译并启动 recorder 共用 C++ 清理策略与 team-preview 生产识别核心 runner |
| `FORMAL_UI` | 0 | E3/E4 | 没有 Node 用例驱动正式 ArkUI 产品页面 |
| `DEVICE_BLACK_BOX` | 0 | E5 | 没有 Node 用例执行签名 ARM64 真机黑盒 |

新增高价值 E2 用例：

- Android 备份黄金数据扩展到 status/import/source/warnings/slotIndex/members/basePower/type/priority，并逐字段完整往返；
- `CrossPlatformGoldenExportTest` 让 `android-app` Kotlin 实际执行备份校验、队伍/预设往返和伤害请求构造；`verify-cross-platform-golden.mjs` 再通过正式 `AppUiModels` adapter、`StorageContracts` 与 `BattleSession` ArkTS 执行同一完整备份、六只队伍、预设和伤害输入，并对三个完整规范 JSON 深比较；
- 模拟事务目录已交换但进程在 journal 清理前终止，下一次 repository 构造必须逐字节恢复旧数据；
- 文件 I/O 错误包含绝对路径、token 和完整队伍内容时，storage 边界只能上抛固定安全文案；
- 伤害引擎元数据和错误包含 token、绝对路径和队伍内容时，domain 边界不得回显原 JSON。

这些用例提升错误、竞态和完整字段覆盖；`TEST-004` 已由正向、边界、迁移、竞态、失败和实际 C++ 清理策略执行关闭，`TEST-005` 的 Kotlin/ArkTS 双执行器与全语义深比较也已在 E2 关闭。OH_AVCodec、OH_AVMuxer、AVScreenCapture 等系统资源并未被该原生测试执行，其设备义务仍保留在 CAPTURE/REPLAY/QUAL 的 E5 条目中。

## 6. Stage 和历史证据边界

生产页面清单和 standard/replay target 页面列表已剔除 Stage3/4/6/7/8/9，相关 Stage 页面与验证启动源文件也已删除；因此：

- 生产包不得包含或路由到 Stage；
- 不再存在可向正式流程注入 Seed 数据的 Stage 路径；
- `TEST-003` 按“彻底删除调试页”这一接受方式关闭为 PASS。

下列材料只允许作历史背景：

- `config/harmonyos-phase10-acceptance.json`：sourceCommit 为 `bbdf55c...`；
- `harmonyos/app/evidence/`：Stage 和旧模拟器层级/截图；
- `.tmp/rotation-fix/`：旧旋转截图和 UI hierarchy；
- `harmonyos/app/dist/*-release-unsigned.hap`：17:23 生成的旧 unsigned Release HAP。

上述历史 HAP 不再进入当前 PASS 证据。当前 `aa28e8f...` 的 standard/replay Debug HAP 已重新构建、记录精确哈希，并在 API 24 x86_64 模拟器上把八组正式入口脚本全部复跑通过，因此 `BASE-005` 按当前产物和可追溯 E3 证据关闭为 PASS。它仍不是正式 Release 或 E5，签名、ARM64 真机和发布升级义务继续由 BUILD/UPDATE/APP 等独立条目保持 BLOCKED。

## 7. 日志与隐私源码审计

| 路径 | 当前日志内容 | 结论 |
| --- | --- | --- |
| `storage/**`、`domain/**` | 无 hilog/console 主动日志；本轮已把原始 I/O 异常、引擎元数据 JSON 和引擎 error.message 改成固定安全错误 | 当前域内路径/token/完整队伍回显已由 E2 用例阻止 |
| `cpp/napi_init.cpp` | 捕获状态、错误码、宽高 | 未发现图片、队伍、token 或路径 |
| `cpp/replay_recorder.cpp` | 固定失败文案、尺寸、fps、错误码 | 未发现输出路径或媒体内容 |
| `EntryBackupAbility.ets` | 文件计数和 bundle version | 当前值安全，但格式中的 `from` 实际是版本名，建议后续改名 |
| `EntryAbility.ets` | 只输出 `safeUiErrorCode` 分类码 | 当前 UI 边界不再串行化原始异常 |
| `pages/Index.ets`、浮窗/HUD 页面 | 只展示 `safeUiError` 的固定分类文案 | 绝对路径、token、队伍 JSON 和原始异常不得进入用户文案 |
| `BattleOverlayCoordinator.ts` | 只输出稳定分类码 | reflow/窗口异常不再以 `%{public}s` 回显原始内容 |

因此 `QUAL-006` 的确定性源码缺口已关闭，并由 Phase 3/4/5 的脱敏执行用例约束；真实系统日志采集仍按设备证据条目复核。

## 8. 高耦合与可测性记录

| 文件 | 当前行数 | 集中职责和风险 |
| --- | ---: | --- |
| `pages/Index.ets` | 1722 | 四主页面、数据加载、计算、队伍/预设 CRUD、更新和导航集中；状态竞态与 UI 测试成本高 |
| `services/BattleOverlayCoordinator.ts` | 1090 | 捕获、普通面板、HUD、队伍预览、计算和 replay 协调集中；生命周期和窗口清理耦合 |
| `cpp/napi_init.cpp` | 926 | NAPI、捕获、帧缓冲、Native 识别和 replay 桥接集中；边界与资源释放难以独立执行 |
| `cpp/replay_recorder.cpp` | 745 | 编码、音频、mux、PTS、文件和失败状态集中 |
| `storage/StorageContracts.ts` | 727 | 多 schema 校验、迁移、分享和备份契约集中 |
| `storage/AppStorageRepository.ts` | 697 | CRUD、备份、事务 journal、系统恢复和测试故障注入集中 |

此项要求是“审阅并记录风险”，本文已给出当前行数、职责和不可测边界，因此 `QUAL-012` 可在 E1 记为 PASS；这不代表耦合已经重构。

## 9. 最终补强与仍未关闭的证据边界

第三轮实现和复测额外关闭了八个条目：`BASE-005`、`PREVIEW-003`、`PREVIEW-007`、`TEAM-007`、`PRESET-010`、`UPDATE-002`、`UPDATE-007`、`TEST-010`。其中：

- team-preview runner 直接编译当前生产 C++ 核心，执行 16/16 policy checks，并对 8 张固定图片各运行两轮、输出完整 12 槽 Top-3，支持 `PREVIEW-003/007` 到 E2；
- Phase 4 直接执行当前 repository，证明当前/非当前队伍删除语义，以及损坏预设禁止保存/导出、先原样备份再显式重置；
- Phase 5 通过 NetworkKit stub 直接执行 `UpdateService.check`，覆盖 URL/headers/timeout/大小/redirect/destroy 与 404/403/429/500/空包/超限/畸形/离线/超时；
- 完整矩阵、报告、证据目录和 81 项分类目录互相校验，`TEST-010` 以当前 E1 审计材料关闭。

第四轮又关闭六个条目：`PREVIEW-004/005/006`、`APP-005/006`、`CALC-012`。其中：

- Android production instrumentation 与 HarmonyOS production runner 使用相同的 8 张 2772×1240 RGBA 输入；修正 OpenCV RNG 零种子语义后，96 槽 Top-1/ordered Top-3/排序信号完全一致，288 个候选均在固定 `1e-6` 容差内，跨端 mismatchCount 为 0；
- 最终 standard/replay HAP 的冷启、热恢复、覆盖安装通过，并以同 bundle standard 1.1.3(8)→1.1.4(9) 完成真实覆盖升级；
- Debug-only 可观测时序证明启动未 ready 时更新被门控，ready 后 real update 与 real calculation 在两变体均真实重叠且最终状态独立；CALC-012 同时记录旧 generation 回调被丢弃、最新结果可见。Debug 延迟不会进入 Release 行为。

保持严格 BLOCKED 的边界包括：`TEST-006/007` 仍缺完整可访问性语义和当前签名构建链；`UI-003/011` 仍需 E4 人工对照；`APP-003` 的系统 Back/弹窗返回已通过，但 API 24 模拟器无法派发边缘返回手势；`TEAM-005/008`、`PRESET-008`、`QUAL-001` 仍缺完整失败、并发或生命周期 E3 状态迁移。系统 OH_AVCodec/OH_AVMuxer/AVScreenCapture 资源和 ARM64 黑盒仍只能由 E5 关闭。

本文和机器清单只闭合“知道证据是什么、来自哪里、能证明到哪一级”，不把缺失的 E4/E5 变成 PASS。
