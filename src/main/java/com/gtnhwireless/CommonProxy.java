package com.gtnhwireless;

import com.gtnhwireless.common.ae.wireless.WorldLifecycleHandler;
import com.gtnhwireless.reference.Reference;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

/**
 * 服务端 / 通用代理。客户端特化逻辑见 {@link ClientProxy}。
 *
 * v0.4.0：跨维度标签网络默认开启（修复了 v0.3.4 的物资复制漏洞与生命周期问题）：
 * - 虚拟枢纽节点生命周期绑定世界加载/卸载（死节点不再复用）；
 * - 收发器链路重连后强制触发网格存储缓存自愈（消除分裂-合并残留导致的刷物品）；
 * - chunk 卸载时完整反注册（消除活动注册表泄漏）。
 *
 * 注意：事件监听器必须用独立的 public 类（{@link WorldLifecycleHandler}），
 * 不能匿名内部类——FML 的 ASMEventHandler 在独立 classloader 中访问监听器类，
 * 匿名类（包私有）会抛 IllegalAccessError 导致启动崩溃。
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        try {
            // v0.4.1 配置迁移：v0.3.4 生成的旧键 crossDimensionalLabelNetwork=false 会被
            // getBoolean 原样读回，覆盖 v0.4.0 代码里的默认 true——升级用户的实际行为仍是
            // "跨维度关闭"（表现为：主世界添加频道后，其他维度看不到相同频道）。
            // 这里删除旧键并改用新键 crossDimensionalNetwork（默认 true），
            // 从旧版本升级的存档在下次保存配置时自动启用跨维度，无需手动改配置文件。
            if (cfg.hasKey("wireless", "crossDimensionalLabelNetwork")) {
                cfg.getCategory("wireless").remove("crossDimensionalLabelNetwork");
            }
            Reference.WIRELESS_CROSS_DIM = cfg.getBoolean(
                    "crossDimensionalNetwork", "wireless", true,
                    "Allow label networks to span dimensions. ON since v0.4.0 (item-duplication "
                            + "exploit fixed via reconnect-driven cache self-healing and world-bound "
                            + "virtual hub lifecycle). The pre-0.4.1 key "
                            + "'crossDimensionalLabelNetwork' is removed automatically on load.");
        } finally {
            if (cfg.hasChanged()) cfg.save();
        }
    }

    public void init(FMLInitializationEvent event) {
        // 世界加载/卸载驱动虚拟枢纽节点生命周期（独立 public 监听器类，见类注释）：
        // 卸载时销毁节点（防死节点残留），加载时提前重建（端点重连更顺）。
        MinecraftForge.EVENT_BUS.register(new WorldLifecycleHandler());
    }

    public void postInit(FMLPostInitializationEvent event) {
    }
}
