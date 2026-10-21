# 1.1.13 发布说明（Android 标准版）

日期：2026-10-21

版本 **1.1.13 (18)**，要求 Android 13+ / ARM64。

## 公开队伍码查询恢复

- 按当前官方客户端协议发送平台参数与非空完整性字段，查询流程不再依赖 Google Play Integrity 证明。
- 服务器返回 `9901` 时会核验并重试当前游戏版本；服务器已接受的版本会在本机缓存复用。
- MuMu Android 15 已验证 `/auth/get-token`、`/auth/login`、`/api/trainingcode/search` 与六成员解码完整链路，测试码成功返回六只宝可梦。
- 只有协议本身或队伍中的实体编号确实不兼容时才拒绝导入；离线 PS 文本导入、截图识别和伤害计算不受网络状态影响。

## 构建依赖安全更新

- sharp 从 0.35.4 升级至 0.35.5，修复其 librsvg 依赖的高危安全公告 `GHSA-wq5f-xc86-pv6w`。
- `npm.cmd audit --omit=dev` 已恢复为 0 vulnerabilities。

## 致谢

特别感谢 [Pedro Soares（silph-scope.com）](https://silph-scope.com/) 提供相关问题的解决方法与协议参数线索，帮助本项目恢复公开队伍码查询。

## 升级与限制

- 沿用原包名和生产签名，版本码由 17 升至 18；升级前可在设置中导出 JSON 备份。
- 公开队伍码查询仍依赖 Pokémon Champions 官方服务和可用网络；服务端协议今后发生变化时可能需要再次适配。
- 仅维护 Android 标准版；历史录屏版覆盖安装后切换为标准功能集。
- 本次自动化验证覆盖协议、解码、JVM、资源、签名、ABI 与打包检查；正式 1.1.13 APK 未执行手机覆盖安装或完整对战验收。

## 验证范围

- `npm.cmd test`：35/35 项引擎与目录回归、8/8 项队伍码协议回归全部通过，TypeScript、版本一致性与许可证检查通过。
- Android JVM 162/162 通过；Release lint、生产签名、标准版身份、版本、识别资源、队伍码数据、许可证、权限和 ARM64/x86_64 单 ABI 包校验通过。
- MuMu Android 15 实际查询：取令牌、登录、队伍查询和六成员解码成功；该验证使用调试探针，不等同于正式 APK 安装验收。

## 下载校验

- 文件：`Pokemon-Champions-Assistant-v1.1.13-arm64.apk`
- 大小：72,028,822 字节（68.69 MiB）。
- SHA-256：`61F683CBFA90BBDB747311FAC990391BF9EFB57CE68554E06C23EAE0D95389FD`
- 生产证书 SHA-256：`671B45190A9DAC81A2747355CB9F10703503F1302EAF3E59582A282DD827EEF8`
- x86_64 Release 包为 107,873,170 字节（102.88 MiB），SHA-256 `28754185AC86C9BA00F13B4D3DE0FEAF1F4C3255A1B9EBFC0B8DA513F9E7AAC5`，仅保留本地，不上传 GitHub Release。
