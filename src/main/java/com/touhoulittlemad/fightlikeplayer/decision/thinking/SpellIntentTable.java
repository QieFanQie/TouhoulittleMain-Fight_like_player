package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>W32：法术意图表</b> —— 从 {@code catalog/data/spell_intent.json} 装进来的一张<b>查表</b>。
 *
 * <h2>★★ 为什么"查表"比"运行期猜关键词"好</h2>
 * 运行期能看到的只有<b>类名</b>与少量属性。而类名是<b>有限的、可以从 jar 里一次枚举完的</b>
 * （实测：铁魔法 113 个法术类、Goety 105 个）。
 * ⇒ 那就<b>一次列清、逐条定类、写进数据文件</b>，运行时只查表：
 * <ul>
 *   <li><b>可复核</b>：一次 {@code diff} 就能看出"这轮改了哪几条分类"；</li>
 *   <li><b>不会静默兜底</b>：新版本加了法术时，{@code tools/audit_spell_intent.py} 会
 *       报"有 N 条没分类"并失败，而不是悄悄按 ATTACK 处理；</li>
 *   <li><b>运行期零推测</b>：查得到就用表，查不到才退回关键词/属性。</li>
 * </ul>
 *
 * <h2>★ 证据等级（比之前的关键词版高一级）</h2>
 * <pre>
 *   ① 接口级证据   ：ISummonSpell 等 instanceof  —— 最硬，不查表
 *   ② 已复核的表   ：本表（生成 + 人工复核 15 条）—— 常规路径
 *   ③ 关键词推断   ：类名含 heal/shield/... —— 表里没有的新法术的兜底
 *   ④ 属性推断     ：SpellStat 的强度/半径 —— 关键词也推不出来时
 *   ⑤ 保守兜底     ：UTILITY / ATTACK（取决于模组）
 * </pre>
 * ⇒ 与旧版的差别在于：**常规路径从③升到了②**。
 *
 * <p>★ 线程模型：只读 + {@code volatile} 一次性换表（{@code /reload} 时整张换掉），
 * 因此运行期读取无需加锁。
 */
public final class SpellIntentTable {

    private SpellIntentTable() {
    }

    /**
     * 两张表。
     *
     * @param irons 类简单名 → {@code ATTACK/SUPPORT/SUMMON}
     * @param goety 类简单名 → {@code ATTACK_SINGLE/ATTACK_AREA/SUMMON/UTILITY/PROTECTION/HEALING/CONTROL/SERVANT_MGMT}
     */
    public record Table(Map<String, String> irons, Map<String, String> goety,
                        /**
                         * ★★ <b>行为标记</b>（类简单名 → 标记）——与"意图分类"<b>分开</b>，
                         * 因为它回答的是另一个问题：<b>这个法术能不能自动放。</b>
                         * <p>当前取值：
                         * <ul>
                         *   <li>{@code SELF_HARM} —— 会伤到施法者自己 ⇒ <b>自动施法时跳过</b>
                         *       （例：Goety 的 {@code KillingSpell}，对施法者造成目标当前血量 125% 的伤害）；</li>
                         *   <li>{@code SNEAK_CLEARS_SERVANTS} —— <b>潜行施法时会杀掉自己的仆从</b>
                         *       ⇒ 施法前必须确认不在潜行，确认不了就跳过
                         *       （委托方实测的「刚召唤就清除」正是这一条）。</li>
                         * </ul>
                         */
                        Map<String, String> flags) {

        public static Table empty() {
            return new Table(Map.of(), Map.of(), Map.of());
        }

        public Table {
            irons = irons == null ? Map.of() : Map.copyOf(irons);
            goety = goety == null ? Map.of() : Map.copyOf(goety);
            flags = flags == null ? Map.of() : Map.copyOf(flags);
        }

        public boolean isEmpty() {
            return irons.isEmpty() && goety.isEmpty();
        }

        /** 这个法术的标记；没有则 {@code null}。 */
        public String flagOf(String className) {
            return flags.get(className);
        }

        /** 是否带某个标记。 */
        public boolean hasFlag(String className, String flag) {
            return flag.equals(flags.get(className));
        }
    }

    private static volatile Table table = Table.empty();

    /**
     * 换上一张新表（由清单加载器在加载/热重载时调用）。
     *
     * <p>★ 空表<b>不会</b>把已有表清掉 —— 与 {@code CatalogHolder.install} 同一纪律：
     * 一次读失败不该让运行中的体系降级。
     */
    public static void install(Table t) {
        if (t == null || t.isEmpty()) {
            return;
        }
        table = t;
    }

    /** 当前表（只读）。 */
    public static Table current() {
        return table;
    }

    /** 表里有没有这一条（审计/诊断用）。 */
    public static boolean has(String className, boolean goety) {
        return (goety ? table.goety() : table.irons()).containsKey(className);
    }

    /** 铁魔法：查表；没有则返回 {@code null}（交给关键词兜底）。 */
    public static String lookupIrons(String className) {
        return className == null ? null : table.irons().get(className);
    }

    /** Goety：查表；没有则返回 {@code null}。 */
    public static String lookupGoety(String className) {
        return className == null ? null : table.goety().get(className);
    }

    /** 已装表的规模（诊断用）。 */
    public static String summary() {
        return "法术意图表：铁魔法 " + table.irons().size() + " 条 / Goety " + table.goety().size() + " 条";
    }

    /** 从"文件里的 JSON 结构"构造（由 {@code CatalogLoader} 调用）。 */
    public static Table fromMaps(Map<String, String> irons, Map<String, String> goety) {
        return fromMaps(irons, goety, Map.of());
    }

    /** 带行为标记的完整构造（由 {@code CatalogLoader} 调用）。 */
    public static Table fromMaps(Map<String, String> irons, Map<String, String> goety,
                                 Map<String, String> flags) {
        return new Table(irons == null ? Map.of() : new LinkedHashMap<>(irons),
                goety == null ? Map.of() : new LinkedHashMap<>(goety),
                flags == null ? Map.of() : new LinkedHashMap<>(flags));
    }

    /** 全部已分类/未分类的检查（供离线自测与审计共用一份判据）。 */
    public static Set<String> keysOf(boolean goety) {
        return goety ? table.goety().keySet() : table.irons().keySet();
    }
}
