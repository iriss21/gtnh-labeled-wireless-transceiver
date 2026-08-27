package com.gtnhwireless.common.ae.wireless;

import com.gtnhwireless.common.ModContent;
import com.gtnhwireless.reference.Reference;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridConnection;
import appeng.api.AEApi;
import appeng.api.exceptions.FailedConnection;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 标签无线网络注册中心（持久化到主世界 WorldSavedData）。
 *
 * 职责：
 * - 标签 → 频率（频道号）的分配与复用（从 1_000_000 起单调递增，不复用）；
 * - 为每个标签网络创建一个虚拟枢纽节点（{@link VirtualLabelNodeHost}），所有同标签收发器连到它；
 * - 记录在线端点；端点卸载后由 {@link #unregister(IWirelessEndpoint)} 移除引用。
 *
 * 移植自 EAEP 的 LabelNetworkRegistry。关键差异：
 * 1.7.10 没有 SavedData 的 computeIfAbsent，改用 MapStorage.loadData/setData；
 * 跨维度默认关闭，枢纽节点创建在收发器所在维度（dim==-1 表示跨维，落到主世界）。
 */
public class LabelNetworkRegistry extends WorldSavedData {

    public static final String SAVE_ID = Reference.MODID + "_label_networks";
    private static final long CHANNEL_START = 1_000_000L;

    /**
     * 进程内单例缓存。同一服务器进程里主世界 MapStorage 的 SavedData 只有一个实例，
     * 缓存它可让世界卸载事件（主世界正在从 DimensionManager 移除、无法再 loadData）
     * 依然能拿到注册表做节点清理，避免虚拟枢纽节点成为“死节点”残留。
     */
    private static LabelNetworkRegistry INSTANCE;

    private final Map<LabelKey, LabelNetwork> networks = new HashMap<>();
    private long nextChannel = CHANNEL_START;

    public LabelNetworkRegistry(String name) {
        super(name);
    }

    public static LabelNetworkRegistry get(World world) {
        if (INSTANCE != null) return INSTANCE;
        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            overworld = world;
        }
        LabelNetworkRegistry reg = (LabelNetworkRegistry) overworld.mapStorage.loadData(LabelNetworkRegistry.class, SAVE_ID);
        if (reg == null) {
            reg = new LabelNetworkRegistry(SAVE_ID);
            overworld.mapStorage.setData(SAVE_ID, reg);
        }
        INSTANCE = reg;
        return reg;
    }

    public static String normalizeLabel(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.length() > 64) t = t.substring(0, 64);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return null;
            }
        }
        return t;
    }

    public LabelNetwork register(World beLevel, String rawLabel, UUID placerId, IWirelessEndpoint endpoint) {
        String label = normalizeLabel(rawLabel);
        if (label == null) return null;

        UUID owner = WirelessTeamUtil.getNetworkOwnerUUID(beLevel, placerId);
        int dim = Reference.WIRELESS_CROSS_DIM ? -1 : beLevel.provider.dimensionId;
        LabelKey key = new LabelKey(dim, label, owner);

        LabelNetwork net = networks.get(key);
        if (net == null) {
            long channel = allocateChannel();
            net = new LabelNetwork(dim, label, owner, channel);
            if (!net.ensureVirtualNode()) return null;
            networks.put(key, net);
            markDirty();
        } else {
            net.ensureVirtualNode();
        }

        net.endpoints.add(new EndpointRef(dim, endpoint.getX(), endpoint.getY(), endpoint.getZ()));
        markDirty();
        return net;
    }

    public void unregister(IWirelessEndpoint endpoint) {
        World level = endpoint.getWorld();
        if (level == null || level.isRemote) return;
        int dim = Reference.WIRELESS_CROSS_DIM ? -1 : level.provider.dimensionId;
        EndpointRef ref = new EndpointRef(dim, endpoint.getX(), endpoint.getY(), endpoint.getZ());
        for (Iterator<Map.Entry<LabelKey, LabelNetwork>> it = networks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<LabelKey, LabelNetwork> e = it.next();
            if (e.getKey().dim == dim && e.getValue().endpoints.remove(ref)) {
                markDirty();
            }
        }
    }

    public LabelNetwork getNetwork(World level, String rawLabel, UUID placerId) {
        String label = normalizeLabel(rawLabel);
        if (label == null) return null;
        UUID owner = WirelessTeamUtil.getNetworkOwnerUUID(level, placerId);
        int dim = Reference.WIRELESS_CROSS_DIM ? -1 : level.provider.dimensionId;
        return networks.get(new LabelKey(dim, label, owner));
    }

    public boolean removeNetwork(World level, String rawLabel, UUID placerId) {
        String label = normalizeLabel(rawLabel);
        if (label == null) return false;
        UUID owner = WirelessTeamUtil.getNetworkOwnerUUID(level, placerId);
        int dim = Reference.WIRELESS_CROSS_DIM ? -1 : level.provider.dimensionId;
        LabelKey key = new LabelKey(dim, label, owner);
        LabelNetwork net = networks.remove(key);
        if (net != null) {
            // v0.4.2：先置“已删除”标记再销毁节点。任何仍持有该网络对象引用的
            // LabelLink（漏网的已加载 tile 等）在下一 tick 看到标记后会立即断开，
            // 不会因 ensureVirtualNode() 把已删除频道“复活”。
            net.deleted = true;
            net.destroyVirtualNode();
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * 重命名标签网络。保持频道号、端点集合和虚拟枢纽节点不变，仅更换 LabelKey 的 label 字段。
     * 所有连接到此网络的收发器在下一 tick 刷新时会从注册中心取到新名称。
     *
     * @return true 表示重命名成功；false 表示旧标签不存在或新标签已占用或非法
     */
    public boolean renameNetwork(World level, String oldRawLabel, String newRawLabel, UUID placerId) {
        String oldLabel = normalizeLabel(oldRawLabel);
        String newLabel = normalizeLabel(newRawLabel);
        if (oldLabel == null || newLabel == null) return false;
        if (oldLabel.equals(newLabel)) return true; // 同名视为成功

        UUID owner = WirelessTeamUtil.getNetworkOwnerUUID(level, placerId);
        int dim = Reference.WIRELESS_CROSS_DIM ? -1 : level.provider.dimensionId;

        LabelKey oldKey = new LabelKey(dim, oldLabel, owner);
        LabelKey newKey = new LabelKey(dim, newLabel, owner);

        // 目标名称已被占用
        if (networks.containsKey(newKey)) return false;

        LabelNetwork net = networks.remove(oldKey);
        if (net == null) return false;

        // 用新 key 重新放入，网络对象本身不变（channel/endpoints/virtualHost 全部保留）
        networks.put(newKey, net);
        // 更新网络对象的 label 字段（用于 ensureVirtualNode 的 tag 名等）
        net.label = newLabel;
        markDirty();
        return true;
    }

    public List<LabelNetworkSnapshot> listNetworks(World level, UUID placerId) {
        UUID owner = WirelessTeamUtil.getNetworkOwnerUUID(level, placerId);
        int dim = Reference.WIRELESS_CROSS_DIM ? -1 : level.provider.dimensionId;
        List<LabelNetworkSnapshot> list = new ArrayList<>();
        for (Map.Entry<LabelKey, LabelNetwork> e : networks.entrySet()) {
            LabelKey k = e.getKey();
            if (k.owner.equals(owner) && k.dim == dim) {
                list.add(new LabelNetworkSnapshot(k.label, e.getValue().channel));
            }
        }
        list.sort(Comparator.comparingLong(a -> a.channel));
        return list;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setLong("nextChannel", nextChannel);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<LabelKey, LabelNetwork> e : networks.entrySet()) {
            LabelKey k = e.getKey();
            LabelNetwork v = e.getValue();
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setInteger("dim", k.dim);
            nbt.setString("label", k.label);
            nbt.setLong("owner", k.owner.getMostSignificantBits());
            nbt.setLong("ownerL", k.owner.getLeastSignificantBits());
            nbt.setLong("channel", v.channel);
            NBTTagList eps = new NBTTagList();
            for (EndpointRef r : v.endpoints) {
                NBTTagCompound rtag = new NBTTagCompound();
                rtag.setInteger("dim", r.dim);
                rtag.setInteger("x", r.x);
                rtag.setInteger("y", r.y);
                rtag.setInteger("z", r.z);
                eps.appendTag(rtag);
            }
            nbt.setTag("endpoints", eps);
            list.appendTag(nbt);
        }
        tag.setTag("networks", list);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        networks.clear();
        nextChannel = tag.getLong("nextChannel");
        NBTTagList list = tag.getTagList("networks", 10); // 10 = NBTTagCompound
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound nbt = list.getCompoundTagAt(i);
            int dim = nbt.getInteger("dim");
            // v0.4.0：跨维度标签网络重新启用（生命周期与缓存自愈修复后）。
            // dim<0（-1）表示跨维度网络，其虚拟枢纽主机在主世界；正常读入，
            // 虚拟枢纽节点由 ensureVirtualNode() 在维度加载后按需重建。
            String label = nbt.getString("label");
            long hi = nbt.getLong("owner");
            long lo = nbt.getLong("ownerL");
            UUID owner = new UUID(hi, lo);
            long channel = nbt.getLong("channel");
            LabelNetwork net = new LabelNetwork(dim, label, owner, channel);
            NBTTagList eps = nbt.getTagList("endpoints", 10);
            for (int j = 0; j < eps.tagCount(); j++) {
                NBTTagCompound rtag = eps.getCompoundTagAt(j);
                net.endpoints.add(new EndpointRef(rtag.getInteger("dim"), rtag.getInteger("x"), rtag.getInteger("y"), rtag.getInteger("z")));
            }
            networks.put(new LabelKey(dim, label, owner), net);
        }
    }

    private long allocateChannel() {
        if (nextChannel < CHANNEL_START) {
            nextChannel = CHANNEL_START;
        }
        return nextChannel++;
    }

    /* ===================== 内部类型 ===================== */

    public static final class LabelNetworkSnapshot {

        public final String label;
        public final long channel;

        public LabelNetworkSnapshot(String label, long channel) {
            this.label = label;
            this.channel = channel;
        }
    }

    private static final class LabelKey {

        final int dim;
        final String label;
        final UUID owner;

        LabelKey(int dim, String label, UUID owner) {
            this.dim = dim;
            this.label = label;
            this.owner = owner;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LabelKey)) return false;
            LabelKey k = (LabelKey) o;
            return dim == k.dim && label.equals(k.label) && owner.equals(k.owner);
        }

        @Override
        public int hashCode() {
            return dim * 31 + label.hashCode() * 31 + owner.hashCode();
        }
    }

    private static final class EndpointRef {

        final int dim, x, y, z;

        EndpointRef(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EndpointRef)) return false;
            EndpointRef r = (EndpointRef) o;
            return dim == r.dim && x == r.x && y == r.y && z == r.z;
        }

        @Override
        public int hashCode() {
            return dim * 31 + x * 31 + y * 31 + z;
        }
    }

    /** 单个标签网络：虚拟枢纽节点 + 在线端点集合。 */
    public static final class LabelNetwork {

        final int dim;
        String label;
        final UUID owner;
        final long channel;
        final Set<EndpointRef> endpoints = new HashSet<>();

        /**
         * 网络是否已被删除（v0.4.2）。removeNetwork 会先置位再销毁虚拟枢纽；
         * 仍引用本对象的 LabelLink 据此判定目标已失效并立即断开，防止频道复活。
         */
        volatile boolean deleted;

        VirtualLabelNodeHost virtualHost;

        LabelNetwork(int dim, String label, UUID owner, long channel) {
            this.dim = dim;
            this.label = label;
            this.owner = owner;
            this.channel = channel;
        }

        public long channel() {
            return channel;
        }

        public IGridNode node() {
            return virtualHost == null ? null : virtualHost.getProxy().getNode();
        }

        /** 虚拟枢纽节点是否有效（存在且世界对象有效）。无效时由 {@link #ensureVirtualNode()} 重建。 */
        public boolean isNodeValid() {
            return !deleted && virtualHost != null && virtualHost.isNodeValid();
        }

        /** 网络是否已被删除（v0.4.2）。删除后不可再用于连接，LabelLink 会立即断开。 */
        public boolean isDeleted() {
            return deleted;
        }

        public int endpointCount() {
            return endpoints.size();
        }

        /** 确保虚拟枢纽节点存在；维度未加载时返回 false，待维度加载后由收发器重试。 */
        public boolean ensureVirtualNode() {
            // v0.4.2：已删除的网络禁止重建虚拟枢纽（任何路径都不能让删除的频道复活）。
            if (deleted) return false;
            if (virtualHost != null && virtualHost.getProxy().getNode() != null && virtualHost.isNodeValid()) {
                return true;
            }
            // 旧宿主存在但节点已失效（世界卸载后被 AE2 清理、或节点脱离网格成为死节点）：
            // 必须先销毁清理，否则复用死节点会导致 createGridConnection 合并到“死网格”，
            // 引发网格分裂-合并竞态（物资复制 / 断连的根源之一）。
            if (virtualHost != null) {
                virtualHost.destroyNode();
                virtualHost = null;
            }
            World w = DimensionManager.getWorld(dim < 0 ? 0 : dim);
            if (w == null) return false;
            ItemStack visual = ModContent.labeledWirelessTransceiver != null
                    ? new ItemStack(ModContent.labeledWirelessTransceiver)
                    : null;
            virtualHost = new VirtualLabelNodeHost(w, visual, "label_net_" + label);
            virtualHost.createNode();
            return true;
        }

        void destroyVirtualNode() {
            if (virtualHost != null) {
                virtualHost.destroyNode();
                virtualHost = null;
            }
        }
    }

    /* ===================== 世界生命周期（CommonProxy 事件驱动） ===================== */

    /**
     * 世界卸载时清理该世界的虚拟枢纽节点。
     *
     * 虚拟枢纽是非世界节点（inWorld=false），没有 tile 生命周期钩子：世界卸载后 AE2
     * 会清理网格与节点，但 proxy 内部仍引用旧节点对象。若不主动销毁，下次 ensureVirtualNode()
     * 会误判节点有效（getNode() 非 null）而复用死节点，导致端点连到“死网格”。
     * 销毁后节点引用置 null，端点重连时 ensureVirtualNode() 会确定性重建。
     */
    public void onWorldUnload(World unloaded) {
        if (unloaded == null || unloaded.isRemote) return;
        for (LabelNetwork net : networks.values()) {
            if (net.virtualHost != null
                    && net.virtualHost.getProxy().getLocation().getWorld() == unloaded) {
                net.destroyVirtualNode();
            }
        }
    }

    /**
     * 世界加载时提前重建该世界所属的虚拟枢纽节点（跨维度网络的主机在主世界）。
     * 即使这里没建好，端点重连时 ensureVirtualNode() 也会补建——这里是提前量，
     * 让端点重连更顺（避免重连首 tick 因节点未就绪而失败）。
     */
    public void onWorldLoad(World loaded) {
        if (loaded == null || loaded.isRemote) return;
        int loadedDim = loaded.provider.dimensionId;
        for (LabelNetwork net : networks.values()) {
            int hostDim = net.dim < 0 ? 0 : net.dim;
            if (hostDim == loadedDim) {
                net.ensureVirtualNode();
            }
        }
    }
}
