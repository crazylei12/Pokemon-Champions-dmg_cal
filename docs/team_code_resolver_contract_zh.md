# Pokémon Champions 公开队伍码 App 直连说明

日期：2026-09-05

## 2026-09-09 实机状态

以下直连成功记录适用于旧客户端 1.1.5。游戏 1.2.0 / Master Data v18 已在 OPD2409 平板核验：旧版请求获取令牌返回 9901；更新客户端版本后取令牌成功，登录返回 1001，尚未获得队伍响应。新版 APK 登录参数新增 `itok / ikey / iass`，并包含 Play Integrity / AttestManager；填空字段不能恢复登录。目前不能宣称独立直连可用，也不能仅凭 1001 断言完整性验证是唯一原因。

当前 APK 使用 v18 映射：396 个官方形态、516 个本应用支持的招式、216 个特性、155 个道具及 25 个性格。全部旧 v17 映射保持一致；这属于数据更新，不是在线查询验收通过。原始游戏数据保留在仓库外，只提交映射和 SHA-256 来源记录。

生成顺序：`node tools/team-code-resolver/generate-species-map.mjs <personal.json>`，然后 `node tools/team-code-resolver/import-master-data.mjs <解密数据目录>`。目录须包含 `MdListMeta`、`personal.json`、`waza.json`、`tokusei.json`、`item.json`、`waza_learn.json`。不得用手工样例响应冒充官方查询结果。

查询失败时明确区分版本不支持（9901）、登录参数不兼容（1001）、设备完整性失败（11200）和无效队伍码；失败不保存队伍。画面识别仍可作为独立入口使用。

## 1. 运行边界

游戏内十位公开队伍码是官方服务器上的查询键，无法离线还原。Android App 在用户点击“解析并预览”后，直接通过系统 HTTPS 连接 `api.app.pokemonchampions.jp`，依次取得一次性令牌、建立短期会话并查询公开码。

运行时不需要启动 Pokémon Champions，不需要电脑端服务、ADB reverse、代理软件、抓包证书或自定义 DNS。App 不读取或注入游戏进程，也不拦截游戏流量。

## 2. 身份与数据处理

- APK 内置一个专用于公开队伍查询的匿名游客身份 UUID。它是账户标识，不是可复用的登录令牌。
- 每次查询都会重新取得官方令牌并建立新会话。令牌、Cookie、会话 ID 和加密中间数据只保留在当前请求的内存中，不写入设置、队伍记录、备份或日志。
- App 只把用户输入的十位公开码放进官方查询请求。截图、本地队伍库、对局状态和伤害计算数据不会随请求上传。
- 保存记录前必须展示六只宝可梦供用户核对；查询结果不会静默写入。

游客身份可通过 Gradle 参数 `-PteamCodeGuestUuid=<42 位 UUID>` 或环境变量 `POKEMON_CHAMPIONS_TEAM_CODE_GUEST_UUID` 替换。默认构建已包含项目维护者明确授权使用的匿名游客身份，因此正常构建和安装不需要额外配置。

## 3. 协议与映射

客户端版本参数、请求哈希、gzip + AES-CBC 封装及响应解密由 `OfficialTeamCodeProtocol.kt` 实现。所有网络目标均固定为官方 HTTPS 主机和以下三个路径：

- `/auth/get-token`
- `/auth/login`
- `/api/trainingcode/search`

官方响应中的宝可梦形态、招式、特性、道具和性格使用数字编号。构建时会校验并打包 `tools/team-code-resolver/data/champions-entity-map.v17.json`；当前覆盖 Master Data v17 的 361 个宝可梦形态，并包含 App 支持的全部对应实体。映射不是针对样例码编写的特例。

如果官方升级客户端协议或 Master Data，App 会把未知编号报告为“可能需要更新 App 数据”，不会猜测或保存错误配置。

## 4. 错误与验收

- 输入必须是十位英文字母或数字，允许粘贴内容中带空白并自动转为大写。
- 官方错误码 `31502`、缺失队伍内容或非六人公开队伍统一视为“公开码不存在或已失效”。
- 网络超时、HTTP 错误和其他官方 API 错误与“队伍码不存在”分开提示。
- `npm.cmd run team-code:test` 校验两份独立公开样本、完整形态覆盖以及跨 Node/Android 的协议加密向量。
- Android 单元测试使用内存中的假官方传输层完整走过“取令牌 → 登录 → 查询 → 数字映射”，不依赖电脑端 HTTP 服务。

最终设备验收必须在游戏进程关闭、Android 全局代理为 `:0`、没有 ADB reverse 的条件下，由安装后的 App 自己查询真实公开码。
