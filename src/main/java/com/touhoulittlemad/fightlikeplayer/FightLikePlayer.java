package com.touhoulittlemad.fightlikeplayer;

import com.mojang.logging.LogUtils;

import com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder;
import com.touhoulittlemad.fightlikeplayer.compat.data.CatalogReloadListener;

import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;

/**
 * 「类玩家」（Fight Like Player）—— 车万女仆的附属模组。
 *
 * <p><b>本模组做什么</b>：把「玩家的操作对应的效果」重新表述为女仆可独立选择的<b>原子动作</b>，
 * 并以数据驱动的方式把这些动作交给一个行为逻辑层去选。
 *
 * <p><b>本类做什么</b>：只做三件事——
 * <ol>
 *   <li>持有 {@link #MOD_ID} 与日志器（其余一切都在别处，保持本类无状态）；</li>
 *   <li>在构造期做一次<b>载体探测并打日志</b>（{@link CarrierDetector}）——
 *       验收时「到底装了哪些载体」必须是<b>可观测</b>的；</li>
 *   <li>★ <b>注册清单重载监听器</b>（M3）：让清单支持数据包覆盖与 {@code /reload}。</li>
 * </ol>
 *
 * <p>⚠️ 与 TLM 的所有交互都不在本类里，而在 {@code compat/LittleMaidCompat} 中，
 * 那个类带 {@code @LittleMaidExtension} 注解 ⇒ 未装 TLM 时不会被加载。
 */
@Mod(FightLikePlayer.MOD_ID)
public class FightLikePlayer {
    public static final String MOD_ID = "fight_like_player";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FightLikePlayer() {
        // ★★ 注册配置 —— 委托方要求「做到在 MC 的模组配置界面配置」。
        //   ⚠️ 但【只注册 ForgeConfigSpec 是不够的】：反汇编 forge 47.3.22 后确认，
        //      整个 Forge 里与配置界面相关的类只有 `net.minecraftforge.client.ConfigScreenHandler`
        //      （一个扩展点），**没有**内置的 ConfigurationScreen。
        //      ⇒ 模组列表里的 Config 按钮来自那个扩展点，由 compat/client/FlpClientSetup 注册。
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                com.touhoulittlemad.fightlikeplayer.config.FlpConfig.SPEC,
                "fight_like_player-common.toml");
        LOGGER.info("[FLP] 已注册配置（模组配置界面 → 玩家拟人战法 → Config）");
        CarrierDetector.detectAndLog();
        LOGGER.info("[FLP] 清单将在服务端资源重载时加载（支持数据包覆盖与 /reload），期望路径 {}/",
                com.touhoulittlemad.fightlikeplayer.carrier.CatalogSource.RESOURCE_DIR_DISPLAY);
    }

    /**
     * 服务端生命周期钩子。
     *
     * <p>★ 用 {@link AddReloadListenerEvent} 而不是自己监听文件：
     * Minecraft 的 {@code /reload} 会触发所有已注册的 {@code PreparableReloadListener}，
     * 而那时<b>资源管理器已经按数据包优先级解析好了</b> —— 自己写文件监听会漏掉这个语义。
     */
    @EventBusSubscriber(modid = MOD_ID)
    public static final class ServerEvents {

        private ServerEvents() {
        }

        @SubscribeEvent
        public static void onAddReloadListener(AddReloadListenerEvent event) {
            event.addListener(new CatalogReloadListener());
            LOGGER.debug("[FLP] 已注册清单重载监听器");
        }

        /**
         * ★ 注册调试命令（{@code /flp}）。
         *
         * <p>这是<b>思维层的手工替身</b>：{@code /flp bias <女仆> <8 个数>} 把一个决策向量
         * 直接注入该女仆的弹簧空间，走的是与真思维层<b>完全相同</b>的接口
         * （{@code SpringImpact.bias}）。
         *
         * <p>用途：把"模型判断错"与"架构错"<b>分开定位</b>（见
         * <a href="../../../../../../docs/11-感知层与思维层设计.md">docs/11</a> §6）。
         */
        @SubscribeEvent
        public static void onRegisterCommands(
                net.minecraftforge.event.RegisterCommandsEvent event) {
            com.touhoulittlemad.fightlikeplayer.compat.command.FlpCommands
                    .register(event.getDispatcher());
            LOGGER.debug("[FLP] 已注册 /flp 调试命令");
        }

        /**
         * 服务端停止时清空清单。
         * <p>★ 为什么必须清：单机存档切换 / 换服务器时，旧的清单若留着，
         * 下一个世界会用到<b>上一个世界的数据包</b>的清单 —— 静默串味。
         */
        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            if (CatalogHolder.isLoaded()) {
                LOGGER.info("[FLP] 服务端停止，清空清单（{}）", CatalogHolder.summary());
                CatalogHolder.clear();
            }
        }
    }
}
