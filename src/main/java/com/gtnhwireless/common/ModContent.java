package com.gtnhwireless.common;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

/**
 * 集中保存本模组注册后的 Block / Item 引用。
 * 在 CommonProxy.preInit 中赋值，供其余代码（如虚拟节点外观、物品比较）引用。
 *
 * 注意：1.7.10 没有 1.20 的注册表式 API，这里用普通静态字段 + GameRegistry 注册。
 */
public final class ModContent {

    // 方块
    public static Block labeledWirelessTransceiver;

    // 物品（含 ItemBlock）
    public static Item itemLabeledWirelessTransceiver;

    private ModContent() {}
}
