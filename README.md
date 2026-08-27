# GTNH Labeled Wireless Transceiver（标签无线收发器）

把 EAEP「标签无线收发器」移植到 **Minecraft 1.7.10 / GTNH**


> **AI 构建声明**：本仓库的全部代码由 AI 模型（D指导）生成，无人类逐行手写，连github都是AI上传，纯石山代码。

> 移植过程基于 [ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）的
> 公开设计与 [AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)
> 的设计思路，未复制上述仓库的任何源码文件；AE2 网格机制经字节码级反汇编分析后独立实现。
> 详见下方「许可」。

## 特性

- **标签频道网络**：给收发器设置一个标签（频道），同标签即互联。
- **跨维度传输（v0.4.0 起默认开启）**：主世界与任意维度（私人维度、末地、下界……）的同标签收发器共享同一网络。
- **无线共享存储 / 合成**：每个本地网络通过虚拟枢纽合并为同一网格，可跨维度访问存储与自动合成。
- **GUI 频道管理**：设置 / 清除 / 删除 / 重命名频道；实时同步频道列表与当前状态。
- **FTB 队伍独立频道（v0.5.0）**：ServerUtilities 队伍成员共享频道，不同队伍互相隔离；无队伍玩家使用个人频道。
- **扳手拆卸（v0.5.0）**：手持扳手 + 蹲下右键可拆卸，标签 / 频率 / 锁定状态写入掉落物物品 NBT，重新放置自动恢复；GUI 可锁定方块，锁定后扳手无法拆卸。
- **AE 内存卡复制粘贴（v0.5.0）**：蹲下右键内存卡复制配置，普通右键粘贴配置到另一台收发器。

## 依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.7.10 | GTNH 2.8.4  |


## 版本历史

### v0.5.0（当前）
- **FTB 队伍独立频道**：频道所有者按 ServerUtilities 队伍 ID 派生固定 UUID——同队伍成员共享频道，跨队伍隔离；无队伍玩家回退到个人频道。队伍派生 UUID 确定性生成，存档重载后依然稳定。
- **扳手拆卸 + 锁定**：手持扳手蹲下右键拆卸收发器，标签 / 频率 / 锁定 / 放置者写入掉落物物品 NBT，重新放置自动恢复（频道归属不变）；GUI 新增锁定按钮，锁定后扳手无法拆卸（徒手挖掘不受影响）。
- **AE 内存卡复制粘贴**：蹲下右键内存卡 = 复制本机配置（标签 / 频率 / 锁定）；普通右键 = 粘贴配置到目标收发器（自动重新注册频道），与 AE2 原生方块交互习惯一致。

### v0.4.2
- **修复删除频道后收发器不断连**：此前删除频道后，未加载区块中的收发器重载时会把已删除的频道“复活”（`refreshLabel` 用 `register` 兜底重建网络）。v0.4.2 改为只查不建，网络不存在即清空标签彻底断开，并给已删除网络加 `deleted` 标记，杜绝任何路径的频道复活。
- **TPS 优化**：方块状态检查从每 tick 节流到每 5 tick（链路翻转时立即刷新），网格能量查询开销降为原来的 1/5；无在线端点时 `tickAll` 直接返回，不再每 tick 分配快照。

### v0.4.1
- **配置迁移修复**：v0.3.4 生成的 `crossDimensionalLabelNetwork=false` 会被 Forge 配置读回、覆盖 v0.4.0 的默认开启，导致跨维度实际未生效（另一维度看不到频道）。v0.4.1 自动删除旧键并改用新键 `crossDimensionalNetwork`（默认 `true`）。
- 修复 FML 事件监听器 IllegalAccessError 启动崩溃：世界生命周期监听器改为独立 public 类 `WorldLifecycleHandler`（1.7.10 FML 的 ASMEventHandler 在独立 classloader 中访问匿名/包私有类会崩溃）。

### v0.4.0
- **跨维度默认开启** + **修复刷物品漏洞**

## 许可

本项目代码由 **AI 模型生成**（见顶部声明），移植自
[GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（**LGPL-3.0**）的
标签无线收发器部分，作为其衍生改编作品以 **LGPL-3.0** 协议发布（见仓库内 `LICENSE`）。
主要设计参考：
- [GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）——标签无线收发器原版设计；
- [mynamexiaopiao/AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)（仓库未声明许可证，仅参考其跨维度无线传输思路，未使用其代码）。

与 AE2（LGPL-3.0）为编译期/运行期接口依赖关系，未包含或分发 AE2 源码/二进制；AE2 二进制由 GTNH 实例提供。
