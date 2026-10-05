package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * 决策循环 —— 弹簧体系的编排者。
 *
 * <h2>为什么它不依赖 Minecraft</h2>
 * 本类对上下文类型 {@code C} 泛型化，只通过三个接口与世界交互
 * （{@link BiasSource} / {@link ActionProvider} / {@link ActionExecutor}）。
 * ⇒ <b>弹簧的数学可以在没有游戏、没有渲染、没有 tick 的环境里用断言验证</b>。
 *
 * <h2>★ 一个循环 = 一套动作层 + 一个共享的弹簧空间</h2>
 * 委托方要求：<b>「主手+步伐+其他」一个循环，副手一个循环；共用一个弹簧空间，动作层隔离。</b>
 * ⇒ 本类<b>不拥有</b>弹簧点，而是持有一个外部的 {@link SpringSpace}
 * （通过构造器注入）。两个实例传入同一个 {@code SpringSpace} 即实现"共用一个空间"。
 *
 * <h2>★ 执行门控：上一个动作执行完毕才能开下一个</h2>
 * 委托方要求：「上一次的执行完毕了才能开启下一次循环」。
 * ⇒ 循环处于 {@code inFlight} 状态期间<b>不重选决策</b>。结束有两种途径：
 * <ol>
 *   <li>★ <b>执行器回报完成</b>（{@link #notifyCompleted()}）—— 这是权威来源
 *       （弹匣打完、一轮连发结束、法术结算完毕，只有执行器知道）；</li>
 *   <li>承诺 tick 数走完（兜底，防止执行器漏报导致永久卡死）。</li>
 * </ol>
 *
 * <h2>每 tick 的流程（对应 docs/09 §5.1）</h2>
 * <pre>
 * 0. 先消化【其他循环 / 思维层】发布的影响器        ← 共享空间的合流点
 * 1. 承诺门控    —— inFlight 期间不重选；完成则结束承诺
 * 2. 周期节流
 * 3. 态势基线偏置 b（规则）→ 作为影响器【发布】
 * 4. 忘却 + 规整（由影响器的 apply 内部完成）
 * 5. 死区判定      ‖p‖ 极小 ⇒ 走默认动作集
 * 6. 载体解析      A ← provider.available(ctx)；A 空 ⇒ 回退动作
 * 7. 最近邻选解    x* ← selector.select(p, A)
 * 8. 提交执行      commit → execute →【发布】扣减影响器 → drain → markUsed
 * </pre>
 *
 * @param <C> 决策上下文（游戏侧通常就是 {@code EntityMaid}）
 * @see SpringSpace
 * @see SpringImpact
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5</a>
 */
public final class DecisionCycle<C> {

    /** 由世界状态产生"想要什么"的偏置。规则版基线 + 未来的思维层。 */
    public interface BiasSource<C> {
        NeedVector bias(C ctx);
    }

    /** 载体解析：当前可用动作集合。对应 docs/09 §4.4。 */
    public interface ActionProvider<C> {
        List<CandidateAction> available(C ctx);
    }

    /**
     * 执行层（M4 才真正落地）。
     *
     * <p>★★ <b>返回"有没有真的做出来"</b>（2026-09-30 修）：
     * 此前是 {@code void} ⇒ 决策层无法区分"做了"和"没做成"，
     * 于是<b>连"物品不在手上、根本没能换手"这种情况也会照扣代价</b>
     * —— 弹簧被一件<b>没发生的事</b>推着走，而执行器缺口也会被这个假代价掩盖。
     *
     * <p>语义要求：
     * <ul>
     *   <li>{@code true} ⇒ 动作<b>已经发起</b>（承诺型动作"发起"即算成功，
     *       完成由 {@link #notifyCompleted()} 或承诺到期收尾）；</li>
     *   <li>{@code false} ⇒ <b>什么都没发生</b>（没目标、换手失败、无执行器、异常）
     *       ⇒ 不扣代价、不进入承诺、不记 lastChosenId ⇒ 下个周期可以选别的。</li>
     * </ul>
     */
    public interface ActionExecutor<C> {
        boolean execute(CandidateAction action, C ctx);
    }

    /**
     * ★★ <b>执行器侧"她还在忙"</b> —— 可选的第三个观察点（默认恒 false）。
     *
     * <p><b>为什么需要它：</b>有两类动作的"做完了没"<b>不可能在决策期算出来</b>：
     * <ul>
     *   <li>引导类法术（Goety 的通道）：长度由运行时挑中的那颗法术决定
     *       —— 清单里的 {@code duration} 只能给兜底值；</li>
     *   <li>清单里压根没登记长度的执行器私有过程（第三方模组的连发、蓄力）。</li>
     * </ul>
     * 只靠 {@code commitmentTicks} 的门控，兜底值一过就会"一边引导一边另开动作"。
     * ⇒ 把执行器的在途状态接进决策门控：<b>在执行器说"还在忙"期间，本循环不开新决策</b>。
     *
     * <p>★ 注意与 {@link #notifyCompleted()} 的分工：那个是<b>执行器主动回报"做完了"</b>
     * （事件驱动，立刻解除承诺）；这个是<b>决策层每 tick 询问"还在忙吗"</b>
     * （状态查询，用于兜底延长）。两者都指向同一件事，互相兜底。
     */
    public interface BusySource<C> {
        boolean busy(C ctx);
    }

    private BusySource<C> busySource = ctx -> false;

    /**
     * ★★ <b>更窄的一路"她在忙"</b>（第二十一轮，仅供 {@code tick} 的 1.5 段使用）。
     *
     * <p>为什么需要两路：{@link #busySource} 回答的是"**这条动作**做完了没"
     * （所以武器/法术的纯冷却也算"没做完"—— 那条门在等它）；而 1.5 段要回答的是
     * "**她本人**现在能不能另开一个动作"（纯冷却 ⇒ 能，去做别的）。
     * 游戏侧分别接 {@code ActionExecutors#isBusy} 与 {@code ActionExecutors#isActivelyBusy}。
     * <p>★ 默认 {@code null} ⇒ 退回 {@link #busySource}：只接一个来源的调用方（自测夹具）语义不变。
     */
    private BusySource<C> activeBusySource;

    private boolean activelyBusy(C ctx) {
        return (activeBusySource == null ? busySource : activeBusySource).busy(ctx);
    }

    /**
     * ★ 看门狗①：允许"承诺到期但执行器还在忙"额外等多少 tick。
     *
     * <p>为什么要上限：{@code busySource} 是执行层报上来的状态，<b>它也可能是错的</b>
     * （在途条目没被清掉）。若无限等，一个执行层的 bug 就会把决策层<b>永久锁死</b>
     * —— 那正是委托方反复报的"卡住"。⇒ 超时后放弃等待、解除承诺，
     * 并把"执行器还在忙"这件事写进日志（可预期的失败必须有可读的出口）。
     * <p>200 tick = 10 秒：比任何已知的合法在途（最长的是 goety 引导，硬上限 120 tick）都长。
     */
    private static final int BUSY_WAIT_LIMIT = 200;

    /** 已经为"执行器还在忙"多等了几 tick（见 {@link #BUSY_WAIT_LIMIT}）。 */
    private int busyWaitTicks;

    /**
     * ★★ <b>看门狗②的闩锁</b>（第二十一轮）：已经放弃等待过**当前这一轮**在途状态了。
     *
     * <p>为什么需要闩锁：若只在超时那一拍放行一次，下一拍又会从头计 200 tick
     * ⇒ 她实际是"每 10 秒动一次"，而不是"放弃等待"。执行器把在途状态清掉时（{@code busy} 变假）
     * 才解锁 —— 那时新的一轮在途状态是**新**的，理应重新等它。
     */
    private boolean busyIgnored;

    private final SpringConfig config;
    private final BiasSource<C> biasSource;
    private final ActionProvider<C> actionProvider;
    private final ActionExecutor<C> executor;
    private final ActionSelector selector;
    private final LongSupplier clock;

    /** ★ 共享的弹簧空间（两个循环传同一个实例）。 */
    private final SpringSpace space;

    /** 本循环的名字 —— 用于影响器的 {@code source}，便于分辨"是谁改的弹簧"。 */
    private final String loopName;

    private long lastDecisionAt = Long.MIN_VALUE;

    // ── 执行状态（上一个动作没完就不开下一个）──
    private CandidateAction inFlight;
    private int inFlightRemaining;
    private int inFlightTotal;

    /**
     * ★ <b>最近一次真正提交的动作 id</b>（跨周期保留，{@code reset()} 才清）。
     *
     * <p>用途：<b>步法</b>要知道"女仆现在想用哪一招"，才能决定站位
     * （见 {@link GaitSelector}：弓 12 格、剑 3 格）。
     * 不能只看 {@link #inFlight} —— 瞬时动作提交完就没有 inFlight 了，
     * 但"她手上拿的还是那把弓"这一事实不变。
     */
    private String lastChosenId;
    /** ★★ 最近一拍的需求向量里"防御"有多大（连段奖励的护栏：在挨打时不硬冲）。 */
    private double lastMitigNeed;

    /**
     * ★★ <b>本条循环使用的旋钮总线</b>（默认 = 全局）。
     *
     * <p>为什么要能换（委托方要求「女仆之间的独立」）：旋钮原来只有全局一份
     * ⇒ 顾问看的是某一个女仆的统计，却改了所有女仆的风格。
     * ⇒ 每个女仆有一条从属于全局的总线（{@link TuningBus#independent()}），
     * 本循环的"满足度"就走她那一条。
     */
    private TuningBus tuning = TuningBus.global();

    // ── recency 记录 ──
    private final Map<String, Long> lastUsed = new HashMap<>();

    private ActionProvider<C> defaultActions = ctx -> List.of();

    /**
     * 回退动作：可用集合为空时使用。
     * <p>★ <b>必须永远存在</b> —— [08 §8.2] 那条"灭火器能力静默丢失"的教训就是"没有回退"造成的。
     */
    private ActionProvider<C> fallbackActions = ctx -> List.of();

    private final List<String> lastDebugLines = new ArrayList<>();

    /**
     * 构造。
     *
     * @param space 共享弹簧空间（<b>两个循环必须传同一个</b>）
     * @param loopName 循环名（"main" / "offhand"），进日志
     */
    public DecisionCycle(SpringConfig config,
                         SpringSpace space,
                         String loopName,
                         BiasSource<C> biasSource,
                         ActionProvider<C> actionProvider,
                         ActionExecutor<C> executor,
                         LongSupplier clock,
                         Random random) {
        this.config = config;
        this.space = space;
        this.loopName = loopName == null ? "loop" : loopName;
        this.biasSource = biasSource;
        this.actionProvider = actionProvider;
        this.executor = executor;
        this.clock = clock;
        this.selector = new ActionSelector(config, random, id -> lastUsed.getOrDefault(id, Long.MIN_VALUE));
    }

    /** 便利构造：自带一个私有空间（单循环场景 / 自测）。 */
    public DecisionCycle(SpringConfig config,
                         BiasSource<C> biasSource,
                         ActionProvider<C> actionProvider,
                         ActionExecutor<C> executor,
                         LongSupplier clock,
                         Random random) {
        this(config, new SpringSpace(), "main", biasSource, actionProvider, executor, clock, random);
    }

    public DecisionCycle<C> withDefaultActions(ActionProvider<C> p) {
        this.defaultActions = p;
        return this;
    }

    public DecisionCycle<C> withFallbackActions(ActionProvider<C> p) {
        this.fallbackActions = p;
        return this;
    }

    public SpringSpace space() {
        return space;
    }

    public String loopName() {
        return loopName;
    }

    // ───────────────────────── 主循环 ─────────────────────────

    /**
     * 每 tick 调用一次。返回是否在本 tick 做出了新决策。
     */
    public boolean tick(C ctx, long now) {
        // 0. ★ 先消化【其他循环 / 思维层】发布的影响器
        //    这是"共用一个弹簧空间"的合流点：副手循环执行的动作，
        //    主循环在这一步就能看见它的影响。
        recordAll(space.tickExternalImpacts(config));

        // 1. 执行门控：上一个动作没完就不开下一个
        if (inFlight != null) {
            inFlightRemaining--;
            if (inFlightRemaining > 0) {
                return false;
            }
            // 兜底：执行器没回报，但承诺时长走完了
            // ★★ 2026-10-01：承诺到期【不等于】执行器说完了 —— 引导类法术的长度是运行时才知道的
            //    （见 BusySource 的说明）⇒ 执行器还在忙就继续等，别一边引导一边另开动作。
            //    但等待有上限（看门狗①）：执行层的 bug 不能把决策层锁死。
            if (busySource.busy(ctx)) {
                if (++busyWaitTicks <= BUSY_WAIT_LIMIT) {
                    inFlightRemaining = 1;                 // 下一 tick 再问一次
                    return false;
                }
                record("★ 看门狗①：执行器报「还在忙」超过 " + BUSY_WAIT_LIMIT + " tick ⇒ "
                        + "放弃等待、解除承诺：" + inFlight.id() + "（执行层可能漏清在途状态）");
                busyWaitTicks = 0;
                // ★★ 第二十一轮：**并且记住"不要再看这个在途状态"** ——
                //   否则下一步的"无承诺 + 还在忙"门（1.5）会从头再等 200 tick，
                //   等于把看门狗①"10 秒后放行"的承诺又抹掉（她就又卡 10 秒）。
                busyIgnored = true;
                finishInFlight();
                return false;
            }
            busyWaitTicks = 0;
            record("承诺到期（执行器未回报完成）：" + inFlight.id());
            finishInFlight();
            return false;
        }

        // 1.5 ★★★ 执行器还在忙（而这一拍**没有承诺**）⇒ 也不开新决策。
        //   ★ 为什么必须有这一段（第二十一轮，委托方实测「让她只用铁魔法，她隔了好久才放出一个法术」）：
        //     `BusySource` 的注释从第一天写的就是**无条件**的「在执行器说"还在忙"期间，
        //     本循环不开新决策」，而实现只写在**承诺到期之后**那一支里
        //     ⇒ 只有 `commitmentTicks() > 0` 的动作才享受它。
        //     而铁魔法的前摇（`Phase.CAST_WINDUP`）恰好是"承诺 0 + 执行器确实在忙"：
        //     `irons:cast_spell` 是**族动作**（清单里的静态承诺只能给族默认 = 瞬发），
        //     真正的蓄力时长由**运行时挑中的那颗法术**决定（`castType=LONG` ⇒ 15~20 tick）
        //     ⇒ 决策层每 tick 重选一次 ⇒ 重选到同一个动作就**重新起手**（`readyAt` 被覆盖）
        //     ⇒ 前摇永远走不完 ⇒ 她只有"连续 15 拍都选中同一条"时才偶然放出一个法术
        //     （日志实证：18:04:06–18:04:09 每秒起手 2~3 次，`fang_ward`/`summon_vex` 交替重启）。
        //   ★ 与承诺门的分工：承诺是**清单给的静态估计**，执行器是**真值**
        //     （docs/09 §5.3：「承诺门的权威来源是执行器」）⇒ 没有估算时就更该问真值。
        if (inFlight == null) {
            if (!activelyBusy(ctx)) {
                busyWaitTicks = 0;
                busyIgnored = false;      // ★ 在途状态清干净了 ⇒ 看门狗的记忆一起清
            } else if (!busyIgnored) {
                if (++busyWaitTicks <= BUSY_WAIT_LIMIT) {
                    return false;         // 她确实在做（前摇/连发/引导）⇒ 这一拍不另开动作
                }
                // 看门狗②：与①同一条纪律 —— 执行层可能漏清在途状态，
                //   绝不许它把决策层锁死 ⇒ 超时后**照常决策**（并留下可读痕迹）。
                //   ★ `busyIgnored` 是**闩锁**：一旦放弃就不再每 200 tick 重来一次，
                //     直到执行器自己把在途状态清掉（否则"放行一次又等 10 秒"，等于没放行）。
                record("★ 看门狗②：执行器报「还在忙」超过 " + BUSY_WAIT_LIMIT
                        + " tick 且这一拍没有承诺 ⇒ 不再等它，照常决策");
                busyWaitTicks = 0;
                busyIgnored = true;
            }
        }

        // 2. 周期节流
        if (lastDecisionAt != Long.MIN_VALUE && now - lastDecisionAt < config.decisionInterval()) {
            return false;
        }
        lastDecisionAt = now;

        // 3~4. 偏置 → 忘却 → 规整，【全部走"发布影响器"这条路】
        //    ★ 与动作层、思维层同构：本循环自己也只是一个"影响的发布者"
        //    ★ 顺序即语义：先受迫位移（+b），再忘却（×(1−λ)），最后统一规整 —— 三者一次 drain
        boolean published = false;
        NeedVector b = biasSource.bias(ctx);
        if (b != null) {
            space.publish(SpringImpact.bias(b, loopName + ":context", "态势基线"));
            published = true;
        }
        space.publish(SpringImpact.decay(config, loopName + ":forget"));
        published = true;
        if (published) {
            recordAll(space.drain(config));
        }

        NeedVector p = space.point();

        // 5. 死区
        if (p.isNearOrigin(config)) {
            List<CandidateAction> defaults = defaultActions.available(ctx);
            if (!defaults.isEmpty()) {
                commit(defaults.get(0), ctx, now, "死区");
                return true;
            }
            return false;
        }

        // 6. 载体解析
        List<CandidateAction> available = actionProvider.available(ctx);
        if (available.isEmpty()) {
            List<CandidateAction> fallback = fallbackActions.available(ctx);
            if (fallback.isEmpty()) {
                record("无可用动作且无回退 —— 本 tick 不动作");
                return false;
            }
            commit(fallback.get(0), ctx, now, "回退（可用集合为空）");
            return true;
        }

        // 7. 选解
        lastMitigNeed = p == null ? 0.0 : p.get(NeedAxis.MITIGATION_SURVIVAL);
        selector.noteMitigNeed(lastMitigNeed);
        ActionSelector.Selection sel = selector.select(p, available, now);
        Optional<CandidateAction> chosen = sel.chosen();
        if (chosen.isEmpty()) {
            return false;
        }

        // 8. 提交执行
        commit(chosen.get(), ctx, now, sel.describe());
        record(sel.describe() + "  需求=" + p.toCompactString(config));
        return true;
    }

    private void commit(CandidateAction action, C ctx, long now, String why) {
        boolean done = executor.execute(action, ctx);

        // ★★ 没做出来 ⇒ 【不扣代价】（docs/09 §5.3 修正 ④ 的推广：只对"真发生的事"付费）
        //    ⚠️ 也不更新 lastUsed / lastChosenId —— 她"想做的"和"能做的"必须分开，
        //       否则步法会按一个根本做不出来的招式去站位。
        if (!done) {
            record("⚠ 执行未成功（不扣代价）：" + action.id() + " ← " + why);
            return;
        }

        // ★ 立即【发布】扣减（不是等结束）—— 承诺语义：我准备付这个代价了
        // ★★ 2026-10-01：扣减量走 TuningBus 的「满足度」规则（只改结算量，不改选解距离）——
        //    委托方第 3 条要求「下调攻击端评分，使攻击需求需要更多攻击完成」。
        //    若直接改 vectors.json，动作在需求空间里的【定位】也会一起变
        //    ⇒ 高攻击需求时反而可能不再选它。分开之后语义干净：选择行为不变，只是"更不解渴"。
        space.publish(SpringImpact.subtract(
                tuning.satisfactionVector(action.effectiveVector()),
                loopName + ":" + action.id(), action.id()));
        recordAll(space.drain(config));

        lastUsed.put(action.id(), now);
        lastChosenId = action.id();

        if (action.commitmentTicks() > 0) {
            inFlight = action;
            inFlightTotal = action.commitmentTicks();
            inFlightRemaining = inFlightTotal;
        }
        record("执行 " + action.id() + "  ← " + why);
    }

    /** ★ 指定本条循环使用的旋钮总线（每女仆一条）。返回 {@code this} 便于链式调用。 */
    public DecisionCycle<C> withTuning(TuningBus bus) {
        if (bus != null) {
            this.tuning = bus;
        }
        return this;
    }

    /**
     * ★★ <b>接上"连段关系"</b>（第十六轮）：把清单里 {@code comboDeps} 的查询函数交给选解器。
     *
     * <p>★ 为什么由外部接线：本类是**纯逻辑**的，它不该认识 {@code CarrierResolver}
     * （那是 carrier 层的）。⇒ 谁有清单，谁在构造循环时接一次。
     */
    public DecisionCycle<C> withComboPreds(
            java.util.function.Function<String, java.util.List<String>> comboPredsOf) {
        selector.wireChain(() -> lastChosenId, comboPredsOf);
        selector.wireChainBonus(tuning::chainBonus, tuning::repeatPenalty);
        return this;
    }


    /**
     * ★★ <b>接上"执行器还在忙"的来源</b>（见 {@link BusySource}）。
     *
     * <p>行为层传的是"执行层有在途动作"。
     */
    public DecisionCycle<C> withBusySource(BusySource<C> source) {
        if (source != null) {
            this.busySource = source;
        }
        return this;
    }

    /**
     * ★★ <b>接上"她本人正在做动作"的来源</b>（第二十一轮，见 {@link #activeBusySource}）。
     *
     * <p>与 {@link #withBusySource} 的区别：那个是"这条动作做完了没"（含纯冷却），
     * 这个是"她能不能另开一个动作"（纯冷却不算）。
     */
    public DecisionCycle<C> withActiveBusySource(BusySource<C> source) {
        if (source != null) {
            this.activeBusySource = source;
        }
        return this;
    }

    /** 本条循环正在用的总线（诊断用）。 */
    public TuningBus tuning() {
        return tuning;
    }

    private void finishInFlight() {
        inFlight = null;
        inFlightTotal = 0;
        inFlightRemaining = 0;
    }

    // ───────────────────────── 完成 / 打断 / 退还 ─────────────────────────

    /**
     * ★ <b>执行器回报"动作已执行完毕"</b> —— 委托方要求的门控的权威来源。
     *
     * <p>为什么必须由执行器回报：<b>"打完一梭子""一轮三连发结束""法术结算完毕"
     * 这些只有执行器知道</b>，静态 tick 数只是估计值。
     * 回报后循环立刻可以开启下一个决策周期。
     *
     * @return 是否真的有动作被结束
     */
    public boolean notifyCompleted() {
        if (inFlight == null) {
            return false;
        }
        record("执行器回报完成：" + inFlight.id());
        busyWaitTicks = 0;
        finishInFlight();
        return true;
    }

    /**
     * 通知"当前执行中的动作被打断"。
     *
     * <p>按<b>剩余比例退还</b>：{@code p += u · (剩余/总)}。
     * ⇒ 避免"没放出来却被扣了代价"——这是 [docs/09 §5.3] 的修正 ④。
     * <p>★ 退还也走"发布影响器"，因此与其它影响<b>按发布顺序</b>合流。
     *
     * @return 是否真的有动作被打断
     */
    public boolean notifyInterrupted() {
        if (inFlight == null) {
            return false;
        }
        double ratio = inFlightTotal <= 0 ? 0.0 : (double) inFlightRemaining / inFlightTotal;
        space.publish(SpringImpact.refund(
                tuning.satisfactionVector(inFlight.effectiveVector()),
                ratio, loopName + ":" + inFlight.id(), inFlight.id()));
        recordAll(space.drain(config));
        record("打断 " + inFlight.id() + "，退还比例 "
                + String.format(java.util.Locale.ROOT, "%.2f", ratio));
        finishInFlight();
        return true;
    }

    /** 目标失效等情况下的强制重置（只重置本循环的执行状态，不动共享空间）。 */
    public void reset() {
        lastDecisionAt = Long.MIN_VALUE;
        finishInFlight();
        lastUsed.clear();
        lastDebugLines.clear();
    }

    /** 连同共享空间一起重置（脱战）。 */
    public void resetAll() {
        reset();
        space.reset();
    }

    // ───────────────────────── 观测 ─────────────────────────

    /** 当前弹簧点（转发自共享空间）。 */
    public NeedVector need() {
        return space.point();
    }

    /** 是否有动作正在执行中（门控状态）。 */
    public boolean isCommitted() {
        return inFlight != null;
    }

    /** 正在执行的动作 id，无则 null。 */
    public String inFlightId() {
        return inFlight == null ? null : inFlight.id();
    }

    /**
     * ★ 最近一次提交的动作 id（跨周期保留）—— 步法用它决定站位。
     * <p>与 {@link #inFlightId()} 的区别：{@code inFlightId} 只在<b>承诺期内</b>非 null；
     * 本方法在动作做完之后仍然记得"她刚才/现在想干什么"。
     */
    public String lastChosenId() {
        return lastChosenId;
    }

    /** 最近一次使用某动作的时刻（{@code Long.MIN_VALUE} = 从未）。 */
    public long lastUsedAt(String actionId) {
        return lastUsed.getOrDefault(actionId, Long.MIN_VALUE);
    }

    /** 取出并清空最近的调试记录（供日志打印）。 */
    public List<String> drainDebug() {
        List<String> copy = List.copyOf(lastDebugLines);
        lastDebugLines.clear();
        return copy;
    }

    private void record(String line) {
        if (lastDebugLines.size() >= 12) {
            lastDebugLines.remove(0);
        }
        lastDebugLines.add("[" + loopName + " t=" + clock.getAsLong() + "] " + line);
    }

    private void recordAll(List<String> lines) {
        for (String l : lines) {
            record(l);
        }
    }
}
