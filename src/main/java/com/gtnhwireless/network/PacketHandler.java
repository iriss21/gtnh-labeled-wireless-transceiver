package com.gtnhwireless.network;

import com.gtnhwireless.reference.Reference;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 网络通道注册。1.7.10 使用 Forge 的 simpleimpl。
 */
public final class PacketHandler {

    public static final SimpleNetworkWrapper INSTANCE =
            NetworkRegistry.INSTANCE.newSimpleChannel(Reference.NETWORK_CHANNEL);

    public static void init() {
        INSTANCE.registerMessage(
                SetLabelC2SPacket.Handler.class,
                SetLabelC2SPacket.class,
                0, Side.SERVER);
        INSTANCE.registerMessage(
                ChannelActionC2SPacket.Handler.class,
                ChannelActionC2SPacket.class,
                1, Side.SERVER);
        INSTANCE.registerMessage(
                ChannelListS2CPacket.Handler.class,
                ChannelListS2CPacket.class,
                2, Side.CLIENT);
    }

    private PacketHandler() {}
}
