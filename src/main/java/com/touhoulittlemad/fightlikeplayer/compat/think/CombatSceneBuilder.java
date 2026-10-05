package com.touhoulittlemad.fightlikeplayer.compat.think;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.behavior.ExtinguishServo;
import com.touhoulittlemad.fightlikeplayer.compat.behavior.OffhandStance;
import com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat;
import com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder;
import com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors;
import com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors;
import com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyFocusOps;
import com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps;
import com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsExecutors;
import com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;
import com.touhoulittlemad.fightlikeplayer.compat.maid.TargetRefs;
import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.CombatScene;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>把"活的女仆"翻译成 {@link CombatScene}</b>（游戏侧适配器）。
 *
 * <h2>为什么要有这一层（委托方第十四轮的核实）</h2>
 * 委托方问：「每个指令的下达<b>所需要的信息</b>，llm 都能获取到相关内容吗？」
 * 核实结论：<b>此前拿不到</b> —— 它只有动作直方图/受伤/需求均值那一套统计。
 * 于是"仅用魔法""只用某类聚晶""省弹药""优先护主"这些指令，
 * 它<b>没有依据去下、也没有依据去撤</b>。
 *
 * <p>本类负责把那些依据<b>如实地</b>取出来：
 * <ul>
 *   <li>{@code equipment.*} —— 手上/包里到底有什么（复用手上的口径方法，不另造一套判断）；</li>
 *   <li>{@code spells_available} —— ★ <b>不经过指令过滤</b>地列出她<b>真正的</b>法术池，
 *       另附 {@code blocked_by_your_directive} 标记。<br>
 *       ★★ 这一点很关键：若直接复用执行期的枚举（它已经按指令过滤过），
 *       那么她下了"只用治疗"之后，模型看到的法术池里就<b>只剩治疗</b>，
 *       它会由此以为"她没有攻击法术" ⇒ <b>把自己刚下的指令当成事实、越走越偏</b>。
 *       ⇒ 所以场景里的池子必须**不经过指令**，被挡住的那几颗要**显式标出来**。</li>
 *   <li>{@code owner.*} / {@code battle.*} —— 主人血量距离、敌人数量与最近距离；</li>
 *   <li>{@code servants} —— 仆从数（`no_summons`/`recall`/`dismiss` 的依据）。</li>
 * </ul>
 * ★ 与 {@link CombatScene}（纯逻辑）分工：那边定义"有哪些事实、键叫什么"，
 * 这边只负责"从游戏里取出来" —— 键名冲突由 {@code DirectiveSelfTest} 挡住。
 */
public final class CombatSceneBuilder {

    private CombatSceneBuilder() {
    }

    /** 统计周围敌人时的半径（格）。 */
    private static final double ENEMY_RADIUS = 16.0;

    /** ★ 已经报过"场景构建失败"的女仆（只报一次，避免刷屏）。 */
    private static final java.util.Set<java.util.UUID> WARNED = java.util.Collections
            .newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * 构建场景。★ <b>绝不抛异常</b>：取不到的部分退化成"未知/空"，
     * 并且**只报一次**（docs/13：可预期的失败要有可读出口，但也不能刷屏）。
     */
    public static CombatScene.Scene build(EntityMaid maid) {
        if (maid == null) {
            return CombatScene.empty();
        }
        try {
            return doBuild(maid);
        } catch (RuntimeException | LinkageError e) {
            // ★ LinkageError 也要接：模组缺席时会抛 NoClassDefFoundError（本项目踩过两次）
            if (WARNED.add(maid.getUUID())) {
                FightLikePlayer.LOGGER.info("[FLP] 战斗场景构建失败（指挥 LLM 将只拿到统计量）：{}",
                        e.toString());
            }
            return CombatScene.empty();
        }
    }

    private static CombatScene.Scene doBuild(EntityMaid maid) {
        List<ItemStack> items = new ArrayList<>(MaidInventory.usableStacks(maid));
        items.add(maid.getMainHandItem());           // usableStacks 已含双手，这里是幂等兜底
        items.add(maid.getOffhandItem());

        LivingEntity target = maid.getTarget();
        boolean hasTarget = target != null && target.isAlive();
        double distTarget = hasTarget ? maid.distanceTo(target) : -1;

        LivingEntity owner = maid.getOwner();
        double ownerHp = owner == null ? -1 : pct(owner);
        double ownerDist = owner == null ? -1 : maid.distanceTo(owner);

        // 周围敌人（含目标 —— 目标未必实现 Enemy，例如玩家）
        AABB box = maid.getBoundingBox().inflate(ENEMY_RADIUS);
        int enemies = 0;
        double nearest = -1;
        for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == maid || e == owner || !e.isAlive()) {
                continue;
            }
            boolean hostile = e instanceof Enemy || e == target;
            if (!hostile || e.isAlliedTo(maid)) {
                continue;
            }
            enemies++;
            double d = maid.distanceTo(e);
            if (nearest < 0 || d < nearest) {
                nearest = d;
            }
        }

        var bus = DirectiveHolder.of(maid);
        String inFlight = ActionExecutors.inFlightActionId(maid);

        return CombatScene.of(
                pct(maid),
                maid.isOnFire(),
                inFlight,
                gaitZh(maid),
                distTarget,
                itemId(maid.getMainHandItem()),
                itemId(maid.getOffhandItem()),
                MaidInventory.totalCount(maid),
                // ★★ 第二十六轮：**"有没有近战手段"按拥有算，不按手上算** ——
                //   原来这一项是 `anyMatch(items, meleeLike) || hasUsableBlade(maid)`，
                //   而 `hasUsableBlade` 只看**主手** ⇒ 刀在背包里时这一栏会变 false，
                //   与"她能做近战动作"（候选集里确实有刀技）自相矛盾
                //   （同一轮里 `flp_blade` 那处也犯了同样的错，被她当场读成"我没有拔刀剑"）。
                anyMatch(items, CombatSceneBuilder::meleeLike) || anyMatch(items,
                        CombatSceneBuilder::slashBladeLike),
                hasModItem("goety", () -> GoetyExecutors.hasAnyWand(maid)),
                hasModItem("irons_spellbooks", () -> IronsExecutors.hasAnySpell(maid)),
                anyGun(items),
                anyMatch(items, CombatSceneBuilder::rangedLike),
                anyMatch(items, OffhandStance::isShield),
                anyMatch(items, ExtinguishServo::isExtinguisher),
                spells(maid, bus),
                servantCount(maid),
                ownerHp, ownerDist,
                hasTarget, enemies, nearest,
                hasTarget ? pct(target) : -1,
                itemLines(maid),
                // ★★ 第十七轮修正口径：这里原来是 `总件数 - 不同种类数` = **重复数量**
                //   （64 个石头会算成 63 件"没列出来的"），而字段的语义是
                //   「**因为太长而没列出来的种类数**」（docs/12 的字段表 + skill.md 都这么写）。
                //   算错的后果很具体：模型以为"我看到的就是全部" ⇒ 不会去查 flp_items。
                Math.max(0, distinctItemCount(maid) - itemLines(maid).size()),
                hasTarget ? TargetRefs.typeId(target.getType()) : "",
                hasTarget ? target.getUUID().toString() : "",
                actionIds(),
                Math.max(0, actionCount() - actionIds().size()));
    }

    // ───────────────────────── 动作 id 清单（only_actions 的参数来源）─────────────────────────

    /** 动作 id 清单的上限（★ 它会进 prompt；清单上百条，不能全塞）。 */
    private static final int MAX_ACTION_LINES = 40;

    /**
     * ★★ 她现在**真正能做**的动作 id（第十六轮，委托方问「动作有 id 吗？」）。
     *
     * <p>——**有**：清单里每条动作都有自己的 id（`slashblade:slash_art`、`goety:cast_focus`…）。
     * 把这份清单喂给模型，它才填得出 `only_actions`（"接下来几秒只做这些动作"）。
     */
    private static List<String> actionIds() {
        try {
            var resolver = com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder.resolver();
            if (resolver == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (var a : resolver.actions()) {
                out.add(a.id());
                if (out.size() >= MAX_ACTION_LINES) {
                    break;
                }
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private static int actionCount() {
        try {
            var resolver = com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder.resolver();
            return resolver == null ? 0 : resolver.actions().size();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ───────────────────────── 物品清单（半命题指令的参数来源）─────────────────────────

    /** 清单上限（★ 有界：`items` 会进 prompt，不能让它随背包膨胀）。 */
    private static final int MAX_ITEM_LINES = 12;

    /**
     * ★★ 她身上带着什么（**去重后按"手上的优先"排序**，最多 {@value #MAX_ITEM_LINES} 条）。
     *
     * <p>委托方第十五轮问「llm 已经可以获得背包里可用物品列表了（可以吗？）」
     * —— 之前**不能**（只有 `has_*` 布尔）。现在能，但**必须有上限**：
     * 这是一段要发给模型的 JSON，把 36 格全列出来既费 token 又淹没有用信息。
     * ⇒ 超出部分不列，只报 {@code items_truncated} 的件数（模型据此知道"还有别的"）。
     */
    private static List<CombatScene.ItemLine> itemLines(EntityMaid maid) {
        Map<String, CombatScene.ItemLine> byId = new LinkedHashMap<>();
        for (PossessedItem it : MaidSnapshot.possessed(maid)) {
            CombatScene.ItemLine old = byId.get(it.itemId());
            int count = (old == null ? 0 : old.count()) + Math.max(1, it.count());
            // ★ "手上的优先"：先出现的槽位优先级更低（MAINHAND=0），所以先放入的不覆盖
            if (old == null) {
                byId.put(it.itemId(), new CombatScene.ItemLine(it.itemId(), count,
                        it.slot().name(), itemLabel(maid, it)));
            } else {
                byId.put(it.itemId(), new CombatScene.ItemLine(old.id(), count, old.where(),
                        old.name()));
            }
        }
        List<CombatScene.ItemLine> out = new ArrayList<>(byId.values());
        // ★★ 第十七轮（委托方：「不管让她使用什么，都用的火箭筒」）：
        //   以前这里**按槽位顺序**取前 12 条 ⇒ 背包一装满，**枪就被挤出清单**，
        //   模型看不到枪的 id ⇒ 它只能猜（而猜错 = only_item 静默回退 = 继续用火箭筒）。
        //   ⇒ 改成**战斗相关优先**（枪 > 法杖/法术书 > 拔刀剑 > 近战 > 盾 > 其它），
        //     同优先级保持"手上的优先"（稳定排序）。
        Map<String, Integer> prio = new HashMap<>();
        for (PossessedItem it : MaidSnapshot.possessed(maid)) {
            prio.putIfAbsent(it.itemId(), itemPriority(it));
        }
        out.sort(Comparator.comparingInt(l -> prio.getOrDefault(l.id(), 9)));
        return out.size() <= MAX_ITEM_LINES ? out : out.subList(0, MAX_ITEM_LINES);
    }

    /**
     * ★ 物品在"给她看的清单"里的优先级（越小越靠前）。
     *
     * <p>判据只用物品的**字符串侧**（{@code typeNames} / 能力 / 注册名）——
     * 与 {@code carriers.json} 的 {@code javaInstanceOf} 同口径，且不碰 MC 类型。
     * ★ 枪排第一是因为只有它会**因为看不到而被猜错**（"用哪把枪"这条指令的参数就是物品 id）；
     * 法杖/法术书同理（{@code ban_item goety:wand} 这类写法要照抄）。
     */
    private static int itemPriority(PossessedItem it) {
        java.util.Set<String> t = it.typeNames();
        if (t.contains("IGun") || t.contains("GunItem")) {
            return 0;                                        // 枪（★ 最容易被猜错）
        }
        if (t.contains("IWand") || t.contains("SpellBook") || t.contains("ISpellContainer")
                || t.contains("ScrollItem")
                || it.itemId().contains("spell_book") || it.itemId().contains("scroll")) {
            return 1;                                        // 法杖 / 法术书 / 卷轴
        }
        if (t.contains("ItemSlashBlade")) {
            return 2;                                        // 拔刀剑
        }
        if (it.hasCapability("maid:weapon") || t.contains("SwordItem") || t.contains("AxeItem")
                || t.contains("TridentItem") || t.contains("BowItem")
                || t.contains("CrossbowItem")) {
            return 3;                                        // 近战/远程武器
        }
        if (t.contains("ShieldItem")) {
            return 4;                                        // 盾
        }
        return 5;
    }

    /**
     * ★ 一行物品的"人读名"（第十七轮续）：`M249 机枪 [mg]`、`RPG-7 火箭筒 [rpg]`。
     *
     * <p>为什么要有它：模型得把玩家嘴里的「机枪」落到某个 id 上。枪型（`mg`/`rpg`…）
     * 与中文枪型名都进 {@code PossessedItem.aliases} ⇒ {@code only_item} 的参数
     * 写「机枪」或 `mg` 都能命中（委托方实测的「我让她只用机枪」就是这条）。
     */
    private static String itemLabel(PossessedItem it) {
        return itemLabel(null, it);
    }

    /**
     * ★★ 第二十五轮（委托方第 2 条）：**枪要多一句"弹药"** —— 指挥通道也得看得到
     * 「她还有没有子弹」（{@code conserve_ammo} / 换近战 这类判断全依赖它）。
     *
     * <p>★ 只对枪加（其它物品没有这个概念），且 {@code maid == null} 时退化成原来的行为
     * （自测里没有女仆上下文）。
     */
    private static String itemLabel(EntityMaid maid, PossessedItem it) {
        String name = it.displayName();
        if (name == null || name.isBlank()) {
            name = it.itemId();
        }
        String type = it.param("gunType");
        StringBuilder sb = new StringBuilder(type == null || type.isBlank()
                ? name : name + " [" + type + "]");
        if (maid != null && it.param("gunId") != null) {
            try {
                net.minecraft.world.item.ItemStack stack = stackOf(maid, it);
                String ammo = stack == null ? null
                        : com.touhoulittlemad.fightlikeplayer.compat.gun.AmmoInfo
                                .describe(maid, stack);
                if (ammo != null && !ammo.isBlank()) {
                    sb.append(" ｜ ").append(ammo);
                }
            } catch (RuntimeException | LinkageError e) {
                // 读不到弹药不影响场景的其余部分
            }
        }
        return sb.toString();
    }

    /** 按身份 id + 槽位把一件物品还原成原始 stack（与 {@code MaidSnapshot.carried} 同口径）。 */
    private static net.minecraft.world.item.ItemStack stackOf(EntityMaid maid, PossessedItem it) {
        MaidSnapshot.Carried carried = MaidSnapshot.carried(maid);
        for (int i = 0; i < carried.items().size(); i++) {
            PossessedItem other = carried.items().get(i);
            if (other.slot() == it.slot() && other.firstSlotIndex() == it.firstSlotIndex()
                    && other.itemId().equals(it.itemId())) {
                return i < carried.stacks().size() ? carried.stacks().get(i) : null;
            }
        }
        return null;
    }

    private static int distinctItemCount(EntityMaid maid) {
        Set<String> ids = new LinkedHashSet<>();
        for (PossessedItem it : MaidSnapshot.possessed(maid)) {
            ids.add(it.itemId());
        }
        return ids.size();
    }

    // ───────────────────────── 法术池 ─────────────────────────

    /**
     * ★ 她真正拥有的法术/聚晶（**不经过指令过滤**），并标出"哪几颗正被她自己的指令挡着"。
     */
    private static List<CombatScene.SpellOption> spells(EntityMaid maid, DirectiveBus bus) {
        List<CombatScene.SpellOption> out = new ArrayList<>();
        if (hasMod("goety")) {
            for (GoetyFocusOps.Focus f : GoetyFocusOps.available(maid, null)) {
                boolean blocked = !DirectiveFilter.focusAllowed(bus,
                        DirectiveFilter.SpellSystem.GOETY, f.category().name(), f.label());
                out.add(new CombatScene.SpellOption(f.label(), f.category().name(),
                        f.source().name(), blocked));
            }
        }
        if (hasMod("irons_spellbooks")) {
            for (IronsSpells.Option o : IronsSpells.options(maid, null)) {
                boolean blocked = !DirectiveFilter.focusAllowed(bus,
                        DirectiveFilter.SpellSystem.IRONS, o.intent().name(), o.label());
                out.add(new CombatScene.SpellOption(o.label(), o.intent().name(),
                        "SPELLBOOK", blocked));
            }
        }
        return out;
    }

    private static int servantCount(EntityMaid maid) {
        if (!hasMod("goety")) {
            return 0;
        }
        try {
            return GoetyServantOps.servantCount(maid);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static boolean hasMod(String mod) {
        return net.minecraftforge.fml.ModList.get().isLoaded(mod);
    }

    /** 调一个只在某模组存在时才合法的方法（模组缺席 ⇒ false，不抛异常）。 */
    private interface BoolCall {
        boolean get();
    }

    private static boolean hasModItem(String mod, BoolCall call) {
        if (!hasMod(mod)) {
            return false;
        }
        try {
            return call.get();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** ★ 纯谓词扫描：**只**看这些物品栈（不掺别的判据 —— 否则"有没有盾"会被拔刀剑带成 true）。 */
    private static boolean anyMatch(List<ItemStack> items, java.util.function.Predicate<ItemStack> p) {
        for (ItemStack s : items) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            try {
                if (p.test(s)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                // ★ 单个物品的判定失败不该毁掉整个场景
                FightLikePlayer.LOGGER.debug("[FLP] 场景判定物品失败：{}", e.toString());
            }
        }
        return false;
    }

    /**
     * ★ 它是不是拔刀剑 —— 判据只用物品的**字符串侧**（{@code typeNames}），
     * 与 {@code carriers.json} 的 {@code slashblade:blade}（{@code javaInstanceOf: ItemSlashBlade}）**同一口径**，
     * 且**与槽位无关**（在背包里也算她有）—— 见第二十六轮那条修法。
     */
    private static boolean slashBladeLike(net.minecraft.world.item.ItemStack stack) {
        var names = MaidSnapshot.typeNames(stack);
        return names.contains("ItemSlashBlade")
                || names.stream().anyMatch(n -> n.endsWith(".ItemSlashBlade"));
    }

    /** 拔刀剑走它自己的判据（它不是 {@code SwordItem}）。 */
    private static boolean hasUsableBlade(EntityMaid maid) {        if (!hasMod("slashblade")) {
            return false;
        }
        try {
            return com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors
                    .isUsableBlade(maid);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean meleeLike(ItemStack s) {
        return s.getItem() instanceof SwordItem
                || s.getItem() instanceof AxeItem
                || s.getItem() instanceof TridentItem;
    }

    /** 远程手段：弓/弩/三叉戟/箭矢（枪单独判，见 {@link #anyGun}）。 */
    private static boolean rangedLike(ItemStack s) {
        return s.getItem() instanceof BowItem
                || s.getItem() instanceof CrossbowItem
                || s.getItem() instanceof TridentItem
                || s.is(net.minecraft.world.item.Items.ARROW)
                || s.is(net.minecraft.world.item.Items.SPECTRAL_ARROW)
                || s.is(net.minecraft.world.item.Items.TIPPED_ARROW);
    }

    private static boolean anyGun(List<ItemStack> items) {
        if (!hasMod("tacz") && !com.github.tartaricacid.touhoulittlemaid.compat.gun.common
                .GunCommonUtil.isInstalled()) {
            return false;
        }
        for (ItemStack s : items) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            try {
                if (com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                        .isGun(s)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        }
        return false;
    }

    private static double pct(LivingEntity e) {
        float max = e.getMaxHealth();
        return max <= 0 ? 1.0 : e.getHealth() / (double) max;
    }

    private static String itemId(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "empty";
        }
        var id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id == null ? s.getItem().toString() : id.toString();
    }

    private static String gaitZh(EntityMaid maid) {
        try {
            var g = PlayerLikeCombat.currentGait(maid);
            return g == null ? "" : g.describe();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }
}
