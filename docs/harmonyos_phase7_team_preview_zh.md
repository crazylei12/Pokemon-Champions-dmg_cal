# HarmonyOS 阶段 7：双方队伍预览识别验收记录

日期：2026-08-01

状态：**实现与模拟器可验范围已完成；8 张真实相册全屏图的端到端捕获门延期**

## 1. 阶段边界

本阶段等价移植 Android 已有的双方各六槽队伍预览识别、候选排序、置信度风险、人工复核和新对局建立。识别使用正式 AVScreenCapture 稳定帧、既有 V2 模板与 SafeZone ROI，没有增加文件路径直读、文件名猜测或固定队伍组合捷径。

系统屏幕捕获授权仍必须由用户在系统界面决定。自动化没有点击“允许使用屏幕”等隐私按钮；模拟器只执行不需要捕获授权的原生合成帧冒烟测试和正式业务页面验证。

## 2. 已实现内容

### 2.1 Native/OpenCV 识别引擎

- 为 `arm64-v8a` 与 `x86_64` 链接 D 盘固定的 OpenCV 4.13；
- 读取并校验大端 `PTVFEAT2` 二进制格式，惰性缓存 1016 个模板；
- 按最大居中 16:9 画布映射 12 个 SafeZone ROI，`2772×1240` 输入得到与 Android 相同的 `2204×1240` 居中游戏画布；
- 复现严格/宽松颜色掩码、UI 伪影移除、连通域选择、GrabCut、CLAHE、方形归一化、灰度粗特征、边缘位图、HSV 直方图与感知哈希；
- 每个物种先保留最佳粗排结果，再取前 24 个物种精排；精排权重、已标注模板加成、置信度和分差风险阈值与 Android 一致；
- 当候选分差过小时启用自适应 GrabCut 回退；每槽返回三个候选、置信度、分差、风险和分阶段耗时；
- Native 接口异步运行并返回 Android 形状一致的 JSON，避免在 ArkUI 主线程执行 12 槽 OpenCV 计算。

### 2.2 复核、修正与对局建立

- 普通悬浮菜单增加“识别双方阵容”，正式对局页增加双方阵容复核入口；
- 复核页显示我方六槽和对手六槽的首选、置信度、分差、确认风险及前三个候选；
- 每槽可选择候选，也可按中文名、英文名或稳定 ID 搜索后人工替换；
- 12 槽均要求用户显式确认，低置信度或小分差不会自动冒充已确认；
- 必须选择物种集合与识别结果匹配且六只完整的已保存我方队伍，才能建立双打对局；
- 成功确认新阵容时原子保存预览与新对局并清理上一局；识别失败、空帧或保存失败时保留上一份有效状态。

正式产品文案只使用“前三个候选”等用户语言，没有暴露模板文件名、ROI、OCR 或测试图片等调试概念。

## 3. 固定相册样本门

`config/harmonyos-phase7-album-samples.json` 固定了既有回放工作树中的 8 张真实队伍预览 JPEG。自动化逐张检查：

- 文件仍位于用户既有的 `artifacts/phase4/candidate-previews`，移植工作树不复制或修改这些图片；
- 每张图片均为精确的 `2772×1240`；
- 文件大小和 SHA-256 与清单一致；
- 不根据文件名、槽位或固定队伍组合参与识别。

该门证明验收输入没有漂移，但不能代替“相册全屏 → 单窗口捕获 → Native 识别”的真实端到端结果。

## 4. 自动化、构建与模拟器结果

执行：

```powershell
npm.cmd run harmonyos:phase7:check
npm.cmd run harmonyos:phase7:emulator
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode release
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-packages.ps1 -BuildMode release
```

阶段纯逻辑与接线门共 5 项，覆盖 12 槽顺序、显式确认、手工替换持久化、V2 资源与 Native 管线接线、16:9 映射，以及 8 张固定图片的哈希和尺寸。

模拟器分别覆盖标准版与录屏功能版：

- Debug 验证页生成一张不依赖捕获权限的 `2772×1240` 合成 RGBA 帧；
- 真实调用 HAP 内 `libpcbridge.so`、读取 12.5 MB 模板并对 12 个 ROI 执行 x86_64/OpenCV 粗排与精排；
- 两个产品均返回 12 个非空候选列表，`NativeSmoke=PASS`；
- 实际打开对局入口与 12 槽复核页，检查候选按钮、风险提示、逐槽确认、保存队伍匹配和建立对局入口；
- 两个产品均通过，且 `PrivacyPromptClicked=False`；
- 结束后清理验证状态，重新安装标准版并回到正常首页。

可提交的 UI 层级证据为：

- `harmonyos/app/evidence/pc-stage7-standard-battle.json`；
- `harmonyos/app/evidence/pc-stage7-standard-review.json`；
- `harmonyos/app/evidence/pc-stage7-replay-battle.json`；
- `harmonyos/app/evidence/pc-stage7-replay-review.json`。

Release 包校验结果：

| 变体 | 字节 | SHA-256 |
| --- | ---: | --- |
| standard | 38,598,654 | `c0a3c5e40146bba240ed07d5006e178c944437b69daf67a9754188d941a7fe9d` |
| replay | 38,599,184 | `3ba3eb3c87fdd25db87c8dffbb2c70b6e8a81527aa9f7ba4517465bda25a97f3` |

两包包含 `arm64-v8a` 和 `x86_64`，包内容校验通过；仍为未配置发布签名的本地产物。构建日志保留 SDK 对 Native 模块静态验证、可能抛出异常与未签名配置等警告，这些警告没有被描述成不存在。

## 5. 未关闭的真实设备门

当前 DevEco 模拟器的 AVScreenCapture 会话不产生视频帧，因此本阶段没有伪造以下结论：

1. 8 张真实图片经系统相册全屏与单窗口捕获进入 Native 识别；
2. 每张图片 12 个槽位的真实物种/形态和候选排名均与人工真值一致；
3. 已有 PC/Android 数据集的正确答案在 HarmonyOS 结果中逐槽保持在前三候选；
4. 相册工具栏、切换动画、黑帧和非预览页在真实捕获链路中均不会覆盖有效状态；
5. 真机端到端耗时、峰值内存、连续重复稳定性和 `arm64-v8a` 数值差异。

有 HarmonyOS 真机后，应按主方案设置横屏 `2772×1240`，在系统相册中真正全屏打开 8 张图片，逐张执行捕获、12 槽候选核对、人工确认与对局建立，并记录每阶段耗时。任何错误候选或门槛退化都必须修复后重跑，不能以模拟器合成帧冒烟结果替代。

## 6. 阶段退出结论

阶段 7 的 Native/OpenCV 引擎、V2 模板读取、16:9 ROI 映射、12 槽候选与人工复核、新对局原子状态、双变体界面、双 ABI 构建和模拟器原生冒烟门均已完成，可以进入阶段 8。8 张真实相册图的端到端捕获与识别门继续保留到真机，不因继续开发而视为通过。
