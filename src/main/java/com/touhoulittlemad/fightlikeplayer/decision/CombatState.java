package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * ★★ <b>「脱战」与「索敌迟滞」的唯一判据</b>（第十七轮续；委托方 2026-10-05 第 1、3 条）。
 *
 * <h2>委托方原话</h2>
 * <ol>
 *   <li>「关于指令一脱战就消失的设计，是存在的吧。我观察到清空敌人指令消失的情况。但脱战的检测
 *       相对简陋，我们可以将脱战定义为<b>连续 5 秒没仇恨对象</b>这样更保险？但也有可能有
 *       <b>永远不脱战</b>的风险，你看一下这样合不合适。」</li>
 *   <li>「扩大女仆产生仇恨的距离，更大地扩大女仆对于已有仇恨对象的<b>锁定距离</b>
 *       （防止仇恨目标稍微跑远一点女仆就不打了）。但距离不要给太远。」</li>
 * </ol>
 *
 * <h2>★ 事实先说清：本项目**没有**"脱战清指令"的代码</h2>
 * 复核：{@code DirectiveHolder#forget} 零调用点、{@code cancelAll} 只在工具/命令里。
 * 他会看到指令"消失"，实际是这三条之一：
 * <ul>
 *   <li>持续指令的 <b>TTL</b>（除 {@code only_actions} = 6 秒，其余默认 <b>120 秒</b>）；</li>
 *   <li>同互斥组被新指令覆盖（回话里会写"自动取消了同组指令"）；</li>
 *   <li>★ {@code only_actions} 的**白名单看门狗**（40 tick 没做出任何动作 ⇒ 自动解除）——
 *       「清空敌人 ⇒ 指令消失」正是它误伤（没敌人 ≠ 卡住）。</li>
 * </ul>
 * ⇒ 本类提供**统一的脱战判据**，供看门狗与（可选的）"脱战清指令"共用。
 *
 * <h2>★ 关于"永远不脱战"的风险（他的担心）</h2>
 * 判据本身不会"卡住"：它只看"上一次有仇恨对象是什么时候"，而她**总会有**没目标的时刻
 * （打完架/换目标/怪死光）。真正需要设防的是**我们拿它去做什么**：
 * <ul>
 *   <li>用它做"自动解除指令"⇒ **默认关闭**（{@code directive.clearOnDisengage=false}）：
 *       哪怕判据失灵，也不会把玩家刚下的指令悄悄撤掉；</li>
 *   <li>即便打开，TTL（120 秒）仍是**硬上限** —— 永远不脱战也不会让指令永远活着（第 26 条的纪律）。</li>
 * </ul>
 */
public final class CombatState {

    private CombatState() {
    }

    /** 「连续多久没有仇恨对象」算脱战：**5 秒**（委托方指定）。 */
    public static final int IDLE_TICKS = 100;

    /** 「刚刚还在打」的窗口：**3 秒**（用于索敌迟滞：这段时间内按**锁定距离**找目标）。 */
    public static final int RECENT_TARGET_TICKS = 60;

    /**
     * 脱战了吗？
     *
     * @param lastTargetTick 她**最后一次**有仇恨对象的世界时间（{@code <= 0} = 从没有过）
     */
    public static boolean disengaged(long lastTargetTick, long now) {
        return lastTargetTick <= 0 || now - lastTargetTick >= IDLE_TICKS;
    }

    /** 刚刚还在打吗（3 秒内）—— 决定用"锁定距离"还是"产生仇恨距离"索敌。 */
    public static boolean recentlyHadTarget(long lastTargetTick, long now) {
        return lastTargetTick > 0 && now - lastTargetTick < RECENT_TARGET_TICKS;
    }

    /**
     * ★★ <b>索敌迟滞</b>（委托方第 3 条）：刚丢目标时用更大的半径去找回来
     * （"目标稍微跑远一点就不打了"的修法），平时用较小的半径产生仇恨。
     */
    public static double acquireRange(boolean recentlyHadTarget, double acquire,
                                      double keep) {
        return recentlyHadTarget ? Math.max(acquire, keep) : acquire;
    }

    /** 一行诊断（`/flp why` / 日志）。 */
    public static String describe(long lastTargetTick, long now) {
        if (lastTargetTick <= 0) {
            return "脱战（她从没有过仇恨对象）";
        }
        long idle = now - lastTargetTick;
        return disengaged(lastTargetTick, now)
                ? "脱战（" + (idle / 20) + " 秒没有仇恨对象）"
                : "在战斗（" + (idle / 20.0) + " 秒前还有目标）";
    }
}
