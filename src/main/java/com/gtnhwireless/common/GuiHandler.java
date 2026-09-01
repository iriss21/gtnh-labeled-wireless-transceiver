package com.gtnhwireless.common;

import com.gtnhwireless.client.gui.LabeledWirelessTransceiverGui;
import com.gtnhwireless.common.ae.wireless.LabelNetworkRegistry;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverContainer;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverTile;
import com.gtnhwireless.network.ChannelListS2CPacket;
import com.gtnhwireless.network.PacketHandler;
import com.gtnhwireless.reference.Reference;
import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI 分发。服务端返回 Container，客户端返回 Gui（并自行构造对应 Container）。
 *
 * 服务端打开 GUI 时，额外发送 {@link ChannelListS2CPacket} 把频道列表同步到客户端。
 */
public class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (id == Reference.GUI_LABELED_WIRELESS && te instanceof LabeledWirelessTransceiverTile) {
            LabeledWirelessTransceiverTile tile = (LabeledWirelessTransceiverTile) te;
            LabeledWirelessTransceiverContainer container = new LabeledWirelessTransceiverContainer(player, tile);

            // Populate channel list on server side
            List<LabelNetworkRegistry.LabelNetworkSnapshot> snapshots =
                    LabelNetworkRegistry.get(world).listNetworks(world, tile.getPlacerId());
            List<String> names = new ArrayList<>();
            List<Boolean> favorites = new ArrayList<>();
            for (LabelNetworkRegistry.LabelNetworkSnapshot s : snapshots) {
                names.add(s.label);
                favorites.add(s.favorite);
            }
            container.channelList = names;

            // Send channel list + current label echo to client
            if (player instanceof EntityPlayerMP) {
                PacketHandler.INSTANCE.sendTo(new ChannelListS2CPacket(
                        names, favorites, x, y, z, tile.getLabelForDisplay()), (EntityPlayerMP) player);
            }

            return container;
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (id == Reference.GUI_LABELED_WIRELESS && te instanceof LabeledWirelessTransceiverTile) {
            LabeledWirelessTransceiverTile t = (LabeledWirelessTransceiverTile) te;
            return new LabeledWirelessTransceiverGui(new LabeledWirelessTransceiverContainer(player, t), t);
        }
        return null;
    }
}
