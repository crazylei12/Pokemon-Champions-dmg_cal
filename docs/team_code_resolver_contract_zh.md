# Pokémon Champions 公开队伍码 App 直连说明

日期：2026-09-05

## 2026-10-21 游戏 1.2.2 登录适配

当前官方接口在全局参数 `pla=2` 的登录分支只校验 `itok` 非空，不请求 Google Play Integrity。Android 标准版因此改用该登录分支并发送非空占位值，保留原有匿名游客、一次性令牌、加密封装和三段官方 HTTPS 查询流程。

MuMu Android 15 在 Google Play Integrity 返回 `-9` 的同一环境中，旧 `pla=3` 流程停在 `/auth/get-token` 后；改用 `pla=2` 后，`/auth/get-token`、`/auth/login`、`/api/trainingcode/search` 均收到 HTTP 200、官方 `code: 0`，公开码 `A4RBRNN9YE` 返回训练家“ワトソン”及完整六人配置。该验收直接运行 Android 客户端实现，没有启动游戏、代理抓包或复用完整性证明。

## 2026-09-10 应用独立直连修复

Android 标准版已接入 Play Integrity 1.6.0。每次取得官方 CSRF 令牌后，将其 UTF-8 字节编码为无填充 URL-safe Base64 nonce，由本应用生成真实证明，将 `itok` 和 `ikey=Integrity` 一起加入登录明文及加密内容。本流程不复用游戏的认证字符串，也不需要常驻查询服务器。

MuMu 上已用助手正常“解析并预览”入口分别查询 `QVQJM7H0XF` 和 `A4RBRNN9YE`，均展示完整六人配置；游戏进程关闭、没有 ADB reverse 和抓包转发。此前游戏 1.2.0 的登录 1001 是未修复状态，已由本次完整性接入后的成功结果取代。网络配置及验收详情见 [MuMu 调查记录](mumu_team_code_capture_20260910_zh.md)。

2026-09-15 修复后的标准版源码使用 v18 映射：396 个官方形态、526 个招式编号（覆盖全部 512 个当前可用招式并保留历史兼容编号）、216 个特性编号（其中 215 个为客户端实际分配）、166 个道具及 25 个性格。旧 v17 编号含义保留，只有审计确认的盾牌坚盾剑怪与两种一家鼠共三处错误作明确纠正。映射覆盖验证与设备在线验收分别记录。原始游戏数据保留在仓库外，只提交映射、所需目录与 SHA-256 来源记录。本轮修复尚未安装到设备，详见 [全量招式审计](move_catalog_audit_2026-09-15_zh.md)及[实体目录与形态关联审计](catalog_audit_2026-09-15_zh.md)。

v18 生成顺序：先运行 `generate-species-map.mjs <personal.json>`，再运行 `sync-champions-moves.mjs <解密数据目录>`（二者均位于 `tools/team-code-resolver/`）；随后运行 `tools/android/prepare-champions-calc.mjs`、`tools/localization/sync-zh-hans.mjs`，再运行 `tools/team-code-resolver/import-master-data.mjs <解密数据目录>` 和 `npm.cmd test`。以上 `.mjs` 使用 `node` 执行。目录须包含 `MdListMeta`、`personal.json`、`waza.json`、`tokusei.json`、`item.json`、`waza_learn.json`。仅变更客户端版本号时由运行时自动适配；Master Data 或实体内容变化时仍须更新数据与来源哈希锁。不得用手工样例响应冒充官方查询结果。

查询失败时明确区分版本自动适配失败（9901）、登录参数不兼容（1001）、服务端认证失败（11200）和无效队伍码；失败不保存队伍。画面识别仍可作为独立入口使用。

## 1. 运行边界

游戏内十位公开队伍码是官方服务器上的查询键，无法离线还原。Android App 在用户点击“解析并预览”后，直接通过系统 HTTPS 连接 `api.app.pokemonchampions.jp`，依次取得一次性令牌、建立短期会话并查询公开码。

运行时只需要能连接官方接口的网络，不依赖 Google Play 商店或 Play Integrity。不需要启动 Pokémon Champions、电脑端查询服务、ADB reverse 或抓包证书；本机 MuMu 保留现有 Clash 出网代理。App 不读取或注入游戏进程，也不拦截游戏流量。

正常查询只访问官方游戏接口。仅当官方接口以 `9901` 拒绝当前客户端版本时，App 才访问 Apple 公开的 iTunes Lookup API，并严格核对固定 App ID `6741503079`、Bundle ID `jp.pokemon.pokemonchampions` 和纯数字点分版本号。解析到的新版本使用全新会话重试一次；只有完整查询协议已经成功时才缓存为下次首选版本。App 不枚举版本号，也不会自动跟随 `9900` 返回的其他环境地址。

## 2. 身份与数据处理

- APK 内置一个专用于公开队伍查询的匿名游客身份 UUID。它是账户标识，不是可复用的登录令牌。
- 每次查询都会重新取得官方令牌并建立新会话。令牌、Cookie、会话 ID 和加密中间数据只保留在当前请求的内存中，不写入设置、队伍记录、备份或日志。
- App 只把用户输入的十位公开码放进官方查询请求。截图、本地队伍库、对局状态和伤害计算数据不会随请求上传。
- 保存记录前必须展示六只宝可梦供用户核对；查询结果不会静默写入。

游客身份可通过 Gradle 参数 `-PteamCodeGuestUuid=<42 位 UUID>` 或环境变量 `POKEMON_CHAMPIONS_TEAM_CODE_GUEST_UUID` 替换。默认构建已包含项目维护者明确授权使用的匿名游客身份，因此正常构建和安装不需要额外配置。

## 3. 协议与映射

客户端版本参数、请求哈希、gzip + AES-CBC 封装及响应解密由 `OfficialTeamCodeProtocol.kt` 实现；版本解析、响应身份校验和本地版本缓存由 `TeamCodeClientVersion.kt` 实现。队伍协议请求固定为官方 HTTPS 主机和以下三个路径：

- `/auth/get-token`
- `/auth/login`
- `/api/trainingcode/search`

官方响应中的宝可梦形态、招式、特性、道具和性格使用数字编号。构建时会校验并打包 `tools/team-code-resolver/data/champions-entity-map.v18.json`；当前覆盖 Master Data v18 的 396 个宝可梦形态，并包含 App 支持的全部对应实体。映射不是针对样例码编写的特例。

如果游戏仅提升客户端版本、而现有查询协议和实体编号仍兼容，App 会自动使用当前版本重试并继续展示队伍。Master Data 版本号变化本身不会提前拦截查询；服务端返回的队伍仍须逐项通过本地宝可梦形态、招式、特性、道具和性格映射。出现未知编号、响应结构变化或新版协议仍被拒绝时，App 才报告需要更新，不会猜测或保存错误配置。某支队伍包含未知实体时只拒绝该队伍；已经由服务器接受的客户端版本仍会保留，避免后续兼容队伍重复探测版本。

## 4. 错误与验收

- 输入必须是十位英文字母或数字，允许粘贴内容中带空白并自动转为大写。
- 官方错误码 `31502`、缺失队伍内容或非六人公开队伍统一视为“公开码不存在或已失效”。
- 网络超时、HTTP 错误、版本元数据不可用和其他官方 API 错误与“队伍码不存在”分开提示。
- `npm.cmd run team-code:test` 校验两份独立公开样本、完整形态覆盖以及跨 Node/Android 的协议加密向量。
- Android 单元测试使用内存中的假官方传输层完整走过“旧版本被拒绝 → 解析当前版本 → 取令牌 → 登录 → 查询 → 严格数字映射 → 缓存已接受版本”，并覆盖缓存直用、未知实体拒绝及版本元数据身份校验，不依赖电脑端 HTTP 服务。

最终设备验收必须在游戏进程关闭、保留设备正常出网配置、没有抓包转发和 ADB reverse 的条件下，由安装后的 App 自己查询真实公开码。
