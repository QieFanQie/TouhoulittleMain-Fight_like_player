package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>行为统计聚合器</b> —— 把"最近发生了一堆事"压成一段<b>又短又够用</b>的数字。
 *
 * <h2>★★ 为什么必须是统计而不是流水（委托方第 6 条的关键洞见）</h2>
 * 委托方原话：「LLM 可以知道现在女仆的一些参数和日志情报，<b>我们尽量从统计结果的方式输入以节省上下文</b>。」
 *
 * <p>这个判断是对的，而且理由比"省 token"更硬：
 * <ul>
 *   <li><b>流水是发散的，统计是有界的</b> —— 一场 Boss 战可能有几千行日志，但"最近 200 tick 的动作直方图"
 *       永远只有几行 ⇒ <b>上下文长度与战况复杂度脱钩</b>；</li>
 *   <li>★ <b>LLM 要的是"模式"，不是"事件"</b> —— 它的任务是判断"她是不是老在干同一件事""她是不是卡住了"，
 *       这些恰恰是统计量而不是单条日志；</li>
 *   <li><b>可离线断言</b> —— 给定一段回放 ⇒ 断言统计值正确。流水没法这样验。</li>
 * </ul>
 *
 * <h2>★ 关键设计：只记"能被动作的事"，不记全部</h2>
 * 统计项是<b>挑出来的</b>（见 {@link #toState()}），每一项都对应一个可能的调参动作：
 * <pre>
 *   老是同一个动作     ⇒ 候选集太窄 / 满足度不对
 *   有动作做不出来     ⇒ 冷却或前置条件有问题（这是"她卡住了"最直接的信号）
 *   出手间隔接近冷却   ⇒ 节奏被冷却限住（该调 cooldown.scale）
 *   需求长期偏向某轴   ⇒ 该轴的动作不够 / 偏置不对
 * </pre>
 * ⇒ <b>统计项与旋钮是一一对应的</b>，这才叫"可调"；记一堆用不上的数是浪费上下文。
 *
 * <p>★ 线程模型：只在服务端主线程读写（与决策循环一致）。
 */
public final class BehaviorStats {

    /** 窗口长度（tick）—— 默认 200 = 10 秒，是"一次交手"的量级。 */
    public static final long DEFAULT_WINDOW = 200;

    /** 一个滑窗事件。 */
    private record Event(long tick, String actionId, boolean ok) {
    }

    private final long window;
    private final java.util.ArrayDeque<Event> events = new java.util.ArrayDeque<>();
    /** 需求采样（按 tick 采样，不是每 tick 都记 —— 否则窗口里全是点）。 */
    private final java.util.ArrayDeque<double[]> needs = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> needTicks = new java.util.ArrayDeque<>();
    /** 受伤事件（血量的下降次数与总量）。 */
    private double damageTaken;
    private int hurtCount;
    /** 最低血量（窗口内）。 */
    private double lowestHealthPct = 1.0;
    private long lastTick = Long.MIN_VALUE;
    /** 每次执行动作的 tick（用来算"平均出手间隔"）。 */
    private final java.util.ArrayDeque<Long> execTicks = new java.util.ArrayDeque<>();

    public BehaviorStats() {
        this(DEFAULT_WINDOW);
    }

    public BehaviorStats(long windowTicks) {
        this.window = Math.max(20, windowTicks);
    }

    // ───────────────────────── 记 ─────────────────────────

    /** 记一次动作尝试（成功或失败）。 */
    public void noteAction(long tick, String actionId, boolean ok) {
        events.addLast(new Event(tick, actionId, ok));
        if (ok) {
            execTicks.addLast(tick);
        }
        prune(tick);
    }

    /** 记一次需求采样（调用方可以每 N tick 调一次）。 */
    public void noteNeed(long tick, NeedVector p) {
        needs.addLast(p == null ? new double[NeedAxis.COUNT] : p.toArray());
        needTicks.addLast(tick);
        while (needTicks.size() > 64) {
            needTicks.removeFirst();
            needs.removeFirst();
        }
    }

    /** 记一次受伤。 */
    public void noteHurt(double amount, double healthPctAfter) {
        if (amount > 0) {
            damageTaken += amount;
            hurtCount++;
        }
        lowestHealthPct = Math.min(lowestHealthPct, healthPctAfter);
    }

    /** 无伤时也更新最低血量。 */
    public void noteHealth(double healthPct) {
        lowestHealthPct = Math.min(lowestHealthPct, healthPct);
    }

    private void prune(long now) {
        lastTick = now;
        while (!events.isEmpty() && now - events.peekFirst().tick() > window) {
            events.removeFirst();
        }
        while (!execTicks.isEmpty() && now - execTicks.peekFirst() > window) {
            execTicks.removeFirst();
        }
    }

    /** 清空（脱战 / 手动重置）。 */
    public void reset() {
        events.clear();
        needs.clear();
        needTicks.clear();
        execTicks.clear();
        damageTaken = 0;
        hurtCount = 0;
        lowestHealthPct = 1.0;
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    // ───────────────────────── 读 ─────────────────────────

    /** 窗口内的动作直方图（按次数降序）。 */
    public Map<String, Integer> actionHistogram() {
        Map<String, Integer> h = new TreeMap<>();
        for (Event e : events) {
            if (e.ok()) {
                h.merge(e.actionId(), 1, Integer::sum);
            }
        }
        return h;
    }

    /** ★ 窗口内"被选中却没做出来"的动作（这是"她卡住了"最直接的信号）。 */
    public Map<String, Integer> failedHistogram() {
        Map<String, Integer> h = new TreeMap<>();
        for (Event e : events) {
            if (!e.ok()) {
                h.merge(e.actionId(), 1, Integer::sum);
            }
        }
        return h;
    }

    /** 平均出手间隔（tick）；不足两次返回 {@code -1}。 */
    public double averageInterval() {
        if (execTicks.size() < 2) {
            return -1;
        }
        List<Long> list = new ArrayList<>(execTicks);
        double sum = 0;
        for (int i = 1; i < list.size(); i++) {
            sum += list.get(i) - list.get(i - 1);
        }
        return sum / (list.size() - 1);
    }

    /** 窗口内各需求的均值（用于"她长期偏向哪一轴"）。 */
    public double[] needMean() {
        double[] out = new double[NeedAxis.COUNT];
        if (needs.isEmpty()) {
            return out;
        }
        for (double[] n : needs) {
            for (int i = 0; i < out.length && i < n.length; i++) {
                out[i] += n[i];
            }
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= needs.size();
        }
        return out;
    }

    public int executedCount() {
        return execTicks.size();
    }

    public int failedCount() {
        int n = 0;
        for (Event e : events) {
            if (!e.ok()) {
                n++;
            }
        }
        return n;
    }

    public double damageTaken() {
        return damageTaken;
    }

    public int hurtCount() {
        return hurtCount;
    }

    public double lowestHealthPct() {
        return lowestHealthPct;
    }

    public long window() {
        return window;
    }

    public long lastTick() {
        return lastTick;
    }

    /**
     * ★★ <b>喂给 LLM 的统计快照</b>（英文键，与 {@link ThinkScene#toState()} 同一体例）。
     *
     * <p>刻意<b>不</b>包含：逐条日志、每次判定、每 tick 的需求点 —— 那些既贵又没用（见类注释）。
     */
    public Map<String, Object> toState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("why_this_summary",
                "These are aggregates over roughly the last " + (window / 20) + " seconds. "
                        + "They are meant for tuning long-term behaviour style, not for judging a single moment.");

        Map<String, Object> rhythm = new LinkedHashMap<>();
        rhythm.put("window_ticks", window);
        rhythm.put("actions_done", executedCount());
        rhythm.put("actions_selected_but_failed", failedCount());
        double avg = averageInterval();
        rhythm.put("average_ticks_between_actions", avg < 0 ? -1.0 : round(avg));
        rhythm.put("action_mix", actionHistogram());
        if (failedCount() > 0) {
            rhythm.put("stuck_actions", failedHistogram());
            rhythm.put("stuck_meaning", "She kept choosing these but could not carry them out. "
                    + "A lot of these means the candidate set contains things she cannot actually do.");
        }
        out.put("rhythm", rhythm);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("damage_taken_in_window", round(damageTaken));
        state.put("times_hurt", hurtCount);
        state.put("lowest_health_pct", round(lowestHealthPct));
        out.put("weariness", state);

        double[] mean = needMean();
        Map<String, Object> needs = new LinkedHashMap<>();
        double max = 0;
        NeedAxis maxAxis = null;
        for (NeedAxis a : NeedAxis.values()) {
            double v = mean[a.ordinal()];
            needs.put(a.name().toLowerCase(Locale.ROOT), round(v));
            if (Math.abs(v) > Math.abs(max)) {
                max = v;
                maxAxis = a;
            }
        }
        out.put("average_needs", needs);
        out.put("dominant_need",
                maxAxis == null ? "none" : maxAxis.name().toLowerCase(Locale.ROOT) + "=" + round(max));
        return out;
    }

    /** 一行摘要（日志与 `/flp tune advisor` 用）。 */
    public String summary() {
        return String.format(Locale.ROOT,
                "窗口 %d tick：出手 %d 次（平均间隔 %.1f）· 失败 %d 次 · 受伤 %d 次/共 %.1f · 最低血 %.0f%%",
                window, executedCount(), averageInterval(), failedCount(), hurtCount(),
                damageTaken, lowestHealthPct * 100);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
