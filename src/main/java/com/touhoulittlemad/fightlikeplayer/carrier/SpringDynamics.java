package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

/**
 * <b>弹簧动力学的局势规则</b> —— 两条"随伤势变化"的机制（纯逻辑，零 Minecraft 依赖）。
 *
 * <p>委托方 2026-10-01 提出的三条机制里，这里实现前两条（第三条见类尾的说明）。
 * 它们都是<b>发布影响器</b>，与态势偏置、动作扣减、思维层走**同一个接口**
 * ⇒ 不新增机制，只是多了两个"影响的来源"。
 *
 * <h2>① 受伤冲击（{@link #onHurt}）</h2>
 * 被打中时立刻往弹簧里推一把，强度由<b>两件事共同</b>决定：
 * <ul>
 *   <li><b>这一下掉了多少血</b>（相对最大生命的比例）—— 决定"这一下有多疼"；</li>
 *   <li><b>剩多少血</b> —— 决定"疼完之后有多慌"（越残越慌）。</li>
 * </ul>
 * 它补的是 {@link ContextBias} 的不足：那边是**分档阈值**（&lt;40% 才给 MITIG +0.6），
 * 只在跨越阈值的瞬间变化；而"被砍了一刀"是一个**事件**，应当有即时的冲量。
 *
 * <h2>② 生存轴的动态忘却（{@link #extraDecayOf}）</h2>
 * 基础忘却率是固定的（{@code MITIGATION_SURVIVAL} 的 λ = 0.01，刻意的低 —— 残血时
 * 生存需求不该 1 秒就消失）。但固定 λ 有一个副作用（2026-10-01 实测暴露的 D6）：
 * <b>近战每挥一刀都会在 `p[MITIG]` 上累积 +0.1（因为它的向量里 MITIG 是负值 = 暴露代价），
 * 而 0.01 的忘却率让它久久不散</b> ⇒ 满血单敌时她也会偶尔"想撤退&脱战"。
 *
 * <p>⇒ 让忘却随血量变化：<b>血越满，暴露/生存需求忘得越快；血越少，忘得越慢（甚至可以完全不衰减）。</b>
 * 这既治了 D6 的病根，又强化了"残血时执念不散"的手感 —— 一个改动同时满足两个目标。
 *
 * <h2>③ 为什么不做"动态权重"（等效度量随血量变化）</h2>
 * 那是改<b>度量本身</b>（{@code NeedVector.weightedDistance} 里每轴的 {@code w_d}），
 * 会牵动核心选择逻辑与文档 §5.6 记录的全部距离数字（那些数字是自测钉住的），
 * 属"麻烦且有风险"。而它的**效果**——"血量低时更偏向生存"——可以用**抬高 p 的生存分量**
 * 等价达成，那正是 ①（受伤冲量）、②（难忘却）与现有 {@link ContextBias} 阈值在做的事。
 * ⇒ 不引入动态权重。
 *
 * @see <a href="../../../../../../../docs/04-开放问题.md">docs/04 Q20（本轮决策）</a>
 */
public final class SpringDynamics {

    private SpringDynamics() {
    }

    // ───────────────────────── ① 受伤冲击 ─────────────────────────

    /** 把"这一下很疼"定义为：掉血达到最大生命的这个比例即算满强度。 */
    public static final double DAMAGE_REFERENCE_FRACTION = 0.25;

    /** 即使只是擦伤，也有这一点基础自保需求。 */
    public static final double HURT_BASE = 0.5;

    /** 血量越低，同样的伤带来的"慌"越大（在基础值上加成的最大幅度）。 */
    public static final double HURT_LOW_HP_GAIN = 1.0;

    /**
     * 受伤时对弹簧空间的一次冲击。
     *
     * <pre>
     *   severity = clamp( 掉血 / (最大生命 × 0.25), 0, 1 )       ← 这一下有多疼
     *   urgency  = 0.5 + 1.0 × (1 − 剩余血量%)                    ← 疼完之后有多慌
     *   MITIGATION_SURVIVAL += severity × urgency                 ← 于是"我需要不被打到"
     * </pre>
     *
     * ★ <b>只推生存轴，不推任何方向</b>：至于她是该格挡、该拉开、还是该喝药，
     * 仍然是**决策层按最近邻去选**（这正是本项目的分工）——
     * 我们只表达"我现在更需要活下来"。
     *
     * @param healthPctAfter 受伤<b>之后</b>的剩余血量百分比（0~1）
     * @param damageTaken    这一下掉了多少点血
     * @param maxHealth      最大生命（≤0 时按"未知"处理，只按 seriousness 的上下限给中值）
     */
    public static NeedVector onHurt(double healthPctAfter, double damageTaken, double maxHealth) {
        double health = clamp01(healthPctAfter);
        double severity;
        if (maxHealth <= 0) {
            severity = 0.5;                          // 数据缺失 ⇒ 取中值，不假装精确
        } else {
            severity = clamp01(damageTaken / (maxHealth * DAMAGE_REFERENCE_FRACTION));
        }
        double urgency = HURT_BASE + HURT_LOW_HP_GAIN * (1.0 - health);
        double mitigation = severity * urgency;
        return NeedVector.of(NeedAxis.MITIGATION_SURVIVAL, mitigation);
    }

    // ───────────────────────── ② 生存轴的动态忘却 ─────────────────────────

    /** 满血时给生存轴【额外】加多少忘却率（正 = 忘得更快）。 */
    public static final double SURVIVAL_EXTRA_DECAY_MAX = 0.06;

    /**
     * 生存轴的<b>附加</b>忘却率。
     *
     * <pre>
     *   k = (剩余血量% − 0.5) × 2        ∈ [−1, +1]
     *   附加λ = k × 0.06                 ← 满血 +0.06（加速忘却）；残血 −0.036（减速）
     * </pre>
     *
     * <p>与基础 λ（{@code MITIGATION_SURVIVAL} = 0.01）叠加后：
     * <ul>
     *   <li><b>满血</b> ⇒ 0.07/周期 ⇒ 累积的暴露约 10 个决策周期（≈50 tick）衰减一半
     *       ⇒ **D6 里"满血还累积暴露然后想撤退"的现象消失**；</li>
     *   <li><b>血量 20%</b> ⇒ 0.01 − 0.036 &lt; 0 ⇒ 钳到 0 ⇒ <b>完全不衰减</b>
     *       ⇒ "残血时的执念不散"，与原本的设计意图一致。</li>
     * </ul>
     *
     * @return 只对生存两轴（{@code MITIGATION_SURVIVAL} / {@code HEAL_SURVIVAL}）非零，其余为 0
     */
    public static double extraDecayOf(NeedAxis axis, double healthPct) {
        if (axis != NeedAxis.MITIGATION_SURVIVAL && axis != NeedAxis.HEAL_SURVIVAL) {
            return 0.0;
        }
        double k = (clamp01(healthPct) - 0.5) * 2.0;
        return k * SURVIVAL_EXTRA_DECAY_MAX;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
