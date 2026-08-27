package com.gtnhwireless.common.ae.wireless;

import com.gtnhwireless.reference.Reference;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网络所有者工具。
 *
 * EAEP 1.20 使用 FTBTeams 做队伍隔离；GTNH 1.7.10 的队伍系统由 ServerUtilities 提供。
 * 规则（v0.5.0）：
 * - 玩家属于某 FTB 队伍：频道所有者 = 由队伍 ID 派生的固定 UUID（同队成员共享频道，
 *   跨队伍隔离——不同队伍的成员互相看不到对方的频道列表，也无法加入对方的频道）；
 * - 玩家不属于任何队伍：频道所有者 = 放置者 UUID；
 * - 无放置者 UUID：回退到固定的“公共网络”UUID。
 *
 * 队伍派生 UUID 是确定性的（UUID.nameUUIDFromBytes），不依赖内存状态，因此
 * 跨服务器重启 / 存档重载后 LabelNetworkRegistry（WorldSavedData 持久化）中
 * 已保存的队伍频道 owner 依然稳定匹配。
 */
public final class WirelessTeamUtil {

    /** 队伍频道 owner 派生前缀（保证与玩家真实 UUID 空间不相交）。 */
    private static final String TEAM_UUID_PREFIX = "gtnhw:team:";

    private WirelessTeamUtil() {}

    public static UUID getNetworkOwnerUUID(World level, UUID placerId) {
        if (placerId == null) {
            return Reference.PUBLIC_NETWORK_UUID;
        }
        // 服务端才查询队伍：客户端没有 Universe 数据，且 owner 只在服务端参与
        // 频道注册 / 列表过滤（客户端仅展示服务端推送的频道名）。
        if (level == null || level.isRemote) {
            return placerId;
        }
        String teamUid = ServerUtilitiesTeamApi.getTeamUidCode(placerId);
        if (teamUid != null) {
            return teamUUID(teamUid);
        }
        return placerId;
    }

    /** 由队伍 ID 派生固定频道 owner UUID（同队伍所有成员一致）。 */
    public static UUID teamUUID(String teamUid) {
        return UUID.nameUUIDFromBytes((TEAM_UUID_PREFIX + teamUid).getBytes(StandardCharsets.UTF_8));
    }

    public static String getNetworkOwnerName(World level, UUID owner) {
        if (owner == null || owner.equals(Reference.PUBLIC_NETWORK_UUID)) {
            return "Public";
        }
        return owner.toString().substring(0, 8);
    }
}
