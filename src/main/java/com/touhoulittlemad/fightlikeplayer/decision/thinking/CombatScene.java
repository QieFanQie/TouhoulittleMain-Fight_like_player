package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>「指挥官 LLM 能看到什么」</b> —— 一份<b>可核实的</b>事实清单（纯逻辑，无 MC 类型）。
 *
 * <h2>★★ 为什么要有这个类（委托方第十四轮的问题）</h2>
 * 委托方问：「我想知道指挥 llm 能得知什么信息，你**核实一下**：
 * <b>每个指令的下达所需要的信息，llm 都能获取到相关内容吗？</b>」
 *
 * <p>★ 这一问是有牙齿的 —— 核实结果是：**此前很多指令它根本没法判断**。
 * 当时它只拿到 {@link BehaviorStats} 的统计（动作直方图/受伤/需求均值）+ 指令表 + 旋钮。
 * 于是：
 * <ul>
 *   <li>{@code magic_only}（仅魔法）—— 它<b>不知道她有没有法术书/法杖</b> ⇒ 可能下一条她根本执行不了的指令；</li>
 *   <li>{@code focus_category} / {@code ban_focus} —— 它<b>不知道她有哪些聚晶</b>（label 与类别）；</li>
 *   <li>{@code conserve_ammo} / {@code reload_now} —— 不知道她有没有枪；</li>
 *   <li>{@code stance_guard} —— 不知道她有没有盾；</li>
 *   <li>{@code keep_distance} —— 不知道现在距离多少（只有"需求轴"）；</li>
 *   <li>{@code no_summons} / {@code recall_servants} —— 不知道她有<b>几个</b>仆从；</li>
 *   <li>{@code protect_owner} —— 不知道主人的血量与距离；</li>
 *   <li>{@code interrupt} —— 不知道她<b>此刻是不是正在引导/蓄力</b>。</li>
 * </ul>
 *
 * <h2>★ 处置：把它需要的东西<b>显式建模成一个类</b>，并让"指令需要什么"可被断言</h2>
 * <ol>
 *   <li>本类列出全部事实字段（{@link #KEYS} 是**权威键集合**）；</li>
 *   <li>{@code DirectiveSpec} 的每条指令声明 {@code needs}（它靠哪些键判断）；</li>
 *   <li>{@code DirectiveSelfTest} 断言：<b>每条指令的每个 needs 键都在 {@link #KEYS} 里</b>
 *       ⇒ "模型拿不到判断依据"这件事<b>不可能再悄悄发生</b>。</li>
 * </ol>
 * ★ 判据（写进 docs/13）：**"模型要做这个判断，它需要什么？我给了吗？"** ——
 * 与"每个位置都算了吗"（第 56 条）同族，这次落在**输入侧**。
 */
public final class CombatScene {

    private CombatScene() {
    }

    /**
     * 她的可施法手段里的一颗法术/聚晶。
     *
     * @param blocked ★ 这颗现在被**她自己的指令**挡着（{@code focus_category} / {@code ban_focus} /
     *                {@code no_summons}）—— ★★ 必须如实告诉她：否则她会以为"她就没这法术"，
     *                于是把自己刚下的指令又撤了（见 {@link #of} 的说明）
     */
    public record SpellOption(String label, String category, String source, boolean blocked) {
    }

    /**
     * ★★ 她身上的一件物品（**给「半命题指令」填参数用**）。
     *
     * <p>委托方第十五轮问：「既然 llm 已经可以获得背包里可用物品列表了（**可以吗？**）」
     * —— 核实答案：**在此之前不能**。当时只有 `equipment.has_melee_weapon` 那几个布尔，
     * 模型知道"她有近战武器"，但**不知道"是哪一件"** ⇒ 没法说"只用 minecraft:iron_sword"。
     * ⇒ 现在把清单给它（有上限，见 {@code items_truncated}）。
     *
     * <p>★★ 第十七轮续：{@code id} 改成**身份 id** —— 枪是**枪自己的 id**（`tacz:rpg7`），
     * 其余是注册名。⚠️ TaCZ 所有现代枪**共用一个物品注册名**（`tacz:modern_kinetic_gun`）
     * ⇒ 用注册名的话五把枪会挤成一行，模型只能猜
     * （委托方实测：「我让她拿火箭筒，她拿了一个不存在的东西」）。
     *
     * @param where MAINHAND / OFFHAND / INVENTORY / …（{@code SlotKind} 的名字，日志与参数都用它）
     * @param name  ★ 人读名（「M249 机枪」）—— 让模型能把玩家说的「机枪」对到某个 id 上
     */
    public record ItemLine(String id, int count, String where, String name) {

        /** 兼容构造：没有人读名时用 id 当名字。 */
        public ItemLine(String id, int count, String where) {
            this(id, count, where, id);
        }
    }

    /**
     * ★ <b>权威键集合</b>：本类会产出的全部字段路径。
     * <p>指令的 {@code needs} 必须落在这里面（由自测断言）。
     */
    public static final Set<String> KEYS = keys();

    private static Set<String> keys() {
        Set<String> s = new LinkedHashSet<>(List.of(
                // 自身
                "self.health_pct", "self.on_fire", "self.in_flight_action", "self.gait",
                "self.distance_to_target", "self.main_hand", "self.off_hand",
                // 装备与能力
                "equipment.has_melee_weapon", "equipment.has_wand", "equipment.has_spellbook_or_scroll",
                "equipment.has_gun", "equipment.has_bow", "equipment.has_shield",
                "equipment.has_extinguisher", "equipment.inventory_items",
                // ★★ 第十五轮：物品清单本身（半命题指令 only_item/ban_item 的参数来源）
                "equipment.items", "equipment.items_truncated",
                // ★★ 第十六轮：她自己能用哪些动作 id（only_actions 的参数来源）
                "actions_available", "actions_truncated",
                // 法术
                "spells_available",
                // 仆从
                "servants",
                // 主人
                "owner.health_pct", "owner.distance",
                // 战斗
                "battle.has_target", "battle.enemies_nearby", "battle.nearest_enemy_distance",
                "battle.target_health_pct", "battle.target_kind", "battle.target_uuid"));
        return java.util.Collections.unmodifiableSet(s);
    }

    /** 一个"场景"的全部事实（不可变）。 */
    public record Scene(Map<String, Object> facts) {

        /** 一行摘要（日志用）。 */
        public String summary() {
            return "自身 " + pct(facts.get("self.health_pct")) + "　主手 " + facts.get("self.main_hand")
                    + "　法术 " + size(facts.get("spells_available")) + " 个"
                    + "　仆从 " + facts.get("servants")
                    + "　敌人 " + facts.get("battle.enemies_nearby")
                    + "　距离 " + facts.get("self.distance_to_target");
        }

        private static String pct(Object o) {
            return o instanceof Number n
                    ? String.format(java.util.Locale.ROOT, "%.0f%%", n.doubleValue() * 100) : "?";
        }

        private static String size(Object o) {
            return o instanceof List<?> l ? String.valueOf(l.size()) : "0";
        }
    }

    /**
     * 组装（**唯一的产出入口** —— 键名只在这里出现，避免"两处口径不一致"）。
     *
     * @param healthPct        自身血量比例
     * @param onFire           是否着火
     * @param inFlightAction   在途动作 id（null = 空闲）
     * @param gaitZh           当前步法的中文描述
     * @param distanceToTarget 与目标的距离（无目标传 -1）
     * @param mainHand         主手物品 id
     * @param offHand          副手物品 id
     * @param invItems         背包里的物品件数
     * @param hasMelee         有近战武器（剑/斧/拔刀剑…）
     * @param hasWand          有诡厄巫法的法杖
     * @param hasSpellbook     有铁魔法的法术书/卷轴
     * @param hasGun           有枪械
     * @param hasBow           有弓/弩/三叉戟
     * @param hasShield        有盾
     * @param hasExtinguisher  有灭火器
     * @param spells           她此刻能放的法术/聚晶（含类别与来源）
     * @param servants         仆从数
     * @param ownerHealthPct   主人血量比例（无主人传 -1）
     * @param ownerDistance    与主人的距离（无主人传 -1）
     * @param hasTarget        当前有没有目标
     * @param enemies          附近敌人数量
     * @param nearestEnemy     最近敌人距离（无则 -1）
     * @param targetHealthPct  目标血量比例（无则 -1）
     * @param items            ★ 她身上的物品清单（有上限；{@code only_item}/{@code ban_item} 的参数来源）
     * @param itemsTruncated   ★ 因为太长而没列出来的件数（>0 时模型知道"还有别的"）
     * @param targetKind       ★ 当前目标的生物 id（如 {@code minecraft:zombie}；无目标 = 空串）
     * @param targetUuid       ★ 当前目标的 UUID（{@code focus_one} 的参数就填它）
     * @param actions          ★★ 她现在**真正能做**的动作 id（{@code only_actions} 的参数来源）
     * @param actionsTruncated ★ 因为太长没列出来的动作条数
     */
    public static Scene of(double healthPct, boolean onFire, String inFlightAction, String gaitZh,
                           double distanceToTarget, String mainHand, String offHand, int invItems,
                           boolean hasMelee, boolean hasWand, boolean hasSpellbook, boolean hasGun,
                           boolean hasBow, boolean hasShield, boolean hasExtinguisher,
                           List<SpellOption> spells, int servants,
                           double ownerHealthPct, double ownerDistance,
                           boolean hasTarget, int enemies, double nearestEnemy,
                           double targetHealthPct,
                           List<ItemLine> items, int itemsTruncated, String targetKind,
                           String targetUuid, List<String> actions, int actionsTruncated) {
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("health_pct", round(healthPct));
        self.put("on_fire", onFire);
        self.put("in_flight_action", inFlightAction == null ? "" : inFlightAction);
        self.put("gait", gaitZh == null ? "" : gaitZh);
        self.put("distance_to_target", round(distanceToTarget));
        self.put("main_hand", mainHand == null ? "" : mainHand);
        self.put("off_hand", offHand == null ? "empty" : offHand);
        f.put("self", self);

        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("has_melee_weapon", hasMelee);
        eq.put("has_wand", hasWand);
        eq.put("has_spellbook_or_scroll", hasSpellbook);
        eq.put("has_gun", hasGun);
        eq.put("has_bow", hasBow);
        eq.put("has_shield", hasShield);
        eq.put("has_extinguisher", hasExtinguisher);
        eq.put("inventory_items", invItems);
        // ★★ 第十五轮：把**清单本身**给它（半命题指令的参数来源）。
        //   有上限：`items_truncated > 0` 表示"还有没列出来的"（模型据此知道自己看到的不是全部）。
        List<Map<String, Object>> itemLines = new ArrayList<>();
        for (ItemLine it : items == null ? List.<ItemLine>of() : items) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", it.id());
            one.put("count", it.count());
            one.put("where", it.where());
            // ★ 人读名（「M249 机枪」）：模型据此把玩家说的「机枪」对到 id 上
            one.put("name", it.name());
            itemLines.add(one);
        }
        eq.put("items", itemLines);
        eq.put("items_truncated", itemsTruncated);
        f.put("equipment", eq);

        List<Map<String, Object>> sp = new ArrayList<>();
        for (SpellOption o : spells == null ? List.<SpellOption>of() : spells) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("label", o.label());
            one.put("category", o.category());
            one.put("source", o.source());
            if (o.blocked()) {
                one.put("blocked_by_your_directive", true);
            }
            sp.add(one);
        }
        f.put("spells_available", sp);
        f.put("servants", servants);

        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("health_pct", round(ownerHealthPct));
        owner.put("distance", round(ownerDistance));
        f.put("owner", owner);

        Map<String, Object> battle = new LinkedHashMap<>();
        battle.put("has_target", hasTarget);
        battle.put("enemies_nearby", enemies);
        battle.put("nearest_enemy_distance", round(nearestEnemy));
        battle.put("target_health_pct", round(targetHealthPct));
        // ★★ 第十五轮：`focus_entity` / `focus_one` 的参数来源 ——
        //   模型要能回答"我现在在打谁？是哪一只？"才敢下"只打这种/只打这只"。
        battle.put("target_kind", targetKind == null ? "" : targetKind);
        battle.put("target_uuid", targetUuid == null ? "" : targetUuid);
        f.put("battle", battle);

        // ★★ 第十六轮：她自己**现在真能做**的动作 id 清单
        //   —— `only_actions`（"接下来几秒只做这些动作"）的参数来源。
        //   有上限（`actions_truncated` 表示还有没列出来的），因为它可能上百条。
        f.put("actions_available", actions == null ? List.of() : List.copyOf(actions));
        f.put("actions_truncated", actionsTruncated);

        return new Scene(Map.copyOf(f));
    }

    /** 一个"什么都没有"的场景（离线自测/构建失败时的兜底）。 */
    public static Scene empty() {
        return of(1.0, false, null, "", -1, "", "", 0,
                false, false, false, false, false, false, false,
                List.of(), 0, -1, -1, false, 0, -1, -1,
                List.of(), 0, "", "", List.of(), 0);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
