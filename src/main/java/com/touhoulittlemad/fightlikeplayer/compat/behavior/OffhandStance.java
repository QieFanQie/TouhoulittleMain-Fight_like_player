package com.touhoulittlemad.fightlikeplayer.compat.behavior;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;

/**
 * ★★ <b>副手姿态伺服（stance servo）</b> —— 委托方第十一轮的设计要求。
 *
 * <h2>委托方的原话（这就是本类存在的理由）</h2>
 * <blockquote>
 * 「我们做的基本上都是<b>主动技能</b>，但是盾是<b>被动迎击</b>：
 * 我们可以独特化副循环：比如「举盾」动作实际上是<b>「确认副手上是否是盾并把盾放在副手上」</b>。」
 * </blockquote>
 *
 * <h2>★★ 为什么"举盾"不该是一个被打分的动作</h2>
 * <ol>
 *   <li><b>它不是一次选择，而是一种状态。</b> 动作层的语义是"这一步做什么"（一次性、有承诺、
 *       做完扣代价）；而"举着盾"没有"做完"的时刻 —— 它是<b>只要该举就一直举着</b>。
 *       把它当成动作，就会出现「举 20 tick → 收手 → 再举」这种周期性破绽
 *       （委托方看到的正是这个：{@code maid_native:shield_block} 是 CHANNEL 20 tick）。</li>
 *   <li><b>权威实现已经存在，只是没被挂上。</b> TLM 的 {@code MaidUseShieldTask} 就是"被动迎击"：
 *       {@code canUseShield() && 目标在 8 格内} ⇒ {@code startUsingItem(OFF_HAND)}，
 *       每 tick 重判，条件不成立就 {@code stopUsingItem()}。
 *       ★ 但它由 TLM 的 <b>{@code TaskAttack}</b> 注册进 brain（{@code TaskAttack.java:58/74}），
 *       而女仆现在跑的是<b>我们的任务</b> ⇒ <b>它永远不会运行</b>
 *       ⇒ 「盾在副手上，却从来不举」。<b>这不是缺机制，是缺接线。</b></li>
 * </ol>
 *
 * <h2>★ 本类做的三件事（与"动作"完全分开）</h2>
 * <ol>
 *   <li><b>把盾放到副手</b>（委托方原话里的那一半）：她的物品栏里若有盾而副手是空的，
 *       就换上去 —— 换手是"确认载体"的一部分，不是一次决策；</li>
 *   <li><b>维持姿态</b>：条件满足就一直举着（{@code OFF_HAND} 使用中），带<b>迟滞</b>
 *       （8 格内开始、12 格外才放）⇒ 不会在临界距离上反复抬手收手；</li>
 *   <li><b>条件不满足就放下</b>：目标没了/太远/盾不在了 ⇒ 收手（与 TLM 的 {@code canStillUse} 同义）。</li>
 * </ol>
 *
 * <h2>★ 为什么它是"伺服"而不是"决策"（与步法同构）</h2>
 * 项目里已经有这个先例：<b>走位</b>不做成动作，而是<b>并发的步法伺服</b>
 * （见 {@code decision/GaitSelector}）—— 因为"还差 3 格"这件事是<b>持续意图</b>，不是一次性动作。
 * <b>举盾与它完全同构</b>：它是"威胁在附近"这个持续条件的函数。
 * ⇒ 因此它有自己的小状态机，<b>不占承诺、不扣弹簧代价、不进候选集</b>。
 *
 * <h2>⚠️ 与主动动作的边界</h2>
 * 它<b>只碰副手</b>（盾 / 灭火器这类"迎击与救火"的被动装备），绝不碰主手
 * —— 主手归主循环。这条边界保证"伺服"不会与"决策"抢同一件东西。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.8（双循环）</a>
 */
public final class OffhandStance {

    private OffhandStance() {
    }

    /**
     * 每女仆的姿态状态。
     *
     * <p>用 {@link java.util.WeakHashMap}（键 = UUID）⇒ 女仆卸载后可回收。
     */
    private static final class State {
        /** 现在是不是举着盾（我们发起的 {@code startUsingItem} 还没收）。 */
        boolean shieldUp;
    }

    private static final Map<UUID, State> STATES = new java.util.WeakHashMap<>();

    /** 开始举盾的距离（格）—— 取自 TLM {@code MaidUseShieldTask.CHECK_RANGE = 8}。 */
    private static final double ENGAGE_RANGE = 8.0;

    /** 放下的距离（格）—— 刻意比开始距离大 ⇒ 迟滞，避免临界抖动。 */
    private static final double RELEASE_RANGE = 12.0;

    private static State stateOf(EntityMaid maid) {
        return STATES.computeIfAbsent(maid.getUUID(), k -> new State());
    }

    /**
     * 每 tick 调一次（由 {@code PlayerLikeCombat} 在**两个循环之外**调用）。
     *
     * @return 本 tick 是否正在举盾（诊断用）
     */
    public static boolean tick(EntityMaid maid) {
        State st = stateOf(maid);
        try {
            return tickShield(maid, st);
        } catch (RuntimeException e) {
            FightLikePlayer.LOGGER.debug("[FLP] 副手姿态伺服异常（已忽略）：{}", e.toString());
            return st.shieldUp;
        }
    }

    private static boolean tickShield(EntityMaid maid, State st) {
        LivingEntity target = maid.getTarget();
        boolean inCombat = target != null && target.isAlive();
        double dist = inCombat ? Math.sqrt(maid.distanceToSqr(target)) : Double.MAX_VALUE;

        // ── ⓪a ★★ 指令优先（第十六轮修 bug）：她下过"不许换手 / 只用某件物品"时，
        //      这个伺服**也不许自己把盾换到副手上**。
        //      ★ 委托方实测：「only_item/only_actions 等多方限制下，还是会有自由使用道具的情况」
        //        —— 根因就是这些**独立伺服**（盾/灭火器）走的是自己那条换手路径，
        //        完全不经过候选集 ⇒ 指令管不到它们。
        //      ★ 例外：`stance_guard`（玩家/模型**明确要求**举盾）仍然放行 —— 那是显式命令。
        long now = maid.level().getGameTime();
        boolean forced = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .forcedGuard(maid, now);
        if (!forced && blocksOwnItemSwitch(maid)) {
            if (st.shieldUp) {
                release(maid, st);                 // 已经举着 ⇒ 放下（指令优先）
            }
            return false;
        }

        // ── ⓪ ★ 指令强制举盾（第十三轮）：`stance_guard` 窗口内无视距离条件 ──
        if (forced) {
            if (ensureShieldInOffhand(maid)) {
                startShield(maid, st, -1);
                return true;
            }
        }

        // ── ① 放下：条件不满足就收手（迟滞：用更大的 RELEASE_RANGE 判"该放下"）──
        if (!inCombat || dist > RELEASE_RANGE) {
            release(maid, st);
            return false;
        }

        // ── ② 确认副手是盾；不是就从物品栏换上来（= 委托方原话里的那一半）──
        if (!isShield(maid.getOffhandItem())) {
            if (!ensureShieldInOffhand(maid)) {
                release(maid, st);              // 她根本没有盾 ⇒ 不举
                return false;
            }
        }

        // ── ③ 够近了 ⇒ 举着（自愈式：掉了就补）──
        if (dist <= ENGAGE_RANGE) {
            startShield(maid, st, dist);
            return true;
        }
        // 8~12 格之间：保持当前状态（迟滞），不再主动举、也不放下
        return st.shieldUp;
    }

    /**
     * ★★ <b>指令是否禁止这个伺服自己换东西</b>（第十六轮，修"多方限制下还是自由使用道具"）。
     *
     * <p>判据（都在纯逻辑层能表达）：
     * <ul>
     *   <li>{@code no_item_switch} —— 明确说了"不换手"；</li>
     *   <li>{@code only_item(X)} —— 盾不是 X（那就不该把盾换上来）；</li>
     *   <li>{@code ban_item(X)} —— 盾就是 X。</li>
     * </ul>
     * ★ 这里只问"盾允许不允许"（这个伺服只管盾）；灭火器那条路自己问自己那件东西。
     */
    static boolean blocksOwnItemSwitch(EntityMaid maid) {
        var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(maid);
        if (bus == null || bus.activeCount() == 0) {
            return false;
        }
        if (bus.has("no_item_switch")) {
            return true;
        }
        // 盾的身份：用物品栏里那面盾来问（主手是什么与"要不要把盾换上来"无关）
        var shield = firstShield(maid);
        if (shield == null) {
            return false;                        // 她本来就没有盾 ⇒ 这个伺服什么也不会做
        }
        var ref = com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(shield);
        String only = bus.textParam("only_item", "item");
        if (only != null && !only.isBlank() && !ref.matches(only)) {
            return true;
        }
        String ban = bus.textParam("ban_item", "item");
        return ban != null && !ban.isBlank() && ref.matches(ban);
    }

    /** 她身上第一面盾（没有 ⇒ null）。★ 用**物品栈**找（PossessedItem 不带 ItemStack）。 */
    private static com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem firstShield(
            EntityMaid maid) {
        var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>(
                com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory.usableStacks(maid));
        items.add(maid.getOffhandItem());
        for (var stack : items) {
            if (isShield(stack)) {
                // ★ 只用它的"身份"（注册名/标签/类型名），不需要原栈
                return fakeRef(maid, stack);
            }
        }
        return null;
    }

    /** 把一个 ItemStack 包成 {@code PossessedItem}（只为拿它的身份字段）。 */
    private static com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem fakeRef(
            EntityMaid maid, net.minecraft.world.item.ItemStack stack) {
        return com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot.possessedOf(
                maid, com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.OFFHAND, stack, -1);
    }

    /** 举盾（幂等；日志只打一次）。{@code dist < 0} = 由指令强制。 */
    private static void startShield(EntityMaid maid, State st, double dist) {
        if (maid.isUsingItem() && maid.getUsedItemHand() == InteractionHand.OFF_HAND) {
            return;
        }
        try {
            maid.startUsingItem(InteractionHand.OFF_HAND);
            if (!st.shieldUp) {
                st.shieldUp = true;
                FightLikePlayer.LOGGER.debug("[FLP] ★ 副手姿态：举盾（{}）",
                        dist < 0 ? "指令强制"
                                : String.format(java.util.Locale.ROOT, "目标 %.1f 格", dist));
            }
        } catch (RuntimeException ignore) {
            // 举不起来（比如手上不是"可使用的"物品）⇒ 下一 tick 再试
        }
    }

    /**
     * ★ 指令 {@code stance_attack} 的落点：立刻收盾。
     * <p>只收"我们举着的"那一次（与 {@link #release} 同一判据），不会误伤别的模组的引导物品。
     */
    public static void forceRelease(EntityMaid maid) {
        release(maid, stateOf(maid));
    }
    /** 放下盾（只在确实是<b>我们</b>举着的时候收手）。 */
    private static void release(EntityMaid maid, State st) {
        if (!st.shieldUp) {
            return;
        }
        st.shieldUp = false;
        try {
            if (maid.isUsingItem() && maid.getUsedItemHand() == InteractionHand.OFF_HAND) {
                maid.stopUsingItem();
            }
        } catch (RuntimeException ignore) {
            // 收不掉也不致命
        }
    }

    /**
     * 这件东西是不是盾（与 {@code carriers.json} 的 {@code maid:shield} 同口径）。
     *
     * <p>★ 第 14 轮把它从 {@code private} 放开：指挥 LLM 的"战斗场景"要用它回答
     * "她到底有没有盾"（{@code equipment.has_shield}）。<b>放开而不是复制一份</b> ——
     * 复制出来的第二份判据迟早会和这份不一致（口径唯一）。
     */
    public static boolean isShield(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (stack.is(Items.SHIELD)
                || MaidSnapshot.typeNames(stack).contains("ShieldItem"));
    }

    /**
     * ★★ <b>把盾从她的物品栏换到副手</b>（委托方要求的那一半）。
     *
     * <h2>★ 为什么不直接用 TLM 的 {@code TaskEquipUtil}</h2>
     * 它的 {@code tryEquipFromBackpack} 只搜 {@code maid.getAvailableBackpackInv()}
     * —— 那是 TLM 的<b>小背包</b>，<b>不含女仆的 27 格物品栏</b>
     * ⇒ 委托方实测「goety 魔杖在物品栏时，女仆无法把它拿到主手（哪怕主手没有东西）」
     * 就是这个原因（法杖在物品栏里，而换手只在 TLM 背包里找）。
     * ⇒ 本项目自己实现一份<b>搜全量可用背包</b>的换手，主手/副手都用它。
     *
     * @return 副手现在是不是盾
     */
    private static boolean ensureShieldInOffhand(EntityMaid maid) {
        ItemStack off = maid.getOffhandItem();
        if (isShield(off)) {
            return true;
        }
        // ★ 副手已经被别的东西占着（不是盾）⇒ 不硬抢（可能是她正需要的另一件被动装备）
        if (!off.isEmpty()) {
            return false;
        }
        return com.touhoulittlemad.fightlikeplayer.compat.maid.HandEquip
                .equipFromInventory(maid, InteractionHand.OFF_HAND, OffhandStance::isShield);
    }

    /** 诊断：这个女仆的副手姿态状态。 */
    public static String describe(EntityMaid maid) {
        State st = STATES.get(maid.getUUID());
        ItemStack off = maid.getOffhandItem();
        return "副手=" + (off.isEmpty() ? "(空)" : MaidSnapshot.itemId(off))
                + (st != null && st.shieldUp ? "·举盾中" : "");
    }

    /** 脱战/卸载时清状态（避免"上一场的姿态"跟着她）。 */
    public static void forget(UUID maidId) {
        State st = STATES.remove(maidId);
        if (st != null) {
            st.shieldUp = false;
        }
    }

    /** 供自测/诊断：当前有多少女仆在举盾。 */
    public static int upCount() {
        int n = 0;
        for (State s : STATES.values()) {
            if (s.shieldUp) {
                n++;
            }
        }
        return n;
    }
}
