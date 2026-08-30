package com.gtnhwireless.common.ae.wireless;

import appeng.api.AEApi;
import appeng.api.exceptions.FailedConnection;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;

/**
 * 标签无线收发器的连接器：把收发器的 in-world 节点连接到标签网络的虚拟枢纽节点。
 *
 * 不负责标签的注册 / 反注册，只维持物理连接。
 * 直接用 IGridConnection 维持物理连接。
 */
public class LabelLink {

    private final IWirelessEndpoint host;
    private IGridConnection connection;
    private LabelNetworkRegistry.LabelNetwork target;

    public LabelLink(IWirelessEndpoint host) {
        this.host = host;
    }

    public void setTarget(LabelNetworkRegistry.LabelNetwork target) {
        this.target = target;
        updateStatus();
    }

    public void clearTarget() {
        this.target = null;
        destroyConnection();
    }

    public boolean isConnected() {
        return connection != null;
    }

    public void updateStatus() {
        if (host.isEndpointRemoved()) {
            destroyConnection();
            return;
        }
        if (target == null) {
            destroyConnection();
            return;
        }
        // v0.4.2：目标网络已被删除（removeNetwork 置位 deleted）——立即断开并清空引用，
        // 绝不触发 ensureVirtualNode() 把已删除的频道“复活”。
        if (target.isDeleted()) {
            this.target = null;
            destroyConnection();
            return;
        }

        IGridNode hostNode = host.getGridNode();
        IGridNode targetNode = target.node();
        if (hostNode == null) {
            destroyConnection();
            return;
        }
        if (targetNode == null || !target.isNodeValid()) {
            // 目标虚拟枢纽节点失效（AE2 的 TickHandler.unloadWorld 会直接 destroy 节点而
            // 不经过 proxy.invalidate()，proxy 仍引用死节点）：若继续 createGridConnection，
            // mergeGrids 会从死节点出发把已清理网格的节点拖回（grid 分裂-合并竞态 -> 复制）。
            // 这里先重建枢纽，拿到新节点再连接；重建失败（维度未加载）则断开，等待重试。
            target.ensureVirtualNode();
            targetNode = target.node();
            if (targetNode == null) {
                destroyConnection();
                return;
            }
        }

        try {
            if (connection != null) {
                IGridNode a = connection.a();
                IGridNode b = connection.b();
                if ((a == hostNode || b == hostNode) && (a == targetNode || b == targetNode)) {
                    return; // 已正确连接
                }
                connection.destroy();
                connection = null;
            }
            connection = AEApi.instance().createGridConnection(hostNode, targetNode);
        } catch (FailedConnection ignore) {
            destroyConnection();
        }
    }

    public void onUnloadOrRemove() {
        this.target = null;
        destroyConnection();
    }

    private void destroyConnection() {
        if (connection != null) {
            connection.destroy();
            connection = null;
        }
    }
}
