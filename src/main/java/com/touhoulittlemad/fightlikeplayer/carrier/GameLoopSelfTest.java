package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.ActionSelector;
import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.CooldownTracker;
import com.touhoulittlemad.fightlikeplayer.decision.DecisionCycle;
import com.touhoulittlemad.fightlikeplayer.decision.LoopSplit;
import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;
import com.touhoulittlemad.fightlikeplayer.decision.SpringImpact;
import com.touhoulittlemad.fightlikeplayer.decision.SpringSpace;
import com.touhoulittlemad.fightlikeplayer.decision.StallDetector;
import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * <b>游戏侧循环回放自测</b>（{@code GameLoopSelfTest}）—— 用<b>真实清单</b>把
 * {@code PlayerLikeCombat} 每 tick 做的那套编排重放 N 个 tick，断言那些
 * <b>"离线测试全绿、游戏里却不工作"</b>的不变量。
 *
 * <h2>★★ 为什么必须补这个测试（一次真实的教训）</h2>
 * 2026-09-30 的 S0 实测结果是：<b>弓拉满但不射</b>、<b>执行处疯狂执行</b>、<b>不论注入什么向量都只是到处跑</b>。
 * 而当时 <b>10 项检查 / 115 项断言全部通过</b>。为什么？
 *
 * <blockquote>
 * 因为 {@code DecisionCycle} 的三个"权威钩子"（执行器回报完成 / 默认动作 / 回退动作）
 * <b>只在自测里被调用，游戏侧一个都没接</b>；
 * 而自测<b>自己把那些缺失的输入塞了进去</b>（例如 {@code PipelineSelfTest} 自己调
 * {@code notifyCompleted()}）⇒ 于是"缺了输入"这件事永远不会失败。
 * </blockquote>
 *
 * <p>⇒ 本测试的纪律是：<b>只喂"游戏侧真的会给"的输入</b>（包括<b>冷却账本</b> —— 它与游戏侧
 * 共用 {@link CooldownTracker}），并且把<b>数据本身</b>（{@code catalog/}）纳入断言 ——
 * 因为那次事故的一号根因就是<b>清单里有 45 个动作的承诺时长是 0</b>，
 * 而数据错误在单元测试里是看不见的。
 *
 * <pre>
 *   java -cp "build\classes\java\main;&lt;gson.jar&gt;" \
 *        com.touhoulittlemad.fightlikeplayer.carrier.GameLoopSelfTest
 * </pre>
 */
public final class GameLoopSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private static CarrierResolver resolver;
    private static List<ActionSpec> actions;
    /** ★★ 执行器账本里"已实现"的动作 id（来自 catalog/executors.json）。 */
    private static Set<String> implemented;
    private static SpringConfig CFG =
            SpringConfig.builder().deterministic().decisionInterval(1).build();

    /** "起手后必须松手"的动作 —— 它们没有承诺时长就一定会卡在"使用物品"状态。 */
    private static final Set<String> NEEDS_RELEASE = Set.of(
            "maid_native:bow_shot",
            "maid_native:crossbow_shot",
            "maid_native:trident",
            "maid_native:danmaku",
            "maid_native:throw_any_item",
            "maid_native:shield_block");

    /** 已确认【真·瞬时】的动作：承诺为 0 是正确的，靠冷却兜底节奏。 */
    private static final Set<String> LEGIT_INSTANT = Set.of(
            // ★ 2026-10-01：goety:cast_focus 已【移出】本名单 —— 它现在必须是非零承诺
            //   （引导法术需要 in-flight tick 才会被推进；见 testGoetyCastFocusHasCommitment）
            "irons:cast_spell",
            "irons:cast_scroll",
            "tacz:shoot",
            "slashblade:summoned_sword");

    public static void main(String[] args) throws Exception {
        System.out.println("=== 游戏侧循环回放自测 (GameLoopSelfTest) ===");
        System.out.println();

        CatalogLoader.Loaded loaded = CatalogLoader.loadFromWorkingDir(Path.of("catalog"));
        actions = loaded.actions();
        resolver = new CarrierResolver(loaded.actions(), loaded.rules());
        implemented = loaded.implementedActionIds();
        System.out.println("清单：动作 " + loaded.actions().size() + " 条，载体规则 "
                + loaded.rules().size() + " 条，执行器账本 " + implemented.size() + " 条");
        System.out.println();

        testNeedsReleaseHasCommitment();
        testEveryChoiceHasRhythm();
        testCommitmentGateHolds();
        testExecutorReportOpensGateEarly();
        testBusyGateWithoutCommitment();
        testCooldownFiltersRepeats();
        testMovementIsNotAnAction();
        testSlashBladeProducesBladeActions();
        testNoExecutorActionsAreNotSelectable();
        testGoetyStarvationIsFixed();
        testOnlyItemReallyFilters();
        testSummonSlotGate();
        testSatisfactionKnobChangesAttackFrequency();
        testBackpackItemDrivesActionAndCarriesEvidence();
        testDefaultActionExistsInDeathZone();
        testReplayProducesSaneRhythm();
        testExecutorLedgerMatchesCatalog();
        testGoetyCastFocusHasCommitment();
        testStallWatchdogPolicy();
        testLoopSplitIsolationInvariant();
        testGoetyChannelShapePolicy();
        testComboChain();
        testComboAndDiversityStats();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════ 1. 需要收尾的动作必须有承诺时长 ═══════════

    private static void testNeedsReleaseHasCommitment() {
        section("1. ★★ 需要「松手」的动作必须有承诺时长（否则 finish() 永不触发 ⇒ 弓拉满不射）");

        for (String id : NEEDS_RELEASE) {
            ActionSpec a = resolver.specOf(id);
            if (a == null) {
                expect("动作存在：" + id, false, "清单里没有这条");
                continue;
            }
            System.out.println("   " + pad(id) + " 承诺=" + a.commitmentTicks() + " tick");
            expect("★ " + id + " 的承诺 > 0", a.commitmentTicks() > 0,
                    "承诺=" + a.commitmentTicks() + " ⇒ 永不放箭/永不收手");
        }
    }

    // ═══════════ 2. 每个可评分动作都有节奏 ═══════════

    private static void testEveryChoiceHasRhythm() {
        section("2. ★ 每个 choice 动作都要有非零的最小重复间隔（否则瞬时动作被 4 次/秒刷屏）");

        int zeroRhythm = 0;
        List<String> zeroCommit = new ArrayList<>();
        for (ActionSpec a : actions) {
            if (!a.isChoice()) {
                continue;
            }
            if (a.effectiveCooldownTicks() <= 0) {
                zeroRhythm++;
            }
            if (a.commitmentTicks() == 0) {
                zeroCommit.add(a.id());
            }
        }
        System.out.println("   choice 动作中承诺=0 的：" + zeroCommit.size() + " 条"
                + "（已登记为真·瞬时的白名单：" + LEGIT_INSTANT.size() + " 条）");
        for (String id : zeroCommit) {
            if (!LEGIT_INSTANT.contains(id)) {
                System.out.println("      （计划缺口，非 bug）" + id);
            }
        }
        expect("★ 没有任何动作的【最小重复间隔】为 0", zeroRhythm == 0, "有 " + zeroRhythm + " 条");
    }

    // ═══════════ 3. 执行门控真的生效 ═══════════

    private static void testCommitmentGateHolds() {
        section("3. ★ 执行门控：承诺期内不重选（旧实现 45 条承诺=0 ⇒ 门控等于没有）");

        List<String> sunk = new ArrayList<>();
        DecisionCycle<ContextFacts> cyc = cycle(sunk, List.of(bow()));
        ContextFacts ctx = combat(1.0, 1.0, 1, 10.0);

        cyc.tick(ctx, 0);
        String first = sunk.isEmpty() ? null : sunk.get(0);
        System.out.println("   t=0 选中：" + first);
        expect("★ 持弓时会选出射击动作（而不是乱跑）", "maid_native:bow_shot".equals(first),
                String.valueOf(first));

        for (int t = 1; t <= 19; t++) {
            cyc.tick(ctx, t);
        }
        System.out.println("   t=1..19 期间执行次数：" + sunk.size() + "（应为 1）");
        expect("★★ 承诺期内【不重选】（1 秒内只起手一次）", sunk.size() == 1,
                "执行了 " + sunk.size() + " 次 ⇒ 门控失效");
    }

    // ═══════════ 4. 执行器回报完成 ⇒ 提前开下一周期 ═══════════

    private static void testExecutorReportOpensGateEarly() {
        section("4. ★★ 执行器回报完成 ⇒ 立刻开下一周期（不必等满清单里的兜底 tick 数）");

        List<String> sunk = new ArrayList<>();
        DecisionCycle<ContextFacts> cyc = cycle(sunk, List.of(bow()));
        ContextFacts ctx = combat(1.0, 1.0, 1, 10.0);

        cyc.tick(ctx, 0);
        int afterStart = sunk.size();
        boolean reported = cyc.notifyCompleted();     // 执行器说"箭已经射出去了"
        cyc.tick(ctx, 1);
        System.out.println("   起手后执行数=" + afterStart + "；回报完成后再 tick ⇒ 执行数=" + sunk.size());
        expect("★ 回报被识别", reported, "notifyCompleted=false");
        expect("★★ 回报后立刻可以开新周期（第 2 次执行发生）", sunk.size() > afterStart,
                "执行数没变 ⇒ 回报被忽略");
    }

    // ═══════════ 4.5 ★★ 执行器在忙 ⇒ 即使承诺为 0 也不许重选 ═══════════

    /**
     * ★★★ <b>第二十一轮（委托方实测"让她只用铁魔法，她隔了好久才放出一个法术"）</b>。
     *
     * <p>病灶：{@code BusySource} 的注释从第一天写的就是**无条件**的
     * 「在执行器说'还在忙'期间，本循环不开新决策」，而实现只写在**承诺到期之后**那一支里
     * ⇒ 只有 {@code commitmentTicks() > 0} 的动作才享受它。
     * 铁魔法的前摇恰好是「承诺 0 + 执行器确实在忙」（{@code irons:cast_spell} 是**族动作**，
     * 静态承诺只能给族默认 = 瞬发；真正的蓄力由运行时挑中的那颗法术决定）
     * ⇒ 决策层每 tick 重选 ⇒ 重选到同一条就**重新起手**（前摇 `readyAt` 被覆盖）⇒ 法术永远放不出来。
     *
     * <p>这条断言用**纯逻辑夹具**复现它：一条瞬时动作（承诺 0），执行器起手后把自己标成"忙"
     * ⇒ 在忙清掉之前，执行器<b>只能被调一次</b>。
     */
    private static void testBusyGateWithoutCommitment() {
        section("4.5. ★★★ 执行器在忙 ⇒ 承诺为 0 也不许重选（铁魔法前摇那个 bug）");

        // ★ 前提断言：铁魔法施法的**静态**承诺确实是 0（族默认 = 瞬发）
        //   否则这条测试就是空话（"我测的那条动作其实有承诺"）
        ActionSpec cast = resolver.specOf("irons:cast_spell");
        System.out.println("   irons:cast_spell 的静态承诺 = "
                + (cast == null ? "?" : cast.commitmentTicks()) + " tick（族动作 ⇒ 由运行时法术决定）");
        expect("★ 前提：irons:cast_spell 的静态承诺 = 0（所以必须靠执行器报'忙'）",
                cast != null && cast.commitmentTicks() == 0,
                cast == null ? "清单里没有这条" : String.valueOf(cast.commitmentTicks()));

        CandidateAction spell = CandidateAction.instant("irons:cast_spell", NeedVector.zeros());
        boolean[] busy = {false};
        int[] calls = {0};
        DecisionCycle<ContextFacts> cyc = new DecisionCycle<>(CFG,
                ContextBias::of,
                ctx -> List.of(spell),
                (a, ctx) -> {
                    calls[0]++;
                    busy[0] = true;          // ★ 起手 ⇒ 执行器进入"前摇中"
                    return true;
                },
                () -> 0L, new Random(1))
                .withBusySource(ctx -> busy[0]);
        ContextFacts ctx = combat(1.0, 1.0, 1, 2.0);

        cyc.tick(ctx, 0);
        expect("★ 第一次照常起手", calls[0] == 1, String.valueOf(calls[0]));

        for (int t = 1; t <= 20; t++) {
            cyc.tick(ctx, t);                // ← 旧实现在这里会每 tick 重新起手（前摇永远走不完）
        }
        System.out.println("   前摇 20 tick 内的起手次数 = " + calls[0] + "（必须仍是 1）");
        expect("★★★ 前摇期间【不重选】（旧实现每次重选都会把前摇重新计一遍）",
                calls[0] == 1, "起手了 " + calls[0] + " 次 ⇒ 法术永远放不出来");

        busy[0] = false;                     // ★ 前摇结束（执行器把法术放出去并清掉在途状态）
        cyc.tick(ctx, 21);
        expect("★★ 忙结束后立刻可以开新周期", calls[0] == 2, String.valueOf(calls[0]));

        // ★ 看门狗②：执行层可能漏清在途状态 —— 绝不许它把决策层锁死
        boolean[] stuck = {true};
        int[] calls2 = {0};
        DecisionCycle<ContextFacts> cyc2 = new DecisionCycle<>(CFG,
                ContextBias::of,
                ctx2 -> List.of(spell),
                (a, ctx2) -> {
                    calls2[0]++;
                    return true;
                },
                () -> 0L, new Random(1))
                .withBusySource(ctx2 -> stuck[0]);
        for (int t = 0; t <= 260; t++) {
            cyc2.tick(ctx, t);
        }
        System.out.println("   一直报'忙' 260 tick ⇒ 决策次数 = " + calls2[0] + "（必须 ≥ 2：看门狗放行）");
        expect("★★ 一直忙也不会把决策层锁死（200 tick 后照常决策 + 留痕）",
                calls2[0] >= 2, "只决策了 " + calls2[0] + " 次 ⇒ 看门狗失效");

        // ★★ 两个"忙"必须分开：**纯冷却不算"她本人在忙"**（否则开完一枪她只会站着等冷却）
        boolean[] waiting = {true};        // 老门（"这条动作做完了没"）：还在冷却 ⇒ true
        boolean[] acting = {false};        // 新门（"她本人能不能另开动作"）：冷却不算 ⇒ false
        int[] calls3 = {0};
        DecisionCycle<ContextFacts> cyc3 = new DecisionCycle<>(CFG,
                ContextBias::of,
                ctx3 -> List.of(spell),
                (a, ctx3) -> {
                    calls3[0]++;
                    return true;
                },
                () -> 0L, new Random(1))
                .withBusySource(ctx3 -> waiting[0])
                .withActiveBusySource(ctx3 -> acting[0]);
        for (int t = 0; t <= 30; t++) {
            cyc3.tick(ctx, t);
        }
        System.out.println("   只有冷却在走（activeBusy=false）⇒ 决策次数 = " + calls3[0] + "（应继续决策）");
        expect("★★ 纯冷却不锁决策层（她该去换别的动作，而不是站着等冷却）",
                calls3[0] >= 2, "只决策了 " + calls3[0] + " 次 ⇒ 两个'忙'没分开");
    }

    // ═══════════ 5. 冷却过滤 ═══════════
    private static void testCooldownFiltersRepeats() {
        section("5. ★ 冷却过滤走既有通路：把动作放进 coolingDown ⇒ 它不在候选里（DropReason.ON_COOLDOWN）");

        List<PossessedItem> bow = List.of(bow());
        ContextFacts hot = combatWith(1.0, 1.0, 1, 10.0, Set.of("maid_native:bow_shot"));
        CarrierResolver.Resolution r = resolver.resolve(bow, hot, CFG);

        boolean stillThere = r.candidates().stream().anyMatch(c -> c.id().equals("maid_native:bow_shot"));
        boolean explained = r.dropped().stream().anyMatch(d ->
                d.actionId().equals("maid_native:bow_shot") && d.reason() == DropReason.ON_COOLDOWN);

        System.out.println("   冷却中的候选：" + ids(r));
        expect("★ 冷却中的动作不在候选集里", !stillThere, ids(r).toString());
        expect("★ 且丢弃原因是 ON_COOLDOWN（不是静默消失）", explained, r.summary());

        int bowCd = resolver.cooldownTicksOf("maid_native:bow_shot");
        System.out.println("   弓的最小重复间隔 = " + bowCd + " tick");
        expect("★ 冷却时长 ≥ 承诺时长", bowCd >= 20, String.valueOf(bowCd));

        // ★ 冷却账本：用过一次之后，在冷却窗口内必须判定为"冷却中"
        CooldownTracker cd = new CooldownTracker();
        cd.markUsed("maid_native:bow_shot", 100);
        expect("★ 账本在窗口内判冷却中",
                cd.isCoolingDown("maid_native:bow_shot", 100 + bowCd - 1, resolver::cooldownTicksOf), "窗口内");
        expect("★ 账本在窗口外放行",
                !cd.isCoolingDown("maid_native:bow_shot", 100 + bowCd, resolver::cooldownTicksOf), "窗口外");
    }

    // ═══════════ 6. 走位不再是候选动作 ═══════════

    private static void testMovementIsNotAnAction() {
        section("6. ★★ 走位不是候选动作了（否则 charge/retreat 会互相偿还 MOBILITY ⇒ 永久震荡）");

        CarrierResolver.Resolution r = resolver.resolve(List.of(sword()), combat(1.0, 1.0, 1, 20.0), CFG);
        List<String> ids = ids(r);
        System.out.println("   持剑候选：" + ids);

        expect("★ 候选里有通用近战（★ 回归保护：custom 前置修好后它必须回来）",
                ids.contains("maid_native:melee_swing"), ids.toString());
        expect("★ 候选里没有 charge", ids.stream().noneMatch("fight_like_player:charge"::equals), ids.toString());
        expect("★ 候选里没有 retreat", ids.stream().noneMatch("fight_like_player:retreat"::equals), ids.toString());

        ActionSpec gait = resolver.specOf("fight_like_player:gait");
        expect("★ 步法在清单里（作为知识记录）", gait != null, "清单里没有 gait");
        if (gait != null) {
            expect("★★ 步法的 role = stance（★ 因此永不进候选集）", "stance".equals(gait.role()), gait.role());
        }

        double swordRange = resolver.preferredRangeOf("maid_native:melee_swing");
        double bowRange = resolver.preferredRangeOf("maid_native:bow_shot");
        System.out.println("   站位：近战 " + swordRange + " 格 / 弓 " + bowRange + " 格");
        expect("★★ 弓的站位显著大于近战（手里拿什么就站什么距离）", bowRange > swordRange + 5,
                bowRange + " vs " + swordRange);
    }

    // ═══════════ 6c. ★★ 拔刀剑：持刀 ⇒ 拿到刀的动作，且通用近战被「专精度」让位 ═══════════

    /**
     * ★★ <b>委托方 2026-10-01 实测反馈「女仆使用拔刀剑没有动作」的离线回归</b>。
     *
     * <p>这条反馈有两种可能的原因，本测试把它们<b>分开钉住</b>：
     * <ol>
     *   <li><b>解析侧</b>：持刀时到底有没有刀的动作进候选？
     *       —— 拔刀剑物品<b>同时</b>命中 {@code slashblade:blade}（{@code javaInstanceOf}，专精度 30）
     *       与 {@code maid:weapon}（{@code itemAttribute}，专精度 10）。
     *       修法见 {@link CarrierResolver} 的「专精度抑制」：<b>更具体的规则赢</b>，
     *       否则一把刀会同时提供 14 条刀技 + 通用近战，通用近战会把刀技的概率稀释掉。</li>
     *   <li><b>执行侧</b>：选中的刀技能不能真的挥出去？—— 那要真实的
     *       {@code CapabilitySlashBlade}，只能游戏内验；但 {@code catalog/executors.json}
     *       必须<b>已经登记</b>这些 id，否则永远是 {@code NO_EXECUTOR}（本测试末尾会查账本）。</li>
     * </ol>
     */
    private static void testSlashBladeProducesBladeActions() {
        section("6c. ★★ 持拔刀剑 ⇒ 刀技进候选，且「通用近战」因专精度更低而让位");

        // ★ 关键：这件物品【同时】带 typeNames(ItemSlashBlade) 与 attr 能力 —— 模拟真实拔刀剑
        PossessedItem blade = PossessedItem.withCapabilities(SlotKind.MAINHAND,
                "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"),
                Set.of("attr:minecraft:generic.attack_damage"));

        CarrierResolver.Resolution r = resolver.resolve(List.of(blade), combat(1.0, 1.0, 1, 3.0), CFG);
        List<String> ids = ids(r);
        long bladeActions = ids.stream().filter(s -> s.startsWith("slashblade:")).count();
        System.out.println("   持刀候选（" + ids.size() + " 条，其中刀技 " + bladeActions + "）：" + ids);

        expect("★★ 持刀时确实拿到了刀的动作（刀不再「没有动作」）", bladeActions > 0,
                "刀技 0 条 ⇒ 候选=" + ids);
        expect("★ 且包含最基础的 combo_a", ids.contains("slashblade:combo_a"), ids.toString());
        expect("★ 也包含不吃输入的 slash_art（刀技）", ids.contains("slashblade:slash_art"), ids.toString());

        expect("★★ 通用近战被【专精度抑制】掉（否则它会稀释 14 条刀技）",
                !ids.contains("maid_native:melee_swing"), ids.toString());
        boolean suppressedExplained = r.notes().stream().anyMatch(s -> s.contains("专精度"));
        expect("★ 且抑制这件事【被说明了】（不是静默消失）", suppressedExplained, r.notes().toString());

        // ★ 反面对照：普通剑（不带 ItemSlashBlade）仍然只能拿到通用近战
        CarrierResolver.Resolution r2 = resolver.resolve(List.of(sword()), combat(1.0, 1.0, 1, 3.0), CFG);
        List<String> ids2 = ids(r2);
        System.out.println("   对照（普通剑）：" + ids2);
        expect("★ 对照：普通剑只有通用近战，没有刀技",
                ids2.contains("maid_native:melee_swing")
                        && ids2.stream().noneMatch(s -> s.startsWith("slashblade:")), ids2.toString());

        // ★ 背包里的刀也要能驱动刀技，并带出「依据物品」（换手前置靠它）
        PossessedItem bladeInBag = PossessedItem.withCapabilities(SlotKind.INVENTORY,
                "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"),
                Set.of("attr:minecraft:generic.attack_damage"));
        CarrierResolver.Resolution r3 = resolver.resolve(List.of(bladeInBag), combat(1.0, 1.0, 1, 3.0), CFG);
        boolean bagBlade = ids(r3).contains("slashblade:combo_a");
        expect("★★ 背包里的刀也产出刀技（① 按拥有物决定循环）", bagBlade, ids(r3).toString());
        expect("★ 且带出「依据物品」＝那把刀（③ 换手前置的输入）",
                r3.evidenceOf("slashblade:combo_a") != null
                        && "slashblade:slashblade".equals(r3.evidenceOf("slashblade:combo_a").itemId()),
                String.valueOf(r3.evidenceOf("slashblade:combo_a")));

        // ★ 站位：刀的 preferredRange 必须大于徒手/通用近战（步法据此贴身）
        double bladeRange = resolver.preferredRangeOf("slashblade:combo_a");
        double plainRange = resolver.preferredRangeOf("maid_native:melee_swing");
        System.out.println("   站位：刀 " + bladeRange + " 格 / 通用近战 " + plainRange + " 格");
        expect("★ 刀的站位 ≥ 通用近战（SA / 幻影剑是中距离起手）", bladeRange >= plainRange,
                bladeRange + " vs " + plainRange);
    }

    // ═══════════ 6d. ★★ 没有执行器的动作【不得成为选项】（2026-10-01 实测的根因） ═══════════

    /**
     * ★★ <b>委托方实测「法术无法释放 / 拔刀剑没有动作」的头号根因</b>的离线回归。
     *
     * <h2>故障链（每一环都已在真实日志里数出来）</h2>
     * <pre>
     * 清单里有一批动作是【可达(RA/RB) + 有向量 + 但没有执行器】
     *   ⇒ 它们照常进候选集
     *   ⇒ 被最近邻选中
     *   ⇒ 执行器回报 NO_EXECUTOR
     *   ⇒ 按设计"没做出来就不扣代价"⇒ p 丝毫不移动
     *   ⇒ 下一周期最近邻【仍然是它】
     *   ⇒ 永久占住决策，把真正能做出来的动作全部饿死
     * </pre>
     * 委托方 2026-10-01 会话日志里的实测计数（被选中次数）：
     * <pre>
     *   slashblade:quick_charge          118 次  ← 一条都没做出来
     *   slashblade:summoned_sword        104
     *   slashblade:combo_b_finish        100
     *   slashblade:judgement_cut_just     98
     *   tacz:melee                        98
     *   goety:transfer_servant_ownership  70  ← 还顺带把弓挤掉（不停换手 bow↔staff）
     * </pre>
     * ⇒ 其中 {@code goety:cast_focus} 与 {@code irons:cast_spell} <b>一次都没被选中过</b>：
     * 前者被同载体的 {@code transfer_servant_ownership} 饿死，后者连候选都进不去。
     * <b>"法术无法释放"与"拔刀剑没有动作"因此是同一个 bug 的两副面孔。</b>
     */
    private static void testNoExecutorActionsAreNotSelectable() {
        section("6d. ★★ 没有执行器的动作不得成为选项（否则会永久占住决策、饿死所有能做的动作）");

        expect("★★ 执行器账本真的读到了（空集会让解析器 fail-open ⇒ 本修复静默失效）",
                !implemented.isEmpty(), "implemented 为空");
        expect("★ 账本条目数 ≥ 30（77 条清单里已实现的那些）",
                implemented.size() >= 30, String.valueOf(implemented.size()));

        CarrierResolver withLedger = new CarrierResolver(actions, resolverRules(), implemented);
        CarrierResolver without = new CarrierResolver(actions, resolverRules());

        PossessedItem blade = PossessedItem.withCapabilities(SlotKind.MAINHAND,
                "slashblade:slashblade", Set.of("ItemSlashBlade", "Item"),
                Set.of("attr:minecraft:generic.attack_damage"));

        List<String> before = ids(without.resolve(List.of(blade), combat(1.0, 1.0, 1, 3.0), CFG));
        List<String> after = ids(withLedger.resolve(List.of(blade), combat(1.0, 1.0, 1, 3.0), CFG));
        System.out.println("   持刀候选（不过滤）：" + before.size() + " 条");
        System.out.println("   持刀候选（按账本过滤）：" + after.size() + " 条 → " + after);

        // ★ 反面对照：证明"过滤"确实是生效的那一步
        expect("★★ 反面对照：不过滤时，无执行器的 quick_charge 确实在候选里（这就是病灶）",
                before.contains("slashblade:quick_charge"), before.toString());
        expect("★★ 按账本过滤后 quick_charge 不在候选里",
                !after.contains("slashblade:quick_charge"), after.toString());
        expect("★★ 同样被挡下的还有 judgement_cut_just / spiral_swords",
                !after.contains("slashblade:judgement_cut_just")
                        && !after.contains("slashblade:spiral_swords"), after.toString());
        // ★★ 第十六轮（委托方问「幻影剑实现了吗」）：**我们已经给它写了执行器**，
        //   所以它现在**不该**再出现在"被账本挡下"的名单里 ——
        //   ★ 这条断言是"撤依赖 = 覆盖率变更，必须走同一套账"（第 21 条）的落点：
        //     账本改了，这条期望值必须**显式**改，不许它悄悄漂移。
        expect("★★ 幻影剑现在【不再】被账本挡下（第十六轮补上了执行器）",
                !after.contains("slashblade:summoned_sword")
                        || implemented.contains("slashblade:summoned_sword"), after.toString());
        expect("★★ 账本里确实登记了幻影剑（否则上面那条断言会变成空话）",
                implemented.contains("slashblade:summoned_sword"), implemented.toString());

        // ★★ 最强的不变量：候选集里【任何】一条都必须有已实现执行器
        boolean allImplemented = after.stream().allMatch(implemented::contains);
        expect("★★★ 不变量：候选集里每一条动作都有已实现执行器（这是本修复的全部意义）",
                allImplemented, after.stream().filter(x -> !implemented.contains(x)).toList().toString());

        // ★ 而且不能把能做的也一起挡掉
        expect("★ 能做的刀技仍在（combo_a / slash_art）",
                after.contains("slashblade:combo_a") && after.contains("slashblade:slash_art"),
                after.toString());

        // ★ 丢弃原因必须可读（是 NO_EXECUTOR，不是静默消失）
        CarrierResolver.Resolution r = withLedger.resolve(List.of(blade), combat(1.0, 1.0, 1, 3.0), CFG);
        boolean explained = r.dropped().stream().anyMatch(d ->
                d.actionId().equals("slashblade:quick_charge") && d.reason() == DropReason.NO_EXECUTOR);
        expect("★★ 且丢弃原因是 NO_EXECUTOR（可见，不是静默消失）", explained, r.summary());
        expect("★ 解析结果带一条说明",
                r.notes().stream().anyMatch(s -> s.contains("无执行器")), r.notes().toString());

        // ★ 无执行器的 goety 动作也必须被挡（它是饿死 cast_focus 的元凶）
        PossessedItem wand = PossessedItem.of(SlotKind.MAINHAND, "goety:void_staff",
                Set.of("IWand", "Item"));
        // ★★ 必须喂 goety:can_cast —— 游戏侧由 PlayerLikeCombat#customFacts 投喂（fail-closed）
        ContextFacts caster = combatWithFacts(1.0, 1.0, 1, 6.0, Set.of(),
                Map.of("goety:can_cast", true, "irons:can_cast", true,
                        // ★ 2026-10-03：拔刀剑也补了解析期前置（见 docs/15 §3.13），夹具照喂
                        "slashblade:has_slash_art", true, "slashblade:can_summon_sword", true));
        List<String> goetyAfter = ids(withLedger.resolve(List.of(wand), caster, CFG));
        System.out.println("   持杖候选（按账本过滤 + 已喂 goety:can_cast）：" + goetyAfter);
        expect("★★ 持杖时 transfer_servant_ownership（无执行器）被挡下",
                !goetyAfter.contains("goety:transfer_servant_ownership"), goetyAfter.toString());
        expect("★★ 而 goety:cast_focus（有执行器）仍在候选里 ⇒ 施法不再被饿死",
                goetyAfter.contains("goety:cast_focus"), goetyAfter.toString());
        expect("★ 反面对照：不喂 goety:can_cast 时 cast_focus 被 fail-closed 丢掉（前置条件真的在起作用）",
                !ids(withLedger.resolve(List.of(wand), combat(1.0, 1.0, 1, 6.0), CFG))
                        .contains("goety:cast_focus"), "没喂事实也拿到了 ⇒ 前置条件形同虚设");

        // ★ 铁魔法：载体判据是 capability（曾因没人喂 irons:isSpellContainer 而永远拿不到）
        PossessedItem book = PossessedItem.withCapabilities(SlotKind.MAINHAND,
                "irons_spellbooks:iron_spell_book", Set.of("SpellBook", "Item"),
                Set.of("irons:isSpellContainer"));
        List<String> ironsAfter = ids(withLedger.resolve(List.of(book), caster, CFG));
        System.out.println("   持法术书候选（带 irons:isSpellContainer 能力）：" + ironsAfter);
        expect("★★ 持『带 irons:isSpellContainer 能力的物品』时铁魔法施法进候选",
                ironsAfter.contains("irons:cast_spell"), ironsAfter.toString());
        expect("★★ 反面对照：没有那个能力键时，同一件物品【拿不到】任何铁魔法动作"
                        + "（这就是「铁魔法法术全部无法释放」的根因）",
                !ids(withLedger.resolve(List.of(PossessedItem.of(SlotKind.MAINHAND,
                                "irons_spellbooks:iron_spell_book", Set.of("SpellBook", "Item"))),
                        caster, CFG)).contains("irons:cast_spell"), "没有能力键也能拿到");
    }

    /** 真实载体规则（从清单再读一次，避免改坏上面的 resolver 字段）。 */
    private static List<CarrierRule> resolverRules() {
        try {
            return CatalogLoader.loadFromWorkingDir(Path.of("catalog")).rules();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ═══════════ 6e. ★★ goety 施法被"空转动作"饿死（2026-10-01 第二次实测） ═══════════

    /**
     * ★★ <b>委托方实测「goety 法术仍然不可行」的根因回归</b>。
     *
     * <p>日志统计：{@code goety:recall_servants} → {@code UNAVAILABLE} <b>20 次</b>
     * （还造成 15 次「换手→法杖」+ 14 次「换手→枪」的抖动），
     * 而 {@code goety:cast_focus} <b>一次都没被选中过</b>。
     *
     * <p>根因：{@code cast_focus} / {@code recall_servants} / {@code execute_servant}
     * <b>共用同一个载体</b> {@code goety:wand_with_focus} ⇒ 持杖时它们直接竞争；
     * 而 {@code recall_servants} 的 preconditions 原本是 {@code null}（永远满足）
     * ⇒ 它靠向量赢，执行时却因为"她根本没有仆从"返回 0 ⇒ 失败 ⇒ 不扣代价 ⇒ 再选中它
     * ⇒ <b>把 cast_focus 饿死</b>（与 §6d 的"幽灵动作"完全同型，只是这次动作有执行器）。
     */
    /**
     * ★★★ <b>委托方 2026-10-05：「only_item 完全不起效」</b>（真根因回归）。
     *
     * <p>根因在 {@code DirectiveFilter#itemPlan} 的两条回退支：
     * <b>只要她要的东西不在主手，就把整表原样还回去</b>（当年为防"清空候选集 ⇒ 她站着不动"）。
     * ⇒ 而她手上通常握着**另一把枪**，于是 `only_item` 成了空操作。
     *
     * <p>现在换手由**指令伺服**负责（与候选集无关）⇒ 断言改成：
     * <b>她要的东西在她身上 ⇒ 过滤必须真的生效</b>（哪怕结果是空表），
     * 状态是 {@code NEED_HAND}（调用方/伺服去换手）。
     */
    private static void testOnlyItemReallyFilters() {
        section("6g. ★★★ only_item 必须真的过滤（「她手上有别的枪」时也不许回退）");

        CarrierResolver r = new CarrierResolver(actions, resolverRules(), implemented);
        PossessedItem rpg = PossessedItem.of(SlotKind.MAINHAND, "tacz:rpg7",
                Set.of("IGun", "ModernKineticGunItem"));
        PossessedItem ak = PossessedItem.of(SlotKind.INVENTORY, "tacz:ak47",
                Set.of("IGun", "ModernKineticGunItem"));
        // ★ 再加一件"别的手段"（三叉戟）：`only_item(AK)` 必须把**由它承载**的动作挡下 ——
        //   这正是委托方看到的「还会拿出别的物品如三叉戟」。
        PossessedItem trident = PossessedItem.of(SlotKind.INVENTORY, "minecraft:trident",
                Set.of("TridentItem", "Item"));
        Map<String, Boolean> facts = Map.of("goety:can_cast", true, "irons:can_cast", true,
                "slashblade:has_slash_art", true, "slashblade:can_summon_sword", true,
                "goety:has_recall", false);
        ContextFacts ctx = new ContextFacts(true, true, 0, true, false, false,
                Map.of(), facts, Map.of(), Set.of(),
                Set.of("touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
                        "fight_like_player"),
                ContextFacts.Vitals.of(1.0, 1.0, 1, 6.0));
        CarrierResolver.Resolution res = r.resolve(List.of(rpg, ak, trident), ctx, CFG);
        List<String> ids = ids(res);
        System.out.println("   主手 RPG / 背包 AK / 背包三叉戟 ⇒ 候选：" + ids);

        var bus = new com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus();
        bus.issue("only_item", Map.of(), Map.of("item", "tacz:ak47"), 0, "chat", 0);
        var plan = com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter.itemPlan(bus, ids,
                id -> com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(res.evidenceOf(id)),
                com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(rpg), true);

        expect("★★★ 她身上有 AK ⇒ 状态是 NEED_HAND（伺服去把 AK 换上来）",
                plan.status() == com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter.ItemStatus.NEED_HAND, plan.status().toString());
        // ★ 断言的前提：三叉戟那类动作**本来**在候选里（否则下面的断言是空话 —— 第 9 条纪律）
        expect("★★ 前提：三叉戟的动作本来在候选里（否则下面的断言是空话）",
                ids.contains("maid_native:trident"), ids.toString());
        expect("★★★ 「只用 AK」必须挡下**由别的武器承载**的动作"
                        + "（委托方看到的「还会拿出三叉戟」就是这里漏的）",
                !plan.allowed().contains("maid_native:trident"), plan.allowed().toString());
        expect("★★★ 过滤真的发生了（旧实现把整表原样放回 ⇒ 指令等于没下）",
                plan.allowed().size() < ids.size(),
                plan.allowed().size() + " vs " + ids.size());

        // ② 不在她身上 ⇒ 这条回退保留（真正的"做不到"）
        var bus2 = new com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus();
        bus2.issue("only_item", Map.of(), Map.of("item", "minecraft:iron_sword"), 0, "chat", 0);
        var plan2 = com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter.itemPlan(bus2, ids,
                id -> com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(res.evidenceOf(id)),
                com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(rpg), false);
        expect("★★ 她身上没有那件东西 ⇒ 回退 + ABSENT（否则她会站着不动）",
                plan2.status() == com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter.ItemStatus.ABSENT
                        && plan2.allowed().size() == ids.size(),
                plan2.status() + "/" + plan2.allowed().size());
        expect("★ 回退原因可读（不许静默）", !plan2.why().isBlank(), plan2.why());
    }

    /**
     * ★★ <b>委托方问："铁魔法召唤类法术的向量是怎么样的？"</b>（第十七轮续）
     *
     * <p>三条取证结论：
     * <ol>
     *   <li>向量：`irons:cast_spell` 的 SUMMON override = `{SINGLE_DAMAGE: -0.2, REINFORCE: 0.8}`；</li>
     *   <li>★ <b>{@code REINFORCE} 这条轴从来没有来源</b>（`ContextBias` 不产出它、
     *       `FitnessCalculator` 常量 1.0）⇒ 那 0.8 是死重。★ 我试过抬它，**被本套自测拦下**
     *       （在 p 上加轴会压缩所有动作的相对间距 ⇒ 更多动作落进 tieWindow ⇒
     *        并列由 recency 打破 ⇒ 在近似动作之间轮换；`PipelineSelfTest §7` 红了）⇒ 已撤回；</li>
     *   <li>★ "用一次就很难再用"的主因是**铁魔法自己的冷却**（{@code SummonVexSpell} 默认
     *       {@code setCooldownSeconds(150.0)} = 150 秒），其次才是"已经有召唤物了"。</li>
     * </ol>
     *
     * <p>⇒ 本轮只做**能确定做对**的那一半：**召唤位**（文档 docs/09 §3.5 早就写着
     * "召唤位满 ⇒ 直接过滤"，而代码里从来没实现）。判据收进纯逻辑
     * {@link com.touhoulittlemad.fightlikeplayer.decision.ServantSlots}，
     * 由两个挑法术的池子（Goety 聚晶 / 铁魔法法术）调用 —— 动作级过滤是**错的**
     * （会把火球一起挡掉：只有 cast_focus/cast_spell 的 **override** 带 REINFORCE，
     * 它们的**默认向量是 0**，所以"动作级召唤位"根本判不出来）。
     */
    private static void testSummonSlotGate() {
        section("6h. \u2605\u2605 \u53ec\u5524\u4f4d\uff1a\u6587\u6863\u91cc\u627f\u8bfa\u8fc7\u3001\u4ee3\u7801\u4e00\u76f4\u6ca1\u505a");

        var slots = com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.class;
        expect("\u2605 \u6ca1\u6ee1 \u21d2 \u80fd\u53ec\u5524\uff08\u4e0a\u9650 " 
                        + com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP + "\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.summonAllowed(0)
                        && com.touhoulittlemad.fightlikeplayer.decision.ServantSlots
                                .summonAllowed(com.touhoulittlemad.fightlikeplayer.decision
                                        .ServantSlots.CAP - 1), "");
        expect("\u2605\u2605 \u6ee1\u4e86 \u21d2 \u4e0d\u80fd\u53ec\u5524\uff08\u8fd9\u6761\u4ee5\u524d\u6839\u672c\u6ca1\u6709\uff09",
                !com.touhoulittlemad.fightlikeplayer.decision.ServantSlots
                        .summonAllowed(com.touhoulittlemad.fightlikeplayer.decision
                                .ServantSlots.CAP), "");
        expect("\u2605 \u539f\u56e0\u53ef\u8bfb\uff08\u4e0d\u8bb8\u9759\u9ed8\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.ServantSlots
                        .describe(com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP)
                        .contains("\u5df2\u6ee1"), "");
        // ★★ 增量 + 消耗（委托方 2026-10-05：「召唤算法没必要那么精致，维度上有增量和消耗就好了」）：
        //   增量 = p[REINFORCE] = 0.5 × 缺几个 / 上限（**只在"有目标 + 她真能施法"时**给）；
        //   消耗 = 法术自己的 cooldownTicks / 冷却账本（铁魔法恼鬼 150 秒）/ 法力（本来就有）。
        java.util.function.BiFunction<Integer, Boolean, Double> increment = (servants, canCast) -> {
            java.util.Map<String, Boolean> facts = canCast
                    ? java.util.Map.of("goety:can_cast", true) : java.util.Map.of();
            ContextFacts f = new ContextFacts(true, true, servants, true, false, false,
                    java.util.Map.of(), facts, java.util.Map.of(), Set.of(),
                    Set.of("touhou_little_maid", "goety", "irons_spellbooks"),
                    ContextFacts.Vitals.of(1.0, 1.0, 2, 6.0));
            return com.touhoulittlemad.fightlikeplayer.carrier.ContextBias.of(f)
                    .get(com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.REINFORCE);
        };
        expect("\u2605\u2605 \u80fd\u65bd\u6cd5 + \u7f3a\u4eba \u21d2 \u589e\u91cf > 0\uff08\u4ee5\u524d\u6052\u4e3a 0\uff09",
                increment.apply(0, true) > 0.0, String.valueOf(increment.apply(0, true)));
        expect("\u2605 \u5979\u6839\u672c\u653e\u4e0d\u51fa\u6cd5\u672f \u21d2 \u589e\u91cf = 0"
                        + "\uff08\u4e0d\u53bb\u6324\u522b\u7684\u52a8\u4f5c\u7684\u95f4\u8ddd\uff09",
                increment.apply(0, false) == 0.0, String.valueOf(increment.apply(0, false)));
        expect("\u2605 \u53ec\u5524\u4f4d\u5df2\u6ee1 \u21d2 \u589e\u91cf = 0\uff08\u4e0d\u518d\u60f3\u53ec\u5524\uff09",
                increment.apply(com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP,
                        true) == 0.0,
                String.valueOf(increment.apply(
                        com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP, true)));

        // ★ 前提断言：清单里"召唤"不是独立动作 —— 只有三条施法动作的 override 带 REINFORCE
        //   ⇒ 动作级过滤会把火球也挡掉，所以门必须开在挑法术那一步（接线由审计脚本盯住）
        List<String> summonActions = actions.stream()
                .filter(a -> a.hasVector() && a.defaultVector()
                        .get(com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.REINFORCE) >= 0.5)
                .map(ActionSpec::id).toList();
        expect("\u2605\u2605 \u524d\u63d0\uff1a\u300c\u53ec\u5524\u300d\u5728\u6e05\u5355\u91cc\u6ca1\u6709\u72ec\u7acb\u52a8\u4f5c"
                        + "\uff08\u6240\u4ee5\u4e0d\u80fd\u505a\u6210\u52a8\u4f5c\u7ea7\u8fc7\u6ee4\uff09",
                summonActions.isEmpty(), summonActions.toString());
    }

    private static void testGoetyStarvationIsFixed() {
        section("6e. ★★ goety 施法被「空转动作」饿死：补上前置条件后 cast_focus 才能被选到");

        CarrierResolver r = new CarrierResolver(actions, resolverRules(), implemented);
        PossessedItem wand = PossessedItem.of(SlotKind.MAINHAND, "goety:void_staff",
                Set.of("IWand", "Item"));
        Map<String, Boolean> facts = Map.of("goety:can_cast", true, "irons:can_cast", true,
                // ★ 2026-10-03：夹具照喂拔刀剑的两个新事实（解析期前置）
                "slashblade:has_slash_art", true, "slashblade:can_summon_sword", true,
                "goety:has_recall", false);

        // ① 没有仆从时：recall_servants 必须【在解析期就被丢掉】
        ContextFacts noServant = new ContextFacts(true, true, 0, true, false, false,
                Map.of(), facts, Map.of(), Set.of(),
                Set.of("touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
                        "fight_like_player"),
                ContextFacts.Vitals.of(1.0, 1.0, 1, 6.0));
        CarrierResolver.Resolution res = r.resolve(List.of(wand), noServant, CFG);
        List<String> ids0 = ids(res);
        System.out.println("   无仆从 + 持杖 ⇒ 候选：" + ids0);
        System.out.println("   其中被丢弃的 goety 动作：" + res.dropped().stream()
                .filter(d -> d.actionId().startsWith("goety:")).toList());

        expect("★★ 无仆从时 recall_servants 不在候选里（它的前置条件已补齐）",
                !ids0.contains("goety:recall_servants"), ids0.toString());
        expect("★★ 同载体的另外两条空转动作也被挡下（set_servant_stance / dismiss_temporary）",
                !ids0.contains("goety:set_servant_stance")
                        && !ids0.contains("goety:dismiss_temporary_servants"), ids0.toString());
        expect("★★★ 于是 cast_focus 终于进了候选 —— 它不再被空转动作饿死",
                ids0.contains("goety:cast_focus"), ids0.toString());
        expect("★★ recall_to_saved_coords 在「回溯聚晶里没存过坐标」时被挡下"
                        + "（它有 custom 前置 fact=goety:has_recall）",
                !ids0.contains("goety:recall_to_saved_coords"), ids0.toString());

        // ② 有仆从时：召回类动作应该回来（证明前置条件不是"一禁了之"）
        ContextFacts withServant = new ContextFacts(true, true, 2, true, false, false,
                Map.of(), facts, Map.of(), Set.of(),
                Set.of("touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
                        "fight_like_player"),
                ContextFacts.Vitals.of(1.0, 1.0, 1, 6.0));
        List<String> ids1 = ids(r.resolve(List.of(wand), withServant, CFG));
        System.out.println("   有 2 个仆从 + 持杖 ⇒ 候选：" + ids1);
        expect("★★ 有仆从时 recall_servants 回来了（前置条件是「条件」而不是「禁用」）",
                ids1.contains("goety:recall_servants"), ids1.toString());
        expect("★ 且 cast_focus 仍在", ids1.contains("goety:cast_focus"), ids1.toString());

        // ②' ★★ 第十七轮（委托方第 4 条）：**即使她有仆从**，"处死仆从"也永远不进候选
        //     —— 它已按委托方要求改成 role=directive（只由上层指令触发，见 docs/13 §70）。
        expect("★★★ 处死仆从是「指令独占」：有仆从时它【仍然】不在候选里",
                !ids1.contains("goety:dismiss_temporary_servants"), ids1.toString());
        var dismissSpec = actions.stream()
                .filter(a -> "goety:dismiss_temporary_servants".equals(a.id())).findFirst().orElse(null);
        expect("★★ 它的 role = directive（因此永不进候选集，与步法的 role=stance 同型）",
                dismissSpec != null && "directive".equals(dismissSpec.role()),
                dismissSpec == null ? "清单里找不到这条动作" : dismissSpec.role());
        expect("★ 它仍然留在清单里（知识记录与执行器都没删）—— 只是「她自己选不到」",
                dismissSpec != null, "被删掉了");

        // ③ 喂上 goety:has_recall 后，回溯动作也可用
        //    ★ 注意：recall_to_saved_coords 的载体是 goety:focus_recall（回溯聚晶），
        //      不是法杖 —— 所以这里要换成"带 RecallFocus 类型的物品"，否则会先被 NO_CARRIER 丢掉。
        PossessedItem focus = PossessedItem.of(SlotKind.MAINHAND, "goety:recall_focus",
                Set.of("RecallFocus", "Item", "IWand"));
        Map<String, Boolean> facts2 = new java.util.HashMap<>(facts);
        facts2.put("goety:has_recall", true);
        ContextFacts recallable = new ContextFacts(true, true, 2, true, false, false,
                Map.of(), facts2, Map.of(), Set.of(),
                Set.of("touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
                        "fight_like_player"),
                ContextFacts.Vitals.of(1.0, 1.0, 1, 6.0));
        List<String> ids2 = ids(r.resolve(List.of(focus), recallable, CFG));
        System.out.println("   持回溯聚晶 + 2 仆从 + has_recall=true ⇒ 候选：" + ids2);
        expect("★★ 喂了 goety:has_recall=true 后 recall_to_saved_coords 才出现"
                        + "（fail-closed：没喂就永远拿不到）",
                ids2.contains("goety:recall_to_saved_coords"), ids2.toString());
        // ★ 反面对照：同一个物品，不喂事实 ⇒ 拿不到
        List<String> ids3 = ids(r.resolve(List.of(focus), withServant, CFG));
        expect("★★ 反面对照：不喂 goety:has_recall 时同一条被 fail-closed 丢掉",
                !ids3.contains("goety:recall_to_saved_coords"), ids3.toString());
    }

    // ═══════════ 6f. ★★ 满足度旋钮：下调攻击端 ⇒ 同一次攻击"更不解渴" ═══════════

    /**
     * ★★ <b>委托方第 3 条要求的可回放判据</b>：
     * 「普遍下调攻击性动作的攻击端评分分数，使得攻击需求需要更多攻击完成」。
     *
     * <p>本测试用<b>同一段战况回放</b>跑两遍，只改 {@code satisfaction.damage}，
     * 断言"攻击次数变多"。这就把一句设计意图变成了<b>可复跑的断言</b>。
     */
    private static void testSatisfactionKnobChangesAttackFrequency() {
        section("6f. ★★ 满足度旋钮：satisfaction.damage 调小 ⇒ 攻击需求要更多次攻击才满足");

        int attacksAt1 = attacksInReplay(1.0, 1.0);
        int attacksAtHalf = attacksInReplay(0.5, 1.0);
        System.out.println("   satisfaction.damage = 1.00 ⇒ 200 tick 内攻击 " + attacksAt1 + " 次");
        System.out.println("   satisfaction.damage = 0.50 ⇒ 200 tick 内攻击 " + attacksAtHalf + " 次");

        // ★★ 诚实的一条：光调满足度【不会】改节奏 —— 因为节奏被【冷却】限住。
        //    （这是自测抓出来的事实，不是推测：三种取值下都是 10 次 = 200/20tick。）
        expect("★★★ 仅下调满足度不改出手节奏（节奏被冷却限住）—— 这一条是【有意记录的事实】",
                attacksAtHalf == attacksAt1,
                attacksAt1 + " → " + attacksAtHalf + "（若不等说明节奏不是冷却限住的）");

        // 所以"要更快出手"必须同时缩短冷却：satisfaction 管"够不够解渴"，cooldown 管"能多快出手"
        int fast = attacksInReplay(1.0, 0.5);
        System.out.println("   cooldown.scale = 0.50（不改满足度）⇒ 攻击 " + fast + " 次");
        expect("★★ 缩短冷却才会真的提高出手频率（两个旋钮分工不同）",
                fast > attacksAt1, attacksAt1 + " → " + fast);

        // ★★ 而"攻击需求需要更多次攻击才满足"这件事，可以直接在弹簧上量出来：
        int need1 = attacksToSatisfy(1.0);
        int needHalf = attacksToSatisfy(0.5);
        System.out.println("   把攻击需求压到死区所需攻击次数：满足度 1.0 ⇒ " + need1
                + " 次；满足度 0.5 ⇒ " + needHalf + " 次");
        expect("★★★ 下调攻击端满足度 ⇒ 攻击需求需要【更多次】攻击才被满足（委托方第 3 条的原话）",
                needHalf > need1, need1 + " → " + needHalf);

        // ★ 机制断言：只改"结算量"，不改"选解位置"
        TuningBus bus = TuningBus.global();
        bus.resetAll();
        try {
            NeedVector u = NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8,
                    NeedAxis.MOBILITY, 0.5, NeedAxis.MITIGATION_SURVIVAL, 0.4);
            bus.set("satisfaction.damage", 0.5);
            NeedVector s = bus.satisfactionVector(u);
            expect("★★ 攻击端分量被减半（单点 0.8 → 0.4）",
                    Math.abs(s.get(NeedAxis.SINGLE_DAMAGE) - 0.4) < 1e-9,
                    String.valueOf(s.get(NeedAxis.SINGLE_DAMAGE)));
            expect("★★ 而非攻击端分量【原样不动】（位移 0.5 与免伤 0.4 都不变）",
                    Math.abs(s.get(NeedAxis.MOBILITY) - 0.5) < 1e-9
                            && Math.abs(s.get(NeedAxis.MITIGATION_SURVIVAL) - 0.4) < 1e-9,
                    s.toCompactString(CFG));
            bus.set("satisfaction.all", 0.5);
            bus.set("satisfaction.damage", 1.0);
            NeedVector s2 = bus.satisfactionVector(u);
            expect("★★ satisfaction.all 则是整体减半（含位移）",
                    Math.abs(s2.get(NeedAxis.MOBILITY) - 0.25) < 1e-9,
                    String.valueOf(s2.get(NeedAxis.MOBILITY)));

            // ★★ 调参总线的三条安全性质（任何外部写入者——命令或 LLM——都只能通过它们）
            bus.resetAll();
            expect("★★★ 未登记的旋钮被【拒绝】且原因可读（LLM 乱写键名不会静默生效）",
                    !bus.set("delete_all_actions", 1.0) && bus.lastRejection() != null,
                    "竟然接受了未知键");
            expect("★ 被拒后什么都没改（isDefault 仍为真）", bus.isDefault(), bus.overrides().toString());
            bus.set("satisfaction.damage", 999.0);
            expect("★★★ 越界值被【钳制】到上限（不该让一个外部数字把体系打爆）",
                    bus.get("satisfaction.damage") <= 2.0,
                    String.valueOf(bus.get("satisfaction.damage")));
            expect("★ 且「被钳制」这件事被记录下来（不是静默改值）",
                    bus.lastRejection() != null && bus.lastRejection().contains("钳"),
                    String.valueOf(bus.lastRejection()));
            bus.reset("satisfaction.damage");
            expect("★ 单个旋钮可回退", Math.abs(bus.get("satisfaction.damage") - 1.0) < 1e-9,
                    String.valueOf(bus.get("satisfaction.damage")));
            bus.set("bias.aggression", 0.0);
            bus.set("bias.caution", 2.0);
            NeedVector styled = bus.styleBias(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 1.0,
                    NeedAxis.MITIGATION_SURVIVAL, 1.0, NeedAxis.MOBILITY, 1.0));
            expect("★★ 风格旋钮：aggression=0 时进攻分量归零、caution=2 时生存分量翻倍",
                    Math.abs(styled.get(NeedAxis.SINGLE_DAMAGE)) < 1e-9
                            && Math.abs(styled.get(NeedAxis.MITIGATION_SURVIVAL) - 2.0) < 1e-9,
                    styled.toCompactString(CFG));
            bus.resetAll();
            expect("★★ 一键复原（动态调试必须可逆）", bus.isDefault(), bus.overrides().toString());
            expect("★ 给 LLM 的旋钮说明书非空且含全部键（提示词从实现生成，不会漂移）",
                    bus.describeForLlm().contains("satisfaction.damage")
                            && bus.describeForLlm().lines().count() == bus.knobs().size(),
                    String.valueOf(bus.describeForLlm().lines().count()));
        } finally {
            bus.resetAll();
        }
    }

    /** 用指定满足度跑 200 tick 回放，返回"攻击类动作"的执行次数。 */
    private static int attacksInReplay(double damageSatisfaction, double cooldownScale) {
        TuningBus bus = TuningBus.global();
        bus.resetAll();
        bus.set("satisfaction.damage", damageSatisfaction);
        bus.set("cooldown.scale", cooldownScale);
        try {
            GameLoop loop = new GameLoop(List.of(sword()));
            for (int i = 0; i < 200; i++) {
                loop.step();
            }
            return (int) loop.executed.stream()
                    .filter(id -> id.contains("melee") || id.contains("slash") || id.contains("gun")
                            || id.contains("bow") || id.contains("cast"))
                    .count();
        } finally {
            bus.resetAll();
        }
    }

    /**
     * ★★ <b>直接量"把这招的攻击需求压下去需要几次"</b> —— 委托方第 3 条的原话就是这个。
     *
     * <p>做法：把弹簧点设成"只想要单体攻击"，然后反复执行同一个近战动作，
     * 数到 {@code p[SINGLE_DAMAGE]} 落回死区为止。满足度越低 ⇒ 每次减得越少 ⇒ 次数越多。
     */
    private static int attacksToSatisfy(double damageSatisfaction) {
        TuningBus bus = TuningBus.global();
        bus.resetAll();
        bus.set("satisfaction.damage", damageSatisfaction);
        try {
            NeedVector melee = null;
            for (ActionSpec a : actions) {
                if (a.id().equals("maid_native:melee_swing")) {
                    melee = a.defaultVector();
                }
            }
            if (melee == null) {
                return -1;
            }
            SpringSpace space = new SpringSpace();
            space.setPoint(NeedVector.of(NeedAxis.SINGLE_DAMAGE, 1.0));
            for (int i = 1; i <= 50; i++) {
                space.publish(SpringImpact.subtract(bus.satisfactionVector(melee),
                        "test", "melee_swing"));
                space.drain(CFG);
                if (space.point().get(NeedAxis.SINGLE_DAMAGE) <= 0.05) {
                    return i;
                }
            }
            return 50;
        } finally {
            bus.resetAll();
        }
    }

    // ═══════════ 6b. ★★ 逻辑层次的第三环：背包里的物品也能驱动动作，并且带得出"依据物品" ═══════════

    /**
     * ★★ 委托方指出的逻辑层次是：
     * <pre>
     *   ① 按女仆【拥有的道具】（含物品栏）决定循环
     *   ② 循环决定动作
     *   ③ 若动作的道具不在手上 ⇒ 切到手上        ← 执行器前置（游戏侧）
     * </pre>
     * 本测试钉住 ① 与 ③ 的<b>输入侧</b>：<b>背包里的弓也要产出"弓射击"候选</b>，
     * 并且解析结果要能回答"是哪件物品让它可用的"（`Resolution#evidenceOf`）——
     * 执行器的换手前置正是靠这个证据去背包里找同类物品。
     *
     * <p>⚠️ ③ 的<b>动作侧</b>（真的调用换手 API）只能在游戏里验：它要
     * {@code TaskEquipUtil.tryEquipFromBackpack} 与真实的 {@code EntityMaid} 容器。
     */
    private static void testBackpackItemDrivesActionAndCarriesEvidence() {
        section("6b. ★★ 背包里的物品也进候选（第三环的输入侧），且解析结果带出「依据物品」");

        PossessedItem bowInBag = PossessedItem.of(SlotKind.INVENTORY, "minecraft:bow",
                Set.of("BowItem", "Item"));
        CarrierResolver.Resolution r = resolver.resolve(
                List.of(sword(), bowInBag), combat(1.0, 1.0, 1, 10.0), CFG);
        List<String> ids = ids(r);
        System.out.println("   手上剑 + 背包弓 ⇒ 候选：" + ids);

        expect("★★ 背包里的弓也产出「弓射击」（① 按拥有物决定循环）",
                ids.contains("maid_native:bow_shot"), ids.toString());

        PossessedItem ev = r.evidenceOf("maid_native:bow_shot");
        System.out.println("   弓射击的依据物品：" + (ev == null ? "(无)" : ev.shortName()));
        expect("★★ 解析结果带出依据物品（③ 换手前置的输入）", ev != null, "evidence=null");
        expect("★ 依据物品的槽位就是背包（不在手上 ⇒ 需要换手）",
                ev != null && ev.slot() == SlotKind.INVENTORY, String.valueOf(ev));

        // 不需要物品的动作不该有"依据物品"（否则执行器会去乱换手）
        expect("★ 不需要物品的动作（撤退&脱战）没有依据物品 ⇒ 不触发换手",
                r.evidenceOf("fight_like_player:disengage") == null,
                String.valueOf(r.evidenceOf("fight_like_player:disengage")));
        // 手上已有的动作，其依据物品应当是主手那件
        PossessedItem meleeEv = r.evidenceOf("maid_native:melee_swing");
        expect("★ 已在手上的动作，依据物品是主手那把剑",
                meleeEv != null && meleeEv.slot() == SlotKind.MAINHAND, String.valueOf(meleeEv));
    }

    // ═══════════ 7. 死区有默认动作 ═══════════
    private static void testDefaultActionExistsInDeathZone() {
        section("7. ★★ 死区（弹簧在原点）时要能做「默认的事」—— 旧实现这里什么都不做（「不注入向量就不动」）");

        List<String> sunk = new ArrayList<>();
        DecisionCycle<ContextFacts> cyc = new DecisionCycle<ContextFacts>(CFG,
                ctx -> NeedVector.zeros(),                       // 恒定零偏置 ⇒ 必是死区
                ctx -> resolver.resolve(List.of(sword()), ctx, CFG).candidates(),
                (a, ctx) -> sunk.add(a.id()),
                () -> 0L, new Random(1))
                .withDefaultActions(ctx -> {
                    if (!ctx.hasTarget() || !ctx.targetInMeleeRange()) {
                        return List.of();
                    }
                    for (CandidateAction c : resolver.resolve(List.of(sword()), ctx, CFG).candidates()) {
                        if (c.id().equals("maid_native:melee_swing")) {
                            return List.of(c);
                        }
                    }
                    return List.of();
                });

        cyc.tick(combat(1.0, 1.0, 1, 2.0), 0);      // 贴身 ⇒ 应当走默认动作
        System.out.println("   与敌人贴身（2 格）且无任何需求 ⇒ 选中：" + sunk);
        expect("★★ 死区 + 贴身 ⇒ 默认挥击（不是「什么都不做」）",
                sunk.contains("maid_native:melee_swing"), sunk.toString());

        // 反转对照：远在 20 格 ⇒ 动作层不该出手（那是步法的活）
        List<String> sunk2 = new ArrayList<>();
        DecisionCycle<ContextFacts> cyc2 = new DecisionCycle<ContextFacts>(CFG,
                ctx -> NeedVector.zeros(),
                ctx -> resolver.resolve(List.of(sword()), ctx, CFG).candidates(),
                (a, ctx) -> sunk2.add(a.id()),
                () -> 0L, new Random(1));
        cyc2.tick(combat(1.0, 1.0, 1, 20.0), 0);
        System.out.println("   远在 20 格且无需求（无默认动作）⇒ 动作层选中："
                + (sunk2.isEmpty() ? "(无，交给步法)" : sunk2));
        expect("★ 远距离时动作层不乱出手（交给步法接近）", sunk2.isEmpty(), sunk2.toString());
    }

    // ═══════════ 8. 忠实回放游戏循环 ═══════════

    private static void testReplayProducesSaneRhythm() {
        section("8. ★★ 忠实回放（与游戏同序：冷却账本 → 决策 → 回报完成）——「到处跑/疯狂执行」的可回放判据");

        GameLoop loop = new GameLoop(List.of(sword()));
        for (int i = 0; i < 200; i++) {
            loop.step();
        }
        System.out.println("   200 tick 内执行 " + loop.executed.size() + " 次，动作种类 "
                + loop.executed.stream().distinct().count() + " 种");
        System.out.println("   前 12 次：" + loop.executed.subList(0, Math.min(12, loop.executed.size())));
        System.out.println("   分布：" + loop.histogram());

        expect("★★ 不会出现「位移类」动作（走位已移出动作层）",
                loop.executed.stream().noneMatch(s -> s.equals("fight_like_player:charge")
                        || s.equals("fight_like_player:retreat")), loop.histogram());
        expect("★ 执行次数有节奏（≤ 40 次 / 200 tick ⇒ 平均间隔 ≥ 5 tick）",
                loop.executed.size() <= 40, "执行了 " + loop.executed.size() + " 次");
        expect("★★ 恒定战况下收敛（动作种类 ≤ 3）",
                loop.executed.stream().distinct().count() <= 3, loop.histogram());

        // ★ 弓手：应当出现"射击"，而不是"贴身站桩"
        GameLoop archer = new GameLoop(List.of(bow()));
        archer.distance = 6.0;
        for (int i = 0; i < 200; i++) {
            archer.step();
        }
        System.out.println("   弓手 200 tick：" + archer.histogram());
        expect("★★ 持弓时会真的射击（而不是什么都不做）",
                archer.executed.stream().anyMatch("maid_native:bow_shot"::equals), archer.histogram());
        expect("★ 射击有节奏（不是每 tick 一次）", archer.executed.size() <= 15,
                "射了 " + archer.executed.size() + " 次");
    }

    // ═══════════ 9. 执行器账本与清单一致 ═══════════

    private static void testExecutorLedgerMatchesCatalog() {
        section("9. ★ 执行器账本（executors.json）与清单一致：implemented 的动作必须真的存在");

        Path p = Path.of("catalog", "executors.json");
        if (!Files.exists(p)) {
            expect("executors.json 存在", false, p.toString());
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception ex) {
            expect("executors.json 语法正确", false, ex.getMessage());
            return;
        }

        Set<String> known = new HashSet<>();
        for (ActionSpec a : actions) {
            known.add(a.id());
        }

        int implemented = 0;
        List<String> danglingIds = new ArrayList<>();
        for (var e : root.getAsJsonArray("executors")) {
            JsonObject ex = e.getAsJsonObject();
            if (!"implemented".equals(ex.get("status").getAsString())) {
                continue;
            }
            for (var h : ex.getAsJsonArray("handles")) {
                implemented++;
                String id = h.getAsString();
                if (!known.contains(id)) {
                    danglingIds.add(id);
                }
            }
        }
        System.out.println("   已实现执行器的动作条目 " + implemented + " 条；悬空 " + danglingIds.size() + " 条");
        expect("★ implemented 的动作都在清单里（无悬空引用）", danglingIds.isEmpty(), danglingIds.toString());
        expect("★ 已实现的动作数 > 0", implemented > 0, String.valueOf(implemented));
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /**
     * ★★ <b>忠实复刻游戏侧的一 tick</b>（{@code PlayerLikeCombat#tick} 的编排）：
     * 冷却账本 → 事实 → 决策 → 执行 → 回报完成。
     *
     * <p>纪律：<b>只喂游戏侧真的会给的输入</b>。这里连冷却账本都用与游戏同一个
     * {@link CooldownTracker} —— 否则测试会看到"同一动作被连续执行 200 次"，
     * 而游戏里其实被冷却挡住了。
     */
    private static final class GameLoop {
        final List<PossessedItem> held;
        final List<String> executed = new ArrayList<>();
        final CooldownTracker cooldowns = new CooldownTracker();
        final DecisionCycle<ContextFacts> cycle;
        long tick = 0;
        double selfHp = 1.0;
        double ownerHp = 1.0;
        int enemies = 1;
        double distance = 3.0;

        GameLoop(List<PossessedItem> held) {
            this(held, 1);
        }

        GameLoop(List<PossessedItem> held, long seed) {
            this.held = held;
            this.cycle = new DecisionCycle<ContextFacts>(CFG,
                    ContextBias::of,
                    ctx -> resolver.resolve(held, ctx, CFG).candidates(),
                    (a, ctx) -> {
                        executed.add(a.id());
                        cooldowns.markUsed(a.id(), tick);
                        return true;         // ★ 执行器接口现在要求回报"有没有真的做出来"
                    },
                    () -> 0L, new Random(seed))
                    .withDefaultActions(this::defaultAction)
                    // ★★ 2026-10-03：夹具也要接"连段关系"，否则回放里奖励/惩罚根本不生效
                    //   （接线的判据：**测试跑的是不是游戏里那套**）
                    .withComboPreds(id -> {
                        ActionSpec s = resolver.specOf(id);
                        return s == null ? java.util.List.<String>of() : s.comboPreds();
                    });
        }

        private List<CandidateAction> defaultAction(ContextFacts ctx) {
            if (!ctx.hasTarget() || !ctx.targetInMeleeRange()) {
                return List.of();
            }
            for (CandidateAction c : resolver.resolve(held, ctx, CFG).candidates()) {
                if (c.id().equals("maid_native:melee_swing")) {
                    return List.of(c);
                }
            }
            return List.of();
        }

        ContextFacts facts() {
            Set<String> all = new LinkedHashSet<>();
            for (ActionSpec a : actions) {
                all.add(a.id());
            }
            // ★★ 与游戏侧共用【同一份】冷却规则（CooldownTracker#effectiveCooldownTicks）——
            //    否则 cooldown.scale 这类旋钮在自测里不生效，会得出"缩短冷却没用"的错误结论。
            Set<String> cooling = cooldowns.coolingDown(all, tick,
                    id -> CooldownTracker.effectiveCooldownTicks(
                            resolver.cooldownTicksOf(id), 0, TuningBus.global()));
            return new ContextFacts(true, distance <= 3.0, 0, true, false, false,
                    Map.of(), Map.of(), Map.of(), cooling,
                    Set.of("touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
                            "fight_like_player"),
                    ContextFacts.Vitals.of(selfHp, ownerHp, enemies, distance));
        }

        void step() {
            tick++;
            cycle.tick(facts(), tick);
            cycle.notifyCompleted();     // 最激进的重选节奏：执行器每次都立刻报告完成
        }

        String histogram() {
            Map<String, Integer> h = new java.util.TreeMap<>();
            for (String s : executed) {
                h.merge(s, 1, Integer::sum);
            }
            return h.toString();
        }
    }

    private static DecisionCycle<ContextFacts> cycle(List<String> sink, List<PossessedItem> held) {
        return new DecisionCycle<>(CFG,
                ContextBias::of,
                ctx -> resolver.resolve(held, ctx, CFG).candidates(),
                (a, ctx) -> sink.add(a.id()),
                () -> 0L, new Random(1));
    }

    /** ★ 一把"真的能被载体规则命中"的剑：必须带 ATTACK_DAMAGE 能力（= 游戏侧的 attr: 投喂）。 */
    private static PossessedItem sword() {
        return PossessedItem.withCapabilities(SlotKind.MAINHAND, "minecraft:diamond_sword",
                Set.of("SwordItem", "Item"), Set.of("attr:minecraft:generic.attack_damage"));
    }

    /** ★ 一把弓（maid:bow 按类型名匹配，不需要属性）。 */
    private static PossessedItem bow() {
        return PossessedItem.of(SlotKind.MAINHAND, "minecraft:bow", Set.of("BowItem", "Item"));
    }

    private static ContextFacts combat(double selfHp, double ownerHp, int enemies, double dist) {
        return combatWith(selfHp, ownerHp, enemies, dist, Set.of());
    }

    private static ContextFacts combatWith(double selfHp, double ownerHp, int enemies, double dist,
                                           Set<String> cooling) {
        // ★ 2026-10-03：基础夹具也喂拔刀剑的解析期事实（运行时由 customFacts 喂）
        return combatWithFacts(selfHp, ownerHp, enemies, dist, cooling,
                Map.of("slashblade:has_slash_art", true,
                        "slashblade:can_summon_sword", true));
    }

    /**
     * ★★ 带【自定义事实】的情境 —— 与游戏侧 {@code PlayerLikeCombat#customFacts} 同构。
     *
     * <p>为什么必须能喂：{@code spellConditionsMet} 是 <b>fail-closed</b> 的
     * ⇒ 不喂 {@code goety:can_cast} / {@code irons:can_cast} 时，
     * 施法动作会被 {@code PRECONDITION_CUSTOM_UNRESOLVED} 丢掉
     * （这正是"游戏侧忘了投喂 ⇒ 动作永远拿不到"那类缺陷的离线复现方式）。
     */
    private static ContextFacts combatWithFacts(double selfHp, double ownerHp, int enemies,
                                                double dist, Set<String> cooling,
                                                Map<String, Boolean> customFacts) {
        return new ContextFacts(true, dist <= 3.0, 0, true, false, false,
                Map.of(), customFacts, Map.of(), cooling,
                Set.of("touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
                        "fight_like_player"),
                ContextFacts.Vitals.of(selfHp, ownerHp, enemies, dist));
    }

    private static List<String> ids(CarrierResolver.Resolution r) {
        List<String> out = new ArrayList<>();
        r.candidates().forEach(c -> out.add(c.id()));
        return out;
    }

    private static String pad(String s) {
        return s.length() >= 34 ? s : s + " ".repeat(34 - s.length());
    }

    private static void section(String t) {
        System.out.println("-- " + t);
    }

    // ═══════════ 15. goety:cast_focus 必须要有承诺时长 ═══════════

    /**
     * ★★ 引导类法术<b>必须有非零承诺</b>，否则决策层"不进入承诺"
     * ⇒ 下一周期就另选动作，一边引导一边干别的。
     *
     * <p>而承诺又不能照抄 {@code castDuration} —— 蓄力类的那个值是 <b>72000 哨兵</b>
     * （见 {@code GoetyChannel.Mode} 与 {@code catalog/data/goety.json} 的注释）。
     * ⇒ 这里的断言是"非零且<b>远小于</b>哨兵值"。
     */
    private static void testGoetyCastFocusHasCommitment() {
        section("15. ★★ goety:cast_focus 的承诺：非零（引导需要 in-flight）且远小于 72000 哨兵");

        ActionSpec a = resolver.specOf("goety:cast_focus");
        if (a == null) {
            expect("动作存在：goety:cast_focus", false, "清单里没有这条");
            return;
        }
        System.out.println("   goety:cast_focus 承诺=" + a.commitmentTicks() + " tick");
        expect("★★ 承诺 > 0（0 ⇒ 引导法术起手后不进承诺，决策层会另开动作）",
                a.commitmentTicks() > 0, "承诺=" + a.commitmentTicks());
        expect("★★ 承诺 ≪ 72000（72000 是 IChargingSpell 的硬上限，不是时长）",
                a.commitmentTicks() < 1000, "承诺=" + a.commitmentTicks());
        expect("★ 且它的最小重复间隔 > 0（瞬时法术靠它兜节奏）",
                a.effectiveCooldownTicks() > 0, "cool=" + a.effectiveCooldownTicks());
    }

    // ═══════════ 16. 卡死看门狗（委托方第 3 条）═══════════

    /**
     * ★★ 看门狗的判据与后果 —— 委托方 2026-10-01 第 3 条：
     * 「为了防止女仆被某个动作卡住，可以引入一些检测机制，在坏的情况下跳过该步骤强行进入下一个循环」。
     *
     * <p>★ 为什么这些断言值得写：这一层是<b>唯一</b>能兜住"回报成功但什么都没发生"的机制
     * （实测 {@code FlameStrikeSpell} 就是这种：前三道保险丝全不响）。
     * 如果它的判据写错（比如把脱战也算卡住），女仆会在和平时期被反复"停放"动作。
     */
    private static void testStallWatchdogPolicy() {
        section("16. ★★ 卡死看门狗：进展指纹 / 判定 / 停放（纯逻辑层 StallDetector）");

        // ── 指纹 ──
        String base = StallDetector.progressKey(7, 20, 3, 20, 12345L);
        expect("★ 指纹稳定（同输入同输出）",
                base.equals(StallDetector.progressKey(7, 20, 3, 20, 12345L)), base);
        expect("★★ 目标掉血 ⇒ 指纹变（= 有进展，不该判卡住）",
                !base.equals(StallDetector.progressKey(7, 18, 3, 20, 12345L)), base);
        expect("★★ 距离变化 ⇒ 指纹变（接近/拉开都算进展）",
                !base.equals(StallDetector.progressKey(7, 20, 4, 20, 12345L)), base);
        expect("★★ 自己掉血 ⇒ 指纹变（挨打也是进展）",
                !base.equals(StallDetector.progressKey(7, 20, 3, 16, 12345L)), base);
        expect("★★ 位置变化 ⇒ 指纹变（她在动）",
                !base.equals(StallDetector.progressKey(7, 20, 3, 20, 99999L)), base);
        expect("★★ 换目标 ⇒ 指纹变",
                !base.equals(StallDetector.progressKey(8, 20, 3, 20, 12345L)), base);

        // ── 判定 ──
        expect("★ 未到阈值 ⇒ 不判卡住",
                !StallDetector.isStalled(0, StallDetector.STALL_TICKS - 1, true), "提前判了");
        expect("★ 到阈值且战斗中 ⇒ 判卡住",
                StallDetector.isStalled(0, StallDetector.STALL_TICKS, true), "没判");
        expect("★★ 脱战时【不判】卡住（站着不动是正常的）",
                !StallDetector.isStalled(0, 100_000, false), "脱战也判了 ⇒ 和平时期会被反复停放");
        expect("★ 阈值不短于 goety 引导的硬上限（120 tick）—— 否则正常长引导被误判",
                StallDetector.STALL_TICKS >= 120, String.valueOf(StallDetector.STALL_TICKS));

        // ── 停放 ──
        expect("★ 停放时长按命中次数递增（第 2 次比第 1 次久）",
                StallDetector.parkTicksFor(2) > StallDetector.parkTicksFor(1),
                StallDetector.parkTicksFor(2) + " vs " + StallDetector.parkTicksFor(1));
        expect("★★ 停放有上限（是「临时让位」，不是「永久禁用」）",
                StallDetector.parkTicksFor(9999) == StallDetector.PARK_TICKS_MAX,
                String.valueOf(StallDetector.parkTicksFor(9999)));
        expect("★ 停放期一过就解禁",
                StallDetector.isParked(101L, 100L) && !StallDetector.isParked(100L, 100L),
                "边界判断错");

        // ── 与冷却通路的合流（★ 复用既有机制的关键）──
        Map<String, Long> parked = StallDetector.newParkedTable();
        parked.put("goety:cast_focus", 50L);
        parked.put("irons:cast_spell", 200L);
        StallDetector.prune(parked, 100L);
        expect("★ 过期的停放项被清掉（表不会无限增长）",
                !parked.containsKey("goety:cast_focus") && parked.containsKey("irons:cast_spell"),
                parked.toString());
        Set<String> cool = new HashSet<>();
        StallDetector.mergeInto(parked, 100L, cool);
        expect("★★ 停放并进「冷却中」集合 ⇒ 解析期按 ON_COOLDOWN 丢掉它（/flp why 看得见）",
                cool.contains("irons:cast_spell") && !cool.contains("goety:cast_focus"),
                cool.toString());
    }

    // ═══════════ 17. 循环隔离不变量（修掉一个恒假的诊断）═══════════

    /**
     * ★ 修：旧 {@code LoopSplit#isIsolated} 是"任一集合内部出现重复槽位就 false"
     * ⇒ 主循环天然有 27 格 {@code INVENTORY} ⇒ 在游戏里<b>恒为 false</b>，
     * {@code describe()} 于是永远打印"（!! 槽位重叠）"。
     * <p>一个恒假的诊断比没有诊断更坏：它会让人以为发现了真 bug。
     * 正确的不变量是<b>两个集合的槽位不相交</b>。
     */
    private static void testLoopSplitIsolationInvariant() {
        section("17. ★ 循环隔离不变量：多格背包 + 饰品槽下仍须成立（旧实现恒为假）");

        List<PossessedItem> items = List.of(
                PossessedItem.of(SlotKind.INVENTORY, "minecraft:bow", Set.of("BowItem")),
                PossessedItem.of(SlotKind.INVENTORY, "minecraft:bread", Set.of("Item")),
                PossessedItem.of(SlotKind.INVENTORY, "minecraft:iron_sword", Set.of("SwordItem")),
                PossessedItem.of(SlotKind.OFFHAND, "minecraft:shield", Set.of("ShieldItem")),
                PossessedItem.of(SlotKind.CURIOS, "goety:focus_bag", Set.of("Item")));
        LoopSplit split = LoopSplit.of(items);
        expect("★★ 三格背包 + 饰品 ⇒ 隔离仍成立（旧实现这里恒为 false）",
                split.isIsolated(), split.describe());
        expect("★ 主循环含背包（3 件）与饰品（1 件）",
                split.mainItems().size() == 4, String.valueOf(split.mainItems().size()));
        expect("★ 副手循环只有盾",
                split.offhandItems().size() == 1
                        && split.offhandItems().get(0).slot() == SlotKind.OFFHAND,
                split.describe());
    }

    // ═══════════ 18. goety 引导长度策略（第十轮：委托方实测驱动）═══════════

    /**
     * ★★ 引导长度的纯逻辑部分 —— 委托方第十轮实测的三条现象都落在这些数字上：
     * <ol>
     *   <li>「蓄力时间长的聚晶（熔岩炸弹）会释放到一半被自己打断重新放」
     *       ⇒ ① 看门狗不该在**在途动作**期间判卡死（见 {@code PlayerLikeCombat#tickWatchdog}）；
     *          ② 定长引导类的长度必须 ≥ 法术自己的 {@code castDuration}
     *          （熔岩炸弹 = {@code LavaballDuration} 默认 **40**）；</li>
     *   <li>「腐化/水蛭可以释放但白烟很多」
     *       ⇒ 白烟来自 {@code startUsingItem} 触发的 {@code finishUsingItem → failParticles}，
     *          已删除该调用（无法在这里断言，但长度策略要保证"够长"，
     *          否则光束类会在 20 tick 内被收）；</li>
     *   <li>「怪死了也不会停止」⇒ 目标一死就收（长度只是上限）。</li>
     * </ol>
     *
     * <p>★ 这里能离线断言的是<b>长度算法本身</b>：它必须是**单调、有界、且与法术自身时长相容**的。
     */
    private static void testGoetyChannelShapePolicy() {
        section("18. ★★ goety 引导长度策略：蓄力类 = castUp + 持续预算；有界；不短于法术自身时长");

        int max = com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy.MAX_SANE_CHANNEL;
        expect("★ 可信上限是 200 tick（超过就不信 castDuration —— 72000 是哨兵不是时长）",
                max == 200, String.valueOf(max));

        int h0 = com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy.chargingHold(0);
        int h20 = com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy.chargingHold(20);
        int h60 = com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy.chargingHold(60);
        int h999 = com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy.chargingHold(9999);
        System.out.println("   蓄力类引导长度：castUp=0 → " + h0 + " ｜ 20 → " + h20
                + " ｜ 60 → " + h60 + " ｜ 9999 → " + h999);
        expect("★★ 持续预算旋钮 = 0 时退回默认（不能让配置把引导变成 0 tick）",
                com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy
                        .chargingHold(0, 0) == h0, "旋钮 0 生效了");

        expect("★★ 前摇越大，引导越长（单调）", h20 > h0 && h60 > h20, h0 + "/" + h20 + "/" + h60);
        expect("★★ 有上限（再离谱的 castUp 也封在 200 tick 内，不会锁死）",
                h999 <= max, String.valueOf(h999));
        expect("★★ 长度 ≥ 20 tick（否则「短到看不见」—— 委托方实测的"
                        + "「腐化/水蛭刚放出来就收」就是这个下界太小造成的）",
                h0 >= 20, String.valueOf(h0));
        expect("★ 前摇 0 的持续类（腐化/水蛭的源码默认 castUp=0）能持续 1 秒以上",
                h0 >= 20, String.valueOf(h0));

        // ★ 定长引导类：长度就是法术自己的 castDuration，必须**不短于**它
        //   （熔岩炸弹 LavaballDuration 默认 40 —— 短了就会"放一半被打断"）
        expect("★★ 熔岩炸弹那类（castDuration=40）落在可信区间内 ⇒ 会按 40 tick 完整引导",
                40 > 0 && 40 <= max, "40 vs max=" + max);
        expect("★ 火焰升腾（FlameStrikeDuration 默认 60）同理",
                60 <= max, "60 vs max=" + max);
        expect("★ recall（160）也仍在可信区间内（最后的合法大值）",
                160 <= max, "160 vs max=" + max);
        expect("★★ 而 72000（IChargingSpell 的哨兵）必须被判定为超限",
                72000 > max, "72000 竟然没超限 ⇒ 会被当成长度，女仆会被锁死");
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

    // ───────────── ★★ 连段奖励 / 不复读（第十六轮，委托方澄清）─────────────

    /**
     * ★★ 委托方原话：「我指的连段是**连段类技能**（比如打一套斩击 > 打一次斩击）而非继续之前的行为，
     * 你如果指的是复读的话我们不要做，**我们不鼓励复读，你可以反向做成鼓励多样化**。」
     *
     * <p>⇒ 这组断言同时钉住**三条**：① 声明后继被奖励；② **同族非后继被惩罚（这就是不复读）**；
     * ③ 她在挨打时（防御需求顶格）规则**整体失效**（命比连段重要）；
     * 外加**负面对照**：不相干的动作之间**一点都不动**（不许把所有动作一起抬高/压低）。
     */
    private static void testComboChain() {
        section("\u2605\u2605 \u8fde\u6bb5\u5956\u52b1\u4e0e\u4e0d\u590d\u8bfb\uff08\u58f0\u660e\u540e\u7ee7\u5956\u52b1 + \u540c\u65cf\u975e\u540e\u7ee7\u60e9\u7f5a\uff09");

        // ① 清单里的 comboDeps 真的读进来了（此前**没人读**）
        var finish = resolver.specOf("slashblade:combo_b_finish");
        expect("\u2605\u2605 \u6e05\u5355\u91cc\u7684 comboDeps \u771f\u7684\u8bfb\u8fdb\u6765\u4e86",
                finish != null && finish.comboPreds().contains("slashblade:combo_b"),
                finish == null ? "spec=null" : finish.comboPreds().toString());

        double bonus = 0.12;
        double penalty = 0.12;
        var preds = java.util.List.of("slashblade:combo_b");

        // ② 声明后继 ⇒ 负（更优）
        expect("\u2605\u2605 \u4e0a\u4e00\u62cd\u662f\u524d\u9a71 \u21d2 \u540e\u7ee7\u88ab\u5956\u52b1\uff08\u8ddd\u79bb\u53d8\u5c0f\uff09",
                ActionSelector.chainAdjustOf("slashblade:combo_b",
                        "slashblade:combo_b_finish", preds, 0.0, bonus, penalty) < 0, "");

        // ③ ★★ 同族但不是声明后继 ⇒ 正（更差）—— "不复读 / 鼓励多样化"
        expect("\u2605\u2605 \u540c\u65cf\u4f46\u4e0d\u662f\u58f0\u660e\u540e\u7ee7 \u21d2 \u53d8\u5dee\uff08\u4e0d\u590d\u8bfb\uff09",
                ActionSelector.chainAdjustOf("slashblade:combo_b",
                        "slashblade:combo_c", java.util.List.of(), 0.0, bonus, penalty) > 0, "");
        expect("\u2605\u2605 \u4e24\u8005\u540c\u65f6\u6210\u7acb\uff1a\u5956\u52b1 > 0 > \u60e9\u7f5a\uff08\u65b9\u5411\u76f8\u53cd\uff09",
                ActionSelector.chainAdjustOf("slashblade:combo_b", "slashblade:combo_b_finish",
                        preds, 0.0, bonus, penalty) < 0
                        && ActionSelector.chainAdjustOf("slashblade:combo_b", "slashblade:combo_c",
                        java.util.List.of(), 0.0, bonus, penalty) > 0, "");

        // ④ ★ 挨打时整体失效（命比连段重要）
        expect("\u2605\u2605 \u5979\u771f\u7684\u5728\u6328\u6253\uff08\u9632\u5fa1\u9700\u6c42 \u2265 1.0\uff09\u21d2 \u89c4\u5219\u6574\u4f53\u5931\u6548",
                ActionSelector.chainAdjustOf("slashblade:combo_b", "slashblade:combo_b_finish",
                        preds, 1.0, bonus, penalty) == 0.0
                        && ActionSelector.chainAdjustOf("slashblade:combo_b", "slashblade:combo_c",
                        java.util.List.of(), 2.0, bonus, penalty) == 0.0, "");

        // ⑤ 负面对照：不相干的动作**一点都不动**
        expect("\u2605 \u4e0d\u76f8\u5e72\u7684\u52a8\u4f5c\u4e4b\u95f4 \u21d2 \u4e0d\u52a8\uff08\u4e0d\u8bb8\u628a\u6240\u6709\u52a8\u4f5c\u4e00\u8d77\u62ac\u9ad8\uff09",
                ActionSelector.chainAdjustOf("slashblade:combo_b", "minecraft:melee_swing",
                        java.util.List.of(), 0.0, bonus, penalty) == 0.0, "");

        // ⑥ 没有上一拍 / 同一个 id ⇒ 不动（行为与从前逐字一致）
        expect("\u2605 \u6ca1\u6709\u4e0a\u4e00\u62cd\uff0c\u6216\u5c31\u662f\u540c\u4e00\u4e2a id \u21d2 \u4e0d\u52a8",
                ActionSelector.chainAdjustOf(null, "slashblade:combo_a", java.util.List.of(),
                        0.0, bonus, penalty) == 0.0
                        && ActionSelector.chainAdjustOf("slashblade:combo_a", "slashblade:combo_a",
                        java.util.List.of(), 0.0, bonus, penalty) == 0.0, "");

        // ⑦ ★ 奖励必须大于平局带 τ，否则会被"平局 + LRU"吃掉（取证实测：0.03 零效果）
        expect("\u2605\u2605 \u8fde\u6bb5\u5956\u52b1\u5fc5\u987b > \u5e73\u5c40\u5e26 \u03c4=0.05\uff08\u5426\u5219\u88ab\u5403\u6389\uff09",
                SpringConfig.defaults().chainBonus() > SpringConfig.defaults().tieWindow(),
                "bonus=" + SpringConfig.defaults().chainBonus() + " tau=" + SpringConfig.defaults().tieWindow());
    }


    // ───────────── ★★ 回放级：连段对数 / 复读率（委托方要求"锁住"）─────────────

    /**
     * ★★ 这组断言把"**打一套 &gt; 打一次**"与"**不鼓励复读**"钉在**回放级统计**上，
     * 而不只是规则层面（规则层在 §连段奖励 那组里）。
     *
     * <p>做法：同一段 200 拍回放跑三遍 ——
     * <ul>
     *   <li>对照组：{@code combo.bonus = 0}（完全关掉）；</li>
     *   <li>默认：{@code combo.bonus = 0.12}（&gt; 平局带 τ=0.05）；</li>
     *   <li>无效对照：{@code combo.bonus = 0.03}（≤ τ ⇒ **必须与对照组一样**）。</li>
     * </ul>
     * 统计两件事：**合法连段对数**（相邻两拍构成"声明前驱→后继"）与
     * **复读率**（相邻两拍同族但不是声明后继）。
     * ★ 用**相对断言**而不是写死基线：这样调参不会让断言说谎，而且"奖励大于平局带才有用"
     *   这条机制被第三条断言直接钉住。
     */
    private static void testComboAndDiversityStats() {
        section("\u2605\u2605 \u56de\u653e\u7ea7\uff1a\u8fde\u6bb5\u5bf9\u6570 \u2265 \u5bf9\u7167\u7ec4\uff0c\u4e14\u590d\u8bfb\u7387 \u2264 \u5bf9\u7167\u7ec4");

        double[] off = comboStats(0.0);
        double[] on = comboStats(0.12);
        double[] tiny = comboStats(0.03);
        System.out.printf("   连段对数：关闭=%.0f  默认0.12=%.0f  无效0.03=%.0f%n", off[0], on[0], tiny[0]);
        System.out.printf("   复读率：关闭=%.3f  默认0.12=%.3f  无效0.03=%.3f%n", off[1], on[1], tiny[1]);

        expect("\u2605\u2605 \u5f00\u4e86\u8fde\u6bb5\u5956\u52b1\u540e\uff0c\u5408\u6cd5\u8fde\u6bb5\u5bf9\u6570\u4e0d\u5c11\u4e8e\u5bf9\u7167\u7ec4",
                on[0] >= off[0], on[0] + " vs " + off[0]);
        expect("\u2605\u2605 \u5f00\u4e86\u5956\u52b1\u540e\uff0c\u590d\u8bfb\u7387\u4e0d\u9ad8\u4e8e\u5bf9\u7167\u7ec4\uff08\u4e0d\u9f13\u52b1\u590d\u8bfb\uff09",
                on[1] <= off[1] + 1e-9, on[1] + " vs " + off[1]);
        // ★★ 这里曾经有一条"奖励越小效果越弱"的断言 —— **删掉了**：它是从**一次**运行里读出来的
        //   假规律（换上一条候选就翻车：0.03 的连段数 17 > 0.12 的 8）。
        //   ⇒ 单次回放是**混沌**的（一拍之差会发散），比较必须在**多种子平均**上做。
        //   下面两条是我真正能担保的：**任何 &gt;0 的奖励，平均连段数都 ≥ 对照组**。
        expect("\u2605\u2605 \u4efb\u4f55 >0 \u7684\u5956\u52b1\uff08\u542b 0.03\uff09\u5e73\u5747\u8fde\u6bb5\u6570\u90fd \u2265 \u5bf9\u7167\u7ec4",
                tiny[0] >= off[0], "tiny=" + tiny[0] + " off=" + off[0]);
        expect("\u2605 \u4e14\u5e73\u5747\u590d\u8bfb\u7387\u4e5f\u4e0d\u9ad8\u4e8e\u5bf9\u7167\u7ec4",
                tiny[1] <= off[1] + 1e-9, "tiny=" + tiny[1] + " off=" + off[1]);
        expect("\u2605 \u5bf9\u7167\u7ec4\u672c\u8eab\u6709\u52a8\u4f5c\uff08\u56de\u653e\u4e0d\u662f\u7a7a\u8dd1\uff09",
                off[2] > 10, "executed=" + off[2]);
        // ★★ 不许空转：若三种配置下"声明连段对"全是 0，这条统计断言**什么都没测**
        //   （第一版就是这样：她拿的是普通剑，而声明的连段关系全是拔刀剑动作 ⇒ 恒为 0）
        expect("\u2605\u2605 \u56de\u653e\u91cc\u771f\u7684\u51fa\u73b0\u8fc7\u58f0\u660e\u8fde\u6bb5\uff08\u5426\u5219\u8fd9\u7ec4\u7edf\u8ba1\u65ad\u8a00\u662f\u7a7a\u8f6c\uff09",
                on[0] > 0 || off[0] > 0 || tiny[0] > 0,
                "off=" + off[0] + " on=" + on[0] + " tiny=" + tiny[0]);
    }

    /** 统计用的刀（与 §6d 里那把同口径）。 */
    private static PossessedItem bladeForStats() {
        return PossessedItem.withCapabilities(SlotKind.MAINHAND, "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"),
                Set.of("attr:minecraft:generic.attack_damage"));
    }

    /**
     * 跑一段固定回放，返回 {@code [合法连段对数, 复读率, 执行总数]}。
     *
     * @param comboBonus {@code combo.bonus} 的取值（0 = 关闭）
     */
    /** ★ 统计用的随机种子们（★ 单次回放是混沌的 ⇒ 必须**跨种子平均**才敢下结论）。 */
    private static final long[] STATS_SEEDS = {1, 2, 3, 4, 5};

    /**
     * ★★ 跨种子平均后的统计（{@code [平均连段对数, 平均复读率, 平均执行数]}）。
     */
    private static double[] comboStats(double comboBonus) {
        double[] sum = new double[3];
        for (long seed : STATS_SEEDS) {
            double[] one = comboStatsOnce(comboBonus, seed);
            sum[0] += one[0];
            sum[1] += one[1];
            sum[2] += one[2];
        }
        return new double[]{sum[0] / STATS_SEEDS.length, sum[1] / STATS_SEEDS.length,
                sum[2] / STATS_SEEDS.length};
    }

    private static double[] comboStatsOnce(double comboBonus, long seed) {
        TuningBus bus = TuningBus.global();
        bus.resetAll();
        bus.set("combo.bonus", comboBonus);
        bus.set("combo.repeatPenalty", comboBonus);
        try {
            // ★ 手里**拿刀** —— 清单里声明的连段关系全是拔刀剑动作，拿普通剑时永远统计不到
            GameLoop loop = new GameLoop(List.of(bladeForStats()), seed);
            for (int i = 0; i < 400; i++) {
                loop.step();
            }
            List<String> seq = loop.executed;
            double pairs = 0;
            double repeats = 0;
            for (int i = 1; i < seq.size(); i++) {
                String prev = seq.get(i - 1);
                String cur = seq.get(i);
                if (prev.equals(cur)) {
                    continue;                        // 同一个 id 连着两次：既不算连段也不算复读
                }
                ActionSpec spec = resolver.specOf(cur);
                boolean declared = spec != null && spec.comboPreds().contains(prev);
                if (declared) {
                    pairs++;
                } else if (ActionSelector.sameFamily(prev, cur)) {
                    repeats++;
                }
            }
            double denom = Math.max(1, seq.size() - 1);
            return new double[]{pairs, repeats / denom, seq.size()};
        } finally {
            bus.resetAll();
        }
    }

}
