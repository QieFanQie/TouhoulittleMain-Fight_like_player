package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★ <b>「同一件事别每 tick 记一遍」</b>（第二十一轮）。
 *
 * <h2>为什么需要它</h2>
 * 本项目的诊断全靠 debug 日志（委托方报的现象，我这边只有日志能复现）。而有些
 * "被指令挡下"的判定发生在**每 tick 都会重建的挑选池**里
 * （最典型：{@code GoetyFocusOps.available} / {@code IronsSpells.options} ——
 * 它们每 tick 被 {@code can_cast} 事实问一次）
 * ⇒ 一句 debug 就会**每秒刷 20 遍**。
 *
 * <p>实测代价（2026-10-05 的实例日志）：一句「指令挡下这颗聚晶：…」（每 tick 8 条）
 * 让一次会话的 {@code debug.log} 涨到 <b>4.7 MB</b>，而**真正要看的那几行被埋了** ——
 * 定位"她为什么隔很久才放一个法术"时，我必须在几千条重复行里找那 20 条
 * （最后靠 grep 排除法才拿到）。
 *
 * <h2>语义</h2>
 * 按 {@code key}（调用方自己拼，例如 {@code 女仆UUID|法术名}）**在窗口内只放行一次**。
 * ★ 刻意是**时间窗**而不是"只记一次"：
 * <ul>
 *   <li>只记一次 ⇒ 指令撤销后又下达时，日志里<b>看不到</b>第二次（而"第二次为什么没生效"
 *       正是最需要日志的时候）；</li>
 *   <li>时间窗 ⇒ 同一现象每 10 秒留一条，既看得出"一直在挡"，又不会刷屏。</li>
 * </ul>
 * ★ 表会**自我修剪**（超过 {@link #PRUNE_AT} 条时清掉窗口外的键）：
 * 女仆数量 × 法术数量的组合是开放的，不能让诊断设施自己变成内存泄漏。
 *
 * <p>★ 纯逻辑、无 MC 类型 ⇒ 可以离线断言（见 {@code DirectiveSelfTest}）。
 */
public final class LogThrottle {

    /** 表多大时修剪一次（清掉窗口外的键）。 */
    private static final int PRUNE_AT = 512;

    private final int windowTicks;
    private final Map<String, Long> lastLoggedAt = new ConcurrentHashMap<>();

    /**
     * @param windowTicks 同一个 key 两次记录之间至少相隔多少 tick（≥1）
     */
    public LogThrottle(int windowTicks) {
        this.windowTicks = Math.max(1, windowTicks);
    }

    /**
     * 现在该不该记这一条。
     *
     * @param key 事件的标识（调用方拼：女仆 + 具体对象）
     * @param now 当前 tick
     * @return {@code true} = 记（并且把这次的时间记下来）
     */
    public boolean should(String key, long now) {
        if (key == null || key.isBlank()) {
            return true;                       // 认不出的调用方 ⇒ 不拦（宁可多记，不可静默）
        }
        Long prev = lastLoggedAt.get(key);
        if (prev != null && now - prev < windowTicks) {
            return false;
        }
        lastLoggedAt.put(key, now);
        if (lastLoggedAt.size() > PRUNE_AT) {
            lastLoggedAt.entrySet().removeIf(e -> now - e.getValue() >= windowTicks);
        }
        return true;
    }

    /** 当前记住的 key 数（自测用）。 */
    public int size() {
        return lastLoggedAt.size();
    }
}
