package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * 候选动作 —— 载体解析的输出单元。
 *
 * <p>它把三样东西捆在一起交给选解器：
 * <ul>
 *   <li>{@code id} —— 动作标识（清单里的 `id`，便于日志与执行层寻址）</li>
 *   <li>{@code staticVector} —— <b>写在清单里的静态向量</b> {@code v_x}（"这招在战术上值多少"）</li>
 *   <li>{@code effectiveVector} —— 应用了<b>契合度乘数</b>与<b>修饰载体</b>之后的 {@code u_x}</li>
 * </ul>
 *
 * <h2>为什么静态向量与有效向量都要留着</h2>
 * 调试时最常问的两个问题是"为什么没选它"与"为什么选了它"：
 * <ul>
 *   <li>看 {@code staticVector} ⇒ 是不是清单里的分就打错了（<b>作者问题</b>）；</li>
 *   <li>看 {@code effectiveVector} ⇒ 是不是情境因子把它压掉了（<b>运行时问题</b>）。</li>
 * </ul>
 * 只留一个就无法区分这两类问题。
 *
 * <p>{@code commitmentTicks} 是承诺时长：{@code 0} = 瞬发；{@code >0} = 期间不重选决策。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §3.5 / §5.3</a>
 */
public record CandidateAction(
        String id,
        NeedVector staticVector,
        NeedVector effectiveVector,
        int commitmentTicks,
        /** 是否可被打断。不可打断者（如引导型法术）在承诺期内连打断都不允许。 */
        boolean interruptible
) {

    public CandidateAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("动作 id 不能为空");
        }
        if (commitmentTicks < 0) {
            throw new IllegalArgumentException("承诺时长不能为负：" + commitmentTicks);
        }
    }

    /** 瞬发、可被打断的便利构造。 */
    public static CandidateAction instant(String id, NeedVector vector) {
        return new CandidateAction(id, vector, vector, 0, true);
    }

    /**
     * 应用契合度乘数（逐轴）与修饰载体（逐轴），产生有效向量。
     *
     * <p>注意：<b>乘数只作用在该动作本来就有值的轴上</b>（{@code v[d] == 0} 时乘什么都是 0）
     * ⇒ 作者只需写静态向量，情境适配是运行时统一的。
     */
    public CandidateAction withFactors(NeedVector fitnessFactors, NeedVector modifierFactors) {
        NeedVector eff = staticVector
                .multiplyComponentwise(fitnessFactors)
                .multiplyComponentwise(modifierFactors);
        return new CandidateAction(id, staticVector, eff, commitmentTicks, interruptible);
    }

    /** 是否瞬发。 */
    public boolean isInstant() {
        return commitmentTicks == 0;
    }
}
