# GTNH Labeled Wireless Transceiver（标签无线收发器）

把「标签无线收发器」移植到 **Minecraft 1.7.10 / GTNH**


> **AI 构建声明**：本仓库的全部代码由 AI 模型（D指导）生成，无任何人类手写代码，连github都是AI上传，纯石山。

> 移植过程基于 [ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）的
> 公开设计与 [AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)
> 的设计思路，未复制上述仓库的任何源码文件；AE2 网格机制经字节码级反汇编分析后独立实现。
> 详见下方「许可」。

## 特性

- **标签频道网络**：给收发器设置一个标签（频道），同标签即互联，不同频道收发器需用线缆传电。
- **跨维度传输**：主世界与任意维度的同标签收发器共享同一网络。
- **FTB 队伍独立频道**：ServerUtilities 队伍成员共享频道，不同队伍互相隔离；无队伍玩家使用个人频道。
- **AE 内存卡复制粘贴**：蹲下右键内存卡复制配置，右键粘贴配置到另一台收发器。

## 依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.7.10 | GTNH 2.8.4  |


## 版本历史

### v0.5.5（当前）
- 修复重命名后 GUI「当前」显示不跟随（防止旧回显/旧描述包把显示打回旧名）
- 删除 GUI 中重叠的「频道列表」标题文字

### v0.5.4
- 修复重命名频道后其他收发器不跟随改名（含未加载区块收发器重载后的旧名解析）

### v0.5.3
- 合成配方更新

### v0.5.2
- 合成配方更新
- GUI 拼音搜索

### v0.5.1
- 修复邻接收发器刷物品漏洞

### v0.5.0
- FTB 队伍独立频道
- 扳手拆卸 + 锁定
- AE 内存卡复制粘贴

### v0.4.2
- 修复删除频道后收发器不断连
- TPS 优化

### v0.4.1
- 配置迁移修复

### v0.4.0
- 跨维度默认开启

## 许可

本项目代码由 **AI 模型生成**（见顶部声明），移植自
[GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（**LGPL-3.0**）的
标签无线收发器部分，作为其衍生改编作品以 **LGPL-3.0** 协议发布（见仓库内 `LICENSE`）。
主要设计参考：
- [GaLicn/ExtendedAE_Plus](https://github.com/GaLicn/ExtendedAE_Plus)（LGPL-3.0）——标签无线收发器原版设计；
- [mynamexiaopiao/AE-Wireless-Transceiver](https://github.com/mynamexiaopiao/AE-Wireless-Transceiver)（仓库未声明许可证，仅参考其跨维度无线传输思路，未使用其代码）。

与 AE2（LGPL-3.0）为编译期/运行期接口依赖关系，未包含或分发 AE2 源码/二进制；AE2 二进制由 GTNH 实例提供。
