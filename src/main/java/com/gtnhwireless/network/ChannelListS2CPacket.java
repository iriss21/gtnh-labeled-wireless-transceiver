package com.gtnhwireless.network;

import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverContainer;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表同步包（服务端 -> 客户端）。
 * 在玩家打开 GUI 或每次操作后由服务端发送，把该玩家所有者下的频道名称列表传到客户端。
 *
 * v0.3.3 起额外携带“当前标签回显”：服务端处理完一次操作（设置 / 清除 / 删除频道）后，
 * 把该收发器的权威当前标签一并推回。客户端 GUI 用它消除“乐观更新与方块实体描述包
 * 之间的竞态”——之前切换频道后偶尔显示“无”，就是因为描述包尚未到达时 GUI 读到了
 * 客户端方块实体的旧值（可能为 null）。
 *
 * 注意：服务端在 getServerGuiElement 中发送此包时，客户端窗口尚未打开，
 * openContainer 仍是旧容器，若用 openContainer 判断会丢弃首包导致列表不显示。
 * 因此这里写入客户端静态缓存 {@link #LATEST} / {@link #LATEST_CURRENT}，
 * GUI 在 updateScreen 中轮询它们刷新，不依赖窗口是否已打开。
 */
public class ChannelListS2CPacket implements IMessage {

    /** 客户端静态缓存：最近一次收到的频道列表。GUI 直接读取它。 */
    public static volatile List<String> LATEST = new ArrayList<>();

    /**
     * 客户端静态缓存：最近一次收到的收藏频道名集合（v0.5.8）。
     * 与 {@link #LATEST} 平行（同一包携带）；GUI 用它排序置顶 + 禁用删除 + 画星标。
     */
    public static volatile java.util.Set<String> LATEST_FAVORITES = new java.util.HashSet<>();

    /**
     * 客户端静态缓存：最近一次收到的“某收发器当前标签”权威回显。
     * GUI 消费一次后即置 null（避免过期回显反复覆盖新状态）。
     */
    public static volatile CurrentLabel LATEST_CURRENT = null;

    private List<String> channels = new ArrayList<>();
    private List<Boolean> favorites = null;
    private CurrentLabel current = null;

    public ChannelListS2CPacket() {}

    public ChannelListS2CPacket(List<String> channels) {
        this.channels = channels;
    }

    /**
     * 带当前标签回显的构造。
     *
     * @param currentLabel 该收发器服务端的权威当前标签；null 表示无标签（已断开）
     */
    public ChannelListS2CPacket(List<String> channels, int x, int y, int z, String currentLabel) {
        this.channels = channels;
        this.current = new CurrentLabel(x, y, z, currentLabel);
    }

    /**
     * 带当前标签回显 + 收藏状态列表的构造（v0.5.8）。
     * favorites 与 channels 一一对应（favorite == true 表示该频道已收藏）；
     * 传 null 表示不带收藏信息（旧调用方保持兼容）。
     */
    public ChannelListS2CPacket(List<String> channels, List<Boolean> favorites, int x, int y, int z, String currentLabel) {
        this.channels = channels;
        this.favorites = favorites;
        this.current = new CurrentLabel(x, y, z, currentLabel);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(channels.size());
        for (String s : channels) {
            writeStr(buf, s);
        }
        buf.writeBoolean(favorites != null);
        if (favorites != null) {
            for (Boolean f : favorites) {
                buf.writeBoolean(f != null && f);
            }
        }
        buf.writeBoolean(current != null);
        if (current != null) {
            buf.writeInt(current.x);
            buf.writeInt(current.y);
            buf.writeInt(current.z);
            writeStr(buf, current.label);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        channels = new ArrayList<>();
        int count = buf.readShort() & 0xFFFF;
        for (int i = 0; i < count; i++) {
            channels.add(readStr(buf));
        }
        favorites = null;
        if (buf.readBoolean()) {
            favorites = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                favorites.add(buf.readBoolean());
            }
        }
        if (buf.readBoolean()) {
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            String label = readStr(buf);
            this.current = new CurrentLabel(x, y, z, label);
        } else {
            this.current = null;
        }
    }

    public List<String> getChannels() {
        return channels;
    }

    /** 收藏状态列表（与 getChannels() 平行）；null 表示本包未携带收藏信息。 */
    public List<Boolean> getFavorites() {
        return favorites;
    }

    public CurrentLabel getCurrent() {
        return current;
    }

    private static void writeStr(ByteBuf buf, String s) {
        if (s == null) {
            buf.writeShort(-1);
        } else {
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    private static String readStr(ByteBuf buf) {
        int len = buf.readShort();
        if (len < 0) return null;
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 某个收发器的权威当前标签（坐标 + 标签值，label 为 null 表示无标签）。 */
    public static final class CurrentLabel {

        public final int x, y, z;
        public final String label;

        public CurrentLabel(int x, int y, int z, String label) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.label = label;
        }

        public boolean matches(int x, int y, int z) {
            return this.x == x && this.y == y && this.z == z;
        }
    }

    public static class Handler implements IMessageHandler<ChannelListS2CPacket, IMessage> {

        @Override
        public IMessage onMessage(ChannelListS2CPacket msg, MessageContext ctx) {
            // 总是写入客户端静态缓存，不依赖窗口是否打开（避免首包被 openContainer 判断丢弃）
            LATEST = new ArrayList<>(msg.getChannels());
            // v0.5.8：收藏集合同步；本包未携带收藏信息时清空（避免残留过期收藏）
            if (msg.getFavorites() != null) {
                java.util.Set<String> favs = new java.util.HashSet<>();
                List<String> chans = msg.getChannels();
                List<Boolean> favList = msg.getFavorites();
                for (int i = 0; i < chans.size() && i < favList.size(); i++) {
                    if (Boolean.TRUE.equals(favList.get(i))) {
                        favs.add(chans.get(i));
                    }
                }
                LATEST_FAVORITES = favs;
            } else {
                LATEST_FAVORITES = new java.util.HashSet<>();
            }
            LATEST_CURRENT = msg.getCurrent();
            // 同时若窗口已打开，也更新容器（兼容旧逻辑）
            if (Minecraft.getMinecraft().thePlayer != null
                    && Minecraft.getMinecraft().thePlayer.openContainer
                            instanceof LabeledWirelessTransceiverContainer) {
                LabeledWirelessTransceiverContainer container =
                        (LabeledWirelessTransceiverContainer) Minecraft.getMinecraft().thePlayer.openContainer;
                container.channelList = new ArrayList<>(msg.getChannels());
            }
            return null;
        }
    }
}
