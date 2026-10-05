package com.touhoulittlemad.fightlikeplayer.decision;

import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.carrier.SlotKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 双循环的物品隔离 —— 把女仆的持有物切成"主循环的"和"副手循环的"。
 *
 * <h2>委托方要求</h2>
 * 「主手+步伐+其他是一个循环，副手自己玩自己的循环。共用一个弹簧空间但动作层独立。
 *  为了避免两个循环用同一个物品的尴尬情况，副手循环的物品和主循环的物品是隔离的：
 *  比如盾（持盾视为一种使用物品盾的持续动作）、goety 中的烈风之伞、灭火器。」
 *
 * <pre>
 *  slot                    归属
 *  ──────────────────────────────────────────────
 *  MAINHAND               主循环
 *  ARMOR_HEAD/CHEST/…     主循环（修饰载体）
 *  INVENTORY              主循环
 *  MAID_BACKPACK          主循环
 *  CURIOS                 主循环
 *  ★ OFFHAND              【副手循环】
 * </pre>
 *
 * <p>⇒ <b>同一个物品不可能同时出现在两个循环的候选里</b>，因为副手槽只有一个，
 * 且它只归副手循环。这从结构上消除了"两个循环抢同一个物品"。
 *
 * <p>★ 副手循环的典型动作是<b>持续型</b>（持盾格挡、撑烈风之伞、灭火器喷烟），
 * 因此它的动作多为 {@code CHANNEL} / 长 {@code COMMIT} —— 与主循环的"打一套"节奏天然错开。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.8</a>
 */
public final class LoopSplit {

    /** 主循环拥有的槽位。 */
    public static final Set<SlotKind> MAIN_SLOTS = EnumSet.of(
            SlotKind.MAINHAND,
            SlotKind.ARMOR_HEAD, SlotKind.ARMOR_CHEST, SlotKind.ARMOR_LEGS, SlotKind.ARMOR_FEET,
            SlotKind.INVENTORY, SlotKind.MAID_BACKPACK, SlotKind.CURIOS);

    /** 副手循环拥有的槽位。 */
    public static final Set<SlotKind> OFFHAND_SLOTS = EnumSet.of(SlotKind.OFFHAND);

    private final List<PossessedItem> main;
    private final List<PossessedItem> offhand;

    private LoopSplit(List<PossessedItem> main, List<PossessedItem> offhand) {
        this.main = List.copyOf(main);
        this.offhand = List.copyOf(offhand);
    }

    /** 把持有物按槽位切开。 */
    public static LoopSplit of(List<PossessedItem> possessed) {
        List<PossessedItem> m = new ArrayList<>();
        List<PossessedItem> o = new ArrayList<>();
        for (PossessedItem item : possessed) {
            if (OFFHAND_SLOTS.contains(item.slot())) {
                o.add(item);
            } else {
                m.add(item);
            }
        }
        return new LoopSplit(m, o);
    }

    public List<PossessedItem> mainItems() {
        return main;
    }

    public List<PossessedItem> offhandItems() {
        return offhand;
    }

    /**
     * 隔离性自检：<b>两个集合的槽位必须不相交</b>。
     *
     * <p>★ 这是一个<b>不变量</b>，值得断言 —— 它保证"两个循环不会用同一个物品"。
     *
     * <p>★★ 2026-10-01 修：旧实现是"任一集合内部出现重复槽位就返回 false"
     * （{@code if (!used.add(i.slot())) return false}）⇒ 而主循环<b>天然</b>会有
     * 27 格背包同属 {@code INVENTORY} ⇒ 在游戏里它<b>永远返回 false</b>，
     * {@code describe()} 于是永远打印"（!! 槽位重叠）"——一个恒假的诊断，
     * 比没有诊断更坏（会让人以为是真 bug）。
     * 正确的不变量是<b>集合层的不相交</b>：主循环的槽位集合 ∩ 副手循环的槽位集合 = ∅。
     */
    public boolean isIsolated() {
        Set<SlotKind> mainSlots = EnumSet.noneOf(SlotKind.class);
        for (PossessedItem i : main) {
            mainSlots.add(i.slot());
        }
        for (PossessedItem i : offhand) {
            if (mainSlots.contains(i.slot())) {
                return false;
            }
        }
        return true;
    }

    public String describe() {
        return "主循环 " + main.size() + " 件 / 副手循环 " + offhand.size() + " 件"
                + (isIsolated() ? "（已隔离）" : "（!! 槽位重叠）");
    }
}
