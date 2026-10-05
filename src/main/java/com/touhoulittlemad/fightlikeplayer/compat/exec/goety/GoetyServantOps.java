package com.touhoulittlemad.fightlikeplayer.compat.exec.goety;

import com.Polarice3.Goety.api.entities.IOwned;
import com.Polarice3.Goety.api.entities.ally.IServant;
import com.Polarice3.Goety.common.items.magic.CommandHorn;
import com.Polarice3.Goety.common.items.magic.RecallFocus;
import com.Polarice3.Goety.init.ModSounds;
import com.Polarice3.Goety.utils.SEHelper;
import com.Polarice3.Goety.utils.WandUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Goety 仆从管理</b> —— 5 条操作的女仆侧实现（② 执行层）。
 *
 * <h2>★★ 为什么这一组能实现，而别的不能</h2>
 * 判据永远是同一条：<b>入口签名吃不吃 {@code Player}</b>。
 * 本类里<b>每一个入口都只吃 {@code LivingEntity} / {@code Entity}</b>：
 * <ul>
 *   <li>{@code IOwned#dismiss()}（{@code IOwned.java:377-381}）—— 处死仆从的等价物。
 *       ⚠️ 玩家侧那个 {@code IServant#tryKill(Player)} <b>全树 18 处声明无一收
 *       {@code LivingEntity}</b> ⇒ 只能走 {@code dismiss()}；</li>
 *   <li>{@code IServant} 的四个姿态 setter（{@code IServant.java:141-165}）—— 纯字段写入，零 Player；</li>
 *   <li>{@code RecallFocus#recall(LivingEntity, ItemStack)}（{@code RecallFocus.java:93}）；</li>
 *   <li>召回 = 复刻 {@code SummonSpell#teleportServants}（{@code SummonSpell.java:121-127}）的三行：
 *       判 {@code getTrueOwner() == owner} + {@code servant.moveTo(owner.position())}。</li>
 * </ul>
 *
 * <h2>⚠️ 两条"看上去能用、其实会静默走错"的路（已避坑）</h2>
 * <ol>
 *   <li><b>不要用「潜行施法」做召回</b>：{@code Spell#isShifting}（{@code Spell.java:71-73}）的
 *       <b>第二个条件</b>是"手上有杖" —— 手上没杖时 {@code isShifting==false}
 *       ⇒ {@code SummonSpell.commonResult:85} 走<b>召唤</b>分支 ⇒
 *       <b>想召回却召唤了一群（不可逆的语义反转）</b>。而且 {@code setShiftKeyDown} 是持久共享 flag，
 *       会污染我们自己对 {@code isCrouching()} 的读取。</li>
 *   <li><b>跨维度身份成立</b>：{@code EntityFinder.getLivingEntityByUuiD} 会遍历<b>所有维度</b>
 *       ⇒ {@code getTrueOwner() == maid} 对<b>别的维度</b>的仆从也可能为真
 *       ⇒ 每条遍历都必须自加 {@code e.level() == maid.level()} 门，
 *       否则会把下界的仆从"召回"到主世界坐标。</li>
 * </ol>
 *
 * <h2>★ 坚守白名单（{@code grounded}）：女仆侧只有"空名单"是官方语义</h2>
 * {@code SEHelper} 的 7 个 grounded 方法<b>全部要 {@code Player}</b>，
 * 且 {@code SEProvider} capability <b>只挂在 Player 上</b>、grounded 表<b>不落盘</b>
 * ⇒ 女仆侧不存在任何官方读取路径。而 Goety 自己对非 Player 所有者的定义就是
 * {@code isGrounded(LivingEntity, …) == false}（{@code SEHelper.java:735-740}）
 * ⇒ <b>"空名单"不是我们的取舍，是复刻官方行为</b>。若想让"主人保护谁、女仆也保护谁"，
 * 用 {@link #groundedOfMaster} 借主人的名单。
 *
 * <p>全部结论的逐条出处见 {@code _scratch/goety-servant-api.md}（取证报告）。
 */
public final class GoetyServantOps {

    private GoetyServantOps() {
    }

    /** 两段确认窗口（{@code Summoned.java:513}：{@code warnKill} 把 killChance 置 60）。 */
    private static final int KILL_CONFIRM_TICKS = 60;

    /** 仆从搜索半径（对齐 {@code DarkWand.java:247,261} 的 {@code distanceTo(player) <= 64}）。 */
    private static final double SELECT_RADIUS = 64.0;

    // ───────────────────────── 白名单（可注入） ─────────────────────────

    /** 免遣散/免指挥判定。见类注释：女仆侧"空名单"就是官方语义。 */
    @FunctionalInterface
    public interface GroundedCheck {
        boolean isGrounded(LivingEntity servant);
    }

    /** 方案 0（默认）：与 Goety 对非 Player 所有者的既有行为一致。 */
    public static final GroundedCheck NO_WHITELIST = servant -> false;

    /**
     * 方案 1：借<b>主人（玩家）</b>的坚守白名单 —— "主人保护谁，女仆就保护谁"。
     * <p>女仆没有主人、或主人不是玩家 ⇒ 退回 {@link #NO_WHITELIST}。
     */
    public static GroundedCheck groundedOfMaster(EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        if (owner instanceof Player master) {
            List<LivingEntity> entities = SEHelper.getGroundedEntities(master);
            List<net.minecraft.world.entity.EntityType<?>> types = SEHelper.getGroundedEntityTypes(master);
            return servant -> entities.contains(servant) || types.contains(servant.getType());
        }
        return NO_WHITELIST;
    }

    // ───────────────────────── 枚举与目标选择 ─────────────────────────

    /**
     * 女仆自己的仆从（含临时）。
     * <p>★ 距离门 + {@code getTrueOwner()} 身份门；**不含维度门**（调用方若做位移必须自己加）。
     */
    public static List<LivingEntity> myServants(EntityMaid maid) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class,
                maid.getBoundingBox().inflate(SELECT_RADIUS))) {
            if (!e.isAlive()) {
                continue;
            }
            if (e instanceof IServant s && s.getTrueOwner() == maid) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 女仆当前拥有几个仆从 —— ★ <b>这一条同时关掉了 W23 的一半</b>
     * （{@code MaidSnapshot} 里"仆从数暂记 0"的注释说"统计需要 Goety 的 IOwned"，现在有了）。
     */
    public static int servantCount(EntityMaid maid) {
        return myServants(maid).size();
    }

    /**
     * ★★ <b>这一只（任意实体）是不是她的 Goety 仆从</b>（第二十二轮）。
     *
     * <p>用途：目标判据（{@code MaidAllies}）—— 她**永远不该**把自己的仆从当敌人。
     * ★ 与 {@link #myServants(EntityMaid)} 同一个身份门 {@code IServant#getTrueOwner() == maid}
     * （口径只有一处）；★ 刻意**不加距离门**：这个问题的语义是"它是不是她的"，
     * 与"她在不在附近"无关（距离门是"处理仆从"那类操作的约束）。
     */
    public static boolean isMyServant(EntityMaid maid, net.minecraft.world.entity.Entity e) {
        if (maid == null || e == null || !available()) {
            return false;
        }
        try {
            return e instanceof IServant s && s.getTrueOwner() == maid;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 是否装了 Goety（第二十二轮补：本类此前**假设调用方已经把守**，
     * 而 {@link #isMyServant} 是从目标判据那条热路径进来的 ⇒ 把守收进本类，
     * 与 {@code IronsServantOps#available()} 对齐）。
     */
    public static boolean available() {
        return net.minecraftforge.fml.ModList.get().isLoaded("goety");
    }

    /**
     * 挑一个要处死的仆从（**确定性**排序，避免每 tick 换目标）。
     *
     * <p>排序键（依次）：① 已在确认窗口里的优先（否则"连点三次杀三只"）；
     * ② 非白名单；③ 排除使魔（养成单位，误杀成本高）；④ 非警戒姿态优先；
     * ⑤ 血量升序；⑥ 距离升序；⑦ UUID 字典序兜底。
     */
    public static LivingEntity pickServantToExecute(EntityMaid maid, GroundedCheck grounded) {
        return myServants(maid).stream()
                .filter(e -> !(((IOwned) e).isFamiliar()))
                .min(Comparator
                        .comparingInt((LivingEntity e) -> ((IServant) e).getKillChance() > 0 ? 0 : 1)
                        .thenComparingInt(e -> grounded.isGrounded(e) ? 1 : 0)
                        .thenComparingInt(e -> ((IServant) e).isGuardingArea() ? 1 : 0)
                        .thenComparingDouble(LivingEntity::getHealth)
                        .thenComparingDouble(e -> e.distanceToSqr(maid))
                        .thenComparing(e -> e.getUUID().toString()))
                .orElse(null);
    }

    // ───────────────────────── 1. 处死指定的一个仆从 ─────────────────────────

    /**
     * 两段确认地处死一个仆从（复刻玩家侧 {@code warnKill → tryKill} 的语义）。
     *
     * <p>第一次调用只<b>警告</b>（{@code setKillChance(60)}），60 tick 内再调用一次才真死
     * —— 这正是"避免不可逆误杀"的设计，我们保留它（不越权一次杀）。
     *
     * @return 是否<b>做了事</b>（警告或处死都算）—— 供 {@code ActionExecutors} 回报成败
     */
    public static boolean executeServant(EntityMaid maid, GroundedCheck grounded) {
        LivingEntity target = pickServantToExecute(maid, grounded);
        if (target == null) {
            return false;                       // 没有可下手的仆从 ⇒ 什么都没发生
        }
        IServant s = (IServant) target;
        if (s.getKillChance() <= 0) {
            s.setKillChance(KILL_CONFIRM_TICKS);        // 第一段：警告
            return true;
        }
        s.dismiss();                                    // 第二段：真死（IOwned.java:377-381）
        return true;
    }

    // ───────────────────────── 2. 处死所有临时仆从 ─────────────────────────

    /**
     * 处死<b>所有临时（限时）</b>仆从 —— 复刻 {@code CDismissServantsPacket.java:29-36} 的筛选。
     *
     * @return 实际处死了几个（0 ⇒ 什么都没发生）
     */
    public static int dismissTemporaryServants(EntityMaid maid, GroundedCheck grounded) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return 0;
        }
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof IOwned owned) || !(e instanceof LivingEntity le)) {
                continue;
            }
            if (owned.getTrueOwner() != maid || !owned.isLimitedLife()) {
                continue;                       // ★ 只清理【临时】仆从
            }
            if (grounded.isGrounded(le)) {
                continue;                       // 白名单里的不碰
            }
            owned.dismiss();
            e.playSound(ModSounds.ROAR_SPELL.get(), 0.5F, 2.0F);
            n++;
        }
        return n;
    }

    /**
     * ★★ <b>处死她名下【全部】仆从</b>（第十七轮，委托方第 6 条：「当前的处死所有仆从指令无法运作」）。
     *
     * <h2>为什么原来"无法运作"（复核结论）</h2>
     * 指令原来调的是 {@link #dismissTemporaryServants}，而它里面有一句
     * <pre>
     *   if (owned.getTrueOwner() != maid || !owned.isLimitedLife()) continue;   // ★ 只清"限时"的
     * </pre>
     * ⇒ <b>她的常驻仆从一个都不会被碰</b>（玩家的绑定键叫「消灭**临时**仆从」，那是玩家侧语义；
     * 而委托方要的是一条"处死所有仆从"的<b>指令</b>）⇒ 观感就是"这条指令没用"。
     *
     * <p>⇒ 本方法<b>不看是否限时</b>：凡 {@code getTrueOwner() == maid} 的一律 {@code dismiss()}。
     * ★ 只由**上层指令**调用（{@code dismiss_servants} 瞬间指令）——
     * 那条动作已经改成 {@code role=directive}（永不进候选），她不会自己触发。
     *
     * @param grounded 免遣散名单（{@link #NO_WHITELIST} = 一个都不免）
     * @return 实际处死了几只（0 ⇒ 她名下本来就没有仆从）
     */
    public static int dismissAllServants(EntityMaid maid, GroundedCheck grounded) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return 0;
        }
        int n = 0;
        int failed = 0;
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof IOwned owned) || !(e instanceof LivingEntity le)) {
                continue;
            }
            if (owned.getTrueOwner() != maid) {
                continue;                       // ★ 不是她的仆从
            }
            if (grounded != null && grounded.isGrounded(le)) {
                continue;                       // 白名单里的不碰
            }
            // ★★ 第十七轮续（委托方：「实际清理仆从的功能完全不起效」）：
            //   原来这里直接 `owned.dismiss()` —— **第一只抛异常就整批中断**
            //   （异常冒到 applyInstant 的 catch，观感就是"这条指令什么也没做"）。
            //   ⇒ 逐只 try/catch：坏的跳过、好的照清，并把失败数报出去。
            try {
                owned.dismiss();
                e.playSound(ModSounds.ROAR_SPELL.get(), 0.5F, 2.0F);
                n++;
            } catch (RuntimeException ex) {
                failed++;
                FightLikePlayer.LOGGER.info("[FLP] 处死仆从失败（跳过这一只）：{} —— {}",
                        e.getName().getString(), ex.toString());
            }
        }
        if (failed > 0) {
            FightLikePlayer.LOGGER.info("[FLP] 处死仆从：成功 {} 只，失败 {} 只（已逐只跳过）", n, failed);
        }
        return n;
    }

    // ───────────────────────── 3. 召回仆从到身边 ─────────────────────────

    /**
     * 把自己所有仆从传送到自己身边 —— <b>直调效果</b>，不经过法术路径（见类注释的避坑 1）。
     *
     * <p>等价于 {@code SummonSpell#teleportServants}（{@code SummonSpell.java:121-127}）的三行：
     * 判归属 + {@code moveTo}。★ 维度门是<b>必须的</b>（见类注释的避坑 2）。
     *
     * @return 实际召回了几个（0 ⇒ 什么都没发生）
     */
    public static int recallServants(EntityMaid maid) {
        Vec3 here = maid.position();
        int n = 0;
        for (LivingEntity servant : myServants(maid)) {
            if (servant.level() != maid.level()) {
                continue;                       // ★ 维度门：别把别的维度的仆从"召回"过来
            }
            if (servant == maid) {
                continue;
            }
            servant.moveTo(here);
            n++;
        }
        return n;
    }

    // ───────────────────────── 4. 设定仆从姿态 ─────────────────────────

    /** 仆从姿态（与清单 family 的 {@code stance} 取值域对应：WANDER/STAYING/FOLLOW/GUARD/NONE）。 */
    public enum Stance {
        WANDER, STAY, GUARD, FOLLOW
    }

    /**
     * ★ <b>读命令呼号（Command Horn）当前选中的模式</b>，并把它广播给女仆自己的仆从
     * —— 这正是玩家按号角时的行为（号角广播的是<b>它当前选的模式</b>，模式存在物品 NBT 的
     * {@code "Mode"} 键里，{@code CommandHorn.java:36,88-90}）。
     *
     * <p>广播门（复刻 {@code CommandHorn.java:157-160}）：
     * {@code !isCommanded()} 且 {@code canUpdateMove()} 且 非白名单。
     *
     * @param horn 命令呼号物品栈（主手）
     * @return 实际改了几个仆从的姿态（0 ⇒ 什么都没发生）
     */
    public static int applyHornMode(EntityMaid maid, ItemStack horn, GroundedCheck grounded) {
        if (horn.isEmpty() || !(horn.getItem() instanceof CommandHorn)) {
            return 0;
        }
        Stance stance = stanceOf(CommandHorn.getMode(horn));
        if (stance == null) {
            FightLikePlayer.LOGGER.debug("[FLP] 命令呼号模式为 NONE/未知 ⇒ 不改姿态：{}",
                    CommandHorn.getMode(horn));
            return 0;
        }
        double range = CommandHorn.RANGE;
        int n = 0;
        for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class,
                maid.getBoundingBox().inflate(range))) {
            if (e instanceof IServant s && s.getTrueOwner() == maid
                    && !s.isCommanded() && s.canUpdateMove() && !grounded.isGrounded(e)) {
                if (setStance(s, stance)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 把命令呼号的模式字符串映射到姿态；{@code NONE}/未知 ⇒ {@code null}。 */
    private static Stance stanceOf(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case "wander" -> Stance.WANDER;
            case "stand_by" -> Stance.STAY;
            case "guard" -> Stance.GUARD;
            case "follow" -> Stance.FOLLOW;
            default -> null;
        };
    }

    /** 直接设置一个仆从的姿态（四个 setter 全是纯字段写入，零副作用）。 */
    public static boolean setStance(IServant s, Stance st) {
        return switch (st) {
            case WANDER -> {
                if (!s.canWander()) {
                    yield false;
                }
                s.setWandering();
                yield true;
            }
            case STAY -> {
                if (!s.canStay()) {
                    yield false;
                }
                s.setStaying();
                yield true;
            }
            case GUARD -> {
                if (!s.canGuardArea()) {
                    yield false;
                }
                s.setGuarding();
                yield true;
            }
            case FOLLOW -> {
                if (!s.canFollow()) {
                    yield false;
                }
                s.setFollowing();
                yield true;
            }
        };
    }

    // ───────────────────────── 5. 回溯到已存坐标 ─────────────────────────

    /**
     * ★ 女仆【拥有的】任何一件回溯聚晶里是否存过坐标 —— 用于喂 {@code goety:has_recall} 事实。
     *
     * <p>为什么要有它：{@code recall_to_saved_coords} 的前置条件在<b>解析期</b>判定，
     * 若这里判不出来就会 fail-closed 丢掉整条动作；而如果干脆不写这条前置，
     * 就会变成「每次都被选中、每次都做不出来」⇒ 永久占住决策
     * （这正是 {@code recall_servants} 实测 20 次 UNAVAILABLE 的同型故障）。
     */
    public static boolean hasUsableRecallFocus(EntityMaid maid) {
        var inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty()) {
                try {
                    if (RecallFocus.hasRecall(s)) {
                        return true;
                    }
                } catch (RuntimeException ignore) {
                    // 不是回溯聚晶（或 NBT 异常）⇒ 跳过这一格
                }
            }
        }
        return false;
    }

    /**
     * 把一个仆从回溯到<b>已存坐标</b>（坐标存在手上的回溯聚晶里）。
     *
     * <p>⚠️ 前置：{@code RecallFocus#hasRecall(stack)} 必须为真，否则 {@code recall} 会
     * <b>静默返回 false</b>（{@code RecallFocus.java:94}）—— 所以我们先自己判一次，
     * 好让"做不了"变成可见的事实。
     *
     * @return 是否真的回溯了
     */
    public static boolean recallServantToSavedCoords(EntityMaid maid, ItemStack focusStack) {
        if (!(maid.level() instanceof ServerLevel)) {
            return false;
        }
        if (focusStack == null || focusStack.isEmpty() || !RecallFocus.hasRecall(focusStack)) {
            return false;                       // 没有已存坐标 ⇒ 什么都没发生
        }
        LivingEntity servant = pickServantToRecall(maid);
        if (servant == null) {
            return false;
        }
        return RecallFocus.recall(servant, focusStack);
    }

    /** 挑一个要回溯的仆从（确定性：距离升序 → 血量升序 → UUID）。 */
    private static LivingEntity pickServantToRecall(EntityMaid maid) {
        return myServants(maid).stream()
                .filter(e -> e.level() == maid.level())
                .min(Comparator
                        .comparingDouble((LivingEntity e) -> e.distanceToSqr(maid))
                        .thenComparingDouble(LivingEntity::getHealth)
                        .thenComparing(e -> e.getUUID().toString()))
                .orElse(null);
    }

    /** 女仆手上的回溯聚晶（主手/副手，{@code WandUtil#findFocus} 无 Player 形参）。 */
    public static ItemStack focusInHand(EntityMaid maid) {
        ItemStack st = WandUtil.findFocus(maid);
        return st == null ? ItemStack.EMPTY : st;
    }
}
