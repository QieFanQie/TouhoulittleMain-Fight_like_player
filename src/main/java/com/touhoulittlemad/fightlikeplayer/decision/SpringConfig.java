package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.EnumMap;
import java.util.Map;

/**
 * 弹簧参数表 —— 全部可调，默认值取自 <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.5</a>。
 *
 * <p>调优顺序建议：<b>先定 {@code weight}（决定性格保守/激进）→ 再调 {@code decay}（节奏快慢）
 * → 最后 {@code epsilon} / {@code max}（敏感度与上限）</b>。
 *
 * <p>本类不可变；用 {@link #builder()} 覆盖个别轴。
 */
public final class SpringConfig {

    /** 决策周期（tick）。越大越迟钝但越省。 */
    private final int decisionInterval;

    /** 平局窗口 τ：距离差小于它即视为平局，转由 recency 打破。 */
    private final double tieWindow;

    /** 平局时"刚用过"的惩罚强度 ρ。要小 —— 主力轮换来自 p 的位移，不靠惩罚。 */
    private final double recencyPenalty;

    /** recency 惩罚的时间常数（tick）。 */
    private final double recencyTau;

    /** 随机抖动幅度 δ，用于彻底打破对称平局。 */
    private final double jitter;

    private final Map<NeedAxis, Double> decay = new EnumMap<>(NeedAxis.class);
    private final Map<NeedAxis, Double> epsilon = new EnumMap<>(NeedAxis.class);
    private final Map<NeedAxis, Double> weight = new EnumMap<>(NeedAxis.class);
    private final Map<NeedAxis, Double> max = new EnumMap<>(NeedAxis.class);

    private SpringConfig(Builder b) {
        this.decisionInterval = b.decisionInterval;
        this.tieWindow = b.tieWindow;
        this.recencyPenalty = b.recencyPenalty;
        this.recencyTau = b.recencyTau;
        this.jitter = b.jitter;
        for (NeedAxis axis : NeedAxis.values()) {
            decay.put(axis, b.decay.getOrDefault(axis, axis.defaultDecay()));
            epsilon.put(axis, b.epsilon.getOrDefault(axis, axis.defaultEpsilon()));
            weight.put(axis, b.weight.getOrDefault(axis, axis.defaultWeight()));
            max.put(axis, b.max.getOrDefault(axis, axis.defaultMax()));
        }
    }

    public static SpringConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── 取值 ──
    public double decay(NeedAxis a)   { return decay.get(a); }
    public double epsilon(NeedAxis a) { return epsilon.get(a); }
    public double weight(NeedAxis a)  { return weight.get(a); }
    public double max(NeedAxis a)     { return max.get(a); }

    public int decisionInterval() { return decisionInterval; }
    /**
     * ★★ 连段奖励（第十六轮）：上一拍是**声明前驱**时给下一拍的加成。
     * <p>★ 必须 > {@code tieWindow()}（τ=0.05），否则会被平局带 + LRU 吃掉（实测）。
     */
    public double chainBonus() { return 0.12; }

    /**
     * ★★ 反向鼓励多样化（第十六轮，委托方要求"不复读"）：
     * 上一拍**同族但不是声明后继**时给的**惩罚**。
     */
    public double repeatPenalty() { return 0.12; }

    public double tieWindow()     { return tieWindow; }
    public double recencyPenalty() { return recencyPenalty; }
    public double recencyTau()    { return recencyTau; }
    public double jitter()        { return jitter; }

    /** 可变构造器。 */
    public static final class Builder {
        private int decisionInterval = 5;
        private double tieWindow = 0.05;
        private double recencyPenalty = 0.05;
        private double recencyTau = 100.0;
        private double jitter = 0.01;
        private final Map<NeedAxis, Double> decay = new EnumMap<>(NeedAxis.class);
        private final Map<NeedAxis, Double> epsilon = new EnumMap<>(NeedAxis.class);
        private final Map<NeedAxis, Double> weight = new EnumMap<>(NeedAxis.class);
        private final Map<NeedAxis, Double> max = new EnumMap<>(NeedAxis.class);

        public Builder decisionInterval(int v)   { this.decisionInterval = v; return this; }
        public Builder tieWindow(double v)       { this.tieWindow = v; return this; }
        public Builder recencyPenalty(double v)  { this.recencyPenalty = v; return this; }
        public Builder recencyTau(double v)      { this.recencyTau = v; return this; }
        public Builder jitter(double v)          { this.jitter = v; return this; }

        public Builder decay(NeedAxis a, double v)   { decay.put(a, v); return this; }
        public Builder epsilon(NeedAxis a, double v) { epsilon.put(a, v); return this; }
        public Builder weight(NeedAxis a, double v)  { weight.put(a, v); return this; }
        public Builder max(NeedAxis a, double v)     { max.put(a, v); return this; }

        /** 关掉全部随机性 —— <b>自测与确定性回归必须用它</b>。 */
        public Builder deterministic() {
            this.jitter = 0.0;
            this.recencyPenalty = 0.0;
            return this;
        }

        public SpringConfig build() {
            return new SpringConfig(this);
        }
    }
}
