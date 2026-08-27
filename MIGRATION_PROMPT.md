# 迁移 / 移植 Prompt：GTNH Labeled Wireless Transceiver

> 本文件是把 EAEP「标签无线收发器」移植到 **Minecraft 1.7.10 / GTNH 2.8.4** 的完整过程总结，
> 作为 prompt 提供给其他 AI / 开发者，用于理解本项目、复现移植、或迁移到其他 MC 版本。
> 本仓库全部代码由 AI 生成并人工验证（见 README 顶部声明）。

---

## 一、任务目标（给接手者的一句话）

把 EAEP「标签无线收发器」移植到 MC 1.7.10 / GTNH 2.8.4 / Forge 10.13.4.1614 /
AE2 rv3-beta-695-GTNH，实现**跨维度无线频道传输**，且**没有**以下三类问题：
离开区块掉线、TPS/MSPT 爆炸、下线后断连，以及最关键的**刷物品（物资复制）漏洞**。

## 二、环境事实（不可更改的硬约束）

| 项 | 值 |
| --- | --- |
| 项目目录 | `F:/AItemp/gtnh_labeled_wireless_transceiver`（无 .git，系统无 git） |
| modid | `gtnhlabeledwireless`，包名 `com.gtnhwireless` |
| 构建 | `$env:JAVA_HOME="F:/AItemp/zulu25"; .\gradlew.bat clean build --no-daemon`（约 1 分钟） |
| 构建产物 | `build/libs/gtnhlabeledwireless-<version>.jar`（-dev.jar / -sources.jar 同时产出） |
| AE2 依赖 | `compileOnly("com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-695-GTNH")`（GTNH Maven，与本地 libs jar SHA256 一致） |
| gradle.properties | `forceToolchainVersion=25`；机器 JDK 路径在用户级 `~/.gradle/gradle.properties`（勿提交） |
| 沙箱 | gradlew.bat 执行需 danger-full-access 批准；PowerShell 会把 javac「注:」误判为错误（exit 1 实际 BUILD SUCCESSFUL） |

## 三、参考仓库（仅设计参考，未复制源码）

- [GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）——标签无线收发器原版设计
- [mynamexiaopiao/AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)（无许可证，仅参考跨维度思路）
- 本项目以 LGPL-3.0 发布，AE2 二进制由 GTNH 实例提供，未分发。

## 四、架构总览（务必先读，再动代码）

```
GTNHWireless (@Mod 入口)
├── CommonProxy / ClientProxy（客户端仅渲染/GUI）
├── network/（FML SimpleImpl 包）
│   ├── PacketHandler, SetLabelC2SPacket（设置/清除标签）
│   ├── ChannelActionC2SPacket（删除/重命名频道）
│   └── ChannelListS2CPacket（频道列表回显）
├── common/block/LabeledWirelessTransceiverBlock
├── common/wireless/
│   ├── AbstractWirelessTile（AE2 生命周期基类，见下）
│   ├── LabeledWirelessTransceiverTile（核心 tile）
│   └── LabeledWirelessTransceiverContainer
└── common/ae/wireless/
    ├── LabelNetworkRegistry（主世界 MapStorage 持久化注册表）
    ├── LabelLink（网格连接器）
    ├── VirtualLabelNodeHost（虚拟枢纽节点宿主）
    ├── WirelessActiveRegistry（在线端点注册表）
    ├── WirelessTickHandler（ServerTickEvent 驱动）
    ├── WirelessTeamUtil（owner 规则：队伍派生 UUID > placerId > PUBLIC，v0.5.0）
    ├── ServerUtilitiesTeamApi（反射封装 ServerUtilities 队伍查询，v0.5.0）
    ├── WorldLifecycleHandler（世界加载/卸载事件）
    └── IWirelessEndpoint / IWirelessTicker（接口）
```

### 核心数据流
1. 玩家给收发器设置标签 → `SetLabelC2SPacket` → `applyLabel` → `LabelNetworkRegistry.register`（跨维度 dim=-1，同维度 dim=当前）→ 虚拟枢纽节点 `ensureVirtualNode()` 建在主世界 → `LabelLink.setTarget` 建立网格连接。
2. 同标签的所有收发器 in-world 节点都连到同一个虚拟枢纽（inWorld=false 的 AENetworkProxy），把多个本地 AE2 网络合并成一个网格。
3. 每 tick `WirelessTickHandler` → `WirelessActiveRegistry.tickAll()` → 每个端点 `wirelessTick()` 维护链路 + 方块状态。
4. 删除频道 → `ChannelActionC2SPacket.ACTION_DELETE` → `removeNetwork`（置 deleted 标记 + 销毁虚拟枢纽）+ 遍历所有已加载收发器 `clearLabel`。

## 五、AE2 rv3 网格机制（字节码级反汇编结论，复制漏洞的核心）

反汇编产物在 `F:/AItemp/ref_src/javap/*.txt`（GridNode / Grid / GridConnection / GridStorageCache / AENetworkProxy / GridSplitDetector / GridPropagator / GridStorage / TickHandler）。

1. **GridConnection 构造器**：`mergeGrids(a,b)` 合并网格——无网格节点在 `assertNodeIsStandalone` 通过后并入对方；双方不同网格按 `isGridABetterThanGridB`（priority 再 size）选优，`GridPropagator` 从败者 `beginVisit` 重挂；然后对 sideA 网格 `IPathingGrid.repath()`，双侧 `addConnection`。
2. **GridNode.destroy()**：循环销毁连接，连接唯一时 `setGridStorage(null)`，对端 `setPivot` 后 `conn.destroy()`；最后 `myGrid.remove(this)`。
3. **GridConnection.destroy()**：repath + 双侧 removeConnection + 双侧 `validateGrid()`。
4. **validateGrid()**：`GridSplitDetector` 以网格 pivot 为靶 `beginVisit`，找不到 pivot → `new Grid(this)` + `GridPropagator` 重挂（网格分裂）。
5. **Grid.add(node)**：node 带别网格 storage → 新 GridStorage + addDivided + 旧网格 caches `onSplit` + 本网格 `onJoin`。**GridStorageCache 的 onSplit/onJoin/populateGridStorage 全是空实现**——存储缓存不自迁移，靠 `MENetworkCellArrayUpdate → cellUpdate`（置空 myItemNetwork + 全量重评 provider 激活 + itemMonitor/fluidMonitor.forceUpdate）自愈。
6. **AENetworkProxy**：`validate()` 只把 tile 加 TickHandler init 队列；`onReady()` → `getNode()` 懒创建（server + isReady）；`invalidate()` → isReady=false + node.destroy() + node=null；`onChunkUnload()` → isReady=false + invalidate()。
7. **TickHandler.unloadWorld(WorldEvent.Unload)**：遍历全部网格节点，`node.getWorld()==卸载世界` → 直接 `node.destroy()`（**不经 proxy.invalidate → proxy.node 保留死节点引用 = 死节点复用根因**）。

### 刷物品（物资复制）漏洞根因（v0.3.4 → v0.4.0 修复）
跨维度收发器 world node 随 chunk 卸载/重载反复 invalidate/recreate → 网格分裂-合并
（GridSplitDetector / GridPropagator / mergeGrids）→ GridStorageCache 存储缓存
（activeCellProviders / myItemNetwork / NetworkMonitor）在分裂-合并往返中残留陈旧状态
（onSplit/onJoin 空实现不自迁移）→ 取物只减一侧 → 无限复制。
**修复三件套**：①虚拟枢纽生命周期绑定世界加载/卸载 + 死节点检测重建；②收发器链路重连后强制
`post MENetworkCellArrayUpdate`（forceCellCacheRefresh）让 cellUpdate 全量重建缓存；
③chunk 卸载时完整反注册（WirelessActiveRegistry + 标签端点）。

## 六、关键经验教训（每一条都踩过坑）

1. **1.7.10 FML 事件监听器必须是独立 public 类**：匿名内部类 → ASMEventHandler 在独立
   ASMClassLoader 跨 classloader 访问包私有类 → `IllegalAccessError` 启动崩溃。
   必须 `public final class XxxHandler { public XxxHandler(){} @SubscribeEvent public void ... }`。
2. **Forge Configuration 键无版本迁移机制**：改默认值必须换键名或删除旧键，否则旧文件残留值
   永远覆盖代码默认（v0.3.4 的 `crossDimensionalLabelNetwork=false` 覆盖 v0.4.0 的默认 true，
   导致跨维度实际未生效——另一维度看不到频道）。
3. **AEBaseTile 的 writeToNBT/readFromNBT 是 final**：自定义 NBT 必须用
   `@TileEvent(WORLD_NBT_WRITE / WORLD_NBT_READ)` 钩子；描述包同理用 NETWORK_WRITE/READ。
4. **gridChanged() 里严禁调 proxy.gridChanged()**：互相递归 → StackOverflowError 崩溃。
5. **PowerShell 5.1 三坑**（上传脚本 F:/AItemp/upload_contents.ps1 纯 ASCII）：
   - 无 BOM UTF-8 脚本按 ANSI(GBK) 解析，中文注释破坏解析 → 脚本必须纯 ASCII；
   - `Set-Content -Encoding UTF8` 写 BOM → curl 发 BOM → GitHub "Problems parsing JSON" →
     用 `[System.IO.File]::WriteAllText(tmp, body, UTF8Encoding($false))`；
   - 命令行插值 URL 的 `?ref=` 会被通配符解析吞掉 → URL 先存变量再传。
6. **GitHub 上传（fine-grained PAT）**：
   - PAT 不能创建仓库（POST /user/repos → 403）→ 网页手动建仓库并授权；
   - git data API 需要 refs 写权限（fine-grained PAT 缺）→ 改用 contents API（PUT 每文件，
     自动创建 commit）；写 workflow 文件需要 "Workflows: Read and write" 权限；
   - 资产上传必须 POST 到 **uploads.github.com**（不是 api.github.com）；PowerShell 里
     `@$var` 会被当 splatting 剥掉 `@` → 先拼字符串 `$arg='@'+$jpath` 再传参。
7. **CI 三坑**（GTNH-Actions-Workflows）：
   - 本地 libs jar（compileOnly files）在 CI checkout 后不存在 → 改 GTNH Maven 坐标；
   - 本机 JDK 路径（org.gradle.java.installations.paths）入库 → CI runner 无此路径必挂
     → 移到用户级 gradle.properties；
   - 默认跑 runServer 90 秒需真实 AE2 环境 → workflow 加 `client-only: true` + `disable-spotless-pr: true`；
   - "Canceling since a higher priority waiting request" = concurrency group cancel-in-progress，
     逐文件 PUT 触发多次 run 互相取消，非代码错误 → 一次性提交尽量少。
8. **网络环境**：直连 GitHub / codeload / ghproxy / Modrinth / raw 全部不通；web_search 可用；
   走 v2rayN SOCKS5 `--socks5-hostname 127.0.0.1:10808`（curl 需 danger-full-access）；
   反编译 AE2 jar 用 `javap -p -c`（Fernflower 报 'invalid' 无法用）。

## 七、版本历史与行为变化

- **v0.4.0**：跨维度默认开启 + 修复刷物品漏洞（见第五节）。
- **v0.4.1**：配置键迁移（crossDimensionalNetwork）+ 修复 FML 监听器 IllegalAccessError。
- **v0.4.2**：
  - **修复删除频道后收发器不断连**：`refreshLabel` 由 `register`（会重建网络）改为只查不建
    （`getNetwork`），网络不存在 → `clearLabel` 彻底断开；`LabelNetwork` 加 `volatile boolean deleted`，
    `removeNetwork` 先置标记再销毁节点；`LabelLink.updateStatus` / `ensureVirtualNode` 遇 deleted
    立即断开/拒绝重建——任何路径都不能让已删除频道复活。
  - **TPS 优化**：`updateState` 每 5 tick 一次（链路翻转立即刷新），网格能量查询降到 1/5；
    `WirelessActiveRegistry.tickAll` 空集合直接返回，不再每 tick 分配快照。
- **v0.5.0**（当前）：
  - **FTB 队伍独立频道**：`WirelessTeamUtil.getNetworkOwnerUUID` 服务端优先查
    `ServerUtilitiesTeamApi.getTeamUidCode(placerId)`——玩家属于队伍时返回
    `UUID.nameUUIDFromBytes("gtnhw:team:" + uid)` 派生固定 UUID（同队共享频道、跨队隔离，
    确定性派生保证 WorldSavedData 持久化 owner 跨重启稳定）；无队伍 → 个人 UUID；无放置者 → PUBLIC。
    注意：`getNetworkOwnerUUID` 在 `level.isRemote` 时直接返回 placerId（客户端无 Universe 数据）。
  - **扳手拆卸 + 锁定**：`LabeledWirelessTransceiverBlock.onBlockActivated` 按序处理——
    ① `Platform.isWrench` + 蹲下 → 拆卸（`downloadSettings(SettingsFrom.DISMANTLE_ITEM)` 把
    标签/频率/锁定/放置者写入掉落物 NBT，`removedByPlayer`+`Platform.spawnDrops`+`setBlockToAir`，
    尊重 `BlockEvent.BreakEvent` 取消；tile 锁定时拒绝并提示）；② 手持 `IMemoryCard` →
    蹲下=复制（`setMemoryCardContents(stack, getUnlocalizedName(), downloadSettings(MEMORY_CARD))`，
    `SETTINGS_SAVED`），普通右键=粘贴（`getSettingsName` 匹配 unlocalizedName 后
    `uploadSettings(MEMORY_CARD, getData)`，`SETTINGS_LOADED`，不匹配 `INVALID_MACHINE`）；
    ③ 默认打开 GUI。放置恢复：`onBlockPlacedBy` 读物品 NBT（NBT_PLACER_HI/LO）恢复原放置者 +
    `readSettingsFromStack` 恢复标签/锁定。
  - **AE 内存卡 / 扳手 NBT 键**：`NBT_LABEL=lwLabel`、`NBT_FREQUENCY=lwFrequency`、
    `NBT_LOCKED=lwLocked`、`NBT_PLACER_HI/LO`、`NBT_PLACER_NAME`（定义在
    `LabeledWirelessTransceiverTile`）。`downloadSettings/uploadSettings` 覆写
    `AEBaseTile`（super 调用保留 customName/config/priority 逻辑）。
  - **锁定状态**：tile 字段 `locked`，NBT（`locked`）+ 描述包（writeWirelessStream 加 boolean）
    双路持久化/同步；GUI 右侧加锁定按钮（BTN_LOCK，`ChannelActionC2SPacket.ACTION_TOGGLE_LOCK=2`），
    updateScreen 按描述包刷新按钮文案，画布底部显示锁定状态行；lang 键：
    `gui.lock/gui.unlock/gui.locked_status/gui.unlocked_status/chat.locked`。

## 八、迁移到其他 MC 版本时的 checklist

1. AE2 版本差异：1.20 的 IManagedGridNode / GridHelper → 1.7.10 的 AENetworkProxy 手动管理
   （onReady/invalidate/onChunkUnload 全部要显式调用）；无 computeIfAbsent 用 MapStorage.loadData/setData。
2. 事件系统：1.20 的 LevelTicks / NeoForge 事件 → 1.7.10 Forge TickEvent + 独立 public 监听器类。
3. 跨维度：虚拟枢纽永远建在主世界（dim<0 → 0），WorldEvent.Load/Unload 驱动节点生命周期。
4. 防复制：重连后必须 post MENetworkCellArrayUpdate（rv3 的 GridStorageCache 不自迁移缓存）。
5. 构建：GTNHGradle 2.0.20 要求 JVM 25+；AE2 用 GTNH Maven 坐标而非本地 libs。

## 九、给 AI 接手者的建议工作流

1. 先读本文件第五节（AE2 机制）与第六节（教训），再看代码；不要改 AE2 依赖坐标（rfg.deobf 勿动）。
2. 复现 bug 前先确认配置键是否残留旧值（第六节第 2 条）。
3. 改完代码用第四节构建命令验证；确认 jar 内无 `CommonProxy$1.class`（匿名类残留检查）。
4. 上传：F:/AItemp/upload_contents.ps1（全量）/ upload_fix.ps1（指定文件）改路径与 PAT 后使用；
   发布 Release 参考 F:/AItemp/release_v041.ps1（注意 uploads.github.com 端点与 @ 拼接）。
5. 每轮修复把结论记入 Graph Memory（如 gtnh-labeled-wireless-transceiver-v040-修复记录），
   防止同类坑重踩。

---

*生成于 2026-08-27，覆盖 v0.3.4 → v0.5.0 全部历史对话经验。*
