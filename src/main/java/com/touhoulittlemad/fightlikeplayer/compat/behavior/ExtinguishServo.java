package com.touhoulittlemad.fightlikeplayer.compat.behavior;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityExtinguishingAgent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors;
import com.touhoulittlemad.fightlikeplayer.compat.maid.HandEquip;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ★★ <b>灭火器伺服</b> —— 委托方第十一轮点名的那一件
 * （「我发现我们要在副手上做的还有很多：比如<b>灭火器</b>」）。
 *
 * <h2>★ 先说一个与委托方假设不同的取证结论：灭火器在 TLM 里是<b>主手</b>物品</h2>
 * TLM 的 {@code MaidExtinguishingTask}（它由「灭火」工作任务的 brain 列表注册）里，
 * 全部三处判断读的都是 <b>{@code maid.getMainHandItem()}</b>：
 * <pre>
 *   if (owner.isOnFire() && isExtinguisher(mainHandItem))  → 生成灭火剂 + 掉耐久 + swing
 *   if (maid.isOnFire()  && isExtinguisher(mainHandItem))  → 同上
 *   附近 2 格的驯服动物着火且 isExtinguisher(mainHandItem) → 同上
 * </pre>
 * ⇒ 它<b>不是</b>"副手被动装备"，而是一件<b>临时插进来的工具</b>（灭火剂实体从主手位置生成）。
 * 所以本类按 TLM 的真实语义实现：<b>需要用的时候把灭火器换到主手、用完还回去</b>，
 * 而不是硬塞进副手（那样灭火剂会生成在错误的位置，而且会跟盾抢副手）。
 *
 * <h2>★ 三条与"主动动作"的边界（为什么它是伺服而不是动作）</h2>
 * <ol>
 *   <li><b>触发是外生事件</b>：她自己/主人/宠物<b>着火</b>—— 这与"该不该进攻"完全无关，
 *       是环境状态；做成动作就要给它打分，而"她在着火"根本不是可权衡的取舍。</li>
 *   <li><b>只在主循环空闲时借用手</b>：{@link #tick} 会先问
 *       {@code DecisionCycle.isCommitted()} 与 {@link ActionExecutors#isBusy}——
 *       <b>她正在挥刀/引导/打一梭子时绝不抢手</b>（那会把别的动作打断）。</li>
 *   <li><b>冷却用 TLM 自己的数</b>：它的 {@code MAX_DELAY_TIME = 12}（每 12 tick 才检查一次）
 *       ⇒ 我们照抄这个节奏，不会每 tick 喷一次。</li>
 * </ol>
 *
 * <p>★ 与委托方的"副手"设想的关系：<b>副手姿态伺服</b>（{@link OffhandStance}，盾）
 * 与<b>主手借用伺服</b>（本类，灭火器）是同一族机制的两个例子 ——
 * 都是"由环境状态驱动的、不参与评分的持续/反应行为"。
 * 后续若发现别的被动装备（例如伞），按同样的形状再加一个即可。
 */
public final class ExtinguishServo {

    private ExtinguishServo() {
    }

    /** TLM 自己的检查节奏（{@code MaidExtinguishingTask.MAX_DELAY_TIME = 12}）。 */
    private static final int CHECK_INTERVAL = 12;

    /** 借用主手的时长上限：超过就把东西换回去，免得灭火器一直占着她的手。 */
    private static final int HOLD_TICKS = 40;

    private static final class State {
        /** 上次检查时刻。 */
        long lastCheck = Long.MIN_VALUE;
        /** 我们主动换过灭火器 ⇒ 记住这一格，用完放回去。 */
        int borrowedFromSlot = -1;
        /** 借用开始时刻（超时归还）。 */
        long borrowedAt;
        /** 上次换上去的灭火器 ItemStack（归还时用它换回原位）。 */
        ItemStack borrowedStack = ItemStack.EMPTY;
    }

    private static final Map<UUID, State> STATES = new java.util.WeakHashMap<>();

    private static State stateOf(EntityMaid maid) {
        return STATES.computeIfAbsent(maid.getUUID(), k -> new State());
    }

    /**
     * 每 tick 调一次（由 {@code PlayerLikeCombat} 在循环之后调用）。
     *
     * @param mainLoopBusy 主循环是否正在忙（承诺中或在途）—— 忙就什么都不做
     * @return 本 tick 是否用了灭火器
     */
    public static boolean tick(EntityMaid maid, boolean mainLoopBusy, long gameTime) {
        State st = stateOf(maid);
        try {
            // ── 归还（超时或不再需要）──
            if (st.borrowedFromSlot >= 0 && gameTime - st.borrowedAt >= HOLD_TICKS) {
                giveBack(maid, st);
            }
            if (gameTime - st.lastCheck < CHECK_INTERVAL) {
                return false;
            }
            st.lastCheck = gameTime;

            if (!(maid.level() instanceof ServerLevel server)) {
                return false;
            }
            // ★ 她正在忙（挥刀/引导/打枪）⇒ 绝不抢手
            if (mainLoopBusy) {
                return false;
            }
            // ★★ 第十六轮修 bug：指令说"不许换手 / 只用某件物品"时，这个伺服也**不许**把灭火器
            //   借到主手上 —— 否则"我让女仆只用幻影剑，她却掏出灭火器"这种事还会发生
            //   （这些独立伺服此前完全绕过候选集 ⇒ 指令管不到）。
            var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(maid);
            if (bus != null && bus.activeCount() > 0) {
                if (bus.has("no_item_switch")) {
                    return false;
                }
                String only = bus.textParam("only_item", "item");
                if (only != null && !only.isBlank() && !matchesExtinguisher(only)) {
                    return false;
                }
                String ban = bus.textParam("ban_item", "item");
                if (ban != null && !ban.isBlank() && matchesExtinguisher(ban)) {
                    return false;
                }
            }
            LivingEntity owner = maid.getOwner();
            // ── 找"着火的、需要我们救的目标"（与 TLM 的三处判断同序）──
            Vec3 at = null;
            if (maid.isOnFire()) {
                at = maid.position();
            } else if (owner instanceof Player p && p.isAlive() && p.isOnFire()
                    && maid.closerThan(p, 8)) {
                at = p.position();
            } else {
                List<TamableAnimal> burning = server.getEntitiesOfClass(TamableAnimal.class,
                        maid.getBoundingBox().inflate(2, 1, 2), Entity::isOnFire);
                if (!burning.isEmpty()) {
                    at = burning.get(0).position();
                }
            }
            if (at == null) {
                return false;
            }
            // ── 借灭火器到主手 ──
            ItemStack main = maid.getMainHandItem();
            if (!isExtinguisher(main)) {
                if (!borrow(maid, st)) {
                    return false;              // 她身上没有灭火器
                }
            }
            // ── 照 TLM 的配方做事：生成灭火剂 + 掉耐久 + swing ──
            server.addFreshEntity(new EntityExtinguishingAgent(server, at));
            maid.getMainHandItem().hurtAndBreak(1, maid,
                    m -> m.broadcastBreakEvent(InteractionHand.MAIN_HAND));
            maid.swing(InteractionHand.MAIN_HAND);
            FightLikePlayer.LOGGER.debug("[FLP] ★ 灭火器伺服：扑灭 {} 附近的火",
                    at.equals(maid.position()) ? "自己" : "同伴/主人");
            return true;
        } catch (Throwable t) {
            // ★ 附属类缺失 / 实体类型改动都绝不能影响 tick
            FightLikePlayer.LOGGER.debug("[FLP] 灭火器伺服异常（已忽略）：{}", t.toString());
            return false;
        }
    }

    /**
     * ★ 这个查询串指的是**灭火器**吗（判定用"她那件灭火器"的身份，而不是硬编码 id）。
     */
    private static boolean matchesExtinguisher(String query) {
        try {
            var ref = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                            InitItems.EXTINGUISHER.get()).toString(),
                    java.util.Set.of(), java.util.Set.of("Item"), java.util.Set.of());
            return ref.matches(query);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 把灭火器从背包换到主手，并记住位置。 */
    private static boolean borrow(EntityMaid maid, State st) {
        ItemStack before = maid.getMainHandItem();
        boolean ok = HandEquip.equipFromInventory(maid, InteractionHand.MAIN_HAND,
                ExtinguishServo::isExtinguisher);
        if (!ok) {
            return false;
        }
        st.borrowedFromSlot = 0;               // HandEquip 已把原物放回她背包，归还时只需放回自己的
        st.borrowedAt = maid.level().getGameTime();
        st.borrowedStack = before;             // 手上原来的东西（归还时换回来）
        return true;
    }

    /**
     * 归还：把灭火器收回背包、把手上的原物换回来。
     *
     * <p>★ 为什么归还这一步不能省：委托方的框架里"手上拿的是什么"决定候选集
     * ⇒ 灭火器一直占着主手，她就"拿灭火器打仗"（近战只有附伤，没有武器倍率）。
     */
    private static void giveBack(EntityMaid maid, State st) {
        try {
            ItemStack now = maid.getMainHandItem();
            if (isExtinguisher(now)) {
                // 灭火器放回背包（找到一个空位；找不到就留在手上，下次再试）
                var inv = maid.getAvailableInv(true);
                boolean stored = false;
                for (int i = 0; i < inv.getSlots() && !stored; i++) {
                    if (inv.getStackInSlot(i).isEmpty()) {
                        inv.insertItem(i, now, false);
                        maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        stored = true;
                    }
                }
                if (stored && !st.borrowedStack.isEmpty()) {
                    maid.setItemInHand(InteractionHand.MAIN_HAND, st.borrowedStack);
                }
            }
        } catch (RuntimeException ignore) {
            // 归还失败不影响别的（下一轮超时还会再试）
        } finally {
            st.borrowedFromSlot = -1;
            st.borrowedStack = ItemStack.EMPTY;
        }
    }

    /**
     * 是不是灭火器（判据与 {@code carriers.json} 的 {@code maid:extinguisher} 同口径）。
     * <p>★ 第 14 轮从 {@code private} 放开：指挥 LLM 的战斗场景要用它
     * （{@code equipment.has_extinguisher}）—— 放开而不是复制一份（口径唯一）。
     */
    public static boolean isExtinguisher(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == InitItems.EXTINGUISHER.get();
    }

    /** 脱战/卸载时清状态。 */
    public static void forget(UUID maidId) {
        STATES.remove(maidId);
    }

    /** 诊断。 */
    public static String describe(EntityMaid maid) {
        State st = STATES.get(maid.getUUID());
        boolean wearing = isExtinguisher(maid.getMainHandItem());
        return wearing ? "灭火器在主手" + (st != null && st.borrowedFromSlot >= 0 ? "（借来的）" : "")
                : "未使用灭火器";
    }
}
