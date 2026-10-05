package com.touhoulittlemad.fightlikeplayer.carrier;

import java.util.Map;
import java.util.Set;

/**
 * 一件被女仆持有的物品 —— <b>脱离 Minecraft 的抽象表示</b>。
 *
 * <h2>为什么这样设计</h2>
 * 载体解析的全部判断都只需要这几样东西：
 * <ol>
 *   <li>它在哪个槽位（{@link SlotKind}）；</li>
 *   <li>它的注册名（{@code itemId}）；</li>
 *   <li>它的<b>物品标签</b>（数据驱动，最稳的匹配依据）；</li>
 *   <li>它的<b>类型名集合</b>与<b>能力集合</b>；</li>
 *   <li>★ 它的<b>物品参数</b>（{@code params}）—— A 档参数化的落点；</li>
 *   <li>★ 它的<b>磨损</b>（{@code wear}）—— 快坏掉的工具不该再被拿去打架。</li>
 * </ol>
 *
 * <p>★ 后三项由游戏侧<b>适配器</b>预先算好，而且都只是<b>字符串与数字</b>
 * ⇒ <b>匹配与参数解析因此完全不含 Minecraft 类型，可以在纯 JVM 里断言</b>。
 *
 * @param slot         持有位置
 * @param itemId       注册名，如 {@code slashblade:slashblade}
 * @param itemTags     物品标签
 * @param typeNames    类型名集合（全名 + 简单名都放进来）
 * @param capabilities 具名能力集合
 * @param params       物品参数（参数名 → 值），供 {@code vectorOverrides} 使用
 * @param count        数量（≥1）
 * @param firstSlotIndex 该物品所在的具体槽位序号
 * @param wear         ★ 磨损信息（耐久）。见 {@link Durability}
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.1-§4.5</a>
 */
public record PossessedItem(
        SlotKind slot,
        String itemId,
        Set<String> itemTags,
        Set<String> typeNames,
        Set<String> capabilities,
        Map<String, String> params,
        int count,
        int firstSlotIndex,
        Durability wear,
        /**
         * ★★ <b>人读名</b>（第十七轮续）—— 例如 {@code M249 机枪}、{@code RPG-7 火箭筒}。
         *
         * <p>为什么必须单独有一个：TaCZ 的现代枪**全部共用一个物品注册名**
         * （{@code tacz:modern_kinetic_gun}），真正的身份在 NBT 的 GunId 里
         * ⇒ 只靠 {@code itemId} 根本区分不了枪（委托方实测：「我让她拿火箭筒，
         * 她拿了一个不存在的东西 {@code tacz:modern_kinetic_gun}」）。
         */
        String displayName,
        /**
         * ★★ <b>可匹配的别名</b>（第十七轮续）—— 枪型（{@code mg}/{@code rpg}）、
         * 枪型中文（「机枪」）等"模型/玩家可能说出来的另一种写法"。
         *
         * <p>匹配判据统一在 {@link ItemQuery}（别名与 id/标签/类型名同一套分级）。
         */
        Set<String> aliases
) {

    /**
     * 磨损／耐久。
     *
     * @param damage    已损耗点数（0 = 全新）
     * @param maxDamage 最大耐久；<b>0 表示"没有耐久概念"</b>（如方块、食物、多数法术书）
     */
    public record Durability(int damage, int maxDamage) {

        /** 没有耐久概念的物品。 */
        public static Durability none() {
            return new Durability(0, 0);
        }

        public static Durability of(int damage, int maxDamage) {
            return new Durability(Math.max(0, damage), Math.max(0, maxDamage));
        }

        /** 是否消耗耐久。 */
        public boolean breakable() {
            return maxDamage > 0;
        }

        /** 剩余可用次数。 */
        public int remaining() {
            return maxDamage <= 0 ? Integer.MAX_VALUE : Math.max(0, maxDamage - damage);
        }

        /** 剩余百分比（不可破坏者恒 1.0）。 */
        public double remainingPct() {
            return maxDamage <= 0 ? 1.0 : (double) remaining() / maxDamage;
        }

        @Override
        public String toString() {
            return breakable() ? remaining() + "/" + maxDamage : "∞";
        }
    }

    public PossessedItem {
        if (slot == null) {
            throw new IllegalArgumentException("slot 不能为空");
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count 必须 ≥ 1：" + count);
        }
        itemTags = itemTags == null ? Set.of() : Set.copyOf(itemTags);
        typeNames = typeNames == null ? Set.of() : Set.copyOf(typeNames);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        params = params == null ? Map.of() : Map.copyOf(params);
        wear = wear == null ? Durability.none() : wear;
        displayName = displayName == null ? "" : displayName;
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }

    /** 兼容构造：不带磨损信息（旧调用点无需改动）。 */
    public PossessedItem(SlotKind slot, String itemId, Set<String> itemTags, Set<String> typeNames,
                         Set<String> capabilities, Map<String, String> params,
                         int count, int firstSlotIndex) {
        this(slot, itemId, itemTags, typeNames, capabilities, params, count, firstSlotIndex,
                Durability.none(), "", Set.of());
    }

    /** 兼容构造：不带人读名/别名（第十七轮续新增两个分量时留的**零改动**入口）。 */
    public PossessedItem(SlotKind slot, String itemId, Set<String> itemTags, Set<String> typeNames,
                         Set<String> capabilities, Map<String, String> params,
                         int count, int firstSlotIndex, Durability wear) {
        this(slot, itemId, itemTags, typeNames, capabilities, params, count, firstSlotIndex,
                wear, "", Set.of());
    }

    /** 便利构造：数量 1、槽位序号 0。 */
    public static PossessedItem of(SlotKind slot, String itemId, Set<String> typeNames) {
        return new PossessedItem(slot, itemId, Set.of(), typeNames, Set.of(), Map.of(), 1, 0);
    }

    /** 便利构造：带能力集合。 */
    public static PossessedItem withCapabilities(SlotKind slot, String itemId,
                                                 Set<String> typeNames, Set<String> capabilities) {
        return new PossessedItem(slot, itemId, Set.of(), typeNames, capabilities, Map.of(), 1, 0);
    }

    /** 便利构造：带物品参数（A 档参数化用）。 */
    public static PossessedItem withParams(SlotKind slot, String itemId,
                                           Set<String> typeNames, Map<String, String> params) {
        return new PossessedItem(slot, itemId, Set.of(), typeNames, Set.of(), params, 1, 0);
    }

    /** 带上磨损信息的副本。★ 注意把新分量一起带上（否则会静默丢名字/别名）。 */
    public PossessedItem withWear(int damage, int maxDamage) {
        return new PossessedItem(slot, itemId, itemTags, typeNames, capabilities, params,
                count, firstSlotIndex, Durability.of(damage, maxDamage), displayName, aliases);
    }

    /** ★ 带人读名与别名的副本（游戏侧适配器用）。 */
    public PossessedItem withIdentity(String displayName, Set<String> aliases) {
        return new PossessedItem(slot, itemId, itemTags, typeNames, capabilities, params,
                count, firstSlotIndex, wear, displayName, aliases);
    }

    /**
     * ★ 这件物品是否还<b>值得拿去执行动作</b>。
     *
     * <p>阈值见 {@link ItemUsability}（5% 与 5 次，可调）。
     * <p>⚠️ 判据是"<b>这件物品</b>"，不是"这个载体" ——
     * 因此**同类的另一件（还有耐久的）仍能提供该动作**。这正是委托方要求的效果。
     */
    public boolean isUsable() {
        return ItemUsability.isUsable(this);
    }

    public boolean hasCapability(String key) {
        return capabilities.contains(key);
    }

    /** 是否属于给定类型（支持全名或简单名）。 */
    public boolean isType(String target) {
        return typeNames.contains(target);
    }

    /** 取物品参数，缺省为 null。 */
    public String param(String name) {
        return params.get(name);
    }

    /** 调试用短名。 */
    public String shortName() {
        return itemId + "@" + slot;
    }

    @Override
    public String toString() {
        return shortName() + (count > 1 ? "x" + count : "")
                + (wear.breakable() ? "(" + wear + ")" : "");
    }
}
