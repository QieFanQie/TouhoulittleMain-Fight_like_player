package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>调控顾问</b> —— 让 LLM「决定风格」而不是「决定战斗」（委托方 2026-10-01 第 6 条）。
 *
 * <h2>★★ 为什么用 JEV 的 {@code score} 而不是"让模型输出 JSON 补丁"</h2>
 * 直觉上想给模型一句话："输出一个 JSON 补丁 {@code {"satisfaction.damage":0.6}}"。<b>但那是错的</b>：
 * <ol>
 *   <li><b>JEV 根本不会输出自由文本</b> —— 它是 System One 决策模型，
 *       只回答 {@code noul}/{@code choice}/{@code score}（见 {@link JevClient} 的类注释）。
 *       让它"写 JSON" 等于换一个模型、换一套风险；</li>
 *   <li>★ <b>自由文本补丁要面对"值不合法/键不存在/越界"的一堆校验</b>，
 *       而这些校验在 {@code score} 形态下<b>由构造保证不可能发生</b> ——
 *       答案是一个 0~4 的连续分，映射区间是我们写死的。</li>
 * </ol>
 * ⇒ 每个旋钮变成<b>一个 {@code score} 问题</b>：等级从"调到最小"到"调到最大"，
 * 中间那档（第 2 档 / 共 5 档）<b>恰好等于当前默认值</b>
 * ⇒ <b>模型回答"中间"就等于"别动"</b>，语义天然保守，而且不需要任何解析容错。
 *
 * <h2>★ 映射为什么要"以默认值为中心"而不是线性铺满 [min,max]</h2>
 * 若线性铺满，则"中间档"会落在 {@code (min+max)/2}，
 * 而那<b>通常不等于默认值</b>（例如 {@code cooldown.scale} 默认 1.0，区间 0.2~5.0 ⇒ 中点 2.6）
 * ⇒ <b>模型每回答一次"中间"，体系就被推离默认值一次</b>，几十轮之后漂到边界。
 * ⇒ 本实现取 <b>分段线性 + 默认值为中档</b>：
 * <pre>
 *   score ∈ [0,2]  ⇒  def + (min−def) × (2−score)/2
 *   score ∈ [2,4]  ⇒  def + (max−def) × (score−2)/2
 *   score = 2      ⇒  def          ★ 严格相等
 * </pre>
 * ⇒ <b>"不动"是一个不动点。</b> 这一条写进自测，因为它是整个机制不漂移的前提。
 *
 * @see TuningBus
 * @see BehaviorStats
 */
public final class Advisor {

    private Advisor() {
    }

    /** 5 档的通用中文/英文标签（由低到高）。 */
    public static final List<String> LEVELS = List.of(
            "much lower",
            "a bit lower",
            "keep as it is now",
            "a bit higher",
            "much higher");

    /** 哪些旋钮交给顾问调（★ 不是全部：{@code failure.backoff} 是保险丝，不该被"风格"改掉）。 */
    public static final List<String> TUNABLE = List.of(
            "satisfaction.damage",
            "satisfaction.all",
            "cooldown.scale",
            "bias.aggression",
            "bias.caution");

    /** 每个旋钮的"问法"（英文，JEV 以英文为主）。 */
    private static final Map<String, String> QUESTION = Map.of(
            "satisfaction.damage",
            "How satisfying should a single attack be for this maid? "
                    + "Lower means each attack satisfies less, so she will need to attack more often "
                    + "before her urge to attack goes away. Higher means one good hit settles it.",
            "satisfaction.all",
            "In general, how quickly should doing something make her feel satisfied "
                    + "(for ALL kinds of actions, not only attacks)? "
                    + "Lower means she will keep doing things more persistently.",
            "cooldown.scale",
            "How long should she wait between two actions (her overall tempo)? "
                    + "Higher means she acts more slowly and deliberately, lower means faster.",
            "bias.aggression",
            "How aggressive should her overall inclination be -- how strongly should she lean toward "
                    + "dealing damage and pushing forward, even when nothing forces her to?",
            "bias.caution",
            "How cautious should her overall inclination be -- how strongly should she lean toward "
                    + "healing, avoiding damage and keeping distance?");

    /**
     * ★★ 构造问题集合 —— <b>一个旋钮一个问题</b>，全部是 {@code score} 5 档。
     *
     * <p>与 {@link ThinkPrompt#questions()} 的关系：那是"此刻该往哪个方向使劲"（秒级），
     * 这是"她这个人是什么脾气"（十秒~分钟级）。<b>两者共用一个客户端、一套提示词纪律。</b>
     */
    public static Map<String, Object> questions() {
        return questions(Map.of());
    }

    /**
     * ★★ 同上，但允许<b>逐旋钮覆写问法</b>（配置界面里的"编辑提示词"）。
     *
     * <p>★ 覆写只换 {@code instructions}，<b>不换等级、不换映射</b> ——
     * "中间档 = 当前默认值"这条不动点性质必须由代码保证（见类注释），
     * 不能交给可编辑的文本。
     *
     * @param overrides 旋钮名 → 问法（不在 {@link #TUNABLE} 里的会被忽略）
     */
    public static Map<String, Object> questions(Map<String, String> overrides) {
        Map<String, String> ov = overrides == null ? Map.of() : overrides;
        Map<String, Object> q = new LinkedHashMap<>();
        for (String key : TUNABLE) {
            String builtin = QUESTION.getOrDefault(key, "How should " + key + " be set?");
            String text = ov.get(key);
            q.put(key, JevClient.score(text == null || text.isBlank() ? builtin : text,
                    LEVELS.toArray(new String[0])));
        }
        return q;
    }

    /**
     * ★★ <b>把 {@code score} 答案映射成旋钮值</b>。
     *
     * <p>三条性质（都在自测里断言）：
     * <ol>
     *   <li><b>中档 = 默认值</b>（严格相等）⇒ "别动"是不动点；</li>
     *   <li><b>结果永远落在 [min, max] 内</b> ⇒ 不可能把体系推出边界；</li>
     *   <li><b>未登记的键被丢</b> ⇒ 模型多答/答错键名不会污染总线。</li>
     * </ol>
     *
     * @return 建议的补丁（键 → 值）；空表 = 模型什么都没答或全"保持"
     */
    public static Map<String, Double> patch(Map<String, Object> response, TuningBus bus) {
        Map<String, Double> out = new LinkedHashMap<>();
        TuningBus b = bus == null ? TuningBus.global() : bus;
        for (String key : TUNABLE) {
            double score = JevClient.scoreValue(response, key);
            if (Double.isNaN(score)) {
                continue;                    // 没答 ⇒ 不动它（★ 不猜默认值）
            }
            TuningBus.Knob knob = null;
            for (TuningBus.Knob k : b.knobs()) {
                if (k.key().equals(key)) {
                    knob = k;
                }
            }
            if (knob == null) {
                continue;                    // 白名单里没有 ⇒ 丢
            }
            out.put(key, valueFor(knob, score));
        }
        return out;
    }

    /** 分段线性映射：2.0 恰好是默认值。 */
    public static double valueFor(TuningBus.Knob knob, double score) {
        double s = Math.max(0.0, Math.min(LEVELS.size() - 1.0, score));
        double def = knob.def();
        double v;
        if (s <= 2.0) {
            v = def + (knob.min() - def) * ((2.0 - s) / 2.0);
        } else {
            v = def + (knob.max() - def) * ((s - 2.0) / 2.0);
        }
        return knob.clamp(v);
    }

    /**
     * ★ 一行诊断："模型想怎么调"。
     * <p>★ 这条日志是 {@code observe} 模式的意义所在：<b>先看它想怎么调，再决定要不要让它真的动。</b>
     */
    public static String describe(Map<String, Double> patch, TuningBus bus) {
        TuningBus b = bus == null ? TuningBus.global() : bus;
        if (patch == null || patch.isEmpty()) {
            return "（模型没有给出任何调整建议）";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Double> e : patch.entrySet()) {
            double now = b.get(e.getKey());
            double to = e.getValue();
            String arrow = Math.abs(to - now) < 1.0e-9 ? "＝"
                    : (to > now ? "↑" : "↓");
            parts.add(String.format(Locale.ROOT, "%s %s %.2f→%.2f", e.getKey(), arrow, now, to));
        }
        return String.join("　", parts);
    }

    /**
     * 构造 {@code state} —— <b>统计 + 当前旋钮 + 旋钮说明书</b>。
     *
     * <p>★ 有意<b>不</b>喂"当前局势"（那是 {@link ThinkPrompt} 的活）：调控看的是<b>一段时间的行为</b>，
     * 混进瞬时局势只会让模型把"这一次很危险"错当成"她这个人该更谨慎"。
     */
    public static Map<String, Object> state(BehaviorStats stats, TuningBus bus) {
        return state(stats, bus, null);
    }

    /**
     * ★★ 同上，但带<b>上一轮的有界反馈</b>（委托方 2026-10-01 的提问：
     * 「llm 动态指挥是默认有上下文功能，还是没有？」）。
     *
     * <h2>★ 诚实回答：默认<b>没有会话上下文</b>，只有"有界反馈"</h2>
     * <ul>
     *   <li><b>没有</b>多轮对话历史 —— 每次请求都是独立判定。这不是我们偷懒：
     *       JEV 是 System One 的<b>打分模型</b>（{@code noul}/{@code choice}/{@code score}），
     *       <b>它压根不接受消息历史</b>（见 {@link JevClient} 的类注释）。
     *       要"多轮"就得换一个 chat 模型，那是另一套风险与成本。</li>
     *   <li><b>有</b>三样东西构成了"上下文"：
     *       {@code recent_behaviour}（一段时间的行为统计，★ 这才是真正的"历史"）、
     *       {@code current_knob_values}（含<b>它自己上次改出来的效果</b>）、
     *       以及本方法的 {@code previous_round}（上一轮它建议了什么、有没有生效）。</li>
     * </ul>
     * ⇒ 也就是说：<b>它记得"世界现在是什么样"，但不记得"我们聊过什么"。</b>
     * 对本用途这反而更稳（不累积幻觉、不被自己前几轮的话带偏），
     * 而"上一轮"这一格足够避免"反复提同样的建议却被忽略"这种最讨厌的情况。
     *
     * @param previousRound 上一轮的反馈（一句话）；{@code null}/空白 ⇒ 不发这个字段
     */
    public static Map<String, Object> state(BehaviorStats stats, TuningBus bus, String previousRound) {
        TuningBus b = bus == null ? TuningBus.global() : bus;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("who", "A combat maid companion in Minecraft. You are NOT choosing her actions. "
                + "You are only deciding her long-term fighting STYLE by tuning a few numeric knobs.");
        out.put("recent_behaviour", stats == null ? Map.of() : stats.toState());
        Map<String, Object> current = new LinkedHashMap<>();
        for (TuningBus.Knob k : b.knobs()) {
            current.put(k.key(), k.def() == b.get(k.key()) ? "default" : b.get(k.key()));
        }
        out.put("current_knob_values", current);
        out.put("knobs_you_may_adjust", b.describeForLlm());
        // ★ 有界反馈：只有"上一轮"这一格 —— 不是对话历史，是"上次我建议了什么、结果如何"
        if (previousRound != null && !previousRound.isBlank()) {
            out.put("previous_round", previousRound);
        }
        out.put("how_to_answer",
                "For every knob answer on the 0-to-4 scale. The MIDDLE answer means 'keep it as it is'. "
                        + "Only deviate from the middle when the recent behaviour above clearly justifies it. "
                        + "You cannot add or remove actions, and you cannot forbid anything.");
        return out;
    }

    /** 给 `/flp tune advisor` 用的一行说明（含"哪些旋钮可被顾问调"）。 */
    public static String describeScope() {
        return "顾问可调：" + String.join(" / ", TUNABLE)
                + "　（★ failure.backoff 不在其中 —— 它是保险丝，不该被「风格」改掉）";
    }
}
