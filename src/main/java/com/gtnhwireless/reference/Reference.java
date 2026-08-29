package com.gtnhwireless.reference;

import java.util.UUID;

/**
 * 全局常量。modid / 名称 / 版本等，供 @Mod 注解与各子系统共用。
 */
public final class Reference {

    public static final String MODID = "gtnhlabeledwireless";
    public static final String NAME = "GTNH Labeled Wireless Transceiver";
    public static final String VERSION = "0.5.4";

    /** AE2（GTNH 分支）的 modid，作为本模组的硬依赖。 */
    public static final String AE2_MODID = "appliedenergistics2";
    /** AE2Stuff – 配方中无线接入器（Wireless Accessor）的来源（运行时 findBlock 查找，非硬依赖）。 */
    public static final String AE2STUFF_MODID = "ae2stuff";
    public static final String DEPENDENCIES = "required-after:" + AE2_MODID + ";";

    public static final String CLIENT_PROXY = "com.gtnhwireless.client.ClientProxy";
    public static final String SERVER_PROXY = "com.gtnhwireless.CommonProxy";
    public static final String NETWORK_CHANNEL = MODID;

    /** GUI id */
    public static final int GUI_LABELED_WIRELESS = 0;

    /** 配置默认值（如需可在 CommonProxy 中改为可配置） */
    public static final double WIRELESS_IDLE_POWER = 1.0D;
    public static final double WIRELESS_MAX_RANGE = 1000.0D;
    /**
     * 跨维度标签网络。v0.4.0 起默认开启：所有维度的同标签收发器共享主世界虚拟枢纽节点。
     *
     * v0.3.4 曾因物资复制漏洞默认关闭（跨维度收发器节点随 chunk 卸载/重载反复
     * invalidate/recreate 触发 grid 分裂-合并，同一存储两侧 CellCache 各留缓存，取出不减）。
     * v0.4.0 的修复：
     * 1. 虚拟枢纽节点生命周期绑定世界加载/卸载（onWorldUnload/onWorldLoad），杜绝死节点复用；
     * 2. 收发器链路重连后强制 post MENetworkCellArrayUpdate，触发 AE2 的 cellUpdate
     *    全量重建 myItemNetwork 并 forceUpdate 监视器，让存储缓存与真实 cell 一致；
     * 3. chunk 卸载时完整反注册（WirelessActiveRegistry + 标签端点），消除泄漏与残留。
     * CommonProxy.preInit 会用 Forge 配置覆盖此值（默认 true；配置键
     * crossDimensionalNetwork，v0.4.1 起自动迁移掉旧键 crossDimensionalLabelNetwork，
     * 避免 v0.3.4 残留的 false 覆盖代码默认值导致跨维度实际未启用）。
     */
    public static boolean WIRELESS_CROSS_DIM = true;

    /** “公共网络”所有者 UUID（标签收发器未指定显式所有者时使用），固定值便于序列化。 */
    public static final UUID PUBLIC_NETWORK_UUID = new UUID(0x676E6877_6972_656CL & 0xFFFFFFFFFFFFFFFFL, 0x6573735F_707562L & 0xFFFFFFFFFFFFFFFFL);

    private Reference() {}
}
