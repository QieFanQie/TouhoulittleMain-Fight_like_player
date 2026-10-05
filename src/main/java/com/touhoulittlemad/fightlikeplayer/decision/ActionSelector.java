package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.ToLongFunction;

/**
 * 最近邻选解器 —— 弹簧循环的第 7 步。
 *
 * <pre>x* = argmin_{x ∈ 可用} [ d(p, u_x) + recencyPenalty(x) + jitter(x) ]</pre>
 *
 * <h2>三处刻意的设计</h2>
 * <ol>
 *   <li><b>加权欧氏而非余弦</b> —— 余弦忽略大小 ⇒ "需要一点治疗"会选中"巨型全体治疗"。
 *       见 {@link NeedVector#weightedDistance}。</li>
 *   <li><b>平局带 + recency</b> —— 两个动作距离相等时会 A→B→A→B 横跳。
 *       距离差小于 {@code τ} 即视为平局，转由"最近最少使用"打破。</li>
 *   <li><b>抖动必须可关</b> —— 自测与确定性回归用 {@link SpringConfig.Builder#deterministic()}。</li>
 * </ol>
 *
 * <p>⚠️ <b>recency 惩罚要小</b>（默认 ρ = 0.05）：主力轮换效果本来就来自 `p` 的位移，
 * 惩罚只是打破平局的辅助。把它调大就会变成"为了轮换而轮换"，与弹簧机制重复且更差。
 */
public final class ActionSelector {

    private final SpringConfig config;
    private final Random random;
    /** 查询"某动作上次被执行的时刻（tick）"；缺省视作"很久以前"。 */
    private final ToLongFunction<String> lastUsedLookup;

    public ActionSelector(SpringConfig config, Random random, ToLongFunction<String> lastUsedLookup) {
        this.config = config;
        this.random = random;
        this.lastUsedLookup = lastUsedLookup;
        // ★ 默认出口（★ 不能写成字段初始化 —— 字段初始化时 config 还没赋值）
        this.bonusOf = config::chainBonus;
        this.penaltyOf = config::repeatPenalty;
    }

    /** 单次选解的结果，含全部候选的得分 —— <b>日志与调试需要它</b>。 */
    public record Selection(Optional<CandidateAction> chosen, List<Scored> scored, boolean tieBroken) {
        /** 一行摘要，用于 DEBUG 日志。 */
        public String describe() {
            if (scored.isEmpty()) {
                return "无候选动作";
            }
            Scored best = scored.get(0);
            return "选中 " + best.action().id()
                    + "  d=" + String.format(java.util.Locale.ROOT, "%.4f", best.distance())
                    + (tieBroken ? "（平局由 recency 打破）" : "")
                    + "  候选 " + scored.size() + " 个";
        }
    }

    /** 一个候选的得分明细。 */
    public record Scored(CandidateAction action, double distance, double recencyPenalty, double jitter, double total) {
    }

    public Selection select(NeedVector need, List<CandidateAction> candidates, long now) {
        if (candidates.isEmpty()) {
            return new Selection(Optional.empty(), List.of(), false);
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (CandidateAction c : candidates) {
            double d = need.weightedDistance(c.effectiveVector(), config);
            double pen = recencyPenalty(c.id(), now);
            double jit = config.jitter() > 0.0 ? random.nextDouble() * config.jitter() : 0.0;
            double chain = chainAdjust(c.id());
            scored.add(new Scored(c, d, pen, jit, d + pen + jit + chain));
        }
        scored.sort(Comparator.comparingDouble(Scored::total));

        double bestTotal = scored.get(0).total();
        // 平局带：距离差小于 τ 的候选都算"并列第一"，在其中选最久没用过的
        List<Scored> tied = scored.stream()
                .filter(s -> s.total() - bestTotal <= config.tieWindow())
                .toList();

        boolean tieBroken = tied.size() > 1;
        Scored pick = tieBroken
                ? tied.stream().min(Comparator.comparingLong(s -> lastUsedLookup.applyAsLong(s.action().id()))).orElse(tied.get(0))
                : scored.get(0);

        // 把选中的排到最前，方便日志取用
        List<Scored> ordered = new ArrayList<>(scored);
        ordered.remove(pick);
        ordered.add(0, pick);
        return new Selection(Optional.of(pick.action()), List.copyOf(ordered), tieBroken);
    }

    // ───────────────── ★★ 连段奖励 / 不复读（第十六轮）─────────────────

    /** ★ 上一拍选了什么（由 {@code DecisionCycle} 每拍喂；{@code null} = 没有上一拍）。 */
    private java.util.function.Supplier<String> lastChosen = () -> null;

    /** ★ 某动作的**声明前驱**（来自清单 {@code comboDeps}；由 {@code DecisionCycle} 喂）。 */
    private java.util.function.Function<String, java.util.List<String>> comboPredsOf = id -> java.util.List.of();

    /** ★ 当前需求向量的防御分量（她是不是在挨打）—— 由 {@code DecisionCycle} 每拍喂。 */
    private double mitigNeed;

    /** 每拍喂一次"她现在有多需要安全"（用于连段奖励的护栏）。 */
    public void noteMitigNeed(double v) {
        this.mitigNeed = v;
    }

    /** ★ 奖励/惩罚的取值出口（默认退回配置常量；接线后由 {@code TuningBus} 提供，可调可关）。 */
    private java.util.function.DoubleSupplier bonusOf;
    private java.util.function.DoubleSupplier penaltyOf;

    /** ★ 接上旋钮（{@code combo.bonus} / {@code combo.repeatPenalty}）。 */
    public void wireChainBonus(java.util.function.DoubleSupplier bonus,
                               java.util.function.DoubleSupplier penalty) {
        if (bonus != null) {
            this.bonusOf = bonus;
        }
        if (penalty != null) {
            this.penaltyOf = penalty;
        }
    }

    /** 接线（{@code DecisionCycle} 构造时调一次）。 */
    public void wireChain(java.util.function.Supplier<String> lastChosen,
                          java.util.function.Function<String, java.util.List<String>> comboPredsOf) {
        if (lastChosen != null) {
            this.lastChosen = lastChosen;
        }
        if (comboPredsOf != null) {
            this.comboPredsOf = comboPredsOf;
        }
    }

    /**
     * ★★ <b>连段奖励 / 不复读的计分</b>（返回要**加到距离上**的量；正 = 变差）。
     *
     * <p>委托方原话：「我指的连段是**连段类技能**（比如打一套斩击 &gt; 打一次斩击）而非继续之前的行为……
     * **我们不鼓励复读，你可以反向做成鼓励多样化**。」
     * <ul>
     *   <li>上一拍是**声明前驱** ⇒ 返回 {@code -chainBonus}（奖励）；</li>
     *   <li>上一拍**同族但不是声明后继** ⇒ 返回 {@code +repeatPenalty}（★ 不复读 / 鼓励多样化）；</li>
     *   <li>★ 她真的在挨打（{@code p[MITIG] ≥ 1.0}）时**整个不生效** —— 命比连段重要；</li>
     *   <li>没有任何上一拍 / 没有清单关系 ⇒ 0（行为与从前**逐字一致**）。</li>
     * </ul>
     */
    private double chainAdjust(String id) {
        return chainAdjustOf(lastChosen.get(), id, comboPredsOf.apply(id), mitigNeed,
                bonusOf.getAsDouble(), penaltyOf.getAsDouble());
    }

    /**
     * ★★ <b>连段奖励 / 不复读的规则本体</b>（纯函数 ⇒ 可离线断言）。
     *
     * <p>委托方原话：「我指的连段是**连段类技能**（比如打一套斩击 &gt; 打一次斩击）而非继续之前的行为……
     * **我们不鼓励复读，你可以反向做成鼓励多样化**。」
     *
     * @param last    上一拍选的动作 id（{@code null}/空 = 没有上一拍）
     * @param id      这一拍某个候选的 id
     * @param preds   这个候选的**声明前驱**（清单 {@code comboDeps}）
     * @param mitig   当前需求向量的防御分量（{@code >= 1.0} ⇒ 她真的在挨打 ⇒ 规则整体失效）
     * @param bonus   连段奖励（**必须 &gt; τ=0.05**，否则会被平局带 + LRU 吃掉）
     * @param penalty 反复读惩罚
     * @return 要**加到距离上**的量：负 = 更优，正 = 更差
     */
    public static double chainAdjustOf(String last, String id, java.util.List<String> preds,
                                       double mitig, double bonus, double penalty) {
        if (last == null || last.isBlank() || last.equals(id)) {
            return 0.0;                                     // 没有上一拍 / 就是同一个 id：不动
        }
        if (mitig >= 1.0) {
            return 0.0;                                     // 在挨打：不为了连段硬冲
        }
        if (preds != null && preds.contains(last)) {
            return -bonus;                                  // ★ 奖励：打一套
        }
        if (sameFamily(last, id)) {
            return penalty;                                 // ★ 不复读：同族但不是声明后继 ⇒ 变差
        }
        return 0.0;
    }

    /**
     * ★ 同族判定（纯字符串，可离线断言）：命名空间相同，且第一个 {@code _} 之前的部分相同。
     * 例：{@code slashblade:combo_a} 与 {@code slashblade:combo_b_finish} ⇒ 同族
     * （family = {@code slashblade:combo}）。
     */
    public static boolean sameFamily(String a, String b) {
        return family(a).equals(family(b));
    }

    /** 取"族"（{@code slashblade:combo_a_ex} ⇒ {@code slashblade:combo}）。 */
    public static String family(String id) {
        if (id == null) {
            return "";
        }
        int colon = id.indexOf(':');
        if (colon < 0) {
            return id;
        }
        String ns = id.substring(0, colon + 1);
        String rest = id.substring(colon + 1);
        int under = rest.indexOf('_');
        return ns + (under < 0 ? rest : rest.substring(0, under));
    }

    private double recencyPenalty(String id, long now) {
        if (config.recencyPenalty() <= 0.0) {
            return 0.0;
        }
        long last = lastUsedLookup.applyAsLong(id);
        if (last == Long.MIN_VALUE) {
            return 0.0; // 从未用过 ⇒ 无惩罚
        }
        long elapsed = Math.max(0, now - last);
        return config.recencyPenalty() * Math.exp(-elapsed / config.recencyTau());
    }
}
