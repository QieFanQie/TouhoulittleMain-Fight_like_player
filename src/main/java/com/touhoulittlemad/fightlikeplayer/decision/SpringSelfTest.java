package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 弹簧核心的<b>离线自测</b> —— 纯 JVM，不启动 Minecraft。
 *
 * <h2>为什么这个文件重要</h2>
 * 弹簧体系的数学不依赖游戏世界。把它做成可离线断言的东西，意味着
 * <b>"行为逻辑"从"靠手感调"变成了"可测试"</b>。
 * 这是 [docs/09 §8] M1 阶段唯一需要的验证手段。
 *
 * <h2>运行方式</h2>
 * <pre>
 *   java -cp build\classes\java\main com.touhoulittlemad.fightlikeplayer.decision.SpringSelfTest
 * </pre>
 * 退出码 0 = 全部通过；1 = 有失败。
 *
 * <h2>它验证什么</h2>
 * <ol>
 *   <li><b>核心主张</b>：群怪环境下应当出现「群攻 → 自保」的节奏，而<b>没有任何一条写着"打完 AoE 就防御"的规则</b>；</li>
 *   <li>规整三件套（非负 / 上限 / 死区）与代价语义；</li>
 *   <li>死区 ⇒ 走默认动作集；可用集合为空 ⇒ 走回退动作；</li>
 *   <li>承诺期门控（引导类法术不会每 tick 被重选打断）；</li>
 *   <li>打断退还（按剩余比例返还代价）。</li>
 * </ol>
 *
 * <p>全部用例使用 {@link SpringConfig.Builder#deterministic()}，因此结果是逐位确定的 ——
 * 一旦有人改动轴默认值或距离度量，这里会立刻红。
 */
public final class SpringSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== 弹簧核心自测 (SpringSelfTest) ===\n");

        testSpringRotationReproducesPlayerStory();
        testNormalization();
        testCostSemantics();
        testDeadZoneUsesDefaultActions();
        testEmptyCandidatesUsesFallback();
        testCommitmentGating();
        testInterruptRefund();
        testVerifiesAgainstExpectedDistances();
        testTieWindowCreatesVariety();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════════════════ 测试 1：核心主张 ═══════════════════════

    /**
     * <b>最关键的一条</b>：复现"群怪环境"故事 —— 群攻 → 因为生存为负 → 生存需求上升 → 选到防御动作。
     *
     * <p>注意本用例的动作表里<b>没有任何一条规则</b>说"放完 AoE 要防御"。
     * 这个序列完全是几何的必然结果。
     *
     * <p>⚠️ 偏置必须给足（群伤 +0.8）。若只给 +0.4，p 太小，
     * 最近邻会选中"近战连击"（SINGLE 0.8 比 AREA 0.9 更贴近小需求）——
     * 见测试 8 的对照，这不是 bug，是"需求小就该用轻手段"的正确行为。
     */
    private static void testSpringRotationReproducesPlayerStory() {
        section("1. 核心主张：群怪环境应自发产生「群攻 -> 自保」节奏（无手写规则）");

        List<CandidateAction> table = List.of(
                CandidateAction.instant("吟唱AoE", NeedVector.of(
                        NeedAxis.SINGLE_DAMAGE, 0.2, NeedAxis.AREA_DAMAGE, 0.9, NeedAxis.MITIGATION_SURVIVAL, -0.5)),
                CandidateAction.instant("壁垒聚晶", NeedVector.of(
                        NeedAxis.MITIGATION_SURVIVAL, 0.8, NeedAxis.ALLY_CARE, 0.1)),
                CandidateAction.instant("近战连击", NeedVector.of(
                        NeedAxis.SINGLE_DAMAGE, 0.8, NeedAxis.AREA_DAMAGE, 0.1, NeedAxis.MITIGATION_SURVIVAL, -0.2)),
                CandidateAction.instant("突进", NeedVector.of(
                        NeedAxis.MOBILITY, 0.8, NeedAxis.SINGLE_DAMAGE, 0.4, NeedAxis.MITIGATION_SURVIVAL, -0.3)));

        List<String> executed = new ArrayList<>();
        // 群怪态势基线：单点 +0.3、群伤 +0.8
        DecisionCycle<Object> c = build(table, ctx -> NeedVector.of(
                NeedAxis.SINGLE_DAMAGE, 0.3, NeedAxis.AREA_DAMAGE, 0.8), executed);

        c.tick(null, 0);
        double survivalAfterAoe = c.need().get(NeedAxis.MITIGATION_SURVIVAL);
        System.out.println("   放完 AoE 后：" + c.need().toCompactString(SpringConfig.defaults()));

        // 再跑 3 个周期，观察是否形成稳定节奏
        for (int t = 1; t < 4; t++) {
            c.tick(null, t);
            System.out.println("   周期 " + (t + 1) + " 后：" + c.need().toCompactString(SpringConfig.defaults()));
        }
        System.out.println("   实际序列：" + executed);

        expect("第 1 个动作是群攻（吟唱AoE）", "吟唱AoE".equals(first(executed)), "实际=" + first(executed));
        expect("★ 放完 AoE 后生存需求被推高（代价语义生效）", survivalAfterAoe > 0.0,
                "MITIGATION_SURVIVAL=" + fmt(survivalAfterAoe));
        expect("第 2 个动作是自保（壁垒聚晶）", "壁垒聚晶".equals(second(executed)), "实际=" + second(executed));

        // ★★ 最强的一条断言：环境完全不变，却自发形成有变化的循环
        // 注意第 4 个动作是「近战连击」而不是「壁垒聚晶」——
        // 因为 d(壁垒)=1.2103 与 d(近战)=1.2477 只差 0.0374 < τ(0.05)，
        // 落入平局带，由 LRU 打破了平局（近战从未用过）。
        // ⇒ 平局带正是"防止机械式两动作死循环"的机制。见测试 9。
        expect("★★ 4 个周期自发形成「群攻 -> 自保 -> 群攻 -> 近战」循环（环境恒定、无任何手写规则）",
                executed.equals(List.of("吟唱AoE", "壁垒聚晶", "吟唱AoE", "近战连击")),
                "实际=" + executed);
    }

    // ═══════════════════════ 测试 2：规整 ═══════════════════════

    private static void testNormalization() {
        section("2. 规整三件套：非负 / 上限 / 死区");

        SpringConfig cfg = SpringConfig.defaults();

        NeedVector p = NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.2)
                .minus(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9))
                .normalize(cfg);
        expect("减法后不为负", p.get(NeedAxis.SINGLE_DAMAGE) == 0.0, "实际=" + p.get(NeedAxis.SINGLE_DAMAGE));

        NeedVector r = NeedVector.of(NeedAxis.MITIGATION_SURVIVAL, 99.0).normalize(cfg);
        expect("上限钳制生效", Math.abs(r.get(NeedAxis.MITIGATION_SURVIVAL) - cfg.max(NeedAxis.MITIGATION_SURVIVAL)) < 1e-9,
                "实际=" + r.get(NeedAxis.MITIGATION_SURVIVAL));

        NeedVector s = NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.001).normalize(cfg);
        expect("死区：极小值归零", s.get(NeedAxis.SINGLE_DAMAGE) == 0.0, "实际=" + s.get(NeedAxis.SINGLE_DAMAGE));
        expect("死区判定 isNearOrigin", s.isNearOrigin(cfg), "isNearOrigin=false");
    }

    // ═══════════════════════ 测试 3：代价语义 ═══════════════════════

    /**
     * ★ 这是整个弹簧体系里最容易写错的一处：
     * 动作向量的<b>负分量</b>在 {@code p - u} 之后必须让该轴需求<b>增大</b>。
     * 若实现里"先规整再减"或"减完不规整"，代价就会静默消失 ——
     * 表现为"女仆放完代价技能后不补状态"。
     */
    private static void testCostSemantics() {
        section("3. 代价语义：减去负分量 => 该轴需求被推高");

        SpringConfig cfg = SpringConfig.defaults();
        NeedVector q = NeedVector.zeros()
                .minus(NeedVector.of(NeedAxis.MITIGATION_SURVIVAL, -0.5))
                .normalize(cfg);
        expect("减去 -0.5 后自保需求 = 0.5", Math.abs(q.get(NeedAxis.MITIGATION_SURVIVAL) - 0.5) < 1e-9,
                "实际=" + q.get(NeedAxis.MITIGATION_SURVIVAL));
    }

    // ═══════════════════════ 测试 4：死区走默认动作 ═══════════════════════

    private static void testDeadZoneUsesDefaultActions() {
        section("4. 死区 => 默认动作集（不要挑最弱的动作乱放）");

        List<String> executed = new ArrayList<>();
        DecisionCycle<Object> c = build(List.of(
                        CandidateAction.instant("超强AoE", NeedVector.of(NeedAxis.AREA_DAMAGE, 1.0))),
                ctx -> NeedVector.zeros(),                 // 无偏置 => 恒在原点
                executed)
                .withDefaultActions(ctx -> List.of(
                        CandidateAction.instant("接近目标", NeedVector.zeros())));

        c.tick(null, 0);
        c.tick(null, 1);

        expect("无需求时走默认动作而非超强AoE",
                executed.size() == 2 && executed.stream().allMatch("接近目标"::equals), "实际=" + executed);
    }

    // ═══════════════════════ 测试 5：空候选走回退 ═══════════════════════

    private static void testEmptyCandidatesUsesFallback() {
        section("5. 回退动作必须永远存在（灭火器能力静默丢失的教训）");

        List<String> executed = new ArrayList<>();
        DecisionCycle<Object> c = build(List.of(),                        // 载体解析结果为空
                ctx -> NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9),
                executed)
                .withFallbackActions(ctx -> List.of(
                        CandidateAction.instant("通用近战", NeedVector.zeros())));

        c.tick(null, 0);

        expect("空候选 => 走回退而非静默不动", executed.contains("通用近战"), "实际=" + executed);
    }

    // ═══════════════════════ 测试 6：承诺门控 ═══════════════════════

    private static void testCommitmentGating() {
        section("6. 承诺期门控：引导类法术不应每 tick 被重选打断");

        List<CandidateAction> table = List.of(
                new CandidateAction("引导法术", NeedVector.of(NeedAxis.AREA_DAMAGE, 0.9),
                        NeedVector.of(NeedAxis.AREA_DAMAGE, 0.9), 10, true),
                CandidateAction.instant("近战", NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8)));

        List<String> executed = new ArrayList<>();
        // 偏置只要群伤 => 必然选引导法术
        DecisionCycle<Object> c = build(table,
                ctx -> NeedVector.of(NeedAxis.AREA_DAMAGE, 0.9), executed);

        for (int t = 0; t < 5; t++) {
            c.tick(null, t);
        }

        long count = executed.stream().filter("引导法术"::equals).count();
        expect("承诺期内只执行一次（共 5 tick）", count == 1, "执行次数=" + count);
        expect("承诺状态被标记", c.isCommitted(), "isCommitted=false");
    }

    // ═══════════════════════ 测试 7：打断退还 ═══════════════════════

    private static void testInterruptRefund() {
        section("7. 打断退还：没放出来就不该被全额扣代价");

        List<CandidateAction> table = List.of(
                new CandidateAction("引导法术", NeedVector.of(NeedAxis.AREA_DAMAGE, 0.9),
                        NeedVector.of(NeedAxis.AREA_DAMAGE, 0.9), 10, true));

        List<String> executed = new ArrayList<>();
        DecisionCycle<Object> c = build(table,
                ctx -> NeedVector.of(NeedAxis.AREA_DAMAGE, 0.9), executed);

        c.tick(null, 0);                                   // 起手，扣满 0.9
        double afterCommit = c.need().get(NeedAxis.AREA_DAMAGE);
        c.tick(null, 1);                                   // 承诺推进 1 tick（剩 9/10）
        boolean didInterrupt = c.notifyInterrupted();
        double afterInterrupt = c.need().get(NeedAxis.AREA_DAMAGE);

        System.out.println("   扣减后=" + fmt(afterCommit) + "  打断后=" + fmt(afterInterrupt));
        expect("打断被识别", didInterrupt, "didInterrupt=false");
        expect("★ 打断后需求回升（退还 9/10 的代价）", afterInterrupt > afterCommit,
                fmt(afterCommit) + " -> " + fmt(afterInterrupt));
        expect("退还比例正确（0.9 * 0.9 = 0.81）", Math.abs(afterInterrupt - 0.81) < 1e-9,
                "实际=" + fmt(afterInterrupt));
        expect("不再处于承诺状态", !c.isCommitted(), "isCommitted=true");
    }

    // ═══════════════════════ 测试 8：距离度量对照 ═══════════════════════

    /**
     * 把测试 1 的第一次决策的<b>全部距离</b>钉死。
     *
     * <p>这既是回归保护，也是文档 <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.6</a>
     * 那张表的数据来源 —— <b>文档里的数字必须来自这里，不能手算</b>
     * （手算曾把偏置取小，得出"群攻先手"的错误结论）。
     */
    private static void testVerifiesAgainstExpectedDistances() {
        section("8. 距离对照表（docs/09 §5.6 的真实数据源）");

        SpringConfig cfg = SpringConfig.defaults();
        NeedVector p = NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.288, NeedAxis.AREA_DAMAGE, 0.768);

        expect("d(群攻) = 0.7247",
                Math.abs(p.weightedDistance(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.2,
                        NeedAxis.AREA_DAMAGE, 0.9, NeedAxis.MITIGATION_SURVIVAL, -0.5), cfg) - 0.7247) < 0.001, "d="
                        + fmt(p.weightedDistance(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.2,
                        NeedAxis.AREA_DAMAGE, 0.9, NeedAxis.MITIGATION_SURVIVAL, -0.5), cfg)));
        expect("d(近战连击) = 0.8879",
                Math.abs(p.weightedDistance(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8,
                        NeedAxis.AREA_DAMAGE, 0.1, NeedAxis.MITIGATION_SURVIVAL, -0.2), cfg) - 0.8879) < 0.001, "d="
                        + fmt(p.weightedDistance(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8,
                        NeedAxis.AREA_DAMAGE, 0.1, NeedAxis.MITIGATION_SURVIVAL, -0.2), cfg)));
        expect("d(壁垒聚晶) = 1.4046",
                Math.abs(p.weightedDistance(NeedVector.of(NeedAxis.MITIGATION_SURVIVAL, 0.8,
                        NeedAxis.ALLY_CARE, 0.1), cfg) - 1.4046) < 0.001, "d="
                        + fmt(p.weightedDistance(NeedVector.of(NeedAxis.MITIGATION_SURVIVAL, 0.8,
                        NeedAxis.ALLY_CARE, 0.1), cfg)));
        expect("d(突进) = 1.1926",
                Math.abs(p.weightedDistance(NeedVector.of(NeedAxis.MOBILITY, 0.8,
                        NeedAxis.SINGLE_DAMAGE, 0.4, NeedAxis.MITIGATION_SURVIVAL, -0.3), cfg) - 1.1926) < 0.001, "d="
                        + fmt(p.weightedDistance(NeedVector.of(NeedAxis.MOBILITY, 0.8,
                        NeedAxis.SINGLE_DAMAGE, 0.4, NeedAxis.MITIGATION_SURVIVAL, -0.3), cfg)));

        // 小需求下的对照：近战连击应当胜过群攻 —— 说明"需求小就该用轻手段"
        NeedVector small = NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.288, NeedAxis.AREA_DAMAGE, 0.384);
        double dCombo = small.weightedDistance(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8,
                NeedAxis.AREA_DAMAGE, 0.1, NeedAxis.MITIGATION_SURVIVAL, -0.2), cfg);
        double dAoe = small.weightedDistance(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.2,
                NeedAxis.AREA_DAMAGE, 0.9, NeedAxis.MITIGATION_SURVIVAL, -0.5), cfg);
        System.out.println("   小需求下：d(近战连击)=" + fmt(dCombo) + "  d(群攻)=" + fmt(dAoe));
        expect("★ 需求小时轻手段胜出（群攻的 -0.5 自保代价此时不划算）", dCombo < dAoe,
                fmt(dCombo) + " vs " + fmt(dAoe));
    }

    // ═══════════════════════ 测试 9：平局带 ⊗ 多样性 ═══════════════════════

    /**
     * 隔离验证<b>平局带 τ</b> 的作用：它是"防止机械式两动作死循环"的机制。
     *
     * <p>场景与测试 1 相同，只改 τ：
     * <ul>
     *   <li>{@code τ = 0} ⇒ 严格最近邻 ⇒ 变成 `群攻↔自保` 的两动作死循环；</li>
     *   <li>{@code τ = 0.05}（默认）⇒ 第 4 步的 `自保(1.2103)` 与 `近战(1.2477)` 只差 0.0374，
     *       落入平局带 ⇒ 由 LRU 交给"从没用过"的近战 ⇒ 出现第三种动作。</li>
     * </ul>
     *
     * <p>⚠️ 注意 {@code τ} 与 {@code recencyPenalty(ρ)} 是<b>两种不同机制</b>：
     * τ 只在"距离几乎相等"时生效（打破平局）；ρ 则对所有动作持续生效。
     * 默认 ρ 很小（0.05）是刻意的 —— 主力轮换必须来自 `p` 的位移，而不是惩罚。
     */
    private static void testTieWindowCreatesVariety() {
        section("9. 平局带 τ：防止机械式两动作死循环");

        List<CandidateAction> table = List.of(
                CandidateAction.instant("吟唱AoE", NeedVector.of(
                        NeedAxis.SINGLE_DAMAGE, 0.2, NeedAxis.AREA_DAMAGE, 0.9, NeedAxis.MITIGATION_SURVIVAL, -0.5)),
                CandidateAction.instant("壁垒聚晶", NeedVector.of(
                        NeedAxis.MITIGATION_SURVIVAL, 0.8, NeedAxis.ALLY_CARE, 0.1)),
                CandidateAction.instant("近战连击", NeedVector.of(
                        NeedAxis.SINGLE_DAMAGE, 0.8, NeedAxis.AREA_DAMAGE, 0.1, NeedAxis.MITIGATION_SURVIVAL, -0.2)),
                CandidateAction.instant("突进", NeedVector.of(
                        NeedAxis.MOBILITY, 0.8, NeedAxis.SINGLE_DAMAGE, 0.4, NeedAxis.MITIGATION_SURVIVAL, -0.3)));

        DecisionCycle.BiasSource<Object> bias = ctx -> NeedVector.of(
                NeedAxis.SINGLE_DAMAGE, 0.3, NeedAxis.AREA_DAMAGE, 0.8);

        List<String> strict = new ArrayList<>();
        DecisionCycle<Object> a = build(SpringConfig.builder().deterministic()
                .decisionInterval(1).tieWindow(0.0).build(), table, bias, strict);
        for (int t = 0; t < 4; t++) {
            a.tick(null, t);
        }

        List<String> varied = new ArrayList<>();
        DecisionCycle<Object> b = build(table, bias, varied);
        for (int t = 0; t < 4; t++) {
            b.tick(null, t);
        }

        System.out.println("   τ=0.00 ：" + strict);
        System.out.println("   τ=0.05 ：" + varied);
        expect("τ=0 时退化为两动作死循环",
                strict.equals(List.of("吟唱AoE", "壁垒聚晶", "吟唱AoE", "壁垒聚晶")), "实际=" + strict);
        expect("★ τ=0.05 时出现第三种动作（多样性）",
                !varied.equals(strict) && varied.contains("近战连击"), "实际=" + varied);
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /** 构造一个把执行序列记进 {@code sink} 的决策循环（默认参数）。 */
    private static DecisionCycle<Object> build(List<CandidateAction> table,
                                               DecisionCycle.BiasSource<Object> bias,
                                               List<String> sink) {
        return build(SpringConfig.builder().deterministic().decisionInterval(1).build(), table, bias, sink);
    }

    /** 指定参数表。 */
    private static DecisionCycle<Object> build(SpringConfig cfg,
                                               List<CandidateAction> table,
                                               DecisionCycle.BiasSource<Object> bias,
                                               List<String> sink) {
        return new DecisionCycle<>(
                cfg,
                bias,
                ctx -> table,
                (action, ctx) -> sink.add(action.id()),
                () -> 0L,
                new Random(1));
    }

    private static String first(List<String> l) {
        return l.isEmpty() ? "(无)" : l.get(0);
    }

    private static String second(List<String> l) {
        return l.size() < 2 ? "(无)" : l.get(1);
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
