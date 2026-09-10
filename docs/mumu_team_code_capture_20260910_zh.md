# MuMu 队伍 ID 抓包与网络复现记录

日期：2026-09-10。范围：Android 标准版 main 的协议调查；未修改助手运行时代码。

## 已验证结果

- MuMu Android 15，ADB `127.0.0.1:16384`，游戏 1.2.0 / Master Data v18。
- 安装系统 CA、处理 Conscrypt APEX 信任目录后，重启游戏进程，TLS 握手成功。
- `/auth/get-token`、`/auth/login`、`/api/common/regular-check`、`/api/trainingcode/search` 均收到 HTTP 200、官方 `code: 0`。
- 游戏查询 `QVQJM7H0XF` 成功；响应的 `pmc` 用既有 AES-CBC + gzip 算法解密，得到六只宝可梦、24 个招式和六个持有物。
- 既有 v18 映射成功转换出爱管侍（雄）、具甲武者、风妖精、赛富豪、烈咬陆鲨、沙奈朵（雌）。能力、性别、道具、招式、能力点与游戏“能力”“状态”两页核对一致。
- 使用本次捕获的临时会话，电脑上的独立诊断脚本重新加密请求并查询第二个公开码 `A4RBRNN9YE`，得到 HTTP 200、`code: 0` 和六名成员。没有将第一个样本的结果当作第二个码的结果。

这证明新版的队伍查询和数据解码仍可用，**不等于助手独立登录已恢复**。第二次查询依赖游戏刚建立的会话；会话材料不得内置进 APK、提交 Git 或视为长期凭据。

## 新版登录证据

成功请求中 `pmp.itok` 是非空的 927 字符字符串，`pmp.ikey` 是非空的九字符字符串；本次 Android 请求没有 `iass`。请求外层 `pmp` 与解密 `pmc` 得到的对象一致。

`itok` 为五段 JWE，头部为 `alg=A256KW`、`enc=A256GCM`。现有 1.2.0 APK 元数据包含 `AttestManager.Request(nonce)`、Classic/Standard Integrity 请求与 Google Play Integrity 客户端。当前助手的登录参数未实现这些动态证明字段。

以上是成功请求与实现差异，尚未通过单变量实验证明 `1001` 仅由哪一个字段触发，也未验证令牌可跨身份、跨应用或跨请求复用。Google 的完整性证明包含请求/应用等验证信息，不能把抓到的字符串当作普通版本常量补入助手：[Classic 请求说明](https://developer.android.com/google/play/integrity/classic)、[完整性结果字段](https://developer.android.com/google/play/integrity/verdicts)。

## 必须保留的正常联网配置

| 项目 | 本机已用配置 |
|---|---|
| Clash | 运行中，`allow-lan: true`，混合代理端口 `7890` 对非回环地址监听 |
| MuMu 网络 | 保留现有 Wi-Fi、IP、DNS；未启用桥接或重建 Wi-Fi |
| Android `http_proxy` | `10.0.2.2:7890` |
| `global_http_proxy_host` / `global_http_proxy_port` | `10.0.2.2` / `7890` |
| 排除列表 | `localhost,127.0.0.1,10.0.2.2` |
| PAC URL | 空字符串 |

不要把“结束抓包”误写成关闭 Clash 局域网或删除全部代理设置。本机正常游戏联网依赖原有的 Clash 代理路径，结束时应恢复上述基线。每次操作前重新读取当前配置，不能无条件覆盖用户后续修改。

## 本次抓包路径与证书

路径：MuMu 临时全局代理 `127.0.0.1:18890` → 指定 MuMu 的 ADB reverse → 电脑回环地址上的 mitmproxy → `127.0.0.1:7890` Clash → 官方服务器。mitmproxy 仅解密 `api.app.pokemonchampions.jp:443`；其他目的地只转发。

MuMu 已开启 root 设置，但初始 shell 是普通用户；执行 `adb -s 127.0.0.1:16384 root` 后取得权限，不需要改网络或重启模拟器。该版本没有普通 `su` 命令，不能仅据 `su` 不存在判定不支持 root。

CA 文件名为 `c8750f0d.0`。安装前核对本机 CA 的 subject hash 和 SHA-256；本次 CA 指纹为 `41DBD7B3F111349FFB699E654F31210B28C98FAD9EC1F1694E9A4B2700BCCF52`。

系统目录为 `/system/etc/security/cacerts`，但 Android 15 还使用只读的 `/apex/com.android.conscrypt/cacerts`。本次复制原有信任库并加入 CA，保留证书权限、所有者与 `system_security_cacerts_file` 标签，再 bind mount 到主命名空间、zygote 和游戏的挂载命名空间。不能用只含自定义 CA 的目录覆盖完整信任库。

安装后旧游戏进程首次 TLS 握手失败；重启游戏进程后握手和完整登录成功。APEX bind mount 是本次开机期间的临时挂载，模拟器重启后需要重新检查并配置，不能仅凭系统目录存在证书宣称抓包可用。

MuMu 采用多个显示屏。当前游戏的逻辑 display ID 为 7，物理截图 ID 为 `4619827052952829958`；这些不是稳定配置。操作前从 `dumpsys activity activities` 和 `dumpsys SurfaceFlinger --display-id` 重新确定目标。误选闲置显示屏会得到黑图，不能据此判断游戏黑屏。

## 本地证据与清理

本轮证据位于仓库外 `D:\crazylei12\.tmp\mumu-team-id-20260910`。`events.jsonl` 只记录路径、HTTP/API 结果与 TLS 状态；`team-*-mapped.json` 是公开队伍映射结果；能力和状态截图用于交叉核对。

原始 flow、解密登录信息和 Wi-Fi 配置备份仅保留在该本地诊断目录，不提交仓库。`restore-network.ps1` 按开始时保存的代理键值恢复配置，并只移除本任务的 `tcp:18890` reverse；随后停止本任务的 mitmproxy 进程。保留 Clash 局域网及正常出网代理，保留本次安装的抓包证书供后续调查使用。
