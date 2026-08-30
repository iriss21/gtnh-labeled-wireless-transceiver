package com.gtnhwireless.common.wireless;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.AEBaseTile;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import com.gtnhwireless.common.ae.wireless.IWirelessEndpoint;
import com.gtnhwireless.common.ae.wireless.IWirelessTicker;
import com.gtnhwireless.common.ae.wireless.WirelessActiveRegistry;
import com.gtnhwireless.reference.Reference;
import io.netty.buffer.ByteBuf;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 无线收发器方块实体的抽象基类。
 *
 * 统一处理：
 * - AE2 网格节点生命周期（通过 {@link AENetworkProxy}：onReady / onChunkUnload / invalidate）；
 * - 放置者信息（placerId / placerName）；
 * - 活动注册（加入 WirelessActiveRegistry 供服务端周期 tick）；
 * - 常见 NBT 读写骨架与描述包同步（客户端 GUI 用）。
 *
 * 1.7.10 没有 IManagedGridNode / GridHelper.createManagedNode（那是 AE2 1.20 的 API），
 * 改为持有 AENetworkProxy 并手动管理 onReady/invalidate。
 *
 * 关键的 AE2 1.7.10 约定：
 * - AEBaseTile 把 writeToNBT / readFromNBT（func_145841_b / func_145839_a）声明为 final，
 *   子类不能重写；自定义 NBT 必须通过 {@link TileEvent}(WORLD_NBT_WRITE / WORLD_NBT_READ) 钩子持久化。
 * - 节点激活链路：游戏 addTileEntity -> validate() -> gridProxy.validate()
 *   （向 AE2 的 TickHandler 注册）-> 下次服务端 tick 由 AE2 调 onReady() -> gridProxy.onReady()
 *   创建并把网格节点接入网络。因此本类必须重写 validate() 调用 gridProxy.validate()。
 */
public abstract class AbstractWirelessTile extends AEBaseTile
        implements IGridProxyable, IWirelessEndpoint, IWirelessTicker {

    protected final AENetworkProxy gridProxy;
    protected boolean beingRemoved;
    protected UUID placerId;
    protected String placerName;

    protected AbstractWirelessTile(ItemStack visual) {
        this.gridProxy = new AENetworkProxy(this, "wireless_node", visual, true);
        this.gridProxy.setFlags(appeng.api.networking.GridFlags.DENSE_CAPACITY);
        this.gridProxy.setIdlePowerUsage(Reference.WIRELESS_IDLE_POWER);
        this.gridProxy.setVisualRepresentation(visual);
    }

    /* ===================== 节点激活（AE2 生命周期） ===================== */

    /**
     * 游戏把方块实体加入世界时会调用 validate()。这里必须调用 gridProxy.validate()，
     * 否则节点永远不会被 AE2 的 TickHandler 注册、onReady() 永不触发，收发器无法接入网格。
     */
    @Override
    public void validate() {
        super.validate();
        this.gridProxy.validate();
    }

    @Override
    public void onReady() {
        super.onReady();
        this.gridProxy.onReady();
        WirelessActiveRegistry.add(this);
        this.onWirelessReady();
    }

    @Override
    public void onChunkUnload() {
        this.gridProxy.onChunkUnload();
        // chunk 卸载后本 tile 即将被世界丢弃（1.7.10 重载时创建新实例）。
        // 必须立即从活动注册表移除并做轻量反注册，否则 ACTIVE 的强引用会
        // 阻止 tile 回收（内存泄漏），且残留端点会让标签网络统计失真。
        WirelessActiveRegistry.remove(this);
        this.onWirelessUnload();
        super.onChunkUnload();
    }

    /** chunk 卸载时的轻量清理（不保存、不通知客户端）：子类在此反注册标签 / 断开链路。 */
    protected void onWirelessUnload() {}

    @Override
    public void invalidate() {
        this.cleanupForRemoval();
        super.invalidate();
    }

    /** 由方块 breakBlock 在实体被移除前调用。 */
    public void onRemoved() {
        this.cleanupForRemoval();
    }

    protected void cleanupForRemoval() {
        if (this.beingRemoved) return;
        this.beingRemoved = true;
        WirelessActiveRegistry.remove(this);
        onWirelessRemoved();
        this.gridProxy.invalidate();
    }

    /* ===================== IWirelessEndpoint ===================== */

    @Override
    public World getWorld() {
        return this.worldObj;
    }

    @Override
    public int getX() {
        return this.xCoord;
    }

    @Override
    public int getY() {
        return this.yCoord;
    }

    @Override
    public int getZ() {
        return this.zCoord;
    }

    @Override
    public IGridNode getGridNode() {
        return this.gridProxy.getNode();
    }

    @Override
    public boolean isEndpointRemoved() {
        return this.beingRemoved;
    }

    /* ===================== IGridProxyable / IGridHost ===================== */

    @Override
    public AENetworkProxy getProxy() {
        return this.gridProxy;
    }

    /**
     * 返回此节点在{@code dir}方向上暴露给 AE2 邻接检测（FindConnections）的网格节点。
     *
     * <p>适配 1.7.10 AE2 rv3 的行为：{@code GridNode.FindConnections()} 遍历六个方向，
     * 对每个方向上 {@code instanceof IGridHost} 的方块调此方法；若返回非 null，
     * 就创建一条从本机网格到对方网格的 {@code GridConnection}。线缆通过此机制发现并连接机器。
     *
     * <p><b>重要修复（v0.5.1）：</b>若邻接方块是另一个无线收发器（{@link AbstractWirelessTile} 子类），
     * 则返回 null，阻止 AE2 自动创建邻接网格连接。原因是两个不同频道（标签）的收发器紧贴时，
     * 它们的网格节点会误连成同一网格 → 存储缓存合并 → 两个标签网络均可访问对方单元格 →
     * 从一侧取物、另一侧不减的复制漏洞。线缆（非 AbstractWirelessTile）不受影响，仍可正常连接。     */
    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        if (this.worldObj != null) {
            TileEntity adj = this.worldObj.getTileEntity(
                    this.xCoord + dir.offsetX,
                    this.yCoord + dir.offsetY,
                    this.zCoord + dir.offsetZ);
            // 邻接也是无线收发器 → 拒絕自动连接（防复制漏洞）
            if (adj instanceof AbstractWirelessTile) {
                return null;
            }
        }
        return this.gridProxy.getNode();
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        if (this.worldObj == null) return AECableType.GLASS;
        TileEntity adjacent = this.worldObj.getTileEntity(
                xCoord + dir.offsetX, yCoord + dir.offsetY, zCoord + dir.offsetZ);
        if (adjacent instanceof IGridHost) {
            AECableType t = ((IGridHost) adjacent).getCableConnectionType(dir.getOpposite());
            if (t != null) return t;
        }
        return AECableType.GLASS;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public void gridChanged() {
        // AE2 网络拓扑 / 供电状态变化时，由 AENetworkProxy.gridChanged() 回调本方法（宿主回调）。
        // 注意：绝不能反过来调用 this.gridProxy.gridChanged()，否则与 AENetworkProxy 互相递归 ->
        // StackOverflowError（见崩溃日志 crash-2026-08-24_14.36.54-server.txt）。
        // 此处只需在网络变化后刷新本机在线 / 频道显示状态即可。
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.updateState();
        }
    }

    /*
     * 注意：IGridProxyable 的继承链上，IGridProxyable -> IGridHost，而 IGridHost 只包含
     * getGridNode / getCableConnectionType / securityBreak 三个方法；IGridBlock（getIdlePowerUsage /
     * getFlags / getMachineRepresentation 等）是独立接口，AENetworkProxy 自身已 implements IGridBlock。
     * 故本类作为 IGridProxyable 宿主，只需实现 IGridHost 的三个方法（其中 securityBreak 由 AEBaseTile
     * 提供），无需、也不能把 IGridBlock 的方法再 @Override 一遍——那些方法由 proxy 自己实现。
     */

    @Override
    public void securityBreak() {
        // 不做安全破坏处理
    }

    /** 暴露受保护的 worldObj 给同包的 Container 使用（避免跨包访问 TileEntity.worldObj 的 protected 字段）。 */
    public World getWorldObj() {
        return this.worldObj;
    }

    /* ===================== 放置者 ===================== */

    public void setPlacerId(UUID placerId, String placerName) {
        this.placerId = placerId;
        this.placerName = placerName;
        this.saveChanges();
        this.syncToClient();
    }

    /** 触发 AE2 描述包，把无线状态同步到客户端（GUI / 渲染用）。 */
    protected void syncToClient() {
        if (this.worldObj != null && !this.worldObj.isRemote) {
            this.markForUpdate();
        }
    }

    public UUID getPlacerId() {
        return this.placerId;
    }

    public String getPlacerName() {
        return this.placerName;
    }

    /**
     * 子类可覆盖以在描述包中同步“显式所有者”（基础收发器用）。默认无。
     * 返回 / 设置的值仅用于客户端显示，不影响服务端逻辑。
     */
    protected UUID getSyncOwner() {
        return null;
    }

    protected void setSyncOwner(UUID owner) {
    }

    /* ===================== NBT 持久化（AE2 @TileEvent 钩子） =====================
     * AEBaseTile 把 writeToNBT / readFromNBT 声明为 final，不能直接重写。
     * AE2 在保存 / 读取 TileEntity 时通过 @TileEvent(WORLD_NBT_WRITE / WORLD_NBT_READ)
     * 回调这些方法（getMethods() 会遍历到继承的 public 方法），因此写在基类即可覆盖所有子类。
     */
    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeToNBT_Wireless(NBTTagCompound tag) {
        if (this.placerId != null) {
            tag.setLong("placerIdHi", this.placerId.getMostSignificantBits());
            tag.setLong("placerIdLo", this.placerId.getLeastSignificantBits());
        }
        if (this.placerName != null) {
            tag.setString("placerName", this.placerName);
        }
        this.gridProxy.writeToNBT(tag);
        this.writeWirelessNBT(tag);
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readFromNBT_Wireless(NBTTagCompound tag) {
        if (tag.hasKey("placerIdHi")) {
            this.placerId = new UUID(tag.getLong("placerIdHi"), tag.getLong("placerIdLo"));
        }
        if (tag.hasKey("placerName")) {
            this.placerName = tag.getString("placerName");
        }
        this.gridProxy.readFromNBT(tag);
        this.readWirelessNBT(tag);
    }

    /** 子类持久化自定义字段（频率 / 主从 / 锁定 / 标签等）到 NBT。 */
    protected void writeWirelessNBT(NBTTagCompound tag) {}

    /** 子类从 NBT 读取自定义字段。 */
    protected void readWirelessNBT(NBTTagCompound tag) {}

    /* ===================== 描述包同步（客户端 GUI 用） =====================
     * syncToClient() -> markForUpdate() -> func_145844_m（描述包）会先写 orientation（若可旋转），
     * 再调用 NETWORK_WRITE 处理器；onDataPacket 对称地先读 orientation 再调用 NETWORK_READ。
     * 本基类统一同步 placerId / placerName / 显式所有者，子类通过 writeWirelessStream 补充各自字段。
     */
    @TileEvent(TileEventType.NETWORK_WRITE)
    public void writeToStream_Wireless(ByteBuf buf) {
        buf.writeBoolean(this.placerId != null);
        if (this.placerId != null) {
            buf.writeLong(this.placerId.getMostSignificantBits());
            buf.writeLong(this.placerId.getLeastSignificantBits());
        }
        writeString(buf, this.placerName);
        UUID syncOwner = this.getSyncOwner();
        writeString(buf, syncOwner != null ? syncOwner.toString() : "");
        this.writeWirelessStream(buf);
    }

    @TileEvent(TileEventType.NETWORK_READ)
    public boolean readFromStream_Wireless(ByteBuf buf) {
        if (buf.readBoolean()) {
            this.placerId = new UUID(buf.readLong(), buf.readLong());
        } else {
            this.placerId = null;
        }
        this.placerName = readString(buf);
        String ownerStr = readString(buf);
        this.setSyncOwner((ownerStr == null || ownerStr.isEmpty()) ? null : parseUUID(ownerStr));
        this.readWirelessStream(buf);
        return true;
    }

    /** 子类把自定义字段写入描述包 ByteBuf（与 readWirelessStream 对称）。 */
    protected void writeWirelessStream(ByteBuf buf) {}

    /** 子类从描述包 ByteBuf 读取自定义字段（与 writeWirelessStream 对称）。 */
    protected void readWirelessStream(ByteBuf buf) {}

    private static void writeString(ByteBuf buf, String s) {
        if (s == null) {
            buf.writeShort(-1);
        } else {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            buf.writeShort(b.length);
            buf.writeBytes(b);
        }
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readShort();
        if (len < 0) return null;
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static UUID parseUUID(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ===================== 子类钩子 ===================== */

    /** 节点创建后（onReady）调用：子类在此恢复频率 / 标签等注册。 */
    protected abstract void onWirelessReady();

    /** 实体被移除 / 卸载前调用：子类在此反注册。 */
    protected abstract void onWirelessRemoved();

    /** 服务端周期 tick：维护无线连接并刷新方块状态。 */
    @Override
    public abstract void wirelessTick();

    /** 根据连接 / 频道数刷新方块状态（元数据）。 */
    protected abstract void updateState();

    protected int computeChannelTier() {
        IGridNode node = this.getGridNode();
        if (node == null || !node.isActive()) return 0;
        int used = 0;
        for (appeng.api.networking.IGridConnection conn : node.getConnections()) {
            used = Math.max(used, conn.getUsedChannels());
        }
        if (used == 0) return 1; // 已连接但暂无频道
        int tier = used / 8 + 1;
        return Math.min(tier, 5);
    }
}
