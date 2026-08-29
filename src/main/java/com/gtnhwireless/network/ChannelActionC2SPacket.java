package com.gtnhwireless.network;

import com.gtnhwireless.common.ae.wireless.LabelNetworkRegistry;
import com.gtnhwireless.common.ae.wireless.WirelessTeamUtil;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverTile;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

/**
 * 频道管理操作包（客户端 GUI -> 服务端）。
 *
 * 支持的操作：
 * - DELETE: 删除指定标签的频道网络
 * - RENAME: 将旧标签重命名为新标签
 *
 * SELECT 操作复用 {@link SetLabelC2SPacket}。
 */
public class ChannelActionC2SPacket implements IMessage {

    public static final int ACTION_DELETE = 0;
    public static final int ACTION_RENAME = 1;
    /** 切换本收发器的锁定状态（锁定后无法用扳手拆卸）。v0.5.0。 */
    public static final int ACTION_TOGGLE_LOCK = 2;

    private int action;
    private int x, y, z;
    private String label;
    private String newName;

    public ChannelActionC2SPacket() {}

    public ChannelActionC2SPacket(int action, int x, int y, int z, String label, String newName) {
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.label = label == null ? "" : label;
        this.newName = newName == null ? "" : newName;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(action);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        writeStr(buf, label);
        writeStr(buf, newName);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.action = buf.readInt();
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.label = readStr(buf);
        this.newName = readStr(buf);
    }

    private static void writeStr(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readStr(ByteBuf buf) {
        int len = buf.readShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<ChannelActionC2SPacket, IMessage> {

        @Override
        public IMessage onMessage(ChannelActionC2SPacket msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            World world = player.worldObj;
            if (world == null || world.isRemote) return null;

            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (!(te instanceof LabeledWirelessTransceiverTile)) return null;
            LabeledWirelessTransceiverTile tile = (LabeledWirelessTransceiverTile) te;
            java.util.UUID placerId = tile.getPlacerId();

            LabelNetworkRegistry reg = LabelNetworkRegistry.get(world);

            switch (msg.action) {
                case ACTION_DELETE:
                    reg.removeNetwork(world, msg.label, placerId);
                    // 断开（清空标签）所有正在使用该频道的已加载收发器
                    disconnectAllUsingLabel(msg.label,
                            WirelessTeamUtil.getNetworkOwnerUUID(world, placerId));
                    break;
                case ACTION_RENAME:
                    // v0.5.4：重命名成功后，所有正在使用该频道的已加载收发器统一跟随改名
                    // （此前只更新发出操作的那一台，其他收发器显示旧名 / 重载后被清空掉线）。
                    if (reg.renameNetwork(world, msg.label, msg.newName, placerId)) {
                        renameAllUsingLabel(msg.label, msg.newName,
                                WirelessTeamUtil.getNetworkOwnerUUID(world, placerId));
                    }
                    break;
                case ACTION_TOGGLE_LOCK:
                    tile.setLocked(!tile.isLocked());
                    break;
            }

            // 把最新频道列表 + 本收发器当前标签权威回显推回客户端，确保 GUI 实时刷新
            java.util.List<String> names = new java.util.ArrayList<>();
            for (LabelNetworkRegistry.LabelNetworkSnapshot s :
                    LabelNetworkRegistry.get(world).listNetworks(world, placerId)) {
                names.add(s.label);
            }
            PacketHandler.INSTANCE.sendTo(new ChannelListS2CPacket(
                    names, tile.xCoord, tile.yCoord, tile.zCoord, tile.getLabelForDisplay()), player);
            return null;
        }

        /**
         * 断开所有正在使用指定频道的已加载收发器：清空其标签并同步（GUI 显示"无"、方块变回未连接材质）。
         * 遍历所有已加载维度的方块实体列表；这是一次性的低频操作（用户点击删除），开销可接受。
         */
        private static void disconnectAllUsingLabel(String rawLabel, java.util.UUID owner) {
            String label = LabelNetworkRegistry.normalizeLabel(rawLabel);
            if (label == null) return;
            for (net.minecraft.world.WorldServer ws : DimensionManager.getWorlds()) {
                if (ws == null || ws.isRemote) continue;
                for (Object o : ws.loadedTileEntityList.toArray()) {
                    if (!(o instanceof LabeledWirelessTransceiverTile)) continue;
                    LabeledWirelessTransceiverTile t = (LabeledWirelessTransceiverTile) o;
                    java.util.UUID tileOwner = WirelessTeamUtil.getNetworkOwnerUUID(ws, t.getPlacerId());
                    if (!owner.equals(tileOwner)) continue;
                    String tileLabel = LabelNetworkRegistry.normalizeLabel(t.getLabelForDisplay());
                    if (label.equals(tileLabel)) {
                        t.clearLabel();
                    }
                }
            }
        }

        /**
         * v0.5.4：让所有正在使用被重命名频道的已加载收发器跟随改名。
         * 与 {@link #disconnectAllUsingLabel} 相同的遍历方式：同归属者 + 标签（normalize 后）匹配旧名
         * 的收发器统一 applyLabel(新名)，保证各端点的显示名与频道列表一致。
         */
        private static void renameAllUsingLabel(String oldRawLabel, String newRawLabel, java.util.UUID owner) {
            String oldLabel = LabelNetworkRegistry.normalizeLabel(oldRawLabel);
            String newLabel = LabelNetworkRegistry.normalizeLabel(newRawLabel);
            if (oldLabel == null || newLabel == null) return;
            for (net.minecraft.world.WorldServer ws : DimensionManager.getWorlds()) {
                if (ws == null || ws.isRemote) continue;
                for (Object o : ws.loadedTileEntityList.toArray()) {
                    if (!(o instanceof LabeledWirelessTransceiverTile)) continue;
                    LabeledWirelessTransceiverTile t = (LabeledWirelessTransceiverTile) o;
                    java.util.UUID tileOwner = WirelessTeamUtil.getNetworkOwnerUUID(ws, t.getPlacerId());
                    if (!owner.equals(tileOwner)) continue;
                    String tileLabel = LabelNetworkRegistry.normalizeLabel(t.getLabelForDisplay());
                    if (oldLabel.equals(tileLabel)) {
                        t.applyLabel(newLabel);
                    }
                }
            }
        }
    }
}
