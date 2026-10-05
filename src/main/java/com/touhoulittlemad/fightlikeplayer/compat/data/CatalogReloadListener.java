package com.touhoulittlemad.fightlikeplayer.compat.data;

import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.carrier.CarrierResolver;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogLoader;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogSource;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 清单重载监听器 —— <b>M3 的核心：让清单支持数据包覆盖与 {@code /reload}</b>。
 *
 * <h2>为什么用 ReloadListener 而不是自己写文件监听</h2>
 * Minecraft 已经有一套成熟的重载机制：{@code /reload} 会触发所有
 * {@code PreparableReloadListener}，且资源管理器此时<b>已经按数据包优先级解析好了</b>。
 * 自己写文件监听会重复造轮子，还会漏掉"数据包优先级"这个语义。
 *
 * <h2>执行时机</h2>
 * <pre>
 *   服务端启动 → AddReloadListenerEvent 注册本监听器
 *              → 首次重载 ⇒ 清单加载完成（此时 CatalogHolder 才有东西）
 *   /reload    → 再次重载 ⇒ 清单热替换
 * </pre>
 *
 * <h2>★ 失败策略（重要）</h2>
 * <ul>
 *   <li>整个清单读不到 ⇒ <b>记 ERROR 并保留上一份</b>，<b>绝不</b>清空
 *       （宁可继续用旧数据，也不要因为一次坏重载让女仆失去全部能力）；</li>
 *   <li>单个文件坏了 ⇒ 由 {@link ResourceManagerCatalogSource} 记进 {@code problems()}，
 *       <b>其余文件照常加载</b>，但问题会打出来 ⇒ 不会静默。</li>
 * </ul>
 *
 * @see CatalogHolder
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §8 (M3)</a>
 */
public final class CatalogReloadListener
        extends SimplePreparableReloadListener<ResourceManagerCatalogSource> {

    /** 供日志用。 */
    public static final String NAME = "fight_like_player 清单";

    @Override
    protected ResourceManagerCatalogSource prepare(ResourceManager rm, ProfilerFiller profiler) {
        // 读资源这一步是"准备"阶段（可并行）；解析在 apply 阶段（主线程）
        return new ResourceManagerCatalogSource(rm);
    }

    @Override
    protected void apply(ResourceManagerCatalogSource source, ResourceManager rm, ProfilerFiller profiler) {
        loadNow(source);
    }

    /**
     * 加载并安装。抽出来是为了让自测与手动重载能用同一段逻辑
     * （★ 自测用的 {@link CatalogSource} 是磁盘来源，走的是同一个方法）。
     */
    public static boolean loadNow(CatalogSource source) {
        String desc = source.describe();
        try {
            CatalogLoader.Loaded loaded = CatalogLoader.load(source);

            if (loaded.actions().isEmpty()) {
                FightLikePlayer.LOGGER.error(
                        "[FLP] 清单加载为空！来源：{}。期望路径 {}/（内置数据应随 jar 发布）",
                        desc, CatalogSource.RESOURCE_DIR_DISPLAY);
                return false;
            }

            // ★★ 把执行器账本传进解析器：没有执行器的动作【不是选项】（见 CatalogSource#EXECUTORS_FILE）
            CarrierResolver resolver = new CarrierResolver(loaded.actions(), loaded.rules(),
                    loaded.implementedActionIds());
            // ★★ W32：装法术意图表（解析器/执行器都从它查"这个法术是干什么的"）
            com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntentTable
                    .install(loaded.spellIntent());
            FightLikePlayer.LOGGER.info("[FLP] {}",
                    com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntentTable.summary());

            boolean ok = CatalogHolder.install(resolver, desc);

            long choice = loaded.actions().stream().filter(a -> a.isChoice()).count();
            int noRule = resolver.carriersWithoutRule().size();

            FightLikePlayer.LOGGER.info("[FLP] 清单已加载：动作 {} 条（可评分 {}）· 载体规则 {} 条",
                    loaded.actions().size(), choice, loaded.rules().size());
            FightLikePlayer.LOGGER.info("[FLP] 清单来源：{}", desc);
            FightLikePlayer.LOGGER.info("[FLP] 执行器账本：已实现 {} 条动作{}",
                    loaded.implementedActionIds().size(),
                    loaded.implementedActionIds().isEmpty()
                            ? "　⚠⚠ 没读到！解析器将【不过滤】无执行器的动作 ⇒ 女仆可能反复选中做不出来的动作"
                            : "（其余可达但未实现的动作已在解析期挡下）");

            if (noRule > 0) {
                FightLikePlayer.LOGGER.warn("[FLP] ⚠ 有 {} 个载体在 carriers.json 里没有规则：{}",
                        noRule, resolver.carriersWithoutRule());
            }
            int unresolved = (int) loaded.rules().stream()
                    .filter(r -> r.matcher() instanceof com.touhoulittlemad.fightlikeplayer.carrier.CarrierRule.Matcher.Unresolved)
                    .count();
            if (unresolved > 0) {
                FightLYPLogUnresolved(unresolved);
            }
            return ok;
        } catch (RuntimeException ex) {
            // ★ 失败不清空：保留上一份好数据
            FightLikePlayer.LOGGER.error("[FLP] 清单加载失败，保留上一份。来源：{}", desc, ex);
            return false;
        }
    }

    private static void FightLYPLogUnresolved(int n) {
        FightLikePlayer.LOGGER.warn("[FLP] ⚠ 有 {} 条载体规则的判据【未能查证】，它们永不命中（fail-closed）"
                + " —— 这是刻意登记缺口，不是 bug。详见 docs/09 §8.0b W12", n);
    }
}
