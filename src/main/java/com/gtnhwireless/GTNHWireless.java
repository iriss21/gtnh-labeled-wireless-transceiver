package com.gtnhwireless;

import com.gtnhwireless.common.GuiHandler;
import com.gtnhwireless.common.ModContent;
import com.gtnhwireless.common.ae.wireless.WirelessTickHandler;
import com.gtnhwireless.common.block.LabeledWirelessTransceiverBlock;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverTile;
import com.gtnhwireless.network.PacketHandler;
import com.gtnhwireless.reference.Reference;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import appeng.api.AEApi;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * 标签无线收发器（Labeled Wireless Transceiver）—— 跨维度 AE2 频道与电能传输模组。
 * 移植自 ExtendedAE Plus，适配 GTNH 2.8.4（MC 1.7.10）。
 */
@Mod(modid = Reference.MODID, name = Reference.NAME, version = Reference.VERSION,
        dependencies = Reference.DEPENDENCIES)
public class GTNHWireless {

    @Mod.Instance(Reference.MODID)
    public static GTNHWireless instance;

    @SidedProxy(clientSide = Reference.CLIENT_PROXY, serverSide = Reference.SERVER_PROXY)
    public static CommonProxy proxy;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 方块：仅保留标签无线收发器
        Block labeledWirelessTransceiver = new LabeledWirelessTransceiverBlock();
        GameRegistry.registerBlock(labeledWirelessTransceiver, ItemBlock.class, "labeled_wireless_transceiver");
        ModContent.labeledWirelessTransceiver = labeledWirelessTransceiver;

        // ItemBlock 引用（供虚拟节点外观 / 比较）
        ModContent.itemLabeledWirelessTransceiver = Item.getItemFromBlock(labeledWirelessTransceiver);

        // 方块实体
        GameRegistry.registerTileEntity(LabeledWirelessTransceiverTile.class, "gtnhlabeledwireless.labeled_wireless_transceiver");

        // 网络
        PacketHandler.init();

        // GUI
        NetworkRegistry.INSTANCE.registerGuiHandler(instance, new GuiHandler());

        proxy.preInit(event);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // 服务端周期驱动：维持标签无线收发器的无线连接
        FMLCommonHandler.instance().bus().register(new WirelessTickHandler());
        registerRecipes();
        proxy.init(event);
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    private void registerRecipes() {
        if (ModContent.labeledWirelessTransceiver != null) {
            // 配方：4 Paper + 1 Wireless Access Point + 4 Fluix Pearls
            // 布局：
            //   P E P
            //   E A E
            //   P E P
            // AE2 rv3: wireless access point = blocks().wireless();
            // maybeStack() returns Guava Optional (not java.util.Optional)
            com.google.common.base.Optional<ItemStack> accessPoint = AEApi.instance().definitions()
                    .blocks().wireless().maybeStack(1);
            com.google.common.base.Optional<ItemStack> fluixPearl = AEApi.instance().definitions()
                    .materials().fluixPearl().maybeStack(1);

            if (accessPoint.isPresent() && fluixPearl.isPresent()) {
                GameRegistry.addRecipe(new ItemStack(ModContent.labeledWirelessTransceiver),
                        "pep", "eae", "pep",
                        'p', net.minecraft.init.Items.paper,
                        'e', fluixPearl.get(),
                        'a', accessPoint.get());
            } else {
                // 回退：如果 AE2 物品尚未可用，使用末影珍珠 + 铁锭（旧配方简化版）
                GameRegistry.addRecipe(new ItemStack(ModContent.labeledWirelessTransceiver),
                        "pep", "eae", "pep",
                        'p', net.minecraft.init.Items.paper,
                        'e', net.minecraft.init.Items.ender_pearl,
                        'a', net.minecraft.init.Items.diamond);
            }
        }
    }
}
