# 手机验收：目录修复与 PS 队伍导入导出

日期：2026-09-15。Android 标准版 main，包含 25d6a24、fa863bb、d807486 及本记录同提交的性别兼容修复。

## 设备与安装

- 真实手机 RMX3820，ADB 序列号 `6465e08`，当前用户 0，支持 arm64-v8a。
- 安装 `com.crazylei12.pokemonchampionsassistant.debug`，版本 `1.1.11-debug` / 16；正式版应用及其数据保留。
- 手机既有调试包属于其他用户，签名与工作树新调试密钥不一致。先读取既有 APK 的证书，确认其 SHA-256 与本机用户目录的旧调试密钥一致，再使用该密钥签署当前 APK，`install --user 0 -r -t -g` 返回 Success。没有卸载、清空数据或修改项目签名配置。
- 最终安装包 SHA-256：`3afd5dc1e6ab98b645ca9286bdc2a24a780aba243c134ace1d2e69a4a20c5ae0`。从手机拉取安装后的 base.apk，哈希与待安装包完全一致。冷启动成功。

## 实际界面验收

| 项目 | 操作与结果 |
| --- | --- |
| 游戏码解析 | 手机助手正常入口在线查询 `61V6V4S9RX`，返回完整六人预览；没有注入假响应 |
| 游戏码预览导出 | 无需先保存，从预览点击「导出 PS 码」，弹窗包含完整六人文本，复制全部成功 |
| PS 导入 | 粘贴刚复制的文本，Champions 模式解析出相同六人及能力点、性格、特性、道具、招式、实际能力值 |
| 保存与重启 | 命名 QAFINAL0915 后保存，首页立即出现；强制停止应用并冷启动后仍可读取 |
| 保存队伍导出 | 从 QAFINAL0915 卡片导出；提取完整界面文本并与游戏码导出逐字比较，一致，含三只雄性和三只雌性 |
| 错误输入 | 输入 INVALID 并解析，显示缺少名称或 Ability 特性，不出现可保存预览、不增加队伍 |
| EV 模式 | 切换传统 EV 模式后显示近似换算提醒；原样例的 32 EV 得到 4 能力点。切回 Champions 模式重新解析恢复 32 能力点。未保存传统换算测试副本 |
| 电光双击 | 自由计算中选择巴布土拨，详细调整的招式搜索 DOUBLE 可找到中文电光双击并选择；对无加点红莲铠骑计算得到 76–91 HP / 47.5%–56.9% |
| 复生祈祷 | 同一宝可梦的招式搜索 REVIVAL 可找到并选择；计算结果为 0 HP，并明确显示「无直接伤害」 |
| 甲贺忍蛙特性 | 详细调整的特性菜单只列未设置、变幻自如、激流，没有牵绊变身 |
| 形态中文入口 | 物种搜索可见一家鼠、一家鼠-四只家庭；甲贺忍蛙与超级甲贺忍蛙分别显示 |

本次仅使用界面点击、系统输入/剪贴板粘贴完成以上流程；保存文件未由脚本直接构造或写入。手机中的初次 QA－PS－0915 测试队伍已通过界面删除，仅保留 QAFINAL0915 供用户查看。

## 验收中修复

官方游戏码的性别字符串为 `male/female/genderless`，PS 使用 `M/F`。初轮发现游戏码导出漏掉性别，新增统一转换，覆盖游戏码映射、保存序列化、旧记录读取和 PS 导出；无性别/未知不编造 M/F。

增加单元回归覆盖 male、female、genderless、unknown 的导出及重载。重建并覆盖安装后，重新在线查询并完整走过复制、PS 导入、保存、冷启动、导出，最终全部文本一致，性别正确保留。

## 自动验证与边界

- `npm.cmd test`：35 项引擎/目录回归、8 项队伍码协议回归通过；类型、版本、许可证检查通过。
- 最终 `:app:testDebugUnitTest :app:assembleDebug`：158 项 JVM 测试通过，无失败或跳过；两种单 ABI Debug APK 构建成功。
- 最终 APK ZIP 检查：arm64-v8a 80.93 MiB，x86_64 115.11 MiB，均只有指定 ABI。供手机安装的同内容兼容签名副本为 80.98 MiB。
- 手机验收是上述真实路径和代表性数据的测试；512 个招式、396 个形态等完整覆盖由自动目录回归证明，未逐个在手机手点全部实体，也未重新执行截图 OCR 或实战对局识别。
- 没有发布版本。x86_64 产物没有安装到模拟器。

本机证据位于 `.tmp/phone-acceptance-20260915/`（Git 忽略）：手机 PNG、界面 XML、修复前后导出文本、build-verification.json 及安装包。关键画面已逐张打开确认，包括 game-code-ps-gender-fixed.png、saved-ps-final.png、double-shock-result.png、revival-result.png、greninja-abilities.png。


## 本机 APK 路径

- Debug / arm64-v8a / 80.93 MiB：`D:\crazylei12\pokemon-champions-assistant-main-safe-area\android-app\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk`
- Debug / x86_64 / 115.11 MiB：`D:\crazylei12\pokemon-champions-assistant-main-safe-area\android-app\app\build\outputs\apk\debug\app-x86_64-debug.apk`
- 已安装的兼容签名 Debug / arm64-v8a / 80.98 MiB：`D:\crazylei12\pokemon-champions-assistant-main-safe-area\.tmp\phone-acceptance-20260915\app-arm64-phone-debug.apk`
