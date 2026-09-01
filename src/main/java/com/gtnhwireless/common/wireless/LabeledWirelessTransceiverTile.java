package com.gtnhwireless.common.wireless;

import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.util.SettingsFrom;
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
 * 标签无线收发器的 TileEntity 实现，通过虚拟枢纽将多个收发器合并为一个 AE2 网格。
 */
public class LabeledWirelessTransceiverTile extends AbstractWirelessTile {

    private long frequency = 0L;
    private String labelForDisplay;
    private final LabelLink labelLink = new LabelLink(this);

    /**
     * 锁定状态（v0.5.0）。锁定时：
     * - 扳手蹲下右键无法拆卸（拆卸请求被拒绝并提示玩家）；
     * - 仍可正常徒手/任意工具挖掘（防误拆，非防破坏）。
     * 持久化到 NBT 并随描述包同步，GUI 提供锁定切换按钮。
     */
    private boolean locked;

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
     * 采纳新标签而不重新注册（v0.5.11，仅频道重命名传播使用）。
     *
     * 与 {@link #applyLabel} 的区别：applyLabel 会 unregister + register（按当前归属派生
     * 重新查/建网络），在归属派生不一致的时序下可能把 tile 误移到另一个同标签网络；
     * 而频道重命名时网络对象本身不变（channel / endpoints / virtualHost 全部保留），
     * tile 已通过 labelLink 连在该网络上，只需更新显示标签并同步即可。
     * 因此本方法只改 labelForDisplay + 持久化 + 描述包同步，不动 frequency 与链路，
     * 任何归属派生下都不会改变 tile 所属网络。
     */
    public void adoptRenamedLabel(String newLabel) {
        if (worldObj == null || worldObj.isRemote) return;
        if (newLabel == null) return;
        if (newLabel.equals(this.labelForDisplay)) return;
        this.labelForDisplay = newLabel;
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
            // v0.5.4：频道被重命名后旧名已不存在。若旧名能解析到新名（重命名别名），
            // 跟随新名重新接入，而不是清空标签掉线（未加载区块的收发器重载时走到这里）。
            String renamed = reg.resolveRenamedLabel(worldObj, labelForDisplay, placerId);
            if (renamed != null) {
                applyLabel(renamed);
                return;
            }
            // v0.5.11：标签 / 别名都查不到时，按频道号找回真实网络（channel 是网络身份，
            // 不依赖归属派生与标签表示）。命中说明 tile 仍连在该网络（如重命名传播漏改、
            // 或注册时归属派生与当前不一致导致标签查不到）——采纳该网络当前标签并保持原网络。
            LabelNetworkRegistry.LabelNetwork byChannel = reg.getNetworkByChannel(this.frequency);
            if (byChannel != null) {
                adoptRenamedLabel(byChannel.label());
                return;
            }
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

    public boolean isLocked() {
        return locked;
    }

    /** 切换锁定状态（服务端 GUI 按钮调用）。持久化并同步描述包。 */
    public void setLocked(boolean locked) {
        if (this.locked == locked) return;
        this.locked = locked;
        saveChanges();
        syncToClient();
    }

    /* ===================== AE 内存卡 / 扳手拆卸 设置存取（v0.5.0） =====================
     * AE2 的内存卡（IMemoryCard）与扳手拆卸（DISMANTLE_ITEM）都通过
     * AEBaseTile.downloadSettings/uploadSettings 读写方块配置。本类把标签、
     * 频率、锁定状态纳入其中：内存卡可复制粘贴整套配置；扳手拆卸会把配置
     * 写进掉落物物品的 NBT，重新放置时由 Block.onBlockPlacedBy 恢复。
     */

    /** 内存卡 / 掉落物上保存本收发器配置的 NBT 键。 */
    public static final String NBT_LABEL = "lwLabel";
    public static final String NBT_FREQUENCY = "lwFrequency";
    public static final String NBT_LOCKED = "lwLocked";
    public static final String NBT_PLACER_HI = "lwPlacerHi";
    public static final String NBT_PLACER_LO = "lwPlacerLo";
    public static final String NBT_PLACER_NAME = "lwPlacerName";

    @Override
    public NBTTagCompound downloadSettings(SettingsFrom from) {
        NBTTagCompound tag = super.downloadSettings(from);
        if (tag == null) tag = new NBTTagCompound();
        if (labelForDisplay != null && !labelForDisplay.isEmpty()) {
            tag.setString(NBT_LABEL, labelForDisplay);
        }
        tag.setLong(NBT_FREQUENCY, frequency);
        tag.setBoolean(NBT_LOCKED, locked);
        // 扳手拆卸：把放置者信息一并写入掉落物，重新放置后频道归属不变。
        if (from == SettingsFrom.DISMANTLE_ITEM) {
            if (placerId != null) {
                tag.setLong(NBT_PLACER_HI, placerId.getMostSignificantBits());
                tag.setLong(NBT_PLACER_LO, placerId.getLeastSignificantBits());
            }
            if (placerName != null) {
                tag.setString(NBT_PLACER_NAME, placerName);
            }
        }
        return tag;
    }

    @Override
    public void uploadSettings(SettingsFrom from, NBTTagCompound tag) {
        super.uploadSettings(from, tag);
        if (tag == null) return;
        this.locked = tag.getBoolean(NBT_LOCKED);
        String label = tag.hasKey(NBT_LABEL) ? tag.getString(NBT_LABEL) : null;
        if (label != null && !label.isEmpty()) {
            applyLabel(label);
        } else {
            clearLabel();
        }
    }

    /** 从（扳手拆卸掉落物）物品 NBT 恢复配置：锁定 + 标签（重新注册接入网络）。 */
    public void readSettingsFromStack(NBTTagCompound tag) {
        if (tag == null) return;
        this.locked = tag.getBoolean(NBT_LOCKED);
        String label = tag.hasKey(NBT_LABEL) ? tag.getString(NBT_LABEL) : null;
        if (label != null && !label.isEmpty() && !label.equals(this.labelForDisplay)) {
            applyLabel(label);
        }
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
     * 标签一致性检查节流（v0.5.11）：每 100 tick（≈5 秒）按频道号核对一次
     * labelForDisplay 与真实网络标签是否一致，不一致则采纳网络当前标签。
     * 用于自愈「重命名传播漏改 / 归属派生漂移」导致已连接端点仍显示旧名的场景。
     */
    private int labelCheckTimer;

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
                // v0.5.4：旧名可能是频道重命名前的名称——解析新名并跟随，避免重连时被误清空
                String renamed = LabelNetworkRegistry.get(worldObj)
                        .resolveRenamedLabel(worldObj, labelForDisplay, placerId);
                if (renamed != null) {
                    applyLabel(renamed);
                } else {
                    // v0.5.11：别名查不到时按频道号找回真实网络（channel 是网络身份，
                    // 不依赖归属派生），命中则采纳其当前标签并保持原网络，避免误清空。
                    LabelNetworkRegistry.LabelNetwork byChannel = LabelNetworkRegistry.get(worldObj)
                            .getNetworkByChannel(this.frequency);
                    if (byChannel != null) {
                        adoptRenamedLabel(byChannel.label());
                    } else {
                        // v0.4.2：网络已被删除（register 不再自动重建，见 refreshLabel）——
                        // 自动重连无意义，清空标签彻底断开，避免残留“有标签但连不上”的中间态。
                        clearLabel();
                    }
                }
            }
        }
        wasConnected = connected;

        // v0.5.11：周期性标签一致性核对（每 100 tick ≈ 5 秒）。
        // 重命名传播按频道号/端点表覆盖所有已加载端点，但此前漏改 / 归属派生漂移的
        // 已连接端点不会走上面的重连分支，需要主动核对：labelForDisplay 与其真实网络
        // （频道号唯一确定）标签不一致时，采纳网络当前标签自愈。
        if (++labelCheckTimer >= 100) {
            labelCheckTimer = 0;
            reconcileLabelWithChannel();
        }

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

    /**
     * v0.5.11：按频道号核对本端点的显示标签与真实网络标签是否一致，不一致则采纳网络当前标签。
     *
     * channel 是网络的唯一身份（单调分配、不复用、重命名不变），比标签字符串与归属派生
     * 都权威：本端点 frequency 指向的网络，就是它实际所在的网络。若网络已被重命名而本端点
     * 显示旧名（传播漏改 / 归属派生漂移），此处直接采纳网络当前标签，保持原网络与频道不变，
     * 且不依赖 ServerUtilities 队伍查询的时序一致性。
     */
    private void reconcileLabelWithChannel() {
        if (worldObj == null || worldObj.isRemote) return;
        if (frequency <= 0) return;
        LabelNetworkRegistry reg = LabelNetworkRegistry.get(worldObj);
        LabelNetworkRegistry.LabelNetwork net = reg.getNetworkByChannel(frequency);
        if (net == null || net.isDeleted()) return;
        String current = LabelNetworkRegistry.normalizeLabel(labelForDisplay);
        if (net.label().equals(current)) return; // 一致
        adoptRenamedLabel(net.label());
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
        tag.setBoolean("locked", locked);
        if (labelForDisplay != null) {
            tag.setString("label", labelForDisplay);
        }
    }

    @Override
    protected void readWirelessNBT(NBTTagCompound tag) {
        this.frequency = tag.getLong("frequency");
        this.locked = tag.getBoolean("locked");
        this.labelForDisplay = tag.hasKey("label") ? tag.getString("label") : null;
    }

    @Override
    protected void writeWirelessStream(io.netty.buffer.ByteBuf buf) {
        buf.writeLong(frequency);
        buf.writeBoolean(locked);
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
        this.locked = buf.readBoolean();
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
