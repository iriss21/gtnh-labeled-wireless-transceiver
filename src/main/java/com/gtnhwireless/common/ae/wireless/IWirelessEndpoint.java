package com.gtnhwireless.common.ae.wireless;

import appeng.api.networking.IGridNode;
import net.minecraft.world.World;

/**
 * 无线端点统一接口：既用于基础无线收发器（主/从），也用于标签无线收发器。
 *
 * 与 EAEP 1.20 的差别：1.7.10 没有 ServerLevel，统一用 {@link World}；
 * 坐标用简单 int 字段而非 BlockPos。注册中心通过 world.getTileEntity 反查活动端点。
 */
public interface IWirelessEndpoint {

    World getWorld();

    int getX();

    int getY();

    int getZ();

    IGridNode getGridNode();

    boolean isEndpointRemoved();
}
