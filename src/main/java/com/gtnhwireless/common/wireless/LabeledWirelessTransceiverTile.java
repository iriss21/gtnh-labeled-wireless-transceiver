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

    /**
     * 恢复标签链路（节点就绪 / chunk 重载 / 服务器启动后调用）。
     *
     * v0.4.2 起只“查”不“建”：网络存在则重连；网络不存在（已被删除、或从未创建）
     * 则清空标签彻底断开。此前用 register 兜底注册，导致删除频道后，未加载区块中的
     * 收发器在重载时通过本方法把已删除的频道“复活”（register 在网络不存在时会新建网络）。
     */
    public void refreshLabel() {
        if (worldObj == null || worldObj.isRemote) return;
        if (labelForDisplay == null || labelForDisplay.isEmpty()) {
            this.frequency = 0L;
            this.labelLink.clearTarget();
            updateState();
            return;
        }
        LabelNetworkRegistry reg = LabelNetworkRegistry.get(worldObj);
        LabelNetworkRegistry.LabelNetwork net = reg.getNetwork(worldObj, labelForDisplay, placerId);
        if (net == null) {
            // 网络已不存在：彻底断开并清空标签（删除频道后未加载收发器重载时走到这里）。
            clearLabel();
            return;
        }
        net.ensureVirtualNode();
        this.frequency = net.channel();
        this.labelLink.setTarget(net);
        updateState();
        saveChanges();
        syncToClient();
    }

    @Override
    protected void onWirelessReady() {
        refreshLabel();
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

    /**
     * 状态检查节流计数器（v0.4.2 TPS 优化）：方块 meta（连接指示材质）不需要每 tick
     * 全量重查网格能量——每 5 tick 或链路翻转时刷新一次，把每端点的网格查询从
     * 20 次/秒 降到 4 次/秒；连接翻转时仍立即更新，材质响应无感知延迟。
     */
    private int stateTimer;

    @Override
    public void wirelessTick() {
        if (worldObj == null || worldObj.isRemote) return;
        if (reconnectCooldown > 0) reconnectCooldown--;

        this.labelLink.updateStatus();
        boolean connected = this.labelLink.isConnected();
        boolean flipped = connected != wasConnected;
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
            } else {
                // v0.4.2：网络已被删除（register 不再自动重建，见 refreshLabel）——
                // 自动重连无意义，清空标签彻底断开，避免残留“有标签但连不上”的中间态。
                clearLabel();
            }
        }
        wasConnected = connected;

        // TPS 节流：非翻转状态下每 5 tick 才重查一次方块状态；翻转立即刷新。
        if (flipped || ++stateTimer >= 5) {
            stateTimer = 0;
            updateState();
        }
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
