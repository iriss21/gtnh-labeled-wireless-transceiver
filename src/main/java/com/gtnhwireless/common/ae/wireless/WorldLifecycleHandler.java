package com.gtnhwireless.common.ae.wireless;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;

/**
 * 世界生命周期监听器：驱动标签网络虚拟枢纽节点的创建与销毁。
 *
 * 注意：必须是一个独立的 public 类（不能是匿名内部类 / 包私有类）。
 * FML 的 ASMEventHandler 会在独立的 ASMClassLoader 中生成处理器包装类，
 * 若监听器类非 public，生成的包装类跨 classloader 访问时会抛
 * IllegalAccessError（crash-2026-08-26_09.12.29-server.txt 就是
 * CommonProxy 内匿名类导致的崩溃）。
 *
 * 职责：
 * - 世界卸载：销毁该世界的虚拟枢纽节点（防死节点残留）；
 * - 世界加载（仅主世界）：提前重建全部虚拟枢纽（端点重连更顺）。
 */
public final class WorldLifecycleHandler {

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World w = event.world;
        if (w == null || w.isRemote) return;
        LabelNetworkRegistry reg = LabelNetworkRegistry.get(w);
        if (reg != null) {
            reg.onWorldUnload(w);
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World w = event.world;
        if (w == null || w.isRemote) return;
        // 只在主世界加载时提前重建全部虚拟枢纽（跨维度枢纽与主世界同维度枢纽都在主世界）。
        // 其他维度加载时其枢纽由端点重连（ensureVirtualNode）按需创建，避免在
        // 主世界尚未可访问时误把注册表建到其他维度的 mapStorage。
        if (w.provider.dimensionId != 0) return;
        LabelNetworkRegistry reg = LabelNetworkRegistry.get(w);
        if (reg != null) {
            reg.onWorldLoad(w);
        }
    }

    /**
     * 必须可被 FML 实例化（EVENT_BUS.register 时 new），故保留默认 public 构造器。
     */
    public WorldLifecycleHandler() {}
}
