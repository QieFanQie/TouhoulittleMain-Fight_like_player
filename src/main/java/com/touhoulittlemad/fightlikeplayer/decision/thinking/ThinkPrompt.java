package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>把「局势」变成「问题」，再把「答案」变成 8 轴决策向量</b> —— 思维层的纯逻辑核心。
 *
 * <h2>为什么是"按 8 轴直接打分"（docs/11 §5.1 的 P1 决议）</h2>
 * 备选方案是"让模型选一个动作"。被否掉，理由有两条硬伤：
 * <ol>
 *   <li><b>动作清单会变</b>（77 条且会增删）⇒ 每次增删都要改提示词、模型行为不可复现；</li>
 *   <li><b>模型看不到动作的真实数值</b>（{@code v_x} 是 8 维向量，写进提示词等于把打分表塞给它）——
 *       那正是<b>决策层已经在做的事</b>，重复一遍只会引入第二套不一致的评分标准。</li>
 * </ol>
 * ⇒ 结论：<b>思维层只回答"此刻该往哪个方向使劲"（8 轴），"具体选哪招"留给最近近邻。</b>
 * 这与 docs/11 的核心判断一致 —— <b>思维层与动作层共用弹簧这一个接口。</b>
 *
 * <h2>★★ 两类轴的等级设计（这是本类最需要小心的部分）</h2>
 * <table border="1">
 *   <tr><th>轴</th><th>JEV 问题类型</th><th>等级</th><th>映射</th></tr>
 *   <tr><td>7 条无向轴</td><td>{@code score} 5 档</td>
 *       <td>not useful → … → essential</td><td>{@code v = score/4} ∈ [0,1]</td></tr>
 *   <tr><td>★ {@link NeedAxis#MOBILITY}</td><td>{@code score} 5 档</td>
 *       <td>must retreat → … → must charge in</td><td>{@code v = (score−2)/2} ∈ [−1,1]</td></tr>
 * </table>
 * <p>★ <b>为什么位移单独一套等级</b>：{@code MOBILITY} 是 8 轴里<b>唯一"负值有语义"</b>的轴
 * （{@code v > 0} = 想靠近，{@code v < 0} = 想远离）。其余 7 轴的"负"是<b>代价</b>，
 * 让模型去判断"负的需求"没有意义 ⇒ 它们恒为非负。
 *
 * <p>★ <b>为什么用 5 档而不是 3 档</b>：{@code score} 返回的是<b>概率加权连续分</b>
 * （如 {@code 2.31}），5 档给出 [0,4] 的连续区间，分辨率足够；
 * 2 档会把"有点用"和"非常有用"压成同一个值。
 *
 * <h2>★ 取连续分，而不是取 argmax 等级</h2>
 * JEV 的价值恰恰在于"它给的是分布"。取 {@code scoreValue} 的连续分能保留
 * "1.6 分"这种"介于两档之间"的信息；取 argmax 就退化成了一次粗糙分类。
 *
 * @see JevClient
 * @see Thinker
 */
public final class ThinkPrompt {

    private ThinkPrompt() {
    }

    // ───────────────────────── 问题定义 ─────────────────────────

    /** 7 条无向轴共用的等级（由低到高）。 */
    public static final List<String> LEVELS_UNSIGNED = List.of(
            "not useful at all",
            "mildly useful",
            "useful",
            "strongly useful",
            "essential right now");

    /** ★ 位移专用等级：从"必须远离"到"必须突进"。 */
    public static final List<String> LEVELS_MOBILITY = List.of(
            "must retreat away from the enemy",
            "back off a little",
            "hold current distance",
            "close in",
            "must charge straight in");

    /** 问题 ID ↔ 轴的对应（★ 一律按 ID 取答案，绝不按下标）。 */
    public static final Map<String, NeedAxis> AXIS_OF = axisOf();

    /** 问题 ID → 中文轴名（只用于日志/命令）。 */
    public static final Map<String, String> ZH_OF = zhOf();

    /** ★ 用 LinkedHashMap + unmodifiableMap 而不是 {@code Map.of} / {@code Map.copyOf}：
     *  后两者的<b>迭代顺序未定义</b>，会让 {@code missing} 列表与日志顺序在两次运行间不一致
     *  （自测因而不可复现）。 */
    private static Map<String, NeedAxis> axisOf() {
        Map<String, NeedAxis> m = new LinkedHashMap<>();
        m.put("single_damage", NeedAxis.SINGLE_DAMAGE);
        m.put("area_damage", NeedAxis.AREA_DAMAGE);
        m.put("heal_survival", NeedAxis.HEAL_SURVIVAL);
        m.put("mitigation_survival", NeedAxis.MITIGATION_SURVIVAL);
        m.put("control", NeedAxis.CONTROL);
        m.put("mobility", NeedAxis.MOBILITY);
        m.put("reinforce", NeedAxis.REINFORCE);
        m.put("ally_care", NeedAxis.ALLY_CARE);
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<String, String> zhOf() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, NeedAxis> e : AXIS_OF.entrySet()) {
            m.put(e.getKey(), e.getValue().zhName());
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * ★★ 构造问题集合 —— <b>一次请求里问 8 个问题，JEV 并行判定</b>（不增加往返）。
     *
     * <p>★ 提示词的写法有意与 {@code catalog/data/*.json} 里的 {@code effects} 语义对齐：
     * 每条轴的说明都在回答"这一招在多大程度上满足了这个方向的需求"，
     * 因为模型打出的分数<b>要和动作的 {@code v_x} 比距离</b> ——
     * 若两边对同一条轴的理解不一致，最近近邻一定会选错。
     */
    public static Map<String, Object> questions() {
        return questions(Map.of());
    }

    /**
     * ★★ 同上，但允许<b>逐轴覆写问法</b>（委托方要求：配置界面能编辑提示词）。
     *
     * <p>★ 覆写只换 {@code instructions} 文本，<b>不换等级、不换映射</b> ——
     * 等级数量一变，{@code score → 向量} 的分段映射就错了（那是代码里的算术，不是提示词）。
     * 因此"能改的"与"不能改的"在这里被刻意分开。
     *
     * @param overrides 轴 id → 问法（键不在 {@link #AXIS_OF} 里的会被忽略）
     */
    public static Map<String, Object> questions(Map<String, String> overrides) {
        Map<String, String> ov = overrides == null ? Map.of() : overrides;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("single_damage", JevClient.score(
                questionOf(ov, "single_damage",
                        "How much does the current situation call for dealing damage to ONE single target?"),
                LEVELS_UNSIGNED.toArray(new String[0])));
        q.put("area_damage", JevClient.score(
                questionOf(ov, "area_damage",
                        "How much does the current situation call for hitting MANY enemies at once "
                                + "(area of effect damage)?"),
                LEVELS_UNSIGNED.toArray(new String[0])));
        q.put("heal_survival", JevClient.score(
                questionOf(ov, "heal_survival",
                        "How much does the current situation call for RECOVERING health "
                                + "(healing, regeneration, lifesteal)? Judge by how hurt she or her owner is."),
                LEVELS_UNSIGNED.toArray(new String[0])));
        q.put("mitigation_survival", JevClient.score(
                questionOf(ov, "mitigation_survival",
                        "How much does the current situation call for AVOIDING incoming damage WITHOUT healing "
                                + "(blocking, dodging, shields, damage reduction, breaking line of sight)?"),
                LEVELS_UNSIGNED.toArray(new String[0])));
        q.put("control", JevClient.score(
                questionOf(ov, "control",
                        "How much does the current situation call for RESTRICTING the enemies' freedom of action "
                                + "(slowing, stunning, knockback, displacement, crowd control)?"),
                LEVELS_UNSIGNED.toArray(new String[0])));
        q.put("mobility", JevClient.score(
                questionOf(ov, "mobility",
                        "In which direction should she move right now? "
                                + "Answer from 'must retreat' to 'must charge in'. "
                                + "Consider her weapon's ideal range and how badly she is hurt."),
                LEVELS_MOBILITY.toArray(new String[0])));
        q.put("reinforce", JevClient.score(
                questionOf(ov, "reinforce",
                        "How much does the current situation call for RAISING her combat power in ways that are "
                                + "NOT healing (summoning servants, buffs, strength, speed, resistance)?"),
                LEVELS_UNSIGNED.toArray(new String[0])));
        q.put("ally_care", JevClient.score(
                questionOf(ov, "ally_care",
                        "How much does the current situation call for PROTECTING or aiding her owner or allies "
                                + "(drawing enemies away, body-blocking, healing the owner)?"),
                LEVELS_UNSIGNED.toArray(new String[0])));
        return q;
    }

    /**
     * 取某轴的问法：<b>覆写优先，空/空白回退内置</b>。
     *
     * <p>★ 回退而不是报错：配置被写坏时，正确的降级是"用内置的继续跑"，
     * 而不是让思维层整层失效（一个行为模组不该因为一行配置变哑）。
     */
    private static String questionOf(Map<String, String> ov, String key, String builtin) {
        String v = ov.get(key);
        return v == null || v.isBlank() ? builtin : v;
    }

    // ───────────────────────── 答案 → 向量 ─────────────────────────

    /** 一次映射的结果：向量 + 逐轴原始分 + 置信度 + 哪些轴没答上来。 */
    public record Verdict(
            NeedVector vector,
            Map<NeedAxis, Double> raw,
            Map<NeedAxis, Double> confidence,
            List<NeedAxis> missing
    ) {
        /** 最低置信度（没有任何 confidence 时返回 1.0 = 不因置信度拦截）。 */
        public double minConfidence() {
            double min = Double.NaN;
            for (double c : confidence.values()) {
                min = Double.isNaN(min) ? c : Math.min(min, c);
            }
            return Double.isNaN(min) ? 1.0 : Math.min(min, 1.0);
        }

        /** 一行摘要（日志/命令用）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (NeedAxis a : NeedAxis.values()) {
                Double v = raw.get(a);
                if (v == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(a.zhName()).append('=').append(String.format(java.util.Locale.ROOT, "%.2f", v));
            }
            return sb.toString();
        }
    }

    /**
     * ★★ <b>把 JEV 的返回映射成 8 轴决策向量</b>。
     *
     * <p>规则：
     * <ul>
     *   <li>某条轴<b>没有答案</b> ⇒ 该轴记 0（<b>不是</b>猜一个默认值）并登记进 {@link Verdict#missing()}；
     *       整包是否可用由调用方按"缺失比例"决定；</li>
     *   <li>分数<b>越界</b>（模型给了 5.2）⇒ 钳到等级区间，而不是丢弃整包；</li>
     *   <li>返回值已经乘上 {@code strength}（偏置强度）。</li>
     * </ul>
     *
     * @param response JEV 的原始返回
     * @param strength 偏置强度（0 = 只观测不推动）
     */
    public static Verdict verdict(Map<String, Object> response, double strength) {
        Map<NeedAxis, Double> raw = new LinkedHashMap<>();
        Map<NeedAxis, Double> conf = new LinkedHashMap<>();
        java.util.List<NeedAxis> missing = new java.util.ArrayList<>();
        double[] values = new double[NeedAxis.COUNT];

        for (Map.Entry<String, NeedAxis> e : AXIS_OF.entrySet()) {
            String id = e.getKey();
            NeedAxis axis = e.getValue();

            double score = JevClient.scoreValue(response, id);
            if (Double.isNaN(score)) {
                missing.add(axis);
                continue;
            }
            double clamped = Math.max(0.0, Math.min(LEVELS_UNSIGNED.size() - 1.0, score));
            double v = axis.isSigned()
                    ? (clamped - 2.0) / 2.0          // MOBILITY：[-1, 1]
                    : clamped / 4.0;                  // 无向轴：[0, 1]
            raw.put(axis, clamped);
            values[axis.ordinal()] = v * strength;

            Double c = JevClient.confidence(response, id);
            conf.put(axis, c == null ? 1.0 : c);
        }
        return new Verdict(NeedVector.wrap(values), Map.copyOf(raw), Map.copyOf(conf), List.copyOf(missing));
    }

    /**
     * ★ 生成**只含"确实做了什么"的自然语言摘要**（供日志与 {@code /flp think last}）。
     * <p>刻意不复述分数表顺序 —— 中文轴名 + 原始分已经够用。
     */
    public static String describeCombination(Verdict v) {
        return "向量 " + v.vector().toCompactString(
                com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults())
                + "　原始分 " + v.summary();
    }
}
