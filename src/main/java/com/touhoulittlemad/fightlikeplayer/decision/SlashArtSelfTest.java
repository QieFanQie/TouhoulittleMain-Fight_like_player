package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>拔刀剑时间线自测</b>（纯逻辑，零 MC 依赖）。
 *
 * <pre>
 *   java -cp "build\classes\java\main" \
 *        com.touhoulittlemad.fightlikeplayer.decision.SlashArtSelfTest
 * </pre>
 *
 * <p>钉的是本轮取证得到的那三条判据（它们错了都很难在游戏里看出来）：
 * ① 蓄力在哪一 tick 松手；② 松手算不算成功；③ <b>连段期间每一 tick 都必须推</b>
 * —— 最后这条正是"SA 触发了却零效果"的根因所在。
 */
public final class SlashArtSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== 拔刀剑时间线自测 (SlashArtSelfTest) ===");
        System.out.println();

        testReleaseTick();
        testRelease();
        testDrive();
        testEnd();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
        } else {
            System.out.println("失败 " + failures.size() + " 项（共 " + (passed + failures.size()) + "）：");
            for (String f : failures) {
                System.out.println("  FAIL " + f);
            }
            System.exit(1);
        }
    }

    // ───────────── 1. 松手时刻 ─────────────
    private static void testReleaseTick() {
        section("1. 松手时刻 = 满蓄力 + justSpan/2（落在 Jackpot 窗口的中段）");
        // 拔刀剑默认：fullCharge = 9；justSpan = min(5, 3+灵魂疾行)
        expect("★★ 默认满蓄力 9 + justSpan 4 ⇒ 第 11 tick 松手",
                SlashArtTimeline.releaseTick(9, 4) == 11,
                String.valueOf(SlashArtTimeline.releaseTick(9, 4)));
        expect("★ justSpan 为奇数时向下取整（5 ⇒ +2）",
                SlashArtTimeline.releaseTick(9, 5) == 11,
                String.valueOf(SlashArtTimeline.releaseTick(9, 5)));
        expect("★ 没有 justSpan（0）⇒ 就是满蓄力那一 tick",
                SlashArtTimeline.releaseTick(9, 0) == 9,
                String.valueOf(SlashArtTimeline.releaseTick(9, 0)));
        expect("★ 满蓄力给 0/负数也被兜成至少 1（不许出现「第 0 tick」）",
                SlashArtTimeline.releaseTick(0, 0) == 1,
                String.valueOf(SlashArtTimeline.releaseTick(0, 0)));
    }

    // ───────────── 2. 什么时候松手 / 松手算不算成功 ─────────────
    private static void testRelease() {
        section("2. 蓄力：到点才松手；松手后必须真的进了连段才算成功");
        int tick = 11;
        expect("★ 第 10 tick 还不松（差一 tick 就不许松）",
                !SlashArtTimeline.shouldRelease(10, tick), "");
        expect("★★ 第 11 tick 松手", SlashArtTimeline.shouldRelease(11, tick), "");
        expect("★ 第 30 tick 也松（她可能卡在蓄力里）",
                SlashArtTimeline.shouldRelease(30, tick), "");
        expect("★★ 松手后 comboSeq 还是 NONE ⇒ 这次 SA 失败（不许当成成功）",
                !SlashArtTimeline.releaseSucceeded(false), "");
        expect("★★ 松手后进了连段 ⇒ 成功", SlashArtTimeline.releaseSucceeded(true), "");
    }

    // ───────────── 3. 连段推进（本轮最关键的一条）─────────────
    private static void testDrive() {
        section("3. ★★ 连段期间【每 tick 都要推】—— 漏一 tick 就永久丢一帧");
        int budget = SlashArtTimeline.COMBO_BUDGET_TICKS;
        expect("★★ 在连段里、第 0 tick ⇒ 必须推", SlashArtTimeline.shouldDriveCombo(true, 0, budget), "");
        expect("★★ 在连段里、第 1 tick ⇒ 必须推", SlashArtTimeline.shouldDriveCombo(true, 1, budget), "");
        expect("★★ 在连段里、第 47 tick（最长链刚打完）⇒ 仍然推",
                SlashArtTimeline.shouldDriveCombo(true, 47, budget), "");
        expect("★ 连段结束（comboSeq = NONE）⇒ 不再推",
                !SlashArtTimeline.shouldDriveCombo(false, 10, budget), "");
        expect("★★ 超过预算 ⇒ 不再推（交给收尾逻辑强制结束，不许无限推）",
                !SlashArtTimeline.shouldDriveCombo(true, budget + 1, budget), "");
        expect("★ 预算数就是参考实现用的 200", budget == 200, String.valueOf(budget));
    }

    // ───────────── 4. 收尾 ─────────────
    private static void testEnd() {
        section("4. 收尾：正常打完 / 超时 / 蓄力被打断 / 松手没进连段");
        int budget = SlashArtTimeline.COMBO_BUDGET_TICKS;
        expect("★★ 连段回到 NONE ⇒ 正常打完（可读出口）",
                SlashArtTimeline.comboEnd(false, 30, budget) == SlashArtTimeline.End.COMBO_ENDED,
                String.valueOf(SlashArtTimeline.comboEnd(false, 30, budget)));
        expect("★★ 超预算 ⇒ 超时收尾（否则她会一直举着刀）",
                SlashArtTimeline.comboEnd(true, budget + 1, budget) == SlashArtTimeline.End.TIMEOUT,
                String.valueOf(SlashArtTimeline.comboEnd(true, budget + 1, budget)));
        expect("★ 连段中且没超时 ⇒ 还没结束",
                SlashArtTimeline.comboEnd(true, 10, budget) == SlashArtTimeline.End.NONE, "");
        expect("★ 四种结束原因都是【已完成】", SlashArtTimeline.End.COMBO_ENDED.finished()
                        && SlashArtTimeline.End.TIMEOUT.finished()
                        && SlashArtTimeline.End.ABORTED_NOT_USING.finished()
                        && SlashArtTimeline.End.ABORTED_NO_COMBO.finished(),
                "");
        expect("★ 进行中不算完成", !SlashArtTimeline.End.NONE.finished(), "");
        expect("★ 每个结束原因都有中文说明（日志里答得上「她为什么不打了」）",
                SlashArtTimeline.End.TIMEOUT.zh != null && !SlashArtTimeline.End.TIMEOUT.zh.isBlank(),
                SlashArtTimeline.End.TIMEOUT.zh);
        expect("★ 一行诊断里有蓄力进度与连段状态",
                SlashArtTimeline.describe(11, 11, true, 3).contains("连段=是"),
                SlashArtTimeline.describe(11, 11, true, 3));
    }

    // ───────────── 小工具 ─────────────
    private static void section(String t) {
        System.out.println("-- " + t);
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("   OK   " + what);
        } else {
            failures.add(what + "  [" + detail + "]");
            System.out.println("   FAIL " + what + "  [" + detail + "]");
        }
    }
}
