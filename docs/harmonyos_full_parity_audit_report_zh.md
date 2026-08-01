# Pokemon Champions Assistant HarmonyOS 全量对照代码审计记录

日期：2026-08-01

状态：**代码静态审计已完成；当前实现不通过，不可发布；真机最终验收未执行**

对应计划：`docs/harmonyos_full_parity_audit_plan_zh.md`

审计对象：`feature/harmonyos-port` 的 `standard`、`replay` 两个 HarmonyOS 产品变体，并以当前 Android 标准版、录屏版为对照。

## 1. 结论

当前 HarmonyOS 实现不能称为“Android 全量等价”，也不能作为正式发布候选。无需实机即可确认发布签名、数据恢复、变体隔离、捕获尺寸、异步生命周期、识别确认、伤害状态、录像时间轴和更新资产校验等确定性问题。

本轮对计划中的 220 项检查条目完成了代码审计归档：

| 静态审计分类 | 数量 | 含义 |
| --- | ---: | --- |
| `FAIL` | 118 | 当前源码或产物已经证明实现缺失、错误或与计划/Android 基线不一致 |
| `BLOCKED` | 66 | 需要模拟器、真机、签名、黄金样本或系统能力才能得出最终结论 |
| `E1_REVIEWED` | 36 | 已检查当前源码路径，未发现确定性静态缺陷，但证据等级不足时不得写成正式 `PASS` |

按照审计计划的证据规则，系统截屏、Core Vision OCR、浮窗触摸、录屏、内部音频、媒体库和发布升级仍至少需要 E5 真机证据。本报告只记录代码、静态产物和现有自动化能够证明的结果，不声称设备端功能正常。

## 2. 审计基线与边界

| 对象 | 工作树 | 分支 | 提交 | 状态 |
| --- | --- | --- | --- | --- |
| HarmonyOS | `D:\crazylei12\pokemon-champions-assistant-harmonyos` | `feature/harmonyos-port` | `c64d69853740f58a58f004910c20a93f6ebd7958` | 干净 |
| Android standard | `D:\crazylei12\pokemon-champions-assistant-main-safe-area` | `main` | `7cfb0b048572b48b02c45b649f2dcde272b3a61c` | 较远端 ahead 2，干净 |
| Android replay | `D:\crazylei12\pokemon-champions-assistant` | `feature/battle-replay-phase-4` | `5650e88f16db466a7167f01ea26ebe8d32b86651` | 较远端 ahead 2；仅有用户既存 `artifacts/` 未跟踪 |
| Smogon 子模块 | `external/smogon-damage-calc` | 固定提交 | `3677e41a5e75...` | 未修改 |

本轮没有运行模拟器、真机、HDC、设备授权、媒体库或安装操作；没有重新生成 HAP；没有修改用户 `artifacts/`。Android 两个分支仅用于读取当前产品真值，没有写入文档或代码。本报告属于 HarmonyOS 专属审计材料，因此只写入 HarmonyOS 工作树。

## 3. P0 缺陷

### P0-01：正式产物没有签名链

关联：`BUILD-009`、`UPDATE-009`

- `harmonyos/app/build-profile.json5` 的 `signingConfigs` 为空；两个产品仍引用名为 `default` 的签名配置。
- `tools/harmonyos/build-app.ps1` 明确收集 `entry-default-unsigned.hap`，输出文件继续命名为 `*-unsigned.hap`。
- `harmonyos/app/dist` 中现存 Debug/Release HAP 全部为 unsigned。

影响：无法验证正式安装、同签名覆盖升级、证书连续性、版本降级拒绝和升级后的用户数据保留。未签名 HAP 不得称为发布包。

修复要求：建立独立 Release signing config；构建脚本必须强制 Release 签名并校验证书指纹；最终在 ARM64 真机执行同 bundle、递增 versionCode 的覆盖升级与数据保留测试。

### P0-02：整包恢复在持续 I/O 故障下可能造成不可恢复数据损坏

关联：`STORE-002`、`STORE-006`、`STORE-012`、`TEAM-008`

- `AppStorageRepository.ts:373-386` 先把当前数据保存在内存快照中，再清空本机文件并写入恢复数据。
- 写入失败后，`AppStorageRepository.ts:496-517` 再次清空目录并重写内存快照。
- 如果失败原因是持续磁盘满、权限或文件系统错误，回滚写入会因相同原因再次失败；旧文件此时已经删除。

影响：用户原有队伍、会话、草稿、配置或更新频道可能永久丢失。这也是当前 Android 实现中存在的共享风险，不应误写成 HarmonyOS 独有差异。

修复要求：在同文件系统 staging 目录完整写入并校验新数据，保留旧目录或事务日志，只有全部成功后才原子交换；对每个写入点注入持续 ENOSPC/I/O 失败并逐字节验证原数据不变。

## 4. P1 核心缺陷

### 4.1 standard/replay 变体串线

关联：`BASE-004`、`BUILD-006`、`QUAL-011`、`REPLAY-001`

- `harmonyos/app/entry/src/main/cpp/CMakeLists.txt:28` 对两个产品无条件编译 `replay_recorder.cpp`。
- 同文件 44–49 行无条件链接屏幕捕获、编码器和 muxer 库。
- `BattleOverlayCoordinator.ts:440-450` 无条件加入 `RECORDING` HUD 元素。
- `BattleHudElement.ets:281-282` 只把 standard 的“录像”按钮禁用，没有移除入口。
- 现有 standard/replay Release HAP 的 Native 库逐 ABI 完全相同。

影响：standard 仍携带录像代码、Native 接口、媒体依赖、攻击面和用户可见录像概念，违反两个产品的明确边界。

### 4.2 Release 包含 Stage 验证页面和调试实现

关联：`BUILD-007`、`BUILD-012`、`UI-010`

`harmonyos/app/entry/src/main/resources/base/profile/main_pages.json:21-26` 注册 Stage3/4/6/7/8/9 页面。`EntryAbility.ets` 的 `DEBUG` 判断只能阻止普通 Release want 跳转，不能把页面、假数据和相关代码从 HAP 中剔除。现有 Release HAP 的 `main_pages.json` 和 `modules.abc` 均包含这些内容。

修复要求：使用 Release source set 或生成式页面清单剔除 Stage；包检查增加 forbidden page、string、symbol、permission、signature 和 replay 隔离负向断言。

### 4.3 捕获尺寸硬编码为 2772×1240

关联：`CAPTURE-005/006/007`、`PREVIEW-013`、`REPLAY-005`

`ScreenCaptureService.ets:4-55` 拒绝任何其他尺寸；`napi_init.cpp:335-453` 始终按固定宽高复制缓冲，虽然开启跟随旋转，却没有尺寸变化通知和安全重建机制。

影响：1240×2772、16:9、非 1:1 缩放、系统栏裁剪和旋转过程可能被直接拒绝、错误解释 stride，或继续读取旧尺寸缓冲。

### 4.4 授权中断后仍可返回旧帧和假运行状态

关联：`CAPTURE-001/003/004/009/010`、`PANEL-007`、`PERM-006/011/012`

- `napi_init.cpp:292-312` 的取消、撤销、中断和错误回调只更新 Native atomic，没有清空 `prepared/latestFrame` 或通知 ArkTS。
- `TakeLatestFrame` 只要旧 buffer 非空便继续返回，不检查捕获是否运行、帧龄或用户动作时间。
- `OwnTeamCaptureCoordinator.ets` 维护另一份 `running`，没有同步 Native 终止状态。
- 浮窗最小化后立即读取 latest frame，没有等待窗口真正离开画面，也没有要求帧时间晚于隐藏完成时间。

### 4.5 OCR、自由计算和 HUD 缺少异步代次保护

关联：`BASE-002`、`APP-006`、`CAPTURE-008`、`OWN-009`、`CALC-010/012`、`HUD-005/006`、`QUAL-001`

- `OwnTeamCaptureCoordinator.ets:123-164` 的 Promise 完成后无条件写草稿/预览；`stop()` 不会取消任务或使旧 generation 失效。
- `Index.ets:778-803` 没有请求 fingerprint、代次或超时，计算期间输入仍可变化。
- `BattleHudElement.ets:83-96` 在计算中忽略后续 revision，旧 Promise 返回后无条件更新 UI。

Android 当前分支已经加入我方识别代次、30 秒慢任务提示、90 秒超时和销毁后结果丢弃，HarmonyOS 尚未移植。

### 4.6 我方 OCR 页面分类、低置信和合并逻辑错误

关联：`OWN-001/007/008/009/011/013`

- `OwnTeamRecognition.ts:302-315` 在证据不足时默认判定为招式页。
- 先能力值页、后招式页时，后者会清除已保存的能力值页，不能支持两页倒序录入。
- 两页缺少可靠的六槽队伍指纹，不同队伍页面可能被错误拼接。
- fuzzy 候选只要字段存在就可成为完整结果，没有把 confidence/ambiguity 纳入保存条件。
- Ditto 只检查一个不同招式，没有限定规范 ID 必须为 `transform`；该漏洞也存在于当前 Android 基线。
- HarmonyOS 保存的最小 JSON 与 Android 的完整状态、来源、确认、warnings 和识别统计 schema 不等价。

### 4.7 低置信队伍预览可绕过人工确认

关联：`PREVIEW-008/010/011`、`BATTLE-005`

`TeamPreviewRecognition.ts:94-100` 给低置信 Top-1 设置 `confirmed=false`，但 `buildBattleSessionFromSetup()` 只检查 `selected`。正式页面确认按钮同样没有要求全部低置信槽被显式确认。

此外，`AppStorageRepository.ts:207-225` 在新预览尚未核对时就删除旧 BattleSession；用户取消核对后无法恢复上一局。

### 4.8 队伍编辑会丢特性和招式元数据

关联：`TEAM-004/005`、`CALC-003/013`

- `Index.ets:317-321` 把特性 Select value 设为中文显示名。
- `Index.ets:758-765` 却把该 value 与 `showdownId` 比较，中文环境选中后可能保存为 `undefined`。
- `Index.ets:413-417` 新增招式时使用招式 ID 调用物种招式池查询，通常退化成缺少类型和威力的 `MANUAL_OVERRIDE`。

### 4.9 正式预设路径未接入形态共享和特性回退

关联：`PRESET-006/007`、`CALC-002/013`

`PresetLogic.ts` 实现了指定形态共享和非法特性回退，但正式自由计算和 HUD 仅按精确 species ID 查询用户预设；切换形态时会直接清除预设和手动覆盖。该 helper 主要被 Stage 验证路径使用，没有进入正式产品仓库/API。

### 4.10 BattleSession 旧字段迁移缺失

关联：`BATTLE-001`

`BattleSession.ts:221-253` 只规范化新 `ownConditions/opponentConditions`，不会迁移旧 `ownBurned/ownStages/opponentBurned/opponentStages`。旧会话加载后会静默丢失烧伤和能力等级，导致伤害输入错误。

### 4.11 对手速度线缺少先制和保护动作

关联：`HUD-004`

`BattleOverlayCoordinator.ts:969-997` 对对手固定传入 `priorityMoves: []`。Android 当前实现会从对手配置和合法动作中提取先制、保护等动作。HarmonyOS 的排序和动作列表因此可能错误。

### 4.12 录像时间轴、失败状态和媒体发布不安全

关联：`REPLAY-007/008/011/012/013/015`

- `replay_recorder.cpp:266-289` 使用“已接受帧序号 × 1/24 秒”生成视频 PTS，忽略真实采集时间。
- PCM 队列满时丢帧但不推进音频时钟，长期录像会发生音画漂移。
- 捕获或编码运行中失败后，Native 与 ArkTS 状态机可能分裂为 UI 已停止但内部仍为 RUNNING，后续无法重新开始。
- 录音可用性用前 12 帧是否非静音判断，正常静音开场可能被误报成内部音频失败。
- 相册目标创建后复制失败只关闭 fd，不删除或回滚空/部分媒体资产。

### 4.13 Native/NAPI 边界不足

关联：`QUAL-002/003/005`

- 多个 NAPI 参数读取忽略 `napi_status`。
- 捕获宽高没有合理上限。
- replay 输出接受任意字符串路径，Native 随后可截断或删除该路径。
- 模板文件的 size/count 缺少硬上限和乘法溢出检查。
- async work 创建或排队失败没有完整处理，Promise 可能永不完成。

### 4.14 更新资产和 URL 校验不足

关联：`UPDATE-002/004/006/008/010/011/012`、`QUAL-004`

- `UpdateService.ets` 会把任意 `.hap/.app/.hsp` 资产视为更新，只依赖文件名选择变体。
- 没有 signed、release、bundle、product、ABI、SHA-256、响应体大小或最终 redirect host 校验。
- `Index.ets` 对 API 返回 URL 直接 `openLink`，没有 HTTPS/GitHub host allowlist。
- UI、更新比较、备份和配置分享在多处硬编码版本 `1.1.4`/versionCode 9，而不是从 HAP metadata 读取。

## 5. 其他已确认缺陷

以下问题主要定为 P2/P3，但仍应在发布前处理：

- 4 MB/16 MB 导入限制在 `readTextSync` 读取完整文件后才检查，不能防止超大文件阻塞和内存耗尽。
- 队伍列表按文件名升序，而 Android 按修改时间降序。
- 损坏队伍文件被静默隐藏；损坏会话下删除队伍可能出现磁盘已删、UI 仍显示并报告失败。
- 删除用户预设不是跨文件事务，后半段失败会留下悬空会话引用。
- 备份输入没有完整验证有限整数、范围、Infinity、重复招式和深层 JSON。
- 页面导航和编辑草稿主要依赖易失 `@State`，缺少系统返回栈、Ability 重建和前台刷新。
- 计算 assumptions、warnings 和 error 的中文本地化落后 Android。
- 悬浮球不可拖动；缺少边缘吸附、安全区、键盘焦点、分屏和折叠区域约束。
- “结束助手”没有统一关闭伤害面板/HUD；HUD 也没有完整关闭会话动作。
- 构建配置硬编码本机 D 盘 DevEco/OpenCV 路径，Hvigor 文件使用 `@ts-nocheck`，C++ 没有 warnings-as-errors。
- `THIRD_PARTY_NOTICES.md` 没有准确记录 HarmonyOS 静态链接的 OpenCV，HAP 内也未见随包许可声明。
- 正式页面缺少系统化无障碍语义，并强制暗色和硬编码颜色。

计划中的 `PRESET-001`、`PRESET-002`、`PRESET-005` 与当前 Android 产品本身也不一致：两端都缺少对局页配置管理入口、复制配置、等级/招式完整编辑。它们仍在矩阵中标为 `FAIL`，但属于计划相对两端的共同产品缺口，不应描述为 HarmonyOS 独有漏移植。

## 6. 220 项代码审计矩阵

`E1_REVIEWED` 不是审计计划定义的正式 `PASS`。它只表示当前代码路径已检查、未发现确定性静态缺陷；若条目最低证据等级为 E2–E5，仍必须在获得相应证据后才能改成 `PASS`。

| 域 | `E1_REVIEWED` | `FAIL` | `BLOCKED` |
| --- | --- | --- | --- |
| BASE | 001、003、006 | 002、004、005 | — |
| BUILD | 005、008 | 001、004、006、007、009–012 | 002、003 |
| APP | 002 | 003–008 | 001 |
| UI | 004、006–008、014 | 005、010、012、013 | 001–003、009、011、015 |
| PERM | 001、003、008、009 | 002、004、006、010–012 | 005、007 |
| CAPTURE | — | 001、003–010 | 002、011、012 |
| OWN | — | 001、007–011、013 | 002–006、012、014–016 |
| PREVIEW | — | 008、010、011、013 | 001–007、009、012、014、015 |
| TEAM | 003、006、007 | 001、002、004、005、008 | 009、010 |
| PRESET | 003、008、010 | 001、002、004–007、009、011 | 012 |
| STORE | 001、002、007、010 | 004–006、008、012 | 003、009、011 |
| CALC | 001、002、004、006、009 | 003、005、008、010–012 | 007、013 |
| BATTLE | — | 001、005 | 002–004 |
| PANEL | — | 001、002、004–007 | 003、008 |
| HUD | — | 004–006、009、011、013 | 001–003、007、008、010、012、014、015 |
| UPDATE | 001、003、005、007 | 002、004、006、008、010–012 | 009 |
| REPLAY | — | 001、005、007、008、011–013、015 | 002–004、006、009、010、014 |
| QUAL | — | 001–007、010–012 | 008、009 |
| TEST | 001、009 | 002–008、010 | — |

## 7. 自动化和旧证据可信度

### 7.1 当前 59 项 Node 测试分类

| 测试阶段 | 纯业务/JS 逻辑 | 源码字符串断言 | JSON/文件/hash 静态契约 | Native | 正式 UI/设备 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Phase 0 | 1 | 0 | 7 | 0 | 0 |
| Phase 2 | 0 | 1 | 3 | 0 | 0 |
| Phase 3 | 6 | 1 | 0 | 0 | 0 |
| Phase 4 | 7 | 1 | 0 | 0 | 0 |
| Phase 5 | 4 | 1 | 1 | 0 | 0 |
| Phase 6 | 4 | 1 | 1 | 0 | 0 |
| Phase 7 | 3 | 1 | 1 | 0 | 0 |
| Phase 8 | 5 | 1 | 0 | 0 | 0 |
| Phase 9 | 2 | 3 | 0 | 0 | 0 |
| Phase 10 | 0 | 1 | 3 | 0 | 0 |
| 合计 | **32** | **11** | **16** | **0** | **0** |

59/59 通过只表示 32 个 JS/ArkTS 纯逻辑用例和 27 个静态检查通过，没有任何 Node 用例执行 C++ Native、正式 ArkUI、系统权限、屏幕捕获、Core Vision、浮窗、录屏或设备黑盒。

### 7.2 测试固化了错误行为

`tools/harmonyos/phase7-team-preview.test.mjs:76-81` 创建 12 槽均为 `confirmed=false` 的 setup draft，只确认 `opponent[5]`，随后明确期望 `buildBattleSessionFromSetup()` 成功。该测试不是漏掉确认门，而是把未确认槽位绕过写成预期。

Stage7 Debug 页还自行定义 Seed 类型并直接写存储，没有经过真实捕获、Native 图像识别和正式解析链路。UI verifier 主要搜索 ID、文本和坐标，不完整检查 clickable、scrollable、enabled、遮挡或真实触摸。

### 7.3 Phase 10 旧矩阵和 HAP 已失效

- `config/harmonyos-phase10-acceptance.json` 的 `sourceCommit` 仍为 `bbdf55c433fc6f7c9b3ce0e7e6fb8c179bbd3ebe`，当前 HEAD 为 `c64d698...`。
- 矩阵记录的 standard/replay HAP bytes 与 SHA-256 均和当前 `dist` 实物不一致。
- `phase10-final-acceptance.test.mjs` 只验证提交/hash 字符串格式和文件名正则，没有读取候选 HAP、比较大小/hash，甚至没有确认候选路径存在。
- 现有四个 HAP 生成于 17:16–17:23，早于 17:25 的旋转修复 `f39d1d9`，不包含该修复。
- 四个 HAP 全部 unsigned。

因此旧阶段 10 的 38 项 `PASS` 应全部视为历史材料，不能继承到当前提交。

## 8. 本轮已执行验证

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| Phase 0、2–10 Node 测试 | 59/59 通过 | 证据类型见第 7 节，不等价于正式产品通过 |
| `npm.cmd run check` | 通过 | 根 TypeScript `tsc --noEmit` 和 Android 版本检查 |
| `npm.cmd run check:licenses` | 通过 | 固定 Smogon/@pkmn 依赖检查 |
| `git diff --check` | 通过 | 审计前工作树无文本问题 |
| HarmonyOS/Android 工作树复核 | 通过 | HarmonyOS/main 干净；replay 仅保留用户 `artifacts/` |
| 当前 HEAD 重新构建 | 未执行 | 用户限定为代码审计；现存 HAP 已判定过期且 unsigned |
| 模拟器/真机 | 未执行 | 用户当前无法提供真机，本轮不推断设备行为 |

## 9. 修复和复测顺序

1. 建立签名链，重构整包恢复为可回滚事务，真正隔离 standard/replay。
2. 修复捕获动态尺寸、帧 freshness、授权状态同步和 OCR generation/timeout。
3. 修复低置信 preview 确认门、pending preview 事务和 BattleSession 旧字段迁移。
4. 修复特性/招式编辑、预设形态共享、对手速度线和自由计算/HUD 异步代次。
5. 修复录像视频/音频 PTS、Native/ArkTS 失败状态同步和媒体发布回滚。
6. 补齐 Native/NAPI 边界、更新资产 manifest/hash/host 校验、导入大小前置限制和第三方许可。
7. 将源码字符串断言降级为静态存在性检查，补充可执行 Native、正式 UI、并发、持续 I/O 失败和完整跨平台黄金测试。
8. 所有 P0/P1 修复后，从当前提交重新构建并解包检查 standard/replay Debug/Release。
9. 配置正式 Release 签名后，再在 ARM64 HarmonyOS 真机完成权限、捕获、OCR、浮窗、旋转、内部音频、MP4、媒体库和覆盖升级 E5 验收。

在第 9 步完成前，最终状态保持：**代码静态审计完成，当前实现不可发布；设备端全量验收 BLOCKED。**
