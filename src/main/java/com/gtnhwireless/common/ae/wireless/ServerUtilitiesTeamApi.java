package com.gtnhwireless.common.ae.wireless;

import cpw.mods.fml.common.Loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * ServerUtilities（GTNH 的 FTB 队伍实现，modid=serverutilities）的队伍查询封装。
 *
 * GTNH 2.8.4 的 mods 目录中队伍系统由 ServerUtilities（FTBU 的 GTNH 分支）提供，
 * 其 API（serverutils.lib.data.*）不在 GTNH 主仓库的依赖里，故此处全部走反射，
 * 避免在 build.gradle.kts 中引入编译期依赖：ServerUtilities 未安装或加载失败时
 * 优雅降级（返回 null = 无队伍），不影响本模组其余功能。
 *
 * 反射目标（已用 javap 确认签名）：
 * - serverutils.lib.data.Universe#get()  -> Universe（静态单例）
 * - Universe#players（public final Map<UUID, ForgePlayer>）
 * - ForgePlayer#team（public ForgeTeam 字段，可能为 null）
 * - ForgeTeam#getUIDCode() -> String（队伍唯一 ID，如 "m" / "myteam"）
 */
public final class ServerUtilitiesTeamApi {

    private static boolean initAttempted;
    private static boolean available;
    private static Method universeGet;
    private static Field playersField;
    private static Field teamField;
    private static Method getUidCode;

    private ServerUtilitiesTeamApi() {}

    /** ServerUtilities 是否已加载且 API 解析成功（懒初始化，线程安全）。 */
    public static boolean isAvailable() {
        init();
        return available;
    }

    /**
     * 查询玩家当前所属队伍的 ID（如 "m" / "myteam"）。
     *
     * @return 队伍 UID code；玩家不在任何队伍 / ServerUtilities 不可用 / 玩家未知时返回 null
     */
    public static String getTeamUidCode(UUID playerId) {
        if (playerId == null || !isAvailable()) return null;
        try {
            Object universe = universeGet.invoke(null);
            if (universe == null) return null;
            @SuppressWarnings("unchecked")
            Map<UUID, Object> players = (Map<UUID, Object>) playersField.get(universe);
            if (players == null) return null;
            Object forgePlayer = players.get(playerId);
            if (forgePlayer == null) return null;
            Object team = teamField.get(forgePlayer);
            if (team == null) return null;
            return (String) getUidCode.invoke(team);
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void init() {
        if (initAttempted) return;
        initAttempted = true;
        if (!Loader.isModLoaded("serverutilities")) return;
        try {
            Class<?> universeClass = Class.forName("serverutils.lib.data.Universe");
            universeGet = universeClass.getMethod("get");
            playersField = universeClass.getField("players");
            Class<?> forgePlayerClass = Class.forName("serverutils.lib.data.ForgePlayer");
            teamField = forgePlayerClass.getField("team");
            Class<?> forgeTeamClass = Class.forName("serverutils.lib.data.ForgeTeam");
            getUidCode = forgeTeamClass.getMethod("getUIDCode");
            available = true;
        } catch (Throwable t) {
            available = false;
        }
    }
}
