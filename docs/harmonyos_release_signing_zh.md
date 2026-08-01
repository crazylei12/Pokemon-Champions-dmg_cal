# HarmonyOS Release 签名与证书连续性

正式 Release 构建必须使用同一套 HarmonyOS 发布证书、Provision Profile 和密钥库。仓库不保存密码、私钥、`.p12`、`.p7b` 或本机签名配置；缺少正式材料时，Release 构建必须失败，不能退化为 unsigned HAP。

## 本机配置

1. 在 DevEco Studio 的 `Project Structure > Project > Signing Configs` 中配置正式发布签名，取得 `.cer`、`.p7b`、`.p12`、密钥别名以及 DevEco 生成的加密密码字段。
2. 复制 `config/harmonyos-release-signing.example.json` 为 `harmonyos/app/signing.local.json`，填入真实值。该文件已被 Git 忽略。
3. 对 `.cer` 文件计算 SHA-256，并把 64 位小写十六进制值写入 `expectedCertificateSha256`。后续版本不得无意更换该值；确需轮换证书时必须走独立发布决策和升级验证。
4. 设置 `HARMONY_SIGNING_CONFIG` 指向该本机 JSON，再构建 Release：

```powershell
$env:HARMONY_SIGNING_CONFIG = 'D:\crazylei12\pokemon-champions-assistant-harmonyos\harmonyos\app\signing.local.json'
npm.cmd run harmonyos:assemble-release
```

构建脚本会先校验材料存在、证书文件 SHA-256 与固定指纹一致，然后临时向 Hvigor 注入签名方案。无论构建成功或失败，受版本控制的 `build-profile.json5` 都会按原始字节恢复。正式产物只接受 `*-release-signed.hap`；unsigned、证书不匹配或签名验签失败都会使任务失败。

## 发布验收

同一个 bundle 的 standard 与 replay 必须由同一证书签名。发布前至少保留以下证据：

- 两个 HAP 的签名校验均成功，且叶证书 SHA-256 都等于 `expectedCertificateSha256`；
- 包内 bundle、versionName、versionCode、product、ABI、许可文件和变体负向断言通过；
- ARM64 真机上使用递增 versionCode 完成同签名覆盖升级，队伍、预设、会话、草稿和更新频道均保留；
- 不同签名、降级 versionCode 和错误变体候选被系统或应用发布门拒绝。

真机上的系统安装确认必须由用户操作，自动化只负责构建、安装前后检查和证据采集。
