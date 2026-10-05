package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.DecisionCycle;
import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 端到端自测 —— <b>把整条链路串起来</b>：
 * <pre>
 *   ContextFacts ──ContextBias──▶ 偏置 b
 *                              ↓
 *   catalog ──CarrierResolver──▶ 候选集 A（含契合度乘数）
 *                              ↓
 *                        DecisionCycle ──▶ 选中的动作
 * </pre>
 *
 * <h2>★ 为什么必须补这个测试</h2>
 * 一次自我检查发现：{@code DecisionCycle} 与 {@code CarrierResolver}
 * <b>只被各自的单元自测引用，没有任何"把两者接起来"的验证</b>。
 * 后果是：即使两边各自 24 项断言全过，**整条链路仍可能是死的**
 * —— 例如"没有任何 {@code biasFromContext} 实现"这个致命缺口，
 * 单元测试**完全发现不了**（因为测试自己塞了偏置）。
 *
 * <p>⇒ 本测试<b>不自己塞偏置</b>，而是走 {@link ContextBias} 的真实规则。
 * 它回答的问题只有一个：<b>"给她一把刀和一群敌人，她到底会不会动？动得对不对？"</b>
 *
 * <pre>
 *   java -cp "build\classes\java\main;&lt;gson.jar&gt;" \
 *        com.touhoulittlemad.fightlikeplayer.carrier.PipelineSelfTest
 * </pre>
 */
public final class PipelineSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private static CarrierResolver resolver;
    private static final SpringConfig CFG =
            SpringConfig.builder().deterministic().decisionInterval(1).build();

    public static void main(String[] args) throws Exception {
        System.out.println("=== 端到端链路自测 (PipelineSelfTest) ===");
        System.out.println();

        CatalogLoader.Loaded loaded = CatalogLoader.loadFromWorkingDir(Path.of("catalog"));
        resolver = new CarrierResolver(loaded.actions(), loaded.rules());
        System.out.println("清单：动作 " + loaded.actions().size() + " 条，载体规则 "
                + loaded.rules().size() + " 条");
        System.out.println();

        testBiasIsNotZero();          // ★ 链路是否"活的"
        testBiasTiers();
        testFullHealthVsLowHealth();  // ★★ 最强证据
        testFitnessSuppressesWastefulActions();
        testSingleEnemySuppressesAoe();
        testFarTargetPrefersApproach();
        testVariedCycleEmerges();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════ 1. 偏置不是 0（链路是活的）═══════════

    private static void testBiasIsNotZero() {
        section("1. ★★ 态势偏置真的产生非零需求（否则弹簧永远停在原点 ⇒ 只会走默认动作）");

        ContextFacts combat = combat(1.0, 1.0, 3, 4.0);
        NeedVector b = ContextBias.of(combat);
        System.out.println("   战斗情境 " + ContextBias.explain(combat));
        System.out.println("   b = " + b.toCompactString(CFG));

        expect("★★ 有敌人时偏置非零（链路是活的）", !b.isNearOrigin(CFG), "b = 原点");
        expect("★ 有目标 ⇒ 单点需求被推高", b.get(NeedAxis.SINGLE_DAMAGE) > 0,
                String.valueOf(b.get(NeedAxis.SINGLE_DAMAGE)));
        expect("★ 敌 ≥ 3 ⇒ 群伤需求被推高", b.get(NeedAxis.AREA_DAMAGE) > 0,
                String.valueOf(b.get(NeedAxis.AREA_DAMAGE)));

        ContextFacts idle = combat(1.0, 1.0, 0, Double.MAX_VALUE).with(true, false);
        // 造一个"无敌人无目标"的情境
        ContextFacts peaceful = new ContextFacts(false, false, 0, true, false, false,
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of(),
                ContextFacts.Vitals.of(1.0, 1.0, 0, Double.MAX_VALUE));
        NeedVector bIdle = ContextBias.of(peaceful);
        System.out.println("   和平情境 b = " + bIdle.toCompactString(CFG));
        expect("★ 无敌人 ⇒ 偏置为零（走死区 ⇒ 默认动作，不乱放技能）",
                bIdle.isNearOrigin(CFG), bIdle.toCompactString(CFG));
    }

    private static void testBiasTiers() {
        section("2. 分档不叠加（否则 p 一次被顶到上限，弹簧失去分辨率）");

        NeedVector e3 = ContextBias.of(combat(1.0, 1.0, 3, 4.0));
        NeedVector e6 = ContextBias.of(combat(1.0, 1.0, 6, 4.0));
        NeedVector e20 = ContextBias.of(combat(1.0, 1.0, 20, 4.0));
        System.out.println("   敌3  → 群伤 " + e3.get(NeedAxis.AREA_DAMAGE));
        System.out.println("   敌6  → 群伤 " + e6.get(NeedAxis.AREA_DAMAGE));
        System.out.println("   敌20 → 群伤 " + e20.get(NeedAxis.AREA_DAMAGE));

        expect("★ 敌 3 ⇒ +0.4", near(e3.get(NeedAxis.AREA_DAMAGE), 0.4),
                String.valueOf(e3.get(NeedAxis.AREA_DAMAGE)));
        expect("★ 敌 6 ⇒ +0.8（换成高档，不是叠加成 1.2）",
                near(e6.get(NeedAxis.AREA_DAMAGE), 0.8),
                String.valueOf(e6.get(NeedAxis.AREA_DAMAGE)));
        expect("★★ 敌 20 与敌 6 相同 ⇒ 高处饱和（不会无限累积）",
                near(e20.get(NeedAxis.AREA_DAMAGE), e6.get(NeedAxis.AREA_DAMAGE)),
                e20.get(NeedAxis.AREA_DAMAGE) + " vs " + e6.get(NeedAxis.AREA_DAMAGE));
    }

    // ═══════════ 3. ★★ 满血 vs 残血 ⇒ 决策不同 ═══════════

    /**
     * <b>本文件最强的一条断言</b> —— 它同时证明了三件事：
     * ① 偏置规则真的改变了需求；② 弹簧真的按需求选解；③ 整条链路是通的。
     */
    private static void testFullHealthVsLowHealth() {
        section("3. ★★ 满血 vs 残血 ⇒ 选中不同的动作（链路真的在工作）");

        List<PossessedItem> blade = List.of(blade());

        String full = runOnce(blade, combat(1.0, 1.0, 1, 4.0));
        String low = runOnce(blade, combat(0.12, 1.0, 1, 4.0));

        System.out.println("   满血(100%) → " + full);
        System.out.println("   残血(12%)  → " + low);

        expect("★ 两种血量下都做出了选择（不是沉默）",
                full != null && low != null, full + " / " + low);
        expect("★★ 满血与残血的决策【不同】⇒ 偏置真的在影响选择",
                full != null && !full.equals(low), full + " == " + low);
    }

    // ═══════════ 4. 契合度抑制浪费行为 ═══════════

    private static void testFitnessSuppressesWastefulActions() {
        section("4. ★ 契合度乘数：满血时治疗类被压低（否则女仆会满血刷治疗）");

        ContextFacts full = combat(1.0, 1.0, 1, 4.0);
        ContextFacts hurt = combat(0.30, 1.0, 1, 4.0);

        NeedVector fFull = FitnessCalculator.factors(full);
        NeedVector fHurt = FitnessCalculator.factors(hurt);
        System.out.println("   满血 回血乘数 = " + fFull.get(NeedAxis.HEAL_SURVIVAL)
                + " / 免伤乘数 = " + fFull.get(NeedAxis.MITIGATION_SURVIVAL));
        System.out.println("   缺血 回血乘数 = " + fHurt.get(NeedAxis.HEAL_SURVIVAL)
                + " / 免伤乘数 = " + fHurt.get(NeedAxis.MITIGATION_SURVIVAL));

        expect("★ 满血时治疗的契合度被压低",
                fFull.get(NeedAxis.HEAL_SURVIVAL) < 0.5,
                String.valueOf(fFull.get(NeedAxis.HEAL_SURVIVAL)));
        expect("★ 缺血时治疗的契合度升高",
                fHurt.get(NeedAxis.HEAL_SURVIVAL) > fFull.get(NeedAxis.HEAL_SURVIVAL),
                fHurt.get(NeedAxis.HEAL_SURVIVAL) + " vs " + fFull.get(NeedAxis.HEAL_SURVIVAL));
        expect("★ 无目标时单体契合度归零（打不到就不该选）",
                near(FitnessCalculator.factors(noTarget()).get(NeedAxis.SINGLE_DAMAGE), 0.0),
                String.valueOf(FitnessCalculator.factors(noTarget()).get(NeedAxis.SINGLE_DAMAGE)));
    }

    // ═══════════ 5. 单敌压制 AoE ═══════════

    private static void testSingleEnemySuppressesAoe() {
        section("5. ★ 单个敌人时 AoE 的契合度被压到 0.3（clusterFit）");

        double one = FitnessCalculator.factors(combat(1.0, 1.0, 1, 4.0)).get(NeedAxis.AREA_DAMAGE);
        double three = FitnessCalculator.factors(combat(1.0, 1.0, 3, 4.0)).get(NeedAxis.AREA_DAMAGE);
        double six = FitnessCalculator.factors(combat(1.0, 1.0, 6, 4.0)).get(NeedAxis.AREA_DAMAGE);
        System.out.println("   敌1 → " + one + " / 敌3 → " + three + " / 敌6 → " + six);

        expect("★ 单敌 AoE 契合度 = 0.3", near(one, 0.3), String.valueOf(one));
        expect("★ 敌 3 ⇒ 满契合 1.0", near(three, 1.0), String.valueOf(three));
        expect("★ 敌 6 仍为 1.0（已饱和）", near(six, 1.0), String.valueOf(six));
    }

    // ═══════════ 6. 远距离偏靠近 ═══════════

    private static void testFarTargetPrefersApproach() {
        section("6. ★ 距离 > 8 格 ⇒ 位移偏置取【正】（想靠近；有向轴的用法）");

        NeedVector near1 = ContextBias.of(combat(1.0, 1.0, 1, 3.0));
        NeedVector far = ContextBias.of(combat(1.0, 1.0, 1, 15.0));
        System.out.println("   3 格 → 位移 " + near1.get(NeedAxis.MOBILITY)
                + " / 15 格 → 位移 " + far.get(NeedAxis.MOBILITY));

        expect("★ 3 格时不产生靠近需求", near(near1.get(NeedAxis.MOBILITY), 0.0),
                String.valueOf(near1.get(NeedAxis.MOBILITY)));
        expect("★ 15 格时产生【正】位移需求（想靠近）",
                far.get(NeedAxis.MOBILITY) > 0,
                String.valueOf(far.get(NeedAxis.MOBILITY)));
    }

    // ═══════════ 7. 局势变化 ⇒ 选择随之变化 ═══════════

    /**
     * ★★ 端到端：<b>局势变化时，选择必须跟着变</b>。
     *
     * <h2>为什么这个断言要这样写（一条实测经验，值得记）</h2>
     * 最初写的是"恒定环境下应出现多个不同动作"，而且**当时是过的** ——
     * 但那次能过是因为 `v_x` 还是**启发式**填的、很粗糙（拔刀剑全族 `SINGLE_DAMAGE` 一律 1.0），
     * 属于「**噪声带来的假多样性**」。
     *
     * <p>换成逐条重打的评分后，弹簧在<b>恒定的群怪环境</b>下**稳定收敛到同一个动作**
     * （连续 6 次 `spiral_swords`）。★ <b>这不是 bug</b>：
     * 局势不变、需求不变 ⇒ 最优解不变 ⇒ 反复用它**正是正确行为**
     * （玩家面对一群怪也会一直放同一个最好的 AoE）。
     *
     * <p>⇒ 断言改成：**让局势变化，看选择是否随之变化**。
     * 这才是真正的要求，且顺带验证了「偏置 → 需求 → 选择」这条因果链。
     */
    private static void testVariedCycleEmerges() {
        section("7. ★★ 局势变化 ⇒ 选择随之变化（端到端）");

        List<PossessedItem> blade = List.of(blade());

        String crowd = runOnce(blade, combat(1.0, 1.0, 6, 4.0));    // 群怪
        String lonely = runOnce(blade, combat(1.0, 1.0, 1, 4.0));    // 单个敌人
        String dying = runOnce(blade, combat(0.12, 1.0, 1, 4.0));    // 濒死

        System.out.println("   群怪(6敌) → " + crowd);
        System.out.println("   单敌      → " + lonely);
        System.out.println("   濒死(12%) → " + dying);

        expect("★ 三种局势都做出了选择", crowd != null && lonely != null && dying != null,
                crowd + " / " + lonely + " / " + dying);
        expect("★★ 濒死的选择与其它两种【不同】（生存需求压过输出）",
                dying != null && !dying.equals(crowd) && !dying.equals(lonely),
                "濒死=" + dying + " vs 群怪=" + crowd + " / 单敌=" + lonely);
        expect("★ 群怪与单敌的选择也不同（群伤需求 vs 单点需求）",
                crowd != null && !crowd.equals(lonely),
                "群怪=" + crowd + " vs 单敌=" + lonely);

        // ★ 恒定局势下应当【稳定收敛】而不是乱跳（这是新的、正确的期望）
        List<String> constant = new ArrayList<>();
        held = blade;
        DecisionCycle<ContextFacts> cyc = buildCycle(constant);
        ContextFacts steady = combat(1.0, 1.0, 6, 4.0);
        for (int t = 0; t < 6; t++) {
            cyc.tick(steady, t);
            cyc.notifyCompleted();
        }
        long distinct = constant.stream().distinct().count();
        System.out.println("   恒定群怪 6 周期：" + constant);
        expect("★★ 恒定局势下【收敛稳定】（不乱跳）—— 需求不变则最优解不变",
                distinct <= 2, "出现了 " + distinct + " 种动作（乱跳）");
    }

    // ═══════════ 辅助 ═══════════

    private static DecisionCycle<ContextFacts> buildCycle(List<String> sink) {
        return new DecisionCycle<>(CFG,
                ContextBias::of,                                  // ★ 真实偏置规则
                ctx -> resolver.resolve(ctx == null ? List.of() : held, ctx, CFG).candidates(),
                (a, ctx) -> sink.add(a.id()),
                () -> 0L, new Random(1));
    }

    /** 当前测试场景持有的物品（简单起见用一个静态槽）。 */
    private static List<PossessedItem> held = List.of();

    private static String runOnce(List<PossessedItem> items, ContextFacts ctx) {
        held = items;
        List<String> sink = new ArrayList<>();
        DecisionCycle<ContextFacts> c = buildCycle(sink);
        c.tick(ctx, 0);
        return sink.isEmpty() ? null : sink.get(0);
    }

    private static PossessedItem blade() {
        return PossessedItem.withParams(SlotKind.MAINHAND, "slashblade:yasha",
                Set.of("ItemSlashBlade", "Item"), Map.of("arts", "judgement_cut"));
    }

    private static ContextFacts combat(double selfHp, double ownerHp, int enemies, double dist) {
        return new ContextFacts(true, dist <= 3.0, 0, true, false, false,
                Map.of("耀魂值", 1000.0), Map.of("tacz:shoot#1", true),
                Map.of(), Set.of(), Set.of("touhou_little_maid", "slashblade", "goeth", "goety",
                "irons_spellbooks", "tacz", "fight_like_player"),
                ContextFacts.Vitals.of(selfHp, ownerHp, enemies, dist));
    }

    private static ContextFacts noTarget() {
        return new ContextFacts(false, false, 0, true, false, false,
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of("slashblade"),
                ContextFacts.Vitals.of(1.0, 1.0, 0, Double.MAX_VALUE));
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

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
