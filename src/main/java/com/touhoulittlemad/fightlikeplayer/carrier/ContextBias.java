package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

/**
 * 态势基线偏置 —— <b>规则版的"决策实质"</b>（docs/09 §5.2 的修正 ⑥）。
 *
 * <h2>★ 为什么这个类至关重要（一次自我检查的结论）</h2>
 * 弹簧机制（{@code DecisionCycle}）只是<b>机械</b>：它把需求点 `p` 与动作向量做最近邻。
 * 但 **`p` 从哪里来？** 若没有本类，`b` 恒为 0 ⇒ `p` 永远停在原点 ⇒
 * **命中死区 ⇒ 永远只走"默认动作"** ⇒ <b>整个决策层什么都不会做</b>。
 *
 * <p>⇒ 本类才是"女仆为什么会动"的答案。它是**思维层接入前的替代品，也是接入后的兜底**。
 *
 * <h2>规则表（与 docs/09 §5.2 一一对应）</h2>
 * <table border="1">
 *   <tr><th>条件</th><th>偏置</th></tr>
 *   <tr><td>有目标且在射程内</td><td>{@code SINGLE_DAMAGE +0.3}</td></tr>
 *   <tr><td>附近敌人 ≥ 3</td><td>{@code AREA_DAMAGE +0.4}</td></tr>
 *   <tr><td>附近敌人 ≥ 6</td><td>{@code AREA_DAMAGE +0.8}（<b>替代</b>上一档，不叠加）</td></tr>
 *   <tr><td>自身血量 &lt; 40%</td><td>{@code MITIGATION_SURVIVAL +0.6}</td></tr>
 *   <tr><td>自身血量 &lt; 15%</td><td>{@code MITIGATION_SURVIVAL +1.0}、{@code SINGLE_DAMAGE −0.5}（<b>替代</b>）</td></tr>
 *   <tr><td>主人血量 &lt; 50%</td><td>{@code ALLY_CARE +0.5}</td></tr>
 *   <tr><td>距离目标 &gt; 8 格</td><td>{@code MOBILITY +0.3}（<b>正 = 想靠近</b>）</td></tr>
 *   <tr><td>附近无敌人</td><td>全 0 ⇒ 死区 ⇒ 默认动作</td></tr>
 * </table>
 *
 * <h2>★ 本轮按 8 轴模型做的两处适配</h2>
 * <ol>
 *   <li><b>旧 {@code SELF_SURVIVAL} 拆成两点</b>：低血时的偏置归
 *       {@link NeedAxis#MITIGATION_SURVIVAL}（"我需要不被打死"），
 *       <b>不是</b> {@link NeedAxis#HEAL_SURVIVAL}（"我需要回血"）。
 *       ★ 理由：低血<b>本身</b>只说明"危险"，不说明"该吃治疗"——
 *       该吃治疗还是该举盾，是**动作层**按哪个更近决定的（这正是拆成两点的意义）。</li>
 *   <li><b>{@code MOBILITY} 变成有向轴</b>：距离远 ⇒ 偏置取**正**（想靠近）。
 *       ⚠️ 反向（想远离）<b>不由血量规则产生</b> —— 那是思维层/直接指令表的事。
 *       ★ 理由：逃跑是**决策**而不是**本能**；把它写成"血少就想跑"会剥夺决策层的选择权。</li>
 * </ol>
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.2</a>
 */
public final class ContextBias {

    // ── 阈值（集中在这里，便于调参）──
    public static final int ENEMY_TIER_1 = 3;
    public static final int ENEMY_TIER_2 = 6;
    public static final double HP_LOW = 0.40;
    public static final double HP_CRITICAL = 0.15;
    public static final double OWNER_HP_LOW = 0.50;
    public static final double FAR_RANGE = 8.0;

    private ContextBias() {
    }

    /**
     * 由情境事实算出态势偏置。
     *
     * @return 偏置向量 `b`（未规整；由 {@code SpringSpace.drain} 统一规整）
     */
    public static NeedVector of(ContextFacts ctx) {
        ContextFacts.Vitals v = ctx.vitals();

        double single = 0.0;
        double area = 0.0;
        double mitigation = 0.0;
        double ally = 0.0;
        double mobility = 0.0;

        // ── 目标与敌人规模 ──
        if (ctx.hasTarget() && v.distanceToTarget() <= FAR_RANGE) {
            single += 0.3;
        }
        // ★ 分档取【最高命中档】，不叠加（否则"敌人多"会把 p 一次顶到上限，弹簧失去分辨率）
        if (v.nearbyEnemies() >= ENEMY_TIER_2) {
            area += 0.8;
        } else if (v.nearbyEnemies() >= ENEMY_TIER_1) {
            area += 0.4;
        }

        // ── 自身血量（同样分档取最高命中档）──
        if (v.selfHealthPct() < HP_CRITICAL) {
            mitigation += 1.0;
            single -= 0.5;          // 濒死时主动收敛输出
        } else if (v.selfHealthPct() < HP_LOW) {
            mitigation += 0.6;
        }

        // ── 主人血量 ──
        if (v.ownerHealthPct() < OWNER_HP_LOW) {
            ally += 0.5;
        }

        // ── 距离：远 ⇒ 想靠近（MOBILITY 正 = 靠近）──
        if (ctx.hasTarget() && v.distanceToTarget() > FAR_RANGE) {
            mobility += 0.3;
        }

        // ── 附近无敌人 ⇒ 全 0（死区）──
        boolean noThreat = v.nearbyEnemies() == 0 && !ctx.hasTarget();
        if (noThreat) {
            // ★ 但主人受伤仍需照顾 —— 那不是"战斗"，是"护理"
            if (v.ownerHealthPct() < OWNER_HP_LOW) {
                return NeedVector.of(NeedAxis.ALLY_CARE, ally);
            }
            return NeedVector.zeros();
        }

        // ★★ 增援需求（REINFORCE）—— 按委托方 2026-10-05 的要求**简化成"增量"**：
        //   > 「召唤算法没必要那么精致，维度上有增量和消耗就好了。」
        //   ⇒ 增量 = 权重 × (上限 − 已有仆从) / 上限（**没在打架 ⇒ 0**）；
        //     上限来自唯一的判据 {@link com.touhoulittlemad.fightlikeplayer.decision.ServantSlots}
        //      ⇒ 满了 ⇒ 增量 0 ⇒ 不需要召唤。
        //   ⇒ 消耗那一侧不需要新机制：法术自己的 `cooldownTicks` + 冷却账本（铁魔法恼鬼 150 秒）
        //     + 法力，本来就在动作层与执行器里。
        //   ★ 历史（别重踩）：上一版把"召唤位"做成 `FitnessCalculator` 的**因子**是**方向错的**
        //     （因子是乘法：`p[REINFORCE]==0` 时乘 0 会让动作向量更靠近原点 ⇒ 满位反而更想召唤）；
        //     而把这里抬高到 0.6 会把**所有**动作的加权距离一起放大 ⇒ 相对间距被压缩 ⇒
        //     更多动作落进 `tieWindow`（`PipelineSelfTest §7` 的收敛断言会红）。
        //     ⇒ 所以本轴取**小权重**，并且"满了就不进池子"那道门仍然留着（同一判据的第二次使用）。
        double reinforce = 0.0;
        //   ★ 前置：**她得真的能施法**（游戏侧喂的 `goety:can_cast` / `irons:can_cast`）。
        //     没装/没杖没聚晶/没法术书 ⇒ 放不出召唤 ⇒ "增量"无从谈起（也免得 p 多出一轴
        //     去挤别的动作的间距 —— 这正是自测红的那条）。
        boolean canConjure = ctx.customFact("goety:can_cast") || ctx.customFact("irons:can_cast");
        if (canConjure && ctx.hasTarget()) {
            int missing = com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP
                    - Math.max(0, ctx.servantCount());
            if (missing > 0) {
                reinforce = REINFORCE_INCREMENT_WEIGHT * missing
                        / com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP;
            }
        }

        return NeedVector.of(
                NeedAxis.SINGLE_DAMAGE, single,
                NeedAxis.AREA_DAMAGE, area,
                NeedAxis.MITIGATION_SURVIVAL, mitigation,
                NeedAxis.MOBILITY, mobility,
                NeedAxis.REINFORCE, reinforce,
                NeedAxis.ALLY_CARE, ally);
    }

    /**
     * ★ "增量"的权重（第十七轮续）：{@code p[REINFORCE] = 权重 × 缺几个 / 上限}。
     *
     * <p>取 0.5：缺满 4 个时该轴为 0.5（够把她推向"召唤"这一类动作，又不至于压过输出轴）；
     * ★ 不取更大值的原因写在上面那段注释里（会压缩所有动作的相对间距 ⇒ 触发并列轮换）。
     */
    public static final double REINFORCE_INCREMENT_WEIGHT = 0.5;

    /** 一行摘要，用于日志（"为什么它这么打"的第一手信息）。 */
    public static String explain(ContextFacts ctx) {
        ContextFacts.Vitals v = ctx.vitals();
        StringBuilder sb = new StringBuilder();
        if (ctx.hasTarget()) {
            sb.append("有目标(").append(String.format(java.util.Locale.ROOT, "%.1f", v.distanceToTarget()))
                    .append("格) ");
        }
        sb.append("敌").append(v.nearbyEnemies()).append(' ');
        sb.append("仆从").append(ctx.servantCount()).append(' ');
        sb.append("自血").append(pct(v.selfHealthPct())).append(' ');
        sb.append("主血").append(pct(v.ownerHealthPct()));
        return sb.toString();
    }

    private static String pct(double d) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", d * 100);
    }
}
