package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * ★★ <b>拔刀剑「刀技 / 连段」的时间线判据</b>（纯逻辑，第十六轮，可离线断言）。
 *
 * <h2>★ 为什么需要它（这一轮取证得到的结论）</h2>
 * 我们此前的 SA（{@code slashblade:slash_art}）是<b>调一次</b>
 * {@code ISlashBladeState#doChargeAction(maid, elapsed)}。日志证明它**确实触发了**
 * （{@code SA 触发：elapsed=9 → slashblade:drive_horizontal}），但**零效果**：
 * {@code doChargeAction} 只做"状态迁移 + clickAction"，
 * 而 drive / piercing / judgement 那几招的**伤害全在 {@code ComboState#tickAction} 里**，
 * 它**只由 {@code Item#inventoryTick} 驱动** —— 而女仆的持有物**永远不会**被引擎调用到那个方法
 * （1.20.1 全库唯一调用者是玩家的 {@code Inventory}）。
 *
 * <h2>★★ 参考实现（万法皆通 1.9.0）的解法 —— 一句话</h2>
 * <b>它不等引擎来推，而是自己每 tick 替她把那一帧推一下。</b>
 * 具体两步（`SlashBladeProvider`）：
 * <ol>
 *   <li><b>起手</b>：{@code startUsingItem(MAIN_HAND)} 蓄力 → 到
 *       {@code getFullChargeTicks + SlashArts.getJustReceptionSpan/2} → {@code releaseUsing(...)} + {@code releaseUsingItem()}；</li>
 *   <li><b>推进</b>：连段期间每 tick 调
 *       {@code blade.getItem().inventoryTick(blade, level, maid, 0, /*isSelected*}{@code true)}。</li>
 * </ol>
 * ⇒ 本类只描述这两步的**判据**（该不该松手、松手算不算成功、该不该继续推、什么时候收尾），
 * 具体 API 调用留在 {@code compat/exec/slashblade/SlashBladeChannel}。
 * ★ 这样"时间线"可以在零 MC 环境里断言 —— 而这正是我们最容易写错的地方
 * （本项目已经因为"状态机没人推"栽过一次）。
 *
 * @see <a href="../../../../../../docs/15-LLM指令系统计划.md">docs/15 §3.9–§3.10</a>
 */
public final class SlashArtTimeline {

    private SlashArtTimeline() {
    }

    /**
     * 连段预算（tick）：超过它还没回到 {@code NONE} 就强制收尾。
     * ★ 200 这个数直接取自参考实现（`processComboExecution` 的 {@code executionTime > 200}），
     * 不是我们拍的 —— 拔刀剑的连段最长链（judgement_cut 全链）约 47 tick，200 有充分余量。
     */
    public static final int COMBO_BUDGET_TICKS = 200;

    /** 连段为什么结束（可读出口：日志/诊断里能看出是"正常打完"还是"被打断/超时"）。 */
    public enum End {
        /** 还没结束。 */
        NONE("进行中"),
        /** 连段自己回到 NONE —— 正常打完。 */
        COMBO_ENDED("连段打完"),
        /** 超过预算还没结束 —— 我们替她收尾（否则她会一直举着刀）。 */
        TIMEOUT("超时收尾"),
        /** 蓄力途中她不在使用物品了（被打断/换手）—— 直接放弃。 */
        ABORTED_NOT_USING("蓄力被打断"),
        /** 松手了但拔刀剑没有进连段（例如这一刀没有 SA、或封印/损坏）——放弃。 */
        ABORTED_NO_COMBO("松手后没有进入连段");

        public final String zh;

        End(String zh) {
            this.zh = zh;
        }

        public boolean finished() {
            return this != NONE;
        }
    }

    /**
     * 该在第几 tick 松手。
     *
     * <p>★ 取 {@code charge + justSpan/2} 而不是 {@code charge + justSpan}：
     * 参考实现就是这么做的（`SlashBladeProvider:139-150`），
     * 意图是**落在 Jackpot 窗口的中段**（窗口宽 {@code min(5, 3+灵魂疾行)}）；
     * 女仆不做按帧博弈，取中段最稳。
     */
    public static int releaseTick(int fullChargeTicks, int justReceptionSpan) {
        return Math.max(1, fullChargeTicks) + Math.max(0, justReceptionSpan) / 2;
    }

    /** 蓄力阶段：到点了就该松手。 */
    public static boolean shouldRelease(int ticksUsing, int releaseTick) {
        return ticksUsing >= Math.max(1, releaseTick);
    }

    /** 松手之后算不算成功：必须真的进了连段（{@code comboSeq != NONE}）。 */
    public static boolean releaseSucceeded(boolean comboActive) {
        return comboActive;
    }

    /**
     * ★★ 连段阶段：这一 tick 要不要**替她推一下**
     * （即调用 {@code Item#inventoryTick(stack, level, maid, 0, true)}）。
     *
     * <p>★ 这是整个修复的核心判据：<b>只要她还在连段里、且没超预算，就必须每 tick 推</b>。
     * 漏推一 tick，那一 tick 的动作（伤害判定/位移/特效）就永久丢失 —— 表现为
     * "SA 触发了但什么都没发生"，正是我们踩到的那个坑。
     */
    public static boolean shouldDriveCombo(boolean comboActive, long comboElapsedTicks,
                                           int budgetTicks) {
        return comboActive && comboElapsedTicks <= Math.max(1, budgetTicks);
    }

    /** 连段阶段什么时候收尾。 */
    public static End comboEnd(boolean comboActive, long comboElapsedTicks, int budgetTicks) {
        if (!comboActive) {
            return End.COMBO_ENDED;
        }
        if (comboElapsedTicks > Math.max(1, budgetTicks)) {
            return End.TIMEOUT;
        }
        return End.NONE;
    }

    /** 一行诊断（日志用）。 */
    public static String describe(int ticksUsing, int releaseTick, boolean comboActive,
                                 long comboElapsed) {
        return "蓄力 " + ticksUsing + "/" + releaseTick
                + "　连段=" + (comboActive ? "是" : "否")
                + "（" + comboElapsed + " tick）";
    }
}
