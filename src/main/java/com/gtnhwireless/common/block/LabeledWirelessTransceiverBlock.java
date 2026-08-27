package com.gtnhwireless.common.block;

import appeng.api.implementations.items.IMemoryCard;
import appeng.api.implementations.items.MemoryCardMessages;
import appeng.util.Platform;
import appeng.util.SettingsFrom;
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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import com.google.common.collect.Lists;

import java.util.UUID;

/**
 * 标签无线收发器方块（本移植核心目标之一）。右键打开标签管理 GUI。
 *
 * v0.3.3 起：
 * - 方块材质随连接状态变化：TileEntity 的 updateState() 在“在线且已连上标签网络”时
 *   把 metadata 置 1，否则 0；本方块据此返回未连接（灰白机身 + 蓝色指示灯）或
 *   已连接（绿光指示灯）贴图。
 * - 使用 Material.piston（不要求工具）+ 低硬度：徒手即可挖掘并正常掉落。
 *
 * v0.5.0 起（对齐 AE2 交互惯例）：
 * - 手持扳手 + 蹲下右键 = 拆卸：把标签 / 频率 / 锁定 / 放置者写入掉落物物品 NBT，
 *   重新放置后恢复配置（频道归属不变）。锁定时拒绝拆卸并提示。
 * - 手持 AE 内存卡 + 蹲下右键 = 复制配置到卡片；+ 普通右键 = 粘贴配置到本方块。
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
                LabeledWirelessTransceiverTile tile = (LabeledWirelessTransceiverTile) te;
                EntityPlayer playerPlacer = (EntityPlayer) placer;
                NBTTagCompound tag = stack != null ? stack.getTagCompound() : null;
                if (tag != null && tag.hasKey(LabeledWirelessTransceiverTile.NBT_PLACER_HI)) {
                    // 扳手拆卸掉落物放置：恢复原放置者（频道归属不变）与配置
                    UUID owner = new UUID(
                            tag.getLong(LabeledWirelessTransceiverTile.NBT_PLACER_HI),
                            tag.getLong(LabeledWirelessTransceiverTile.NBT_PLACER_LO));
                    String ownerName = tag.hasKey(LabeledWirelessTransceiverTile.NBT_PLACER_NAME)
                            ? tag.getString(LabeledWirelessTransceiverTile.NBT_PLACER_NAME)
                            : playerPlacer.getCommandSenderName();
                    tile.setPlacerId(owner, ownerName);
                    tile.readSettingsFromStack(tag);
                } else {
                    tile.setPlacerId(playerPlacer.getGameProfile().getId(), playerPlacer.getCommandSenderName());
                }
            }
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hx, float hy, float hz) {
        if (world.isRemote) return true; // 全部交互逻辑在服务端执行
        if (player == null) return false;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof LabeledWirelessTransceiverTile)) return false;
        LabeledWirelessTransceiverTile tile = (LabeledWirelessTransceiverTile) te;
        ItemStack held = player.getCurrentEquippedItem();

        // 1) 扳手 + 蹲下右键 = 拆卸（对齐 AE2 AEBaseTileBlock 交互）
        if (held != null && player.isSneaking() && Platform.isWrench(player, held, x, y, z)) {
            if (tile.isLocked()) {
                player.addChatMessage(new ChatComponentTranslation("gtnhlabeledwireless.chat.locked"));
                return true;
            }
            return wrenchDismantle(world, x, y, z, player, tile);
        }

        // 2) AE 内存卡：蹲下 = 复制配置，普通右键 = 粘贴配置（对齐 AE2 AEBaseTileBlock 交互）
        if (held != null && held.getItem() instanceof IMemoryCard) {
            IMemoryCard card = (IMemoryCard) held.getItem();
            if (player.isSneaking()) {
                NBTTagCompound settings = tile.downloadSettings(SettingsFrom.MEMORY_CARD);
                if (settings != null) {
                    card.setMemoryCardContents(held, this.getUnlocalizedName(), settings);
                    card.notifyUser(player, MemoryCardMessages.SETTINGS_SAVED);
                    return true;
                }
            } else {
                String settingsName = card.getSettingsName(held);
                if (this.getUnlocalizedName().equals(settingsName)) {
                    NBTTagCompound data = card.getData(held);
                    if (data != null) {
                        tile.uploadSettings(SettingsFrom.MEMORY_CARD, data);
                        card.notifyUser(player, MemoryCardMessages.SETTINGS_LOADED);
                        return true;
                    }
                } else {
                    card.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
                    return false;
                }
            }
        }

        // 3) 打开标签管理 GUI
        player.openGui(GTNHWireless.instance, Reference.GUI_LABELED_WIRELESS, world, x, y, z);
        return true;
    }

    /**
     * 扳手拆卸：把配置写入掉落物物品 NBT 并移除方块（对齐 AE2 AEBaseTileBlock 的扳手分支）。
     * 仅服务端调用（onBlockActivated 已保证）。
     */
    private boolean wrenchDismantle(World world, int x, int y, int z, EntityPlayer player, LabeledWirelessTransceiverTile tile) {
        Block block = world.getBlock(x, y, z);
        if (block == null) return false;

        // 尊重保护插件（领地等）：BreakEvent 被取消则中止
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(x, y, z, world, block, 0, player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return false;

        ItemStack[] drops = Platform.getBlockDrops(world, x, y, z);
        ItemStack reference = new ItemStack(this);
        if (drops != null) {
            for (ItemStack drop : drops) {
                if (drop != null && Platform.isSameItemType(drop, reference)) {
                    NBTTagCompound settings = tile.downloadSettings(SettingsFrom.DISMANTLE_ITEM);
                    if (settings != null) {
                        drop.setTagCompound(settings);
                    }
                }
            }
        }

        if (block.removedByPlayer(world, player, x, y, z, false)) {
            if (drops != null) {
                Platform.spawnDrops(world, x, y, z, Lists.newArrayList(drops));
            }
            world.setBlockToAir(x, y, z);
        }
        return true;
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
