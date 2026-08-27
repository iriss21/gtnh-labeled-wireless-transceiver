package com.gtnhwireless.common.block;

import com.gtnhwireless.GTNHWireless;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverTile;
import com.gtnhwireless.reference.Reference;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 标签无线收发器方块（本移植核心目标之一）。右键打开标签管理 GUI。
 *
 * v0.3.3 起：
 * - 方块材质随连接状态变化：TileEntity 的 updateState() 在“在线且已连上标签网络”时
 *   把 metadata 置 1，否则 0；本方块据此返回未连接（灰白机身 + 蓝色指示灯）或
 *   已连接（绿光指示灯）贴图。
 * - 使用 Material.piston（不要求工具）+ 低硬度：徒手即可挖掘并正常掉落。
 */
public class LabeledWirelessTransceiverBlock extends Block implements ITileEntityProvider {

    /** 未连接贴图（物品形态亦使用此贴图）。 */
    private IIcon iconOff;
    /** 已连接贴图（metadata 最低位为 1 时使用）。 */
    private IIcon iconOn;

    public LabeledWirelessTransceiverBlock() {
        // Material.piston：isToolNotRequired == true，徒手可挖且正常掉落；
        // 同时该材质不可被活塞推动，符合 AE2 网络机器的惯例。
        super(Material.piston);
        setBlockName(Reference.MODID + ".labeled_wireless_transceiver");
        setHardness(0.5F);
        setResistance(10.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new LabeledWirelessTransceiverTile();
    }

    @Override
    public void registerBlockIcons(IIconRegister reg) {
        this.iconOff = reg.registerIcon(Reference.MODID + ":labeled_wireless_transceiver");
        this.iconOn = reg.registerIcon(Reference.MODID + ":labeled_wireless_transceiver_on");
    }

    @Override
    public IIcon getIcon(int side, int meta) {
        return (meta & 1) == 1 ? this.iconOn : this.iconOff;
    }

    @Override
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        return (world.getBlockMetadata(x, y, z) & 1) == 1 ? this.iconOn : this.iconOff;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        if (!world.isRemote && placer instanceof EntityPlayer) {
            TileEntity te = world.getTileEntity(x, y, z);
            if (te instanceof LabeledWirelessTransceiverTile) {
                EntityPlayer playerPlacer = (EntityPlayer) placer;
                ((LabeledWirelessTransceiverTile) te).setPlacerId(playerPlacer.getGameProfile().getId(), playerPlacer.getCommandSenderName());
            }
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hx, float hy, float hz) {
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof LabeledWirelessTransceiverTile) {
            player.openGui(GTNHWireless.instance, Reference.GUI_LABELED_WIRELESS, world, x, y, z);
            return true;
        }
        return false;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof LabeledWirelessTransceiverTile) {
            ((LabeledWirelessTransceiverTile) te).onRemoved();
        }
        super.breakBlock(world, x, y, z, block, meta);
    }
}
