package com.touhoulittlemad.fightlikeplayer.compat.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 「类玩家战斗」任务 —— 本模组向 TLM 注册的唯一任务入口。
 *
 * <h2>为什么是「新增一个任务」而不是改造 {@code TaskAttack}</h2>
 * 与既有实现（万法皆通的两个施法任务）一致，也是与 TLM 共存的正确姿势：
 * TLM 的 {@code TaskManager} 支持附属追加任务，而<b>改造 {@code TaskAttack} 会与所有其它附属冲突</b>。
 *
 * <h2>★★ 为什么它 implements {@link IRangedAttackTask}（2026-09-30 修，实测驱动）</h2>
 *
 * <p>S0 实测现象：<b>「用弓会拉满但不射」</b>。查源码后发现的第二根因是：
 *
 * <pre>
 * // EntityMaid.java:1207-1217
 * public void performRangedAttack(LivingEntity target, float distanceFactor) {
 *     IMaidTask maidTask = this.getTask();
 *     if (maidTask instanceof IRangedAttackTask rangedAttackTask) {   // ★ 方法体【全在】这个 if 里
 *         ...
 *         rangedAttackTask.performRangedAttack(this, target, distanceFactor);
 *     }
 * }
 * </pre>
 *
 * ⇒ 我们的任务此前只 implements {@code IAttackTask} ⇒ <b>整个方法是空操作</b> ⇒
 * 箭永远射不出去。这与 {@code SlashBladeCompat} 被门控在 {@code TaskAttack.UID} 上
 * 是<b>同一类问题（W34）的第二例</b>：<b>TLM 的兼容助手按"任务类型/UID"分流，
 * 而"单一模式"任务天然不在那些分支里。</b>
 *
 * <p>★ <b>TLM 的模型是「一个任务 = 一类武器」</b>（{@code TaskBowAttack}/{@code TaskCrossBowAttack}/
 * {@code TaskTridentAttack}/{@code TaskDanmakuAttack} 各实现一次 {@code performRangedAttack}）。
 * 我们的模型是「<b>单一模式 + 按手持物分发</b>」（Q15）。
 * ⇒ 两者的正确接合点是：<b>用我们的任务实现那个接口，但把调用"路由"回 TLM 已验证的实现</b>：
 *
 * <pre>
 *   女仆射出箭
 *      ↓ EntityMaid#performRangedAttack（TLM 的门）
 *      ↓ TaskPlayerLikeCombat#performRangedAttack（我们的路由，按主手物品分流）
 *      ↓ TaskManager.findTask("touhou_little_maid:ranged_attack") ⇒ TaskBowAttack（TLM 的实现，未经修改）
 * </pre>
 *
 * <p>这样<b>一行 TLM 的代码都不抄</b>（许可与行为漂移两个问题同时避免），
 * 而且箭的弹道、伤害、耐久消耗与 TLM 的弓任务<b>逐字一致</b>
 * —— 这正是本项目「效果同构」的定义：<b>玩家用弓射出的那一箭，与女仆射出的那一箭是同一个效果。</b>
 *
 * <h2>★ 顺带修好的第二件事：射程</h2>
 * {@code EntityMaid.canSee}（{@code :1866-1871}）同样按任务分流：
 * 若任务实现了 {@code IRangedAttackTask}，就用它的 {@code canSee}（可拿到
 * {@code MaidConfig.BOW_RANGE} 这类<b>超视距</b>配置），否则退回原版 {@code BehaviorUtils.canSee}（16 格）。
 * ⇒ 我们也把 {@code canSee} 按主手物品分流，于是「拿着弓能看到 16 格外的敌人」——
 * 与玩家一致，也解释了为什么以前女仆对远处的敌人毫无反应。
 *
 * <h2>⚠️ 本类依赖的 TLM 非 api 类</h2>
 * {@link TaskManager} 与 {@code GunCommonUtil} 都在 TLM 的 {@code entity.task} /
 * {@code compat.gun} 包里，<b>不是 api 包</b>。这与既有的 {@code EntityMaid}、
 * {@code GunCommonUtil} 用法一致（全部局限在 {@code compat/} 层，由
 * {@code check_isolation.py} 守住）；取不到任务时<b>记 warn 并放弃</b>（fail-visible），
 * 不会静默变成"又一发射不出去的箭"。
 *
 * @see <a href="../../../../../../docs/01-操作清单.md">docs/01-操作清单.md</a>
 * @see <a href="../../../../../../docs/08-测试计划.md">docs/08-测试计划.md</a>
 */
public class TaskPlayerLikeCombat implements IRangedAttackTask {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(FightLikePlayer.MOD_ID, "player_like_combat");

    /** ★ TLM 各远程任务的 UID（用于把 {@code performRangedAttack} 路由回它们的实现）。 */
    private static final ResourceLocation TLM_BOW = new ResourceLocation("touhou_little_maid", "ranged_attack");
    private static final ResourceLocation TLM_CROSSBOW = new ResourceLocation("touhou_little_maid", "crossbow_attack");
    private static final ResourceLocation TLM_TRIDENT = new ResourceLocation("touhou_little_maid", "trident_attack");
    private static final ResourceLocation TLM_DANMAKU = new ResourceLocation("touhou_little_maid", "danmaku_attack");

    /** 取不到任务只警告一次（避免每 tick 刷屏）。 */
    private static boolean warnedMissingTask = false;

    // ───────────────────────── ★ 目标过滤（半命题指令，第十五轮）─────────────────────────

    /** ★ 已经为"哪个目标 + 哪条指令"打过日志（避免每 tick 刷屏）。 */
    private static final java.util.Map<java.util.UUID, String> LAST_TARGET_REJECT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ★★ <b>目标筛选的唯一落点</b>（第十五轮，委托方的「只打某种/某只生物」）。
     *
     * <h2>为什么必须在**这里**（取证结论，TLM 1.5.3 实测）</h2>
     * 女仆的攻击目标是脑记忆 {@code ATTACK_TARGET}（她覆写了 {@code getTarget()} 直读它，
     * 所以两者恒等；{@code Mob.target} 字段对她来说是死字段）。
     * 而写/清这条记忆的三条路径**都要过 {@code EntityMaid#canAttack}**：
     * <ol>
     *   <li>{@code IAttackTask.findFirstValidAttackTarget} 的谓词（找目标）；
     *       <br>★ 而它又只由 {@code StartAttacking} 调用，那个行为的门是
     *       <b>{@code absent(ATTACK_TARGET)}</b> ⇒ <b>她已经有目标时根本不会重选</b>
     *       —— 这正是"你让她只打 B，她却继续打 A"的机制；</li>
     *   <li>{@code StartAttacking} 内的 {@code Mob#canAttack}（开始攻击）；</li>
     *   <li>{@code StopAttackingIfTargetInvalid} 的保留条件（放弃目标）——
     *       ★ 它的 {@code erase()} 是**无条件**执行的 ⇒
     *       <b>只要这里返回 false，A 会在下一 tick 被它自己清掉</b>，
     *       我们**不需要**手动清目标（手动清反而会与它抢时序）。</li>
     * </ol>
     * ⇒ 一个 {@code default} 方法覆写，三处同时生效，<b>不需要 mixin、不需要事件</b>。
     *
     * <p>★★ 事实统计**不能**走这条被指令污染的路：见
     * {@link #canAttackIgnoringDirectives}（{@code enemies_nearby} 那个事实口径必须保持中立）。
     */
    @Override
    public boolean canAttack(EntityMaid maid, LivingEntity target) {
        if (!canAttackBase(maid, target)) {
            return false;                       // ★ 阵营口径（含"自家召唤物"）+ TLM 自己的判定
        }
        var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(maid);
        if (!com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.active(bus)) {
            return true;
        }
        String why = com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.reject(bus,
                com.touhoulittlemad.fightlikeplayer.compat.maid.TargetRefs.of(target));
        if (why == null) {
            return true;
        }
        // ★ 可读出口：只在"目标或指令变了"时记一条（否则每 tick 一条会刷爆日志）
        String note = why + "｜指令：" + String.join("，",
                com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.queries(bus));
        if (!note.equals(LAST_TARGET_REJECT.get(maid.getUUID()))) {
            LAST_TARGET_REJECT.put(maid.getUUID(), note);
            com.touhoulittlemad.fightlikeplayer.FightLikePlayer.LOGGER.info(
                    "[FLP][directive] 目标被指令挡下：{}（她原本要打 {}）", why, target.getName()
                            .getString());
        }
        return false;
    }

    /**
     * ★ <b>不受指令影响的"她本来能不能打它"</b> —— 事实统计专用（{@code enemies_nearby}）。
     *
     * <p>★ 为什么必须有它：{@code MaidSnapshot.countNearbyEnemies} 用的是
     * {@code maid.canAttack(e)}。如果那条路也被指令过滤，那么
     * 「只打僵尸」时"附近敌人"会**悄悄变成"附近僵尸数"** ⇒
     * 它同时污染态势偏置（{@code ContextBias}）与指挥 LLM 看到的 {@code scene}
     * —— 那是一种最难查的错：**看起来一切正常，只是数字的含义变了**。
     * ⇒ 两个概念必须分开：**"允许打吗"（含指令）** vs **"本来能打吗"（中立事实）**。
     */
    public static boolean canAttackIgnoringDirectives(EntityMaid maid, LivingEntity target) {
        return com.touhoulittlemad.fightlikeplayer.compat.task.TaskPlayerLikeCombat
                .INSTANCE_DEFAULT.canAttackBase(maid, target);
    }

    /**
     * 走 TLM 的原判据（不含我们的指令）+ <b>阵营口径</b>。
     *
     * <p>★★ <b>第二十二轮（委托方实测：铁魔法召唤物会索敌女仆）</b>：
     * 这一层额外排掉<b>她自己的东西</b>（她自己 / 自家召唤物 / 自家仆从 / 同阵营）。
     * 为什么必须在这里（而不是在"找目标"那一处）：TLM 的
     * {@code EntityMaid#canAttack} 是**写、清、保留目标三条路径的汇合点**
     * （见 {@link #canAttack} 的注释）⇒ 一处生效＝三处生效，
     * 特别是 {@code StopAttackingIfTargetInvalid} 的 {@code erase()} 是无条件的，
     * 于是"她现在的目标是自家召唤物"这件事会**在下一 tick 自动松开**（不必我们手动清）。
     *
     * <p>★ 判据本体在纯逻辑层 {@link com.touhoulittlemad.fightlikeplayer.decision.FactionRules}，
     * compat 侧只负责查那四个事实（{@code MaidAllies}）。
     * ★ 这一层对 {@link #canAttackIgnoringDirectives}（事实统计）也生效，而且**应该是**：
     * 「附近敌人」这个数字本来就不该把自家召唤物算进去（否则态势偏置会被自己人抬高）。
     */
    public boolean canAttackBase(EntityMaid maid, LivingEntity target) {
        if (!IRangedAttackTask.super.canAttack(maid, target)) {
            return false;                       // TLM 自己的阵营/黑名单/分档 —— 先过它
        }
        String ally = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidAllies
                .friendlyReason(maid, target);
        if (ally != null) {
            com.touhoulittlemad.fightlikeplayer.compat.maid.MaidAllies
                    .noteRefused(maid, target, ally);
            return false;
        }
        return true;
    }

    /**
     * ★ 注册用的那一个实例（静态可及）—— 只为让 {@link #canAttackIgnoringDirectives}
     * 能拿到 {@code IAttackTask.super} 的实现（{@code super} 只能在实例方法里用）。
     */
    public static final TaskPlayerLikeCombat INSTANCE_DEFAULT = new TaskPlayerLikeCombat();

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return net.minecraft.world.item.Items.DIAMOND_SWORD.getDefaultInstance();
    }

    @Override
    @Nullable
    public SoundEvent getAmbientSound(EntityMaid maid) {
        // 第一版复用 TLM 的攻击音效，避免引入额外资源。
        return null;
    }

    /**
     * ★★ <b>把"任务配置界面"路由回 TLM 的原版攻击任务</b>（第十六轮，委托方第 2 条）。
     *
     * <h2>委托方原话</h2>
     * > 「考虑到女仆现在已经能获得周围是否有怪物，可否为女仆 llm 添加『获得怪物信息，
     * > 并在**模式设置**中编辑/添加/删除**生物友好/中立/敌对**』的能力？」
     *
     * <h2>★ 复核结论：这套能力 TLM 本来就有，只是我们的任务没把它露出来</h2>
     * TLM 有每女仆的 {@code AttackListData}（`Map<实体类型, MonsterType{FRIENDLY,NEUTRAL,HOSTILE}>`）
     * + 一个图形界面，而那个界面挂在 {@code IAttackTask#getTaskConfigGuiProvider} 上。
     * <b>我们的任务此前没有覆写它</b> ⇒ 玩家在这个任务下**打不开**友好/中立/敌对的编辑器。
     *
     * <p>⇒ 与"箭的 {@code performRangedAttack}"同一招：<b>路由回 TLM 的 {@code TaskAttack} 实现</b>
     * （一行 TLM 的代码都不抄，行为与官方逐字一致）。取不到就返回 null（记 warn，fail-visible）。
     */
    @Override
    public net.minecraft.world.MenuProvider getTaskConfigGuiProvider(EntityMaid maid) {
        var tlmAttack = TaskManager.findTask(TASK_ATTACK_UID).orElse(null);
        if (tlmAttack == null) {
            FightLikePlayer.LOGGER.warn("[FLP] 取不到 TLM 的原版攻击任务 ⇒ 打不开攻击名单界面");
            return null;
        }
        return tlmAttack.getTaskConfigGuiProvider(maid);
    }

    /** TLM 原版攻击任务的 UID（"友好/中立/敌对"编辑器挂在它上面）。 */
    private static final ResourceLocation TASK_ATTACK_UID =
            new ResourceLocation("touhou_little_maid", "attack");

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        BehaviorControl<EntityMaid> findTarget =
                StartAttacking.create(this::hasWeapon, IAttackTask::findFirstValidAttackTarget);
        BehaviorControl<EntityMaid> stopAttack =
                StopAttackingIfTargetInvalid.create(target -> !hasWeapon(maid) || farAway(target, maid));
        // ★ M4：攻击那一步改为走我们的决策循环（+ 双循环）
        BehaviorControl<EntityMaid> decideAndAct = PlayerLikeCombat.create();

        // ★★ 2026-09-30：**移除了** SetWalkTargetFromAttackTargetIfTargetOutOfReach。
        //
        //   它每 tick 把"接近目标"写进 WALK_TARGET，而我们的 charge/retreat 也在写导航
        //   ⇒ 两个写入者互相打架，净效果是"一边想后退一边被拉向敌人"（实测：到处跑）。
        //   ⇒ 现在走位只有一个写入者：步法（decision/GaitSelector → ActionExecutors.gait）。
        //   它的伺服规则里已经包含"距离 > 站位 + 迟滞 ⇒ 靠近"，因此**没有丢掉接近行为**，
        //   只是把它从"每 tick 无条件拉"改成了"按需、可被决策层否决"。
        return Lists.newArrayList(
                Pair.of(5, findTarget),
                Pair.of(5, stopAttack),
                Pair.of(5, decideAndAct)
        );
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        BehaviorControl<EntityMaid> findTarget =
                StartAttacking.create(this::hasWeapon, IAttackTask::findFirstValidAttackTarget);
        BehaviorControl<EntityMaid> stopAttack =
                StopAttackingIfTargetInvalid.create(target -> !hasWeapon(maid) || farAway(target, maid));
        BehaviorControl<EntityMaid> decideAndAct = PlayerLikeCombat.create();

        return Lists.newArrayList(
                Pair.of(5, findTarget),
                Pair.of(5, stopAttack),
                Pair.of(5, decideAndAct)
        );
    }

    // ───────────────────────── ★ 远程攻击路由（IRangedAttackTask）─────────────────────────

    /**
     * ★ <b>按主手物品，路由到 TLM 自己的射击实现</b>。
     *
     * <p>为什么用"路由"而不是"自己实现"：见类注释 —— 一行不抄、行为逐字一致。
     * 也为什么用"实现接口"而不是"在别处直接调 TLM 的任务对象"：
     * 前者让 <b>TLM 自己的所有调用点</b>（含饰品事件 {@code onRangedAttack}）
     * 都能正常走到我们的路由上。
     */
    @Override
    public void performRangedAttack(EntityMaid shooter, LivingEntity target, float distanceFactor) {
        ItemStack main = shooter.getMainHandItem();
        ResourceLocation uid = rangedTaskUidFor(main);
        if (uid == null) {
            // 枪械：TLM 的 TaskGunAttack#performRangedAttack 本身就是空的
            //（枪走 GunCommonUtil，我们的 gun 执行器已直接调用）
            return;
        }
        TaskManager.findTask(uid).ifPresentOrElse(task -> {
            if (task instanceof IRangedAttackTask ranged) {
                ranged.performRangedAttack(shooter, target, distanceFactor);
            }
        }, () -> warnMissing(uid));
    }

    /**
     * ★ 可见性也按主手物品分流 —— 让"拿着弓"真的能超视距索敌。
     *
     * <p>出处：{@code EntityMaid.java:1866-1871} 把 {@code canSee} 委托给当前任务的
     * {@code IRangedAttackTask#canSee}；TLM 的弓/弩/三叉戟/弹幕任务各自返回
     * {@code IRangedAttackTask.targetConditionsTest(maid, target, MaidConfig.XXX_RANGE)}。
     */
    @Override
    public boolean canSee(EntityMaid maid, LivingEntity target) {
        ItemStack main = maid.getMainHandItem();
        if (GunCommonUtil.isGun(main)) {
            return GunCommonUtil.canSee(maid, target)
                    .orElseGet(() -> IRangedAttackTask.super.canSee(maid, target));
        }
        ResourceLocation uid = rangedTaskUidFor(main);
        if (uid != null) {
            var task = TaskManager.findTask(uid).orElse(null);
            if (task instanceof IRangedAttackTask ranged) {
                return ranged.canSee(maid, target);
            }
        }
        return IRangedAttackTask.super.canSee(maid, target);
    }

    /** 主手物品 → 该用哪个 TLM 远程任务；不是远程武器则返回 {@code null}。 */
    @Nullable
    private static ResourceLocation rangedTaskUidFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof BowItem) {
            return TLM_BOW;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return TLM_CROSSBOW;
        }
        if (stack.getItem() instanceof TridentItem) {
            return TLM_TRIDENT;
        }
        // 弹幕物品的判据尚未查证（carriers.json 里 maid:danmaku 仍是 unresolved）
        // ⇒ 有意**不**在这里猜：猜错会导致拿普通物品时被当作弹幕。
        return null;
    }

    private static void warnMissing(ResourceLocation uid) {
        if (!warnedMissingTask) {
            warnedMissingTask = true;
            FightLikePlayer.LOGGER.warn(
                    "[FLP] 找不到 TLM 的远程任务 {} —— 远程攻击将无效。"
                            + "可能是 TLM 版本改了任务 UID（本模组按 1.5.3 的 UID 路由）。", uid);
        }
    }

    /**
     * 「是不是武器」—— ★ <b>W24 已修：改问动作层，而不是看一个属性</b>。
     *
     * <p>旧判据（主手有 {@code ATTACK_DAMAGE}）会让<b>持弓 / 持盾 / 持法杖 / 空手</b>的女仆
     * 统统不被视为武装 ⇒ {@code StartAttacking} 不找目标 ⇒ <b>任务等于没启动</b>。
     */
    @Override
    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        return PlayerLikeCombat.isArmed(maid);
    }

    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getConditionDescription(EntityMaid maid) {
        return Lists.newArrayList(Pair.of("assault_weapon", this::hasWeapon));
    }

    /**
     * 提供给女仆 AI 聊天的任务摘要（TLM 1.5.1+）。
     * 用英文硬编码，与 TLM 的约定一致。
     */
    @Override
    public String getMaidActionSummary() {
        return "Player-like combat: choose discrete actions with whatever is in hand "
                + "(melee, bow, crossbow, trident, gun, spells) and move the way a player would.";
    }

    private boolean hasWeapon(EntityMaid maid) {
        return isWeapon(maid, maid.getMainHandItem());
    }

    /**
     * 目标是否已经超出有效范围 ⇒ 放弃。
     * 判据照搬 TLM：家里模式用女仆自身距离，否则用主人的距离（避免女仆追着主人跑远）。
     */
    private boolean farAway(LivingEntity target, EntityMaid maid) {
        if (!target.isAlive()) {
            return true;
        }
        float radius = maid.getRestrictRadius();
        boolean homeMode = maid.isHomeModeEnable();
        if (!homeMode && maid.getOwner() != null) {
            return maid.getOwner().distanceTo(target) > radius;
        }
        return maid.distanceTo(target) > radius;
    }
}
