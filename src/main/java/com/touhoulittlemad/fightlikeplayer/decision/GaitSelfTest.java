package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>步法自测</b>（纯逻辑，零 Minecraft 依赖）—— 钉住 {@link GaitSelector} 的八条规则。
 *
 * <h2>为什么必须有这个自测</h2>
 * 步法的引入是为了修一个<b>实测出来的</b>缺陷（"无论注入什么向量都只是到处跑"）。
 * 那条结论来自"旧设计里位移是可消费的需求"这一语义错误。
 * ⇒ 因此这里要断言的不只是"能选对方向"，而是<b>新语义本身</b>：
 * <ol>
 *   <li>方向由 {@code MOBILITY} 的<b>符号</b>驱动（唯一有向轴）；</li>
 *   <li>没有强需求时<b>伺服在"站位"上</b>（弓手拉开、剑士贴身）；</li>
 *   <li>站位来自<b>载体</b>（换武器 ⇒ 站位自动变）；</li>
 *   <li>动作接管导航时步法<b>让位</b>；</li>
 *   <li>无目标时回到主人身边；</li>
 *   <li>近战载体<b>不会</b>因为"太近"而后退（否则会贴上去又退开）。</li>
 * </ol>
 *
 * <p>运行：{@code java -cp build\classes\java\main com.touhoulittlemad.fightlikeplayer.decision.GaitSelfTest}
 */
public final class GaitSelfTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== 步法自测 (GaitSelfTest) ===");

        testNoTargetFollowsOwner();
        testMobilitySignDrivesDirection();
        testServoOnPreferredRange();
        testMeleeNeverBacksOff();
        testNavigationLockYields();
        testCarrierRangeIsWhatChangesBehaviour();
        testHysteresisPreventsJitter();
        testActionTakesNavigationWhitelist();
        testGaitDefenseScore();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════════ 1. 无目标 ⇒ 归位或保持 ═══════════════

    private static void testNoTargetFollowsOwner() {
        section("1. 无目标时：主人远 ⇒ 归位；主人近 ⇒ 保持（类玩家：打完回到主人身边）");

        Gait far = GaitSelector.select(p(0, 0), false, Double.MAX_VALUE, 2.5, 20.0, false);
        System.out.println("   主人 20 格 → " + far.describe());
        expect("★ 无目标且主人远 ⇒ 归位（TO_OWNER）",
                far.direction() == Gait.Direction.TO_OWNER, far.describe());

        Gait near = GaitSelector.select(p(0, 0), false, Double.MAX_VALUE, 2.5, 3.0, false);
        System.out.println("   主人 3 格 → " + near.describe());
        expect("无目标且主人近 ⇒ 保持", near.isHold(), near.describe());
    }

    // ═══════════════ 2. MOBILITY 的符号驱动方向 ═══════════════

    private static void testMobilitySignDrivesDirection() {
        section("2. ★ MOBILITY 的【符号】决定方向（唯一有向轴：正=靠近，负=远离）");

        Gait toward = GaitSelector.select(p(0.8, 0), true, 10.0, 2.5, 100, false);
        System.out.println("   p[位移]=+0.8，距离 10 格，站位 2.5 → " + toward.describe());
        expect("★ 位移需求为正 ⇒ 靠近", toward.direction() == Gait.Direction.TOWARD, toward.describe());

        Gait away = GaitSelector.select(p(-0.8, 0), true, 3.0, 2.5, 100, false);
        System.out.println("   p[位移]=-0.8，距离 3 格 → " + away.describe());
        expect("★★ 位移需求为负 ⇒ 远离（同一个轴、相反方向）",
                away.direction() == Gait.Direction.AWAY, away.describe());
        expect("★ 后撤距离 = max(2×站位, 站位+6)", away.desiredDistance() >= 8.5,
                String.valueOf(away.desiredDistance()));

        Gait already = GaitSelector.select(p(0.8, 0), true, 2.0, 2.5, 100, false);
        System.out.println("   位移需求为正但已在站位内（2.0 ≤ 2.5）→ " + already.describe());
        expect("已到位 ⇒ 保持（不再往前顶）", already.isHold(), already.describe());
    }

    // ═══════════════ 3. 伺服在站位上（弓手拉开 / 剑士贴身）═══════════════

    private static void testServoOnPreferredRange() {
        section("3. ★★ 无强需求时【伺服在站位】—— 弓手拉开、剑士贴身（无需任何手写规则）");

        double bowRange = 12.0;
        Gait kite = GaitSelector.select(p(0, 0), true, 4.0, bowRange, 100, false);
        System.out.println("   弓（站位 12），敌人贴到 4 格 → " + kite.describe());
        expect("★ 弓手被贴脸 ⇒ 主动拉开（风筝）",
                kite.direction() == Gait.Direction.AWAY, kite.describe());

        Gait hold = GaitSelector.select(p(0, 0), true, 11.5, bowRange, 100, false);
        System.out.println("   弓（站位 12），距离 11.5 格 → " + hold.describe());
        expect("★ 已在站位内 ⇒ 保持（不抖动）", hold.isHold(), hold.describe());

        Gait close = GaitSelector.select(p(0, 0), true, 30.0, bowRange, 100, false);
        System.out.println("   弓（站位 12），距离 30 格 → " + close.describe());
        expect("★ 太远 ⇒ 靠近到站位", close.direction() == Gait.Direction.TOWARD, close.describe());
    }

    // ═══════════════ 4. 近战不会"太近就后退" ═══════════════

    private static void testMeleeNeverBacksOff() {
        section("4. ★ 近战载体（站位 ≤ 3.5）不会因为「太近」而后退 —— 否则会贴上去又退开");

        Gait melee = GaitSelector.select(p(0, 0), true, 1.0, 2.5, 100, false);
        System.out.println("   剑（站位 2.5），距离 1.0 格 → " + melee.describe());
        expect("★ 贴身时近战保持（不后退）", melee.isHold(), melee.describe());

        Gait far = GaitSelector.select(p(0, 0), true, 20.0, 2.5, 100, false);
        System.out.println("   剑（站位 2.5），距离 20 格 → " + far.describe());
        expect("★ 太远 ⇒ 靠近", far.direction() == Gait.Direction.TOWARD, far.describe());
    }

    // ═══════════════ 5. 导航锁：动作接管时步法让位 ═══════════════

    private static void testNavigationLockYields() {
        section("5. ★ 动作接管导航时（如「撤退&脱战」）步法必须让位 —— 否则会取消它自己的寻路");

        Gait locked = GaitSelector.select(p(-0.9, 0), true, 3.0, 2.5, 100, true);
        System.out.println("   接管中 + 位移需求为负 → " + locked.describe());
        expect("★ 接管期间一律保持（连 stop 都不能调）", locked.isHold(), locked.describe());
    }

    // ═══════════════ 6. 站位是载体的属性 ⇒ 换武器就换打法 ═══════════════

    private static void testCarrierRangeIsWhatChangesBehaviour() {
        section("6. ★★ 同一情境、只改「站位」（= 换了一种武器）⇒ 行为不同");

        double dist = 3.5;
        Gait withBlade = GaitSelector.select(p(0, 0), true, dist, 3.0, 100, false);
        Gait withBow = GaitSelector.select(p(0, 0), true, dist, 12.0, 100, false);
        System.out.println("   距离 3.5 格：拔刀剑（站位 3）→ " + withBlade.describe()
                + " ；弓（站位 12）→ " + withBow.describe());
        expect("★ 剑士在 3.5 格时【不动】（在站位迟滞带内）", withBlade.isHold(), withBlade.describe());
        expect("★★ 弓手在 3.5 格时【拉开】 —— 手里拿什么就站什么距离",
                withBow.direction() == Gait.Direction.AWAY, withBow.describe());
    }

    // ═══════════════ 7. 迟滞带防抖动 ═══════════════

    private static void testHysteresisPreventsJitter() {
        section("7. 迟滞带 ±1.5 格：站位附近不抖动（否则会出现「走一步停一步」）");

        int moves = 0;
        for (double d = 11.0; d <= 13.0; d += 0.25) {
            if (!GaitSelector.select(p(0, 0), true, d, 12.0, 100, false).isHold()) {
                moves++;
            }
        }
        System.out.println("   在 11.0~13.0 格（站位 12 ± 迟滞 1.5）内被判定为「需要移动」的次数：" + moves);
        expect("★ 站位 ±1.5 格内应当全部保持（0 次移动）", moves == 0, "moves=" + moves);

        // 迟滞带之外必须动 —— 否则就变成"永远不动"了
        int outside = 0;
        for (double d : new double[]{10.0, 14.0}) {
            if (!GaitSelector.select(p(0, 0), true, d, 12.0, 100, false).isHold()) {
                outside++;
            }
        }
        System.out.println("   在迟滞带之外（10.0 / 14.0）判为「需要移动」的次数：" + outside);
        expect("★ 迟滞带之外必须移动（否则等于永远不动）", outside == 2, "outside=" + outside);
    }

    // ═══════════════ 8. 接管导航的白名单 ═══════════════

    private static void testActionTakesNavigationWhitelist() {
        section("8. 哪些动作会接管导航（白名单必须显式且最小）");

        expect("「撤退&脱战」接管导航", GaitSelector.actionTakesNavigation("fight_like_player:disengage"),
                "disengage");
        expect("通用近战【不】接管导航（打的同时仍可走位）",
                !GaitSelector.actionTakesNavigation("maid_native:melee_swing"), "melee_swing");
        expect("★ 走位本身已不是动作 ⇒ 不该再有 charge/retreat 这两个 id",
                !GaitSelector.actionTakesNavigation("fight_like_player:charge")
                        && !GaitSelector.actionTakesNavigation("fight_like_player:retreat"), "charge/retreat");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /** 只带 MOBILITY 的弹簧点。 */
    private static NeedVector p(double mobility, double single) {
        return NeedVector.of(NeedAxis.MOBILITY, mobility, NeedAxis.SINGLE_DAMAGE, single);
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

    // ───────────── ★★ 身法防御分（第十六轮，委托方要求）─────────────

    /**
     * ★★ 委托方原话：「**给身法侧增加防御分数**就好了，身法不需要物品，不论何时都是可行端口。
     * 比如增加：『面向仇恨目标向右后方撤』+ 它的左后版本，这两者有**防御端**的分数。」
     *
     * <p>这组断言钉住的是**两个需求各得其所**：
     * 想跑（位移需求极负）⇒ 正后方；在挨打（防御需求高）⇒ 斜后撤；
     * `no_retreat`（死战不退）⇒ **三个后撤全都不许**（包括两个斜的）。
     */
    private static void testGaitDefenseScore() {
        section("\u2605\u2605 \u8eab\u6cd5\u9632\u5fa1\u5206\uff1a\u8eab\u6cd5\u4e0d\u9700\u8981\u7269\u54c1\uff0c\u968f\u65f6\u53ef\u884c\u7684\u9632\u5b88\u51fa\u53e3");

        // ① 单纯想跑（位移 −1.0、没有防御压力）⇒ 正后方
        expect("\u2605\u2605 \u60f3\u8dd1\uff08\u4f4d\u79fb \u22121.0\uff09\u21d2 \u6b63\u540e\u65b9",
                GaitScoring.pick(-1.0, 0.0, true, false) == Gait.Direction.AWAY,
                String.valueOf(GaitScoring.pick(-1.0, 0.0, true, false)));

        // ② 在挨打（防御需求 2.0 = 顶格）、位移需求一般 ⇒ 斜后撤（防御分高）
        Gait.Direction safe = GaitScoring.pick(-0.3, 2.0, true, false);
        expect("\u2605\u2605 \u6328\u6253\uff08\u9632\u5fa1 2.0\uff09\u21d2 \u659c\u540e\u64a4\uff08\u5de6/\u53f3\uff09",
                safe == Gait.Direction.AWAY_LEFT || safe == Gait.Direction.AWAY_RIGHT,
                String.valueOf(safe));
        expect("\u2605 \u4e14\u5b83\u786e\u5b9e\u662f\u300c\u540e\u64a4\u300d\u65cf\uff08\u53d7 no_retreat \u7ba1\uff09",
                safe.isRetreat(), String.valueOf(safe));

        // ③ 两个斜后撤都存在，且"左/右"是镜像的两种选择（同分时靠前的优先，所以固定取左）
        expect("\u2605 \u5de6\u540e\u64a4\u4e0e\u53f3\u540e\u64a4\u90fd\u5728\u9009\u9879\u8868\u91cc",
                GaitScoring.OPTIONS.stream().anyMatch(o -> o.direction() == Gait.Direction.AWAY_LEFT)
                        && GaitScoring.OPTIONS.stream()
                        .anyMatch(o -> o.direction() == Gait.Direction.AWAY_RIGHT), "");

        // ④ 想靠近 ⇒ 靠近
        expect("\u2605 \u60f3\u8d34\u4e0a\u53bb\uff08\u4f4d\u79fb +0.8\uff09\u21d2 \u9760\u8fd1",
                GaitScoring.pick(0.8, 0.0, true, false) == Gait.Direction.TOWARD, "");

        // ⑤ ★★ 死战不退：三个后撤**全都不许**
        Gait.Direction noRetreat = GaitScoring.pick(-1.0, 2.0, false, false);
        expect("\u2605\u2605 no_retreat \u21d2 \u4e0d\u9009\u4efb\u4f55\u540e\u64a4\uff08\u5305\u62ec\u4e24\u4e2a\u659c\u7684\uff09",
                !noRetreat.isRetreat(), String.valueOf(noRetreat));
        expect("\u2605\u2605 \u4e14\u4e09\u4e2a\u540e\u64a4\u65b9\u5411\u90fd\u88ab isRetreat() \u8ba4\u51fa",
                Gait.Direction.AWAY.isRetreat() && Gait.Direction.AWAY_LEFT.isRetreat()
                        && Gait.Direction.AWAY_RIGHT.isRetreat()
                        && !Gait.Direction.TOWARD.isRetreat()
                        && !Gait.Direction.HOLD.isRetreat(), "");

        // ⑥ 已经站在合适距离、两个需求都平 ⇒ 原地
        expect("\u2605 \u4e24\u4e2a\u9700\u6c42\u90fd\u5e73\uff08\u4e14\u5df2\u5230\u4f4d\uff09\u21d2 \u539f\u5730",
                GaitScoring.pick(0.0, 0.0, true, true) == Gait.Direction.HOLD,
                String.valueOf(GaitScoring.pick(0.0, 0.0, true, true)));

        // ⑦ ★ 身法现在**看得到防御轴**了（此前只吃 MOBILITY）：
        //    同样的位移需求，防御需求不同 ⇒ 选出的方向不同（这是"加了防御分"的判据）
        Gait.Direction calm = GaitScoring.pick(-0.4, 0.0, true, false);
        Gait.Direction hurt = GaitScoring.pick(-0.4, 2.0, true, false);
        expect("\u2605\u2605 \u540c\u4f4d\u79fb\u9700\u6c42\u4e0b\uff0c\u9632\u5fa1\u9700\u6c42\u4e0d\u540c \u21d2 \u9009\u51fa\u7684\u8eab\u6cd5\u4e0d\u540c",
                calm != hurt, calm + " vs " + hurt);

        // ⑧ 端到端：GaitSelector 也想用这两轴（防御高时不该再傻乎乎地正后撤）
        Gait gCalm = GaitSelector.select(p(-0.5, 0), true, 6.0, 3.0, 2.0, false);
        Gait gHurt = GaitSelector.select(hurtNeed(), true, 6.0, 3.0, 2.0, false);
        expect("\u2605\u2605 \u6328\u6253\u65f6 GaitSelector \u9009\u7684\u662f\u659c\u540e\u64a4\uff08\u9632\u5fa1\u5206\u751f\u6548\uff09",
                gHurt.direction().isRetreat() && gHurt.direction() != Gait.Direction.AWAY,
                gHurt.describe() + " / 平静时=" + gCalm.describe());
    }

    /** 位移 −0.5 + 防御顶格 2.0 的需求点。 */
    private static NeedVector hurtNeed() {
        return NeedVector.of(NeedAxis.MOBILITY, -0.5,
                NeedAxis.MITIGATION_SURVIVAL, 2.0);
    }

    // ───────────── 辅助 ─────────────

}
