package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * 对弹簧空间的一次影响 —— <b>以"函数"的形式发布，而不是直接改写点</b>。
 *
 * <h2>为什么改成"发布函数"（委托方要求）</h2>
 * 原来是"执行动作时直接 {@code p ← p − u}"。这有三个问题：
 * <ol>
 *   <li><b>两个循环会互相看不见</b> —— 主循环与副手循环共用一个弹簧空间，
 *       但副手执行的动作若直接改 {@code p}，主循环无从知道自己错过了什么；</li>
 *   <li><b>顺序不确定</b> —— 同一 tick 里多个来源（主循环动作、副手动作、态势偏置、思维层）
 *       都想影响 {@code p}，谁的先？直接赋值时这个顺序是隐式的、依赖调用点的；</li>
 *   <li><b>思维层没有位置</b> —— 思维层要"推一把"弹簧，却只能去改 {@code p} 本身，绕过了规整。</li>
 * </ol>
 *
 * <p>改成 <b>"发布 → 按发布顺序逐个执行"</b> 之后：
 * <ul>
 *   <li>每个影响器都是 {@code NeedVector → NeedVector} 的<b>纯函数</b>（易测、可复现）；</li>
 *   <li>顺序<b>显式</b>且由"发布顺序"决定，不再依赖调用点；</li>
 *   <li>**动作层与思维层用同一套接口** —— 思维层就是"再发布一个影响器"；</li>
 *   <li>两个循环的影响<b>天然合流</b>到同一个空间。</li>
 * </ul>
 *
 * @see SpringSpace
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.7</a>
 */
public interface SpringImpact {

    /**
     * 施加影响。
     *
     * @param p      当前弹簧点（<b>不要原地修改</b>，{@link NeedVector} 本身也是不可变的）
     * @param config 参数表（规整所需）
     * @return 新的弹簧点
     */
    NeedVector apply(NeedVector p, SpringConfig config);

    /** 发布者 —— 用于日志与调试（如 {@code "main:slashblade:combo_a"}）。 */
    String source();

    /** 一行描述，用于 {@code drainDebug}。 */
    String describe();

    // ───────────────────────── 常用工厂 ─────────────────────────

    /**
     * <b>动作扣减</b>：{@code p ← p − u}。
     *
     * <p>★ 这是"收益与代价用同一个向量表达"的落点：
     * {@code u} 的负分量在减法后自动<b>推高</b>该轴需求（见 {@link NeedVector} 的符号语义）。
     */
    static SpringImpact subtract(NeedVector u, String source, String label) {
        return new SpringImpact() {
            @Override
            public NeedVector apply(NeedVector p, SpringConfig config) {
                return p.minus(u);
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String describe() {
                return "扣减 " + label;
            }
        };
    }

    /**
     * <b>打断退还</b>：{@code p ← p + u·ratio}（按未完成比例返还）。
     *
     * <p>[docs/09 §5.3] 的修正 ④：没放出来就不该被全额扣代价。
     */
    static SpringImpact refund(NeedVector u, double ratio, String source, String label) {
        return new SpringImpact() {
            @Override
            public NeedVector apply(NeedVector p, SpringConfig config) {
                if (ratio <= 0.0) {
                    return p;
                }
                return p.plus(u.scaled(ratio));
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String describe() {
                return "退还 " + label + " ×" + String.format(java.util.Locale.ROOT, "%.2f", ratio);
            }
        };
    }

    /**
     * <b>偏置</b>：{@code p ← p + b}（态势基线偏置 / 思维层推挤）。
     *
     * <p>★ 思维层将来要"决策出一个决策向量"并推弹簧，用的就是这一个工厂 ——
     * <b>动作层与思维层因此共用同一个接口</b>。
     */
    static SpringImpact bias(NeedVector b, String source, String label) {
        return new SpringImpact() {
            @Override
            public NeedVector apply(NeedVector p, SpringConfig config) {
                return p.plus(b);
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String describe() {
                return "偏置 " + label;
            }
        };
    }

    /**
     * <b>忘却</b>：{@code p ← p·(1−λ)}（把整体往原点拉一次）。
     *
     * <p>正常由 {@link DecisionCycle} 的每周期规整自动做；单独暴露是为了让
     * <b>思维层也能显式地"催忘"</b>（例如"强制冷静下来，忘掉刚才的执念"）。
     */
    static SpringImpact decay(SpringConfig config, String source) {
        return new SpringImpact() {
            @Override
            public NeedVector apply(NeedVector p, SpringConfig cfg) {
                return p.decayed(cfg);
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String describe() {
                return "忘却（整体向原点回归）";
            }
        };
    }

    /**
     * <b>附加忘却</b>：{@code p_i ← p_i·(1 − extraLambda(axis))} —— <b>逐轴可不同</b>。
     *
     * <p>与 {@link #decay} 的区别：{@code decay} 用的是参数表里<b>固定</b>的 λ；
     * 本工厂允许调用方按<b>局势</b>给出一个额外的 λ（可为负 = 减速忘却）。
     *
     * <p>★ 首要用例（2026-10-01，委托方提的第 ② 条机制）：<b>让生存轴的忘却随血量变化</b> ——
     * 血越满，"暴露/生存"忘得越快（治 D6 里"满血也在累积暴露"的病根）；血越少越难忘。
     * 规则本身是纯函数，见
     * {@link com.touhoulittlemad.fightlikeplayer.carrier.SpringDynamics#extraDecayOf}。
     *
     * @param extraLambda 每轴的附加忘却率；返回 0 = 该轴不受影响
     */
    static SpringImpact extraDecay(SpringConfig config,
                                   java.util.function.ToDoubleFunction<NeedAxis> extraLambda,
                                   String source) {
        return new SpringImpact() {
            @Override
            public NeedVector apply(NeedVector p, SpringConfig cfg) {
                double[] out = new double[NeedAxis.COUNT];
                for (int i = 0; i < out.length; i++) {
                    NeedAxis axis = NeedAxis.values()[i];
                    // 总衰减系数钳在 [0,1]：负的附加 λ 只是"变慢"，不会让 p 反向增长
                    double keep = 1.0 - extraLambda.applyAsDouble(axis);
                    keep = keep < 0 ? 0 : (keep > 1 ? 1 : keep);
                    out[i] = p.get(i) * keep;
                }
                return NeedVector.wrap(out);
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String describe() {
                return "附加忘却（逐轴，随局势）";
            }
        };
    }

    /**
     * <b>强制置零</b>（脱离战斗 / 重置）。
     * <p>这是唯一"不尊重规整"的影响器，因此单独命名，避免被误用。
     */
    static SpringImpact reset(String source) {
        return new SpringImpact() {
            @Override
            public NeedVector apply(NeedVector p, SpringConfig config) {
                return NeedVector.zeros();
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String describe() {
                return "重置弹簧到原点";
            }
        };
    }
}
