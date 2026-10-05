package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import java.util.Locale;

/**
 * <b>法术意图：按名字判"这颗聚晶 / 这个法术是干什么的"</b> —— <b>纯逻辑，可离线断言</b>。
 *
 * <h2>★★ 为什么它必须在纯逻辑层（一次自测崩溃换来的教训）</h2>
 * 最初的实现把关键词表放在 {@code compat/exec/irons/IronsSpells} 与
 * {@code compat/exec/goety/GoetyFocusOps} 里，结果自测一调到
 * {@code IronsSpells.classifyName("...")} 就 <b>{@code NoClassDefFoundError: LivingEntity}</b>
 * —— 因为那两个类是 compat 层，签名里带着 {@code EntityMaid} / {@code ItemStack}。
 *
 * <p>⇒ 这违反本项目自己的隔离性纪律（{@code check_isolation.py} 的第②组
 * "纯逻辑层 0 TLM / 0 Minecraft ⇒ 可离线自测"）：<b>判据本身不含 MC 类型，就必须能被离线断言。</b>
 * ⇒ 把它搬到这里。compat 层只负责"把名字取出来"（那是唯一需要 MC 的那一步）。
 *
 * <h2>★ 证据等级：推断（不是勘测）</h2>
 * 分类是<b>按法术 id / 类名的关键词</b>推的，不是读了官方分类。
 * ⇒ 三件事保证它"错了看得见"：
 * <ol>
 *   <li>分类结果会打进日志（施法时那条 INFO）；</li>
 *   <li>关键词表在这里，改一行就能修，不需要动兼容层；</li>
 *   <li>自测里钉了一批真实法术 id 的正例与反例（含"不能被 life 之类误伤"的对照）。</li>
 * </ol>
 * 彻底的方案仍是 W14/W32（读调律师的 {@code FocusClassifier} 或自研分类器）。
 */
public final class SpellIntent {

    private SpellIntent() {
    }

    /** 铁魔法法术意图 —— 与 {@code vectors.json} 里 {@code irons:cast_spell.overrides} 的键<b>逐字一致</b>。 */
    public enum Intent {
        ATTACK, SUPPORT, SUMMON
    }

    /** Goety 聚晶类别 —— 与 {@code vectors.json} 里 {@code goety:cast_focus.overrides} 的键<b>逐字一致</b>。 */
    public enum Category {
        ATTACK_SINGLE, ATTACK_AREA, SUMMON, UTILITY, PROTECTION, HEALING, CONTROL, SERVANT_MGMT
    }

    // ───────────────────────── 铁魔法 ─────────────────────────

    /**
     * 铁魔法：把"法术类名 / 法术 id"判成三类之一。
     *
     * <p>★ <b>证据等级</b>（W32 之后，常规路径从"关键词"升到"已复核的表"）：
     * <ol>
     *   <li>① <b>已复核的表</b>（{@code catalog/data/spell_intent.json}，由
     *       {@link SpellIntentTable} 装进来）—— 常规路径；</li>
     *   <li>② 关键词推断 —— 表里没有的<b>新法术</b>的兜底；</li>
     *   <li>③ {@code ATTACK} —— 铁魔法绝大多数法术确实是攻击性的。</li>
     * </ol>
     *
     * @param className 法术类的<b>简单名</b>（如 {@code FireballSpell}）—— 表就是按它索引的
     * @param fallbackName 兜底用的名字串（类名 + 法术 id）
     */
    public static Intent ironsIntent(String className, String fallbackName) {
        String t = SpellIntentTable.lookupIrons(className);
        if (t != null) {
            try {
                return Intent.valueOf(t);
            } catch (IllegalArgumentException ignore) {
                // 表里写了非法值 ⇒ 当没查到，继续走关键词（并且是可被审计发现的）
            }
        }
        return ironsIntentByName(fallbackName);
    }

    /** 兼容旧签名（只给关键词那一层用）。 */
    public static Intent ironsIntent(String rawName) {
        return ironsIntentByName(rawName);
    }

    /**
     * 铁魔法：<b>纯关键词</b>那一层。
     *
     * <p>★ 顺序有意：<b>支援 → 召唤 → 攻击</b>。因为"治疗/护盾"类法术名里几乎不含攻击词，
     * 而反过来不然（{@code blood_slash} 是攻击、{@code blood_heal} 是支援）。
     */
    public static Intent ironsIntentByName(String rawName) {
        String n = lower(rawName);
        if (containsAny(n, "heal", "cure", "regen", "mend", "restore", "life_steal", "lifesteal",
                "shield", "ward", "barrier", "fortif", "absorb", "resist", "bless", "haste",
                "invisib", "cleanse", "dispel", "echoing", "guiding", "angel")) {
            return Intent.SUPPORT;
        }
        if (containsAny(n, "summon", "raise", "zombie", "skeleton", "wither_skeleton", "vex",
                "wolf", "bear", "spider", "slime", "magma", "necromanc", "horde", "hollow",
                "spectral_hammer", "sacrifice", "familiar")) {
            return Intent.SUMMON;
        }
        return Intent.ATTACK;
    }

    // ───────────────────────── Goety ─────────────────────────

    /**
     * Goety：把"聚晶类名 + 法术学派"判成八类之一。
     *
     * <p>★ <b>证据等级</b>：
     * <ol>
     *   <li>① <b>已复核的表</b>（W32）—— 常规路径；</li>
     *   <li>② 关键词推断 —— 表里没有的新法术的兜底；</li>
     * </ol>
     * <p>⚠️ 接口级证据（{@code instanceof ISummonSpell}）在<b>调用方</b>那里就先判了 ——
     * 那是比表更硬的一手证据（见 {@code GoetyFocusOps#classify}）。
     *
     * @param className    法术类简单名（表按它索引）
     * @param fallbackName 兜底名字串（类名 + 学派）
     * @return {@code null} = <b>推不出来</b>（让调用方继续用"法术自身的属性"判，
     *         例如 {@code SpellStat} 的强度与半径）—— <b>刻意不硬猜</b>。
     */
    public static Category goetyCategory(String className, String fallbackName) {
        String t = SpellIntentTable.lookupGoety(className);
        if (t != null) {
            try {
                return Category.valueOf(t);
            } catch (IllegalArgumentException ignore) {
                // 表里写了非法值 ⇒ 当没查到（审计会发现）
            }
        }
        return goetyCategoryByName(fallbackName);
    }

    /** 兼容旧签名。 */
    public static Category goetyCategory(String rawName) {
        return goetyCategoryByName(rawName);
    }

    /** Goety：<b>纯关键词</b>那一层。 */
    public static Category goetyCategoryByName(String rawName) {
        String n = lower(rawName);
        if (containsAny(n, "heal", "cure", "regen", "mend", "life", "vital", "restor")) {
            return Category.HEALING;
        }
        if (containsAny(n, "shield", "protect", "ward", "barrier", "absorb", "guard", "defen",
                "resist", "armor", "fortif")) {
            return Category.PROTECTION;
        }
        if (containsAny(n, "slow", "freeze", "frost", "ice", "stun", "bind", "trap", "web",
                "grasp", "entangl", "root", "wind", "gust", "knock")) {
            return Category.CONTROL;
        }
        if (containsAny(n, "command", "servant", "recall", "order", "stance")) {
            return Category.SERVANT_MGMT;
        }
        if (containsAny(n, "summon", "raise", "zombie", "skeleton", "golem", "necromanc")) {
            return Category.SUMMON;
        }
        return null;
    }

    /** ★ 是否"攻击性"类别 —— 用于"挑法术"之外的诊断与统计。 */
    public static boolean isOffensive(Category c) {
        return c == Category.ATTACK_SINGLE || c == Category.ATTACK_AREA;
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
