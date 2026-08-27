# GTNH Labeled Wireless Transceiver（标签无线收发器）

把 EAEP「标签无线收发器」移植到 **Minecraft 1.7.10 / GTNH 2.8.4 / Forge 10.13.4.1614 / AE2 rv3-beta-695-GTNH**。

同一标签下的所有标签无线收发器会连接到同一个虚拟枢纽节点（AE2 in-world=false 网格节点），
从而把多个本地 AE2 网络合并成一个网格——**跨距离、跨维度**无线共享存储 / 合成，
类似 ExtendedAE 的标签无线收发器在 1.20 的行为。

> **AI 构建声明**：本仓库的全部代码由 AI 模型生成并人工验证，无人类逐行手写。
> 移植过程基于 [ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）的
> 公开设计与 [AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)
> 的设计思路，未复制上述仓库的任何源码文件；AE2 网格机制经字节码级反汇编分析后独立实现。
> 详见下方「许可」。

## 特性

- **标签频道网络**：给收发器设置一个标签（频道），同标签即互联；频道号从 1000000 起自动分配，持久化到主世界。
- **跨维度传输（v0.4.0 起默认开启）**：主世界与任意维度（私人维度、末地、下界……）的同标签收发器共享同一网络。
- **无线共享存储 / 合成**：每个本地网络通过虚拟枢纽合并为同一网格，可跨维度访问存储与自动合成。
- **GUI 频道管理**：设置 / 清除 / 删除 / 重命名频道；实时同步频道列表与当前状态。
- **防刷物品（v0.4.0）**：修复了跨维度网格分裂-合并导致的存储缓存残留复制漏洞（详见下方版本历史）。

## 依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.7.10 | GTNH 2.8.4 线 |
| Forge | 10.13.4.1614 | GTNH 分支 |
| AE2 | rv3-beta-695-GTNH | 编译与运行时必需（`required-after:appliedenergistics2`） |
| Java | 8+（构建需 JDK 25，见下） | GTNHGradle 2.0.20 要求 JVM 25+ |

## 构建

```powershell
# 1. 准备本地编译依赖：把 AE2（GTNH 分支）dev jar 放到 libs/ 下
#    （文件名需与 build.gradle.kts 中的 compileOnly(files(...)) 一致）：
#    libs/appliedenergistics2-rv3-beta-695-GTNH.jar
#    该 jar 可从 GTNH 实例的 mods 目录复制（AE2 rv3-beta-695-GTNH），
#    GTNH Maven 上此坐标 404，故采用本地文件引用，未入库。

# 2. 构建（需要本机装有 Azul Zulu JDK 25；路径见 gradle.properties）
$env:JAVA_HOME = "<你的 zulu25 路径>"
.\gradlew.bat clean build --no-daemon
```

产物：`build/libs/gtnhlabeledwireless-<version>.jar`（正式版为无 `-dev` 后缀的 jar）。

> 注意：`gradle.properties` 中 `org.gradle.java.installations.paths` 指向本机 JDK 路径，
> 换机器构建时按需修改。

## 安装

1. 把 `gtnhlabeledwireless-<version>.jar` 放进 `mods/` 目录（GTNH 实例）。
2. 确保已安装 AE2（GTNH 2.8.4 自带）。
3. 启动游戏，放置「标签无线收发器」方块，右键打开 GUI 设置标签。

## 配置（config/gtnhlabeledwireless.cfg）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `wireless.crossDimensionalNetwork` | `true` | 跨维度标签网络开关。v0.4.0 起默认开启（复制漏洞已修复）；v0.4.1 起自动迁移掉旧键 `crossDimensionalLabelNetwork`，避免旧版本残留的 `false` 覆盖默认值。 |

## 版本历史

### v0.4.1（当前）
- **配置迁移修复**：v0.3.4 生成的 `crossDimensionalLabelNetwork=false` 会被 Forge 配置读回、覆盖 v0.4.0 的默认开启，导致跨维度实际未生效（另一维度看不到频道）。v0.4.1 自动删除旧键并改用新键 `crossDimensionalNetwork`（默认 `true`）。
- 修复 FML 事件监听器 IllegalAccessError 启动崩溃：世界生命周期监听器改为独立 public 类 `WorldLifecycleHandler`（1.7.10 FML 的 ASMEventHandler 在独立 classloader 中访问匿名/包私有类会崩溃）。

### v0.4.0
- **跨维度默认开启** + **修复刷物品漏洞**：跨维度收发器节点随 chunk 卸载/重载反复 invalidate/recreate 触发 AE2 rv3 网格分裂-合并，而 `GridStorageCache.onSplit/onJoin/populateGridStorage` 是空实现、存储缓存不自迁移，导致同一存储两侧各留缓存、取物只减一侧 → 无限复制。
  修复手段：
  1. 虚拟枢纽节点生命周期绑定世界加载/卸载（`onWorldUnload`/`onWorldLoad`），杜绝死节点复用；
  2. 收发器链路重连后强制 `post(MENetworkCellArrayUpdate)`，触发 AE2 `cellUpdate` 全量重建存储缓存（置空 `myItemNetwork` + 重评全部 cell provider + `forceUpdate` 监视器）；
  3. chunk 卸载时完整反注册（`WirelessActiveRegistry` + 标签端点），消除泄漏与残留；
  4. 断开时周期性重建虚拟枢纽并自动重连（下线后断连的根治）。

### v0.3.4
- 首个可玩版本；跨维度默认关闭（受复制漏洞影响）。

## 移植说明

- 原版：EAEP「标签无线收发器」（LabeledWirelessTransceiver），移植核心类：
  `LabelNetworkRegistry` / `LabelNetwork` / `VirtualLabelNodeHost` / `LabelLink` / `AbstractWirelessTile`。
- 1.7.10 差异：`WorldSavedData` 无 `computeIfAbsent`，用 `MapStorage.loadData/setData`；
  虚拟枢纽用 `AENetworkProxy(inWorld=false)` 实现；AE2 节点生命周期与 rv3 网格分裂-合并机制经
  javap 字节码级分析确认（详见源码注释）。
- 1.7.10 FML 注意事项：事件监听器必须是独立 public 类（匿名类跨 classloader 访问会抛
  `IllegalAccessError`）。

## 许可

本项目代码由 **AI 模型生成**（见顶部声明），移植自
[GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（**LGPL-3.0**）的
标签无线收发器部分，作为其衍生改编作品以 **LGPL-3.0** 协议发布（见仓库内 `LICENSE`）。
主要设计参考：
- [GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）——标签无线收发器原版设计；
- [mynamexiaopiao/AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)（仓库未声明许可证，仅参考其跨维度无线传输思路，未使用其代码）。

与 AE2（LGPL-3.0）为编译期/运行期接口依赖关系，未包含或分发 AE2 源码/二进制；AE2 二进制由 GTNH 实例提供。
