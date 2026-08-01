# HarmonyOS 阶段 6：相册取帧与我方两页 OCR 验收记录

日期：2026-08-01

状态：**实现与模拟器可验范围已完成；真实相册取帧和 OCR 真机门延期**

## 1. 阶段边界

本阶段移植 Android 已有的单窗口画面捕获、普通悬浮入口、我方队伍招式/道具页和能力值页识别、两页合并、人工核对、命名与保存。没有增加文件直读捷径，也没有把调试样本入口暴露到 Release 产品。

系统屏幕捕获授权仍必须由用户在系统界面决定。自动化没有点击“允许使用屏幕”等隐私按钮；模拟器验收只验证授权前的正式入口和授权后的业务页面结构。

## 2. 已实现内容

### 2.1 单窗口捕获与有效帧保护

- Native `AVScreenCapture` 使用系统选择器选择单个相册窗口，输出 RGBA 原始帧；
- 捕获画布按旋转和实际帧尺寸更新，目标横屏样本为 `2772×1240`；
- 去除缓冲 stride 后才向 ArkTS 暴露连续像素，并返回尺寸、时间戳、哈希和六卡候选；
- 只有非黑、具有足够亮度差且连续两帧稳定的画面才能替换最新有效帧；
- 识别前收起悬浮菜单并冻结帧，黑帧或不可见内容不会覆盖上一次有效结果；
- 停止、Ability 销毁和异常路径统一释放捕获会话、PixelMap 与悬浮窗口。

### 2.2 悬浮入口与页面顺序

普通悬浮球可展开以下原应用操作：

- 录入我的队伍；
- 重新选择相册窗口；
- 返回助手核对；
- 结束对局助手。

流程把招式/道具页作为新一轮起点；新的招式页会清除未完成的上一轮，能力值页只有与当前六槽队伍一致时才进入核对。顺序错误、未知页面和未形成六卡结构都会保留当前有效草稿并给出下一步提示。

### 2.3 OCR、归一化与人工核对

- 使用与 Android 相同的六卡布局、字段 ROI 和页面证据分类；
- 卡片 ROI 以 2 倍 PixelMap 交给 Core Vision，文字行再按坐标回填物种、特性、道具、招式和能力值字段；
- 原始文本经中文名、Showdown ID、规范 ID 和别名目录归一化；不能可靠归一化的字段保持未决，不自动伪造；
- 保留原招式格位索引，两页物种冲突必须人工确认；明确“无道具”可完成道具字段；
- 六只的物种、特性、道具结论、招式和六项实际能力值均可逐项修改；
- 普通物种必须有四个互不重复的招式，只有百变怪使用一个“变身”招式例外；
- 完整后可用 1–30 字符名称保存；取消不破坏已有队伍，放弃/重试会清理本轮草稿。

## 3. 固定相册样本门

`config/harmonyos-phase6-album-samples.json` 固定了用户已有的六组两页样本，共 12 张 JPEG。自动化逐张检查：

- 文件仍位于既有回放工作树的用户 `artifacts/phase4/candidate-previews`，移植工作树不复制或修改这些图片；
- 每张图片均为精确的 `2772×1240`；
- 文件大小与 SHA-256 和清单一致；
- 六组均具有招式/道具页和能力值页，未把中间的队伍预览图片混入本阶段。

该门只能证明验收输入未漂移，不能替代“相册全屏 → 单窗口捕获 → Core Vision OCR”的端到端结果。

## 4. 自动化、构建与模拟器结果

执行：

```powershell
npm.cmd run harmonyos:phase6:check
npm.cmd run harmonyos:phase6:emulator
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode debug
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode release
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-packages.ps1 -BuildMode release
```

纯逻辑与接线门共 6 项，通过页面分类、数字候选与 `6/9`、`2/3` 视觉纠错、顺序重开、两页合并、六槽完整性、百变怪窄例外、Native/Core Vision/悬浮/核对页接线以及 12 张固定图片哈希。

模拟器分别覆盖安装标准版和录屏功能版，应用内 Debug 验证页只向正式仓库注入一份结构完整的草稿，随后实际点击并确认：

- 对局页保持 Android 原有的“启动对局助手”“启动对局助手（HUD版）”、录入说明和开始一场对局说明，不增加相册测试入口；
- 草稿在调试页和正常应用进程之间持久化；人工纠正 UI、六槽完整性和百变怪一招例外由同阶段静态回归覆盖；
- 核对页有六槽完整数据、逐项输入、冲突确认、无道具确认、保存/取消/重试；
- 百变怪显示“需要 1 个不同招式”，其他物种保持四招要求；
- 两个产品的正式页面均通过，且 `PrivacyPromptClicked=False`；
- 结束后清除调试草稿、重新安装标准版并回到正常首页。

可提交的 UI 层级证据为：

- `harmonyos/app/evidence/pc-stage6-standard-battle.json`；
- `harmonyos/app/evidence/pc-stage6-standard-correction.json`；
- `harmonyos/app/evidence/pc-stage6-replay-battle.json`；
- `harmonyos/app/evidence/pc-stage6-replay-correction.json`。

Debug 和 Release 的标准版/录屏功能版均完成 ArkTS、C++、arm64-v8a、x86_64 编译。Release 包校验结果：

| 变体 | 字节 | SHA-256 |
| --- | ---: | --- |
| standard | 25,666,575 | `0db2f79e8710a75a656b347aa8fc7a3807198097ea61a69abfbe84f6c000a71d` |
| replay | 25,667,105 | `924915792aff00c80e6fad1376ae52bae6c7897174b5ab16929996fe40b1582c` |

两包仍为未签名本地产物；编译和包内容通过不等于发布签名或真机安装通过。

## 5. 未关闭的真实设备门

阶段 1 已确认当前 DevEco 模拟器不支持录屏：指定相册窗口的会话虽然启动并产生内部音频回调，但视频帧始终为 0；Core Vision 在模拟器运行时报 `Cannot read property recognizeText of undefined`。因此本阶段没有伪造以下结论：

1. 12 张图片经真实单窗口捕获进入 OCR；
2. 六组物种、形态、特性、道具、招式和能力值逐字段正确；
3. 每组连续 10 次无错页、旧结果或浮层污染；
4. 真机横竖屏切换、授权撤销、强杀和窗口消失后的资源释放；
5. 真机 OCR 性能、失败率和恢复耗时。

有 HarmonyOS 真机后，应按主方案设置横屏 `2772×1240`，将每张图片在系统相册真正全屏显示，再逐组执行 10 次。任何未决字段必须进入人工核对，不能为了通过验收降低完整性规则。

## 6. 阶段退出结论

阶段 6 的产品代码、两页识别域逻辑、完整人工修正、双变体界面、双模式构建和模拟器可验证门已完成，可以继续阶段 7 的双方队伍预览识别。真实相册捕获与 Core Vision 的端到端门继续保留到真机，不因进入下一阶段而视为通过。
