# Pokémon Champions 公开队伍码解析服务契约

日期：2026-09-05

## 1. 边界

游戏内十位公开码是官方服务器上的查询键，不能离线还原队伍。Android App 只向本项目配置的解析服务发送用户主动输入的公开码；官方协议、专用游戏会话、响应解密和数字 ID 映射均留在隔离的服务端适配层。个人账号凭据、会话、Cookie 和协议密钥不得写入 APK、响应或日志。

解析服务只返回公开队伍。它不得接收截图、本地队伍库、对局状态或伤害计算数据，并应设置请求限速、短期无敏感信息缓存和官方协议失效监控。

## 2. HTTP 请求

```http
POST /v1/team-codes/resolve
Content-Type: application/json; charset=utf-8

{"schemaVersion":1,"code":"A4RBRNN9YE"}
```

正式地址必须使用 HTTPS。客户端不会跟随重定向。成功响应上限为 512 KiB。

## 3. 成功响应

```json
{
  "schemaVersion": 1,
  "kind": "PokemonChampionsPublicTeam",
  "code": "A4RBRNN9YE",
  "trainerName": "公开训练家名",
  "members": [
    {
      "speciesId": "Dragonite",
      "level": 50,
      "gender": "female",
      "natureId": "Modest",
      "abilityId": "Multiscale",
      "itemId": "Dragoninite",
      "statPoints": { "hp": 2, "atk": 0, "def": 0, "spa": 32, "spd": 0, "spe": 32 },
      "moveIds": ["Dragon Pulse", "Heat Wave", "Extreme Speed", "Protect"]
    }
  ]
}
```

`members` 必须恰好为六项，并保持游戏队伍顺序。所有实体 ID 使用项目现有 Showdown ID；服务端负责把官方数字 ID 和形态编号映射到稳定 ID。每项能力点为 `0..32`，合计不超过 `66`；招式为一至四个且不可重复。无持有物时省略 `itemId`。

## 4. 错误状态

- `400`：请求或十位码格式无效。
- `404` / `410` / `422`：公开码不存在、已删除或已失效；客户端提示用户检查队伍码。
- `429`：请求过于频繁。
- 其他 `5xx`：解析服务或官方上游暂不可用；客户端不会把它误报为“队伍码不存在”。

错误正文可以使用 `{"error":{"code":"TEAM_CODE_NOT_FOUND"}}`，但客户端只依据 HTTP 状态显示固定、可控的中文提示。

## 5. 构建配置与本地验收

正式构建通过环境变量 `POKEMON_CHAMPIONS_TEAM_CODE_RESOLVER_URL` 或 Gradle 参数 `-PteamCodeResolverUrl=https://...` 写入固定解析地址。未配置时首页入口仍可见，但解析按钮禁用并明确说明原因。

`tools/team-code-resolver/mock-server.mjs` 是不访问官方服务的开发验收夹具，仅收录已经人工核对的公开样本，不是生产解析器。雷电等支持 ADB reverse 的模拟器可运行：

```powershell
npm.cmd run team-code:mock
adb -s emulator-5554 reverse tcp:8765 tcp:8765
```

并用 `-PteamCodeResolverUrl=http://127.0.0.1:8765/v1/team-codes/resolve` 构建 Debug APK。Android Studio 模拟器也可把夹具监听到开发机网络后使用 `10.0.2.2`。客户端只在 Debug 构建中允许 `localhost`、`127.0.0.1`、`10.0.2.2` 或 `10.0.3.2` 的明文 HTTP；Release 始终要求 HTTPS。
