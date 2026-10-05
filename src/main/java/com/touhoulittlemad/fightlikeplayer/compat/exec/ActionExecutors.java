package com.touhoulittlemad.fightlikeplayer.compat.exec;

import com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.TaskEquipUtil;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.carrier.CarrierResolver;
import com.touhoulittlemad.fightlikeplayer.carrier.CarrierRule;
import com.touhoulittlemad.fightlikeplayer.carrier.SlotKind;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;
import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.Gait;
import com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy;
import com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * <b>动作执行器</b> —— 把"选中的动作"在服务端真正做出来（② 执行层）。
 *
 * <h2>设计原则</h2>
 * <ol>
 *   <li><b>只做已核实的调用</b>：每个执行器的 API 出处都写在注释里。
 *       <b>未核实的宁可不实现</b>（登记为 planned/blocked），也不写猜的调用 ——
 *       猜错的调用要么编译不过，要么<b>静默做错事</b>（更难查）。</li>
 *   <li><b>不做判断</b>：选哪个动作由决策层定；执行器只回答"怎么做"。
 *       ⚠️ 唯一例外是"当前状态下还做不做得了"（没目标、枪不在手上）——
 *       那是<b>执行失败</b>，必须回报，不能静默吞掉。</li>
 *   <li>★ <b>不覆写 {@code hasExtraAttack}/{@code doExtraAttack} 来做灭火器</b>：
 *       TLM 的 {@code doHurtTarget} 内部已会调用它们（{@code EntityMaid.java:941}），
 *       按 Q16 的决策灭火器只是一个<b>普通数据记录</b>，不做特殊处理。</li>
 * </ol>
 *
 * <h2>★★ 2026-09-30 的两处结构性修正（实测驱动）</h2>
 *
 * <h3>① 蓄力射击改成「执行器驱动的状态机」</h3>
 * 旧实现的放箭条件是"承诺 tick 到期"，而 {@code maid_native:bow_shot} 等
 * <b>清单里根本没有 duration ⇒ 承诺=0</b> ⇒ {@code pendingFinish} 从不被设置
 * ⇒ <b>弓拉满但永不发射</b>（S0 实测现象）。
 *
 * <p>现在改为：<b>执行器是"这一发打完没有"的权威</b>（与 docs/09 §5.3 的原设计一致）——
 * {@link #execute} 起手蓄力并登记状态，{@link #tickInFlight} 每 tick 检查
 * "蓄力够了没有"，够了就放箭并回报 {@link Progress#COMPLETED}；
 * 清单的 {@code duration} 只作<b>兜底</b>（防执行器漏报导致永久卡死）。
 *
 * <h3>② 走位从"动作"变成"步法"</h3>
 * 旧实现把 {@code charge}/{@code retreat} 做成原子动作，执行后从弹簧里扣减自己的
 * {@code MOBILITY} 向量 ⇒ <b>两个动作互相偿还，永久震荡</b>（"到处跑"）。
 * ⇒ 走位改由 {@link #gait} 执行，它<b>不被评分、不发布扣减</b>，
 * 并且是<b>唯一的导航写入者</b>（vanilla brain 里的接近行为已从任务中移除）。
 *
 * <h2>★★ 五种远程攻击同构</h2>
 * 弓 / 弩 / 三叉戟 / 投掷任意物品 / 弹幕 的发射流程完全同构：
 * <pre>
 *   起手：startUsingItem(...)      松手：stopUsingItem() + performRangedAttack(目标, 力度)
 * </pre>
 * 差别只在【蓄力时长】与【力度算法】。⇒ 一个执行器覆盖 5 个动作，只在 {@link ShotKind} 分叉。
 *
 * <table border="1">
 *   <tr><th>动作</th><th>出处</th><th>蓄力</th><th>力度</th></tr>
 *   <tr><td>弓</td><td>{@code MaidShootTargetTask.java:89-100}</td><td>20 − 5×快速射击</td><td>{@code BowItem.getPowerForTime(t)}</td></tr>
 *   <tr><td>投掷任意物品</td><td>{@code MaidShootTargetAnyItemTask.java:86-105}</td><td>{@code chargeDurationTick}（默认 20）</td><td>同上</td></tr>
 *   <tr><td>弹幕</td><td>{@code TaskDanmakuAttack.java:65}（<b>直接复用 MaidShootTargetTask</b>）</td><td>20</td><td>同上</td></tr>
 *   <tr><td>三叉戟</td><td>{@code MaidTridentTargetTask.java:90-92}</td><td><b>30</b>（★ 不是 {@code THROW_THRESHOLD_TIME}）</td><td>{@code 0}</td></tr>
 *   <tr><td>弩</td><td>{@code MaidCrossbowAttack.java:36-56}</td><td>{@code CrossbowItem.getChargeDuration}</td><td>{@code 1.0F}</td></tr>
 * </table>
 *
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §8 (M4)</a>
 */
public final class ActionExecutors {

    /** 执行结果 —— 让"做不出来"变成可见的事实，而不是静默。 */
    public enum Result {
        /** 已发起/已执行。 */
        OK,
        /** 条件不满足（没目标、枪不在手上…）⇒ 调用方应结束承诺并退还代价。 */
        UNAVAILABLE,
        /** 该动作没有执行器（尚未覆盖）。 */
        NO_EXECUTOR,
        /** 执行时抛异常（已捕获）。 */
        FAILED
    }

    /** ★ <b>正在执行的动作的进展</b> —— 由执行器回报（docs/09 §5.3 的"权威来源"）。 */
    public enum Progress {
        /** 没有登记在册的在途动作。 */
        NONE,
        /** ★ <b>动作真的做完了</b>（箭已射出 / 一梭子冷却结束）⇒ 调用方应 {@code notifyCompleted()}。 */
        COMPLETED
    }

    /** ★ 蓄力射击的种类 —— 决定"怎么松手"。 */
    public enum ShotKind {
        /** 弓 / 投掷 / 弹幕：{@code BowItem.getPowerForTime(t)}。 */
        BOW,
        /** 三叉戟：力度传 0。 */
        TRIDENT,
        /** 弩：需要 {@code setChargingCrossbow} 且力度固定 1.0F。 */
        CROSSBOW
    }

    /**
     * ★ <b>三叉戟的投掷阈值</b>：30 tick。
     *
     * <p>⚠️ <b>不是原版的 {@code TridentItem.THROW_THRESHOLD_TIME}（=10）</b> ——
     * TLM 自己的女仆任务用的是 <b>30</b>（{@code MaidTridentTargetTask.java:90}：
     * {@code if (ticksUsingItem >= 30 && inSafeArea)}），而该文件虽然 import 了
     * {@code TridentItem} 却<b>没有把那个常量用在这个判断上</b>。
     * ⇒ 记录在案：<b>以常量在真实判断点的用法为准，不看 import</b>（README 更正记录 #2 的同型陷阱）。
     */
    public static final int TRIDENT_THROW_TICKS = 30;

    // ───────────────────────── ★ 在途状态（每个女仆一份）─────────────────────────

    /** 在途动作的两个阶段。 */
    private enum Phase {
        /** 正在蓄力（弓/弩/三叉戟）。 */
        CHARGING,
        /** 冷却中（枪械）—— 冷却结束才算"这一发打完"。 */
        COOLDOWN,
        /**
         * ★★ <b>正在打一梭子</b>（枪械）—— 委托方第九轮要求。
         * <p>「冲锋枪、步枪这类枪械，我希望以打完一梭子为单位
         * （开始-若没满就换弹-打空弹夹-换弹-结束），现在是每次打两三发。」
         */
        MAGAZINE,
        /**
         * ★★ <b>正在引导法术</b>（goety 通道类）——
         * 每 tick 推进 {@code useSpell}，到点调 {@code stopSpell} 才算做完。
         */
        CHANNEL,
        /**
         * ★★ <b>前摇中</b>（铁魔法的 LONG / CONTINUOUS 法术）。
         *
         * <p>为什么需要：玩家侧 {@code attemptInitiateCast} 会先等
         * {@code getEffectiveCastTime(level, entity)} 个 tick，而我们（与 ISS 自己的 mob 路径一样）
         * 直接调 {@code onCast} —— 那是<b>效果</b>钩子 ⇒ 前摇被跳过
         * （委托方第十一轮提问「铁魔法是不是前摇、冷却都被取消了」⇒ <b>确实是</b>）。
         * ⇒ 我们自己补上这段前摇：等 {@code readyAt}，期间持续瞄准与转向。
         */
        CAST_WINDUP
    }

    /** 每个女仆的在途状态。 */
    private static final class InFlight {
        String actionId;
        Phase phase;
        ShotKind kind;
        /** COOLDOWN 阶段的到期时刻（游戏 tick）。 */
        long readyAt;
        // ── ★ MAGAZINE 阶段用（打一梭子）──
        /** 下一发最早在哪个 tick（由枪的射速/换弹时间决定）。 */
        long nextShotAt;
        /** 这一梭子已经打出去几发。 */
        int shotsFired;
        /** 起手时刻（兜底上限用）。 */
        long startedAt;
        /**
         * 连续遇到几次「长等待」—— 长等待 = 换弹/上膛/开镜/抽枪（射速回到 >8 tick）。
         * <p>★ 用途：**她身上没有备用弹药**时，TaCZ 每次都返回"换弹时间"，
         * 若不管它就会永远换下去 ⇒ 连续两次长等待而没有一发打出去 ⇒ 提前结束。
         */
        int longWaits;
        /** 上一次调用是否真的打出人一发（用来判"连续长等待"）。 */
        boolean lastCallWasSlow;
        /** ★ 通用载荷（CAST_WINDUP 用它带着"到点要放的那颗铁魔法法术"）。 */
        Object payload;
    }

    /**
     * ★ 用 {@link WeakHashMap}（键为 UUID）⇒ 女仆卸载后可回收，不会泄漏。
     * <p>⚠️ 服务端单线程访问（brain tick），因此无需并发容器。
     */
    private static final Map<UUID, InFlight> IN_FLIGHT = new WeakHashMap<>();

    private ActionExecutors() {
    }

    /**
     * 执行一个动作。
     *
     * <p>★★ <b>第一步永远是"把动作需要的东西换到手上"</b>（见 {@link #ensureCarrierInHand}）——
     * 因为候选集是<b>按女仆拥有的全部物品</b>（含背包）算出来的，而执行器只能操作主手。
     *
     * @param maid      女仆
     * @param action    选中的动作（只用它的 id）
     * @param gameTime  当前游戏 tick（枪械冷却要用；未知传 0）
     * @param resolver  载体解析器（换手判定要用；{@code null} = 跳过换手前置）
     */
    public static Result execute(EntityMaid maid, CandidateAction action, long gameTime,
                                 CarrierResolver resolver) {
        return execute(maid, action, gameTime, resolver, SlotKind.MAINHAND);
    }

    /**
     * 同上，但<b>显式说明是哪一个循环在调</b>。
     *
     * <p>★ 为什么要这个参数（2026-10-01 实测暴露的 bug）：
     * 副手循环的动作（举盾）其载体规则 {@code maid:shield} 的 {@code slots = [OFFHAND]}，
     * 而"换手前置"的判据是"规则的槽位含不含主手" ⇒ 于是<b>副手动作永远被判成"不该换手"</b>，
     * 换手前置直接返回 false ⇒ 举盾每次都是 {@code UNAVAILABLE}。
     * 实测日志：{@code maid_native:shield_block} 被选中 36 次、<b>成功 0 次</b>。
     *
     * @param loopSlot 调用方所在的循环槽位（主循环 {@code MAINHAND} / 副手循环 {@code OFFHAND}）
     */
    public static Result execute(EntityMaid maid, CandidateAction action, long gameTime,
                                 CarrierResolver resolver, SlotKind loopSlot) {
        return execute(maid, action, gameTime, resolver, loopSlot, null);
    }

    /**
     * 完整版：多带一个<b>当前需求向量</b>。
     *
     * <p>★ 为什么执行器需要它（2026-10-01）：<b>一个动作可能对应多个具体法术</b> ——
     * 法术书里有好几个法术、聚晶包里囤着好几颗聚晶。
     * 决策层选中的是"<b>施法</b>"这个动作，而"<b>放哪一个</b>"必须由同一个需求向量决定
     * （否则就会出现委托方观察到的"<b>永远只用法术书的第一个法术</b>"）。
     * ⇒ 把 {@code p} 传下来，执行器就能用<b>与决策层完全相同的判据</b>在具体法术里挑一个。
     *
     * @param need 当前弹簧点（需求向量）；{@code null} ⇒ 执行器退回"轮换/第一个"
     */
    public static Result execute(EntityMaid maid, CandidateAction action, long gameTime,
                                 CarrierResolver resolver, SlotKind loopSlot,
                                 com.touhoulittlemad.fightlikeplayer.decision.NeedVector need) {
        String id = action.id();
        try {
            // ── 0a. ★★ 先转向仇恨目标（第十二轮：委托方要求"释放动作时正对仇恨目标"）──
            //   放在**最前面**、对所有动作统一生效：近战/刀技/弓/枪/法术都需要朝向，
            //   而此前只有法术与枪自己调了一次（近战挥击方向、刀技的判定朝向都可能不对着目标）。
            //   ★ 它只改朝向（yRot/xRot/yHeadRot），不做任何别的事；没有目标时是空操作。
            AimHelper.faceTarget(maid);

            // ── 0b. ★★ 执行器前置：换手（"换武器不是动作，是执行器的前置步骤"）──
            if (!ensureCarrierInHand(maid, action, resolver, loopSlot)) {
                return Result.UNAVAILABLE;
            }
            ShotKind kind = shotKindOf(id);
            if (kind != null) {
                return chargeShot(maid, id, kind);
            }
            return switch (id) {
                // 近战（MaidMeleeAttack.java:35-36 —— TLM 自己就是这么调的）
                case "maid_native:melee_swing" -> melee(maid);

                // 举盾（MaidUseShieldTask.java:22,37）
                case "maid_native:shield_block" -> shield(maid);

                // ★ 枪械：GunCommonUtil 是 TLM 的**公开**抽象，同时覆盖 TaCZ 与卓越前线
                //   （GunShootTargetTask.java:68 就是这么调的）
                //   ⚠️ 不用 TacInnerCompat.performGunAttack —— 它是**包内可见**（static int，非 public）
                case "maid_native:gun_shot", "tacz:shoot" -> gun(maid, id, gameTime);

                // ★★ 拔刀剑·可做子集（M5，2026-10-01）—— 直调 AttackManager.doSlash（公开静态）
                //    ⚠️ 不吃输入状态的那几类；瞬步/踢跃/SSA/格挡 归入"不做"（见类注释）
                case "maid_native:slashblade_generic_slash" -> slashblade(maid, 0.0f, 1.0);
                case "slashblade:combo_a" -> slashblade(maid, -10.0f, 0.44);
                case "slashblade:combo_b" -> slashblade(maid, 0.0f, 1.0);
                case "slashblade:combo_c" -> slashblade(maid, -30.0f, 0.88);
                // ★ 2026-10-03：连击 B 的收招（此前**没有执行器** ⇒ 解析期就被丢，
                //   于是"combo_b → combo_b_finish"这条声明连段**永远不可能发生**）
                case "slashblade:combo_b_finish" -> slashblade(maid, 20.0f, 1.0);
                case "slashblade:combo_a_ex" -> slashblade(maid, 40.0f, 1.0);
                case "slashblade:rapid_slash" -> slashblade(maid, 0.0f, 0.6);
                case "slashblade:upperslash" -> slashblade(maid, -90.0f, 0.6);
                case "slashblade:rising_star" -> slashblade(maid, 90.0f, 0.6);
                case "slashblade:upperslash_jump" -> slashblade(maid, -45.0f, 0.6);
                case "slashblade:judgement_cut" -> slashblade(maid, 0.0f, 1.0);
                case "slashblade:aerial_rave_a" -> slashblade(maid, -20.0f, 0.28);
                case "slashblade:aerial_rave_b" -> slashblade(maid, 45.0f, 0.34);
                case "slashblade:aerial_cleave" -> slashblade(maid, 60.0f, 0.44);
                case "slashblade:slash_art" -> slashbladeArt(maid);
                // ★★ 第十六轮：幻影剑（此前根本没有执行器 ⇒ 在解析期就被丢掉）
                case "slashblade:summoned_sword" -> summonSword(maid);

                // 撤退&脱战：位移由它自己接管（步法让位），并清空目标进入脱战
                case "fight_like_player:disengage" -> disengage(maid);

                // ── ★ Goety / 铁魔法：跨模组，走惰性守卫 ──
                case "goety:cast_focus" -> goetyCast(maid, id, need, resolver, gameTime);
                // ★★ Goety 仆从管理 5 条（2026-10-01）—— 全部非 Player 入口，见 GoetyServantOps
                case "goety:execute_servant",
                     "goety:dismiss_temporary_servants",
                     "goety:recall_servants",
                     "goety:set_servant_stance",
                     "goety:recall_to_saved_coords" -> goetyServant(maid, id);
                case "irons:cast_spell", "irons:cast_scroll" -> ironsCast(maid, id, need, resolver);

                default -> {
                    FightLikePlayer.LOGGER.debug("[FLP] 无执行器：{}", id);
                    yield Result.NO_EXECUTOR;
                }
            };
        } catch (Exception ex) {
            // ★ GunCommonUtil.performGunAttack 声明了 throws Exception（枪包的 lua 脚本可能出错）
            FightLikePlayer.LOGGER.error("[FLP] 执行动作 {} 时异常", id, ex);
            clearInFlight(maid);
            return Result.FAILED;
        }
    }

    /**
     * ★ <b>每 tick 推进在途动作</b>，返回它是否已经真的做完。
     *
     * <p>这是"执行器回报完成"的落点（docs/09 §5.3 的承诺门控权威来源）。
     * 调用方（{@code PlayerLikeCombat}）在收到 {@link Progress#COMPLETED} 时
     * 调 {@code DecisionCycle#notifyCompleted()} ⇒ 立刻可以开启下一个决策周期，
     * <b>不必等满清单里的兜底 tick 数</b>。
     */
    public static Progress tickInFlight(EntityMaid maid, long gameTime) {
        InFlight st = IN_FLIGHT.get(maid.getUUID());
        if (st == null) {
            return Progress.NONE;
        }

        if (st.phase == Phase.CHANNEL) {
            // ★★ 第十五轮：刀技走它自己的驱动器（SlashBladeChannel，挂在 MaidTickEvent 上）。
            //   为什么不能落进下面 goety 那一条：刀技**不是**引导法术 ——
            //   它的"推进"是每 tick 替她把刀那一帧推一下（见 SlashBladeChannel 的类注释），
            //   而且完成判据是 comboSeq 回到 NONE。★ 这里只做"回报"，推进不在这里做
            //   （她在没仇恨目标时行为层不跑；驱动器必须独立于行为层）。
            if (st.actionId != null && st.actionId.startsWith("slashblade:")) {
                if (com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel
                        .isRunning(maid)) {
                    return Progress.NONE;
                }
                clearInFlight(maid);
                return Progress.COMPLETED;
            }
            // ★★ 引导法术：交给 GoetyChannel 推进（它会自己处理蹲姿/瞄准/使用物品/异常拉黑）
            var p = com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel.tick(maid);
            if (p == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel.Progress.RUNNING) {
                return Progress.NONE;
            }
            clearInFlight(maid);
            return Progress.COMPLETED;
        }

        if (st.phase == Phase.COOLDOWN) {
            if (gameTime >= st.readyAt) {
                clearInFlight(maid);
                return Progress.COMPLETED;
            }
            return Progress.NONE;
        }

        if (st.phase == Phase.MAGAZINE) {
            return tickMagazine(maid, st, gameTime);
        }

        if (st.phase == Phase.CAST_WINDUP) {
            // ★ 前摇期间：持续瞄准（法术只能沿朝向发射）+ 转向，到点再真正施法
            AimHelper.faceTarget(maid);
            LivingEntity t = maid.getTarget();
            if (t == null || !t.isAlive()) {
                // 目标没了 ⇒ 前摇作废（与"提前松手不放"一致）
                clearInFlight(maid);
                return Progress.COMPLETED;
            }
            if (gameTime < st.readyAt) {
                return Progress.NONE;
            }
            if (st.payload instanceof com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                    .IronsSpells.Option option) {
                boolean ok = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells
                        .cast(maid, option);
                if (ok) {
                    spellCooldowns(maid).markUsed(option.label(), gameTime,
                            com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells
                                    .cooldownTicksOf(option));
                }
            }
            clearInFlight(maid);
            return Progress.COMPLETED;
        }

        // ── CHARGING ──
        LivingEntity target = maid.getTarget();
        if (target == null || !target.isAlive()) {
            stopUse(maid, st.kind);                 // 目标没了 ⇒ 收手，不放箭
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        if (isCharged(maid, st.kind)) {
            releaseShot(maid, st.kind);             // ★ 真的放出去
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        if (!maid.isUsingItem()) {
            // 有人把它打断了（换手、被击落…）⇒ 不能让循环永远等着
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        return Progress.NONE;
    }

    /** 是否有在途动作（诊断用）。 */
    public static boolean hasInFlight(EntityMaid maid) {
        return IN_FLIGHT.containsKey(maid.getUUID());
    }

    private static void clearInFlight(EntityMaid maid) {
        IN_FLIGHT.remove(maid.getUUID());
    }

    /**
     * 蓄力是否已经够了。
     *
     * <p>★ 三个来源都与 TLM 的实现一一对应（见类注释的表格）：
     * <ul>
     *   <li>弓：{@code ticksUsingItem >= 20 - 5×快速射击等级}（{@code MaidShootTargetTask.java:91-97}）</li>
     *   <li>弩：装填完成（{@code CrossbowItem.isCharged}）或 {@code ticksUsingItem >= getChargeDuration}</li>
     *   <li>三叉戟：{@code ticksUsingItem >= 30}（{@code MaidTridentTargetTask.java:90}）</li>
     * </ul>
     */
    private static boolean isCharged(EntityMaid maid, ShotKind kind) {
        int used = maid.getTicksUsingItem();
        return switch (kind) {
            case BOW, CROSSBOW -> {
                if (kind == ShotKind.CROSSBOW && CrossbowItem.isCharged(maid.getItemInHand(weaponHand(maid)))) {
                    yield true;
                }
                yield used >= chargeTicksFor(maid, kind);
            }
            case TRIDENT -> used >= TRIDENT_THROW_TICKS;
        };
    }

    /**
     * 各射击动作的蓄力时长（<b>运行期真值</b>，不是清单里的兜底值）。
     */
    private static int chargeTicksFor(EntityMaid maid, ShotKind kind) {
        return switch (kind) {
            case BOW -> {
                // ★ MaidShootTargetTask.java:91-97：有快速射击附魔时每级 −5 tick
                int level = EnchantmentHelper.getItemEnchantmentLevel(
                        Enchantments.QUICK_CHARGE, maid.getUseItem().isEmpty()
                                ? maid.getMainHandItem() : maid.getUseItem());
                yield Math.max(1, 20 - level * 5);
            }
            case CROSSBOW -> {
                ItemStack st = maid.getItemInHand(weaponHand(maid));
                yield st.getItem() instanceof CrossbowItem ? CrossbowItem.getChargeDuration(st) : 25;
            }
            case TRIDENT -> TRIDENT_THROW_TICKS;
        };
    }

    // ───────────────────────── ★★ 换手前置（逻辑层次的第三环）─────────────────────────

    /**
     * ★★ <b>确保该动作所需的载体物品在主手</b>；不在就换过来。
     *
     * <h2>为什么它必须是"前置步骤"而不是"一个动作"</h2>
     * 委托方的逻辑层次是：
     * <pre>
     *   ① 按【女仆拥有的道具】（含物品栏）决定循环      ← 解析期：CarrierResolver
     *   ② 循环决定动作                                  ← 决策期：DecisionCycle
     *   ③ 若动作的道具不在手上 ⇒ 切到手上                ← ★ 本方法（执行期）
     * </pre>
     * 第 ③ 环此前<b>完全缺失</b>：候选集来自背包，执行器却都只看主手
     * ⇒ 实测表现为「女仆从不切换物品，手上拿着啥就用啥」（委托方 2026-09-30）。
     *
     * <p>把它做成<b>动作</b>是错的：玩家也不会"选择"换武器，他是<b>顺手换</b>的
     * —— 这正是 docs/09 §4.6.2 的结论（"换武器是执行器的前置步骤"）。
     *
     * <h2>判据复用解析期的同一条载体规则</h2>
     * 用 {@link CarrierRule#matches} 判断，而不是另写一套名字比对 ⇒
     * <b>换手判定与"这个动作能不能选"的判定永远一致</b>（弓、类型名匹配、
     * 属性匹配、能力匹配全部自动一致）。
     *
     * <h2>从哪换</h2>
     * 用 TLM 自己的 {@code TaskEquipUtil#tryEquipFromBackpack(maid, predicate)}：
     * 它是<b>同步</b>的（同一 tick 内完成），并且会把主手原来的物品放回那个槽位
     * —— TLM 的 {@code IAttackTask#onFunctionCallSwitch} 就是这么做的，属已验证路径。
     *
     * @return {@code true} = 已就绪（本来就在手上，或换成功了）
     */
    /**
     * ★ <b>只查「手上那件行不行」，不换手</b>（{@code no_item_switch} 的判据，M2 落点 ②）。
     *
     * <p>与 {@link #ensureCarrierInHand} 的区别：那个会「从背包换上来」，这个只问
     * 「现在这个位置上的东西符不符合规则」—— 不符合就当「没做出来」（不扣代价）。
     */
    private static boolean handAlreadyFits(EntityMaid maid, CandidateAction action,
                                           CarrierResolver resolver, SlotKind loopSlot) {
        if (resolver == null) {
            return true;
        }
        var spec = resolver.specOf(action.id());
        if (spec == null) {
            return true;
        }
        CarrierRule rule = resolver.ruleOf(spec.carrier());
        if (rule == null || rule.isAlways() || rule.isUnresolved()) {
            return true;
        }
        boolean offhandLoop = loopSlot == SlotKind.OFFHAND
                || (!rule.slots().isEmpty() && !rule.slots().contains(SlotKind.MAINHAND));
        if (offhandLoop) {
            ItemStack off = maid.getOffhandItem();
            return !off.isEmpty()
                    && rule.matches(MaidSnapshot.possessedOf(maid, SlotKind.OFFHAND, off, 0));
        }
        ItemStack cur = maid.getMainHandItem();
        return !cur.isEmpty()
                && rule.matches(MaidSnapshot.possessedOf(maid, SlotKind.MAINHAND, cur, 0));
    }

    public static boolean ensureCarrierInHand(EntityMaid maid, CandidateAction action,
                                              CarrierResolver resolver) {
        return ensureCarrierInHand(maid, action, resolver, SlotKind.MAINHAND);
    }

    /**
     * 槽位感知版：见 {@link #execute(EntityMaid, CandidateAction, long, CarrierResolver, SlotKind)} 的说明。
     *
     * @param loopSlot 调用方所在的循环槽位
     */
    public static boolean ensureCarrierInHand(EntityMaid maid, CandidateAction action,
                                              CarrierResolver resolver, SlotKind loopSlot) {
        // ★★ 指令 {@code no_item_switch}（M2 落点 ②）：她不换手 ⇒ 候选集只由【主手现有物品】决定。
        //   ★ 语义上它不是“拒绝”，而是“换手这一步不存在” ⇒ 手上不是那件东西就直接做不了。
        if (com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(maid).has("no_item_switch")) {
            return handAlreadyFits(maid, action, resolver, loopSlot);
        }
        // ★★ 她正在引导法术时【不允许换手】—— 换手会把法杖换走 ⇒ 通道立刻中断、
        //    光束自毁。返回 false ⇒ 上层按"没做出来"处理（★ 不扣代价、不进承诺，
        //    所以她只是这一拍没做别的事，而不是被罚）。
        if (com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel.isChanneling(maid)
                && !action.id().equals("goety:cast_focus")) {
            // ★ 第十二轮：这条是【可见】的（原来是 debug）——
            //   委托方实测「法杖切不出来」时，日志里反复出现的就是这一行，
            //   而它在 debug 级别下用户看不见 ⇒ 观感就是"她什么都不做"。
            FightLikePlayer.LOGGER.info("[FLP] 正在引导法术 ⇒ 本 tick 不换手、不做 {}"
                    + "（当前引导：{}）", action.id(),
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel
                            .describe(maid));
            return false;
        }
        // ★★ 第十六轮（同一条教训的第二个实例）：她在放刀技时【也不允许换手】。
        //   刀技是跨 tick 的动作（蓄力 → 松手 → 连段逐帧推进），而推进的对象是
        //   **主手那把刀**；换手会让蓄力/连段当场作废（cur 的日志会是"蓄力被打断"）。
        //   ★ 与我们给引导法术加的那条守卫同构 —— 差别只在"结束判据"不同。
        if (com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel
                .isRunning(maid)) {
            FightLikePlayer.LOGGER.info("[FLP] 正在放刀技 ⇒ 本 tick 不换手、不做 {}"
                    + "（当前：{}）", action.id(),
                    com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel
                            .describe(maid));
            return false;
        }
        if (resolver == null) {
            return true;                        // 没有清单信息 ⇒ 不做前置（保守放行）
        }
        var spec = resolver.specOf(action.id());
        if (spec == null) {
            return true;
        }
        CarrierRule rule = resolver.ruleOf(spec.carrier());
        // 不需要物品的载体（走位/撤退等 always 规则），或判据未查证 ⇒ 无需换手
        if (rule == null || rule.isAlways() || rule.isUnresolved()) {
            return true;
        }
        // ★★ 槽位保护：规则的槽位不含主手 ⇒ 这个动作属于【副手循环】。
        //    旧实现直接 return false ⇒ 副手动作（举盾）永远 UNAVAILABLE（实测选中 36 次、成功 0 次）。
        //    正确语义：既然是副手动作，就检查"副手是不是那件合规物品"，而不是"换到主手"。
        if (!rule.slots().isEmpty() && !rule.slots().contains(SlotKind.MAINHAND)) {
            if (!rule.slots().contains(SlotKind.OFFHAND)) {
                // 既不要主手也不要副手（例如只在 INVENTORY 里用）⇒ 这两个循环都不该做它
                FightLikePlayer.LOGGER.debug("[FLP] {} 的载体 {} 的槽位 {} 既不含主手也不含副手 ⇒ 不换手",
                        action.id(), rule.carrier(), rule.slots());
                return false;
            }
            ItemStack off = maid.getOffhandItem();
            boolean ready = !off.isEmpty()
                    && rule.matches(MaidSnapshot.possessedOf(maid, SlotKind.OFFHAND, off, 0));
            if (!ready) {
                FightLikePlayer.LOGGER.debug("[FLP] 副手动作 {} 的前置不满足：副手是 {}（载体 {} 需要 {}）",
                        action.id(), off.isEmpty() ? "(空手)" : MaidSnapshot.itemId(off),
                        rule.carrier(), rule.slots());
            }
            return ready;
        }
        // 手上已经是合规物品 ⇒ 无需换手
        ItemStack cur = maid.getMainHandItem();
        if (!cur.isEmpty() && rule.matches(MaidSnapshot.possessedOf(maid, SlotKind.MAINHAND, cur, 0))) {
            return true;
        }
        // 从背包里找一件同样满足该规则的
        String before = cur.isEmpty() ? "(空手)" : MaidSnapshot.itemId(cur);
        // ★★ 第十一轮修：改用【全量可用背包】换手（委托方实测「goety 魔杖在物品栏时拿不到主手」）。
        //    根因：TLM 的 TaskEquipUtil 只搜 getAvailableBackpackInv()（它的【小背包】），
        //    不含女仆的 27 格物品栏 ⇒ 放物品栏里的法杖永远换不上来。
        //    先问 TLM 的（保持兼容它在某些任务下的小背包语义），失败再用我们自己的全量实现。
        boolean ok = TaskEquipUtil.tryEquipFromBackpack(maid, st ->
                !st.isEmpty() && rule.matches(MaidSnapshot.possessedOf(maid, SlotKind.MAINHAND, st, 0)));
        if (!ok) {
            ok = com.touhoulittlemad.fightlikeplayer.compat.maid.HandEquip.equipFromInventory(
                    maid, net.minecraft.world.InteractionHand.MAIN_HAND, st ->
                            !st.isEmpty() && rule.matches(MaidSnapshot.possessedOf(
                                    maid, SlotKind.MAINHAND, st, 0)));
        }
        if (ok) {
            FightLikePlayer.LOGGER.debug("[FLP] ★ 换手：{} → {}（为 {}）", before,
                    MaidSnapshot.itemId(maid.getMainHandItem()), action.id());
        } else {
            FightLikePlayer.LOGGER.debug("[FLP] 换手失败：{}（为 {}）—— 物品可能在盔甲/饰品槽，"
                    + "或同类物品都被耐久过滤掉了", before, action.id());
        }
        return ok;
    }

    // ───────────────────────── ★ 跨模组：Goety / 铁魔法 ─────────────────────────

    /**
     * Goety 施法。
     *
     * <p>★ <b>为什么单独包一层</b>：{@code GoetyExecutors} 引用了 Goety 的类型。
     * 把"是否装了模组"这个判断<b>显式化</b>，而不是依赖 JVM 的类加载时机 ——
     * 意图可读，日志也能说清"为什么没做"。
     *
     * <p>★★ 2026-10-01：<b>施法前先朝目标瞄准</b>（{@link AimHelper}）。
     * 法术没有"目标"参数，只能沿施法者朝向发射 ⇒ 不瞄准就会飞向空气，
     * 观感就是"施法了但什么都没发生"。委托方实测「goety 法术不可行」的另一半正在这里。
     */
    private static Result goetyCast(EntityMaid maid, String actionId,
                                    com.touhoulittlemad.fightlikeplayer.decision.NeedVector need,
                                    CarrierResolver resolver, long gameTime) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
            return Result.NO_EXECUTOR;
        }
        AimHelper.faceTarget(maid);
        // ★ 按需求挑一颗聚晶并把具体法术的向量告诉上层（供扣减用）
        var spec = resolver == null ? null : resolver.specOf(actionId);
        java.util.Map<String, com.touhoulittlemad.fightlikeplayer.decision.NeedVector> overrides =
                spec == null ? java.util.Map.of() : spec.vectorOverrides();
        var r = com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.cast(
                maid, need, overrides, CONFIG_FOR_PICK,
                spellLastUsed(maid), gameTime);
        // ★ 失败必须【可读】：静默返回 false 会让"法术无法释放"永远查不出原因（docs/13 第 19 条）
        if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result.NO_WAND) {
            FightLikePlayer.LOGGER.info("[FLP] goety 施法失败：主手不是法杖（{}）—— 换手前置应当先换上来",
                    MaidSnapshot.itemId(maid.getMainHandItem()));
        } else if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result.NO_FOCUS) {
            FightLikePlayer.LOGGER.info("[FLP] goety 施法失败：她没有任何能用的聚晶"
                    + "（杖内/物品栏/聚晶包都看了，或装不进法杖）");
        } else if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result.NOT_A_SPELL) {
            FightLikePlayer.LOGGER.info("[FLP] goety 施法失败：这个聚晶不是 Spell 子类 ⇒ 无法走 mobSpellResult");
        }
        // ★ 注意：本类自己也有一个嵌套的 Result 枚举 ⇒ 不能 import Goety 那个，只能写全名。
        if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result.OK) {
            return Result.OK;
        }
        if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result
                .CHANNEL_STARTED) {
            // ★★ 通道类法术：登记在途状态，由 tickInFlight 每 tick 推进 startSpell→useSpell→stopSpell
            InFlight st = new InFlight();
            st.actionId = actionId;
            st.phase = Phase.CHANNEL;
            IN_FLIGHT.put(maid.getUUID(), st);
            return Result.OK;
        }
        if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result
                .CONDITIONS_NOT_MET) {
            FightLikePlayer.LOGGER.info("[FLP] goety 法术条件不满足（已跳过，不扣代价）");
        } else if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result
                .SNEAK_CLEAR_RISK) {
            FightLikePlayer.LOGGER.info("[FLP] 跳过 goety 法术：潜行变体有危险性（见上一行）");
        } else if (r == com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors.Result
                .SNEAK_BRANCH_RISK) {
            // ★ 第九轮的新硬规则：潜行会让法术换成"清除/召回"那一套分支语义
            FightLikePlayer.LOGGER.info("[FLP] 跳过 goety 法术：她仍处于潜行（蹲姿清不掉）⇒ 不施法 —— "
                    + "潜行会把这个法术换成清除/召回分支，而清除是 dismiss_temporary_servants 的职责");
        } else {
            FightLikePlayer.LOGGER.info("[FLP] goety 法术已跳过（{}）", r);
        }
        return Result.UNAVAILABLE;
    }

    /** 挑法术用的弹簧参数（与决策层同一套：加权距离必须同口径，否则"挑法术"与"挑动作"不一致）。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.SpringConfig CONFIG_FOR_PICK =
            com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults();

    /** 每个女仆"上一颗聚晶用在哪一 tick" —— 用来打破"永远只用同一颗"。 */
    private static final Map<UUID, Map<String, Long>> SPELL_LAST_USED = new WeakHashMap<>();

    private static Map<String, Long> spellLastUsed(EntityMaid maid) {
        return SPELL_LAST_USED.computeIfAbsent(maid.getUUID(), k -> new java.util.HashMap<>());
    }

    /** 记一次法术使用（供"最久没用优先"用）。 */
    private static void markSpellUsed(EntityMaid maid, String label, long gameTime) {
        spellLastUsed(maid).put(label, gameTime);
    }

    /** ★★ 每个女仆的「法术自己的冷却」账本（ISS / Goety 共用一套；见 {@link SpellCooldownLedger}）。 */
    private static final Map<UUID, com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger>
            SPELL_COOLDOWNS = new WeakHashMap<>();

    private static com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger
            spellCooldowns(EntityMaid maid) {
        return SPELL_COOLDOWNS.computeIfAbsent(maid.getUUID(),
                k -> new com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger());
    }

    /** 铁魔法施法（已核实 ISS 1.20.1-3.16.3 的签名）。 */
    private static Result ironsCast(EntityMaid maid, String actionId,
                                    com.touhoulittlemad.fightlikeplayer.decision.NeedVector need,
                                    CarrierResolver resolver) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")) {
            return Result.NO_EXECUTOR;
        }
        // ★★ 与 goety 同理：法术没有"目标"参数，只能沿施法者朝向发射 ⇒ 先瞄准
        AimHelper.faceTarget(maid);
        var spec = resolver == null ? null : resolver.specOf(actionId);
        var overrides = spec == null ? java.util.Map.<String,
                com.touhoulittlemad.fightlikeplayer.decision.NeedVector>of()
                : spec.vectorOverrides();
        var options = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells
                .options(maid);
        long now = maid.level().getGameTime();
        // ★★ 排除"还在自己冷却里"的法术（第十一轮：冷却的权威来源是法术自己）
        var ledger = spellCooldowns(maid);
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (var o : options) {
            if (!ledger.isReady(o.label(), now)) {
                excluded.add(o.label());
            }
        }
        var chosen = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells.pick(
                options, need, overrides, CONFIG_FOR_PICK, spellLastUsed(maid), now, excluded);
        if (chosen == null) {
            if (!options.isEmpty() && excluded.size() == options.size()) {
                FightLikePlayer.LOGGER.debug("[FLP] 铁魔法：她所有法术都在自己的冷却里（{}）",
                        ledger.describe(excluded, now));
            } else {
                FightLikePlayer.LOGGER.info("[FLP] 铁魔法施放失败：她的法术书/卷轴里没有可放的法术");
            }
            return Result.UNAVAILABLE;
        }
        // ★★ 前摇：ISS 的 LONG / CONTINUOUS 法术在玩家侧要先等 getEffectiveCastTime 个 tick，
        //    而我们直接调 onCast（效果钩子）会把它跳过 ⇒ 由我们自己补一段前摇。
        int windup = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells
                .windupTicksOf(maid, chosen);
        if (windup > 0) {
            InFlight st = new InFlight();
            st.actionId = actionId;
            st.phase = Phase.CAST_WINDUP;
            st.payload = chosen;                       // 到点了用它来真正施法
            st.readyAt = now + windup;
            IN_FLIGHT.put(maid.getUUID(), st);
            markSpellUsed(maid, chosen.label(), now);
            FightLikePlayer.LOGGER.debug("[FLP] 铁魔法起手 {}（前摇 {} tick）", chosen.label(), windup);
            return Result.OK;
        }
        boolean ok = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells
                .cast(maid, chosen);
        if (ok) {
            markSpellUsed(maid, chosen.label(), now);
            // ★ 记下这颗法术自己的冷却（玩家侧走 SEHelper，对女仆从来没有 ⇒ 由我们记账）
            ledger.markUsed(chosen.label(), now,
                    com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsSpells
                            .cooldownTicksOf(chosen));
        }
        return ok ? Result.OK : Result.UNAVAILABLE;
    }

    /**
     * ★★ <b>Goety 仆从管理 5 条</b>（2026-10-01 新增）。
     *
     * <p>入口全部只吃 {@code LivingEntity} / {@code Entity}（见
     * {@link com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps} 的类注释）：
     * <ul>
     *   <li>{@code execute_servant} —— 两段确认（先警告、再处死），与玩家侧语义一致；</li>
     *   <li>{@code dismiss_temporary_servants} —— 只清理【限时】仆从 + 非白名单；</li>
     *   <li>{@code recall_servants} —— 直接位移（★ 不走"潜行施法"，那条会变成召唤）；</li>
     *   <li>{@code set_servant_stance} —— 读<b>命令呼号</b>当前模式并广播给仆从（= 玩家按号角的行为）；</li>
     *   <li>{@code recall_to_saved_coords} —— 用回溯聚晶里存的坐标，把仆从回溯过去。</li>
     * </ul>
     * <p>白名单用 {@code NO_WHITELIST}（= Goety 对非 Player 所有者的官方语义：恒 false）。
     */
    private static Result goetyServant(EntityMaid maid, String actionId) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
            return Result.NO_EXECUTOR;
        }        var noWhitelist = com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps.NO_WHITELIST;
        boolean done = switch (actionId) {
            case "goety:execute_servant" ->
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                            .executeServant(maid, noWhitelist);
            case "goety:dismiss_temporary_servants" ->
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                            .dismissTemporaryServants(maid, noWhitelist) > 0;
            case "goety:recall_servants" ->
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                            .recallServants(maid) > 0;
            case "goety:set_servant_stance" ->
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                            .applyHornMode(maid, maid.getMainHandItem(), noWhitelist) > 0;
            case "goety:recall_to_saved_coords" ->
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                            .recallServantToSavedCoords(maid,
                                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                                            .focusInHand(maid));
            default -> false;
        };
        return done ? Result.OK : Result.UNAVAILABLE;
    }

    // ───────────────────────── 近战 / 盾 ─────────────────────────
    /** 近战：一次调用 = 近战 + 横扫 + 额外伤害（见类注释）。 */
    private static Result melee(EntityMaid maid) {
        LivingEntity target = maid.getTarget();
        if (target == null || !target.isAlive()) {
            return Result.UNAVAILABLE;
        }
        maid.swing(InteractionHand.MAIN_HAND);
        maid.doHurtTarget(target);
        return Result.OK;
    }

    /** 举盾：进入"使用副手"状态（CHANNEL）。 */
    private static Result shield(EntityMaid maid) {
        if (!maid.canUseShield()) {
            return Result.UNAVAILABLE;
        }
        if (!maid.isUsingItem()) {
            maid.startUsingItem(InteractionHand.OFF_HAND);
        }
        return Result.OK;
    }

    // ───────────────────────── ★ 蓄力射击（5 个动作共用）─────────────────────────

    /**
     * 起手蓄力 + <b>登记在途状态</b>。
     *
     * <p>★ 两段动作：这里只起手，{@link #tickInFlight} 在蓄力够了时调
     * {@link #releaseShot} 放出去。与清单的 {@code duration.kind = COMMIT(20)} 吻合。
     */
    private static Result chargeShot(EntityMaid maid, String actionId, ShotKind kind) {
        if (maid.getTarget() == null || !maid.getTarget().isAlive()) {
            return Result.UNAVAILABLE;
        }
        if (!maid.isUsingItem()) {
            if (kind == ShotKind.CROSSBOW) {
                // MaidCrossbowAttack.java:36-39：起手 + 置"正在装填"标志
                maid.startUsingItem(weaponHand(maid));
                maid.setChargingCrossbow(true);
            } else {
                maid.startUsingItem(InteractionHand.MAIN_HAND);
            }
        }
        InFlight st = new InFlight();
        st.actionId = actionId;
        st.phase = Phase.CHARGING;
        st.kind = kind;
        IN_FLIGHT.put(maid.getUUID(), st);
        return Result.OK;
    }

    /**
     * 放出去 —— <b>由 {@link #tickInFlight} 在蓄力足够时调用</b>（不是每 tick 调）。
     */
    public static Result releaseShot(EntityMaid maid, ShotKind kind) {
        LivingEntity target = maid.getTarget();
        if (target == null || !target.isAlive()) {
            stopUse(maid, kind);
            return Result.UNAVAILABLE;
        }
        int used = Math.max(maid.getTicksUsingItem(), 20);
        switch (kind) {
            case BOW -> {
                maid.stopUsingItem();
                maid.performRangedAttack(target, BowItem.getPowerForTime(used));
            }
            case TRIDENT -> {
                // MaidTridentTargetTask.java:91-92：力度传 0（三叉戟不看力度）
                maid.stopUsingItem();
                maid.performRangedAttack(target, 0);
            }
            case CROSSBOW -> {
                // MaidCrossbowAttack.java:48,55,63
                maid.releaseUsingItem();
                maid.setChargingCrossbow(false);
                maid.performRangedAttack(target, 1.0F);
                // ★ 别忘了清"已装填"标志，否则弩会一直是装填态
                ItemStack st = maid.getItemInHand(weaponHand(maid));
                CrossbowItem.setCharged(st, false);
            }
        }
        return Result.OK;
    }

    /** 只收手、不放箭（目标没了的情况）。 */
    private static void stopUse(EntityMaid maid, ShotKind kind) {
        if (kind == ShotKind.CROSSBOW) {
            maid.releaseUsingItem();
            maid.setChargingCrossbow(false);
        } else {
            maid.stopUsingItem();
        }
    }

    /** 找出持弩的那只手（弩可能不在主手）。 */
    private static InteractionHand weaponHand(EntityMaid maid) {
        return ProjectileUtil.getWeaponHoldingHand(maid, item -> item instanceof CrossbowItem);
    }

    /** 由动作 id 得到射击种类；不是射击动作则返回 {@code null}。 */
    public static ShotKind shotKindOf(String actionId) {
        return switch (actionId) {
            case "maid_native:bow_shot", "maid_native:throw_any_item", "maid_native:danmaku" ->
                    ShotKind.BOW;
            case "maid_native:trident" -> ShotKind.TRIDENT;
            case "maid_native:crossbow_shot" -> ShotKind.CROSSBOW;
            default -> null;
        };
    }

    // ───────────────────────── 枪械 ─────────────────────────

    /**
     * ★★ <b>开火 —— 一次动作 = 打一梭子</b>（委托方第九轮要求）。
     *
     * <p>出处：{@code GunShootTargetTask.java:68}
     * （{@code attackCooldown = GunCommonUtil.performGunAttack(owner, target, mainHandItem)}）。
     *
     * <h2>★ 返回值是"这一发要多长时间"（tick）</h2>
     * 它不是"射速"这么简单 —— TLM 的实现把**抽枪/开镜/上膛/换弹**都折算成"下一次调用之前要等多久"：
     * <pre>
     *   ShootResult.NO_AMMO   → 请求背包弹药 + gunOperator.reload() → 等 reloadData.cooldown.emptyTime
     *   ShootResult.NOT_DRAW  → draw()                             → 等 drawTime
     *   ShootResult.NEED_BOLT → bolt()                             → 等 boltActionTime
     *   狙击枪/超距        → aim(true)                          → 等 aimTime
     *   SEMI / BURST      → 10 + rand(5)
     *   其余（AUTO）       → 2
     * </pre>
     * ⇒ 我们只要**按它说的节奏反复调用**，就是"连着打"，而换弹会在弹夹打空时自动发生
     * （TaCZ 返回 {@code NO_AMMO} 那条分支）。**不需要 TaCZ 的编译期依赖、也不抄它一行代码。**
     *
     * <h2>★ 怎么知道"这一梭子打完了"</h2>
     * 我们<b>读不到弹夹余弹</b>（那要 TaCZ 的 {@code IGun#getCurrentAmmoCount}），
     * 所以用**行为**推断，三条收尾判据：
     * <ol>
     *   <li><b>打够发数</b>（{@code gun.magazineShots}，默认 30）⇒ 收；
     *       中间的换弹不计数（它返回的是"长等待"，见下）；</li>
     *   <li><b>连续两次"长等待"而没有一发打出去</b> ⇒ 判定为"她没有备用弹药/卡住了" ⇒ 收
     *       （否则会永远换弹）；</li>
     *   <li><b>超过 {@code gun.magazineTicks}（默认 200 tick = 10 秒）</b> ⇒ 兜底收。</li>
     * </ol>
     * 另外目标死亡 / 枪不在手 ⇒ 立刻收。
     *
     * <p>★ 收尾一律回报 {@code Progress.COMPLETED} ⇒ 决策层立刻开下一个周期
     * （这就是"以梭子为单位"的边界：**一次决策 = 一梭子**）。
     */
    private static Result gun(EntityMaid maid, String actionId, long gameTime) {
        LivingEntity target = maid.getTarget();
        if (target == null || !target.isAlive()) {
            return Result.UNAVAILABLE;
        }
        ItemStack gun = maid.getMainHandItem();
        if (!GunCommonUtil.isGun(gun)) {
            return Result.UNAVAILABLE;             // 枪不在主手（等换武器接上）
        }

        // ★ 只有 TaCZ 走"一梭子"；别的枪械包（卓越前线）保持"一发"语义 ——
        //   它们的 performGunAttack 口径不同，用同一套推断会误判。
        //   判据用**类型名**（与 carriers.json 的 javaInstanceOf 同口径），不需要编译期依赖。
        //   ★★ 第十七轮（枪械审计 S1）：这个 `!tacz` 分支**以前是不可达的** ——
        //      `maid:gun` 与 `tacz:gun` 判据都是 IGun ⇒ 能进候选的枪必是 IGun ⇒ tacz 恒为真。
        //      现在 superbwarfare:gun（GunItem）单列一条载体规则 ⇒ **这个分支就是卓越前线的路径**。
        boolean tacz = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .typeNames(gun).contains("IGun");

        int cooldown;
        try {
            // ★★ 第十七轮（枪械审计 S5）：返回值要**除以 `MAID_GUN_ATTACK_SPEED`** —— 这是 TLM 自己的口径
            //    （`GunShootTargetTask#performRangedAttack` 字节码 122-161：先把返回值存进 attackCooldown，
            //     再 `getAttribute(InitAttribute.MAID_GUN_ATTACK_SPEED)`，非空就 `ddiv` 后 `d2i`）。
            //    该属性默认 1.0 ⇒ 现在数值等价；但装了改这个属性的东西之后，不折算就会漂移。
            cooldown = gunCooldownTicks(maid, GunCommonUtil.performGunAttack(maid, target, gun));
        } catch (Exception e) {
            // ★ 它声明的是 checked `throws Exception`（枪包那边的 lua 脚本可能出错）——
            //   不能只 catch RuntimeException（那会编译不过，也漏掉真正的错误）
            FightLikePlayer.LOGGER.info("[FLP] 枪械开火失败（返回失败，不扣代价）：{}", e.toString());
            return Result.UNAVAILABLE;
        }

        InFlight st = new InFlight();
        st.actionId = actionId;
        if (!tacz) {
            st.phase = Phase.COOLDOWN;
            st.readyAt = gameTime + Math.max(1, cooldown);
        } else {
            st.phase = Phase.MAGAZINE;
            st.startedAt = gameTime;
            st.shotsFired = 1;                     // 上面那一发已经出去了
            st.nextShotAt = gameTime + Math.max(1, cooldown);
            st.lastCallWasSlow = isSlow(cooldown);
            st.longWaits = st.lastCallWasSlow ? 1 : 0;
        }
        IN_FLIGHT.put(maid.getUUID(), st);
        return Result.OK;
    }

    /**
     * ★★ <b>把 {@code performGunAttack} 的返回值折算成 tick</b> —— 复刻 TLM 的口径（第十七轮，枪械审计 S5）。
     *
     * <pre>
     *   GunShootTargetTask#performRangedAttack（javap -c 偏移 122-161）：
     *     attackCooldown = GunCommonUtil.performGunAttack(maid, target, stack);
     *     AttributeInstance ai = maid.getAttribute(InitAttribute.MAID_GUN_ATTACK_SPEED.get());
     *     if (ai != null) attackCooldown = (int) ((double) attackCooldown / ai.getValue());
     * </pre>
     * ⇒ 我们照抄：**同一个属性、同一种取法、同一种截断**（{@code d2i} 是截断而不是四舍五入）。
     * ★ 属性/实例拿不到时用原值（与 TLM 的 {@code if (ai != null)} 同构，绝不因为拿不到而变成 0）。
     */
    private static int gunCooldownTicks(EntityMaid maid, int cooldown) {
        try {
            var inst = maid.getAttribute(
                    com.github.tartaricacid.touhoulittlemaid.init.InitAttribute
                            .MAID_GUN_ATTACK_SPEED.get());
            if (inst != null) {
                return (int) ((double) cooldown / inst.getValue());
            }
        } catch (Throwable t) {
            FightLikePlayer.LOGGER.debug("[FLP] 枪械射速属性取不到，按原值处理：{}", t.toString());
        }
        return cooldown;
    }

    /** "长等待" = 这一发之外还有别的事（换弹/抽枪/上膛/开镜）。 */
    private static boolean isSlow(int cooldown) {
        return cooldown > SLOW_CALL_TICKS;
    }

    /**
     * 射速判据的阈值（tick）：超过它就认为"这一次调用不是单纯打了一发"。
     * <p>依据 TLM 的实现（见 {@link #gun} 的表）：AUTO = 2、SEMI/BURST = 10~14，
     * 而抽枪/开镜/换弹是"按秒算"的（{@code Math.round(seconds * 20)}）。
     * ⇒ 取 16 能把两侧干净分开（SEMI 的上界 14 < 16 < 最短的按秒动作 20）。
     */
    private static final int SLOW_CALL_TICKS = 16;

    /**
     * ★★ 推进"一梭子"。
     *
     * @return {@link Progress#COMPLETED} = 这一梭子结束（决策层可以开下一个周期了）
     */
    private static Progress tickMagazine(EntityMaid maid, InFlight st, long gameTime) {
        // ① 目标没了 / 枪不在手 ⇒ 收
        LivingEntity target = maid.getTarget();
        if (target == null || !target.isAlive()) {
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        ItemStack gun = maid.getMainHandItem();
        if (!GunCommonUtil.isGun(gun)) {
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        // ③ 兜底时限
        int maxTicks = com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                .get(com.touhoulittlemad.fightlikeplayer.config.FlpConfig.GUN_MAGAZINE_TICKS, 200);
        if (gameTime - st.startedAt >= maxTicks) {
            if (FightLikePlayer.LOGGER.isDebugEnabled()) {
                FightLikePlayer.LOGGER.debug("[FLP] 枪械：达到时长上限 {} tick ⇒ 收枪（打了 {} 发）",
                        maxTicks, st.shotsFired);
            }
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        // 还没到下一发的时刻 ⇒ 等
        if (gameTime < st.nextShotAt) {
            return Progress.NONE;
        }
        // ② 再打一发
        int cooldown;
        try {
            // ★ 与 gun() 同一口径（除以 MAID_GUN_ATTACK_SPEED），见那里的说明
            cooldown = gunCooldownTicks(maid, GunCommonUtil.performGunAttack(maid, target, gun));
        } catch (Exception e) {
            clearInFlight(maid);
            return Progress.COMPLETED;
        }
        boolean slow = isSlow(cooldown);
        if (slow) {
            // 长等待 = 换弹/抽枪/上膛/开镜 —— 不算一发，但要按它说的时间等
            st.longWaits = st.lastCallWasSlow ? st.longWaits + 1 : 1;
            st.lastCallWasSlow = true;
            if (st.longWaits >= 2) {
                // ★ 连续两次长等待、中间一发都没打出去 ⇒ 打不出去（多半是没有备用弹药）
                if (FightLikePlayer.LOGGER.isDebugEnabled()) {
                    FightLikePlayer.LOGGER.debug(
                            "[FLP] 枪械：连续 {} 次换弹/上膛都没有打出子弹 ⇒ 提前收（多半没有备用弹药）",
                            st.longWaits);
                }
                clearInFlight(maid);
                return Progress.COMPLETED;
            }
        } else {
            st.shotsFired++;
            st.longWaits = 0;
            st.lastCallWasSlow = false;
            int maxShots = com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                    .get(com.touhoulittlemad.fightlikeplayer.config.FlpConfig.GUN_MAGAZINE_SHOTS, 30);
            // ★★ 指令 {@code conserve_ammo}（M2 落点 ⑤）：把「一梭子」改成「点射 N 发」。
            //   ★ 指令优先于配置旋钮（越具体的层优先）—— 见 docs/15 §6 冲突规则。
            var dirBus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                    .of(maid);
            if (dirBus.has("conserve_ammo")) {
                Double shots = dirBus.param("conserve_ammo", "shots");
                if (shots != null) {
                    maxShots = Math.min(maxShots, (int) Math.round(shots));
                }
            }
            if (st.shotsFired >= maxShots) {
                if (FightLikePlayer.LOGGER.isDebugEnabled()) {
                    FightLikePlayer.LOGGER.debug("[FLP] 枪械：打满 {} 发 ⇒ 收枪", st.shotsFired);
                }
                clearInFlight(maid);
                return Progress.COMPLETED;
            }
        }
        st.nextShotAt = gameTime + Math.max(1, cooldown);
        return Progress.NONE;
    }

    /**
     * ★★ <b>拔刀剑·斩击子集</b> —— 直调 {@code AttackManager.doSlash(maid, …)}。
     *
     * <p>★ 为什么这样就能用：`doSlash` 是 **public static**，形参只有 `LivingEntity`；
     * TLM 的 {@code SlashBladeCompat#swingSlashBlade} 被硬门控在 `TaskAttack.UID`（W34），
     * 但**那个门控只在 TLM 的包装层** ⇒ 我们绕开包装、直调底层即可。
     *
     * <p>⚠️ 每次调用 = 一次即时的斩击判定（不是它那一招的逐帧编排）⇒ 见
     * {@code SlashBladeExecutors} 的类注释与 W41。
     */
    private static Result slashblade(EntityMaid maid, float roll, double comboRatio) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
            return Result.NO_EXECUTOR;
        }
        return com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors
                .slash(maid, roll, comboRatio) ? Result.OK : Result.UNAVAILABLE;
    }

    /**
     * ★★ 刀技（SA）—— 第十六轮改成<b>真正的起手 + 逐帧驱动</b>。
     *
     * <h2>为什么不再直调 {@code doChargeAction}</h2>
     * 那条路只做"状态迁移 + clickAction"，**伤害全在 {@code ComboState#tickAction} 里**，
     * 而它只由 {@code Item#inventoryTick} 驱动 —— 女仆的持有物永远不会被引擎调到那个方法
     * ⇒ 日志里"SA 触发成功"与"实际零效果"同时成立（我们踩过）。
     * ⇒ 现在：这里只**起手**（进蓄力）并登记在途；之后由
     * {@link com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel}
     * 每 tick 推（松手 → 每 tick {@code inventoryTick} → 连段结束）。
     */
    private static Result slashbladeArt(EntityMaid maid) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
            return Result.NO_EXECUTOR;
        }
        boolean started = com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade
                .SlashBladeChannel.start(maid, maid.level().getGameTime());
        if (!started) {
            return Result.UNAVAILABLE;
        }
        // ★ 登记为 CHANNEL：承诺门控等 SlashBladeChannel 说"连段打完了"才放行下一个动作
        //   （★ 超时兜底放在执行器里，见 tickInFlight 的 slashblade 分支）
        InFlight st = new InFlight();
        st.actionId = "slashblade:slash_art";
        st.phase = Phase.CHANNEL;
        st.readyAt = maid.level().getGameTime()
                + com.touhoulittlemad.fightlikeplayer.decision.SlashArtTimeline
                .COMBO_BUDGET_TICKS;
        IN_FLIGHT.put(maid.getUUID(), st);
        return Result.OK;
    }

    /** ★★ 幻影剑（{@code slashblade:summoned_sword}）—— 第十六轮补上执行器。 */
    private static Result summonSword(EntityMaid maid) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
            return Result.NO_EXECUTOR;
        }
        return com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors
                .summonSword(maid) ? Result.OK : Result.UNAVAILABLE;
    }

    // ★★ 第十七轮（枪械审计 S4）：这里原来有一个 `isMainHandReady(maid, actionId)`
    //   —— 全仓**没有任何调用点**（死代码），而"主手是不是那把枪"这件事已经由
    //   `ensureCarrierInHand`（按**载体规则**判定 + 换手）统一负责。留着它等于同一件事有两个位置。

    // ───────────────────────── ★ 步法（走位）─────────────────────────

    /**
     * ★★ <b>执行一步"步法"</b> —— 走位的唯一入口（见 {@link Gait} 的类注释）。
     *
     * <p>与其它执行器的两点不同，都是刻意的：
     * <ol>
     *   <li><b>没有承诺、不进候选集、不返还/扣减弹簧</b> —— 位移是伺服误差，不是可消费的需求；</li>
     *   <li>它是<b>唯一的导航写入者</b>（brain 里的
     *       {@code SetWalkTargetFromAttackTargetIfTargetOutOfReach} 已被移除）
     *       ⇒ 不再出现"一边后退一边被 vanilla 往敌人拉"的双写打架。</li>
     * </ol>
     *
     * <p>方向语义：
     * <ul>
     *   <li>{@code TOWARD} ⇒ 走向距目标 {@code desiredDistance} 格的位置（用目标当前位置，
     *       因为目标在动）；</li>
     *   <li>{@code AWAY} ⇒ 沿"远离目标"方向走 {@code desiredDistance} 格，
     *       并按 <b>60% 远离 + 40% 朝主人</b> 加权合成 —— 这是委托方 Q8「逃跑不能乱跑，要往主人那边撤」的要求；</li>
     *   <li>{@code TO_OWNER} ⇒ 走向主人；</li>
     *   <li>{@code HOLD} ⇒ 停止寻路（**显式停下**，不是"什么都不做"——
     *       否则上一次的路径会一直走完）。</li>
     * </ul>
     *
     * @param gait 步法（来自 {@code GaitSelector}）
     * @return 是否成功
     */
    public static Result gait(EntityMaid maid, Gait gait) {
        // ★★ 指令 {@code hold_position}（M2 落点 ③ 的锚点部分）：超出半径就往回走。
        //   ★ 放在这里而不在 GaitSelector：「走到哪个坐标」需要坐标，
        //     而导航的唯一写入者是本方法。
        var dirOfMaid = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(maid);
        var holdAnchor = com.touhoulittlemad.fightlikeplayer.compat.directive
                .DirectiveHolder.anchorOf(maid);
        if (holdAnchor != null && dirOfMaid.has("hold_position")) {
            Double radius = dirOfMaid.param("hold_position", "radius");
            double r = radius == null ? 4 : radius;
            if (Math.sqrt(maid.blockPosition().distSqr(holdAnchor)) > r) {
                return navigate(maid, net.minecraft.world.phys.Vec3.atBottomCenterOf(holdAnchor),
                        1.0);
            }
        }

        if (gait == null || gait.isHold()) {
            maid.getNavigation().stop();
            return Result.OK;
        }
        LivingEntity target = maid.getTarget();
        LivingEntity owner = maid.getOwner();
        double d = gait.desiredDistance();

        switch (gait.direction()) {
            case TOWARD -> {
                if (target == null) {
                    return Result.UNAVAILABLE;
                }
                // 走到"距目标 d 格"的位置：从目标朝女仆方向退 d 格
                Vec3 dir = maid.position().subtract(target.position());
                Vec3 unit = dir.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : dir.normalize();
                Vec3 dest = target.position().add(unit.scale(d));
                return navigate(maid, dest, 1.2);
            }
            case AWAY, AWAY_LEFT, AWAY_RIGHT -> {
                Vec3 dir;
                if (target != null) {
                    Vec3 away = maid.position().subtract(target.position());
                    dir = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
                } else {
                    dir = new Vec3(1, 0, 0);
                }
                if (owner != null) {
                    Vec3 toOwner = owner.position().subtract(maid.position());
                    Vec3 ownerDir = toOwner.lengthSqr() < 1.0E-4 ? Vec3.ZERO : toOwner.normalize();
                    dir = dir.scale(0.6).add(ownerDir.scale(0.4));
                    if (dir.lengthSqr() < 1.0E-4) {
                        dir = ownerDir;
                    }
                }
                // ★★ 第十六轮：左/右后撤 = 把"背离方向"在**水平面**上转 ±35°（斜着撤）。
                //   为什么要它：她挨打时（防御需求堆积）"撤得更稳"是一种有效防守，
                //   而身法是**不需要任何物品、任何时刻都可行**的那条防守出路（委托方原话）。
                if (gait.direction() == Gait.Direction.AWAY_LEFT
                        || gait.direction() == Gait.Direction.AWAY_RIGHT) {
                    double deg = gait.direction() == Gait.Direction.AWAY_LEFT ? 35.0 : -35.0;
                    dir = dir.yRot((float) Math.toRadians(deg));
                }
                return navigate(maid, maid.position().add(dir.normalize().scale(d)), 1.3);
            }
            case TO_OWNER -> {
                if (owner == null) {
                    return Result.UNAVAILABLE;
                }
                return navigate(maid, owner.position(), 1.0);
            }
            default -> {
                maid.getNavigation().stop();
                return Result.OK;
            }
        }
    }

    /**
     * 撤退 &amp; 脱战：远离仇恨源 + 朝主人方向，并清空目标。
     *
     * <p>★ 「<b>优先玩家方向</b>」是委托方 Q8 的明确要求 —— 逃跑不能乱跑，要往主人那边撤。
     * <p>★ 它是<b>动作</b>（含一次性决策 {@code SET_AI_STATE = 脱战}），
     * 因此它会"{@link GaitSelector#actionTakesNavigation 接管导航}"，步法在它执行期间让位。
     */
    private static Result disengage(EntityMaid maid) {
        Result r = gait(maid, new Gait(Gait.Direction.AWAY, 10.0));

        // 进入脱战状态：清空攻击目标（SET_AI_STATE 效果的落点）
        maid.setTarget(null);
        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
        return r;
    }

    /** 原版寻路移动。 */
    private static Result navigate(EntityMaid maid, Vec3 dest, double speed) {
        return maid.getNavigation().moveTo(dest.x, dest.y, dest.z, speed)
                ? Result.OK : Result.UNAVAILABLE;
    }

    // ───────────────────────── 收尾 ─────────────────────────

    /**
     * 动作结束时的清理 —— <b>必须幂等</b>（可能被重复调用）。
     *
     * <p>它是<b>兜底</b>：正常路径下由 {@link #tickInFlight} 的
     * {@link Progress#COMPLETED} 收尾；但若执行器漏报（异常、状态丢失），
     * 承诺到期时这里必须把"使用物品"的状态收干净 ——
     * 否则会永久卡在举盾 / 半拉弓。
     */
    public static void finish(EntityMaid maid, CandidateAction action) {
        String id = action.id();
        try {
            InFlight st = IN_FLIGHT.get(maid.getUUID());
            if (st != null && st.actionId != null && st.actionId.equals(id)) {
                clearInFlight(maid);
            }
            ShotKind kind = shotKindOf(id);
            if (kind != null) {
                releaseShot(maid, kind);            // ★ 承诺到期 ⇒ 放出去（可能已经是空操作）
            } else if ("maid_native:shield_block".equals(id)) {
                maid.stopUsingItem();               // 收手
            }
        } catch (RuntimeException ex) {
            FightLikePlayer.LOGGER.error("[FLP] 收尾动作 {} 时异常", id, ex);
        }
    }

    /** 女仆消失/卸载时清掉在途状态。 */
    public static void forget(UUID maidId) {
        com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel.forget(maidId);
        IN_FLIGHT.remove(maidId);
    }

    /**
     * ★★ <b>强行中止在途动作</b> —— 看门狗的"跳过这一步、强行进入下一个循环"的落点。
     *
     * <p>与 {@link #finish} 的区别：{@code finish} 是"把这一招收干净"（弓要放箭、盾收手），
     * 这里是"<b>不要了</b>"：<b>引导法术要 {@code stopSpell} 收尾</b>（否则第三方那边
     * 可能留着光束/引导状态），蓄力射击则松手不射。
     *
     * <p>为什么必须有：行为层能看见的只是"她一直是这个动作、世界毫无变化"，
     * 而<b>唯一能真正把它拆掉的地方是执行层</b>（只有它知道在途阶段是什么）。
     * 见 {@code PlayerLikeCombat#tickWatchdog}。
     */
    public static void abort(EntityMaid maid) {
        InFlight st = IN_FLIGHT.get(maid.getUUID());
        try {
            if (st != null && st.phase == Phase.CHANNEL) {
                com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel.abort(maid);
            }
            // 蓄力/引导都会"正在使用物品" ⇒ 一律松手（不在使用物品时是空操作）
            if (maid.isUsingItem()) {
                maid.stopUsingItem();
            }
        } catch (RuntimeException ex) {
            FightLikePlayer.LOGGER.error("[FLP] 中止在途动作时异常（已忽略）", ex);
        } finally {
            clearInFlight(maid);
        }
    }

    /** 是否在途（供行为层的"执行器还在忙"查询 / 看门狗用）。 */
    public static boolean isBusy(EntityMaid maid) {
        return IN_FLIGHT.containsKey(maid.getUUID());
    }

    /**
     * ★★★ <b>她是不是"正在做一个动作"</b>（第二十一轮）—— 与 {@link #isBusy} 的区别只在
     * <b>"纯冷却"不算</b>。
     *
     * <h2>为什么要分开</h2>
     * 在途记录的语义有两种，混在一起会把决策层锁得太死：
     * <ul>
     *   <li><b>她正在做</b>（{@code CHARGING} 蓄力 · {@code MAGAZINE} 打一梭子 ·
     *       {@code CHANNEL} 引导 · {@code CAST_WINDUP} 前摇）⇒ 此时另开一个动作
     *       会**打断/覆盖**它（前摇被覆盖 = 法术永远放不出来）；
     *   <li><b>只是武器/法术在冷却</b>（{@code COOLDOWN}）⇒ 那**不是她在忙**，
     *       而是"那件武器暂时不能再用"。此时**应该**允许决策层去挑别的动作
     *       （换近战、换个法术），否则就会变成"开完一枪站着不动"。
     * </ul>
     * ⇒ 决策层的新门（{@code DecisionCycle} 的 1.5 段）用本方法，而
     * "承诺到期还在忙 ⇒ 再等一会儿"那条老门继续用 {@link #isBusy}
     * （它问的是"这条动作做完了没"，冷却没走完当然算没做完）。
     */
    public static boolean isActivelyBusy(EntityMaid maid) {
        InFlight st = IN_FLIGHT.get(maid.getUUID());
        return st != null && st.phase != Phase.COOLDOWN;
    }

    /** 在途动作的 id（诊断用；无则 null）。 */
    public static String inFlightActionId(EntityMaid maid) {
        InFlight st = IN_FLIGHT.get(maid.getUUID());
        return st == null ? null : st.actionId;
    }

    /**
     * ★★ <b>通道已经结束 ⇒ 把执行层的在途状态收干净</b>（第十二轮）。
     *
     * <p>为什么需要：通道现在由 {@code SpellEntityCleaner}（服务器 tick）<b>独立推进</b>
     * ⇒ 它可能在<b>行为层没有运行</b>的时候结束（例如目标已死、一时没有目标），
     * 此时 {@code PlayerLikeCombat} 的第 0 步不会跑，没人调 {@code notifyCompleted()}
     * ⇒ 那条 {@code Phase.CHANNEL} 的在途记录会一直挂着（也会挡住换手）。
     *
     * <p>★ 只清 {@code CHANNEL} 阶段：弓的蓄力、枪的弹夹仍归行为层管
     * （它们本来就需要行为层每 tick 决定"要不要放箭"）。
     */
    public static void releaseChannelInFlight(EntityMaid maid) {
        InFlight st = IN_FLIGHT.get(maid.getUUID());
        if (st != null && st.phase == Phase.CHANNEL) {
            clearInFlight(maid);
        }
    }

    /**
     * ★★ <b>请求立刻换弹</b>（指令 {@code reload_now} 的落点，第十三轮）。
     *
     * <p>做法：直接调一次 {@code performGunAttack} —— 按 TLM 的实现，枪里没弹时它会走
     * {@code ShootResult.NO_AMMO} 分支（请求背包弹药 + {@code reload()}），
     * 而那正是"换弹"的唯一正确入口（我们不去碰 TaCZ 的内部状态）。
     */
    public static void requestReload(EntityMaid maid) {
        try {
            ItemStack gun = maid.getMainHandItem();
            if (!GunCommonUtil.isGun(gun)) {
                FightLikePlayer.LOGGER.info("[FLP][directive] 换弹请求被忽略：主手不是枪械");
                return;
            }
            LivingEntity target = maid.getTarget();
            int cooldown = GunCommonUtil.performGunAttack(maid, target == null ? maid : target, gun);
            FightLikePlayer.LOGGER.info("[FLP][directive] 已请求换弹（下一次可调用时间 {} tick）", cooldown);
        } catch (Exception e) {
            FightLikePlayer.LOGGER.info("[FLP][directive] 换弹请求失败：{}", e.toString());
        }
    }

    /** 已实现的动作 id（与 {@code catalog/executors.json} 的 implemented 项保持一致）。 */
    public static final Set<String> IMPLEMENTED = Set.of(
            // 近战 / 盾
            "maid_native:melee_swing",
            "maid_native:shield_block",
            // ★ 蓄力射击五兄弟
            "maid_native:bow_shot",
            "maid_native:crossbow_shot",
            "maid_native:trident",
            "maid_native:throw_any_item",
            "maid_native:danmaku",
            // ★ 枪械（两个任务入口，同一套实现）
            "maid_native:gun_shot",
            "tacz:shoot",
            // ★★ 拔刀剑·斩击子集（直调 AttackManager.doSlash / doChargeAction，绕开 TLM 的任务门控）
            "maid_native:slashblade_generic_slash",
            "slashblade:combo_a",
            "slashblade:combo_b",
            "slashblade:combo_c",
            "slashblade:combo_a_ex",
            "slashblade:rapid_slash",
            "slashblade:upperslash",
            "slashblade:rising_star",
            "slashblade:upperslash_jump",
            "slashblade:judgement_cut",
            "slashblade:aerial_rave_a",
            "slashblade:aerial_rave_b",
            "slashblade:aerial_cleave",
            "slashblade:slash_art",
            // ★ 跨模组施法
            "goety:cast_focus",
            "irons:cast_spell",
            "irons:cast_scroll",
            // ★★ Goety 仆从管理（2026-10-01）
            "goety:execute_servant",
            "goety:dismiss_temporary_servants",
            "goety:recall_servants",
            "goety:set_servant_stance",
            "goety:recall_to_saved_coords",
            // 撤退&脱战（★ 走位本身已改为"步法"，不再是动作）
            "fight_like_player:disengage");
}
