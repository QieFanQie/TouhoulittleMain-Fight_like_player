package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.Arrays;

/**
 * n 维向量（n = {@link NeedAxis#COUNT}）。
 *
 * <p>同时用于表示<b>需求点 p</b>、<b>动作向量 v</b>、<b>有效向量 u</b> 与<b>偏置 b</b> ——
 * 它们都在同一个语义空间里，这是"弹簧"能成立的前提。
 *
 * <h2>不可变</h2>
 * 所有运算返回新实例，内部数组不对外暴露（构造与取值都做防御性拷贝）。
 * 弹簧每个决策周期会做十几次向量运算，这类数量级下不可变对象比原地修改更不容易出错。
 *
 * <h2>符号语义（关键）</h2>
 * <ul>
 *   <li>作为<b>动作向量</b>时：{@code >0} 表示该动作<b>满足</b>该轴需求，{@code <0} 表示<b>制造</b>该轴需求。</li>
 *   <li>作为<b>需求点</b>时：恒 {@code >= 0}（由 {@link #normalize} 保证）。</li>
 * </ul>
 * ⇒ {@code p - v} 在 {@code v[d] < 0} 时<b>自动增大</b> {@code p[d]}，代价语义无需额外机制。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §3.2</a>
 */
public final class NeedVector {

    private final double[] values;

    private NeedVector(double[] values) {
        this.values = values;
    }

    // ───────────────────────── 构造 ─────────────────────────

    public static NeedVector zeros() {
        return new NeedVector(new double[NeedAxis.COUNT]);
    }

    /** 由 {@code axis -> value} 的稀疏写法构造，未提及的轴为 0。 */
    public static NeedVector of(Object... axisValuePairs) {
        if (axisValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("需要成对的 (NeedAxis, Number)");
        }
        double[] v = new double[NeedAxis.COUNT];
        for (int i = 0; i < axisValuePairs.length; i += 2) {
            NeedAxis axis = (NeedAxis) axisValuePairs[i];
            v[axis.ordinal()] = ((Number) axisValuePairs[i + 1]).doubleValue();
        }
        return new NeedVector(v);
    }

    /**
     * 由长度恰为 {@link NeedAxis#COUNT} 的数组构造。
     *
     * <p>★ 公开的理由：**调试命令需要按"8 个数字"注入决策向量**
     * （见 {@code /flp bias}）—— 那是"思维层的替身"，用来在**没有 JEV/LLM** 的情况下
     * 驱动弹簧、验证整条链路。⇒ 这个构造是那条调试通路的一部分。
     */
    public static NeedVector wrap(double[] values) {
        if (values.length != NeedAxis.COUNT) {
            throw new IllegalArgumentException("维度不匹配：" + values.length + " != " + NeedAxis.COUNT);
        }
        return new NeedVector(values.clone());
    }

    /** 按 {@link NeedAxis} 的顺序取一份数组（调试命令的显示用）。 */
    public double[] values() {
        return values.clone();
    }

    // ───────────────────────── 取值 ─────────────────────────

    public double get(NeedAxis axis) {
        return values[axis.ordinal()];
    }

    public double get(int index) {
        return values[index];
    }

    /** 复制一份可变数组（仅供需要原地演算的调用方使用）。 */
    public double[] toArray() {
        return values.clone();
    }

    // ───────────────────────── 运算 ─────────────────────────

    public NeedVector plus(NeedVector other) {
        double[] r = new double[NeedAxis.COUNT];
        for (int i = 0; i < r.length; i++) {
            r[i] = values[i] + other.values[i];
        }
        return new NeedVector(r);
    }

    /**
     * {@code this - other}。
     * <p>★ <b>不做钳制</b> —— 钳制是 {@link #normalize} 的职责。
     * 顺序必须是"先减、再规整"，否则负值会在减法前被吃掉、代价语义失效。
     */
    public NeedVector minus(NeedVector other) {
        double[] r = new double[NeedAxis.COUNT];
        for (int i = 0; i < r.length; i++) {
            r[i] = values[i] - other.values[i];
        }
        return new NeedVector(r);
    }

    /** 逐分量相乘 —— 契合度乘数与修饰载体都走这条。 */
    public NeedVector multiplyComponentwise(NeedVector other) {
        double[] r = new double[NeedAxis.COUNT];
        for (int i = 0; i < r.length; i++) {
            r[i] = values[i] * other.values[i];
        }
        return new NeedVector(r);
    }

    public NeedVector scaled(double factor) {
        double[] r = new double[NeedAxis.COUNT];
        for (int i = 0; i < r.length; i++) {
            r[i] = values[i] * factor;
        }
        return new NeedVector(r);
    }

    // ───────────────────────── 规整 ─────────────────────────

    /**
     * 规整三件套（顺序不可换）：非负钳制 → 上限钳制 → 死区。
     *
     * <p>★ <b>例外</b>：{@link NeedAxis#isSigned() 有向轴}（当前只有 {@code MOBILITY}）
     * <b>不做非负钳制</b>，而是钳到 {@code [-max, +max]} —— 因为它的负值表示
     * <b>"另一个方向的需求"</b>（远离），而不是代价。见 {@link NeedAxis#MOBILITY}。
     *
     * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §3.6</a>
     */
    public NeedVector normalize(SpringConfig config) {
        double[] r = new double[NeedAxis.COUNT];
        for (int i = 0; i < r.length; i++) {
            NeedAxis axis = NeedAxis.values()[i];
            double x = values[i];
            if (axis.isSigned()) {
                // 有向轴：保留符号，双向钳制
                x = Math.max(-config.max(axis), Math.min(config.max(axis), x));
            } else {
                x = Math.max(0.0, x);                  // ① 非负
                x = Math.min(config.max(axis), x);      // ② 上限
            }
            if (Math.abs(x) < config.epsilon(axis)) {   // ③ 死区
                x = 0.0;
            }
            r[i] = x;
        }
        return new NeedVector(r);
    }

    /** 忘却机制：{@code p <- p * (1 - lambda)}，逐轴不同。 */
    public NeedVector decayed(SpringConfig config) {
        double[] r = new double[NeedAxis.COUNT];
        for (int i = 0; i < r.length; i++) {
            NeedAxis axis = NeedAxis.values()[i];
            r[i] = values[i] * (1.0 - config.decay(axis));
        }
        return new NeedVector(r);
    }

    // ───────────────────────── 度量 ─────────────────────────

    /** 未加权 L2 范数。 */
    public double norm() {
        double s = 0.0;
        for (double v : values) {
            s += v * v;
        }
        return Math.sqrt(s);
    }

    /**
     * <b>加权欧氏距离</b> —— 最近邻选解的度量。
     *
     * <pre>d(p, u) = sqrt( Σ_d w_d · (p_d − u_d)² )</pre>
     *
     * <p><b>为什么不用余弦相似度</b>：余弦只看方向、忽略大小 ⇒
     * "需要一点治疗"时会选中"巨型全体治疗"（方向对、大小离谱）。
     * 加权欧氏能同时惩罚"方向不对"与"超出需求太多"，更贴合"够用就好"。
     *
     * <p><b>为什么权重有差异</b>：同权时 0.3 的输出收益与 0.3 的自保代价等价 ⇒
     * 女仆会用致命的自保换一点输出。<b>自保必须比输出更"贵"。</b>
     */
    public double weightedDistance(NeedVector other, SpringConfig config) {
        double s = 0.0;
        for (int i = 0; i < values.length; i++) {
            NeedAxis axis = NeedAxis.values()[i];
            double diff = values[i] - other.values[i];
            s += config.weight(axis) * diff * diff;
        }
        return Math.sqrt(s);
    }

    /** 是否落在死区（"离原点极近即算作原点"）。★ 有向轴比较绝对值。 */
    public boolean isNearOrigin(SpringConfig config) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i]) >= config.epsilon(NeedAxis.values()[i])) {
                return false;
            }
        }
        return true;
    }

    // ───────────────────────── 调试 ─────────────────────────

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(NeedAxis.values()[i].zhName()).append(' ').append(fmt(values[i]));
        }
        return sb.append(')').toString();
    }

    /** 紧凑形式，只列出非零轴 —— 日志里更好读。 */
    public String toCompactString(SpringConfig config) {
        StringBuilder sb = new StringBuilder("[");
        boolean any = false;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i]) < 1e-9) {
                continue;
            }
            if (any) {
                sb.append(' ');
            }
            sb.append(NeedAxis.values()[i].zhName()).append('=').append(fmt(values[i]));
            any = true;
        }
        return sb.append(any ? "]" : " 原点]").toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NeedVector other)) {
            return false;
        }
        return Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
