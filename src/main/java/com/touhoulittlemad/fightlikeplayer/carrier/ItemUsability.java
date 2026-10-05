package com.touhoulittlemad.fightlikeplayer.carrier;

/**
 * 物品可用性判据 —— <b>"快坏掉的工具不该再被拿去打架"</b>。
 *
 * <h2>委托方要求</h2>
 * 「若物品耐久低于 5% 或低于 5 次时，其不再为动作列表提供可选动作
 * （但要注意有两份同类的，其中一份没耐久另一份还有的情况）」
 *
 * <h2>★ 为什么判据落在「物品」而不是「载体」</h2>
 * 这正是括号里那句话的关键：
 * <pre>
 *   背包里有 2 把拔刀剑：A 剩 1 点耐久、B 是全新的
 *   ├─ 若判据落在【载体】(slashblade:blade)  ⇒ A 快坏了 ⇒ 整个载体被禁
 *   │                                          ⇒ 女仆【看着 B 却不能用刀】❌
 *   └─ 若判据落在【物品】(这一把刀)            ⇒ A 被排除、B 仍提供动作 ✅
 * </pre>
 * ⇒ 因此本判据只回答"<b>这件物品</b>还能不能用"，
 * 而"这个载体还提不提供动作"由 {@link CarrierResolver} 在**物品池**层面自然得出：
 * **只要池子里还有一件可用的，动作就仍然可用。**
 *
 * <h2>阈值（可调）</h2>
 * <table border="1">
 *   <tr><th>判据</th><th>默认</th><th>理由</th></tr>
 *   <tr><td>剩余百分比</td><td><b>&lt; 5%</b></td><td>低于这个比例，一次攻击就可能把装备打碎</td></tr>
 *   <tr><td>剩余次数</td><td><b>&lt; 5 次</b></td><td>百分比对"高耐久低消耗"的物品不敏感（如 1000 耐久的工具剩 5% = 50 次，还很够用）</td></tr>
 * </table>
 *
 * <p>★ <b>两个条件是"或"</b>：任一命中即视为不可用。
 * 这是刻意的保守取向 —— 宁可提前换一把，也不要在战斗中把武器打碎。
 *
 * <p>⚠️ <b>没有耐久概念的物品</b>（{@code maxDamage == 0}，如食物、方块、多数法术书）
 * <b>永远可用</b> —— 不能因为"没写耐久"就把它判成坏的。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.4</a>
 */
public final class ItemUsability {

    /** 剩余百分比下限（低于它即不可用）。 */
    public static double minRemainingPct = 0.05;

    /** 剩余次数下限（低于它即不可用）。 */
    public static int minRemainingUses = 5;

    private ItemUsability() {
    }

    /**
     * 这件物品还能不能拿去执行动作。
     *
     * @return {@code true} = 可用
     */
    public static boolean isUsable(PossessedItem item) {
        PossessedItem.Durability w = item.wear();
        if (!w.breakable()) {
            return true;                      // 没有耐久概念 ⇒ 永远可用
        }
        if (w.remaining() < minRemainingUses) {
            return false;
        }
        return w.remainingPct() >= minRemainingPct;
    }

    /**
     * 为什么不可用（用于日志/调试）。
     *
     * @return 可用时返回 {@code null}
     */
    public static String whyUnusable(PossessedItem item) {
        PossessedItem.Durability w = item.wear();
        if (!w.breakable()) {
            return null;
        }
        if (w.remaining() < minRemainingUses) {
            return "剩余 " + w.remaining() + " 次 < " + minRemainingUses;
        }
        if (w.remainingPct() < minRemainingPct) {
            return String.format(java.util.Locale.ROOT, "剩余 %.1f%% < %.1f%%",
                    w.remainingPct() * 100, minRemainingPct * 100);
        }
        return null;
    }

    /** 恢复默认阈值（自测用）。 */
    public static void resetDefaults() {
        minRemainingPct = 0.05;
        minRemainingUses = 5;
    }
}
