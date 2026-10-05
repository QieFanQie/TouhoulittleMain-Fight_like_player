package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>弹簧动力学规则自测</b>（纯逻辑，零 Minecraft 依赖）——
 * 钉住委托方 2026-10-01 提的两条机制（{@link SpringDynamics}）。
 *
 * <p>为什么必须有：这两条机制会**改变弹簧的行为**（受伤冲量、动态忘却），
 * 而它们此前只写在游戏侧的接线里 ⇒ 一旦公式写错，只有"进游戏看观感"能发现。
 * ⇒ 按本项目纪律：<b>能离线断言的规则，就必须离线断言</b>。
 *
 * <pre>
 *   java -cp build\classes\java\main com.touhoulittlemad.fightlikeplayer.carrier.SpringDynamicsSelfTest
 * </pre>
 */
public final class SpringDynamicsSelfTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== 弹簧动力学自测 (SpringDynamicsSelfTest) ===");

        testHurtScalesWithDamage();
        testHurtScalesWithRemainingHealth();
        testHurtOnlyPushesSurvival();
        testExtraDecayDirection();
        testExtraDecayOnlyOnSurvivalAxes();
        testFullHealthForgetsExposureFast();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════ ① 受伤冲击 ═══════════

    private static void testHurtScalesWithDamage() {
        section("1. ★ 受伤冲击随「这一下掉多少血」单调上升（同一剩余血量下比较）");

        double h = 0.8;
        double light = mit(SpringDynamics.onHurt(h, 1.0, 20.0));
        double heavy = mit(SpringDynamics.onHurt(h, 5.0, 20.0));
        System.out.println("   剩 80%：掉 1 点 → " + fmt(light) + " ；掉 5 点 → " + fmt(heavy));
        expect("★ 掉得多 ⇒ 冲量大", heavy > light, fmt(light) + " vs " + fmt(heavy));

        // 掉血达到参考比例（最大生命的 25%）即为满强度
        double max = mit(SpringDynamics.onHurt(h, 5.0, 20.0));
        double over = mit(SpringDynamics.onHurt(h, 50.0, 20.0));
        System.out.println("   超过参考比例后是否封顶：掉 5 点 = " + fmt(max)
                + " ；掉 50 点 = " + fmt(over));
        expect("★ 冲量有上限（不会因为一刀巨额伤害而爆表）", Math.abs(over - max) < 1e-9,
                fmt(max) + " vs " + fmt(over));
    }

    private static void testHurtScalesWithRemainingHealth() {
        section("2. ★ 同样的伤，剩血越少冲量越大（「疼完之后有多慌」）");

        double dmg = 4.0;
        double healthy = mit(SpringDynamics.onHurt(0.9, dmg, 20.0));
        double hurt = mit(SpringDynamics.onHurt(0.3, dmg, 20.0));
        System.out.println("   掉 4 点：剩 90% → " + fmt(healthy) + " ；剩 30% → " + fmt(hurt));
        expect("★ 残血时同一刀更「慌」（冲量更大）", hurt > healthy,
                fmt(healthy) + " vs " + fmt(hurt));
    }

    private static void testHurtOnlyPushesSurvival() {
        section("3. ★ 受伤只推「不被打到」，不替决策层决定方向（不推 MOBILITY / 输出轴）");

        NeedVector b = SpringDynamics.onHurt(0.5, 4.0, 20.0);
        System.out.println("   b = " + b.toCompactString(
                com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults()));
        expect("MITIGATION_SURVIVAL > 0",
                b.get(NeedAxis.MITIGATION_SURVIVAL) > 0,
                String.valueOf(b.get(NeedAxis.MITIGATION_SURVIVAL)));
        expect("★ MOBILITY 保持 0（该退该进仍由决策层选）", b.get(NeedAxis.MOBILITY) == 0,
                String.valueOf(b.get(NeedAxis.MOBILITY)));
        expect("★ SINGLE_DAMAGE 保持 0（不擅自收敛输出）", b.get(NeedAxis.SINGLE_DAMAGE) == 0,
                String.valueOf(b.get(NeedAxis.SINGLE_DAMAGE)));
    }

    // ═══════════ ② 动态忘却 ═══════════

    private static void testExtraDecayDirection() {
        section("4. ★★ 血量越高 ⇒ 生存轴忘却越快；越低 ⇒ 越慢（可为负 = 减速）");

        var m = NeedAxis.MITIGATION_SURVIVAL;
        double full = SpringDynamics.extraDecayOf(m, 1.0);
        double half = SpringDynamics.extraDecayOf(m, 0.5);
        double low = SpringDynamics.extraDecayOf(m, 0.2);
        System.out.println("   附加忘却率：满血 " + fmt(full) + " ／ 半血 " + fmt(half)
                + " ／ 剩 20% " + fmt(low));
        expect("★ 满血 ⇒ 附加忘却为正（忘得快）", full > 0, fmt(full));
        expect("★ 半血 ⇒ 附加忘却为 0（不干预）", Math.abs(half) < 1e-9, fmt(half));
        expect("★★ 残血 ⇒ 附加忘却为负（忘得更慢 = 执念不散）", low < 0, fmt(low));
    }

    private static void testExtraDecayOnlyOnSurvivalAxes() {
        section("5. 附加忘却只作用于生存两轴（不误伤输出/控制轴）");

        for (NeedAxis axis : NeedAxis.values()) {
            double v = SpringDynamics.extraDecayOf(axis, 1.0);
            boolean survival = axis == NeedAxis.MITIGATION_SURVIVAL || axis == NeedAxis.HEAL_SURVIVAL;
            if (survival) {
                expect("生存轴 " + axis.name() + " 有附加忘却", v > 0, fmt(v));
            } else {
                expect("非生存轴 " + axis.name() + " 附加忘却为 0", v == 0, fmt(v));
            }
        }
    }

    /**
     * ★★ 这条是本机制的**目的本身**（治 D6）：满血时"近战累积的暴露"要能较快散掉，
     * 否则她会莫名其妙地"想撤退&脱战"。
     */
    private static void testFullHealthForgetsExposureFast() {
        section("6. ★★ 目的验证：满血时累积的暴露能在合理时间内忘掉（治 D6 的病根）");

        var cfg = com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults();
        NeedAxis m = NeedAxis.MITIGATION_SURVIVAL;
        double base = cfg.decay(m);
        double extra = SpringDynamics.extraDecayOf(m, 1.0);
        double p0 = 0.6;                       // 挥了 6 刀之后累积的暴露量级
        double p = p0;
        int cycles = 0;
        while (p > 0.3 && cycles < 100) {      // 忘掉一半
            p *= (1.0 - Math.min(1.0, base + extra));
            cycles++;
        }
        System.out.println("   基础 λ=" + fmt(base) + " 附加 λ=" + fmt(extra)
                + " ⇒ 暴露从 0.60 衰减到 0.30 用了 " + cycles + " 个决策周期");
        expect("★★ 满血时暴露在一半以内衰减到一半（≤20 个周期 = 100 tick）", cycles <= 20,
                cycles + " 个周期");

        // 对照：残血时它**不该**散掉（保留"残血执念不散"的设计意图）
        double lowExtra = SpringDynamics.extraDecayOf(m, 0.15);
        double effLow = Math.max(0.0, base + lowExtra);
        System.out.println("   对照：剩 15% 血时有效 λ = " + fmt(effLow) + "（越低越慢）");
        expect("★ 残血时有效忘却率明显低于满血", effLow < base + extra,
                fmt(effLow) + " vs " + fmt(base + extra));
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private static double mit(NeedVector v) {
        return v.get(NeedAxis.MITIGATION_SURVIVAL);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static void section(String title) {
        System.out.println("-- " + title);
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
