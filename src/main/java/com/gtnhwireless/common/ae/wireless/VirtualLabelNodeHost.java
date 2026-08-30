package com.gtnhwireless.common.ae.wireless;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.EnumSet;

/**
 * 标签网络的“虚拟枢纽节点宿主”。
 *
 * 它不是世界中的实体，而是一个独立的 AE2 网格节点（inWorld=false）。
 * 同一标签下的所有标签无线收发器，其 in-world 节点都会与此枢纽节点建立直接连接，
 * 从而把多个本地 AE2 网络合并成同一个网格，实现跨距离无线共享存储 / 合成。
 *
 * 虚拟枢纽节点宿主，1.7.10 用 AENetworkProxy(inWorld=false) 实现。
 */
public class VirtualLabelNodeHost implements IGridProxyable {

    private final DimensionalCoord loc;
    private final AENetworkProxy proxy;

    public VirtualLabelNodeHost(World world, ItemStack visual, String tag) {
        // loc 必须在构造 proxy 之前就绪，因为 proxy 构造时会回调 getLocation()
        this.loc = new DimensionalCoord(world, 0, 0, 0);
        this.proxy = new AENetworkProxy(this, tag, visual, false);
        this.proxy.setFlags(appeng.api.networking.GridFlags.DENSE_CAPACITY);
        this.proxy.setIdlePowerUsage(0.0D);
        this.proxy.setVisualRepresentation(visual);
    }

    public void createNode() {
        this.proxy.onReady();
    }

    /**
     * 销毁虚拟枢纽节点。AENetworkProxy.invalidate() 内部对 node==null 幂等，
     * 因此重复调用安全；销毁后 proxy.getNode() 返回 null，下次 createNode() 重建。
     */
    public void destroyNode() {
        this.proxy.invalidate();
    }

    /**
     * 节点是否仍然有效。
     *
     * rv3 的 AENetworkProxy 是非世界节点（inWorld=false），没有 tile 生命周期钩子：
     * 世界卸载 / 网格被 AE2 清理时，节点可能被内部 destroy 而 proxy.node 仍引用旧对象。
     * 这种情况下 getNode() 返回非 null 但节点已不属于任何网格（getGrid()==null 且无连接），
     * 继续复用会导致 createGridConnection 合并到“死网格”，引发分裂-合并竞态与断连。
     * 因此判定条件 = 节点存在 && 其所在世界对象仍有效。
     */
    public boolean isNodeValid() {
        if (this.proxy.getNode() == null) return false;
        World w = this.loc.getWorld();
        return w != null && !w.isRemote;
    }

    public AENetworkProxy getProxy() {
        return proxy;
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        return proxy.getNode();
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        return AECableType.NONE;
    }

    @Override
    public void securityBreak() {
        // 虚拟节点无需安全破坏逻辑
    }

    @Override
    public DimensionalCoord getLocation() {
        return loc;
    }

    @Override
    public void gridChanged() {
        // 虚拟枢纽节点宿主：AE2 网格变化时由 AENetworkProxy.gridChanged() 回调（宿主回调）。
        // 同样不能调用 proxy.gridChanged()，否则与 AENetworkProxy 互相递归 -> StackOverflowError。
        // 本宿主无需在网络变化时执行任何逻辑（节点状态完全由 AENetworkProxy 自身维护）。
    }
}
