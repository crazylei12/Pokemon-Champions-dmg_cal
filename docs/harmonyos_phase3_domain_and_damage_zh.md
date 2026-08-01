# HarmonyOS 阶段 3：共享领域模型与伤害运行时验收记录

- 日期：2026-08-01
- 分支：`feature/harmonyos-port`
- 正式工程：`harmonyos/app`
- 阶段状态：**已完成**

本阶段移植原应用可独立于界面运行的领域规则，并把 Android 使用的固定 Smogon JavaScript 伤害引擎接到 ArkWeb。本阶段没有把测试入口暴露为产品功能，也没有提前声明自由计算、对局助手或存储界面已经完成；这些属于阶段 4、5 和 8。

## 1. 已实现范围

领域层位于 `harmonyos/app/entry/src/main/ets/domain`，包含：

- 稳定实体 ID、Showdown ID、简体中文显示名和别名查找；
- 宝可梦、招式、特性、道具、形态、配置、能力值、速度区间和对局条件模型；
- 我方已确认招式优先于静态合法招式池，且不会因快照缺招而丢失用户确认招式；
- 对手配置顺序为用户保存、生成模板、开放来源预设；
- 仅按 Android 既有规则共享指定形态配置，目标形态不支持原特性时回退到目标默认特性；
- 从实际能力值反推现有加点/性格并映射形态能力值；
- 单打/双打默认值、天气、场地、帮助加成和范围招式约束；
- 伤害引擎元数据校验、响应投影和错误传播。

`RuntimeDataRepository.ets` 从 HAP 的 rawfile 读取真实资源，不依赖测试目录或网络。运行时校验预设 schema、Champions 规则集版本和数据来源后，提供本地化、形态、合法招式、预设与速度区间查询。

## 2. 固定数据与规则边界

模拟器从正式 HAP 读取并通过以下检查：

| 项目 | 实际值 |
| --- | ---: |
| 简体中文本地化条目 | 1,219 |
| 物种 | 163 |
| 对手配置 | 645 |
| 形态共享组 | 97 |
| 物种形态 | 324 |
| 规则集 | `pkmn-mods-champions-0.10.11` |
| 合法招式池来源 | `CHAMPIONS_SNAPSHOT` |

额外固定用例验证：

- `Mawile` 可解析并显示为“大嘴娃”；
- 大嘴娃和超级大嘴娃按既有规则共享用户配置；
- 超级大嘴娃默认特性回退为 `Huge Power`；
- 姆克鹰合法池包含 `Blaze Kick`，不包含 `Toxic`；
- 我方配置中的 `Knock Off` 即使不在传入的残缺合法池中仍保留。

## 3. Android/HarmonyOS 同输入结果

`phase3-domain.test.mjs` 通过 esbuild 直接编译正式 ArkTS/TypeScript 领域源码，并调用与 Android 打包内容逐字节相同的 `damage-engine.js`。共 7 组测试全部通过。

关键黄金结果如下：

| 用例 | 结果 |
| --- | --- |
| 我方大嘴娃 `Play Rough` → 对手红莲铠骑 | 32–38，19.9%–23.6%，5HKO |
| 对手红莲铠骑 `Flash Cannon` → 我方大嘴娃 | 48.4%–57.3%，方向为 `OPPONENT_TO_OWN` |
| 反射壁 | 16–19，9.9%–11.8%，9HKO |
| 会心 | 48–57，29.8%–35.4%，3HKO |
| 双打范围修正 | 24–28，14.9%–17.4%，6HKO |
| 双打、会心、范围、帮助加成组合 | 54–63，33.5%–39.1%，3HKO |

测试还覆盖稳定 ID、非拉丁文本、重复招式去重、形态共享边界、默认特性、配置顺序、速度范围、实际能力值变形、对局缺省值、无效枚举回退和一位小数精度。

## 4. ArkWeb 模拟器运行验收

Debug 包保留一个没有产品入口的 `Stage3Verification` 页面，且只有 `DEBUG` 构建收到显式 Want 参数时才加载。`verify-stage3-runtime.ps1` 会安装标准版 Debug HAP、启动该页、收集日志和 UI 树，然后强制结束并恢复普通首页。

在 `127.0.0.1:5555`、HarmonyOS API 24 模拟器上的最新结果：

- 连续 100 次相同请求全部得到逐字段相同结果；
- 总用时 720 ms；
- 引擎版本 `pokemon-champions-smogon-0.11.0-3677e41`；
- 引擎报告 `offline=true`；
- 注入无效 JSON 后正确拒绝，下一次有效计算恢复成功；
- UI 状态为 `PASS 100 720`；
- 同一运行中真实资源仓库校验通过。

正式模块未申请 `ohos.permission.INTERNET`，ArkWeb 宿主页只引用同包内的 `damage-engine.js`，不存在 HTTP/HTTPS 地址。阶段 3 的领域与伤害功能因此可以离线运行；用户主动检查更新所需网络仍属于阶段 5。

## 5. 构建与包校验

领域层接入后，标准版和录屏版的 Debug、Release 模式都完成干净编译。包校验确认 bundle、版本、产品标签、双 ABI Native 库和 7 个运行时资源哈希。

| 模式 | 产品 | 大小（bytes） | SHA-256 |
| --- | --- | ---: | --- |
| Debug | 标准版 | 25,279,895 | `d601f2662051fc97581189ab5aed9ce097c9c2ad06a69043a324267b1eee09f5` |
| Debug | 录屏版 | 25,279,881 | `bea68428157e76adca9863dbc738f77ba6a6061b161839b0b8f4d02ed7c5f9e7` |
| Release | 标准版 | 25,222,196 | `f241e918e29ba22064a802760e2c99ebeb7bd0d69bfabc79e7f1d5db3bfa18c4` |
| Release | 录屏版 | 25,222,194 | `88d7ec2871b64ca25fef90d9a93b0c98ef9d8893bc0f7c7fedc291c9cc523518` |

这些 Release HAP 仍未签名；这里证明 Release 编译和包内容，不代表发布签名或真机安装通过。Hvigor 的本地 NAPI 类型、可抛函数、弃用上下文 API和 Release 依赖扫描提示均为已记录警告，没有掩盖编译或运行失败。

## 6. 复现命令

```powershell
npm.cmd run harmonyos:phase3:check
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -Clean
npm.cmd run harmonyos:verify
npm.cmd run harmonyos:phase3:emulator

powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/build-app.ps1 -Variant all -BuildMode release -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File tools/harmonyos/verify-app-packages.ps1 -BuildMode release
```

模拟器运行证据位于 `harmonyos/app/evidence/pc-stage3-verification.json`；过滤后的日志在同目录 `.log` 文件中，按仓库规则忽略。脚本每次执行都会重新生成证据。

## 7. 阶段结论与下一门

阶段 3 的领域模型、真实数据读取、双向伤害、对局修正、离线 ArkWeb 运行、连续调用和异常恢复门全部通过，可以进入阶段 4 的本地存储、备份、分享与 Android 数据迁移。

仍未关闭的项目不属于本阶段：正式发布签名、真实 arm64 设备、阶段 1 的真机媒体能力，以及阶段 4–9 的存储、完整界面、识别、悬浮窗和录屏业务。模拟器结果不会替代这些后续门。
