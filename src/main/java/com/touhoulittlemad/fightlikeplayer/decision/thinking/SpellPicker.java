package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>"挑哪一个具体法术"的判据</b> —— <b>纯逻辑，两个模组共用一份实现</b>。
 *
 * <h2>★★ 它解决的两件事</h2>
 * <ol>
 *   <li><b>委托方第 1 条</b>：「女仆使用 iron 法术时，总是使用法术书的第一个法术。」
 *       —— 根因是硬编码了下标 0。现在改成<b>按当前需求在"她拥有的全部法术"里挑</b>。</li>
 *   <li><b>委托方第 2 条</b>：Goety 的聚晶可以从物品栏 / 聚晶包 / 多晶大袋 / 杖内来，
 *       同样需要"挑哪一颗"。铁魔法与 Goety 的<b>挑选规则完全同构</b>
 *       （都是"在带类别标签的候选里，选类别向量离需求最近的那个"）⇒ <b>写一遍。</b></li>
 * </ol>
 *
 * <h2>★ 判据：与决策层选动作<b>完全一致</b></h2>
 * 决策层选动作 = 在候选里找 {@code argmin d(p, u_x)}。这里换成"类别"这一层：
 * <pre>
 *   ① 在 overrides 里找【类别键 → 向量】，取 weightedDistance(p, v) 最小的那个类别
 *   ② 该类别里有多个具体法术 ⇒ 取【最久没用过的】那个
 * </pre>
 * ★ 第②步是<b>打破"永远只用同一个"</b>的关键：若只有第①步，只要需求不变，
 * 每次都会挑到同一个法术 —— 那只是把"第一个法术"换成了"某个固定法术"。
 *
 * <p>★ 为什么放在纯逻辑层：**判据不含任何 Minecraft 类型** ⇒ 必须能离线断言
 * （这是本项目对"推断/选择类判据"的一贯要求；compat 层只负责把名字与向量取出来）。
 *
 * @see SpellIntent
 */
public final class SpellPicker {

    private SpellPicker() {
    }

    /**
     * 一个候选。
     *
     * @param label  人读标签（法术 id / 类名）—— 也是"最久没用"的键
     * @param intent 类别键（必须与 {@code overrides} 的键逐字一致）
     */
    public record Choice(String label, String intent) {
    }

    /**
     * 挑一个下标。
     *
     * @param options   候选（不能为空）
     * @param need      当前需求向量（弹簧点）；{@code null} ⇒ 只按"最久没用"
     * @param overrides 类别键 → 该类别的需求向量（来自 {@code vectors.json} 的 {@code overrides}）
     * @param lastUsed  标签 → 上次使用时刻；{@code null} ⇒ 视为都没用过
     * @return 选中的下标；{@code options} 为空时返回 {@code -1}
     */
    public static int pickIndex(List<Choice> options, NeedVector need,
                                Map<String, NeedVector> overrides, SpringConfig config,
                                Map<String, Long> lastUsed) {
        return pickIndex(options, need, overrides, config, lastUsed, null);
    }

    /**
     * ★★ 同上，但可以<b>排除一批候选</b>（第十一轮新增：用于"法术自己的冷却"）。
     *
     * <p>为什么排除要在<b>这里</b>而不是调用方：选法的逻辑是"类别最近 → 类别内最久没用"，
     * 若把冷却项留在池子里，会出现"选出正在冷却的那颗 ⇒ 执行失败 ⇒ 不扣代价 ⇒ 再选它"
     * —— 本项目已经踩过三次的那个死循环（见 docs/13 第 22/25/26 条）。
     * ⇒ <b>不可选的项必须从一开始就不在池子里。</b>
     *
     * @param excluded ★ 这些标签<b>不参与</b>（例如还在自己的冷却里）；{@code null}/空 = 不排除
     * @return 选中的下标；<b>全部被排除</b>时返回 {@code -1}（调用方据此说"都在冷却"）
     */
    public static int pickIndex(List<Choice> options, NeedVector need,
                                Map<String, NeedVector> overrides, SpringConfig config,
                                Map<String, Long> lastUsed, Set<String> excluded) {
        if (options == null || options.isEmpty()) {
            return -1;
        }
        List<Choice> pool = options;
        if (excluded != null && !excluded.isEmpty()) {
            pool = new java.util.ArrayList<>(options.size());
            for (Choice c : options) {
                if (!excluded.contains(c.label())) {
                    pool.add(c);
                }
            }
            if (pool.isEmpty()) {
                return -1;                       // ★ 全在冷却 —— 由调用方给出可读的原因
            }
        }
        if (need == null || overrides == null || overrides.isEmpty()) {
            return indexIn(options, leastRecentlyUsedChoice(pool, lastUsed));
        }
        // ① 哪个【类别】最贴近当前需求
        String bestIntent = null;
        double bestDist = Double.MAX_VALUE;
        for (Choice c : pool) {
            NeedVector v = overrides.get(c.intent());
            if (v == null) {
                continue;                       // 该类别没有评分数据 ⇒ 不参与（但下面仍可能兜底）
            }
            double d = need.weightedDistance(v, config);
            if (d < bestDist) {
                bestDist = d;
                bestIntent = c.intent();
            }
        }
        if (bestIntent == null) {
            // 没有任何类别能打分（例如 overrides 的键与分类枚举对不上）⇒ 纯轮换
            return indexIn(options, leastRecentlyUsedChoice(pool, lastUsed));
        }
        // ② 该类别里挑最久没用的
        Choice best = null;
        long bestAt = Long.MAX_VALUE;
        for (Choice c : pool) {
            if (!c.intent().equals(bestIntent)) {
                continue;
            }
            long at = lastUsed == null ? Long.MIN_VALUE
                    : lastUsed.getOrDefault(c.label(), Long.MIN_VALUE);
            if (at < bestAt) {
                bestAt = at;
                best = c;
            }
        }
        return indexIn(options, best != null ? best : leastRecentlyUsedChoice(pool, lastUsed));
    }

    /** 池内最久没用过的那一个（★ 打破"永远只用同一个"）。 */
    private static Choice leastRecentlyUsedChoice(List<Choice> pool, Map<String, Long> lastUsed) {
        Choice best = null;
        long bestAt = Long.MAX_VALUE;
        for (Choice c : pool) {
            long at = lastUsed == null ? Long.MIN_VALUE
                    : lastUsed.getOrDefault(c.label(), Long.MIN_VALUE);
            if (at < bestAt) {
                bestAt = at;
                best = c;
            }
        }
        return best == null ? (pool.isEmpty() ? null : pool.get(0)) : best;
    }

    /**
     * 按<b>对象同一性</b>回查原表下标。
     * <p>★ 必须回查、不能拿池内下标当原表下标 —— 排除了几项之后两者就错位了
     * （这是"把过滤后的下标用回原表"这类经典错误，离线自测里有一条专门钉它）。
     */
    private static int indexIn(List<Choice> all, Choice c) {
        if (c == null) {
            return -1;
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == c) {
                return i;
            }
        }
        return -1;
    }

    /** 最久没用过的那个（★ 打破"永远只用同一个"）。 */
    public static int leastRecentlyUsedIndex(List<Choice> options, Map<String, Long> lastUsed) {
        int best = -1;
        long bestAt = Long.MAX_VALUE;
        for (int i = 0; i < options.size(); i++) {
            long at = lastUsed == null ? Long.MIN_VALUE
                    : lastUsed.getOrDefault(options.get(i).label(), Long.MIN_VALUE);
            if (at < bestAt) {
                bestAt = at;
                best = i;
            }
        }
        return best;
    }
}
