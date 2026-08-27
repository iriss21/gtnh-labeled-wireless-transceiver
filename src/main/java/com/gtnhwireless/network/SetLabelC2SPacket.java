package com.gtnhwireless.network;

import com.gtnhwireless.common.ae.wireless.LabelNetworkRegistry;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverTile;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 标签收发器配置包（客户端 GUI -> 服务端）。
 * 携带坐标 + 标签字符串（空字符串表示清除标签）。
 */
public class SetLabelC2SPacket implements IMessage {

    private int x, y, z;
    private String label;

    public SetLabelC2SPacket() {}

    public SetLabelC2SPacket(int x, int y, int z, String label) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.label = label == null ? "" : label;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        int len = buf.readShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        this.label = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<SetLabelC2SPacket, IMessage> {

        @Override
        public IMessage onMessage(SetLabelC2SPacket msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            World world = player.worldObj;
            if (world == null || world.isRemote) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof LabeledWirelessTransceiverTile) {
                LabeledWirelessTransceiverTile tile = (LabeledWirelessTransceiverTile) te;
                if (msg.label == null || msg.label.isEmpty()) {
                    tile.clearLabel();
                } else {
                    tile.applyLabel(msg.label);
                }
                // 把最新频道列表 + 本收发器当前标签权威回显推回客户端，
                // 确保 GUI 列表与"当前"显示实时刷新（消除描述包竞态）
                syncChannelList(world, player, tile);
            }
            return null;
        }

        private static void syncChannelList(World world, EntityPlayerMP player,
                                            LabeledWirelessTransceiverTile tile) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (LabelNetworkRegistry.LabelNetworkSnapshot s :
                    LabelNetworkRegistry.get(world).listNetworks(world, tile.getPlacerId())) {
                names.add(s.label);
            }
            PacketHandler.INSTANCE.sendTo(new ChannelListS2CPacket(
                    names, tile.xCoord, tile.yCoord, tile.zCoord, tile.getLabelForDisplay()), player);
        }
    }

    /** 频道管理操作类型（删除/重命名），与 {@link ChannelActionC2SPacket} 保持一致。 */
    public static final int ACTION_DELETE = ChannelActionC2SPacket.ACTION_DELETE;
    public static final int ACTION_RENAME = ChannelActionC2SPacket.ACTION_RENAME;

    /**
     * 便捷发送方法：由 GUI 调用，内部构造并发送 {@link ChannelActionC2SPacket}，
     * 避免 GUI 直接依赖该类型（规避特定编译环境下 GUI 跨包无法解析该类的问题）。
     */
    public static void sendChannelAction(int action, int x, int y, int z, String label, String newName) {
        PacketHandler.INSTANCE.sendToServer(new ChannelActionC2SPacket(
                action, x, y, z, label == null ? "" : label, newName == null ? "" : newName));
    }
}
