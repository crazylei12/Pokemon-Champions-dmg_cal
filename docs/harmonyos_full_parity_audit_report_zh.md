# Pokemon Champions Assistant HarmonyOS 全量对照修复与验收报告

日期：2026-08-02

状态：**确定性代码缺陷已修复，双变体 Debug 构建与 API 24 模拟器验收通过；因缺少正式签名材料和 ARM64 真机 E5 证据，当前仍不可作为正式 Release 发布。**

对应计划：`docs/harmonyos_full_parity_audit_plan_zh.md`

机器可读矩阵：`config/harmonyos-full-audit-matrix.json`

证据清单：`config/harmonyos-emulator-evidence-manifest.json`、`config/harmonyos-e4-paired-ui-evidence.json`

## 1. 最终结论

本轮按 220 项计划逐项复核并修复了原审计报告确认的确定性缺陷。产品实现快照已固定为：

`eb228033292e3eef1999cd3f82c198e6d9449bf9`

最终矩阵汇总：**PASS：106，FAIL：0，BLOCKED：114，NOT_APPLICABLE：0，合计 220。**

- `FAIL` 只用于当前源码或当前可执行证据仍能证明的确定缺陷；本轮修复后不再把“缺设备/缺签名”误写成代码失败。
- `BLOCKED` 只用于证据等级尚未满足的项目，主要是正式签名 Release、ARM64 真机授权、真实捕获/OCR、系统浮窗、录屏编解码、内部音频、媒体库和覆盖升级。
- Debug HAP 的自动化运行证据单独只支持到 E3；本轮另用带哈希的 Android/HarmonyOS 成对截图、hierarchy 和人工评估关闭 4 项空状态 E4。两者都不冒充正式签名或 E5 真机证据。

因此，结论不是“已经可发布”，而是：**仓库内可独立完成的代码、契约、构建、包级隔离和模拟器正式页面问题已关闭；发布验收还需要外部签名材料与 ARM64 HarmonyOS 真机。**

## 2. 审计基线

| 对象 | 分支 | 提交 | 本轮写入 |
| --- | --- | --- | --- |
| HarmonyOS | `feature/harmonyos-port` | `eb228033292e3eef1999cd3f82c198e6d9449bf9` | 产品修复、测试和当前模拟器/E4 证据 |
| Android standard | `main` | `7cfb0b048572b48b02c45b649f2dcde272b3a61c` | 否，只读对照；工作树保持干净 |
| Android replay | `feature/battle-replay-phase-4` | `5650e88f16db466a7167f01ea26ebe8d32b86651` | 否，只读对照；仅保留用户既有 `artifacts/` |

39 组 Android 共享主题和 13 个 replay-only 主题的逐提交语义对照位于 `docs/harmonyos_shared_fix_and_evidence_mapping_zh.md`。共享主题最终为 30 组已移植、9 组 Android 专属或过期发布材料不适用、0 组仍缺失。

## 3. 已关闭的主要缺陷

### 3.1 构建、签名门和变体隔离

- DevEco、SDK、Hvigor、Node、Java、OpenCV 和 ffprobe 路径改为本地配置，示例配置可提交，实际机器配置被忽略；大工具继续位于 D 盘。
- Release 构建必须提供 signing config，并校验证书、profile、keystore 和 SHA-256；缺材料时立即失败，禁止生成或命名无签名 Release。
- standard/replay 使用独立 source set：standard 不编译 replay recorder，不链接录屏/编码/muxer 库，也不显示录像入口；replay 才包含相应能力和 `WRITE_IMAGEVIDEO`。
- 正式页面彻底删除 Stage3/4/6/7/8/9 及其 Seed/launch 源文件；包验证对 Stage 页面、调试字符串、权限、ABI、资源和变体符号做负向检查。
- C++ 以 warnings-as-errors 构建，ArkTS 不再使用 `@ts-nocheck`；第三方许可随 9 个 runtime 资产进入 HAP。

### 3.2 存储、备份和跨平台契约

- 全量恢复改为同文件系统 staging、校验、journal 和原子交换；持续 I/O 失败、交换中断和重复恢复不会先删除活数据。
- 删除预设及其会话引用采用事务路径；损坏文件、超限数据、非法数值、旧 schema 和 partial restore 均在提交前验证。
- 生产 `AppStorageRepository` 新增直接执行证据：删除非当前队伍不影响当前会话，删除当前引用队伍同步清理会话；损坏预设在保存/导出前被拦截，先逐字节保留恢复副本，再由显式重置恢复为空。
- 修复正式文档导入的真实缺陷：DocumentPicker 返回 URI，现改为 URI open→fd stat→64 KiB 分块读取→严格 UTF-8 解码，并在成功、短读、超限和异常路径统一关闭 fd；Phase 5 共 15/15 用例通过。正式系统选择器和隐私交互仍保留 E4/E5 边界。
- Android 完整备份字段、六只队伍、用户预设、BattleSession、状态/来源/warnings、招式元数据和伤害请求均被纳入黄金契约。
- 新增 Kotlin→JSON 与 ArkTS 正式 parser/adapter→JSON 双执行器，同一输入逐字段 `deepEqual`；并修复用户预设实体缺失 `source: "user"` 的真实差异。

### 3.3 捕获、识别与异步生命周期

- Native 捕获按实际 width/height/stride/format/timestamp 处理；旋转时先暂停消费者，安全重建后恢复，旧 generation 和陈旧帧不可再返回。
- 授权取消、目标隐藏/私有、目标销毁和错误回调会同步停止状态、清空可用帧并使 ArkTS 代次失效。
- 我方两页识别支持任意页序、expectedType、六槽指纹、空白失败页进入人工核对、低置信阻断及 Ditto-only Transform 例外。
- 双方预览必须保留 12 槽顺序，低置信槽需要显式确认；取消核对不会提前破坏上一局会话。
- 新增 Harmony SDK Clang 原生 runner，直接编译当前 `team_preview_engine.cpp` 并在 API 24 x86_64 模拟器执行：16/16 边界策略和 8 张固定图 × 2 轮识别通过；空 ROI、负尺寸、旋转旧 generation、Top-3、去重及 0.90/0.035 精确边界都有生产 C++ 证据。
- Android production instrumentation 直接同步并执行只读 Android main/replay 字节一致的 Kotlin/OpenCV 源码和生产资源；两端使用相同 2772×1240 RGBA 输入。由此定位并修复 OpenCV RNG 零种子语义差异，最终 Top-1 96/96、ordered Top-3 96/96、排序信号 96/96 完全一致，288/288 候选数值在固定 `1e-6` 容差内，mismatchCount 为 0。
- 自由计算、OCR、Panel 和 HUD 均使用 generation、请求指纹、超时和迟到结果丢弃；Panel 24 项与 HUD 12 项 LRU 忽略易变 requestId。

### 3.4 正式 UI、浮窗与共享 Android 语义

- 四个主入口、二级页返回、重试、受保护 mutation、完整预设字段、合法招式编辑、伤害 warnings 和中英文规范 ID 搜索均进入正式页面。
- 修复首页标题挤压版本号的问题；standard/replay 正式 HAP 的 hierarchy 与新截图均显示完整 `v1.1.4`，标题和版本边界不重叠。
- 修复队伍编辑器槽位切换时表单仍引用上一槽模型的问题：选中对象改为深拷贝，并以 revision/key 重建编辑树。最终 HAP 已确认从 Ditto 切换到大嘴娃时显示大嘴娃、威吓和嬉闹，不残留变身；完整“编辑→保存→自由计算/回放载入”链尚未执行，因此 TEAM-005 仍保持 `BLOCKED`。
- 修复自由计算页对手配置 Select 初始只显示箭头的问题；标准版和回放版最终 hierarchy 与截图都明确显示“无加点”。
- 自由计算输入变化会立即 supersede 旧 generation 并清空旧结果；更新检查增加重入与 generation 防护。最终 HAP 探针在 standard/replay 都观察到真实更新与计算区间重叠且最终状态独立，并记录旧计算 generation 的回调被丢弃、最新结果可见。APP-006 与 CALC-012 因此达到 E3。
- APP-003 在两变体最终 HAP 上通过系统 Back、弹层 Back、编辑页/二级页和 raw `uinput -T` 分段左右边缘手势，达到 E3。
- standard/replay 冷启动、热恢复和同构建覆盖安装均通过；但本轮缺少可追溯的同身份低版本 HAP，无法重跑升级子路径，APP-005 按整项验收保持 `BLOCKED`。
- Panel 支持用户收起、恢复原子页、新阵容重置到伤害页；HUD 有独立我方识别 busy 状态和继续核对流程。
- panel、HUD 和悬浮入口统一处理系统安全区、键盘、旋转、边缘吸附以及 `getCurrentFoldCreaseRegion()` 返回的中间 fold/hinge 不可用区域。
- UI、storage、domain 和协调器不再把原始异常、绝对路径、token 或队伍 JSON 输出到日志/用户文案，只暴露稳定分类码和脱敏文案。
- 更新入口只接受符合 variant/bundle/version/size/digest/host 约束的 HAP 元数据，并明确使用系统浏览器、系统下载/安装管理器提供进度、取消、重试和低空间处理。

### 3.5 replay 录制与资源清理

- 视频使用真实采集 PTS；音视频队列、暂停时间轴、旋转、目标隐藏和内部音频状态被纳入 recorder 状态机。
- 输出路径限制为私有目录，预检至少 64 MiB；失败时不发布到媒体库，私有 partial 文件按策略保留并暴露清理结果。
- 成功、取消、encoder/muxer 失败、重复 cleanup、重复 finalize、partial retention 和禁止发布均由生产 C++ 共用策略覆盖。
- HarmonyOS SDK Clang 实际编译并启动原生 x86_64 测试程序；这属于 E2 Native 执行，不代表 OH_AVCodec/OH_AVMuxer/AVScreenCapture 真机资源已执行。

## 4. 自动化与可执行证据

### 4.1 Node、Native 和跨端黄金测试

当前共有 82 个 Node test，并逐项分类：

| 类型 | 数量 | 最高证据 |
| --- | ---: | --- |
| `LOGIC_EXECUTION` | 49 | E2 |
| `STATIC_CONTRACT` | 20 | E1 |
| `SOURCE_ASSERTION` | 11 | E1 |
| `NATIVE_EXECUTION` | 2 | E2 |
| `FORMAL_UI` | 0 | 不由 Node 冒充 |
| `DEVICE_BLACK_BOX` | 0 | 不由 Node 冒充 |

最终要求是 82/82 PASS。除此之外：

- Kotlin `CrossPlatformGoldenExportTest`：1/1 PASS；
- `node tools/harmonyos/verify-cross-platform-golden.mjs`：PASS；
- C++ replay lifecycle executable：PASS；
- C++ team-preview production runner：16/16 policy checks、8 张图 × 2 轮 PASS；
- PowerShell 16 个脚本解析：PASS；
- `git diff --check`：PASS。

### 4.2 Debug 双变体构建

命令：`npm.cmd run harmonyos:assemble`

环境：DevEco Studio 6.1.1.300、SDK 6.1.1(24)/6.1.1.125、Hvigor 6.24.4、Node 18.20.1、Java 21.0.8、arm64-v8a+x86_64。

| 变体 | 页面 | ABI | 字节 | SHA-256 |
| --- | ---: | --- | ---: | --- |
| standard Debug unsigned | 17 | arm64-v8a、x86_64 | 40,606,092 | `a5743c9d180106d71a3d3e600aa4f46d864903e350cf535edde5b40ed6a7626e` |
| replay Debug unsigned | 18 | arm64-v8a、x86_64 | 40,926,266 | `c08e6ece638712b5a7f1ec0fe9bc5bb7328ee7c8969aa8c233bc2d486958fc60` |

两包均通过 bundle/version、双 ABI、9 个资源及哈希、许可、页面、权限、Stage 负向、standard/replay Native 和 ArkTS 标记隔离检查。这里明确写作 Debug unsigned，不称为 Release。

E3 延迟探针由构建脚本按 build mode 临时物化：Debug 源包含可观测延迟，Release 源为 no-op，构建结束后正式源码目录恢复为空。`runtime-e3-probe-release-scan.json` 记录 `4b9fce7` 临时 unsigned Release 字节码中 3 个 Debug 标记匹配数均为 0；之后到 `eb22803` 只改 `Index.ets` 与 Phase 5 测试，因此该文件仅作隔离支持证据，不计当前签名 Release 验收。

### 4.3 API 24 模拟器正式入口

目标：`127.0.0.1:5557`，`OpenHarmony-6.1.1.125`，x86_64 emulator。

以下八组脚本在最终 HAP 上执行通过：

1. standard/replay 冷启动、正式首页、变体标记和 Native bridge；
2. 本地伤害引擎与计算页；
3. 两变体正式存储入口；
4. 首页、预设、自由计算、对局和设置主 UI；
5. 我方识别正式入口；
6. 双方预览正式入口；
7. Panel/HUD 正式入口和权限边界；
8. standard 无录像说明、replay 有录像说明的产品门。

UI hierarchy 同时断言可点击、可滚动、enabled/disabled 和 visible。脚本不会自动同意隐私授权，也不会用 Seed 数据制造一场已确认对局。

另分 scope 执行 `verify-app-calc-parity-ui.ps1`：APP-003 在双变体通过系统 Back、弹层 Back 和 raw `uinput -T` 分段左右边缘手势；双变体冷/热恢复和同构建覆盖安装通过，但升级因缺少同身份低版本 HAP 而阻塞；standard/replay 的真实更新检查与真实计算区间重叠且最终状态互不覆盖；CALC-012 记录旧 generation 回调被丢弃且最新结果可见。APP-003、APP-006、CALC-012 达到 E3，APP-005 保持 `BLOCKED`。首页版本裁切和计算页“无加点”初始选择器均由最终 HAP hierarchy 与人工截图确认修复。

### 4.4 Android/HarmonyOS 成对 E4 空状态验收

以独立工作树从 Android standard `7cfb0b048572b48b02c45b649f2dcde272b3a61c` 和 replay `5650e88f16db466a7167f01ea26ebe8d32b86651` 构建 x86_64 Debug APK，在专用 API 34 模拟器新装采集首页、自由计算、对局和设置；HarmonyOS 两变体也在最终 HAP 新装后采集相同四页。`config/harmonyos-e4-paired-ui-evidence.json` 固化 Android 基线清单、两份 HAP、32 个截图/hierarchy 文件和人工结论的 SHA-256。

8 组配对人工复核未发现裁切、重叠、错误导航态或标准/回放文案串线；状态栏、手势条、平台图标、Select/分段控件、纵向留白和系统悬浮能力确认被记录为合理平台适配。`UI-001`、`UI-002`、`UI-006`、`UI-015` 因此达到 E4。读屏、键盘、长文本、授权后状态、刘海/折叠屏与真实数据仍由其余独立条目保持 `BLOCKED`。

## 5. Release 负向门

`npm.cmd run harmonyos:assemble-release` 在缺少 signing config 时按预期失败：

`Release builds require -SigningConfigPath or HARMONY_SIGNING_CONFIG; unsigned Release output is forbidden.`

`harmonyos/app/dist` 中只保留两份当前 Debug HAP，`*-release-unsigned.hap` 数量为 0。仓库和常见配置位置未发现可授权使用的正式 HarmonyOS 证书、profile 或 keystore，因此本轮不能生成正式签名包，也不能验证证书连续性和覆盖升级。

## 6. 仍然 BLOCKED 的外部验收

剩余 114 项不是继续猜测代码即可关闭的同一种缺陷；按要求等级分为 8 项 E3、25 项 E4、81 项 E5。主要边界为：

- PREVIEW-004～006 已由 Android/HarmonyOS 生产算法对 8 张相同 RGBA 输入的逐候选黄金结果关闭为 E2 PASS；该结果不外推到 ARM64、系统截图或隐私授权。
- APP-005 仍缺同身份低版本升级；TEAM-005、TEAM-008、PRESET-008 与 QUAL-001 仍缺其验收语义要求的完整保存/载入、失败、并发或生命周期 E3 状态迁移；BUILD-002/007/012 缺签名 Release 产物。已有局部证据不拆分验收项冒充整项 PASS。
- 25 项 E4 仍需 Android/HarmonyOS 成对真实数据截图、读屏/焦点、键盘、长文本、危险操作和完整交互状态的人工核验。
- 正式 Release signing config、证书指纹、同签名覆盖升级、降级拒绝和升级后数据保留；
- 用户亲自选择捕获目标后的系统授权、撤销、锁屏、前后台、分屏、旋转、目标隐藏/私有和异常终止；
- 真实 Pokémon Champions 两页我方识别、12 槽预览、低置信人工核对和已确认对局；
- 真实浮窗/HUD 触摸、拖动、键盘、刘海、折叠铰链和多窗口合成；
- ARM64 上 H.264/AAC/MP4、非静音内部音频、长时音画同步、编码器/muxer 失败、媒体库发布/回滚和播放器兼容；
- 正式更新源、系统下载/安装、低空间、网络中断、取消重试和签名连续性。

这些条目在矩阵中保持 `BLOCKED`，而不是伪造 `PASS` 或遗留为已修代码 `FAIL`。

## 7. 复验命令

```powershell
node tools/harmonyos/verify-cross-platform-golden.mjs
$tests = Get-ChildItem tools/harmonyos/*.test.mjs | Sort-Object Name | ForEach-Object FullName
node --test @tests
npm.cmd run harmonyos:assemble
npm.cmd run harmonyos:assemble-release  # 无签名材料时必须失败
```

模拟器脚本必须显式指定目标：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-emulator-shell.ps1 -Target 127.0.0.1:5557
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-calc-parity-ui.ps1 -Target 127.0.0.1:5557 -Scope Runtime
```

其余 Stage3–9 正式验收脚本使用同一 `-Target` 参数。真机验收必须改为实际 ARM64 HDC target，并由用户完成所有隐私与保存决定。

## 8. 发布判定

- 确定性源码/契约缺陷：已关闭；
- 双 Debug HAP 与包级隔离：通过；
- API 24 x86_64 模拟器正式入口：通过；
- 正式签名 Release：未生成，BLOCKED；
- ARM64 真机 E5：未执行，BLOCKED；
- 当前发布判定：**NO-GO，等待签名材料和 ARM64 真机验收。**
