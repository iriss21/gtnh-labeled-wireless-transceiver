package com.gtnhwireless.common.wireless;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

/**
 * 标签收发器配置容器。
 *
 * 这是纯配置 GUI（无方块自身库存，也不需要玩家背包），
 * 故不绑定任何物品槽，避免背包物品渲染在配置面板后面造成视觉重叠。
 */
public class LabeledWirelessTransceiverContainer extends Container {

    public final LabeledWirelessTransceiverTile tile;
    public final EntityPlayer player;
    /** 频道列表快照（客户端由 S2C 包填充，服务端在打开时填充）。 */
    public java.util.List<String> channelList = new java.util.ArrayList<>();

    public LabeledWirelessTransceiverContainer(EntityPlayer player, LabeledWirelessTransceiverTile tile) {
        this.player = player;
        this.tile = tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return this.tile != null
                && !this.tile.isInvalid()
                && this.tile.getWorldObj() != null
                && this.tile.getWorldObj().getTileEntity(
                this.tile.xCoord, this.tile.yCoord, this.tile.zCoord) == this.tile;
    }
}
