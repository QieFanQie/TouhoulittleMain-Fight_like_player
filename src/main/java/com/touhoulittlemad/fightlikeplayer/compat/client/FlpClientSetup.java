package com.touhoulittlemad.fightlikeplayer.compat.client;

import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.think.ThinkBridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * <b>仅客户端的注册</b> —— 把配置界面挂到模组列表的 <b>Config</b> 按钮上。
 *
 * <h2>★★ 为什么这一步不可省（一次真实的取证）</h2>
 * 我原以为"用 {@code ForgeConfigSpec} 注册配置，Forge 就会自动给一个配置界面"。
 * 反汇编 {@code forge-1.20.1-47.3.22-universal.jar} 后确认<b>这是错的</b>：
 * <pre>
 *   // 全仓只有这一个与"配置界面"相关的类（forge-1.20.1-47.3.22）
 *   net.minecraftforge.client.ConfigScreenHandler
 *     └ record ConfigScreenFactory(BiFunction&lt;Minecraft, Screen, Screen&gt;)
 *   static Optional&lt;BiFunction&lt;Minecraft, Screen, Screen&gt;&gt; getScreenFactoryFor(IModInfo)
 *       → mc.getCustomExtension(ConfigScreenFactory.class)      // ★ 只查扩展点
 * </pre>
 * ⇒ <b>不注册这个扩展点，模组列表里根本不会出现 Config 按钮</b>
 * （内置的 {@code ConfigurationScreen} 是 NeoForge / 更高版本才有的）。
 * ⇒ 因此本项目自带一个配置界面：{@link FlpConfigScreen}。
 *
 * <h2>★ 为什么整个类必须 {@code Dist.CLIENT}</h2>
 * 它引用了 {@code Minecraft} / {@code Screen}（客户端类）。
 * 用 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} 让 Forge 在<b>专用服务器上完全不加载它</b>
 * —— 这正是本项目 S1（专用服务器类加载）那条不变量的标准做法：
 * <b>客户端代码只出现在标注了 Dist.CLIENT 的类里。</b>
 */
@Mod.EventBusSubscriber(modid = FightLikePlayer.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FlpClientSetup {

    private FlpClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new FlpConfigScreen(parent)));
        FightLikePlayer.LOGGER.debug("[FLP] 已注册配置界面（模组列表 → 玩家拟人战法 → Config）");
    }

    /** 保存后调用 —— 让 ThinkBridge 丢掉按旧配置构造的 HTTP 客户端。 */
    static void onConfigSaved() {
        ThinkBridge.invalidateClient();
    }
}
