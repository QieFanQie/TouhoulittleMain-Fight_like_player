package com.touhoulittlemad.fightlikeplayer.decision;

import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.carrier.SlotKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 弹簧空间 / 双循环 / 有向轴 的离线自测 —— 纯 JVM。
 *
 * <h2>验证的四件委托方要求</h2>
 * <ol>
 *   <li><b>有向位移轴</b>：{@code MOBILITY} 是唯一有正负语义的轴（正=靠近，负=远离）；</li>
 *   <li><b>影响器以发布顺序执行</b>（动作层与思维层同构）；</li>
 *   <li><b>双循环共用一个弹簧空间、动作层隔离</b>（副手自己玩自己的）；</li>
 *   <li><b>上一个动作执行完毕才能开下一个</b>，且"完成"由执行器回报。</li>
 * </ol>
 */
public final class SpringSpaceSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== 弹簧空间 / 双循环自测 (SpringSpaceSelfTest) ===\n");

        testSignedMobilityAxis();
        testSignedMobilitySelectsRetreat();
        testImpactsRunInPublishOrder();
        testThinkingLayerUsesSameInterface();
        testTwoLoopsShareOneSpace();
        testLoopItemIsolation();
        testExecutorDrivenCompletion();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════ 1. 有向位移轴 ═══════════

    private static void testSignedMobilityAxis() {
        section("1. ★ 有向位移轴：MOBILITY 的正负都表示【需求方向】");

        SpringConfig cfg = SpringConfig.defaults();

        expect("MOBILITY 被标记为有向轴", NeedAxis.MOBILITY.isSigned(), "isSigned=false");
        expect("其他轴都不是有向轴",
                NeedAxis.values().length - 1 ==
                        (int) java.util.Arrays.stream(NeedAxis.values()).filter(a -> !a.isSigned()).count(),
                "存在多个有向轴");

        // 负值必须被【保留】（不能像其他轴那样被钳到 0）
        NeedVector p = NeedVector.of(NeedAxis.MOBILITY, -0.7).normalize(cfg);
        System.out.println("   p[位移] = -0.7 规整后 = " + p.get(NeedAxis.MOBILITY));
        expect("★ 有向轴保留负值（-0.7 不被钳成 0）",
                Math.abs(p.get(NeedAxis.MOBILITY) + 0.7) < 1e-9,
                String.valueOf(p.get(NeedAxis.MOBILITY)));

        // 双向钳制
        NeedVector q = NeedVector.of(NeedAxis.MOBILITY, -99.0).normalize(cfg);
        expect("有向轴向下钳到 -max",
                Math.abs(q.get(NeedAxis.MOBILITY) + cfg.max(NeedAxis.MOBILITY)) < 1e-9,
                String.valueOf(q.get(NeedAxis.MOBILITY)));

        // 非有向轴仍然非负
        NeedVector r = NeedVector.of(NeedAxis.SINGLE_DAMAGE, -0.5).normalize(cfg);
        expect("非有向轴仍被钳到 0（代价语义不变）",
                r.get(NeedAxis.SINGLE_DAMAGE) == 0.0,
                String.valueOf(r.get(NeedAxis.SINGLE_DAMAGE)));
    }

    /** 想远离时，后撤类动作（MOBILITY 为负）应当胜出。 */
    private static void testSignedMobilitySelectsRetreat() {
        section("2. ★ 有向轴的实战含义：想远离 ⇒ 选中负位移动作");

        List<CandidateAction> table = List.of(
                CandidateAction.instant("突进", NeedVector.of(
                        NeedAxis.MOBILITY, 0.8, NeedAxis.SINGLE_DAMAGE, 0.4)),
                CandidateAction.instant("后撤", NeedVector.of(
                        NeedAxis.MOBILITY, -0.8, NeedAxis.MITIGATION_SURVIVAL, 0.3)));

        // 场景 A：需求是"靠近"
        List<String> near = run(table, NeedVector.of(NeedAxis.MOBILITY, 0.8));
        System.out.println("   需要靠近 → " + near);
        expect("★ 需要靠近时选『突进』", near.contains("突进"), near.toString());

        // 场景 B：需求是"远离"
        List<String> far = run(table, NeedVector.of(NeedAxis.MOBILITY, -0.8));
        System.out.println("   需要远离 → " + far);
        expect("★★ 需要远离时选『后撤』（同一个轴、相反方向）",
                far.contains("后撤") && !far.get(0).equals("突进"), far.toString());
    }

    // ═══════════ 2. 影响器按发布顺序执行 ═══════════

    private static void testImpactsRunInPublishOrder() {
        section("3. ★ 影响器按【发布顺序】逐个执行");

        SpringConfig cfg = SpringConfig.defaults();
        SpringSpace space = new SpringSpace();

        // 先发布偏置，再发布扣减 —— 顺序不同，结果不同
        space.publish(SpringImpact.bias(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9),
                "test:context", "偏置 +0.9"));
        space.publish(SpringImpact.subtract(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.5),
                "test:action", "扣减 0.5"));

        List<String> log = space.drain(cfg);
        double after = space.point().get(NeedAxis.SINGLE_DAMAGE);
        System.out.println("   顺序 [" + String.join(" | ", log) + "]");
        System.out.println("   → p[单点] = " + after + "（+0.9 再 −0.5 = 0.4，规整一次）");

        expect("★ 发布顺序被执行（日志按发布顺序）",
                log.size() == 2 && log.get(0).contains("偏置") && log.get(1).contains("扣减"),
                log.toString());
        // ★ 本用例【故意没有发布忘却影响器】⇒ 没有 ×0.96，结果就是 0.9−0.5
        expect("两个影响器都生效且只规整一次",
                Math.abs(after - (0.9 - 0.5)) < 1e-9,
                String.valueOf(after));
        expect("队列已清空", space.pendingCount() == 0, String.valueOf(space.pendingCount()));
        expect("执行计数正确", space.appliedCount() == 2, String.valueOf(space.appliedCount()));
    }

    /** 思维层与动作层用同一个接口 —— 这是改成"发布函数"的核心收益。 */
    private static void testThinkingLayerUsesSameInterface() {
        section("4. ★★ 思维层与动作层【共用同一个影响器接口】");

        SpringConfig cfg = SpringConfig.defaults();
        SpringSpace space = new SpringSpace();
        space.setPoint(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.5, NeedAxis.AREA_DAMAGE, 0.5));

        // 模拟"思维层直接下令：马上逃命" —— 用同一个 SpringImpact 接口
        // （★ 这正是委托方要登记的「直接指令表」将来要走的路径）
        space.publish(SpringImpact.bias(NeedVector.of(
                NeedAxis.MITIGATION_SURVIVAL, 2.5, NeedAxis.MOBILITY, -1.0),
                "thinking:directive", "立即逃命"));
        space.drain(cfg);

        NeedVector p = space.point();
        System.out.println("   思维层推挤后：" + p.toCompactString(cfg));
        expect("★ 思维层用同一接口成功推挤弹簧（+2.5 被上限钳到 2.0）",
                Math.abs(p.get(NeedAxis.MITIGATION_SURVIVAL) - cfg.max(NeedAxis.MITIGATION_SURVIVAL)) < 1e-9,
                String.valueOf(p.get(NeedAxis.MITIGATION_SURVIVAL)));
        expect("★★ 思维层能让位移需求变成【负】（想远离）",
                p.get(NeedAxis.MOBILITY) < 0,
                String.valueOf(p.get(NeedAxis.MOBILITY)));
    }

    // ═══════════ 3. 双循环共享空间 / 物品隔离 ═══════════

    private static void testTwoLoopsShareOneSpace() {
        section("5. ★★ 双循环共用一个弹簧空间（副手的影响主循环能看见）");

        SpringConfig cfg = SpringConfig.builder().deterministic().decisionInterval(1).build();
        SpringSpace shared = new SpringSpace();

        List<String> mainLog = new ArrayList<>();
        List<String> offLog = new ArrayList<>();

        DecisionCycle<Object> main = new DecisionCycle<>(cfg, shared, "main",
                ctx -> NeedVector.zeros(),
                ctx -> List.of(CandidateAction.instant("主手近战",
                        NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8))),
                (a, ctx) -> mainLog.add(a.id()), () -> 0L, new Random(1));
        DecisionCycle<Object> off = new DecisionCycle<>(cfg, shared, "offhand",
                ctx -> NeedVector.zeros(),
                ctx -> List.of(CandidateAction.instant("举盾",
                        NeedVector.of(NeedAxis.MITIGATION_SURVIVAL, 0.9))),
                (a, ctx) -> offLog.add(a.id()), () -> 0L, new Random(1));

        expect("★ 两个循环引用的是同一个 SpringSpace 实例",
                main.space() == off.space(), "不是同一个实例");

        // 让副手循环先动：它扣减了免伤需求 → 主循环的 p 上必须能看见
        off.tick(null, 0);
        off.tick(null, 1);
        double afterOff = shared.point().get(NeedAxis.MITIGATION_SURVIVAL);

        // 主循环此时读到的是【共享】的点
        System.out.println("   副手循环两次后 shared.p[免伤] = " + afterOff);
        System.out.println("   主循环看到的 p = " + main.need().toCompactString(cfg));
        expect("★★ 共享点：从副手循环对象读到的 p 与主循环读到的是同一个值",
                Math.abs(main.need().get(NeedAxis.MITIGATION_SURVIVAL) - afterOff) < 1e-9,
                "不一致");
    }

    private static void testLoopItemIsolation() {
        section("6. ★ 双循环的物品隔离（不会两个循环抢同一个物品）");

        List<PossessedItem> possessed = List.of(
                PossessedItem.of(SlotKind.MAINHAND, "minecraft:iron_sword", Set.of("SwordItem")),
                PossessedItem.of(SlotKind.OFFHAND, "minecraft:shield", Set.of("ShieldItem")),
                PossessedItem.of(SlotKind.INVENTORY, "minecraft:bow", Set.of("BowItem")));

        LoopSplit split = LoopSplit.of(possessed);
        System.out.println("   " + split.describe());
        System.out.println("   主循环：" + split.mainItems());
        System.out.println("   副手循环：" + split.offhandItems());

        expect("★ 副手循环只拿到副手物品",
                split.offhandItems().size() == 1
                        && split.offhandItems().get(0).slot() == SlotKind.OFFHAND,
                split.offhandItems().toString());
        expect("★ 主循环拿到主手+背包（不含副手）",
                split.mainItems().size() == 2
                        && split.mainItems().stream().noneMatch(i -> i.slot() == SlotKind.OFFHAND),
                split.mainItems().toString());
        expect("★★ 隔离不变量成立（两个集合的槽位不相交）", split.isIsolated(), "槽位重叠");
    }

    // ═══════════ 4. 执行器驱动的完成门控 ═══════════

    private static void testExecutorDrivenCompletion() {
        section("7. ★★ 上一个动作执行完毕才能开下一个（完成由【执行器回报】）");

        SpringConfig cfg = SpringConfig.builder().deterministic().decisionInterval(1).build();
        List<String> executed = new ArrayList<>();

        DecisionCycle<Object> c = new DecisionCycle<>(cfg,
                ctx -> NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9),
                ctx -> List.of(new CandidateAction("长引导", NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9),
                        NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9), 1000, true)),
                (a, ctx) -> executed.add(a.id()), () -> 0L, new Random(1));

        // 承诺 1000 tick —— 若靠 tick 倒计时，要跑 1000 tick 才会重选
        for (int t = 0; t < 5; t++) {
            c.tick(null, t);
        }
        System.out.println("   承诺 1000 tick，跑了 5 tick → 执行次数 = " + executed.size());
        expect("★ 执行中不重选（门控生效）", executed.size() == 1, executed.toString());
        expect("门控状态可见", c.isCommitted() && "长引导".equals(c.inFlightId()), c.inFlightId());

        // ★ 执行器回报"已完成" ⇒ 立刻放行，不必等 1000 tick
        boolean done = c.notifyCompleted();
        expect("★ 执行器回报完成被识别", done, "notifyCompleted=false");
        expect("回报后不再处于执行中", !c.isCommitted(), "仍 isCommitted");

        c.tick(null, 6);
        System.out.println("   回报完成后再 tick → 执行次数 = " + executed.size());
        expect("★★ 回报完成后立刻开启下一个循环（不必等满承诺时长）",
                executed.size() == 2, executed.toString());
    }

    // ═══════════ 辅助 ═══════════

    private static List<String> run(List<CandidateAction> table, NeedVector bias) {
        SpringConfig cfg = SpringConfig.builder().deterministic().decisionInterval(1).build();
        List<String> executed = new ArrayList<>();
        DecisionCycle<Object> c = new DecisionCycle<>(cfg,
                ctx -> bias, ctx -> table, (a, ctx) -> executed.add(a.id()), () -> 0L, new Random(1));
        c.tick(null, 0);
        return executed;
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
