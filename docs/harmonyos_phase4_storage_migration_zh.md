# HarmonyOS 阶段 4：本地存储、备份与 Android 数据迁移验收记录

日期：2026-08-01

状态：**已完成（模拟器与契约范围）**

## 1. 阶段边界

本阶段只移植原应用已有的本地数据能力：我方队伍、导入草稿、当前对局、用户保存的对手配置、更新通道、HUD 布局、整包备份、配置分享和系统备份。不新增云同步、账号、调试入口或新的产品数据格式。文件选择器和正式管理界面属于阶段 5；相册取帧、识别、悬浮窗与录屏仍属于阶段 6–9。

跨平台格式以阶段 0 冻结的 Android 黄金文件为准：`PokemonChampionsAssistantBackup`、`OpponentUserPresetShare`、`SavedOwnTeam`、`BattleSession` 和 `OpponentUserPresets` 的 kind、schemaVersion、稳定 ID 与缺省语义均保持不变。

## 2. 已实现内容

### 2.1 存储仓库

`AppStorageRepository` 管理以下持久化对象：

- `saved-teams/*.json`：已保存的六只我方队伍；
- `battle-session/current-battle-session.json` 与当前队伍预览；
- `pending-own-team.json`、`own-team-import-draft.json`；
- `user-opponent-presets.json`；
- `app-settings.json` 与 `battle-direct-hud-layouts.json`。

每次写入先在同目录写 `.tmp`，使用同步模式写入并 `fsync`，再以覆盖移动替换目标文件。HarmonyOS API 20 的递归建目录在目录已存在时会返回 `13900015 File exists`，因此仓库先检查目录是否存在，只在缺失时创建。该行为已在模拟器真实应用沙箱中验证，不是仅靠 Node 文件系统推断。

### 2.2 校验、损坏保护与事务恢复

- 整包备份上限 16 MB，最多 100 支队伍；配置分享上限 4 MB，最多 500 条配置；
- 队伍 ID 只能使用安全文件名字符，完整保存队伍必须恰好六只；
- 拒绝重复队伍/配置 ID、错误 kind/schema、缺失队伍引用、非六只对手和非法实体；
- 用户配置文件一旦损坏，保存、导出和导入合并均停止，原始文件在显式复制成 `corrupt-*` 恢复副本前不会被覆盖；
- 整包恢复先完整解析并校验，再保存允许文件的内存快照；任一步写入失败都会清理半成品并恢复快照；
- 模拟器验收在第一项恢复写入完成后主动注入异常，确认队伍仍为空、会话仍不存在、原有两条配置和 `stable` 通道逐项恢复，然后才执行正常恢复。

### 2.3 Android 兼容和配置分享

- 阶段 0 的真实 Android `app-backup.json` 可由 HarmonyOS 契约完整解析，并可重新构造为逐字段相同的 envelope；
- Android `opponent-preset-share.json` 可导入；已存在 ID 原位更新，新 ID 追加，重复导入变为 unchanged，不产生副本；
- 用户保存配置保持文件中的保存顺序，继续位于系统预设之前；
- 旧备份缺少 `userOpponentPresets` 时保留本地配置，显式空对象才清空；
- 删除配置时会同时清理当前选择、槽位引用和以该配置为基准的人工覆盖；删除被当前会话引用的我方队伍会删除失效会话，避免悬空引用。

### 2.4 系统备份白名单

应用注册了非导出的 `BackupExtensionAbility`，元数据指向启用备份恢复的 `backup_config.json`。安装后的 bundle dump 已确认 `EntryBackupAbility` 的 `extensionTypeName` 为 `backup`。

白名单只包含已保存队伍、对局状态与草稿、用户对手配置、设置和 HUD 布局。截图、识别模板缓存、临时帧、下载缓存、鉴权令牌和回放中间文件不在列表中。扩展的复制/恢复路径与正式仓库共用相同校验和回滚实现；模拟器中已完成五个实际文件的独立备份目录往返。模拟器镜像没有可调用的系统级备份命令，因此系统服务主动调起扩展仍保留到真实设备/发布环境门，不能把文件级往返误写成真机系统恢复已经完成。

## 3. 自动化验收

执行：

```powershell
npm.cmd run harmonyos:phase4:check
npm.cmd run harmonyos:phase4:emulator
```

契约测试共 8 项，覆盖 Android 整包逐字段重建、扩展注册与白名单、分享顺序和幂等性、旧备份字段语义、损坏/重复/越界/超限输入、引用清理以及设置/HUD 归一化。

模拟器一键流程从清空应用数据开始：

1. 安装标准版并确认备份扩展已进入安装清单；
2. 执行保存、覆盖写入、整包备份、第一项写入后故障注入和回滚、正常恢复；
3. 执行配置分享首次合并与重复合并；
4. 写入损坏配置，确认阻写、保留恢复副本并显式重置；
5. 对系统备份白名单执行独立目录复制与恢复；
6. 不清数据覆盖安装录屏版，确认队伍、会话、3 条配置、更新通道和 HUD 全部保留；
7. 重新安装标准版并回到正常首页。

同一次运行的关键结果：

- 标准版日志：`STAGE4_STORAGE_PASS variant=standard teams=1 presets=3 files=5 atomic=true rollback=true`；
- 标准版界面：`PASS seed 5`；
- 录屏版日志：`STAGE4_VARIANT_PASS variant=replay teams=1 presets=3 session=true settings=true hud=true`；
- 录屏版界面：`PASS replay persistence`。

可提交的 UI 层级证据位于 `harmonyos/app/evidence/pc-stage4-standard-seed.json` 和 `pc-stage4-replay-verify.json`；过滤日志按仓库规则留在本地，不提交。

## 4. 阶段退出结论

阶段 4 的 Android 兼容契约、原子写入、损坏保护、事务回滚、配置合并、引用清理、系统备份白名单与双产品共享数据门均已关闭，可以进入阶段 5 的正式主界面和非截图业务流程。

未关闭项不属于本阶段完成声明：系统服务在真机/发布签名环境主动调用备份扩展、正式发布签名、真实 arm64 设备，以及阶段 1 的媒体能力真机门。它们仍会保留在最终验收清单中。
