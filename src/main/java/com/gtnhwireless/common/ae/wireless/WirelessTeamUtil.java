package com.gtnhwireless.common.ae.wireless;

import com.gtnhwireless.reference.Reference;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * 网络所有者工具。
 *
 * EAEP 1.20 使用 FTBTeams 做队伍隔离；GTNH 1.7.10 没有对应的跨端队伍系统，
 * 这里退化为：有放置者 UUID 就用放置者，否则用固定的“公共网络”UUID。
 */
public final class WirelessTeamUtil {

    private WirelessTeamUtil() {}

    public static UUID getNetworkOwnerUUID(World level, UUID placerId) {
        return placerId != null ? placerId : Reference.PUBLIC_NETWORK_UUID;
    }

    public static String getNetworkOwnerName(World level, UUID owner) {
        if (owner == null || owner.equals(Reference.PUBLIC_NETWORK_UUID)) {
            return "Public";
        }
        return owner.toString().substring(0, 8);
    }
}
