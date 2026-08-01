# HarmonyOS 阶段 10：最终模拟器验收与发布准备记录

日期：2026-08-01

状态：**阶段 0–10 的实现、自动化、模拟器可验证范围和候选包构建均已完成；完整相册黑盒验收仍被无真机阻塞，因此不得签署“全部验收通过”或“可发布”**

应用源码候选提交：`bbdf55c433fc6f7c9b3ce0e7e6fb8c179bbd3ebe`

## 1. 本阶段完成内容

- 将阶段 0 冻结的 66 项功能逐项登记到 `config/harmonyos-phase10-acceptance.json`，每项都给出实现状态、验收结论和证据；
- 66 项实现均为 `COMPLETE`，当前环境中 38 项 `PASS`、28 项 `BLOCKED`、0 项未测；
- 新增阶段 10 结构检查，防止遗漏、重复、无证据、模糊状态或把未签名包写成发布包；
- 新增 `tools/harmonyos/verify-stage10-final.ps1`，可复现地运行阶段 0、2–10 测试、阶段 3–9 模拟器门、双变体干净 Debug/Release 构建及包校验；
- 重新对照 Android 录屏契约，补齐三档 H.264 能力降级和内部音频不可用时的明确“无声继续/取消”决定，不做静默降级；
- 全程没有自动点击屏幕捕获、媒体库保存或其他隐私决定。

## 2. 自动化与模拟器结果

阶段 0、2–10 共 59 项 Node 回归全部通过。最终候选源码还完成了以下实际模拟器检查：

| 阶段 | 结果 | 关键证据 |
| --- | --- | --- |
| 3 伤害与目录运行时 | PASS | 100 次 ArkWeb 伤害调用及目录加载通过 |
| 4 存储与跨变体 | PASS | 原子写入、恢复保护及 replay 覆盖安装保留数据通过 |
| 5 正式主界面 | PASS | 两变体首页、配置管理、真实本地计算、对局和设置入口通过 |
| 6 我方队伍流程 | PASS（UI/逻辑） | 两变体纠正页与百变怪规则通过；真实取帧/OCR 被设备能力阻塞 |
| 7 队伍预览 | PASS（Native/逻辑） | x86_64 Native 冒烟、12 槽审阅和两变体页面通过；真实取帧被阻塞 |
| 8 面板与 HUD | PASS（UI/逻辑） | 两变体 `Panel/HudDamage/SingleDouble/HideRestore` 全部 PASS |
| 9 录屏功能版 | 部分 PASS | `Routes=PASS`、`ProductGate=PASS`；模拟器无 H.264 encoder，`CodecPrepare=BLOCKED_BY_EMULATOR` |

各阶段的 `PrivacyPromptClicked` 均为 `False`。阶段 6–9 的脚本只验证不会代替用户作出系统隐私决定。

## 3. 最终候选包

双变体均从干净 Release 构建产生，版本为 1.1.4（versionCode 9），bundle 为 `com.crazylei12.pokemonchampionsassistant`，同时包含 `arm64-v8a` 与 `x86_64`：

| 变体 | 文件 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| standard | `harmonyos/app/dist/pokemon-champions-standard-release-unsigned.hap` | 38,874,743 | `a0f419c56818fbc1ef9446a1c9bf8c26a8abe3d21d33776e7efaba033c94c459` |
| replay | `harmonyos/app/dist/pokemon-champions-replay-release-unsigned.hap` | 38,875,405 | `a9649b59f31765803e5ee35d27868abd8b4fa632cc1bebc35dda1799f021af80` |

包结构、资源、产品标记和双 ABI 校验通过。项目没有发布签名配置，所以这两个文件是本地未签名候选，不是可分发发布包。

## 4. 66 项验收结论

详细逐项记录以 `config/harmonyos-phase10-acceptance.json` 为准：

- 38 项 PASS：伤害与数据规则、主界面业务、存储/迁移、预设、人工修正、离线/Native 队伍预览、对局状态、面板/HUD 逻辑和更新选择；
- 28 项 BLOCKED：真实单窗口截图帧、Core Vision 相册 OCR、浮窗真实触摸/旋转/合成隔离、H.264/AAC MP4、内部播放音频、媒体库确认、异常录制和 30 分钟稳定性；
- 0 项未测：阻塞项均写明所需设备、阻塞原因和已有实现证据，没有以模拟器页面或编译成功冒充真机通过。

因此当前准确结论是：**功能移植实现完成，模拟器范围验收完成，完整产品验收未完成。**

## 5. 有真机后的唯一剩余验收批次

1. 把 12 张我方两页 OCR 图、8 张双方队伍预览图和固定本地音视频导入真机相册；
2. 把设备横屏渲染分辨率设为 `2772×1240`，图片全屏并隐藏相册工具栏，只在系统选择器中授权相册窗口；
3. 完成六组两页 OCR、八张 12 槽队伍预览、人工确认、面板、HUD、隐藏恢复和再战；
4. 再用一组非 1:1 分辨率复测最大居中 16:9 映射、浮窗安全区和旋转恢复；
5. 对录屏功能版分别验证“识别并录屏”“仅识别”“仅录屏”，核对三档视频规格、96 kbps AAC、内部音频、明确无声回退、MP4 回放与媒体库保存；
6. 验证权限拒绝/撤销、空间不足、系统停止、强杀、未完成文件清理和连续 30 分钟稳定性；
7. 配置发布签名后重新干净构建，并做标准版与录屏版覆盖安装和数据保留复测。

这些步骤中的屏幕捕获和保存到相册确认必须由设备使用者在系统界面决定；自动化可继续执行前后步骤并收集证据，但不会代点授权。

## 6. 复现命令

完整流程：

```powershell
npm.cmd run harmonyos:phase10:emulator
```

仅检查最终矩阵：

```powershell
npm.cmd run harmonyos:phase10:check
```

若暂时没有模拟器，可使用 `-SkipEmulator`；若只复核现有构建和证据，可按脚本参数使用 `-SkipBuild`。跳过项不会改变矩阵中对应的真机阻塞结论。
