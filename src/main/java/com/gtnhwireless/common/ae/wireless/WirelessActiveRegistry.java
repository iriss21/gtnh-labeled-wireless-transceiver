package com.gtnhwireless.common.ae.wireless;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 活动无线端点注册表：由 WirelessTickHandler 周期性遍历，驱动各端维护无线连接。
 *
 * 用强引用 + 显式 add/remove（在 onReady / invalidate 时调用），避免 WeakHashMap 误回收。
 */
public final class WirelessActiveRegistry {

    private static final Set<IWirelessTicker> ACTIVE = Collections.synchronizedSet(
            new java.util.HashSet<IWirelessTicker>());

    public static void add(IWirelessTicker t) {
        ACTIVE.add(t);
    }

    public static void remove(IWirelessTicker t) {
        ACTIVE.remove(t);
    }

    public static void tickAll() {
        List<IWirelessTicker> snapshot = new ArrayList<>(ACTIVE);
        for (IWirelessTicker t : snapshot) {
            t.wirelessTick();
        }
    }

    private WirelessActiveRegistry() {}
}
