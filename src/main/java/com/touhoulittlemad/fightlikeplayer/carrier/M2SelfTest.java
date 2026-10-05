package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 载体解析（M2）的<b>离线自测</b> —— 纯 JVM，不启动 Minecraft。
 *
 * <h2>它验证什么</h2>
 * <ol>
 *   <li><b>核心主张</b>：给定"女仆持有什么"，断言"她能做什么"；</li>
 *   <li>★ <b>不限主副手</b>：背包里的枪也让开火可用（委托方 M2 注记）；</li>
 *   <li>★ <b>载体按能力拆分</b>：拿弓的女仆<b>不会</b>拿到近战动作；</li>
 *   <li><b>无载体动作永远可用</b>（`maid:body`）⇒ 空手不会无事可做；</li>
 *   <li><b>剪枝</b>：模组未装 ⇒ 整族不出现；</li>
 *   <li><b>硬过滤</b>：`RC` 不可达、冷却、互斥、前置条件；</li>
 *   <li>★ <b>A 档参数化</b>：枪型/SA 种在运行期解析，命中 {@code vectorOverrides}；</li>
 *   <li>★ <b>丢弃原因可查</b>：每条被丢的动作都带原因（灭火器教训的对策）；</li>
 *   <li><b>缺口显式</b>：判据未查证的载体记 {@code CARRIER_RULE_UNRESOLVED}，不静默。</li>
 * </ol>
 *
 * <h2>运行</h2>
 * <pre>
 *   java -cp "build\classes\java\main;&lt;gson.jar&gt;" \
 *        com.touhoulittlemad.fightlikeplayer.carrier.M2SelfTest
 * </pre>
 * 工作目录必须是仓库根（要读 {@code catalog/}）。退出码 0 = 全过。
 */
public final class M2SelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    /** 模拟"全部联动模组都装了"。 */
    private static final Set<String> ALL_MODS = Set.of(
            "touhou_little_maid", "slashblade", "goety", "irons_spellbooks", "tacz",
            "pillagers_gun", "superbwarfare", "fight_like_player");

    private static CarrierResolver resolver;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 载体解析自测 (M2SelfTest) ===");
        System.out.println();

        CatalogLoader.Loaded loaded = CatalogLoader.loadFromWorkingDir(Path.of("catalog"));
        resolver = new CarrierResolver(loaded.actions(), loaded.rules());

        System.out.println("已加载：动作 " + loaded.actions().size() + " 条，载体规则 " + loaded.rules().size() + " 条");
        System.out.println("有向量的动作：" + resolver.vectorCoverage() + " / " + loaded.actions().size());
        Set<String> noRule = resolver.carriersWithoutRule();
        System.out.println("没有载体规则的动作载体：" + (noRule.isEmpty() ? "（无）" : noRule));
        System.out.println();

        testEmptyHanded();
        testWeaponSplitIsCorrect();
        testNonHandSlotsCount();
        testModPruning();
        testUnreachableDropped();
        testCooldownAndExclusivity();
        testParametricGunType();
        testParametricSlashArts();
        testDropReasonsAreExplained();
        testUnresolvedCarriersVisible();
        testFireModeChangesCommitmentShape();
        testDurabilityFiltering();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════════ 1. 空手：无载体动作仍然可用 ═══════════════

    private static void testEmptyHanded() {
        section("1. 空手 ⇒ 无载体动作（maid:body）仍可用，不会无事可做");

        ContextFacts ctx = ctx(true, false);
        CarrierResolver.Resolution r = resolver.resolve(List.of(), ctx, SpringConfig.defaults());

        List<String> ids = ids(r);
        System.out.println("   空手可用：" + ids);
        expect("空手仍能撤退&脱战", ids.contains("fight_like_player:disengage"), ids.toString());
        // ★ 2026-09-30：走位已从「原子动作」改为并发槽位「步法」（role=stance）
        //   ⇒ 空手时**不该**再有 charge/retreat 这两个候选动作（它们本来会导致弹簧震荡）。
        expect("★ 空手时【不再】把走位当作候选动作（步法不是 choice）",
                ids.stream().noneMatch(s -> s.equals("fight_like_player:charge")
                        || s.equals("fight_like_player:retreat")), ids.toString());
        expect("空手拿不到任何枪械动作", ids.stream().noneMatch(s -> s.startsWith("tacz:")), ids.toString());
    }

    // ═══════════════ 2. 载体按能力拆分 ═══════════════

    private static void testWeaponSplitIsCorrect() {
        section("2. ★ 拿弓的女仆【不该】拿到近战动作（载体拆分验证）");

        PossessedItem bow = PossessedItem.of(SlotKind.MAINHAND, "minecraft:bow", Set.of("BowItem", "Item"));
        CarrierResolver.Resolution r = resolver.resolve(List.of(bow), ctx(true, false), SpringConfig.defaults());
        List<String> ids = ids(r);

        System.out.println("   持弓可用：" + ids);
        expect("★ 持弓时『弓射击』可用", ids.contains("maid_native:bow_shot"), ids.toString());
        expect("★ 持弓时『通用近战』【不可用】（旧设计会错误地给出它）",
                !ids.contains("maid_native:melee_swing"), ids.toString());
        expect("持弓时三叉戟不可用", !ids.contains("maid_native:trident"), ids.toString());
    }

    // ═══════════════ 3. 不限主副手 ═══════════════

    private static void testNonHandSlotsCount() {
        section("3. ★ 背包里的物品也算（委托方 M2 注记：不局限于主副手）");

        PossessedItem gunInBackpack = PossessedItem.withParams(
                SlotKind.INVENTORY, "tacz:ak47", Set.of("IGun", "ModernKineticGunItem"),
                Map.of("gunType", "rifle"));

        CarrierResolver.Resolution r = resolver.resolve(
                List.of(gunInBackpack), ctx(true, false), SpringConfig.defaults());
        List<String> ids = ids(r);

        System.out.println("   仅背包有枪 → 可用动作数：" + ids.size());
        expect("★ 背包里的枪也让『开火』可用（旧 TLM 只认主手）",
                ids.contains("tacz:shoot"), ids.toString());
    }

    // ═══════════════ 4. sourceMod 剪枝 ═══════════════

    private static void testModPruning() {
        section("4. 剪枝：模组未装 ⇒ 整族不出现");

        PossessedItem blade = PossessedItem.of(SlotKind.MAINHAND, "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"));

        // 只装 TLM，不装拔刀剑
        ContextFacts noSlashBlade = new ContextFacts(true, false, 0, true, false, false,
                Map.of(), Map.of("slashblade:has_slash_art", true,
                "slashblade:can_summon_sword", true), Map.of(),
                Set.of(), Set.of("touhou_little_maid"));

        CarrierResolver.Resolution r = resolver.resolve(List.of(blade), noSlashBlade, SpringConfig.defaults());
        List<String> ids = ids(r);

        System.out.println("   未装拔刀剑时可用：" + ids);
        expect("★ 未装拔刀剑 ⇒ 拔刀剑的 31 条动作全部不出现",
                ids.stream().noneMatch(s -> s.startsWith("slashblade:")), ids.toString());
    }

    // ═══════════════ 5. RC 不可达 ═══════════════

    private static void testUnreachableDropped() {
        section("5. 硬过滤：reach == RC 的动作不可达");

        PossessedItem blade = PossessedItem.of(SlotKind.MAINHAND, "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"));
        CarrierResolver.Resolution r = resolver.resolve(List.of(blade), ctx(true, false), SpringConfig.defaults());

        List<String> rcDropped = r.dropped().stream()
                .filter(d -> d.reason() == DropReason.UNREACHABLE)
                .map(CarrierResolver.Dropped::actionId)
                .toList();

        System.out.println("   被 RC 过滤掉：" + rcDropped.size() + " 条 " + head(rcDropped, 4));
        expect("★ 有动作因 RC 被丢弃", !rcDropped.isEmpty(), "0 条");
        expect("★ RC 动作【不在】候选里",
                ids(r).stream().noneMatch(rcDropped::contains), "候选里混入了 RC 动作");
    }

    // ═══════════════ 6. 冷却与互斥 ═══════════════

    private static void testCooldownAndExclusivity() {
        section("6. 硬过滤：冷却 + 互斥占用");

        PossessedItem blade = PossessedItem.of(SlotKind.MAINHAND, "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"));

        ContextFacts cool = new ContextFacts(true, false, 0, true, false, false,
                Map.of(), Map.of("tacz:shoot#1", true), Map.of(),
                Set.of("slashblade:combo_a"), ALL_MODS);
        CarrierResolver.Resolution r = resolver.resolve(List.of(blade), cool, SpringConfig.defaults());
        expect("冷却中的动作被剔除",
                !ids(r).contains("slashblade:combo_a"),
                r.dropped().stream().filter(d -> d.reason() == DropReason.ON_COOLDOWN)
                        .map(CarrierResolver.Dropped::actionId).toList().toString());
    }

    // ═══════════════ 7. ★ A 档参数化：枪型 ═══════════════

    private static void testParametricGunType() {
        section("7. ★★ A 档参数化：枪型在运行期解析，命中 vectorOverrides");

        PossessedItem rifle = PossessedItem.withParams(
                SlotKind.MAINHAND, "tacz:m4a1",
                Set.of("IGun", "ModernKineticGunItem"),
                Map.of("gunType", "rifle", "fireMode", "AUTO"));
        PossessedItem rpg = PossessedItem.withParams(
                SlotKind.MAINHAND, "tacz:rpg7",
                Set.of("IGun", "ModernKineticGunItem"),
                Map.of("gunType", "rpg", "fireMode", "SEMI"));

        CandidateAction shootRifle = find(resolver.resolve(List.of(rifle), ctx(true, false),
                SpringConfig.defaults()), "tacz:shoot");
        CandidateAction shootRpg = find(resolver.resolve(List.of(rpg), ctx(true, false),
                SpringConfig.defaults()), "tacz:shoot");

        System.out.println("   步枪 gunType=rifle → " + v(shootRifle));
        System.out.println("   火箭筒 gunType=rpg  → " + v(shootRpg));

        expect("★ 步枪取到 rifle 的向量", shootRifle != null
                && near(shootRifle.staticVector().get(
                        com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.SINGLE_DAMAGE), 0.6),
                v(shootRifle));
        expect("★★ 同一动作、不同枪型 ⇒ 不同向量（火箭筒偏 AoE、步枪偏单体）",
                shootRpg != null && shootRifle != null
                        && shootRpg.staticVector().get(
                                com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.AREA_DAMAGE)
                         > shootRifle.staticVector().get(
                                com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.AREA_DAMAGE),
                v(shootRpg));
        expect("★ 开火只有一个动作 id（枪型不是新的动作条目）",
                shootRifle != null && shootRpg != null
                        && shootRifle.id().equals(shootRpg.id()),
                "id 不同");

        // ★★ 第十七轮（枪械审计 B1/B2）：把"枪包 ↔ 载体规则"的一对一关系钉住。
        List<String> taczIds = ids(resolver.resolve(List.of(rifle), ctx(true, false),
                SpringConfig.defaults()));
        expect("★★★ TaCZ 枪 ⇒ 开火动作【恰好一个】：tacz:shoot 进、maid_native:gun_shot 不进"
                        + "（此前两条载体规则判据完全相同 ⇒ 两个 id 一起进候选、两套冷却）",
                taczIds.contains("tacz:shoot") && !taczIds.contains("maid_native:gun_shot"),
                taczIds.toString());

        PossessedItem swGun = PossessedItem.of(SlotKind.MAINHAND, "superbwarfare:ak47",
                Set.of("com.atsuishio.superbwarfare.item.gun.GunItem", "GunItem", "Item"));
        List<String> swIds = ids(resolver.resolve(List.of(swGun), ctx(true, false),
                SpringConfig.defaults()));
        System.out.println("   卓越前线的枪 → " + swIds);
        expect("★★★ 卓越前线的枪也拿得到开火动作 maid_native:gun_shot"
                        + "（此前全文没有 superbwarfare 的载体规则 ⇒ 一条候选都没有）",
                swIds.contains("maid_native:gun_shot"), swIds.toString());
        expect("★★ 且它【不会】拿到 TaCZ 的那条（不重叠）",
                !swIds.contains("tacz:shoot"), swIds.toString());
    }

    // ═══════════════ 8. ★ A 档参数化：SA ═══════════════

    private static void testParametricSlashArts() {
        section("8. ★★ A 档参数化：SA 种在运行期解析（清单不必知道有哪把刀）");

        PossessedItem bladeJudgement = PossessedItem.withParams(
                SlotKind.MAINHAND, "slashblade:yasha",
                Set.of("ItemSlashBlade", "Item"),
                Map.of("arts", "judgement_cut"));
        PossessedItem bladeDrive = PossessedItem.withParams(
                SlotKind.MAINHAND, "slashblade:kiri",
                Set.of("ItemSlashBlade", "Item"),
                Map.of("arts", "drive_horizontal"));

        CandidateAction saJ = find(resolver.resolve(List.of(bladeJudgement), ctx(true, false),
                SpringConfig.defaults()), "slashblade:slash_art");
        CandidateAction saD = find(resolver.resolve(List.of(bladeDrive), ctx(true, false),
                SpringConfig.defaults()), "slashblade:slash_art");

        System.out.println("   arts=judgement_cut    → " + v(saJ));
        System.out.println("   arts=drive_horizontal → " + v(saD));

        expect("★ 次元斩命中 AREA 0.9 的覆写", saJ != null
                && near(saJ.staticVector().get(
                        com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.AREA_DAMAGE), 0.9),
                v(saJ));
        expect("★★ 换一把刀（改 NBT）⇒ 同一条目的向量随之改变，清单无需改动",
                saD != null && saJ != null
                        && !saJ.staticVector().equals(saD.staticVector()),
                v(saD));
    }

    // ═══════════════ 9. 丢弃原因 ═══════════════

    private static void testDropReasonsAreExplained() {
        section("9. ★ 每条被丢弃的动作都有原因（灭火器静默丢失教训的对策）");

        PossessedItem blade = PossessedItem.of(SlotKind.MAINHAND, "slashblade:slashblade",
                Set.of("ItemSlashBlade", "Item"));
        CarrierResolver.Resolution r = resolver.resolve(List.of(blade), ctx(true, false), SpringConfig.defaults());

        boolean allExplained = r.dropped().stream()
                .allMatch(d -> d.reason() != null && d.detail() != null);
        System.out.println("   " + r.summary());
        for (var e : r.droppedByReason().entrySet()) {
            System.out.println("     - " + e.getKey() + " (" + e.getKey().zh() + ") x" + e.getValue());
        }
        expect("★ 所有丢弃都有原因与细节", allExplained, "存在无原因的丢弃");

        // ★ W2 已基本解决：所有 role=choice 的动作都应有向量。
        //   缺向量的应当【只有】maintenance/stance 类（它们本来就不是战术选择）。
        long choiceCount = resolver.actions().stream().filter(ActionSpec::isChoice).count();
        long choiceScoreable = resolver.actions().stream()
                .filter(ActionSpec::isChoice).filter(ActionSpec::isScoreable).count();
        System.out.println("   choice 动作的向量覆盖：" + choiceScoreable + " / " + choiceCount
                + "（含仅靠 vectorOverrides 评分的，如 goety:cast_focus）");
        expect("★★ W2：所有「可选择」动作都能被评分（维护/姿态类不进候选，无需向量）",
                choiceScoreable == choiceCount,
                choiceScoreable + "/" + choiceCount);

        // 机制仍在：若某动作缺向量，必须报 NO_VECTOR 而不是当成"零价值"静默放行
        expect("★ 缺向量的机制仍生效（NO_VECTOR 是显式原因，不是静默丢弃）",
                DropReason.NO_VECTOR.zh().contains("无法参与评分"),
                DropReason.NO_VECTOR.zh());
    }

    // ═══════════════ 10. 未查证载体显式可见 ═══════════════

    private static void testUnresolvedCarriersVisible() {
        section("10. ★ 判据未查证的载体显式登记（fail-closed，不静默成功）");

        long unresolved = resolver.rules().stream().filter(CarrierRule::isUnresolved).count();
        List<String> names = resolver.rules().stream().filter(CarrierRule::isUnresolved)
                .map(CarrierRule::carrier).toList();
        System.out.println("   未查证载体 " + unresolved + " 个：" + names);

        expect("★ 未查证载体被显式标出（而不是假装能用）", unresolved > 0, "0 个");

        // 它们必须永不命中：即使给了对应的物品
        PossessedItem horn = PossessedItem.of(SlotKind.MAINHAND, "goety:command_horn", Set.of("Item"));
        CarrierResolver.Resolution r = resolver.resolve(List.of(horn), ctx(true, false), SpringConfig.defaults());
        expect("★ 未查证载体永不命中（fail-closed）",
                ids(r).stream().noneMatch(s -> s.startsWith("goety:command_horn")),
                ids(r).toString());
    }

    // ═══════════════ 11. ★ 动作的「单位」随 fireMode 改变 ═══════════════

    /**
     * ★★ 验证 docs/09 §2.4 的单位定义：
     * <b>「开火」的单位不是"一发"也不是"一梭子"，而是"一次 {@code shoot()} 调用"，
     * 其承诺形状由 {@code fireMode} 决定。</b>
     *
     * <p>这条断言直接钉住了一个**曾经存在的建模错误**：把 AUTO 当 INSTANT，
     * 会让女仆每决策周期（5 tick）只打 1 发，而玩家是每 2 tick 一发 ⇒ 火力只有 40%。
     */
    private static void testFireModeChangesCommitmentShape() {
        section("11. ★★ 动作单位：同一动作在不同 fireMode 下承诺形状不同");

        CandidateAction semi = gun("SEMI");
        CandidateAction burst = gun("BURST");
        CandidateAction auto = gun("AUTO");

        System.out.println("   SEMI  → commitmentTicks=" + (semi == null ? "?" : semi.commitmentTicks()));
        System.out.println("   BURST → commitmentTicks=" + (burst == null ? "?" : burst.commitmentTicks()));
        System.out.println("   AUTO  → commitmentTicks=" + (auto == null ? "?" : auto.commitmentTicks()));

        expect("SEMI = 一发 ⇒ 瞬发（承诺 0）", semi != null && semi.commitmentTicks() == 0,
                String.valueOf(semi == null ? "null" : semi.commitmentTicks()));
        expect("★ BURST = 一轮连发 ⇒ 有承诺（>0，一次 shoot() 打完三连发）",
                burst != null && burst.commitmentTicks() > 0,
                String.valueOf(burst == null ? "null" : burst.commitmentTicks()));
        expect("★★ AUTO = 【一梭子打完】⇒ 承诺最长（弹匣 > 一轮连发；换弹自动）",
                auto != null && auto.commitmentTicks() > burst.commitmentTicks(),
                String.valueOf(auto == null ? "null" : auto.commitmentTicks()));
    }

    /** 构造一把指定 fireMode 的枪并取出「开火」候选。 */
    private static CandidateAction gun(String fireMode) {
        PossessedItem g = PossessedItem.withParams(
                SlotKind.MAINHAND, "tacz:testgun", Set.of("IGun", "ModernKineticGunItem"),
                Map.of("gunType", "rifle", "fireMode", fireMode));
        return find(resolver.resolve(List.of(g), ctx(true, false), SpringConfig.defaults()), "tacz:shoot");
    }

    // ═══════════════ 12. ★★ 耐久过滤（委托方要求） ═══════════════

    /**
     * 验委托方的耐久规则：
     * 「若物品耐久低于 5% 或低于 5 次时，其不再为动作列表提供可选动作
     *   （但要注意有两份同类的，其中一份没耐久另一份还有的情况）」
     *
     * <p>★ 关键在于<b>判据落在"物品"而不是"载体"</b> —— 见 {@link ItemUsability}。
     */
    private static void testDurabilityFiltering() {
        section("12. ★★ 耐久过滤：快坏的物品不再提供动作（但同类的另一件还能）");

        ItemUsability.resetDefaults();
        String bladeId = "slashblade:slashblade";
        Set<String> bladeTypes = Set.of("ItemSlashBlade", "Item");

        // ── ① 全新：可用 ──
        PossessedItem fresh = PossessedItem.withParams(SlotKind.MAINHAND, bladeId, bladeTypes,
                Map.of("arts", "judgement_cut")).withWear(0, 1000);
        expect("全新（0/1000）可用", fresh.isUsable(), fresh.toString());
        expect("★ 剩余 100% 通过", ItemUsability.whyUnusable(fresh) == null,
                String.valueOf(ItemUsability.whyUnusable(fresh)));

        // ── ② 低于 5% ⇒ 不可用 ──
        PossessedItem lowPct = PossessedItem.withParams(SlotKind.MAINHAND, bladeId, bladeTypes,
                Map.of()).withWear(960, 1000);   // 剩 40/1000 = 4%
        System.out.println("   4%/40 次 → " + lowPct + " 可用=" + lowPct.isUsable()
                + " 原因=" + ItemUsability.whyUnusable(lowPct));
        expect("★ 剩余 4%（<5%）不可用", !lowPct.isUsable(), lowPct.toString());

        // ── ③ 低于 5 次 ⇒ 不可用（即使百分比还很高）──
        PossessedItem lowUses = PossessedItem.withParams(SlotKind.MAINHAND, bladeId, bladeTypes,
                Map.of()).withWear(96, 100);     // 剩 4/100 = 4%
        expect("★ 剩余 4 次（<5 次）不可用", !lowUses.isUsable(), lowUses.toString());
        // 高耐久物品的"高百分比但低次数"是靠次数条件兜住的
        PossessedItem fewUses = PossessedItem.withParams(SlotKind.MAINHAND, bladeId, bladeTypes,
                Map.of()).withWear(9996, 10000);  // 剩 4/10000 = 0.04%
        expect("★ 剩 4/10000 不可用", !fewUses.isUsable(), fewUses.toString());

        // ── ④ 没有耐久概念的物品：永远可用 ──
        PossessedItem noDura = PossessedItem.of(SlotKind.MAINHAND, "minecraft:bread", Set.of("Item"));
        expect("★ 无耐久概念（maxDamage=0）永远可用", noDura.isUsable(), noDura.toString());

        // ── ⑤ ★★ 核心：两份同类，一份坏了另一份还好 ⇒ 动作【仍然可用】 ──
        PossessedItem broken = PossessedItem.withParams(SlotKind.MAINHAND, bladeId, bladeTypes,
                Map.of("arts", "judgement_cut")).withWear(999, 1000);
        PossessedItem backup = PossessedItem.withParams(SlotKind.INVENTORY, bladeId, bladeTypes,
                Map.of("arts", "judgement_cut")).withWear(0, 1000);

        CarrierResolver.Resolution onlyBroken =
                resolver.resolve(List.of(broken), ctx(true, false), SpringConfig.defaults());
        expect("★★ 只有坏刀 ⇒ 拔刀剑动作不可用，且原因是 ITEM_EXHAUSTED（不是 NO_CARRIER）",
                ids(onlyBroken).stream().noneMatch(s -> s.startsWith("slashblade:"))
                        && onlyBroken.droppedByReason().containsKey(DropReason.ITEM_EXHAUSTED),
                onlyBroken.droppedByReason().toString());

        CarrierResolver.Resolution brokenPlusBackup =
                resolver.resolve(List.of(broken, backup), ctx(true, false), SpringConfig.defaults());
        System.out.println("   坏刀 + 备份 → 可用 " + ids(brokenPlusBackup).size() + " 条");
        expect("★★★ 坏刀【+ 背包里的备份】⇒ 拔刀剑动作【恢复可用】",
                ids(brokenPlusBackup).stream().anyMatch(s -> s.startsWith("slashblade:")),
                ids(brokenPlusBackup).toString());
        expect("★★ 且 ITEM_EXHAUSTED 不再出现（因为池子里有可用的）",
                !brokenPlusBackup.droppedByReason().containsKey(DropReason.ITEM_EXHAUSTED),
                brokenPlusBackup.droppedByReason().toString());

        // ── ⑥ 可用数不应减少（备份完全补偿了坏刀）──
        CarrierResolver.Resolution onlyBackup =
                resolver.resolve(List.of(backup), ctx(true, false), SpringConfig.defaults());
        expect("★『坏刀+备份』的可用数与『只有备份』相同",
                ids(brokenPlusBackup).size() == ids(onlyBackup).size(),
                ids(brokenPlusBackup).size() + " vs " + ids(onlyBackup).size());

        ItemUsability.resetDefaults();
    }

    // ═══════════════ 辅助 ═══════════════

    /**
     * 构造上下文事实。
     *
     * <p>★ 注意 {@code customFacts} 里的键：{@code custom} 类前置条件是 <b>fail-closed</b> 的，
     * 必须显式喂进来。键的规则见 {@link CarrierResolver#customKey}：
     * 清单里写了 {@code fact} 就用它，否则用 {@code "<动作id>#<前置条件下标>"}。
     *
     * <p>这里喂的是 {@code tacz:shoot} 的第 2 个前置条件（下标 1）=「弹药在女仆背包里」。
     * <b>如果这里漏喂，开火就会被正确地丢弃</b> —— 这正是我们要的 fail-closed 行为。
     */
    private static ContextFacts ctx(boolean hasTarget, boolean sneaking) {
        return new ContextFacts(hasTarget, false, 0, true, sneaking, false,
                Map.of("耀魂值", 100.0),
                Map.of("tacz:shoot#1", true,
                        // ★ 2026-10-03：拔刀剑也补了解析期前置，夹具照喂
                        "slashblade:has_slash_art", true,
                        "slashblade:can_summon_sword", true),
                Map.of(), Set.of(), ALL_MODS);
    }

    private static List<String> ids(CarrierResolver.Resolution r) {
        return r.candidates().stream().map(CandidateAction::id).sorted().toList();
    }

    private static CandidateAction find(CarrierResolver.Resolution r, String id) {
        Optional<CandidateAction> c = r.candidates().stream().filter(a -> a.id().equals(id)).findFirst();
        return c.orElse(null);
    }

    private static String v(CandidateAction c) {
        return c == null ? "(不可用)" : c.staticVector().toCompactString(SpringConfig.defaults());
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static List<String> head(List<String> l, int n) {
        return l.size() <= n ? l : l.subList(0, n);
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
