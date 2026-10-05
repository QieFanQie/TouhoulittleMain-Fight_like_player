package com.touhoulittlemad.fightlikeplayer.carrier;

/**
 * 持有位置 —— 载体解析的「物品从哪来」。
 *
 * <h2>为什么不止主副手</h2>
 * ★ 委托方 M2 注记：「由于女仆可以自由切换物品，所以考虑物品时没必要局限于主副手」。
 * 女仆与玩家的关键差别就在这：**她会在战斗中换手、会把东西放进背包**。
 *
 * <p>顺序即「优先级」：同一个载体被多个位置命中时，靠前的槽位更适合作为**执行时的首选物品**
 * （见 {@link CarrierResolver} 的 W 项处理）。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.1</a>
 */
public enum SlotKind {
    /** 主手 —— 绝大多数动作的实际执行位置，优先级最高。 */
    MAINHAND(0),

    /** 副手 —— 盾、灭火器。 */
    OFFHAND(1),

    ARMOR_HEAD(2),
    ARMOR_CHEST(3),
    ARMOR_LEGS(4),
    ARMOR_FEET(5),

    /** 女仆自己的 27 格背包（`maid.getAvailableInv(true)`）。 */
    INVENTORY(6),

    /** ★ TLM 特有的女仆背包（`IMaidBackpack`）。 */
    MAID_BACKPACK(7),

    /** ★ Curios 饰品槽。 */
    CURIOS(8);

    /** 数值越小越优先 —— 用于 W 项在同一载体的多个物品间排序。 */
    private final int priority;

    SlotKind(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /** 是否是"手上"的槽位（执行动作时必须把物品拿到手上）。 */
    public boolean isHand() {
        return this == MAINHAND || this == OFFHAND;
    }
}
