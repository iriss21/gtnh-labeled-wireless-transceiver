package com.gtnhwireless.common.ae.wireless;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端周期驱动：每个 tick 让活动无线端点（标签收发器方块实体）维护连接与方块状态。
 * 1.7.10 用 Forge 的 {@link TickEvent.ServerTickEvent}（无 1.20 的 LevelTicks）。
 */
public class WirelessTickHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        WirelessActiveRegistry.tickAll();
    }
}
