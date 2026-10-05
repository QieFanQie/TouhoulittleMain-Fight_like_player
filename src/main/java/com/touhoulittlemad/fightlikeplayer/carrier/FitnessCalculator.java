package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

/**
 * 契合度乘数 `m_x[d]` —— 逐轴的<b>情境适配</b>（docs/09 §3.5 的修正 ③）。
 *
 * <h2>为什么必须有它</h2>
 * 清单里的 `v_x` 是<b>静态</b>的，但效果依赖情境：
 * <ul>
 *   <li>"治疗"在<b>满血时价值 0</b>，但静态向量会说它很划算；</li>
 *   <li>"AoE"在<b>只面对一个敌人</b>时价值很低；</li>
 *   <li>"突进"在<b>已经贴身</b>时毫无意义。</li>
 * </ul>
 * ⇒ 没有本类，女仆会在满血时给自己刷治疗、对单个敌人放大招。
 * <b>这是"看起来能动但很蠢"的典型症状。</b>
 *
 * <h2>语义</h2>
 * <pre>
 *   u_x[d] = v_x[d] · m_x[d]
 * </pre>
 * ★ 乘数<b>只作用在该动作本来就有值的轴上</b>（`v_x[d] = 0` 时乘什么都是 0）——
 * 因此作者只需写静态向量，情境适配是**运行时统一**的，不必为每个情境手写一套分数。
 *
 * <h2>乘数表（docs/09 §3.5）</h2>
 * <table border="1">
 *   <tr><th>轴</th><th>来源</th><th>规则</th></tr>
 *   <tr><td>{@code SINGLE_DAMAGE}</td><td>{@code rangeFit × targetPresent}</td><td>超出射程 ⇒ 0；无目标 ⇒ 0</td></tr>
 *   <tr><td>{@code AREA_DAMAGE}</td><td>{@code rangeFit × targetPresent × clusterFit}</td><td>{@code clusterFit = clamp(敌数/3, 0, 1)}；<b>对单个敌人 → 0.3</b></td></tr>
 *   <tr><td>{@code HEAL_SURVIVAL}</td><td>{@code deficitFit}</td><td>满血 → 0.2；&lt;40% → 1.0</td></tr>
 *   <tr><td>{@code MITIGATION_SURVIVAL}</td><td>{@code threatFit}</td><td>无威胁 → 0.3；低血/多敌 → 1.0</td></tr>
 *   <tr><td>{@code ALLY_CARE}</td><td>{@code allyNeedFit}</td><td>主人满血 → 0.2；&lt;50% → 1.0</td></tr>
 *   <tr><td>{@code CONTROL}</td><td>{@code targetPresent}</td><td>无目标 → 0</td></tr>
 *   <tr><td>{@code MOBILITY}</td><td>{@code gapFit}</td><td>已贴身且想靠近 → 0.2；距离远 → 1.0</td></tr>
 *   <tr><td>{@code REINFORCE}</td><td>{@code slotFit}</td><td>召唤位满 ⇒ <b>直接过滤</b>（不进评分）</td></tr>
 * </table>
 *
 * <p>★ <b>默认全 1.0</b>：任何"无法判定"的情形都回落到 1.0 而不是 0 ——
 * 缺省判 0 会让动作**静默消失**，比"判错"更难排查。
 *
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §3.5</a>
 */
public final class FitnessCalculator {

    /** 最大有效射程（格）—— 超过它，单体与群伤都判为打不到。 */
    public static double maxRange = 24.0;

    /** 近战距离（格）。 */
    public static double meleeRange = 3.0;


    private FitnessCalculator() {
    }

    /**
     * 算出逐轴乘数。
     *
     * @param ctx 情境事实
     * @return 乘数向量（各轴默认 1.0）
     */
    public static NeedVector factors(ContextFacts ctx) {
        ContextFacts.Vitals v = ctx.vitals();

        boolean hasTarget = ctx.hasTarget();
        double dist = v.distanceToTarget();
        int enemies = v.nearbyEnemies();

        // 射程契合：超出最大射程 ⇒ 打不到
        double rangeFit = !hasTarget ? 0.0
                : (dist > maxRange ? 0.0 : 1.0);

        // 目标存在
        double targetFit = hasTarget ? 1.0 : 0.0;

        // 聚集度：敌人越多，AoE 越划算；单个敌人时 AoE 只值 0.3
        double clusterFit = enemies <= 1
                ? (enemies == 1 ? 0.3 : 0.0)
                : Math.min(1.0, enemies / 3.0);

        // 缺血量契合：满血时治疗没什么用（但不是 0 —— 预留一点，避免完全锁死）
        double hp = v.selfHealthPct();
        double deficitFit = hp >= 0.95 ? 0.2 : (hp < 0.40 ? 1.0 : 0.6 + (0.95 - hp));

        // 威胁契合：没有威胁时防御动作价值低
        double threatFit = (!hasTarget && enemies == 0) ? 0.3
                : (hp < 0.40 || enemies >= ContextBias.ENEMY_TIER_1 ? 1.0 : 0.7);

        // 友方需求
        double ownerHp = v.ownerHealthPct();
        double allyNeedFit = ownerHp >= 0.95 ? 0.2 : (ownerHp < 0.50 ? 1.0 : 0.6);

        // ★★ 关于"召唤位"为什么**不是**一个因子（第十七轮续，我踩过并撤回）：
        //   因子的语义是**逐分量相乘**，而 p[REINFORCE] == 0 ⇒ 把召唤动作的 REINFORCE
        //   分量乘 0 会让它的向量**更靠近原点** ⇒ **召唤位满时反而更想召唤** ——
        //   与"召唤位满 ⇒ 直接过滤"完全相反。
        //   ⇒ 这道门只能做成**过滤**，已落在 `CarrierResolver` 里（解析期丢弃 + 可读原因）。

        // 距离间隔契合（有向轴：正=想靠近）
        double gapFit = !hasTarget ? 0.3
                : (dist <= meleeRange ? 0.2 : Math.min(1.0, dist / 8.0));

        return NeedVector.of(
                NeedAxis.SINGLE_DAMAGE, rangeFit * targetFit,
                NeedAxis.AREA_DAMAGE, rangeFit * targetFit * clamp01(clusterFit),
                NeedAxis.HEAL_SURVIVAL, clamp01(deficitFit),
                NeedAxis.MITIGATION_SURVIVAL, clamp01(threatFit),
                NeedAxis.CONTROL, targetFit,
                NeedAxis.MOBILITY, clamp01(gapFit),
                // ★ REINFORCE 轴保持常量 1.0（别再往这里塞"召唤位"因子 —— 见下面那段说明）
                NeedAxis.REINFORCE, 1.0,
                NeedAxis.ALLY_CARE, clamp01(allyNeedFit));
    }

    /**
     * 修饰载体（盔甲/饰品）带来的额外乘数（docs/09 §4.2）。
     * <p>⚠️ 目前返回全 1.0（<b>占位</b>）—— 需要读属性才能算，属 M4。
     * 登记为 W21。
     */
    public static NeedVector modifierFactors(ContextFacts ctx) {
        return NeedVector.of();   // 空 = 全 0；注意 CandidateAction.withFactors 是逐分量相乘
    }

    /** 用 1.0 填充的"无修饰"乘数（正确地表示"不改动"）。 */
    public static NeedVector noModifiers() {
        return NeedVector.of(
                NeedAxis.SINGLE_DAMAGE, 1.0,
                NeedAxis.AREA_DAMAGE, 1.0,
                NeedAxis.HEAL_SURVIVAL, 1.0,
                NeedAxis.MITIGATION_SURVIVAL, 1.0,
                NeedAxis.CONTROL, 1.0,
                NeedAxis.MOBILITY, 1.0,
                NeedAxis.REINFORCE, 1.0,
                NeedAxis.ALLY_CARE, 1.0);
    }

    private static double clamp01(double d) {
        return Math.max(0.0, Math.min(1.0, d));
    }
}
