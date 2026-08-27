package com.gtnhwireless.common.ae.wireless;

/**
 * 由 WirelessTickHandler 统一驱动的服务端周期回调。
 * 基础/标签无线收发器实现此接口后加入活动集合，每若干个 tick 被调用一次以维护无线连接。
 */
public interface IWirelessTicker {

    void wirelessTick();
}
