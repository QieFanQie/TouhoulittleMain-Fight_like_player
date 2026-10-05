package com.touhoulittlemad.fightlikeplayer.carrier;


import java.util.List;

/**
 * 当前生效的清单解析器 —— 一个<b>全局可替换的槽位</b>。
 *
 * <h2>为什么需要一个 holder</h2>
 * 清单是<b>可以在运行中被 {@code /reload} 替换</b>的：
 * <pre>
 *   /reload  →  CatalogReloadListener 重新读资源  →  造出新的 CarrierResolver
 *                                                      ↓ 换进这里
 *   任务（每个 tick）→ CatalogHolder.resolver()  →  拿到【当前】那一份
 * </pre>
 * 若任务持有一份固定引用，{@code /reload} 就会静默失效（改了数值但行为不变）——
 * 这类"看起来支持热重载其实没有"是最容易骗过测试的缺陷。
 *
 * <p>⇒ 任务<b>每次要用时才取</b>，不缓存。
 *
 * <p>★ 线程模型：{@code volatile} 足够 —— 重载发生在服务端主线程，
 * 读也在主线程；volatile 只是保证"换的那一瞬间"其它线程不会读到半个对象。
 *
 * @see CatalogReloadListener
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §8 (M3)</a>
 */
public final class CatalogHolder {

    /** 空清单（尚未加载 / 加载失败时的兜底）。 */
    private static final CarrierResolver EMPTY =
            new CarrierResolver(List.of(), List.of());

    private static volatile CarrierResolver current = EMPTY;
    private static volatile String lastDescription = "（尚未加载）";
    private static volatile int loadCount;

    private CatalogHolder() {
    }

    /** 当前生效的解析器。★ 每次都取，不要缓存。 */
    public static CarrierResolver resolver() {
        return current;
    }

    /** 是否已经成功加载过（用于启动自检与日志）。 */
    public static boolean isLoaded() {
        return current != EMPTY;
    }

    /** 最近一次加载的来源描述。 */
    public static String lastDescription() {
        return lastDescription;
    }

    /** 已重载次数（{@code /reload} 会递增；用于验证热重载真的生效了）。 */
    public static int loadCount() {
        return loadCount;
    }

    /**
     * 换上新加载的清单。
     *
     * @param resolver 新的解析器；{@code null} 视为加载失败，<b>保留旧的</b>
     *                 （★ 宁可继续用上一次的好数据，也不要因为一次坏重载把功能清空）
     * @param description 来源描述
     * @return 是否替换成功
     */
    public static boolean install(CarrierResolver resolver, String description) {
        if (resolver == null || resolver.actions().isEmpty()) {
            lastDescription = "⚠ 加载结果为空，保留上一份：" + description;
            return false;
        }
        current = resolver;
        lastDescription = description;
        loadCount++;
        return true;
    }

    /** 强制清空（脱战 / 测试用）。 */
    public static void clear() {
        current = EMPTY;
        lastDescription = "（已清空）";
    }

    /** 一行摘要，供启动日志。 */
    public static String summary() {
        CarrierResolver r = current;
        if (r == EMPTY) {
            return "未加载";
        }
        long choices = r.actions().stream().filter(ActionSpec::isChoice).count();
        return "动作 " + r.actions().size() + " 条（可评分 " + choices + "）· 载体规则 "
                + r.rules().size() + " 条 · 已重载 " + loadCount + " 次";
    }
}
