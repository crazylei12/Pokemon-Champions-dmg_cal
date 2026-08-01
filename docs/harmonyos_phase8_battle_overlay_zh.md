# HarmonyOS 阶段 8：对局会话、悬浮伤害面板和战斗 HUD 验收记录

日期：2026-08-01

状态：**实现与模拟器可验范围已完成；真实取帧隔离、浮窗触摸穿透和旋转恢复的真机门延期**

## 1. 阶段边界

本阶段等价移植 Android 标准版已有的对局状态、完整悬浮伤害面板和战斗 HUD。标准版与录屏功能版共享同一套对局实现；录屏能力仍只在下一阶段对录屏功能版开放。

正式页面没有新增调试入口，也没有把相册、测试图、ROI、OCR、Top-3 或内部文件名暴露给用户。Debug 验收入口只接受命令行启动参数，不出现在 Release 产品界面。

## 2. 已实现内容

### 2.1 对局状态与计算

- 保存我方/对手当前槽位、逐对手预设、逐槽烧伤与五项能力等级；
- 保存天气、场地、墙、守住、帮助、要害、范围修正和我方输出/我方承伤方向；
- 单打关闭帮助与范围修正，切回双打恢复双打默认范围修正；
- 逐槽保存速度等级、麻痹、速度翻倍、讲究围巾覆盖、双方顺风和戏法空间；
- 速度线同时处理招式优先度、精确速度、区间速度和戏法空间反转；
- 对手可即时切换形态、预设、六项努力点、特性和携带物，修改后立即进入计算；
- 伤害请求继续使用固定本地 ArkWeb 引擎，完整传递四招、状态、等级、场地与双打修正，缓存键忽略一次性请求 ID。

### 2.2 普通悬浮伤害面板

- 普通悬浮菜单可打开正式 `TYPE_FLOAT` 伤害面板；主应用不增加 Android 原版没有的直达测试按钮；
- 面板提供“我方输出”“我方承伤”、双方宝可梦、招式与预设切换；
- “伤害、战场状态、速度线、对手配置”四个区段均连接当前对局，不是静态占位；
- 重新识别双方阵容或我方队伍前先最小化伤害浮窗，流程结束后恢复；
- 结束进程或 UIAbility 销毁时销毁对局窗口，避免残留浮窗。

### 2.3 战斗 HUD 与布局

- HUD 不再使用单个大窗口，而是与 Android 一样拆分为调整、再战、隐藏/显示、录像、单双打、识别我方、状态、速度、耐久假设、双方场上槽位、四招伤害和详细入口等独立 `TYPE_FLOAT` 窗口；
- 双打就绪时共 15 个窗口，单打就绪时共 13 个窗口，隐藏 HUD 后只保留 6 个工具栏窗口；
- HUD 可显示双打四个场上槽位、单打两个场上槽位、速度顺序、四招伤害、场地摘要、预设和戏法空间；
- 切换槽位时保持 HUD 两个位置互不重复，并把当前槽位优先放入单打 HUD；
- 每个部件按 Android `BattleDirectHudLayout` 的比例锚点恢复，支持独立拖动、缩放、安全边界约束，以及横屏/竖屏独立比例布局；
- 支持隐藏 HUD 后保留最小恢复入口，并提供再战与重新识别我方入口；
- 保存的布局和对局状态复用阶段 4 的原子存储，不写入截图或临时帧。

## 3. 自动化、构建与模拟器结果

执行：

```powershell
npm.cmd run harmonyos:phase8:check
npm.cmd run harmonyos:phase8:emulator
node --test tools/harmonyos/phase0-contracts.test.mjs tools/harmonyos/phase2-scaffold.test.mjs tools/harmonyos/phase3-domain.test.mjs tools/harmonyos/phase4-storage.test.mjs tools/harmonyos/phase5-main-ui.test.mjs tools/harmonyos/phase6-own-team-ocr.test.mjs tools/harmonyos/phase7-team-preview.test.mjs tools/harmonyos/phase8-battle-overlay.test.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode release -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-packages.ps1 -BuildMode release
```

最终全阶段静态回归共 59 项全部通过，其中阶段 8 覆盖逐槽状态、HUD 槽位去重、单双打默认值、速度修正与戏法空间、布局缩放/越界约束、完整伤害请求、缓存键、独立窗口路由和正式产品入口。

模拟器分别安装标准版和录屏功能版 Debug 包，实际创建 `TYPE_FLOAT` 窗口并验证：

- 完整面板包含我方输出、我方承伤、战场状态、速度线和对手配置；
- HUD 调用 HAP 内固定本地引擎，两个产品均显示“十万伏特 1 56.2–68.0%”；
- 验收先将模拟器切为横屏 `2772×1240`，实际创建并核对各独立窗口的位置、页面路由和文本；
- 双打速度线显示两个我方和两个对手位置且窗口数为 15；单打窗口数为 13；隐藏后窗口数为 6，恢复后回到双打 15；
- 相册全屏图视觉对照确认 HUD 默认不遮挡中央主要游戏区域，深色半透明样式与 Android 原位 HUD 一致；
- 标准版与录屏功能版均为 `Panel=PASS`、`HudDamage=PASS`、`SingleDouble=PASS`、`HideRestore=PASS`；
- 自动化没有点击任何系统隐私授权按钮，`PrivacyPromptClicked=False`。

可提交的 UI 层级证据为：

- `harmonyos/app/evidence/pc-stage8-standard-panel.json`；
- `harmonyos/app/evidence/pc-stage8-standard-hud.json`；
- `harmonyos/app/evidence/pc-stage8-standard-hud-damage.json`；
- `harmonyos/app/evidence/pc-stage8-replay-panel.json`；
- `harmonyos/app/evidence/pc-stage8-replay-hud.json`；
- 本地视觉证据 `.tmp/ui-parity/gallery-photo-hud-fullscreen.png`、`.tmp/ui-parity/gallery-photo-hud-single.png`、`.tmp/ui-parity/gallery-panel-dark.jpeg`（不提交 Git）。

最终 Release 包校验结果：

| 变体 | 字节 | SHA-256 |
| --- | ---: | --- |
| standard | 38,925,439 | `10f66679bfe8c95420032edacf73d6a75b2c5af206f07bafa616f1c5994034d8` |
| replay | 38,926,409 | `41ef690e3c7caf2ab9c0d23f759d9b4ebfa57d239be348d8af657d916fff984f` |

两包包含 `arm64-v8a` 和 `x86_64`，包结构与变体元数据校验通过；仍为未配置发布签名的本地产物。构建保留 SDK 对 Native 模块静态验证、可能抛出异常、未启用混淆和未签名配置等警告。

## 4. 未关闭的真实设备门

当前 DevEco 模拟器的 AVScreenCapture 不输出视频帧，且 `uitest` 坐标注入不能驱动 `TYPE_FLOAT` 按钮，因此以下结论仍需 HarmonyOS 真机或可用的系统捕获环境关闭：

1. 相册全屏图片被捕获时，悬浮球、菜单、面板和 HUD 均不进入原始帧；
2. 用户手指可在浮窗内操作控件，同时浮窗外游戏区域保持预期触摸行为；
3. 标题栏连续拖动、缩放、越界约束和横竖屏旋转后布局恢复均符合手感与安全区要求；
4. 系统撤销浮窗或屏幕捕获授权、进程被杀和捕获失败后没有残留窗口；
5. “再战”和“识别我方”经真实捕获授权、识别、返回后能恢复 HUD 且截图帧不受污染。

这些门没有被模拟器布局树、Debug 直接状态调用或代码接线冒充为通过。系统屏幕捕获和隐私授权仍必须由用户本人决定。

## 5. 阶段退出结论

阶段 8 的对局状态、双向伤害、普通悬浮面板、HUD、单双打、速度线、对手即时配置、布局保存、隐藏恢复、再战入口、双变体构建和模拟器实际伤害结果均已完成，可以进入阶段 9。与真实捕获帧、浮窗触摸和设备旋转有关的门继续保留到最终真机验收。
