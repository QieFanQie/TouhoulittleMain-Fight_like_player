package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>身法（步法）的"两轴评分"</b> —— 第十六轮，委托方要求：**给身法侧加防御分**。
 *
 * <h2>委托方原话</h2>
 * > 「既然有堆积防御需求这一点，那么**给身法侧增加防御分数**就好了，**身法不需要物品，
 * > 不论何时都是可行端口**。比如增加：『面向仇恨目标向右后方撤』+ 它的左后版本，
 * > 这两者有**防御端**的分数。这样就好了。」
 * > 「（身法侧的动作该不会只有身法分数吧？（但我建议**只局限在身法侧和防御侧**，在这方面我们可以补全一下））」
 *
 * <h2>★★ 修的是什么（取证结论）</h2>
 * 此前身法是伺服 {@code GaitSelector}，**只由 `p[MOBILITY]` 一个轴**决定
 * （`< −ε` ⇒ 后撤 / `> +ε` ⇒ 靠近 / 否则原地），**清单里根本没有身法条目**。
 * 而实测里 `p[MITIGATION_SURVIVAL]` 中位 **1.96**、**91% 顶到上限 2.0**
 * （攻击的负 MITIG 在喂养它、λ 只有 0.01、没有释放通路）
 * ⇒ 需求在堆积，而身法侧**完全没利用它**：她挨打时只知道"要不要拉开距离"，
 * 不知道"**撤得更稳**"也是一种防守。
 *
 * <h2>★ 判据：只动【身法侧 + 防御侧】两个轴（委托方的边界）</h2>
 * 每个身法选项带一个 2 轴向量 `(MOBILITY, MITIGATION_SURVIVAL)`，
 * 用与"选动作"**同一套最近邻**去挑（加权欧氏，权重 MOBILITY=1.0、MITIG=2.0 与主轴一致）：
 * <table border="1">
 *   <tr><th>选项</th><th>MOBILITY</th><th>MITIG</th><th>什么时候该选它</th></tr>
 *   <tr><td>原地 HOLD</td><td>0</td><td>0</td><td>两个需求都平</td></tr>
 *   <tr><td>靠近 TOWARD</td><td>+0.8</td><td>0</td><td>想贴上去</td></tr>
 *   <tr><td>正后撤 AWAY</td><td><b>−1.0</b></td><td>+0.2</td><td>就是要拉开距离（跑）</td></tr>
 *   <tr><td>左/右后撤</td><td>−0.6</td><td><b>+0.8</b></td><td>★ 在挨打、需要"撤得稳"（防御分高）</td></tr>
 * </table>
 * ⇒ `p[MITIG]` 高时自然选**斜后撤**；`p[MOBILITY]` 极负（就是想跑）时选**正后方**。
 * ★ 这两个选项**不需要任何物品** ⇒ 是"**永远可行**的防守出口"：
 * 即使她被指令限制到只剩一把武器，身法仍然能做防守。
 *
 * <p>★ 本类**纯逻辑**（只吃两个 double + 距离），零 MC 依赖 ⇒ 可离线断言。
 * ★ 真正的移动由 {@code ActionExecutors#gait} 做（斜后撤 = 把"背离方向"水平旋转 ±35°）。
 */
public final class GaitScoring {

    private GaitScoring() {
    }

    /**
     * 一个身法选项的 2 轴向量（**只在这两个轴上**，别的轴一律不参与 —— 委托方给的边界）。
     *
     * @param mobility 正 = 靠近，负 = 拉开
     * @param mitig    正 = 更安全（防御）
     */
    public record Option(Gait.Direction direction, double mobility, double mitig) {
    }

    /** 选项表（顺序稳定；同分时靠前的优先）。 */
    public static final List<Option> OPTIONS = List.of(
            new Option(Gait.Direction.TOWARD, 0.8, 0.0),
            new Option(Gait.Direction.AWAY, -1.0, 0.2),
            new Option(Gait.Direction.AWAY_LEFT, -0.6, 0.8),
            new Option(Gait.Direction.AWAY_RIGHT, -0.6, 0.8),
            new Option(Gait.Direction.HOLD, 0.0, 0.0));

    /** 两轴权重（★ 与主轴一致：MOBILITY 1.0 / MITIG 2.0，见 {@code NeedAxis}）。 */
    public static final double W_MOBILITY = 1.0;
    public static final double W_MITIG = 2.0;

    /**
     * ★★ <b>挑一个身法选项</b>（最近邻，只在 MOBILITY / MITIG 两轴上）。
     *
     * @param pMobility      需求向量的位移分量（正 = 想靠近）
     * @param pMitig         需求向量的防御分量（越高越"需要更安全"）
     * @param canRetreat     ★ 允许后退吗（`no_retreat` 指令时为 false ⇒ 后撤三兄弟全排除）
     * @param alreadyInRange 她已经站在合适的距离内（⇒ 不靠近也不远离，原地最省）
     * @return 选中的方向（**永不返回 null**；一个都挑不出时返回 {@link Gait.Direction#HOLD}）
     */
    public static Gait.Direction pick(double pMobility, double pMitig, boolean canRetreat,
                                      boolean alreadyInRange) {
        List<Option> pool = new ArrayList<>();
        for (Option o : OPTIONS) {
            if (!canRetreat && o.direction().isRetreat()) {
                continue;                        // ★ 死战不退：三个后撤全排除
            }
            if (alreadyInRange && o.direction() == Gait.Direction.TOWARD) {
                continue;                        // 已经到位 ⇒ 不必再凑近
            }
            pool.add(o);
        }
        if (pool.isEmpty()) {
            return Gait.Direction.HOLD;
        }
        Option best = null;
        double bestD = Double.MAX_VALUE;
        for (Option o : pool) {
            double dm = (o.mobility() - pMobility) * W_MOBILITY;
            double di = (o.mitig() - pMitig) * W_MITIG;
            double d = dm * dm + di * di;
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best == null ? Gait.Direction.HOLD : best.direction();
    }

    /** 一行诊断（日志/命令）。 */
    public static String describe(double pMobility, double pMitig, Gait.Direction chosen) {
        return String.format(java.util.Locale.ROOT,
                "身法选 %s（位移需求 %.2f，防御需求 %.2f）", chosen.zh(), pMobility, pMitig);
    }
}
