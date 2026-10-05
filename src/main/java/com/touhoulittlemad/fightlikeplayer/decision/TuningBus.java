package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>运行期可调参数总线</b> —— 一个<b>白名单 + 钳制</b>的参数注册表。
 *
 * <h2>为什么需要它（委托方 2026-10-01 的第 4 条设想）</h2>
 * 委托方想要：「接入一个 LLM 实时调控机制：LLM 不直接决定战斗，但可以决定<b>风格</b>和<b>决策</b>；
 * LLM 可以知道女仆的参数与日志情报（尽量用统计结果以省上下文），
 * 并<b>通过输出指令来调整我们这个体系的各个参数</b> —— 类似于动态调试。」
 *
 * ⇒ 这条设想成立的前提，是"体系里有一组<b>有名有界、可读写</b>的旋钮"。
 * 在那之前，"调参"只能靠改 Java 常量或改 JSON 再重启 —— 那是**离线**调试，不是动态调试。
 * 本类就是那组旋钮。
 *
 * <h2>★★ 三条安全设计（每一条都是为了让"外部输入"不可能搞坏系统）</h2>
 * <ol>
 *   <li><b>白名单</b>：只有 {@link #KNOBS} 里登记过的键能被写。
 *       LLM 输出 {@code "delete_all_actions": 1} 之类的键会被<b>拒绝</b>并记原因，而不是静默忽略；</li>
 *   <li><b>硬钳制</b>：每个旋钮自带 {@code [min, max]}，越界值被<b>钳到边界</b>而不是接受
 *       （"LLM 把强度设成 900" 不该让弹簧爆炸）；</li>
 *   <li><b>可回退</b>：{@code null} 覆盖 = 用默认值。{@link #resetAll()} 一键回到出厂。
 *       任何一次调参都不该是"单向不可逆"的 —— 否则动态调试就变成了动态破坏。</li>
 * </ol>
 *
 * <h2>★ 与"思维层"的分工（这一点很关键）</h2>
 * <table border="1">
 *   <tr><th></th><th>输入端</th><th>时间尺度</th><th>它改什么</th></tr>
 *   <tr><td><b>短周期判定</b>（已有）</td><td>当前局势</td><td>秒级</td>
 *       <td>弹簧点<b>这一次</b>往哪推（{@code SpringImpact.bias}）</td></tr>
 *   <tr><td><b>调参总线</b>（本类）</td><td>一段时间的行为<b>统计</b></td><td>十秒~分钟级</td>
 *       <td>体系<b>长期</b>的性格：满足得多快、冷却多长、偏置多凶</td></tr>
 * </table>
 * ⇒ 前者是"这一步怎么走"，后者是"这个人是什么脾气"。
 * ★ <b>LLM 只动后者，就永远不会做出单步的蠢决策</b> —— 这是委托方那条设想最值钱的地方：
 * 它把"模型可能犯错"的影响面从"一次战斗"缩小到"一组有界的旋钮"。
 *
 * <p>★ 线程模型：只在服务端主线程读写（与决策循环一致），故不加锁。
 * 若将来 LLM 在别的线程上写，改为 {@code ConcurrentHashMap} 即可 —— 现在是刻意的简单。
 *
 * @see TuningKnob
 */
public final class TuningBus {

    /** 一个旋钮的元数据。 */
    public record Knob(String key, String zhName, double def, double min, double max, String help) {

        /** 把任意值钳进合法区间（并对 NaN 返回默认值）。 */
        public double clamp(double v) {
            if (Double.isNaN(v)) {
                return def;
            }
            return v < min ? min : (v > max ? max : v);
        }
    }

    /** 全部旋钮（★ 这是唯一的事实来源：文档、命令、LLM 提示词都从它生成）。 */
    private static final List<Knob> KNOBS = List.of(
            new Knob("satisfaction.all", "总满足度倍率", 1.0, 0.1, 2.0,
                    "动作扣减弹簧的【整体】倍率。调小 ⇒ 做什么都觉得'没解决'，于是做得更频繁。"),
            new Knob("satisfaction.damage", "攻击端满足度倍率", 1.0, 0.1, 2.0,
                    "★ 只作用于扣减时的【单点/群伤】两个分量。调小 ⇒ 砍一刀'不够解渴'⇒ 需要更多次攻击才能满足攻击需求。"),
            new Knob("cooldown.scale", "冷却倍率", 0.9, 0.2, 5.0,
                    "所有动作最小重复间隔的倍率（节奏缩放）。调大 ⇒ 出手更慢更沉稳。"
                            + "★ 默认 0.9（委托方要「微微高频一些」）——**只动 10%**："
                            + "取证显示调到 0.5 会把连贯度打散（合法连段对 12→5、平局率 9%→41%）"
                            + "⇒ 想更快请分小步调，并盯住平局率。"),
            new Knob("failure.backoff", "失败退避上限", 4.0, 1.0, 16.0,
                    "★ 一个动作连续失败时，冷却乘以 (1+连败) 并钳到本上限。这是'选中却做不出来'不再卡死的保险丝。"),
            new Knob("combo.bonus", "连段奖励", 0.12, 0.0, 0.5,
                    "★ 上一拍是某动作的【声明前驱】时，给它的加成（打一套 > 打一次）。"
                            + "★ 必须大于平局带 τ=0.05，否则会被平局带 + LRU 吃掉（实测 0.03 零效果）；"
                            + "设为 0 即完全关闭。"),
            new Knob("combo.repeatPenalty", "反复读惩罚", 0.12, 0.0, 0.5,
                    "★ 上一拍【同族但不是声明后继】时给的惩罚 —— 这就是「不鼓励复读、鼓励多样化」。"
                            + "设为 0 即只奖励连段、不惩罚复读。"),
            new Knob("bias.aggression", "进攻性", 1.0, 0.0, 2.0,
                    "态势偏置里【单点/群伤/位移朝敌】分量的倍率。风格旋钮：调大 ⇒ 更主动压上。"),
            new Knob("bias.caution", "谨慎度", 1.0, 0.0, 2.0,
                    "态势偏置里【回血/免伤/远离】分量的倍率。风格旋钮：调大 ⇒ 更早举盾拉开。"));

    private static final Map<String, Knob> BY_KEY = index();

    /**
     * ★ 全局单例 —— 因为三个写入者必须写同一份：模组配置、{@code /flp tune} 命令、LLM 调控。
     * <p>读取者则是决策循环与游戏侧接线（每 tick 都会读，所以必须是同一个对象）。
     * <p>★★ 它同时是<b>每女仆总线的父层</b>（见下）。
     */
    private static final TuningBus GLOBAL = new TuningBus();

    public static TuningBus global() {
        return GLOBAL;
    }

    /**
     * ★★ <b>父层</b>：为 {@code null} 表示这是全局（根）总线。
     *
     * <h2>为什么要有"父层"（委托方 2026-10-01 要求：女仆之间的独立）</h2>
     * 旋钮原来只有全局一份 ⇒ <b>两个女仆会抢同一组旋钮</b>：
     * 顾问看的是<b>某一个</b>女仆的统计，却改了<b>所有</b>女仆的风格；
     * 而且 A 的统计会把 B 的脾气带偏。
     *
     * <p>⇒ 做成<b>两层</b>：
     * <pre>
     *   全局（GLOBAL，父）      ← /flp tune、模组配置写这里；没被任何女仆覆盖时的默认
     *     ├── 女仆 A 的总线    ← 顾问按 A 的统计写这里；A 的行为只受它影响
     *     └── 女仆 B 的总线    ← 独立
     * </pre>
     * 读取是<b>沿链向上找</b>：本层有覆盖用本层，没有就问父层，父层也没有才用默认值
     * ⇒ <b>没有覆盖时不复制任何状态</b>，所以"全局改了、女仆没改"能立刻生效。
     */
    private final TuningBus parent;

    /** 根总线（父层 = null）。 */
    private TuningBus() {
        this.parent = null;
    }

    /** 建一条从属于 {@code parent} 的总线（每女仆一份）。 */
    public TuningBus(TuningBus parent) {
        this.parent = parent;
    }

    /** 父层；{@code null} = 这是全局总线。 */
    public TuningBus parent() {
        return parent;
    }

    /** 是不是全局（根）总线。 */
    public boolean isGlobal() {
        return parent == null;
    }

    /** ★ 本条独立总线（每个女仆一份），父层是全局 —— 供 {@code PlayerLikeCombat.Loops} 使用。 */
    public static TuningBus independent() {
        return new TuningBus(GLOBAL);
    }

    private static Map<String, Knob> index() {
        Map<String, Knob> m = new LinkedHashMap<>();
        for (Knob k : KNOBS) {
            m.put(k.key(), k);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 运行期覆盖（未设置的键 = 用默认值）。 */
    private final Map<String, Double> overrides = new LinkedHashMap<>();
    /** 最近一次被拒绝的写入（诊断用 —— "LLM 说了但没生效"必须看得见）。 */
    private String lastRejection;

    /** 全部旋钮（只读）。 */
    public List<Knob> knobs() {
        return KNOBS;
    }

    /** 取一个旋钮的<b>当前生效值</b>（沿父链向上找；未登记的键 ⇒ 返回 1.0 并记拒绝）。 */
    public double get(String key) {
        Knob k = BY_KEY.get(key);
        if (k == null) {
            lastRejection = "未登记的旋钮：" + key;
            return 1.0;
        }
        for (TuningBus b = this; b != null; b = b.parent) {
            Double v = b.overrides.get(key);
            if (v != null) {
                return v;
            }
        }
        return k.def();
    }

    /** ★ 本层有没有直接覆盖这个旋钮（不看父层）—— 诊断用（"这个女仆被单独调过吗"）。 */
    public boolean isOverriddenHere(String key) {
        return overrides.containsKey(key);
    }

    /** ★ 生效值来自哪一层：{@code "本女仆"} / {@code "全局"} / {@code "默认"}。 */
    public String sourceOf(String key) {
        for (TuningBus b = this; b != null; b = b.parent) {
            if (b.overrides.containsKey(key)) {
                return b.parent == null ? "全局" : "本女仆";
            }
        }
        return "默认";
    }

    /**
     * 写一个旋钮。
     *
     * @return {@code true} = 接受（值可能被钳制过）；{@code false} = 键未登记，<b>什么都没改</b>
     */
    public boolean set(String key, double value) {
        Knob k = BY_KEY.get(key);
        if (k == null) {
            lastRejection = "拒绝未登记的旋钮：" + key;
            return false;
        }
        double clamped = k.clamp(value);
        if (clamped != value) {
            lastRejection = "旋钮 " + key + " 的值 " + value + " 越界 ⇒ 已钳到 " + clamped;
        }
        // ★★ 写一个"等于默认值"的数 == 撤销这个覆盖。
        //   为什么必须这样（否则"不动点"只是数值上的，账面上会一直攒覆盖项）：
        //   顾问每轮都会把五个旋钮都答一遍；若"答保持"也记一条覆盖，那么
        //   ① `/flp tune` 会把它们全标成"★已改"（误导人）；
        //   ② {@link #isDefault()} 永远为假 ⇒ "有没有被改过"这个判据失效；
        //   ③ 越攒越多，看不出"到底谁改过哪一个"。
        if (clamped == k.def()) {
            overrides.remove(key);
            return true;
        }
        overrides.put(key, clamped);
        return true;
    }

    /** 是不是"被钳过"（调用方据此给出提示）。 */
    /** 撤销一个旋钮（回到默认）。 */
    public boolean reset(String key) {
        return overrides.remove(key) != null;
    }

    /** 全部回到默认（"一键恢复出厂"—— 任何动态调试都必须有这一步）。 */
    public void resetAll() {
        overrides.clear();
        lastRejection = null;
    }

    /** 当前覆盖快照（只含<b>本层</b>被改过的键）。 */
    public Map<String, Double> overrides() {
        return Map.copyOf(overrides);
    }

    /** 是否<b>本层</b>全部为默认值（不看父层）。 */
    public boolean isDefault() {
        return overrides.isEmpty();
    }

    /** 最近一次被拒绝/钳制的原因（{@code null} = 没有）。 */
    public String lastRejection() {
        return lastRejection;
    }

    public void clearRejection() {
        lastRejection = null;
    }

    // ───────────────────────── 应用点（供体系各处调用）─────────────────────────

    /**
     * ★★ <b>把"满足度"规则作用在一个动作的有效向量上</b> —— 得到<b>真正要从弹簧里扣掉</b>的量。
     *
     * <h2>为什么"满足度"要独立于"选解距离"</h2>
     * {@code v_x} 在现有架构里同时承担两个角色（docs/09 §5.3）：
     * <ol>
     *   <li><b>定位</b>：它在需求空间里的位置 ⇒ 决定"当前需求下该不该选它"；</li>
     *   <li><b>结算</b>：执行后从 {@code p} 里减掉它 ⇒ 决定"这一下解决了多少"。</li>
     * </ol>
     * 委托方第 3 条要求「下调攻击端评分，使攻击需求需要更多攻击完成」——
     * 若直接改 {@code vectors.json}，<b>两个角色会一起变</b>：动作在需求空间里也往原点挪了，
     * 于是"高攻击需求"时反而可能不再选它。⇒ <b>必须把两者分开</b>：
     * 本方法只改**结算量**，不动**定位**。
     *
     * <p>于是"调小攻击端满足度"的语义变得干净且可预测：
     * <b>选择行为完全不变，只是每次攻击'更不解渴'</b>。
     *
     * @param effective 动作的有效向量（{@code u}）
     */
    public NeedVector satisfactionVector(NeedVector effective) {
        double all = get("satisfaction.all");
        double dmg = get("satisfaction.damage");
        if (all == 1.0 && dmg == 1.0) {
            return effective;
        }
        double[] a = effective.toArray();
        a[NeedAxis.SINGLE_DAMAGE.ordinal()] *= dmg;
        a[NeedAxis.AREA_DAMAGE.ordinal()] *= dmg;
        return NeedVector.wrap(a).scaled(all);
    }

    /** ★★ 连段奖励（见 {@code combo.bonus}）。 */
    public double chainBonus() {
        return get("combo.bonus");
    }

    /** ★★ 反复读惩罚（见 {@code combo.repeatPenalty}）。 */
    public double repeatPenalty() {
        return get("combo.repeatPenalty");
    }

    /** 冷却倍率（见 {@code cooldown.scale}）。 */
    public double cooldownScale() {
        return get("cooldown.scale");
    }

    /** 失败退避上限（见 {@code failure.backoff}）。 */
    public double failureBackoffCap() {
        return get("failure.backoff");
    }

    /**
     * ★ <b>风格化态势偏置</b>：按 {@code bias.aggression} / {@code bias.caution} 逐轴缩放。
     *
     * <p>哪些轴算"进攻"、哪些算"谨慎"：按 {@link NeedAxis} 的语义分两组
     * （★ 与动作评分无关，只影响<b>态势基线</b> ⇒ 这是一条"性格"旋钮，不是"战术"旋钮）。
     */
    public NeedVector styleBias(NeedVector bias) {
        double agg = get("bias.aggression");
        double cau = get("bias.caution");
        if (agg == 1.0 && cau == 1.0) {
            return bias;
        }
        double[] a = bias.toArray();
        a[NeedAxis.SINGLE_DAMAGE.ordinal()] *= agg;
        a[NeedAxis.AREA_DAMAGE.ordinal()] *= agg;
        a[NeedAxis.CONTROL.ordinal()] *= agg;
        a[NeedAxis.REINFORCE.ordinal()] *= agg;
        a[NeedAxis.HEAL_SURVIVAL.ordinal()] *= cau;
        a[NeedAxis.MITIGATION_SURVIVAL.ordinal()] *= cau;
        a[NeedAxis.MOBILITY.ordinal()] *= agg;   // ★ 位移是"朝敌推进"的正向分量 ⇒ 归进攻组
        return NeedVector.wrap(a);
    }

    // ───────────────────────── LLM 友好的自描述 ─────────────────────────

    /**
     * ★ <b>生成给 LLM 的旋钮说明书</b>（从 {@link #KNOBS} 生成，不可能与实现漂移）。
     *
     * <p>这就是"LLM 能调参"的接口面：模型只需要知道键名、当前值、取值范围与含义，
     * 然后输出 {@code {"key": value}} 即可。
     */
    public String describeForLlm() {
        StringBuilder sb = new StringBuilder();
        for (Knob k : KNOBS) {
            sb.append("- ").append(k.key())
                    .append(" (now ").append(fmt(get(k.key())))
                    .append(", range ").append(fmt(k.min())).append("..").append(fmt(k.max()))
                    .append(", default ").append(fmt(k.def()))
                    .append(") : ").append(k.help()).append('\n');
        }
        return sb.toString();
    }

    /** 中文一侧的说明（命令输出用）。 */
    public String describeForPlayer() {
        StringBuilder sb = new StringBuilder();
        for (Knob k : KNOBS) {
            boolean changed = isOverriddenHere(k.key());
            sb.append(String.format(java.util.Locale.ROOT, "  %-22s = %-6s %-8s %s  范围 %s~%s  %s%n",
                    k.key(), fmt(get(k.key())),
                    changed ? "★本层改" : "(" + sourceOf(k.key()) + ")",
                    "", fmt(k.min()), fmt(k.max()), k.zhName()));
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ───────────────────────── 便于让命令/自测批量写入 ─────────────────────────

    /** 一次批量写入的结果。 */
    public record ApplyResult(int applied, int rejected, List<String> messages) {
    }

    /**
     * 批量写入（LLM 的输出通常是一整组）。
     *
     * <p>★ <b>部分成功语义</b>：非法的键被拒、合法的键照常写入，并把每条原因返回
     * ⇒ "模型说了一堆，其中一条不认"不会让整批失效，也不会静默。
     */
    public ApplyResult applyAll(Map<String, ? extends Number> values) {
        int ok = 0;
        int bad = 0;
        List<String> msgs = new ArrayList<>();
        if (values == null) {
            return new ApplyResult(0, 0, List.of());
        }
        for (Map.Entry<String, ? extends Number> e : values.entrySet()) {
            Number n = e.getValue();
            if (n == null) {
                bad++;
                msgs.add("拒绝 " + e.getKey() + "：值为 null");
                continue;
            }
            clearRejection();
            if (set(e.getKey(), n.doubleValue())) {
                ok++;
                if (lastRejection() != null) {
                    msgs.add(lastRejection());
                }
            } else {
                bad++;
                msgs.add(lastRejection());
            }
        }
        return new ApplyResult(ok, bad, List.copyOf(msgs));
    }

    /** 便捷：从一个 {@code Supplier} 读值（给"配置项作为默认值"留的口子）。 */
    public double getOrDefault(String key, Supplier<Double> fallback) {
        Double v = overrides.get(key);
        if (v != null) {
            return v;
        }
        Knob k = BY_KEY.get(key);
        if (k == null) {
            return 1.0;
        }
        Double f = fallback == null ? null : fallback.get();
        return k.clamp(f == null ? k.def() : f);
    }
}
