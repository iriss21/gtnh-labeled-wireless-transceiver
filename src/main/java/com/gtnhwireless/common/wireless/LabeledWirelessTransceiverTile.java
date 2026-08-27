package com.gtnhwireless.common.wireless;

import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import com.gtnhwireless.common.ModContent;
import com.gtnhwireless.common.ae.wireless.LabelLink;
import com.gtnhwireless.common.ae.wireless.LabelNetworkRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * 标签无线收发器方块实体（本移植的核心目标之一）。
 *
 * 通过 {@link LabelNetworkRegistry} 把同标签的所有收发器连到同一个虚拟枢纽节点，
 * 从而把多个本地 AE2 网络合并成一个网格，实现跨距离无线共享存储 / 合成。
 *
 * 移植自 EAEP 的 LabeledWirelessTransceiverBlockEntity。
 */
public class LabeledWirelessTransceiverTile extends AbstractWirelessTile {

    private long frequency = 0L;
    private String labelForDisplay;
    private final LabelLink labelLink = new LabelLink(this);

    public LabeledWirelessTransceiverTile() {
        super(ModContent.labeledWirelessTransceiver != null
                ? new ItemStack(ModContent.labeledWirelessTransceiver) : null);
    }

    public long getFrequency() {
        return frequency;
    }

    public String getLabelForDisplay() {
        return labelForDisplay;
    }

    public void applyLabel(String rawLabel) {
        if (worldObj == null || worldObj.isRemote) return;
        LabelNetworkRegistry.get(worldObj).unregister(this);
        LabelNetworkRegistry.LabelNetwork net = LabelNetworkRegistry.get(worldObj)
                .register(worldObj, rawLabel, placerId, this);
        if (net == null) {
            clearLabel();
            return;
        }
        this.labelForDisplay = rawLabel;
        this.frequency = net.channel();
        this.labelLink.setTarget(net);
        updateState();
        saveChanges();
        syncToClient();
    }

    public void clearLabel() {
        if (worldObj != null && !worldObj.isRemote) {
            LabelNetworkRegistry.get(worldObj).unregister(this);
        }
        this.labelForDisplay = null;
        this.frequency = 0L;
        this.labelLink.clearTarget();
        updateState();
        saveChanges();
        syncToClient();
    }

    public void refreshLabel(boolean ensureRegister) {
        if (worldObj == null || worldObj.isRemote) return;
        if (labelForDisplay == null || labelForDisplay.isEmpty()) {
            this.frequency = 0L;
            this.labelLink.clearTarget();
            updateState();
            return;
        }
        LabelNetworkRegistry reg = LabelNetworkRegistry.get(worldObj);
        LabelNetworkRegistry.LabelNetwork net = ensureRegister
                ? reg.register(worldObj, labelForDisplay, placerId, this)
                : reg.getNetwork(worldObj, labelForDisplay, placerId);
        if (net == null) {
            this.frequency = 0L;
            this.labelLink.clearTarget();
        } else {
            net.ensureVirtualNode();
            this.frequency = net.channel();
            this.labelLink.setTarget(net);
        }
        updateState();
        saveChanges();
        syncToClient();
    }

    @Override
    protected void onWirelessReady() {
        refreshLabel(true);
    }

    @Override
    protected void onWirelessRemoved() {
        clearLabel();
    }

    @Override
    protected void onWirelessUnload() {
        // chunk 卸载：轻量反注册（不触发 saveChanges/syncToClient，卸载期间保存有风险）
        if (worldObj == null || worldObj.isRemote) return;
        LabelNetworkRegistry.get(worldObj).unregister(this);
        this.labelLink.onUnloadOrRemove();
    }

    /** 上一 tick 的链路状态，用于检测“刚重连”翻转以触发存储缓存自愈。 */
    private boolean wasConnected;

    /** 断线重试冷却：目标枢纽节点失效（世界卸载清理）后周期性重建，避免每 tick 重试。 */
    private int reconnectCooldown;

    @Override
    public void wirelessTick() {
        if (worldObj == null || worldObj.isRemote) return;
        if (reconnectCooldown > 0) reconnectCooldown--;

        this.labelLink.updateStatus();
        boolean connected = this.labelLink.isConnected();
        if (connected && !wasConnected) {
            // 链路刚建立：可能发生在初次接入、chunk 重载重连、或跨维度网格分裂-合并之后。
            // 分裂-合并往返中 GridStorageCache 的存储缓存可能残留陈旧状态（AE2 rv3 的
            // onSplit/onJoin/populateGridStorage 是空实现，缓存不自迁移），必须强制触发
            // MENetworkCellArrayUpdate 让 cellUpdate 全量重建 myItemNetwork 并 forceUpdate
            // 监视器，使缓存与真实 cell 内容一致——否则会出现“取物只减一侧”的刷物品漏洞。
            forceCellCacheRefresh();
        } else if (!connected && labelForDisplay != null && reconnectCooldown == 0) {
            // 断开且目标枢纽可能失效（如虚拟枢纽节点被世界卸载清理）：
            // 周期性重建虚拟枢纽并重设目标，让链路自动恢复（下线后断连的根治）。
            reconnectCooldown = 20;
            LabelNetworkRegistry.LabelNetwork net = LabelNetworkRegistry.get(worldObj)
                    .getNetwork(worldObj, labelForDisplay, placerId);
            if (net != null) {
                net.ensureVirtualNode();
                this.frequency = net.channel();
                this.labelLink.setTarget(net);
            }
        }
        wasConnected = connected;
        updateState();
    }

    /** 触发所在网格的存储缓存全量自愈（置空 handler + 重评全部 cell provider + 监视器强刷）。 */
    private void forceCellCacheRefresh() {
        IGridNode node = getGridNode();
        if (node == null) return;
        appeng.api.networking.IGrid grid = node.getGrid();
        if (grid == null) return;
        grid.postEvent(new appeng.api.networking.events.MENetworkCellArrayUpdate());
    }

    @Override
    protected void updateState() {
        if (worldObj == null || worldObj.isRemote || beingRemoved) return;
        net.minecraft.block.Block b = getBlockType();
        if (worldObj.getBlock(xCoord, yCoord, zCoord) != b) return;

        boolean online = false;
        IGridNode node = getGridNode();
        if (node != null && node.isActive()) {
            try {
                IEnergyGrid energy = node.getGrid().getCache(IEnergyGrid.class);
                online = energy != null && energy.isNetworkPowered();
            } catch (Throwable ignored) {
                online = false;
            }
        }
        int newMeta = (online && labelLink.isConnected()) ? 1 : 0;
        if (worldObj.getBlockMetadata(xCoord, yCoord, zCoord) != newMeta) {
            worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, newMeta, 3);
        }
    }

    @Override
    protected void writeWirelessNBT(NBTTagCompound tag) {
        tag.setLong("frequency", frequency);
        if (labelForDisplay != null) {
            tag.setString("label", labelForDisplay);
        }
    }

    @Override
    protected void readWirelessNBT(NBTTagCompound tag) {
        this.frequency = tag.getLong("frequency");
        this.labelForDisplay = tag.hasKey("label") ? tag.getString("label") : null;
    }

    @Override
    protected void writeWirelessStream(io.netty.buffer.ByteBuf buf) {
        buf.writeLong(frequency);
        if (labelForDisplay == null) {
            buf.writeShort(-1);
        } else {
            byte[] b = labelForDisplay.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(b.length);
            buf.writeBytes(b);
        }
    }

    @Override
    protected void readWirelessStream(io.netty.buffer.ByteBuf buf) {
        this.frequency = buf.readLong();
        int len = buf.readShort();
        if (len < 0) {
            this.labelForDisplay = null;
        } else {
            byte[] b = new byte[len];
            buf.readBytes(b);
            this.labelForDisplay = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
