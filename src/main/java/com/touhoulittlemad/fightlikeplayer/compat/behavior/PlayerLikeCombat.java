package com.touhoulittlemad.fightlikeplayer.compat.behavior;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.carrier.CarrierResolver;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder;
import com.touhoulittlemad.fightlikeplayer.carrier.ContextBias;
import com.touhoulittlemad.fightlikeplayer.carrier.ContextFacts;
import com.touhoulittlemad.fightlikeplayer.carrier.ItemRef;
import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.carrier.SpringDynamics;
import com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;
import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.CooldownTracker;
import com.touhoulittlemad.fightlikeplayer.decision.DecisionCycle;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter;
import com.touhoulittlemad.fightlikeplayer.decision.Gait;
import com.touhoulittlemad.fightlikeplayer.decision.GaitSelector;
import com.touhoulittlemad.fightlikeplayer.decision.LoopSplit;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;
import com.touhoulittlemad.fightlikeplayer.decision.SpringImpact;
import com.touhoulittlemad.fightlikeplayer.decision.SpringSpace;
import com.touhoulittlemad.fightlikeplayer.decision.StallDetector;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 「类玩家」的战斗行为 —— <b>决策循环接入游戏的地方（M4）</b>。
 *
 * <pre>
 *   MaidSnapshot ──▶ ContextFacts + List&lt;PossessedItem&gt;
 *                            │
 *                    ContextBias（态势偏置）
 *                            │
 *                    CarrierResolver（持有物 → 可用动作）
 *                            │
 *                     DecisionCycle（弹簧最近邻选解）
 *                            │
 *              ┌─────────────┴──────────────┐
 *        ActionExecutors                GaitSelector
 *        （手上做什么）                   （脚下怎么走）
 * </pre>
 *
 * <h2>★★ 2026-09-30 的三处结构性修正（S0 实测驱动）</h2>
 *
 * <h3>① 走位改由「步法」承担（不再是动作）</h3>
 * 见 {@link Gait}。旧的 {@code charge}/{@code retreat} 动作执行后会从弹簧里扣减
 * {@code MOBILITY}，二者互相偿还 ⇒ 永久震荡（"到处跑"）。现在的分工是：
 * <b>动作层管"手上做什么"（占承诺门控），步法层管"脚下怎么走"（并发、不占门控、不被评分）</b>
 * —— 与玩家一致：可以边走边拉弓。
 *
 * <h3>② 三个"权威钩子"终于接上了</h3>
 * {@code DecisionCycle} 早就设计了三个钩子，但<b>此前只在离线自测里被调用，游戏侧一个都没接</b>
 * ⇒ <b>115 项断言全绿而游戏里不工作</b>。现在：
 * <table border="1">
 *   <tr><th>钩子</th><th>接法</th></tr>
 *   <tr><td>{@code notifyCompleted()}（执行器回报完成＝权威）</td>
 *       <td>{@link ActionExecutors#tickInFlight} 报 {@code COMPLETED} 时调用</td></tr>
 *   <tr><td>{@code withDefaultActions()}（死区时做"默认的事"）</td>
 *       <td>有近敌 ⇒ 通用近战（"没特别想做的就砍一刀"）</td></tr>
 *   <tr><td>冷却（{@code coolingDown}）</td>
 *       <td>由 {@code lastUsed + CarrierResolver#cooldownTicksOf} 算出，
 *           走既有的 {@code DropReason.ON_COOLDOWN} 通路 ⇒ 消灭"瞬时动作被 4 次/秒刷屏"</td></tr>
 * </table>
 *
 * <h3>③ 每 tick 只枚举一次持有物</h3>
 * 旧实现里 {@link MaidSnapshot#possessed} 在一次 tick 内被调用 3~8 次（每个候选集一次）。
 * 现在由 {@link Ctx} 携带，一次 tick 一次。
 *
 * <h2>★ 双循环</h2>
 * 主循环（主手 + 盔甲 + 背包 + 女仆背包 + Curios）与<b>副手循环</b>（仅副手槽）
 * <b>共用一个 {@link SpringSpace}</b>，但物品集隔离（{@link LoopSplit}）。
 *
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.7–§5.8 / §8 (M4)</a>
 */
public final class PlayerLikeCombat {

    /** 调参集中在这里。 */
    private static final SpringConfig CONFIG = SpringConfig.builder()
            .decisionInterval(5)
            .build();

    /**
     * ★ 步法的重算周期（tick）。
     * <p>比决策周期稍长：寻路本身有惯性，每 tick 重下目的地只会让女仆"抖动"。
     * 但也不能太长 —— 目标在动，需要重新取点。
     */
    private static final int GAIT_INTERVAL = 10;

    /** 每个女仆的循环状态。 */
    private static final Map<UUID, Loops> STATE = new WeakHashMap<>();

    /**
     * ★★ 看门狗①：多久"毫无可观测变化"就判定卡住（tick）。
     * <p>★ 判据本体在纯逻辑层 {@link StallDetector}（可离线断言）—— 这里只是别名，
     * 便于日志与文档引用同一个数。
     */
    private static final int STALL_TICKS = StallDetector.STALL_TICKS;

    private PlayerLikeCombat() {
    }

    private static final class Loops {
        final SpringSpace space = new SpringSpace();
        final DecisionCycle<Ctx> main;
        final DecisionCycle<Ctx> offhand;

        /** ★ 冷却账本（两个循环共用）—— 纯逻辑类，与回放自测同一实现。 */
        final CooldownTracker cooldowns = new CooldownTracker();

        /** ★ 上一个交给执行器的动作 —— 用于承诺结束时的兜底收尾（弓要放箭、盾要收手）。 */
        CandidateAction pendingFinish;

        /**
         * ★★ 最后一次"她有仇恨对象"的世界时间（第十七轮续）—— 脱战判据的唯一输入。
         * <p>{@code <= 0} = 从没有过（⇒ 一上来就算"脱战"）。
         */
        long lastTargetTick;

        /** ★ 本轮脱战是否已经执行过"自动解除指令"（避免每 tick 都清）。 */
        boolean disengageCleared;

        /** 上次计算步法的时刻。 */
        long lastGaitAt = Long.MIN_VALUE;

        /** 最近一次步法（诊断用）。 */
        Gait lastGait = Gait.hold();

        /** ★ 最近一条「指令挡下」的记录（只在内容变化时写，避免刷屏）。 */
        String lastDirectiveNote;

        /** ★ 最近一次被指令改写的步法（避免刷屏）。 */
        String lastGaitNote;

        /** ★ 最近一次算出的冷却集合（诊断用 —— `/flp why` 会显示它的大小）。 */
        Set<String> lastCooling = Set.of();

        /** ★★ 最近一次解析结果（候选 + 丢弃原因）—— `/flp why` 的"为什么没选它"就靠它。 */
        CarrierResolver.Resolution lastMain = CarrierResolver.Resolution.empty();

        /**
         * ★★ <b>最近的执行结果环形缓冲</b>（`/flp why` 用）。
         *
         * <p>为什么需要：新语义是"没做出来 ⇒ 不扣代价、不进承诺"，
         * 于是**失败是完全静默的**（只在 debug 日志里）。
         * 委托方 2026-10-01 反馈「拔刀剑没有动作 / 从未观测到施法」时，
         * 光看观感无法区分「没被选中」「被前置条件丢了」「执行器回报 UNAVAILABLE」三种情况。
         * ⇒ 把最近若干条结果留在内存里，命令一查就知道。
         */
        final java.util.ArrayDeque<String> recentResults = new java.util.ArrayDeque<>();

        /** 环形缓冲容量 —— 够看清"最近几十拍到底发生了什么"，又不至于占内存。 */
        static final int RECENT_RESULTS_MAX = 40;

        /**
         * ★★ <b>短周期思维层</b>（每个女仆一个）—— 它自己管节流、在途、超时与失败。
         *
         * <p>为什么放在 {@code Loops} 里而不是全局单例：间隔、在途状态都是<b>每女仆</b>的
         * （一个女仆在打、另一个在挖矿，不该互相拖慢）。
         */
        final com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker thinker =
                new com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker(
                        com.touhoulittlemad.fightlikeplayer.compat.think.ThinkBridge.transport());

        /** 最近一次成功施加的判定（诊断用 —— `/flp think` 看它）。 */
        com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker.Outcome lastThink;

        /**
         * ★★ <b>连续失败计数</b>（动作 id → 连续几次被选中却没做出来）。
         *
         * <p>用途：把它乘进冷却（见 {@code effectiveCooldown}），让"注定做不出来的动作"
         * <b>自动让位</b>，而不是永远占住最近邻。
         *
         * <p>★ 为什么不能靠"扣代价"来惩罚失败：那会让弹簧被<b>没发生过的事</b>推动
         * （docs/09 §5.3 修正 ④ 明确禁止——"只对真发生的事付费"）。
         * ⇒ 正确的惩罚维度是<b>时间</b>，不是<b>代价</b>。
         * <p>成功一次即清零：这是"偶发失败"与"根本做不到"的分界。
         */
        final Map<String, Integer> failStreak = new HashMap<>();

        /**
         * ★★ <b>卡死看门狗的状态</b>（委托方 2026-10-01 第 3 条要求）。
         *
         * <p>为什么还需要它（前三道保险丝各管一段，都不管"成功但没用"）：
         * <ul>
         *   <li>「无执行器」过滤 —— 管"压根没人实现"；</li>
         *   <li>连败退避 —— 管"<b>回报失败</b>的动作"；</li>
         *   <li>法术拉黑 / 引导硬上限 —— 管"抛异常 / 收不了尾"。</li>
         * </ul>
         * 而委托方实测的 {@code FlameStrikeSpell} 属于最坏的一种：<b>回报成功、照扣代价，
         * 但世界里什么都没发生</b>（活儿在 {@code startSpell} 里，我们没调）。
         * 这种"成功但没用"既不会增加连败、也不会抛异常 ⇒ <b>前三道全都不响</b>。
         * ⇒ 只能从<b>结果</b>上判：她在战斗，却在一段时间里<b>没有任何可观测变化</b>。
         */
        long lastProgressAt;

        /** 上一次的"进展指纹"（目标/自己/位置），变了就算有进展。 */
        String progressKey = "-";

        /** 连续判定为"卡住"的次数 —— 用它把嫌疑动作停放得越来越久。 */
        int stallStrikes;

        /** ★ 被临时停放的动作（动作 id → 解禁时刻）：停放期内它按"冷却中"被丢出候选集。 */
        final Map<String, Long> parkedUntil = new HashMap<>();

        /**
         * ★★ <b>调控顾问的状态</b>（每女仆一份统计）——
         * 它看的是"她最近的行为统计"，写的是全局旋钮（{@code TuningBus}）。
         * <p>★ 已知限制：旋钮是全局的，而统计是每女仆的 ⇒ 两个女仆会抢同一组旋钮。
         * 这是当前刻意的简化（见 docs/11 §5.1c）。
         */
        final com.touhoulittlemad.fightlikeplayer.compat.think.AdvisorBridge.State advisor =
                new com.touhoulittlemad.fightlikeplayer.compat.think.AdvisorBridge.State();

        /**
         * ★★ <b>LLM 动态指挥的状态</b>（每女仆一份）—— 与 {@link #advisor} <b>并列</b>。
         *
         * <p>委托方第十二轮的批评是对的：那是<b>两个不同的模型</b> ——
         * {@code advisor} 用 JEV（**打分**模型，问 score），
         * 这一条用 <b>chat 模型</b>（deepseek 等，读一段**可编辑的提示词**、写自然语言）。
         * ★ 本轮之前项目里<b>只有前者</b>，而委托方要的是后者。
         */
        final com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.State llm =
                new com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.State();

        /**
         * ★★ <b>这个女仆自己的旋钮总线</b>（父层 = 全局）—— 委托方要求「女仆之间的独立」。
         *
         * <p>语义：全局那份是<b>默认</b>（{@code /flp tune} 与模组配置写它）；
         * 本层是<b>这个女仆被单独调过的部分</b>（顾问按<b>她的</b>统计写它）。
         * 读取沿链向上找 ⇒ 没有单独调过时，全局改动立刻对她生效。
         */
        final com.touhoulittlemad.fightlikeplayer.decision.TuningBus tuning =
                com.touhoulittlemad.fightlikeplayer.decision.TuningBus.independent();

        Loops() {
            main = new DecisionCycle<>(CONFIG, space, "main",
                    // ★ 态势基线偏置 → 过一遍「风格」旋钮（bias.aggression / bias.caution）
                    //   ⇒ 这是"LLM 决定风格而不决定战斗"的落点之一：模型只改这两个数，
                    //     进攻/谨慎的倾向就整体变了，而单步决策仍由弹簧+最近邻做。
                    c -> tuning.styleBias(ContextBias.of(c.facts)),
                    PlayerLikeCombat::mainCandidates,
                    PlayerLikeCombat::executeMain,
                    () -> 0L, new Random())
                    // ★ 死区（弹簧回到原点）⇒ 做"默认的事"，而不是"什么都不做"
                    .withDefaultActions(PlayerLikeCombat::defaultActions)
                    // ★★ 承诺到期但执行器还在忙（引导类法术的长度是运行时才知道的）
                    //    ⇒ 别一边引导一边另开动作（有上限兜底，见 DecisionCycle#BUSY_WAIT_LIMIT）
                    .withBusySource(c -> ActionExecutors.isBusy(c.maid()))
                    // 第二十一轮：1.5 段（没有承诺时的门）用更窄的判据 ——
                    //   纯冷却不算她本人在忙（否则刚开完一枪她会站着等冷却，而不是换近战）。
                    .withActiveBusySource(c -> ActionExecutors.isActivelyBusy(c.maid()))
                    // ★★ 满足度走【这个女仆自己的】总线（委托方要求：女仆之间独立）
                    .withTuning(tuning)
                    // ★★ 第十六轮：把清单里的**连段关系**（comboDeps）接到选解器上。
                    //   委托方要的是「打一套 > 打一次」，而**不要复读** ——
                    //   规则的实现见 ActionSelector#chainAdjust（奖励声明后继 + 惩罚同族非后继）。
                    //   ★ 只有这一层有清单（CarrierResolver），所以接线在这里做一次。
                    .withComboPreds(id -> {
                        var res = CatalogHolder.resolver();
                        if (res == null) {
                            return java.util.List.<String>of();
                        }
                        var spec = res.specOf(id);
                        return spec == null ? java.util.List.<String>of() : spec.comboPreds();
                    });
            offhand = new DecisionCycle<>(CONFIG, space, "offhand",
                    c -> null,                        // 副手循环不自己产生偏置（共享同一个弹簧）
                    PlayerLikeCombat::offhandCandidates,
                    PlayerLikeCombat::executeOffhand,
                    () -> 0L, new Random())
                    .withBusySource(c -> ActionExecutors.isBusy(c.maid()))
                    // 第二十一轮：1.5 段（没有承诺时的门）用更窄的判据 ——
                    //   纯冷却不算她本人在忙（否则刚开完一枪她会站着等冷却，而不是换近战）。
                    .withActiveBusySource(c -> ActionExecutors.isActivelyBusy(c.maid()))
                    .withTuning(tuning);
        }
    }

    /**
     * ★★ <b>女仆此刻是否"武装"</b> —— W24 的修法。
     *
     * <p>旧判据是「主手物品有 {@code ATTACK_DAMAGE} 属性」，被用在
     * {@code StartAttacking.create(...)} 里 ⇒ <b>不满足就根本不找目标 ⇒ 任务等于没启动</b>。
     * 于是<b>持弓/持盾/持法杖/空手的女仆全都不会战斗</b>。
     *
     * <p>⇒ 改为<b>问决策层自己</b>：「我的候选集空不空？」
     * 这条修法的意义超出本 bug：它把"能不能战斗"的判据<b>从硬编码属性交给了动作层</b>
     * ⇒ 以后加新载体（Tetra / 新附属），任务条件<b>自动跟着变</b>（Q14 的精神）。
     */
    public static boolean isArmed(EntityMaid maid) {
        CarrierResolver r = CatalogHolder.resolver();
        if (r == null || r.actions().isEmpty()) {
            return false;                 // 清单未加载 ⇒ 不启动任务（宁可不打，也不乱打）
        }
        // ★★ 第二十四轮（性能，委托方报「一卡一卡的」）：
        //   **TLM 会拿这个方法当"这件东西是不是武器"**（`TaskPlayerLikeCombat#isWeapon`
        //   直接转发到这里，而 TLM 会对**她身上的每一件东西**问一次，且每 tick 都可能问）
        //   ⇒ 一次判定要做一遍完整快照 + 5 个自定义事实探针 + 2 次载体解析。
        //   ⇒ 结果按「物品清单指纹 + 1 秒」缓存：她换了装备立刻重算，没换就白拿。
        //   ★ 语义没变：这仍然回答"她现在有没有可做的动作"，只是最多晚 1 秒反映"刚捡到东西"。
        try {
            long fp = MaidSnapshot.itemFingerprint(maid);
            long now = maid.level().getGameTime();
            ArmedCache c = ARMED_CACHE.get(maid.getUUID());
            if (c != null && c.fingerprint == fp && now - c.at < ARMED_TTL) {
                return c.armed;
            }
            boolean armed = computeArmed(maid, r);
            ARMED_CACHE.put(maid.getUUID(), new ArmedCache(fp, now, armed));
            return armed;
        } catch (RuntimeException ex) {
            FightLikePlayer.LOGGER.error("[FLP] isArmed 判定异常，按未武装处理", ex);
            return false;
        }
    }

    /** {@link #isArmed} 的缓存项。 */
    private record ArmedCache(long fingerprint, long at, boolean armed) {
    }

    /** 主手那件的 {@link ItemRef}（空手 ⇒ {@code null}）—— 与 tick 里的"手"口径一致。 */
    private static ItemRef mainHandRef(MaidSnapshot.Carried carried) {
        for (var it : carried.items()) {
            if (it.slot() == com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.MAINHAND) {
                return ItemRef.of(it);
            }
        }
        return null;
    }

    /**
     * ★★ <b>回退用的"含指令过滤的攻击候选"探测</b>（可以用它做冲突体检）：</b>
     *
     * <p>回答的是"**按当前全部指令过滤之后，还剩几个能打的动作**"（`MELEE`/`RANGED`/`SPELL`）。
     * 委托方那次「枪械用不出来」的过程是：`close_in`（llm）禁掉远程 + `only_item`（chat）禁掉别的一切
     * ⇒ 只剩 `MOVE`（撤退）⇒ 看起来"她只会跑"。
     * ⇒ 这个方法就是"组合体检"的判据（只在**下达指令那一刻**调用，不是每 tick）。
     *
     * <p>★★ <b>三种"不知道"必须与"真的没有"分开</b>（否则会**误撤**指令）：
     * <ul>
     *   <li>清单没加载 / 计算抛异常 ⇒ 返回 {@code null}（**未知** ⇒ 调用方跳过体检）；</li>
     *   <li>她**当前没有目标** ⇒ 也返回 {@code null}：此时"没有攻击候选"是正常的
     *       （攻击动作多半要求"有目标"），不能当成冲突；</li>
     *   <li>只有"她在打架、却一个能打的候选都不剩"才是**真冲突**。</li>
     * </ul>
     *
     * @return 能打的候选 id；{@code null} = 判不出来（别据此撤任何指令）
     */
    public static List<String> attackCandidatesLeft(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        try {
            CarrierResolver r = CatalogHolder.resolver();
            if (r == null || r.actions().isEmpty()) {
                return null;
            }
            LivingEntity target = maid.getTarget();
            if (target == null || !target.isAlive()) {
                return null;                 // 没在打架 ⇒ "没有攻击动作"是正常的
            }
            var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(maid);
            MaidSnapshot.Carried carried = MaidSnapshot.carried(maid);
            LoopSplit split = LoopSplit.of(carried.items());
            ContextFacts facts = MaidSnapshot.facts(maid, loadedMods(), Set.of(), customFacts(maid));
            var res = r.resolve(split.mainItems(), facts, CONFIG);
            String want = bus.has("only_item") ? bus.textParam("only_item", "item")
                    : bus.textParam("ban_item", "item");
            PossessedItem wanted = null;
            if (want != null) {
                for (var it : carried.items()) {
                    if (ItemRef.of(it).matches(want)) {
                        wanted = it;
                        break;
                    }
                }
            }
            final PossessedItem wantItem = wanted;     // ★ lambda 里要用 ⇒ 必须 effectively final
            var plan = DirectiveFilter.itemPlan(bus, res.candidates().stream()
                            .map(CandidateAction::id).toList(),
                    id -> {
                        var spec = r.specOf(id);
                        com.touhoulittlemad.fightlikeplayer.carrier.CarrierRule rule =
                                spec == null ? null : r.ruleOf(spec.carrier());
                        if (rule != null && !rule.isAlways() && !rule.isUnresolved()
                                && wantItem != null && rule.matches(wantItem)) {
                            return ItemRef.of(wantItem);
                        }
                        // ★ 与 tick 里的 itemFilter 同一条口径：退回"解析器记录的证据物品"
                        var ev = res.evidenceOf(id);
                        return ev == null ? null : ItemRef.of(ev);
                    },
                    mainHandRef(carried), want != null && wantItem != null);
            Set<String> allowedByPlan = Set.copyOf(plan.allowed());
            for (var a : res.candidates()) {
                if (!DirectiveFilter.allowed(bus, a.id())) {
                    continue;
                }
                if (!allowedByPlan.contains(a.id())) {
                    continue;
                }
                DirectiveFilter.ActionClass c = DirectiveFilter.classify(a.id());
                if (c == DirectiveFilter.ActionClass.MELEE || c == DirectiveFilter.ActionClass.RANGED
                        || c == DirectiveFilter.ActionClass.SPELL) {
                    out.add(a.id());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            FightLikePlayer.LOGGER.debug("[FLP] 冲突体检失败（忽略）：{}", e.toString());
        }
        return out;
    }

    /** 女仆 → isArmed 缓存。 */
    private static final java.util.Map<java.util.UUID, ArmedCache> ARMED_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** isArmed 的缓存时长（tick）：物品没变时最多 1 秒重算一次。 */
    private static final long ARMED_TTL = 20;

    private static boolean computeArmed(EntityMaid maid, CarrierResolver r) {
        // ★ 必须与 tick 里用同一个事实集 —— 否则会出现"任务启动了但一个动作都没有"
        //   （或反过来："有动作但任务不启动"）这类极难查的不一致。
        ContextFacts facts = MaidSnapshot.facts(maid, loadedMods(), Set.of(), customFacts(maid));
        List<PossessedItem> main = LoopSplit.of(MaidSnapshot.possessed(maid)).mainItems();
        return !r.resolve(main, facts, CONFIG).candidates().isEmpty();
    }

    /**
     * 决策上下文 —— <b>一次 tick 内共享的、已翻译好的世界状态</b>。
     *
     * <p>★ 持有物只枚举一次、<b>载体解析也只做一次</b>（两个循环各一次）：
     * 旧实现里 {@link MaidSnapshot#possessed} 与 {@code resolve} 在一次 tick 内被调用 3~4 次
     * （主候选、副候选、默认动作各一次）。
     *
     * <p>★★ 同时把 {@link CarrierResolver.Resolution} 带进上下文 ——
     * 执行器的<b>换手前置</b>要问"这个动作当初是哪件物品让它可用的"
     * （见 {@code ActionExecutors#ensureCarrierInHand}）⇒
     * <b>执行路径必须与候选集来自同一次解析</b>，否则两者可能对不上。
     */
    private record Ctx(EntityMaid maid, ContextFacts facts, List<PossessedItem> possessed,
                       String targetKey, long gameTime,
                       CarrierResolver.Resolution mainResolution,
                       CarrierResolver.Resolution offhandResolution,
                       /**
                        * ★★ 当前需求向量（弹簧点）—— 执行器要用它来在"一个动作对应多个具体法术"时挑一个。
                        * <p>见 {@code ActionExecutors#execute(..., need)} 的说明：不传它就会出现
                        * "永远只用法术书的第一个法术"（委托方 2026-10-01 观察）。
                        */
                       com.touhoulittlemad.fightlikeplayer.decision.NeedVector need) {
    }

    // ───────────────────────── 主入口 ─────────────────────────

    /**
     * 构造一个可挂到 brain 上的<b>每 tick 行为</b>。
     *
     * <p>与 TLM 的 {@code MaidMeleeAttack.create(20)} 同构（{@code OneShot} + {@code BehaviorBuilder}），
     * 但内部替换为我们自己的决策循环。
     */
    public static OneShot<EntityMaid> create() {
        return BehaviorBuilder.create(ctx -> ctx.group(
                ctx.registered(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET),
                ctx.present(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
        ).apply(ctx, (lookTarget, attackTarget) -> (level, maid, gameTime) -> tick(level, maid, gameTime)));
    }

    /** 每 tick 的决策 + 执行。 */
    public static boolean tick(ServerLevel level, EntityMaid maid, long gameTime) {
        CarrierResolver resolver = CatalogHolder.resolver();
        if (resolver == null || resolver.actions().isEmpty()) {
            return false;   // 清单未加载 ⇒ 什么都不做（绝不用空表乱决策）
        }

        Loops loops = STATE.computeIfAbsent(maid.getUUID(), k -> new Loops());

        // ── 0. 在途动作（蓄力射击 / 枪械弹匣 / 引导法术）的推进 ──
        //   ★★ 第十七轮续：**推进本身已搬进 `DirectiveTicker`**（挂 MaidTickEvent、与战斗无关）——
        //     原来它挂在本行为上，而本行为门控在"她有攻击目标"上 ⇒
        //     **目标一没，在途动作就不再前进**（法术不结算、弹匣不再开火，观感"卡 2 秒"）。
        //   ★ 这里只负责**消化完成信号**（承诺门在本类里）：驱动器报完成后调
        //     {@link #noteInFlightCompleted(EntityMaid)}，本方法看门是否已经打开。
        //   ⚠️ 绝不能再在这里调 tickInFlight（会每 tick 推进两次 ⇒ 射速翻倍）。

        // ── 0b. ★★ 生存轴的动态忘却（委托方机制 ②）：血越满，暴露/生存忘得越快 ──
        //   这是"另一个逻辑指挥它们"的落点：我们只**发布一个影响器**，
        //   由 DecisionCycle 在自己的周期里 drain（与态势偏置、动作扣减同一套机制）。
        double healthPct = maid.getMaxHealth() <= 0 ? 1.0 : maid.getHealth() / maid.getMaxHealth();
        loops.space.publish(SpringImpact.extraDecay(CONFIG,
                axis -> SpringDynamics.extraDecayOf(axis, healthPct), "dynamic:forget"));
        loops.space.drain(CONFIG);

        // ── 0c. ★★ 指令总线（第十三轮，docs/15 的 M1/M2）──
        //   ★★ 第十七轮续：**这里不再执行瞬间指令**（原来在这里，是个 bug）——
        //     本行为被脑门控在"她有攻击目标"上（见 create()），
        //     ⇒ 玩家在她没打架时说「处死仆从 / 把枪拿出来」时，那一刻指令**永远不会被执行**，
        //       60 tick 后被判"放太久"作废。⇒ 现在搬到 `DirectiveTicker`（挂 MaidTickEvent，
        //       与战斗无关），并且"把指令要的东西换到手上"也一起搬过去了（`DirectiveHolder#itemServo`）。

        // ── 0d. ★★ 死目标不是目标（第十二轮：委托方第③条的另一半）──
        //   委托方实测：「敌人死完了腐化聚晶并没有马上消失……只要不主动切换聚晶并发动下一次攻击，
        //   不论换成什么模式，腐化光束都会一直进行（真的有伤害）」。
        //   ★ 根因不是"光束不会消失"（那一条第十一轮已经用不变量清扫修了），而是
        //     **她的 ATTACK_TARGET 记忆还指着那具尸体** ⇒ 我们的行为持续运行
        //     ⇒ 她一轮又一轮地重新起手引导 ⇒ `isChanneling` 几乎恒为真
        //     ⇒ 清扫的判据"没在引导就清掉"永远不成立 ⇒ 光束被"合法地"一直留着。
        //   ⇒ 判据补在最上游：目标死了就把它从记忆里抹掉（TLM 自己也这么干，见 EntityMaid:2764）。
        //     抹掉之后：行为不再运行、引导自然结束、清扫随即收掉光束。
        clearDeadTarget(maid, gameTime);

        // ── 0d. ★★ 卡死看门狗（委托方第 3 条）：判"她在战斗但世界毫无变化" ──
        //    ★ 位置刻意在【候选解析之前】：判定的后果是"把嫌疑动作停放"，
        //      而停放要经过解析期的冷却通路才能生效 ⇒ 必须赶在本 tick 解析之前做。
        tickWatchdog(loops, maid, gameTime);

        // ── 1. 翻译世界状态（★ 持有物只枚举一次、解析也只做一次）──
        List<PossessedItem> possessed = MaidSnapshot.possessed(maid);
        LoopSplit split = LoopSplit.of(possessed);
        Set<String> cooling = coolingDown(loops, resolver, gameTime);
        loops.lastCooling = cooling;
        ContextFacts facts = MaidSnapshot.facts(maid, loadedMods(), cooling, customFacts(maid));
        // ★★ 第十七轮续（委托方第 1 条）：脱战判据要的"最后一次有仇恨对象"。
        //   在这里更新是因为 facts 刚算好（hasTarget 是权威口径）。
        if (facts.hasTarget()) {
            loops.lastTargetTick = gameTime;
        }

        // ★ 两个循环各解析一次，结果进 Ctx ⇒ 候选集 / 默认动作 / 换手前置共用同一份
        CarrierResolver.Resolution mainRes = resolver.resolve(split.mainItems(), facts, CONFIG);
        CarrierResolver.Resolution offhandRes = split.offhandItems().isEmpty()
                ? CarrierResolver.Resolution.empty()
                : resolver.resolve(split.offhandItems(), facts, CONFIG);
        loops.lastMain = mainRes;                 // ★ 诊断：`/flp why` 要看"候选与丢弃原因"
        Ctx c = new Ctx(maid, facts, possessed, targetKey(maid), gameTime, mainRes, offhandRes,
                loops.space.point());

        // ── 1b. ★★ 思维层（短周期 JEV）：慢变量，绝不阻塞 ──
        //   ★ 位置刻意在"主循环之前"：本 tick 取到的判定能在【同一个 tick】被主循环 drain 掉，
        //     否则要白等一拍。整个调用【立刻返回】—— 网络在别的线程上跑（见 Thinker 的线程模型）。
        com.touhoulittlemad.fightlikeplayer.compat.think.ThinkBridge.tick(
                loops.thinker, maid, facts,
                mainRes.candidates().stream().map(CandidateAction::id).toList(),
                loops.main.lastChosenId(), loops.lastGait, gameTime,
                (impact, outcome) -> {
                    loops.space.publish(impact);
                    loops.lastThink = outcome;
                });

        // ── 1c. ★★ 调控顾问（JEV 打分模型：把旋钮变成 score 问题）──
        //   与 1b 的区别：1b 是秒级的"该往哪使劲"，这里是十秒~分钟级的"她该是什么脾气"。
        double hpPct = maid.getMaxHealth() <= 0 ? 1.0 : maid.getHealth() / maid.getMaxHealth();
        com.touhoulittlemad.fightlikeplayer.compat.think.AdvisorBridge.tick(
                loops.advisor, loops.tuning, maid, loops.space.point(), facts.hasTarget(),
                hpPct, gameTime);

        // ── 1d. ★★ LLM 动态指挥（chat 模型：可编辑提示词 + 自然语言回复）──
        //   ★ 与 1c **并列且独立**：1c 用 JEV（打分模型），这里用 chat 模型（deepseek 等）；
        //     两者的 key/端点/开关都是**分开的两份配置**。
        //   ★ 同样只写 TuningBus（风格旋钮），绝不决定"这一步做什么"。
        com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.tick(
                loops.llm, loops.tuning, maid, loops.space.point(), facts.hasTarget(),
                hpPct, gameTime);

        // ── 2. 主循环 ──
        boolean acted = loops.main.tick(c, gameTime);
        drainLog(loops.main, maid);

        // ── 3. 副手循环 ──
        loops.offhand.tick(c, gameTime);
        drainLog(loops.offhand, maid);

        // ── 4. ★ 步法（并发槽位）：动作层管手上，步法层管脚下 ──
        tickGait(loops, c, resolver, gameTime);

        // ── 4b/4c. ★★ 副手姿态伺服（举盾）与灭火器伺服 ──
        //   ★★ 第十七轮续：这两个伺服**已搬到 `DirectiveTicker`**（挂 MaidTickEvent，每 tick、与战斗无关）。
        //   理由：它们都是"状态"（举盾 / 灭火），而她**不打架的时候也必须维持**——
        //   跟着你跑的时候着火、或者你喊"举盾"时她刚好没目标，都曾经因此完全没反应。
        //   ★ 判据见 docs/13 第 77 条：**"她不打架的时候，这个东西还会跑吗？"**

        // ── 5. ★ 兜底收尾：承诺结束 ⇒ 弓放箭 / 盾收手 ──
        //    正常路径已由第 0 步的 notifyCompleted 处理；这里防"执行器漏报"导致永久半拉弓。
        if (loops.pendingFinish != null && !loops.main.isCommitted()) {
            ActionExecutors.finish(maid, loops.pendingFinish);
            loops.pendingFinish = null;
        }

        return acted;
    }

    // ───────────────────────── 候选集 ─────────────────────────
    //  ★★ 三者都直接读 Ctx 里的解析结果 —— 一次 tick 只解析一次（旧实现解析 3~4 次）。

    /** 主循环的候选集：持有物减去副手槽。 */
    private static List<CandidateAction> mainCandidates(Ctx c) {
        return applyDirectives(c, c.mainResolution().candidates(), "main");
    }

    /**
     * ★★ <b>指令过滤</b>（M2 的落点 ①）：把被指令挡下的动作从候选集里拿掉。
     *
     * <h2>为什么在"决策之前"过滤，而不是"选中之后拒绝"</h2>
     * 本项目已经为这件事交过三次学费（docs/13 第 22/25/26 条）：
     * <b>凡"会被选中但注定做不出来/不该做"的东西留在候选集里，就会永久占住决策</b>。
     * ⇒ 指令的作用方式必须是"让决策层看不见它们"，而不是"选到了再说不行"。
     *
     * <p>★ 被挡下多少条**必须可见**（否则就是"她为什么不用魔法了"这种查不出来的现象）：
     * 这里把计数与 id 写进内存环形缓冲（`/flp whyfull` 可见），并在"数量变化"时打一条日志。
     */
    private static List<CandidateAction> applyDirectives(Ctx c, List<CandidateAction> in,
                                                         String loop) {
        DirectiveBus bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(c.maid());
        if (bus == null || bus.activeCount() == 0) {
            return in;
        }
        List<CandidateAction> out = new ArrayList<>(in.size());
        List<String> blocked = new ArrayList<>();
        for (CandidateAction a : in) {
            if (DirectiveFilter.allowed(bus, a.id())) {
                out.add(a);
            } else {
                blocked.add(a.id());
            }
        }
        // ★★ 第二十一轮（委托方：细分"只用铁魔法"）：**说出是哪条指令挡的**。
        //   原来只记 "goety:cast_focus 被挡下" ⇒ 读日志的人分不清是 `magic_only`
        //   还是 `irons_only` 挡的 —— 而这两条的解释完全不同（前者=她的法术被禁了，
        //   后者=她该去用另一系法术）。这正是委托方上一轮反复问的那类现象。
        if (DirectiveFilter.hasSpellSystemDirective(bus) && !blocked.isEmpty()) {
            blocked.add(0, (bus.has("goety_only") ? "goety_only" : "irons_only")
                    + " 生效 ⇒ 只放行" + (bus.has("goety_only") ? "诡厄巫法" : "铁魔法"));
        }

        // ── ★★ 第二轮：物品类指令（半命题：only_item / ban_item）──
        //   判据本体在纯逻辑层 DirectiveFilter.itemGate；这里只负责**把两趟跑对**。
        //   ★ 为什么要分两趟：「禁用 X」里有一条"X 正在主手 ⇒ 连不依赖物品的挥击也挡下"，
        //     而这条**只有在挡下之后还有别的动作可做时**才允许生效 ——
        //     否则候选集会被清空 ⇒ 她什么都不做 ⇒ 正是本项目最怕的那种静默卡死
        //     （docs/13 第 26 条）。⇒ 第一趟不带那条规则，第二趟带上；第二趟若把集合清空**就退回第一趟**。
        CarrierResolver.Resolution res = mainLoopResolution(c, loop);
        boolean mainLoop = "main".equals(loop);
        // ★★ 第十七轮续（委托方："有些攻击可以绕过指令"）：
        //   物品过滤原来**只在主循环跑**（当时的理由："只有主循环才有主手物品这个概念"）
        //   ⇒ 副手上的刀/盾类动作**绕过** `only_item` —— 这就是"指定枪械时她还会拔刀砍一下"
        //     的三条真旁路之一。⇒ 现在**两个循环都跑**（副手用副手物品当"手"）。
        if (DirectiveFilter.hasItemDirective(bus)) {
            PossessedItem mainItem = findPossessed(c, mainLoop
                    ? com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.MAINHAND
                    : com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.OFFHAND);
            String want = bus.has("only_item") ? bus.textParam("only_item", "item")
                    : bus.textParam("ban_item", "item");
            // ★ "她要的东西在她身上吗（任何位置）" —— 纯逻辑层查不到，必须由这里喂
            PossessedItem wantItem = findItemMatching(c, want);
            // ★★★ 第十七轮续（委托方：「指令下她只拿着枪发呆」）：
            //   过滤要问的是「**她要的那件能不能承载这个动作**」，而不是
            //   「解析器恰好把哪件记成了证据」—— 同一载体的多件物品里，证据**只留一件**
            //   （按槽位优先级），于是完全可能出现"她手上正是她要的那把枪，
            //   而证据落在另一把枪上 ⇒ 动作被挡 ⇒ 候选集空 ⇒ 发呆"。
            CarrierResolver loopResolver = CatalogHolder.resolver();
            var wantRef = ItemRef.of(wantItem);
            java.util.function.Function<String, ItemRef> carrierOf = id -> {
                if (wantRef != null && loopResolver != null) {
                    var spec = loopResolver.specOf(id);
                    var rule = spec == null ? null : loopResolver.ruleOf(spec.carrier());
                    if (rule != null && !rule.isAlways() && !rule.isUnresolved()
                            && wantItem != null && rule.matches(wantItem)) {
                        return wantRef;         // ★ 她要的那件确实能承载 ⇒ 以它为准
                    }
                }
                return ItemRef.of(res.evidenceOf(id));
            };
            var plan = DirectiveFilter.itemPlan(bus, ids(out), carrierOf,
                    ItemRef.of(mainItem), wantItem != null);
            out = keepById(out, plan.allowed());
            // ★★ 第十七轮续：**在途动作与指令冲突 ⇒ 立刻中止**（"应激反应保持静默"）。
            //   在途 = 连段 / 引导法术 / 弹匣 / 蓄力射击 —— 它们跨 tick，若不中止就会"做完"，
            //   观感正是"指定了枪械她还砍了一下 / 放了一颗聚晶"。
            abortInFlightIfForbidden(c, loopResolver, wantItem);
            if (mainLoop && plan.status() == DirectiveFilter.ItemStatus.NEED_HAND
                    && wantItem != null && mainItem != wantItem) {
                // ★★ 换手**不在这里做**（第十七轮续）：那个行为只在她有攻击目标时运行
                //   ⇒ 非战斗时"拿出来"永远不发生。现在由 `DirectiveHolder#itemServo`
                //   （挂 MaidTickEvent，每 tick、与战斗无关）负责，**同一个东西只留一个主人**。
                noteOnce(c, loop, "[directive] " + plan.why() + "（换手由指令伺服负责）");
            } else if (!plan.why().isBlank()) {
                noteOnce(c, loop, "[directive] " + plan.why());
            }
            if (plan.status() == DirectiveFilter.ItemStatus.OK && !plan.want().isBlank()) {
                blocked.add("物品指令生效：" + plan.want());
            }
        }

        // ── ★★ 第三轮：动作白名单 only_actions（"接下来几秒只做这些动作"）──
        //   ★ 同样有"不许清空候选集"的护栏：白名单一个都匹配不上 ⇒ 回退 + 说清原因。
        if (bus.has("only_actions")) {
            List<String> omitted = new ArrayList<>();
            List<CandidateAction> listed = new ArrayList<>();
            for (CandidateAction a : out) {
                if (DirectiveFilter.inActionWhitelist(bus, a.id())) {
                    listed.add(a);
                } else {
                    omitted.add(a.id());
                }
            }
            if (listed.isEmpty()) {
                noteOnce(c, loop, "[directive] only_actions 里的动作她现在一个都做不了 ⇒ 这条暂时不生效"
                        + "（白名单=" + String.join(",", DirectiveFilter.actionWhitelist(bus)) + "）");
            } else {
                out = listed;
                if (!omitted.isEmpty()) {
                    blocked.add("only_actions 挡下 " + omitted.size() + " 条");
                }
            }
        }

        if (!blocked.isEmpty()) {
            Loops loops = STATE.get(c.maid().getUUID());
            if (loops != null) {
                String line = "[directive] " + loop + " 被指令挡下 " + blocked.size()
                        + " 条：" + String.join(",", blocked);
                // ★ 只在"内容变了"时记录（否则每 tick 一条会刷爆环形缓冲）
                if (!line.equals(loops.lastDirectiveNote)) {
                    loops.lastDirectiveNote = line;
                    pushResult(c.maid(), line);
                    FightLikePlayer.LOGGER.debug("[FLP] {}", line);
                }
            }
        }
        return out;
    }

    /**
     * ★ 一趟物品类过滤：逐个候选问 {@code itemGate}，把"靠哪件物品才可用"从
     * {@link CarrierResolver.Resolution#evidenceOf} 取出来。
     *
     * @param blockMainHandWielder 见 {@link DirectiveFilter#itemGate} 的同名参数（第二趟才 true）
     * @param droppedInto          {@code null} = 这一趟不登记原因（第二趟只是"试探"）
     */
    private static List<CandidateAction> itemFilter(DirectiveBus bus,
                                                    CarrierResolver.Resolution res,
                                                    ItemRef mainHand, List<CandidateAction> in,
                                                    boolean blockMainHandWielder,
                                                    List<String> droppedInto) {
        List<CandidateAction> out = new ArrayList<>(in.size());
        for (CandidateAction a : in) {
            ItemRef carrier = ItemRef.of(res.evidenceOf(a.id()));
            String why = DirectiveFilter.itemGate(bus, carrier, mainHand, blockMainHandWielder);
            if (why == null) {
                out.add(a);
            } else if (droppedInto != null) {
                droppedInto.add(a.id() + "(" + why + ")");
            }
        }
        return out;
    }

    /** 候选集 → id 列表（纯逻辑层的整表判据只吃字符串）。 */
    private static List<String> ids(List<CandidateAction> in) {
        List<String> out = new ArrayList<>(in.size());
        for (CandidateAction a : in) {
            out.add(a.id());
        }
        return out;
    }

    /** 按 id 白名单保留（保持原顺序）。 */
    private static List<CandidateAction> keepById(List<CandidateAction> in, List<String> allowed) {
        java.util.Set<String> keep = new java.util.HashSet<>(allowed);
        List<CandidateAction> out = new ArrayList<>(in.size());
        for (CandidateAction a : in) {
            if (keep.contains(a.id())) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * 在她身上（任何位置）找**最匹配**这个查询串的那一件（{@code null} = 没有）。
     *
     * <p>★★ 第十七轮：「第一件匹配的」改成「**最强的**那一件」——
     * 判据分级见 {@link com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery}。
     * 例：她说 {@code rpg}，而身上既有 {@code tacz:rpg7}（LOOSE 命中）
     * 又有别的名字里带 rpg 的东西 ⇒ 取**最强**的那件；强度并列时按她身上的自然顺序
     * （主手在最前）⇒ 与"我正在用手上这把"一致。
     * ★ 含糊**不静默**：下达那一刻的回话会把"还匹配到哪些"一并说清
     * （{@code DirectiveHolder#feasibility}），所以这里不必再刷日志（本方法每 tick 都会跑）。
     */
    private static PossessedItem findItemMatching(Ctx c, String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        PossessedItem best = null;
        int bestRank = -1;
        for (PossessedItem it : c.possessed()) {
            var m = com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery
                    .strength(ItemRef.of(it), query);
            if (m.ordinal() > bestRank) {
                bestRank = m.ordinal();
                best = it;
            }
        }
        return bestRank <= 0 ? null : best;
    }

    // ★★ 第十七轮续：原来这里有一个 `equipToHand(maid, itemId)` ——
    //   已删除并搬进 `DirectiveHolder#itemServo`（同一个东西只留一个主人）。
    //   它当年的存在理由（破 only_item 的死锁）现在由伺服每 tick 满足，而且**不依赖她在战斗**。

    /** ★ 只在"内容变了"时记一条（否则每 tick 一条会刷爆环形缓冲）。 */
    private static void noteOnce(Ctx c, String loop, String line) {
        Loops loops = STATE.get(c.maid().getUUID());
        if (loops == null) {
            return;
        }
        String full = line + "　[" + loop + "]";
        if (!full.equals(loops.lastDirectiveNote)) {
            loops.lastDirectiveNote = full;
            pushResult(c.maid(), full);
            FightLikePlayer.LOGGER.info("[FLP] {}", full);
        }
    }

    /** 在她持有的物品里找某个槽位的那一件（找不到 ⇒ null）。 */
    private static PossessedItem findPossessed(Ctx c, com.touhoulittlemad.fightlikeplayer.carrier
            .SlotKind slot) {
        for (PossessedItem it : c.possessed()) {
            if (it.slot() == slot) {
                return it;
            }
        }
        return null;
    }

    /** 按循环名取解析结果（主/副手共用一套过滤逻辑）。 */
    private static CarrierResolver.Resolution mainLoopResolution(Ctx c, String loop) {
        return "main".equals(loop) ? c.mainResolution() : c.offhandResolution();
    }

    /**
     * ★★ <b>在途动作与指令冲突 ⇒ 立刻中止</b>（第十七轮续，委托方："应激反应保持静默"）。
     *
     * <p>判据两条（任一不满足就中止）：
     * <ol>
     *   <li><b>类别</b>：这条动作现在还被指令允许吗（{@code DirectiveFilter#allowed}）——
     *       例如 `magic_only` 生效时她正在开枪；</li>
     *   <li><b>物品</b>：它的**载体规则**能否由"她要的那件东西"满足 ——
     *       例如 `only_item(枪)` 生效时她正在放刀技/引导法术。</li>
     * </ol>
     * ★ 与 `interrupt` 指令同义：**指令优先**，在途的那一套立刻收（连段/引导/弹匣都会收）。
     * ★ 为什么不"让它做完"：委托方要的就是**静默** —— "做完"看起来就是"绕过了指令"。
     */
    private static void abortInFlightIfForbidden(Ctx c,
                                                 CarrierResolver resolver, PossessedItem wantItem) {
        String inFlight = com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors
                .inFlightActionId(c.maid());
        if (inFlight == null) {
            return;
        }
        var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(c.maid());
        // ① 类别：这条动作现在还允许吗
        if (!DirectiveFilter.allowed(bus, inFlight)) {
            FightLikePlayer.LOGGER.info("[FLP][directive] 在途动作 {} 已被类别指令禁止 ⇒ 立刻中止",
                    inFlight);
            com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors.abort(c.maid());
            return;
        }
        // ② 物品：它的载体能不能由"她要的那件"满足
        if (wantItem != null && resolver != null) {
            var spec = resolver.specOf(inFlight);
            var rule = spec == null ? null : resolver.ruleOf(spec.carrier());
            if (rule != null && !rule.isAlways() && !rule.isUnresolved()
                    && !rule.matches(wantItem)) {
                FightLikePlayer.LOGGER.info("[FLP][directive] 在途动作 {} 的物品不符合"
                        + "「只用 {}」⇒ 立刻中止（不等它做完）", inFlight,
                        wantItem.itemId());
                com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors.abort(c.maid());
            }
        }
    }

    /** 副手循环的候选集：只有副手槽。 */
    private static List<CandidateAction> offhandCandidates(Ctx c) {
        return applyDirectives(c, c.offhandResolution().candidates(), "offhand");
    }

    /**
     * ★ <b>死区（弹簧回到原点）时的"默认动作"</b> —— "没特别想做的，就砍眼前这一刀"。
     *
     * <p>⚠️ 在此之前 {@code withDefaultActions} <b>从未被调用</b> ⇒ 死区时候选为空 ⇒
     * "什么都不做"。这在实战里表现为<b>「不注入任何向量时女仆不动」</b>。
     *
     * <p>判据刻意保守：<b>只有在"有目标 且 已进入近战距离"时才默认挥击</b>，
     * 否则返回空（交给步法 —— 她仍会按伺服规则维持站位）。
     */
    /**
     * ★★ <b>死区时的"默认动作"</b>（弹簧回到原点 ⇒ "没什么特别想做的，就砍眼前这一刀"）。
     *
     * <h2>★★ 第十六轮修的真 bug（委托方：「指定只使用……后，女仆仍然可以做出动作，很神奇」）</h2>
     * 这里此前**直接读原始解析结果** {@code c.mainResolution().candidates()}，
     * <b>完全绕过了指令过滤</b>（`only_item` / `only_actions` / `magic_only` … 一个都没经过）
     * ⇒ 只要弹簧回到死区，她就照样挥刀 —— **观感就是"限制没用，她还能动"**。
     *
     * <p>★ 这与我们给"唯一写入者"立的规矩是同一条：**任何产出动作的路径都必须过同一套过滤**，
     * 否则那条路就是指令系统的一个后门（docs/13 第 56 条：同一件事有几个位置，我算全了吗）。
     * ⇒ 现在改成走 {@code mainCandidates(c)}（= 已经过完整指令过滤的那一份）。
     */
    private static List<CandidateAction> defaultActions(Ctx c) {
        if (!c.facts().hasTarget() || !c.facts().targetInMeleeRange()) {
            return List.of();
        }
        for (CandidateAction a : mainCandidates(c)) {
            if ("maid_native:melee_swing".equals(a.id())) {
                return List.of(a);
            }
        }
        return List.of();
    }

    // ───────────────────────── 执行 ─────────────────────────

    /**
     * ★★ 执行主循环的动作，并<b>回报"有没有真的做出来"</b>（返回给 {@code DecisionCycle}）。
     *
     * <p>返回 {@code false} ⇒ 决策层<b>不扣代价</b>、不进入承诺。这正是"物品不在手上、
     * 换手也失败"这类情况的正确语义：<b>什么都没发生，就不该付费。</b>
     */
    private static boolean executeMain(CandidateAction action, Ctx c) {
        CarrierResolver resolver = CatalogHolder.resolver();
        ActionExecutors.Result r = ActionExecutors.execute(c.maid(), action, c.gameTime(), resolver,
                com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.MAINHAND, c.need());
        Loops loops = STATE.computeIfAbsent(c.maid().getUUID(), k -> new Loops());
        // ★ 记冷却（无论成败：失败也等一拍，避免"失败即重试"的抖动）
        loops.cooldowns.markUsed(action.id(), c.gameTime());
        boolean done = r == ActionExecutors.Result.OK;
        // ★ 记下这个动作，等承诺结束时兜底收尾
        if (done && action.commitmentTicks() > 0) {
            loops.pendingFinish = action;
        }
        logResult(c.maid(), "main", action, r);
        // ★★ 连败计数：失败 ⇒ +1（冷却随之放大）；成功 ⇒ 清零
        noteOutcome(loops, action.id(), done);
        whitelistWatch(c, done);
        return done;
    }

    /**
     * ★ 记录一次成败，维护 {@link Loops#failStreak}。
     * <p>刻意只动"时间"（冷却），不动"代价" —— 见 {@code failStreak} 的说明。
     */
    /** \u2605 \u767d\u540d\u5355\u628a\u5979\u5361\u4f4f\u591a\u4e45\u4e4b\u540e\u81ea\u52a8\u89e3\u9664\uff08tick\uff09\u30022 \u79d2\u3002 */
    private static final long WHITELIST_STUCK_TICKS = 40;

    /** \u6bcf\u4e2a\u5973\u4ec6\uff1a\u767d\u540d\u5355\u671f\u95f4\u300c\u8fde\u7eed\u591a\u5c11\u6b21\u6ca1\u505a\u51fa\u6765\u300d\u3002 */
    private static final java.util.Map<java.util.UUID, int[]> WHITELIST_WATCH =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * \u2605\u2605 \u767d\u540d\u5355\u770b\u95e8\u72d7\uff08\u7b2c\u5341\u516d\u8f6e\uff1a\u59d4\u6258\u65b9\u5b9e\u6d4b\u300c\u6211\u53ea\u8ba9\u5979\u7528\u5e7b\u5f71\u5251\uff0c\u5979\u5c31\u4e0d\u8fdb\u884c\u6218\u6597\u884c\u4e3a\u4e86\u300d\uff09\u3002
     *
     * <p>\u75c5\u7076\uff1a{@code only_actions} \u628a\u5019\u9009\u96c6\u6536\u7a84\u6210\u4e00\u6761\u5979\u6b64\u523b\u505a\u4e0d\u51fa\u6765\u7684\u52a8\u4f5c
     * \uff08\u524d\u7f6e\u4e0d\u6ee1\u8db3 / \u5728\u51b7\u5374 / \u88ab\u5728\u9014\u52a8\u4f5c\u6321\u7740\uff09\u21d2 \u5979\u4e00\u76f4\u53ea\u9009\u5b83\u3001\u4ec0\u4e48\u90fd\u505a\u4e0d\u51fa\u6765\uff0c
     * \u800c\u4e14\u6ca1\u6709\u63d0\u793a\u3001\u4e5f\u4e0d\u4f1a\u81ea\u6108 \u2014\u2014 \u76f4\u5230 TTL \u5230\u671f\u3002
     *
     * <p>\u5224\u636e\u7528**\u6267\u884c\u5668\u5230\u5e95\u505a\u51fa\u6765\u4e86\u6ca1\u6709**\uff08{@code done}\uff09\uff0c\u4e0d\u662f\u201c\u9009\u4e2d\u4e86\u4ec0\u4e48\u201d\u3002
     * \u8fde\u7eed {@link #WHITELIST_STUCK_TICKS} \u6b21\u6ca1\u505a\u51fa\u6765 \u21d2 **\u81ea\u52a8\u89e3\u9664**\u8fd9\u6761\u6307\u4ee4 + \u53ef\u8bfb\u65e5\u5fd7\u3002
     * \u2605 \u4e0e\u201cTTL \u4e0a\u9650\u201d\u540c\u4e00\u6761\u7eaa\u5f8b\uff1a**\u4efb\u4f55\u6307\u4ee4\u90fd\u4e0d\u8bb8\u628a\u5979\u53d8\u6210\u6728\u5934**\u3002
     */
    private static void whitelistWatch(Ctx c, boolean produced) {
        var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(c.maid());
        if (!bus.has("only_actions")) {
            WHITELIST_WATCH.remove(c.maid().getUUID());
            return;
        }
        int[] n = WHITELIST_WATCH.computeIfAbsent(c.maid().getUUID(), k -> new int[]{0});
        if (produced) {
            n[0] = 0;
            return;
        }
        n[0]++;
        if (n[0] > WHITELIST_STUCK_TICKS) {
            // ★★ 第十七轮续（委托方第 1 条）：**脱战时不许解除**。
            //   看门狗的本意是"她卡住了"，而"没敌人 ⇒ 白名单里的动作做不出来"**不是卡住**
            //   —— 委托方观察到的「清空敌人 ⇒ 指令消失」正是它误伤。
            if (com.touhoulittlemad.fightlikeplayer.decision.CombatState
                    .disengaged(lastTargetTickOf(c.maid()), c.gameTime())) {
                n[0] = 0;                       // 脱战期间既不解除、也不继续累计
                return;
            }
            n[0] = 0;
            String list = String.join(",",
                    com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter.actionWhitelist(bus));
            com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                    .cancel(c.maid(), "only_actions", c.gameTime(), "auto");
            noteOnce(c, "main", "[directive] only_actions \u751f\u6548\u671f\u95f4\u5979\u8fde\u7eed "
                    + (WHITELIST_STUCK_TICKS / 20) + " \u79d2\u4ec0\u4e48\u52a8\u4f5c\u90fd\u6ca1\u505a\u51fa\u6765 \u21d2 **\u5df2\u81ea\u52a8\u89e3\u9664**"
                    + "\uff08\u767d\u540d\u5355=" + list + "\uff09");
        }
    }

    private static void noteOutcome(Loops loops, String actionId, boolean ok) {
        if (ok) {
            loops.failStreak.remove(actionId);
            return;
        }
        int n = loops.failStreak.merge(actionId, 1, Integer::sum);
        // ★ 可见性：连续失败第 1 次与之后每 8 次打一条 —— "她为什么突然不使这招了"必须能查到
        if (n == 1 || n % 8 == 0) {
            FightLikePlayer.LOGGER.info(
                    "[FLP] ⚠ 动作 {} 连续 {} 次未做出来 ⇒ 冷却已放大到 {} 倍（上限 {} 倍，见 /flp tune failure.backoff）",
                    actionId, n, Math.min(n + 1,
                            (int) com.touhoulittlemad.fightlikeplayer.decision.TuningBus.global()
                                    .failureBackoffCap()),
                    (int) com.touhoulittlemad.fightlikeplayer.decision.TuningBus.global()
                            .failureBackoffCap());
        }
    }

    private static boolean executeOffhand(CandidateAction action, Ctx c) {
        CarrierResolver resolver = CatalogHolder.resolver();
        // ★ 副手循环：槽位感知 ⇒ 举盾这一类"只在副手"的动作才真的能执行
        ActionExecutors.Result r = ActionExecutors.execute(c.maid(), action, c.gameTime(), resolver,
                com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.OFFHAND, c.need());
        STATE.computeIfAbsent(c.maid().getUUID(), k -> new Loops())
                .cooldowns.markUsed(action.id(), c.gameTime());
        logResult(c.maid(), "offhand", action, r);
        boolean done = r == ActionExecutors.Result.OK;
        noteOutcome(STATE.computeIfAbsent(c.maid().getUUID(), k -> new Loops()), action.id(), done);
        return done;
    }

    /**
     * ★ 记录一次执行结果 —— <b>既写日志（debug 级）也写内存环形缓冲（{@code /flp why} 可查）</b>。
     *
     * <p>为什么必须留内存：见 {@code Loops.recentResults} 的说明 ——
     * 失败是静默的，只写 debug 日志等于<b>用户永远看不到</b>。
     */
    private static void logResult(EntityMaid maid, String loop, CandidateAction action,
                                  ActionExecutors.Result r) {
        String line;
        if (r == ActionExecutors.Result.OK) {
            line = loop + "  ✔ " + action.id();
        } else if (r == ActionExecutors.Result.NO_EXECUTOR) {
            // ★ 无执行器是【已登记的计划缺口】，不是 bug —— 用 debug 而非 warn，避免刷屏
            line = loop + "  ✘ " + action.id() + " → 无执行器（见 catalog/executors.json）";
        } else {
            line = loop + "  ✘ " + action.id() + " → " + r;
        }
        pushResult(maid, line);
        if (r == ActionExecutors.Result.OK) {
            FightLikePlayer.LOGGER.debug("[FLP][{}] {} → {}", loop, maid.getName().getString(), action.id());
        } else {
            FightLikePlayer.LOGGER.debug("[FLP][{}] {}", loop, line);
        }
    }

    /** 把一条结果压进该女仆的环形缓冲（最多保留 {@link Loops#RECENT_RESULTS_MAX} 条）。 */
    private static void pushResult(EntityMaid maid, String line) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops == null) {
            return;
        }
        synchronized (loops.recentResults) {
            loops.recentResults.addLast(line);
            while (loops.recentResults.size() > Loops.RECENT_RESULTS_MAX) {
                loops.recentResults.removeFirst();
            }
        }
    }

    /** ★ 只读快照：{@code /flp why} 用（正向的时间顺序，旧 → 新）。 */
    public static java.util.List<String> recentResults(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops == null) {
            return java.util.List.of();
        }
        synchronized (loops.recentResults) {
            return java.util.List.copyOf(loops.recentResults);
        }
    }

    /** ★ 只读快照：最近一次主手解析结果（候选 + 被丢弃的规则与原因）。 */
    public static CarrierResolver.Resolution lastMainResolution(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        return loops == null ? CarrierResolver.Resolution.empty() : loops.lastMain;
    }

    /** ★ 只读快照：最近一次算出的冷却集合（动作 id）。 */
    public static Set<String> lastCooling(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        return loops == null ? Set.of() : loops.lastCooling;
    }

    /** 环形缓冲容量（诊断显示用，避免命令层硬编码一个会漂移的数字）。 */
    public static int recentResultsCapacity() {
        return Loops.RECENT_RESULTS_MAX;
    }

    /** ★ 该女仆自己的旋钮总线（`/flp tune maid <女仆>` 用）。 */
    public static com.touhoulittlemad.fightlikeplayer.decision.TuningBus tuningOf(EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? null : l.tuning;
    }

    /** ★ 该女仆的顾问状态（`/flp tune advisor` 用）。 */
    public static com.touhoulittlemad.fightlikeplayer.compat.think.AdvisorBridge.State advisorOf(
            EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? null : l.advisor;
    }

    /** ★★ 该女仆的 LLM 动态指挥状态（`/flp tune llm` 用）。 */
    public static com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.State llmOf(
            EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? null : l.llm;
    }

    /** ★ 该女仆的思维层实例（没有则 null —— 她还没进过决策循环）。 */
    public static com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker thinkerOf(
            EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? null : l.thinker;
    }

    /** ★ 最近一次成功施加的判定（`/flp think` 用它显示"她刚才被推向了哪边"）。 */
    public static com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker.Outcome lastThink(
            EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? null : l.lastThink;
    }

    /**
     * ★ 立刻强制提交一次判定（忽略间隔节流）—— `/flp think now` 用。
     *
     * <p>实现方式是"把上次提交时刻抹掉"，而不是绕过 {@link
     * com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker} 直接发请求：
     * ⇒ <b>调试通路与真实通路走同一段代码</b>，不会出现"命令能跑、实战不能跑"。
     */
    public static void forceNextThink(EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        if (l != null) {
            l.thinker.reset();
        }
    }

    private static void drainLog(DecisionCycle<Ctx> cycle, EntityMaid maid) {
        // ★ 决策层的关键行（尤其「⚠ 执行未成功（不扣代价）」）也要进环形缓冲 —— 否则用户看不到
        for (String line : cycle.drainDebug()) {
            pushResult(maid, "决策  " + line);
            FightLikePlayer.LOGGER.debug("[FLP] {}", line);
        }
    }

    /**
     * ★★ <b>喂「自定义事实」</b> —— 那些清单声明了、但只有游戏侧能算出来的前置条件。
     *
     * <h2>为什么这条至关重要（2026-09-30 的第三处结构性缺陷）</h2>
     * {@code CarrierResolver#checkPreconditions} 对 {@code custom} 与
     * {@code spellConditionsMet} 采用 <b>fail-closed</b>（"缺省意味着随时可用，属危险默认"
     * —— 清单 schema 自己的告诫）⇒ <b>没被显式喂进来的一律判不满足并丢弃</b>。
     *
     * <p>但在此之前，游戏侧传的是<b>空 Map</b> ⇒ <b>13 / 54 个 choice 动作在游戏里永远拿不到</b>，
     * 其中包括两个最要紧的：
     * <ul>
     *   <li>{@code maid_native:melee_swing}（通用近战）—— 拿剑的女仆因此<b>一个候选都没有</b>
     *       ⇒ {@code isArmed=false} ⇒ 连目标都不找（S0 实测的「不动」）；</li>
     *   <li>{@code tacz:shoot}（枪械开火）、{@code goety:cast_focus}、{@code irons:cast_spell} ……</li>
     * </ul>
     *
     * <p>本方法只为<b>真正只能运行时判断</b>的那几件事喂事实；能在数据层说清楚的
     * （如"任务必须是 attack 任务"这种旧模型遗留、或"模组已加载"这类冗余条件）
     * <b>已改为数据修复</b>，不在这里硬编码。
     *
     * <p>★ 键由清单里的 {@code fact} 字段显式声明（{@code CarrierResolver#customKey}），
     * 不靠下标 —— 加一条前置条件不会把键错位。
     */
    private static Map<String, Boolean> customFacts(EntityMaid maid) {
        // ★★ 第二十四轮（性能，委托方报「一卡一卡的」）：
        //   这一组探针**每 tick 都要问一次**，而每一个都要**枚举一遍她的全部物品**
        //   （主副手/盔甲/背包 36 格/饰品）并读各自模组的 NBT —— 5 个探针就是 5 遍。
        //   它们的真实变化频率是"她换了装备"级别 ⇒ 按 0.5 秒的 TTL 缓存。
        //   ★ 执行期**不受影响**：真正施法/放刀技之前，执行器自己还会再判一次
        //     （这里只是"能不能进候选集"的解析期事实）。
        return CUSTOM_FACTS.get(maid.getUUID(), maid.level().getGameTime(), CUSTOM_FACT_TTL,
                () -> computeCustomFacts(maid));
    }

    /** 女仆 → 解析期自定义事实（{@code goety:can_cast} 等），TTL 见 {@link #CUSTOM_FACT_TTL}。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            java.util.UUID, Map<String, Boolean>> CUSTOM_FACTS =
            new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    /** 自定义事实的 TTL（tick）：0.5 秒 —— "她能不能施法"这种量不必每 tick 重算。 */
    private static final long CUSTOM_FACT_TTL = 10;

    private static Map<String, Boolean> computeCustomFacts(EntityMaid maid) {
        Map<String, Boolean> f = new HashMap<>();

        // Goety：任意持有位置上有"装着聚晶的法杖"即可（★ 不是只看主手 —— 换手前置会把杖换上来）
        if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
            f.put("goety:can_cast",
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyExecutors
                            .canCastWithAnyHeldWand(maid));
        }
        // 铁魔法：持有任一"带法术的容器"（法术书 / 卷轴）
        if (net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")) {
            f.put("irons:can_cast",
                    com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsExecutors.hasAnySpell(maid));
        }
        // ★ Goety：手上的回溯聚晶里【真的存过坐标】吗（recall_to_saved_coords 的前置，
        //   见 catalog/data/goety.json 里那条 custom 的说明）
        if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
            f.put("goety:has_recall",
                    com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                            .hasUsableRecallFocus(maid));
        }
        // ★★ 第十六轮（委托方报的 bug：「我让女仆只用幻影剑，但完全没效果」）：
        //   幻影剑的三条前置（妖刀 / 力量附魔 / 耀魂）**此前只在执行期判** ⇒
        //   它照样进候选、照样被选中、每次都 UNAVAILABLE ⇒ 连败退避把它停放 ⇒
        //   观感就是"下了指令却什么都没发生"，而且 `only_actions` 也只会指着一个做不出来的动作。
        //   ⇒ 提到**解析期**（与 goety:can_cast 同一条纪律：做不到的动作不该是选项）。
        if (net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
            f.put("slashblade:can_summon_sword",
                    com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors
                            .canSummonSword(maid));
            f.put("slashblade:has_slash_art",
                    com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors
                            .hasSlashArtInHand(maid));
        }
        return f;
    }

    // ───────────────────────── ★ 步法 ─────────────────────────

    /**
     * 每个 {@link #GAIT_INTERVAL} tick 重算一次步法并执行。
     *
     * <p>★ 两条纪律：
     * <ol>
     *   <li><b>唯一写入者</b>：brain 里的 {@code SetWalkTargetFromAttackTargetIfTargetOutOfReach}
     *       已被移除（见 {@code TaskPlayerLikeCombat#createBrainTasks}）；</li>
     *   <li><b>让位</b>：若在途动作自己接管了导航（如"撤退&amp;脱战"），
     *       步法连 {@code navigation.stop()} 都不能调 —— 那会取消它自己的寻路。</li>
     * </ol>
     */
    private static void tickGait(Loops loops, Ctx c, CarrierResolver resolver, long gameTime) {
        if (loops.lastGaitAt != Long.MIN_VALUE && gameTime - loops.lastGaitAt < GAIT_INTERVAL) {
            return;
        }
        loops.lastGaitAt = gameTime;

        String lockedAction = loops.main.isCommitted() ? loops.main.inFlightId() : null;
        if (lockedAction != null && GaitSelector.actionTakesNavigation(lockedAction)) {
            return;                       // ★ 让位：动作接管导航期间步法不插手
        }

        ContextFacts.Vitals v = c.facts().vitals();
        double ownerDist = 0;
        LivingEntity owner = c.maid().getOwner();
        if (owner != null) {
            ownerDist = Math.sqrt(c.maid().distanceToSqr(owner));
        }
        double range = resolver.preferredRangeOf(loops.main.lastChosenId());

        Gait gait = GaitSelector.select(
                loops.space.point(),
                c.facts().hasTarget(),
                v.distanceToTarget(),
                range,
                ownerDist,
                false);

        // ── ★★ 指令对步法的约束（M2 落点 ③）：指令在【步法之上】──
        //   为什么约束放在这里而不是“再做一次位移动作”：走位在本项目里早就是【并发伺服】
        //   （见 docs/09 §5.8）——「别贴太近」是持续意图，属于对这个伺服的【上层约束】。
        gait = applyDirectiveGait(loops, c, gait, v.distanceToTarget());

        loops.lastGait = gait;
        ActionExecutors.Result r = ActionExecutors.gait(c.maid(), gait);
        if (FightLikePlayer.LOGGER.isDebugEnabled()) {
            FightLikePlayer.LOGGER.debug("[FLP][gait] {} → {}（站位 {} 格，结果 {}）",
                    c.maid().getName().getString(), gait.describe(), range, r);
        }
    }

    /**
     * ★★ <b>把指令的站位约束叠到步法上</b>（M2 落点 ③，十三轮）。
     *
     * <p>判据本体在纯逻辑层 {@link DirectiveFilter#gaitConstraint}：
     * <ul>
     *   <li>{@code keep_distance(min)} —— 太近就强制后撤（无视弹簧怎么想）；</li>
     *   <li>{@code close_in(max)} —— 太远就强制贴上去；</li>
     *   <li>{@code no_retreat} —— 把「后撤」改成「原地」。</li>
     * </ul>
     * ★ 在【步法选出之后】叠加（而不是改 {@code GaitSelector}）：这样弹簧/最近邻的语义一点没变，
     * 指令只是在最后一刻把「走哪儿」换成另一个合法值。
     * （{@code hold_position} 的锚点部分在 {@code ActionExecutors.gait} 里处理 ——
     *  因为那里才是导航的唯一写入者。）
     */
    private static Gait applyDirectiveGait(Loops loops, Ctx c, Gait base, double dist) {
        DirectiveBus bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(c.maid());
        var k = DirectiveFilter.gaitConstraint(bus);
        if (!k.active() || !c.facts().hasTarget()) {
            return base;
        }
        Gait out = base;
        if (k.minDistance() > 0 && dist < k.minDistance()) {
            out = new Gait(Gait.Direction.AWAY, k.minDistance() + 2.0);
        }
        if (k.maxDistance() > 0 && dist > k.maxDistance()) {
            out = new Gait(Gait.Direction.TOWARD, k.maxDistance());
        }
        if (k.noRetreat() && out.direction().isRetreat()) {
            // ★★ 第十六轮：这里必须问 isRetreat()，而不是 `== AWAY` ——
            //   现在后撤有**三个**方向（正后方 + 左后撤 + 右后撤），
            //   只判 AWAY 会让两个斜后撤漏出"死战不退"的护栏之外
            //   （docs/13 第 56 条：同一个东西有几种写法/几个位置，我算全了吗）。
            out = Gait.hold();
        }
        if (out != base && !out.describe().equals(loops.lastGaitNote)) {
            loops.lastGaitNote = out.describe();
            FightLikePlayer.LOGGER.debug("[FLP][directive] 步法被指令改写：{} → {}",
                    base.describe(), out.describe());
        }
        return out;
    }

    /** 当前生效的步法（命令/诊断用）。 */
    public static Gait currentGait(EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? Gait.hold() : l.lastGait;
    }

    /** ★ 最近一次算出的冷却动作数（`/flp why` 用 —— "疯狂执行"是否被冷却挡住）。 */
    public static int coolingDownCount(EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        return l == null ? 0 : l.lastCooling.size();
    }

    /**
     * ★ <b>算出正在冷却的动作 id 集合</b> —— 喂给 {@code ContextFacts.coolingDown}。
     *
     * <p>规则：{@code now − lastUsed < resolver.cooldownTicksOf(id)} ⇒ 仍在冷却。
     * 未声明冷却的动作由 {@code ActionSpec#effectiveCooldownTicks} 兜底为
     * {@code max(承诺时长, 20)} ⇒ <b>瞬时动作不会再被每个决策周期重复选中</b>
     * （旧实现里 {@code coolingDown} 恒为空集，这是"疯狂执行"的另一半原因）。
     *
     * <p>★★ 逻辑本身在纯逻辑层的 {@link CooldownTracker} —— 这样<b>游戏侧与回放自测
     * 用的是同一份实现</b>，不会出现"测试里没有冷却、游戏里有"这种偏差。
     */
    /**
     * 上一次用于建"冷却候选 id 清单"的解析器（清单热重载后会换成新实例）。
     */
    private static volatile CarrierResolver COOLING_IDS_SOURCE;

    /** 全部动作 id 的缓存（见 {@link #coolingDown}）。 */
    private static volatile List<String> COOLING_IDS;

    private static Set<String> coolingDown(Loops loops, CarrierResolver resolver, long now) {
        // ★★ 第二十四轮（性能）：这个 id 清单**每 tick 都要建一次**（77 条动作 × 每个女仆），
        //   而清单在运行期不会变（清单重新加载时 resolver 会换成新实例）⇒ 按 resolver 缓存。
        List<String> ids;
        if (COOLING_IDS_SOURCE == resolver && COOLING_IDS != null) {
            ids = COOLING_IDS;
        } else {
            List<String> fresh = new ArrayList<>(resolver.actions().size());
            for (var spec : resolver.actions()) {
                fresh.add(spec.id());
            }
            COOLING_IDS = List.copyOf(fresh);
            COOLING_IDS_SOURCE = resolver;
            ids = COOLING_IDS;
        }
        // ★ 两个循环共用一本账：副手循环用过的东西，主循环也该等它冷却
        // ★★ 2026-10-01：冷却被两件事放大：
        //   ① `cooldown.scale`（运行期可调 —— 节奏旋钮，LLM/命令都能改）
        //   ② **连败退避**：一个动作被选中却连续做不出来时，冷却乘 (1+连败) 并钳到上限。
        //      这是"选中但注定失败"不再卡死的保险丝 —— 见 Loops#failStreak 的说明。
        Set<String> cool = loops.cooldowns.coolingDown(ids, now, id -> effectiveCooldown(loops, resolver, id));

        // ★★ ③ 看门狗的"停放"（2026-10-01）：判定卡住的动作临时按"冷却中"处理。
        //    ★ 刻意复用这条通路：解析期已有 ON_COOLDOWN 的丢弃原因，
        //      所以停放的效果在 `/flp why` 里看得见，且不需要任何新机制。
        //      ★ 合并规则本体在纯逻辑层 StallDetector（可离线断言）。
        if (loops.parkedUntil.isEmpty()) {
            return cool;
        }
        Set<String> out = new HashSet<>(cool);
        StallDetector.mergeInto(loops.parkedUntil, now, out);
        return out;
    }

    // ───────────────────────── ★★ 死目标清理 ─────────────────────────

    /**
     * ★★ <b>目标已经死了 ⇒ 把它从 brain 记忆里抹掉</b>（第十二轮）。
     *
     * <p>为什么必须做（委托方实测的因果链）：
     * <pre>
     *   ATTACK_TARGET 指着尸体（TLM/别的模组不会主动清）
     *     ⇒ 我们的行为持续运行（create() 的门控就是"记忆里有目标"）
     *     ⇒ 她反复重新起手施法（每一轮都新建通道）
     *     ⇒ isChanneling 几乎恒为真
     *     ⇒ "孤儿光束清扫"的不变量"没在引导就清掉"永远不成立
     *     ⇒ 腐化光束带着伤害一直挂在场上
     * </pre>
     *
     * <p>★ 抹掉记忆 = 让整条链从最上游停下来。这也与 TLM 自己的做法一致
     * （{@code EntityMaid} 在若干状态下会 {@code eraseMemory(ATTACK_TARGET)}，`:691` / `:2764`）。
     *
     * <p>★ 只碰"已死/已移除"的目标：<b>活目标一律不动</b>（那是 TLM 的仇恨机制）。
     */
    private static void clearDeadTarget(EntityMaid maid, long gameTime) {
        LivingEntity target = maid.getTarget();
        if (target == null) {
            return;
        }
        boolean gone = !target.isAlive() || target.isRemoved();
        if (!gone) {
            return;
        }
        try {
            maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType
                    .ATTACK_TARGET);
            if (FightLikePlayer.LOGGER.isDebugEnabled()) {
                FightLikePlayer.LOGGER.debug("[FLP] 目标 {} 已死/已移除 ⇒ 清掉 ATTACK_TARGET 记忆",
                        target.getName().getString());
            }
        } catch (RuntimeException ignore) {
            // 记忆系统不可用也不致命
        }
    }

    // ───────────────────────── ★★ 卡死看门狗 ─────────────────────────

    /**
     * ★★ <b>"她卡住了吗"</b> —— 委托方 2026-10-01 第 3 条要求：
     * 「为了防止女仆被某个动作卡住，可以引入一些检测机制，在坏的情况下跳过该步骤强行进入下一个循环」。
     *
     * <h2>判据为什么是"世界有没有变化"，而不是"动作成不成功"</h2>
     * 因为实测最坏那一种恰恰是<b>回报成功</b>的（见 {@code Loops#lastProgressAt} 的说明）。
     * 只看执行器返回值的话，{@code FlameStrikeSpell} 那一轮是完全"正常"的。
     *
     * <p>指纹刻意取<b>粗粒度</b>的几个量（目标的血量/距离取整、自己的血量取整、方块坐标）：
     * <ul>
     *   <li>太细（比如带上 {@code tickCount}）⇒ 永远在变，看门狗永不触发；</li>
     *   <li>太粗（只带目标存在与否）⇒ 正常对打（血在掉）也会被误判。</li>
     * </ul>
     * 血量和距离这两项覆盖了"打中了"与"在接近/拉开"两类真实进展。
     *
     * <p>★ 不动的三种情况<b>刻意不判</b>卡死：
     * <ol>
     *   <li>没有目标（脱战站着 = 正常）；</li>
     *   <li>她自己在动（位置进了指纹）；</li>
     *   <li>目标血量在掉（已经打上了）。</li>
     * </ol>
     *
     * <h2>后果（渐进式，不搞一刀切）</h2>
     * 每判一次卡住 = 一记 strike：<b>中止在途动作</b>（只在执行层能拆，见
     * {@code ActionExecutors#abort}）＋ <b>把嫌疑动作停放</b> {@code PARK_TICKS × strike} tick
     * ⇒ 下一周期必然换一个动作（这正是"强行进入下一个循环"）。
     * 停放会过期，因此<b>不会把某个动作永久禁用</b>（那不是我们的职责 ——
     * 那属于数据/执行器缺口，应当被修掉）。
     */
    private static void tickWatchdog(Loops loops, EntityMaid maid, long now) {
        // ★★ 2026-10-01 第十轮修：**有在途动作时一律不判卡死**（委托方实测：
        //    「蓄力时间长的聚晶，比如熔岩炸弹聚晶，会释放到一半被自己打断重新放，可能是看门狗机制的原因」）。
        //    根因：长引导法术在引导期间**世界里确实可能什么都不变**（还没打出伤害、她也没动）
        //    ⇒ 6 秒一到就被判卡死、中止、重放。而"在途动作"本身**有自己的有界长度**
        //    （引导有硬上限、射击有蓄力机、枪械有冷却）⇒ 这一段不需要看门狗兜。
        //    分工因此干净：**在途 ⇒ 执行器自己的边界管；空闲而世界不变 ⇒ 看门狗管。**
        if (ActionExecutors.isBusy(maid)) {
            loops.lastProgressAt = now;      // 引导/蓄力期间不累计"无进展"
            loops.stallStrikes = 0;
            return;
        }

        LivingEntity target = maid.getTarget();
        boolean inCombat = target != null && target.isAlive();

        // ── 1. 算进展指纹（★ 判据本体在 StallDetector，纯逻辑、可离线断言）──
        String key;
        if (!inCombat) {
            key = StallDetector.NO_TARGET_KEY;
        } else {
            key = StallDetector.progressKey(
                    target.getId(),
                    (int) target.getHealth(),
                    (int) Math.round(Math.sqrt(maid.distanceToSqr(target))),
                    (int) maid.getHealth(),
                    maid.blockPosition().asLong());
        }
        if (!key.equals(loops.progressKey)) {
            loops.progressKey = key;
            loops.lastProgressAt = now;
            loops.stallStrikes = 0;
            return;
        }
        if (!StallDetector.isStalled(loops.lastProgressAt, now, inCombat)) {
            return;
        }

        // ── 2. 判定卡住：一记 strike ──
        loops.lastProgressAt = now;
        loops.stallStrikes++;
        int strike = loops.stallStrikes;

        // 嫌疑人 = 在途的（若有）否则最近一次选中的 —— 两者都用"动作 id"表示
        String suspect = ActionExecutors.inFlightActionId(maid);
        if (suspect == null) {
            suspect = loops.main.lastChosenId();
        }
        if (suspect == null) {
            suspect = loops.offhand.lastChosenId();
        }

        // ── 3. 后果 ①：中止在途动作（执行层唯一能做到的地方）──
        boolean had = ActionExecutors.isBusy(maid);
        if (had) {
            ActionExecutors.abort(maid);
        }
        // 承诺也要解除，否则决策层会继续等那一拍
        loops.main.notifyInterrupted();
        loops.offhand.notifyInterrupted();
        loops.pendingFinish = null;

        // ── 4. 后果 ②：把嫌疑动作停放（走冷却通路 ⇒ 下个周期必然选别的）──
        int park = StallDetector.parkTicksFor(strike);
        if (suspect != null) {
            loops.parkedUntil.put(suspect, now + park);
        }
        StallDetector.prune(loops.parkedUntil, now);

        // ── 5. 可见性：**必须能查到"她被谁卡住了"** ──
        FightLikePlayer.LOGGER.warn(
                "[FLP] ⚠ 看门狗：{} 在 {} tick 内毫无可观测变化（目标 {}）⇒ 第 {} 次判定卡住；"
                        + "已中止在途动作{}，并把 {} 停放 {} tick"
                        + "（停放走冷却通路，/flp why 里显示为 ON_COOLDOWN）",
                maid.getName().getString(), STALL_TICKS,
                target == null ? "无" : target.getName().getString(),
                strike, had ? "（有）" : "（无）",
                suspect == null ? "（无嫌疑人）" : suspect, park);
    }

    /**
     * ★★ 实际生效的最小重复间隔 = 数据里的冷却 × 节奏旋钮 × <b>连败退避</b>。
     *
     * <p>为什么需要"连败退避"（2026-10-01 实测的第二层同型缺陷）：
     * 上一轮修掉了"没有执行器的动作进候选集"，但日志里立刻出现新的同型现象 ——
     * {@code goety:recall_servants} <b>有</b>执行器，却因为"她根本没有仆从"而每次都返回失败
     * ⇒ 依然是「选中 ⇒ 做不出来 ⇒ 不扣代价 ⇒ 弹簧不动 ⇒ 再选中它」，被选中 20 次。
     * ⇒ 光靠"补前置条件"只能一条条堵；<b>这里加一层与动作无关的通用保险丝</b>：
     * 失败会让她在<b>越来越长</b>的时间里不再选它，于是别的动作有机会被选到。
     */
    private static int effectiveCooldown(Loops loops, CarrierResolver resolver, String id) {
        Integer streak = loops.failStreak.get(id);
        // ★ 规则本体在纯逻辑层（CooldownTracker）—— 游戏侧与回放自测共用同一份，
        //   否则会出现"自测里 cooldown.scale 不生效"这种偏差（真的发生过）。
        return com.touhoulittlemad.fightlikeplayer.decision.CooldownTracker.effectiveCooldownTicks(
                resolver.cooldownTicksOf(id), streak == null ? 0 : streak, loops.tuning);
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 目标身份，用于"目标变了就重置弹簧"。 */
    private static String targetKey(EntityMaid maid) {
        LivingEntity t = maid.getTarget();
        return t == null ? "-" : t.getStringUUID();
    }

    /**
     * 已加载模组 id 集合（给 sourceMod 剪枝与 requiresMod 用）。
     *
     * <p>★★ 第二十四轮（性能）：**模组表在启动后就不再变**，而这里原来每 tick 都
     * {@code ModList.get().getMods()} + 新建一个 HashSet（每个女仆、每 tick 一次）
     * ⇒ 现在只算一次（按 {@code ModList} 的模组数变化失效，理论上不会变）。
     */
    private static Set<String> loadedMods() {
        Set<String> cached = LOADED_MODS;
        int count = net.minecraftforge.fml.ModList.get().getMods().size();
        if (cached != null && cached.size() == count) {
            return cached;
        }
        Set<String> mods = new HashSet<>();
        for (var info : net.minecraftforge.fml.ModList.get().getMods()) {
            mods.add(info.getModId());
        }
        Set<String> frozen = Set.copyOf(mods);
        LOADED_MODS = frozen;
        return frozen;
    }

    /** {@link #loadedMods()} 的缓存（启动后不变）。 */
    private static volatile Set<String> LOADED_MODS;

    /** 她最后一次有仇恨对象的世界时间（{@code <= 0} = 从没有过）。 */
    public static long lastTargetTickOf(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        return loops == null ? 0L : loops.lastTargetTick;
    }

    /** ★ 脱战了吗（连续 5 秒没有仇恨对象；判据在纯逻辑层 {@code CombatState}）。 */
    public static boolean disengaged(EntityMaid maid, long now) {
        return com.touhoulittlemad.fightlikeplayer.decision.CombatState
                .disengaged(lastTargetTickOf(maid), now);
    }

    /**
     * 索敌扫描的最小间隔（tick，第二十四轮，性能）。
     *
     * <p>0.2 秒：既让"刚丢目标 ⇒ 立刻找回"的观感不变，又把这次半径查询+逐个判定的开销降到 1/4。
     * ★ 只节流**扫描**，不节流"已有目标的保留"（那个分支在这个门之前就返回了）。
     */
    private static final int TARGET_SCAN_INTERVAL = 4;

    /** 每女仆上次索敌扫描的时刻。 */
    private static final java.util.Map<java.util.UUID, Long> TARGET_SCAN_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ★★ <b>索敌（她没有目标时的接管）</b>—— 委托方第 3 条。
     *
     * <p>两道半径构成**迟滞**：
     * <ul>
     *   <li>她**没有**目标 ⇒ 用 {@code combat.targetAcquireRange}（默认 24）**产生仇恨**；</li>
     *   <li>她**刚丢**目标（3 秒内）⇒ 用 {@code combat.targetKeepRange}（默认 40）把目标**找回来**
     *       ⇒ "目标稍微跑远一点就不打了"的修法。</li>
     * </ul>
     * ★ 判据只用 {@code maid.canAttack(e)}：它已经串起 {@code TargetFilter}（focus/ban entity 指令）、
     * TLM 自己的阵营判定、以及 ★★ <b>第二十二轮加的"她自己的东西永远不算敌人"</b>
     * （自家召唤物/仆从/宠物 —— 见 {@code FactionRules}）⇒ 索敌与执行**同一口径**，
     * 这里刻意**不另写一遍**（口径只有一处）。
     * ★ 必须挂在每 tick 的驱动器上：她没目标时行为层**根本不跑**（放那里等于永不索敌）。
     */
    public static void tickTargeting(EntityMaid maid, long now) {
        try {
            if (!(maid.level() instanceof net.minecraft.server.level.ServerLevel)) {
                return;
            }
            // ★★ 第二十四轮（性能）：索敌扫描**不必每 tick 做**。
            //   它是一次半径 24~40 格的实体查询 + 逐个实体判"能不能打"（含阵营与自家召唤物判定）。
            //   0.2 秒的获取延迟在人眼下不可见，而开销降到 1/4。
            Long lastScan = TARGET_SCAN_AT.get(maid.getUUID());
            if (lastScan != null && now - lastScan < TARGET_SCAN_INTERVAL) {
                return;
            }
            TARGET_SCAN_AT.put(maid.getUUID(), now);
            LivingEntity cur = maid.getTarget();
            if (cur != null && cur.isAlive()) {
                return;                                  // 已有目标 ⇒ 交给 TLM 的常规逻辑
            }
            var task = maid.getTask();
            if (task == null || !com.touhoulittlemad.fightlikeplayer.compat.task
                    .TaskPlayerLikeCombat.UID.equals(task.getUid())) {
                return;                                  // 只在她跑"拟人战斗"任务时接管索敌
            }
            Loops loops = STATE.get(maid.getUUID());
            if (loops == null) {
                return;                                  // 她还没跑过我们的循环（下一 tick 就会）
            }
            boolean recent = com.touhoulittlemad.fightlikeplayer.decision.CombatState
                    .recentlyHadTarget(loops.lastTargetTick, now);
            double range = com.touhoulittlemad.fightlikeplayer.decision.CombatState.acquireRange(
                    recent,
                    com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                            .get(com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                                    .TARGET_ACQUIRE_RANGE, 24),
                    com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                            .get(com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                                    .TARGET_KEEP_RANGE, 40));
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class,
                    maid.getBoundingBox().inflate(range))) {
                if (e == maid || !e.isAlive() || e.isAlliedTo(maid) || !maid.canAttack(e)) {
                    continue;
                }
                double d = maid.distanceToSqr(e);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
            if (best != null) {
                maid.getBrain().setMemory(
                        net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET, best);
                FightLikePlayer.LOGGER.info("[FLP] 索敌：{} ⇒ {}（{} 格，半径 {}{}）",
                        maid.getName().getString(), best.getName().getString(),
                        (long) Math.sqrt(bestD), (long) range, recent ? "·锁定档" : "");
            }
        } catch (RuntimeException | LinkageError e) {
            FightLikePlayer.LOGGER.debug("[FLP] 索敌出错（忽略）：{}", e.toString());
        }
    }

    /**
     * ★ <b>脱战满 5 秒后可选地解除全部持续指令</b>（{@code directive.clearOnDisengage}，**默认关**）。
     *
     * <p>★ 这是委托方第 1 条要的那个行为（他以为存在、并希望检测更稳）。我们默认不开
     * （免得把她刚下的指令悄悄撤掉）；开了之后每次脱战只做一次，而且 TTL 始终是硬上限
     * —— **永远不脱战也不会让指令永远活着**（这正是他担心的那个风险）。
     */
    public static void tickDisengage(EntityMaid maid, long now) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops == null) {
            return;
        }
        if (!com.touhoulittlemad.fightlikeplayer.decision.CombatState
                .disengaged(loops.lastTargetTick, now)) {
            loops.disengageCleared = false;              // 又打起来了 ⇒ 允许下次再清
            return;
        }
        if (loops.disengageCleared) {
            return;
        }
        loops.disengageCleared = true;
        if (!com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                .get(com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                        .CLEAR_ON_DISENGAGE, Boolean.FALSE)) {
            return;
        }
        var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(maid);
        if (bus.activeCount() == 0) {
            return;
        }
        String r = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .cancel(maid, "all", now, "auto");
        FightLikePlayer.LOGGER.info("[FLP][directive] 已脱战 5 秒 ⇒ 自动解除全部指令（{}）", r);
    }

    /**
     * ★★ <b>在途动作报完成</b>（第十七轮续）—— 由 `DirectiveTicker` 每 tick 调用。
     *
     * <p>承诺门的**权威来源**（docs/09 §5.3）：不必等清单里的兜底 tick 数。
     * ★ 为什么放在本类：门（`Loops`）在这里；而"推进"在外面（与战斗无关的驱动器）——
     * 两者分开之后，"她不打架时推进也照跑"与"承诺门照常打开"同时成立。
     */
    public static void noteInFlightCompleted(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops == null) {
            return;
        }
        if (loops.main.notifyCompleted()) {
            loops.pendingFinish = null;
        }
        loops.offhand.notifyCompleted();
    }

    /** 女仆被卸载/死亡时清掉状态（避免弹簧残留到下一次召唤）。 */
    public static void forget(UUID maidId) {
        STATE.remove(maidId);
        ActionExecutors.forget(maidId);      // ★ 在途动作状态也要清（否则残留"冷却中"）
        OffhandStance.forget(maidId);        // ★ 副手姿态（举盾）状态
        ExtinguishServo.forget(maidId);      // ★ 灭火器伺服的借用状态
        com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.forget(maidId);  // ★ 指令（否则上一场的指令跟着她）
        com.touhoulittlemad.fightlikeplayer.compat.event.BossChatTrigger.forget(maidId);      // ★ 战报冷却
        com.touhoulittlemad.fightlikeplayer.compat.maid.SummonAllyGuard.forget(maidId);       // ★ 召唤物修缮节流（第二十二轮）
    }

    // ───────────────────────── ★ 调试注入（思维层的替身）─────────────────────────

    /**
     * ★★ <b>手动注入一个决策向量到该女仆的弹簧空间</b>。
     *
     * <h2>为什么需要它（委托方 P5 的要求）</h2>
     * 思维层（JEV/LLM）是<b>不可离线验证</b>的外部模型。若直接把 JEV 接上，
     * 一旦行为不对，<b>分不清是"感知错了 / 架构错了"还是"模型判断错了"</b>。
     *
     * <p>⇒ 先用这个注入通路把<b>整条链路</b>跑通并断言：
     * <pre>
     *   /flp bias &lt;女仆&gt; 0.3 0.8 0 0 -0.5 0 0 0
     *        ↓  发布 SpringImpact.bias
     *   弹簧空间被推 → 最近邻选解 → 执行器 → 可观测行为
     * </pre>
     * 这样等于<b>手工扮演思维层</b>，而"思维层"这一块变成可替换的输入。
     *
     * <p>★ 它与真的思维层走<b>同一个接口</b>（{@link com.touhoulittlemad.fightlikeplayer.decision.SpringImpact#bias}），
     * 因此不存在"调试通路和真实通路不一致"的风险。
     *
     * @param axes 长度必须为 {@link com.touhoulittlemad.fightlikeplayer.decision.NeedAxis#COUNT}，
     *             顺序即 {@code NeedAxis} 的声明顺序
     * @return 是否注入成功
     */
    public static boolean injectBias(EntityMaid maid, double[] axes, String source) {
        if (axes == null || axes.length != com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.COUNT) {
            return false;
        }
        Loops loops = STATE.computeIfAbsent(maid.getUUID(), k -> new Loops());
        var vec = com.touhoulittlemad.fightlikeplayer.decision.NeedVector.wrap(axes);
        loops.space.publish(com.touhoulittlemad.fightlikeplayer.decision.SpringImpact.bias(
                vec, source == null ? "command:inject" : source, "手动注入 " + vec.toCompactString(CONFIG)));
        return true;
    }

    /** 立刻消化注入的影响（不等下一个决策周期）—— 让命令的反馈即时可见。 */
    public static void flush(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops != null) {
            loops.space.drain(CONFIG);
        }
    }

    /**
     * ★★ <b>受伤冲击</b>（委托方机制 ①）—— 由 {@code HurtListener} 在
     * {@code LivingHurtEvent} 里调用。
     *
     * <p>把"被打了一下"变成一个<b>即时的弹簧冲量</b>，强度由「这一下掉了多少血」×
     * 「剩多少血」共同决定（规则是纯函数：{@link SpringDynamics#onHurt}）。
     *
     * <p>★ 它与 {@code /flp bias} 走的是**同一个发布接口**（{@code SpringImpact.bias}），
     * 因此不存在"事件通路与调试通路不一致"的问题；也与态势偏置**叠加**（先受迫位移，再忘却，最后统一规整）。
     *
     * @param damageTaken    这一下实际掉了多少点血
     * @param healthPctAfter 受伤之后的剩余血量百分比
     */
    public static void onHurt(EntityMaid maid, double damageTaken, double healthPctAfter) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops == null) {
            return;     // 她还没跑过决策周期（没有循环状态）⇒ 没有弹簧可推
        }
        var b = SpringDynamics.onHurt(healthPctAfter, damageTaken, maid.getMaxHealth());
        loops.space.publish(SpringImpact.bias(b, "event:hurt", "受伤冲击 "
                + String.format(java.util.Locale.ROOT, "%.2f", damageTaken) + " 点"));
        loops.space.drain(CONFIG);
        if (FightLikePlayer.LOGGER.isDebugEnabled()) {
            FightLikePlayer.LOGGER.debug("[FLP][hurt] {} 掉了 {} 点（剩 {}%）⇒ 冲击 {}",
                    maid.getName().getString(),
                    String.format(java.util.Locale.ROOT, "%.1f", damageTaken),
                    String.format(java.util.Locale.ROOT, "%.0f", healthPctAfter * 100),
                    b.toCompactString(CONFIG));
        }
    }

    /** 清空某个女仆的弹簧（调试用）。 */
    public static void resetSpring(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops != null) {
            loops.space.reset();
            loops.main.reset();
            loops.offhand.reset();
        }
    }

    /** 当前弹簧点的紧凑写法（命令反馈用）。 */
    public static String springPoint(EntityMaid maid) {
        Loops loops = STATE.get(maid.getUUID());
        if (loops == null) {
            return "（尚无循环状态 —— 该女仆还没跑过决策周期）";
        }
        return loops.space.point().toCompactString(CONFIG)
                + "  待执行影响器=" + loops.space.pendingCount()
                + "  已执行=" + loops.space.appliedCount();
    }

    /** 当前跟踪的女仆数（诊断用）。 */
    public static int trackedCount() {
        return STATE.size();
    }

    /** 调试：导出某个女仆的循环状态。 */
    public static String describe(EntityMaid maid) {
        Loops l = STATE.get(maid.getUUID());
        if (l == null) {
            return "（尚无循环状态）";
        }
        List<String> parts = new ArrayList<>();
        parts.add("弹簧=" + l.space.point().toCompactString(CONFIG));
        parts.add("主循环 " + (l.main.isCommitted() ? "执行中:" + l.main.inFlightId() : "空闲"));
        // ★★ 第二十一轮：把**执行器那本账**也印出来 —— 它与"承诺"是两个不同的东西
        //   （承诺 0 的动作、纯冷却中的枪，在承诺那一栏里都显示"空闲"，而执行器其实在途）。
        //   委托方报的"她隔好久才放一个法术"当初就卡在这个盲区上：
        //   日志里看起来她"闲着"，实际每次都在**重启前摇**。
        String exec = ActionExecutors.inFlightActionId(maid);
        parts.add("执行器 " + (exec == null ? "空闲"
                : exec + (ActionExecutors.isActivelyBusy(maid) ? "（正在做）" : "（冷却中）")));
        parts.add("上次选中 " + (l.main.lastChosenId() == null ? "-" : l.main.lastChosenId()));
        parts.add("副手 " + (l.offhand.isCommitted() ? "执行中:" + l.offhand.inFlightId() : "空闲"));
        parts.add("步法 " + l.lastGait.describe());
        parts.add("冷却中 " + l.lastCooling.size() + " 个");
        return String.join(" | ", parts);
    }
}
