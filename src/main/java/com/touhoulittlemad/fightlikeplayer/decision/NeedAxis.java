package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * 需求轴 —— 弹簧空间的维度。
 *
 * <h2>★ 最重要的性质：这些是「评分」，不是「归类」</h2>
 * 每条轴都是一个<b>连续的分数维度</b>，<b>不是互斥的类别桶</b>。
 * 一个动作可以同时在多条轴上有值（拔刀剑·防御 = 非回血生存 +0.9、单点 −0.3）。
 * ⇒ <b>不要用轴去做动作分类</b>；分类是 `carrier` / `role` / `kind` 的事。
 * 轴只回答一个问题：**"这一招在多大程度上满足了这个方向的需求？"**
 *
 * <h2>选轴准则（三条同时满足才可入选）</h2>
 * <ol>
 *   <li>由清单里的 {@code effects} <b>可推导</b>；</li>
 *   <li><b>玩家会为它专门选一个不同的操作</b>（否则它不该是独立轴）；</li>
 *   <li><b>能为负</b>（有代价）—— 负值表示"该动作制造了这个需求"。</li>
 * </ol>
 *
 * <h2>枚举顺序 = 向量的分量顺序</h2>
 * {@link NeedVector} 内部用 {@code double[values().length]} 存储，索引即本枚举的 {@link #ordinal()}。
 * ⇒ <b>新增轴只能追加在末尾</b>，不能插在中间（会静默改变所有已存向量的语义）。
 *
 * <h2>8 轴（生命周期变更记录）</h2>
 * 原 `SELF_SURVIVAL` 一个轴，按委托方要求<b>拆成两点</b>（回血生存 / 非回血生存）；
 * `REINFORCE` 收窄为<b>不含回血</b>的强化。见 docs/09 §3.1。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §3.1</a>
 */
public enum NeedAxis {
    /** 解决单个目标。由单体 {@code DAMAGE} 推导。 */
    SINGLE_DAMAGE(0.04, 0.02, 1.0, 1.0, false),

    /** 压制一群目标。由 AoE {@code DAMAGE} 推导。 */
    AREA_DAMAGE(0.04, 0.02, 1.0, 1.0, false),

    /**
     * ★ <b>回血生存</b>：靠<b>恢复生命</b>让自己活下去。
     *
     * <p>由 {@code HEAL} / 生命恢复 / 吸血 / 伤害吸收推导。
     * <p>λ 取得很小（0.01）：受伤后这个需求不应在 1 秒内消失。
     * <p>⚠️ <b>这是评分不是归类</b> —— 它只表示"这一招在多大程度上补回了血"。
     */
    HEAL_SURVIVAL(0.01, 0.02, 2.0, 2.0, false),

    /**
     * ★ <b>非回血生存</b>：<b>不靠回血</b>的生存手段 —— 格挡、闪避、减伤、位移规避。
     *
     * <p>由格挡 / 负 {@code APPLY_EFFECT} 于敌方 / 护盾 / 无敌帧推导。
     * <p>与 {@link #HEAL_SURVIVAL} 分开的理由：**两者是玩家会专门做不同选择的两件事**
     * （血少了你会决定"吃治疗"还是"举盾/拉开"），合成一条轴就丢掉了这个区分。
     *
     * <p>负值语义：<b>暴露</b>（如长引导期间无法格挡）⇒ 制造了非回血生存需求。
     */
    MITIGATION_SURVIVAL(0.01, 0.02, 2.0, 2.0, false),

    /** 限制敌方行动自由。由负面 {@code APPLY_EFFECT} / {@code DISPLACE} 推导。 */
    CONTROL(0.04, 0.02, 1.0, 1.0, false),

    /**
     * ★★ <b>位移（唯一的有向轴）</b> —— 本枚举里<b>唯一允许正负都有语义</b>的轴。
     *
     * <pre>
     *   v[MOBILITY] &gt; 0  ⇒ 前进 / 突进 / 追踪类走位      （想靠近）
     *   v[MOBILITY] &lt; 0  ⇒ 后撤 / 躲避 / 拉开距离类走位  （想远离）
     * </pre>
     *
     * <p><b>为什么只有它特殊</b>：其他轴的"负"是<b>代价</b>（"我制造了这个需求"）；
     * 而位移的"负"是<b>另一个方向的需求</b>（"我要远离"），两者语义完全不同。
     * ⇒ 因此 {@code p[MOBILITY]} 也<b>允许为负</b>，规整时钳到 {@code [-max, +max]} 而不是 {@code [0, max]}。
     *
     * <p>λ 稍大（0.06）：走位需求最容易过期 —— 位置一旦变了，需求就没了。
     */
    MOBILITY(0.06, 0.02, 1.0, 1.0, true),

    /**
     * ★ <b>强化（不含回血）</b>：召唤仆从、增益、战力提升。
     *
     * <p>由 {@code SUMMON} / 正面 {@code APPLY_EFFECT}（力量、急速、抗性）推导。
     * <p>⚠️ <b>回血类的提升不在这里</b> —— 按委托方要求，回血归 {@link #HEAL_SURVIVAL}。
     * ⇒ 本轴只评"<b>非回血的</b>战力增强"。
     */
    REINFORCE(0.03, 0.02, 1.0, 1.0, false),

    /** 照顾主人 / 友方。与生存同权重、同低忘却率。 */
    ALLY_CARE(0.01, 0.02, 2.0, 2.0, false);

    /** 分量个数 —— 所有向量的固定长度。 */
    public static final int COUNT = values().length;

    private final double defaultDecay;
    private final double defaultEpsilon;
    private final double defaultWeight;
    private final double defaultMax;
    private final boolean signed;

    NeedAxis(double defaultDecay, double defaultEpsilon, double defaultWeight,
             double defaultMax, boolean signed) {
        this.defaultDecay = defaultDecay;
        this.defaultEpsilon = defaultEpsilon;
        this.defaultWeight = defaultWeight;
        this.defaultMax = defaultMax;
        this.signed = signed;
    }

    public double defaultDecay() {
        return defaultDecay;
    }

    public double defaultEpsilon() {
        return defaultEpsilon;
    }

    public double defaultWeight() {
        return defaultWeight;
    }

    public double defaultMax() {
        return defaultMax;
    }

    /**
     * ★ 本轴是否允许负值<b>语义</b>（而不只是"代价"）。
     * <p>当前只有 {@link #MOBILITY} 为 {@code true}。
     */
    public boolean isSigned() {
        return signed;
    }

    /** 中文短名，只用于日志与自测输出。 */
    public String zhName() {
        return switch (this) {
            case SINGLE_DAMAGE -> "单点";
            case AREA_DAMAGE -> "群伤";
            case HEAL_SURVIVAL -> "回血";
            case MITIGATION_SURVIVAL -> "免伤";
            case CONTROL -> "控制";
            case MOBILITY -> "位移";
            case REINFORCE -> "强化";
            case ALLY_CARE -> "护友";
        };
    }
}
