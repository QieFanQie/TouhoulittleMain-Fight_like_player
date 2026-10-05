package com.touhoulittlemad.fightlikeplayer.decision;

import com.touhoulittlemad.fightlikeplayer.decision.thinking.CombatScene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>指令系统自测</b>（docs/15 的 M1/M2 判据）—— 纯逻辑，零 MC 依赖。
 *
 * <pre>
 *   java -cp "build\classes\java\main" \
 *        com.touhoulittlemad.fightlikeplayer.decision.DirectiveSelfTest
 * </pre>
 *
 * <p>钉住的是<b>计划的判据</b>，不是实现细节：
 * 白名单/丢弃登记、TTL 钳制与过期、互斥自动让位、cancel 先于 issue、
 * 候选集过滤（含"只允许 X 时维护/位移类也挡下"）、步法约束的提取。
 */
public final class DirectiveSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== 指令系统自测 (DirectiveSelfTest) ===");
        System.out.println();

        testTable();
        testIssueAndTtl();
        testMutex();
        testInstantQueue();
        testFilterRules();
        testGaitConstraint();
        testSceneFacts();
        testChatChannel();
        testSemiOpenDirectives();

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

    // ───────────── 1. \u6307\u4ee4\u8868 ─────────────
    private static void testTable() {
        section("1. \u6307\u4ee4\u8868\uff1a21 \u6761\uff08\u6301\u7eed/\u77ac\u95f4\uff09\u3001id \u552f\u4e00\u3001\u53c2\u6570\u5408\u6cd5");

        var all = DirectiveSpec.all();
        System.out.println("   \u6307\u4ee4\u603b\u6570 = " + all.size());
        expect("\u2605 \u81f3\u5c11 20 \u6761\u6307\u4ee4", all.size() >= 20, String.valueOf(all.size()));

        long sustained = all.stream()
                .filter(s -> s.kind() == DirectiveSpec.Kind.SUSTAINED).count();
        long instant = all.stream()
                .filter(s -> s.kind() == DirectiveSpec.Kind.INSTANT).count();
        System.out.println("   \u6301\u7eed = " + sustained + "\uff0c\u77ac\u95f4 = " + instant);
        expect("\u2605 \u4e24\u7c7b\u90fd\u6709\uff08\u77ac\u95f4\u4e0e\u6301\u7eed\uff09", sustained > 0 && instant > 0, "");

        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean unique = true;
        boolean hasZh = true;
        boolean hasEnforce = true;
        for (var s : all) {
            unique &= ids.add(s.id());
            hasZh &= s.zh() != null && !s.zh().isBlank();
            hasEnforce &= s.enforce() != null && !s.enforce().isBlank();
        }
        expect("\u2605\u2605 id \u552f\u4e00", unique, "\u6709\u91cd\u590d");
        expect("\u2605 \u6bcf\u6761\u90fd\u6709\u4e2d\u6587\u540d", hasZh, "");
        expect("\u2605\u2605 \u6bcf\u6761\u90fd\u6709\u300c\u5f3a\u5236\u624b\u6bb5\u300d\u8bf4\u660e\uff08\u8fdb prompt\uff0c\u4e5f\u8fdb\u547d\u4ee4\uff09",
                hasEnforce, "");

        // \u56fa\u5b9a\u51e0\u6761\u8ba1\u5212\u91cc\u660e\u786e\u8981\u6c42\u7684
        for (String id : List.of("magic_only", "melee_only", "ranged_only", "keep_distance",
                "close_in", "hold_position", "no_retreat", "no_item_switch", "conserve_ammo",
                "focus_category", "ban_focus", "protect_owner", "no_summons",
                "interrupt", "disengage", "reload_now", "recall_servants", "dismiss_servants",
                "stance_guard", "stance_attack", "heal_now")) {
            expect("\u2605 \u6307\u4ee4\u8868\u542b " + id, DirectiveSpec.exists(id), "\u7f3a\u5931");
        }

        // \u53c2\u6570\u89c4\u683c\u5408\u6cd5
        var keep = DirectiveSpec.find("keep_distance");
        expect("\u2605 keep_distance \u6709 min \u53c2\u6570\uff08\u9ed8\u8ba4 8\uff09",
                keep.hasParams() && keep.params().get("min").def() == 8, String.valueOf(keep.params()));
        expect("\u2605 \u53c2\u6570\u533a\u95f4\u5408\u6cd5\uff08min <= def <= max\uff09",
                keep.params().get("min").min() <= 8 && 8 <= keep.params().get("min").max(), "");
        expect("\u2605\u2605 \u6301\u7eed\u6307\u4ee4\u7684\u9ed8\u8ba4 TTL \u2264 \u4e0a\u9650\uff08\u9632\u6c38\u4e45\u5361\u6b7b\uff09",
                keep.defTtlTicks() <= DirectiveSpec.MAX_TTL_TICKS,
                keep.defTtlTicks() + " > " + DirectiveSpec.MAX_TTL_TICKS);

        // \u7ed9 LLM \u770b\u7684\u8868\uff1a\u5fc5\u987b\u542b id/kind/params/enforce
        String llm = DirectiveSpec.describeForLlm();
        expect("\u2605\u2605 LLM \u7684\u6307\u4ee4\u8868\u542b id/kind/enforce",
                llm.contains("\"id\":\"magic_only\"") && llm.contains("\"kind\":\"sustained\"")
                        && llm.contains("\"enforce\""), llm.substring(0, Math.min(80, llm.length())));
        expect("\u2605 \u6307\u4ee4\u8868\u542b\u53c2\u6570\u533a\u95f4\uff08\u6a21\u578b\u624d\u4e0d\u4f1a\u5199\u8d8a\u754c\u503c\uff09",
                llm.contains("\"min\"") && llm.contains("\"max\""), "");
        expect("\u2605 \u73a9\u5bb6\u8868\u975e\u7a7a", !DirectiveSpec.describeForPlayer().isBlank(), "");
    }

    // ───────────── 2. \u4e0b\u8fbe / TTL ─────────────
    private static void testIssueAndTtl() {
        section("2. \u4e0b\u8fbe\uff1a\u672a\u77e5 id \u4e22\u5f03\u5e76\u767b\u8bb0\u3001\u53c2\u6570\u94b3\u5236\u3001TTL \u94b3\u5236\u4e0e\u8fc7\u671f");

        var bus = new DirectiveBus();
        var r1 = bus.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605 \u5408\u6cd5\u6307\u4ee4\u4e0b\u8fbe\u6210\u529f", r1.ok(), r1.message());
        expect("\u2605 \u5df2\u751f\u6548", bus.has("magic_only"), "");

        var r2 = bus.issue("not_a_real_directive", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 \u672a\u77e5 id \u4e0b\u8fbe\u5931\u8d25\uff08\u4e0d\u9759\u9ed8\uff09", !r2.ok(), r2.message());
        expect("\u2605\u2605 \u4e14\u88ab\u767b\u8bb0\uff08\u547d\u4ee4\u91cc\u80fd\u770b\u5230\u300c\u66fe\u4e22\u5f03\u4ec0\u4e48\u300d\uff09",
                bus.lastRejected().contains("not_a_real_directive"), bus.lastRejected().toString());

        // \u53c2\u6570\u94b3\u5236
        var bus2 = new DirectiveBus();
        bus2.issue("keep_distance", Map.of("min", 999.0), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 \u8d8a\u754c\u53c2\u6570\u88ab\u94b3\u5230\u4e0a\u9650\uff0812 \u4ee5\u5185\u2192 24\uff09",
                bus2.param("keep_distance", "min") == 24.0,
                String.valueOf(bus2.param("keep_distance", "min")));
        var bus3 = new DirectiveBus();
        var r3 = bus3.issue("keep_distance", Map.of("nonsense", 5.0), Map.of(), 0, "llm", 0);
        expect("\u2605 \u672a\u77e5\u53c2\u6570\u88ab\u4e22\u5f03\u5e76\u5728\u7ed3\u679c\u91cc\u70b9\u540d",
                r3.message().contains("nonsense"), r3.message());

        // TTL
        var bus4 = new DirectiveBus();
        bus4.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        long until = bus4.active().get("magic_only").untilTick();
        expect("\u2605 \u9ed8\u8ba4 TTL \u751f\u6548\uff08untilTick > \u5f53\u524d\uff09", until > 0, String.valueOf(until));
        expect("\u2605 \u672a\u5230\u671f\u4e0d\u8fc7\u671f\uff0c\u4e14 tick \u8fd4\u56de\u7a7a",
                bus4.tick(until - 1).isEmpty() && bus4.has("magic_only"), "");
        expect("\u2605\u2605 \u5230\u671f\u81ea\u52a8\u89e3\u9664\uff0c\u5e76\u544a\u8bc9\u8c03\u7528\u65b9\u662f\u54ea\u4e00\u6761",
                bus4.tick(until).equals(List.of("magic_only")) && !bus4.has("magic_only"),
                bus4.active().toString());

        // TTL \u4e0a\u9650
        var bus5 = new DirectiveBus();
        bus5.issue("magic_only", Map.of(), Map.of(), Integer.MAX_VALUE, "llm", 0);
        long u5 = bus5.active().get("magic_only").untilTick();
        expect("\u2605\u2605 \u8d85\u5927 TTL \u88ab\u94b3\u5230 MAX_TTL_TICKS\uff08\u7edd\u4e0d\u5141\u8bb8\u6c38\u4e45\u5361\u6b7b\uff09",
                u5 <= DirectiveSpec.MAX_TTL_TICKS,
                u5 + " > " + DirectiveSpec.MAX_TTL_TICKS);

        // \u53d6\u6d88
        var bus6 = new DirectiveBus();
        bus6.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605 \u53d6\u6d88\u751f\u6548\u4e2d\u7684\u6307\u4ee4", bus6.cancel("magic_only"), "");
        expect("\u2605 \u53d6\u6d88\u4e0d\u5b58\u5728\u7684\u8fd4\u56de false", !bus6.cancel("magic_only"), "");
        bus6.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        bus6.issue("close_in", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605 cancelAll \u6e05\u7a7a\u5e76\u8fd4\u56de\u6e05\u5355", bus6.cancelAll().size() == 2, "");
    }

    // ───────────── 3. \u4e92\u65a5 ─────────────
    private static void testMutex() {
        section("3. \u4e92\u65a5\u7ec4\uff1a\u540c\u7ec4\u53ea\u80fd\u6709\u4e00\u6761\uff08\u5426\u5219\u5019\u9009\u96c6\u4f1a\u7a7a \u21d2 \u5979\u4ec0\u4e48\u90fd\u4e0d\u505a\uff09");

        var bus = new DirectiveBus();
        bus.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        var r = bus.issue("melee_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 \u4e0b\u8fbe melee_only \u65f6\u81ea\u52a8\u53d6\u6d88 magic_only",
                r.autoCancelled().contains("magic_only"), r.message());
        expect("\u2605 \u73b0\u5728\u53ea\u6709 melee_only",
                bus.has("melee_only") && !bus.has("magic_only"), bus.active().toString());
        expect("\u2605\u2605 \u4e09\u9009\u4e00\uff1a\u4e0b\u8fbe ranged_only \u540e\u53ea\u5269\u5b83",
                bus.issue("ranged_only", Map.of(), Map.of(), 0, "llm", 0).ok()
                        && bus.activeCount() == 1 && bus.has("ranged_only"),
                bus.active().toString());
        expect("\u2605 distance \u7ec4\u540c\u6837\u4e92\u65a5\uff1aclose_in \u4f1a\u628a keep_distance \u8ba9\u51fa\u6765",
                bus.issue("keep_distance", Map.of(), Map.of(), 0, "llm", 0).ok()
                        && bus.issue("close_in", Map.of(), Map.of(), 0, "llm", 0)
                        .autoCancelled().contains("keep_distance"),
                bus.active().toString());
        expect("\u2605 \u4e0d\u540c\u7ec4\u4e0d\u4e92\u65a5\uff08ranged_only + close_in \u5171\u5b58\uff09",
                bus.has("ranged_only") && bus.has("close_in"), bus.active().toString());
    }

    // ───────────── 4. \u77ac\u95f4\u961f\u5217 ─────────────
    private static void testInstantQueue() {
        section("4. \u77ac\u95f4\u6307\u4ee4\uff1a\u5165\u961f\u3001\u9010\u6761\u53d6\u8d70\u3001\u4e0d\u5360\u6301\u7eed\u96c6\u5408");

        var bus = new DirectiveBus();
        var r = bus.issue("interrupt", Map.of(), Map.of(), 0, "player", 0);
        expect("\u2605 \u77ac\u95f4\u6307\u4ee4\u4e0d\u8fdb\u6301\u7eed\u96c6\u5408", r.ok() && bus.activeCount() == 0,
                bus.active().toString());
        expect("\u2605 \u53d6\u8d70\u4e00\u6761", bus.pollInstant() != null, "");
        expect("\u2605 \u518d\u53d6\u5c31\u6ca1\u4e86", bus.pollInstant() == null, "");

        bus.issue("interrupt", Map.of(), Map.of(), 0, "player", 0);
        bus.issue("stance_guard", Map.of("ticks", 100.0), Map.of(), 0, "player", 0);
        var a = bus.pollInstant();
        var b = bus.pollInstant();
        expect("\u2605\u2605 \u5148\u8fdb\u5148\u51fa\uff08\u987a\u5e8f\uff09",
                a != null && b != null && "interrupt".equals(a.id()) && "stance_guard".equals(b.id()),
                (a == null ? "null" : a.id()) + "/" + (b == null ? "null" : b.id()));
        expect("\u2605 \u77ac\u95f4\u6307\u4ee4\u7684\u53c2\u6570\u4e5f\u94b3\u5236\uff08ticks \u8d85\u9650\uff09",
                true, "");
        var bus2 = new DirectiveBus();
        bus2.issue("stance_guard", Map.of("ticks", 99999.0), Map.of(), 0, "player", 0);
        var g = bus2.pollInstant();
        expect("\u2605 stance_guard ticks \u94b3\u5230 600",
                g != null && g.params().get("ticks") == 600.0,
                g == null ? "null" : String.valueOf(g.params().get("ticks")));
        // \u2605 \u6ce8\u610f\uff1acancelAll() \u8fd4\u56de\u7684\u662f\u3010\u6301\u7eed\u6307\u4ee4\u3011\u7684 id \u6e05\u5355（\u8fd9\u91cc\u6ca1\u6709\u6301\u7eed\u6307\u4ee4 \u21d2 \u7a7a\u8868\u662f\u5bf9\u7684\uff09\uff0c
        //   \u8981\u65ad\u8a00\u7684\u662f\u300c\u77ac\u95f4\u961f\u5217\u4e5f\u88ab\u6e05\u4e86\u300d\u3002
        bus2.issue("interrupt", Map.of(), Map.of(), 0, "player", 0);
        bus2.cancelAll();
        expect("\u2605 cancelAll \u4e5f\u6e05\u77ac\u95f4\u961f\u5217", bus2.pollInstant() == null, "");

        // \u2605\u2605 \u77ac\u95f4\u6307\u4ee4\u7684\u4fdd\u9c9c\u671f\uff08\u7b2c 14 \u8f6e\u53d1\u73b0\u7684\u5751\uff09\uff1a
        //   \u6267\u884c\u70b9\u5728\u884c\u4e3a\u5c42\uff0c\u800c\u884c\u4e3a\u5c42\u53ea\u5728\u300c\u5979\u6709\u653b\u51fb\u76ee\u6807\u300d\u65f6\u8dd1
        //   \u21d2 \u5979\u6ca1\u5728\u6253\u67b6\u65f6\u4e0b\u7684\u77ac\u95f4\u6307\u4ee4\u4f1a\u4e00\u76f4\u8eba\u5728\u961f\u5217\u91cc\uff0c
        //     \u76f4\u5230\u5979\u4e0b\u6b21\u8fdb\u6218\u6597\u624d\u7a81\u7136\u751f\u6548\uff08\u73b0\u8c61\uff1a\u300c\u51e0\u5206\u949f\u524d\u8bf4\u7684\u8bdd\u7a81\u7136\u6267\u884c\u300d\uff09
        var bus3 = new DirectiveBus();
        bus3.issue("stance_guard", Map.of(), Map.of(), 0, "chat", 100);
        bus3.tick(100 + DirectiveBus.INSTANT_MAX_AGE_TICKS - 5);
        expect("\u2605 \u4fdd\u9c9c\u671f\u5185 \u21d2 \u4ecd\u7136\u53d6\u5f97\u5230",
                bus3.pollInstant() != null, "");

        var bus4 = new DirectiveBus();
        bus4.issue("stance_guard", Map.of(), Map.of(), 0, "chat", 100);
        bus4.tick(100 + DirectiveBus.INSTANT_MAX_AGE_TICKS + 1);
        expect("\u2605\u2605 \u653e\u8d85\u8fc7\u4fdd\u9c9c\u671f \u21d2 \u4f5c\u5e9f\uff08\u4e0d\u5728\u5979\u4e0b\u6b21\u8fdb\u6218\u6597\u65f6\u7a81\u7136\u751f\u6548\uff09",
                bus4.pollInstant() == null && !bus4.lastStaleInstant().isEmpty(),
                bus4.lastStaleInstant().toString());
    }

    // ───────────── 5. \u5019\u9009\u96c6\u8fc7\u6ee4 ─────────────
    private static void testFilterRules() {
        section("5. \u5019\u9009\u96c6\u8fc7\u6ee4\uff1a\u624b\u6bb5\u4e09\u9009\u4e00 / \u7ad9\u4f4d\u7ea6\u675f / \u4e0d\u53ec\u5524");

        // \u5206\u7c7b
        expect("\u2605 goety:cast_focus \u2192 SPELL",
                DirectiveFilter.classify("goety:cast_focus") == DirectiveFilter.ActionClass.SPELL, "");
        expect("\u2605 maid_native:melee_swing \u2192 MELEE",
                DirectiveFilter.classify("maid_native:melee_swing")
                        == DirectiveFilter.ActionClass.MELEE, "");
        expect("\u2605 slashblade:combo_a \u2192 MELEE",
                DirectiveFilter.classify("slashblade:combo_a")
                        == DirectiveFilter.ActionClass.MELEE, "");
        expect("\u2605 tacz:shoot \u2192 RANGED",
                DirectiveFilter.classify("tacz:shoot") == DirectiveFilter.ActionClass.RANGED, "");
        // \u2605\u2605 第十七轮（枪械审计 B4）："最长前缀优先"必须真的生效 ——
        //    否则 `tacz:` 会把这些维护类全部吃掉（它们曾因此被判成 RANGED，登记等于没有）。
        expect("\u2605\u2605 tacz:reload \u2192 MAINTENANCE（最长前缀 11 > 5，不再被 `tacz:` 吃掉）",
                DirectiveFilter.classify("tacz:reload") == DirectiveFilter.ActionClass.MAINTENANCE,
                DirectiveFilter.classify("tacz:reload").name());
        expect("\u2605\u2605 tacz:aim / fire_select / bolt / draw \u2192 MAINTENANCE",
                DirectiveFilter.classify("tacz:aim") == DirectiveFilter.ActionClass.MAINTENANCE
                        && DirectiveFilter.classify("tacz:fire_select")
                                == DirectiveFilter.ActionClass.MAINTENANCE
                        && DirectiveFilter.classify("tacz:bolt") == DirectiveFilter.ActionClass.MAINTENANCE
                        && DirectiveFilter.classify("tacz:draw") == DirectiveFilter.ActionClass.MAINTENANCE,
                "有非 MAINTENANCE 的");
        expect("\u2605 tacz:melee \u2192 MELEE（枪托近战不是远程）；tacz:shoot 仍是 RANGED",
                DirectiveFilter.classify("tacz:melee") == DirectiveFilter.ActionClass.MELEE
                        && DirectiveFilter.classify("tacz:shoot") == DirectiveFilter.ActionClass.RANGED,
                DirectiveFilter.classify("tacz:melee").name());
        expect("\u2605 maid_native:bow_shot \u2192 RANGED",
                DirectiveFilter.classify("maid_native:bow_shot")
                        == DirectiveFilter.ActionClass.RANGED, "");
        expect("\u2605\u2605 \u8ba4\u4e0d\u51fa\u6765\u7684 -> OTHER\uff08\u4e0d\u731c\uff09",
                DirectiveFilter.classify("some_mod:mystery")
                        == DirectiveFilter.ActionClass.OTHER, "");

        // magic_only
        var bus = new DirectiveBus();
        bus.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 magic_only \u653e\u884c\u65bd\u6cd5", DirectiveFilter.allowed(bus, "goety:cast_focus"), "");
        expect("\u2605\u2605 magic_only \u6321\u4e0b\u8fd1\u6218", !DirectiveFilter.allowed(bus, "maid_native:melee_swing"), "");
        expect("\u2605\u2605 magic_only \u6321\u4e0b\u8fdc\u7a0b", !DirectiveFilter.allowed(bus, "tacz:shoot"), "");
        expect("\u2605\u2605 magic_only \u4e5f\u6321\u4e0b\u7ef4\u62a4\u7c7b\uff08\u6362\u5f39/\u4e3e\u76fe\uff09",
                !DirectiveFilter.allowed(bus, "tacz:reload")
                        && !DirectiveFilter.allowed(bus, "maid_native:shield_block"), "");
        expect("\u2605\u2605 magic_only \u6321\u4e0b\u4f4d\u79fb\u7c7b\u52a8\u4f5c\uff08\u4f4d\u79fb\u7531\u6b65\u6cd5\u8d1f\u8d23\uff09",
                !DirectiveFilter.allowed(bus, "fight_like_player:disengage"), "");
        expect("\u2605\u2605 magic_only \u6321\u4e0b\u8ba4\u4e0d\u51fa\u6765\u7684\uff08\u5b81\u53ef\u4e0d\u505a\uff09",
                !DirectiveFilter.allowed(bus, "other_mod:thing"), "");

        // melee_only / ranged_only
        var bus2 = new DirectiveBus();
        bus2.issue("ranged_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605 ranged_only \u653e\u884c\u8fdc\u7a0b", DirectiveFilter.allowed(bus2, "maid_native:bow_shot"), "");
        expect("\u2605 ranged_only \u6321\u4e0b\u65bd\u6cd5", !DirectiveFilter.allowed(bus2, "goety:cast_focus"), "");

        // \u7ad9\u4f4d\u7ea6\u675f
        var bus3 = new DirectiveBus();
        bus3.issue("keep_distance", Map.of("min", 8.0), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 keep_distance \u6321\u4e0b\u8fd1\u6218\u7c7b\u52a8\u4f5c",
                !DirectiveFilter.allowed(bus3, "maid_native:melee_swing"), "");
        expect("\u2605 keep_distance \u4e0d\u6321\u65bd\u6cd5", DirectiveFilter.allowed(bus3, "goety:cast_focus"), "");
        var bus4 = new DirectiveBus();
        bus4.issue("close_in", Map.of("max", 3.0), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 close_in \u6321\u4e0b\u8fdc\u7a0b\u7c7b\u52a8\u4f5c",
                !DirectiveFilter.allowed(bus4, "tacz:shoot"), "");
        expect("\u2605 close_in \u4e0d\u6321\u8fd1\u6218", DirectiveFilter.allowed(bus4, "maid_native:melee_swing"), "");

        // \u4e0d\u53ec\u5524 / \u805a\u6676\u8fc7\u6ee4
        var bus5 = new DirectiveBus();
        bus5.issue("no_summons", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 no_summons \u6321\u4e0b\u4ec6\u4ece\u7ba1\u7406\u7c7b\u52a8\u4f5c",
                !DirectiveFilter.allowed(bus5, "goety:recall_servants"), "");
        expect("\u2605\u2605 no_summons \u5728\u805a\u6676\u5c42\u6321\u4e0b\u53ec\u5524\u7c7b\u805a\u6676",
                !DirectiveFilter.focusAllowed(bus5, "SUMMON", "VexSpell")
                        && DirectiveFilter.focusAllowed(bus5, "ATTACK_SINGLE", "FireballSpell"), "");
        var bus6 = new DirectiveBus();
        bus6.issue("focus_category", Map.of("category", 0.0), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 focus_category=0 \u53ea\u653e\u884c ATTACK_SINGLE",
                DirectiveFilter.focusAllowed(bus6, "ATTACK_SINGLE", "FireballSpell")
                        && !DirectiveFilter.focusAllowed(bus6, "SUMMON", "VexSpell"), "");
        var bus7 = new DirectiveBus();
        bus7.issue("ban_focus", Map.of(), Map.of("label", "FireballSpell"), 0, "llm", 0);
        expect("\u2605\u2605 ban_focus \u7cbe\u786e\u6392\u9664\u90a3\u4e00\u9897",
                !DirectiveFilter.focusAllowed(bus7, "ATTACK_SINGLE", "FireballSpell")
                        && DirectiveFilter.focusAllowed(bus7, "ATTACK_SINGLE", "IceSpell"), "");

        // \u65e0\u6307\u4ee4 = \u5168\u653e\u884c
        // \u2605\u2605 \u7b2c\u4e8c\u5341\u4e00\u8f6e\uff1a**\u65bd\u6cd5\u4f53\u7cfb\u7ec6\u5206**\uff08\u59d4\u6258\u65b9\u539f\u8bdd\u300c\u5973\u4ec6\u4f3c\u4e4e\u505a\u4e0d\u5230"\u6307\u5b9a\u4f7f\u7528\u94c1\u9b54\u6cd5"\uff0c
        //   \u5bf9\u4e8e\u5979\u800c\u8a00\uff0c\u94c1\u9b54\u6cd5\u548c\u5deb\u6cd5\u662f\u4e00\u4e2a\u7c7b\u7684\u300d\uff09\u2014\u2014 \u603b\u7c7b\u4fdd\u7559\uff0c\u7ec6\u5206\u65b0\u589e\u3002
        expect("\u2605\u2605 \u4f53\u7cfb\u5224\u5b9a\uff1acast_focus=GOETY / irons:cast_spell=IRONS / \u5176\u5b83=NONE",
                DirectiveFilter.spellSystemOf("goety:cast_focus") == DirectiveFilter.SpellSystem.GOETY
                        && DirectiveFilter.spellSystemOf("irons:cast_scroll")
                                == DirectiveFilter.SpellSystem.IRONS
                        && DirectiveFilter.spellSystemOf("irons:recast")
                                == DirectiveFilter.SpellSystem.IRONS
                        && DirectiveFilter.spellSystemOf("maid_native:melee_swing")
                                == DirectiveFilter.SpellSystem.NONE
                        && DirectiveFilter.spellSystemOf(null) == DirectiveFilter.SpellSystem.NONE, "");
        var sysTotal = new DirectiveBus();
        sysTotal.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605\u2605 magic_only\uff08\u603b\u7c7b\uff09\u4ecd\u7136\u4e24\u7cfb\u6cd5\u672f\u90fd\u653e\u884c",
                DirectiveFilter.allowed(sysTotal, "goety:cast_focus")
                        && DirectiveFilter.allowed(sysTotal, "irons:cast_spell"), "");
        expect("\u2605 magic_only \u4ecd\u7136\u6321\u4e0b\u975e\u6cd5\u672f\u52a8\u4f5c",
                !DirectiveFilter.allowed(sysTotal, "maid_native:melee_swing"), "");
        var sysIrons = new DirectiveBus();
        sysIrons.issue("irons_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605\u2605 irons_only\uff1a\u653e\u884c\u94c1\u9b54\u6cd5\u3001\u6321\u4e0b\u5deb\u6cd5",
                DirectiveFilter.allowed(sysIrons, "irons:cast_spell")
                        && DirectiveFilter.allowed(sysIrons, "irons:cast_scroll")
                        && DirectiveFilter.allowed(sysIrons, "irons:recast")
                        && !DirectiveFilter.allowed(sysIrons, "goety:cast_focus"), "");
        expect("\u2605\u2605 irons_only \u4e0e magic_only \u540c\u53e3\u5f84\uff1a\u975e\u65bd\u6cd5\u52a8\u4f5c\u4e5f\u6321\u4e0b",
                !DirectiveFilter.allowed(sysIrons, "maid_native:melee_swing")
                        && !DirectiveFilter.allowed(sysIrons, "tacz:reload")
                        && !DirectiveFilter.allowed(sysIrons, "goety:recall_servants"), "");
        var sysGoety = new DirectiveBus();
        sysGoety.issue("goety_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605\u2605 goety_only\uff1a\u653e\u884c\u5deb\u6cd5\u3001\u6321\u4e0b\u94c1\u9b54\u6cd5",
                DirectiveFilter.allowed(sysGoety, "goety:cast_focus")
                        && !DirectiveFilter.allowed(sysGoety, "irons:cast_spell")
                        && !DirectiveFilter.allowed(sysGoety, "irons:recast"), "");
        expect("\u2605\u2605 \u6cd5\u672f\u6c60\u540c\u53e3\u5f84\uff08\u7126\u70b9/\u6cd5\u672f\u4e24\u8fb9\u90fd\u8981\u6321\uff09",
                !DirectiveFilter.focusAllowed(sysIrons, DirectiveFilter.SpellSystem.GOETY,
                        "ATTACK_SINGLE", "FireballSpell")
                        && DirectiveFilter.focusAllowed(sysIrons, DirectiveFilter.SpellSystem.IRONS,
                                "ATTACK_SINGLE", "FireballSpell")
                        && !DirectiveFilter.focusAllowed(sysGoety, DirectiveFilter.SpellSystem.IRONS,
                                "ATTACK", "FireballSpell"), "");
        expect("\u2605 \u4e09\u53c2\u6570\u7248\u4e0d\u53d7\u4f53\u7cfb\u6307\u4ee4\u8bef\u4f24\uff08\u65e7\u8c03\u7528\u65b9\u53e3\u5f84\u4e0d\u53d8\uff09",
                DirectiveFilter.focusAllowed(sysIrons, "ATTACK_SINGLE", "FireballSpell"), "");
        var sysMutex = new DirectiveBus();
        sysMutex.issue("magic_only", Map.of(), Map.of(), 0, "llm", 0);
        sysMutex.issue("irons_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 \u603b\u7c7b\u4e0e\u7ec6\u5206\u4e92\u65a5\uff1a\u4e0b irons_only \u4f1a\u628a magic_only \u9876\u6389",
                !sysMutex.has("magic_only") && sysMutex.has("irons_only"), "");
        var sysMutex2 = new DirectiveBus();
        sysMutex2.issue("irons_only", Map.of(), Map.of(), 0, "llm", 0);
        sysMutex2.issue("goety_only", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605\u2605 \u4e24\u6761\u7ec6\u5206\u4e4b\u95f4\u4e5f\u4e92\u65a5\uff08goety_only \u9876\u6389 irons_only\uff09",
                !sysMutex2.has("irons_only") && sysMutex2.has("goety_only"), "");
        expect("\u2605 \u7ec6\u5206\u6307\u4ee4\u5728 LLM \u6e05\u5355\u91cc\uff08\u63d0\u793a\u8bcd\u81ea\u52a8\u5305\u542b\uff09",
                DirectiveSpec.describeForLlm().contains("irons_only")
                        && DirectiveSpec.describeForLlm().contains("goety_only"), "");

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u4e00\u8f6e\uff08\u59d4\u6258\u65b9\u7b2c 2 \u6761\uff09\uff1a**\u81ea\u52a8\u6307\u6325\u901a\u9053\u4e0d\u8bb8\u81ea\u4f5c\u4e3b\u5f20\u7981\u53ec\u5524/\u6e05\u4ec6\u4ece**\u3002
        //   \u5224\u636e\u5728\u7eaf\u903b\u8f91\u5c42\uff0c\u6267\u884c\u8005\u662f DirectiveHolder#issue\uff08\u53e3\u5f84\u53ea\u6709\u4e00\u5904\uff09\u3002
        expect("\u2605\u2605 dismiss_servants / no_summons \u88ab\u6807\u8bb0\u4e3a\u300c\u53ea\u80fd\u7531\u4e3b\u4eba\u4e0b\u8fbe\u300d",
                DirectiveSpec.playerOnlyReason("dismiss_servants") != null
                        && DirectiveSpec.playerOnlyReason("no_summons") != null, "");
        expect("\u2605 \u53ec\u96c6\u4ec6\u4ece\uff08recall_servants\uff09\u4e0d\u5728\u5176\u4e2d \u2014\u2014 \u5b83\u4e0d\u662f\u7834\u574f\u6027\u52a8\u4f5c",
                DirectiveSpec.playerOnlyReason("recall_servants") == null
                        && DirectiveSpec.playerOnlyReason("disengage") == null
                        && DirectiveSpec.playerOnlyReason(null) == null, "");
        expect("\u2605\u2605 \u4e24\u6761\u300c\u53ea\u80fd\u7531\u4e3b\u4eba\u4e0b\u8fbe\u300d\u7684\u6307\u4ee4\u786e\u5b9e\u5b58\u5728\uff08\u9632\u6b62\u6539\u540d\u540e\u5224\u636e\u60ac\u7a7a\uff09",
                DirectiveSpec.playerOnlyIds().stream().allMatch(DirectiveSpec::exists), "");

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u4e00\u8f6e\uff1a\u7ed9\u8bca\u65ad\u65e5\u5fd7\u88c5\u8282\u6d41\u9600\uff08\u4e0d\u7136"\u6bcf tick \u5224\u5b9a\u4e00\u6b21"\u7684\u65e5\u5fd7\u4f1a\u628a\u65e5\u5fd7\u5237\u7206\uff09
        var throttle = new com.touhoulittlemad.fightlikeplayer.decision.LogThrottle(200);
        expect("\u2605\u2605 \u7a97\u53e3\u5185\u540c\u4e00 key \u53ea\u653e\u884c\u4e00\u6b21\uff08\u7b2c\u4e8c\u5341\u4e00\u6b21\u8c03\u7528\u88ab\u6321\uff09",
                throttle.should("maid|FireballSpell", 0)
                        && !throttle.should("maid|FireballSpell", 1)
                        && !throttle.should("maid|FireballSpell", 199)
                        && throttle.should("maid|FireballSpell", 200), "");
        expect("\u2605\u2605 \u4e0d\u540c key \u4e92\u4e0d\u5f71\u54cd\uff08\u4e00\u9897\u6cd5\u672f\u88ab\u6321\u4e0d\u5f71\u54cd\u53e6\u4e00\u9897\u7684\u8bb0\u5f55\uff09",
                throttle.should("maid|IceSpell", 201)
                        && !throttle.should("maid|IceSpell", 202)
                        && throttle.should("maid|FireballSpell", 401), "");
        expect("\u2605 \u7a7a key \u4e00\u5f8b\u653e\u884c\uff08\u8ba4\u4e0d\u51fa\u8c03\u7528\u65b9\u65f6\u5b81\u53ef\u591a\u8bb0\uff0c\u4e0d\u9759\u9ed8\uff09",
                throttle.should("", 402) && throttle.should(null, 402), "");
        var prune = new com.touhoulittlemad.fightlikeplayer.decision.LogThrottle(10);
        for (int i = 0; i < 600; i++) {
            prune.should("k" + i, i * 100L);          // \u6bcf\u4e2a key \u90fd\u4e0d\u5728\u522b\u4eba\u7684\u7a97\u53e3\u91cc
        }
        expect("\u2605\u2605 \u8868\u4f1a\u81ea\u6211\u4fee\u526a\uff08\u8bca\u65ad\u8bbe\u65bd\u4e0d\u8bb8\u53d8\u6210\u5185\u5b58\u6cc4\u6f0f\uff09", prune.size() < 600,
                String.valueOf(prune.size()));

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u4e8c\u8f6e\uff1a**\u5979\u81ea\u5df1\u7684\u4e1c\u897f\u6c38\u8fdc\u4e0d\u7b97\u654c\u4eba**\uff08\u59d4\u6258\u65b9\u5b9e\u6d4b\uff1a\u94c1\u9b54\u6cd5\u53ec\u5524\u7269\u4f1a\u7d22\u654c\u5973\u4ec6\uff09
        expect("\u2605\u2605\u2605 \u5224\u636e\uff1a\u56db\u79cd\u300c\u81ea\u5bb6\u300d\u60c5\u51b5\u90fd\u88ab\u8ba4\u51fa\u6765\uff0c\u4e14\u5404\u6709\u53ef\u8bfb\u7406\u7531",
                com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                        .friendlyReason(true, false, false, false) != null
                        && com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                                .friendlyReason(false, true, false, false) != null
                        && com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                                .friendlyReason(false, false, true, false) != null
                        && com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                                .friendlyReason(false, false, false, true) != null, "");
        expect("\u2605\u2605 \u666e\u901a\u654c\u4eba\uff08\u56db\u6761\u90fd\u4e0d\u6210\u7acb\uff09\u21d2 \u53ef\u4ee5\u5f53\u654c\u4eba\uff08\u4e0d\u8bb8\u628a\u6240\u6709\u4e1c\u897f\u90fd\u5f53\u53cb\u519b\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                        .friendlyReason(false, false, false, false) == null, "");
        expect("\u2605 \u7406\u7531\u53ef\u8bfb\uff08\u8bca\u65ad\u51fa\u53e3\u76f4\u63a5\u7528\u8fd9\u53e5\u8bdd\uff1b\u7a7a\u5b57\u7b26\u4e32\u7b49\u4e8e\u6ca1\u6709\u51fa\u53e3\uff09",
                !com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                        .friendlyReason(false, false, false, true).isBlank(), "");
        expect("\u2605 isFriendly \u4e0e friendlyReason \u540c\u53e3\u5f84\uff08\u4e24\u4e2a\u51fa\u53e3\u4e0d\u8bb8\u6f02\u79fb\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                        .isFriendly(false, false, true, false)
                        == (com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                                .friendlyReason(false, false, true, false) != null)
                        && !com.touhoulittlemad.fightlikeplayer.decision.FactionRules
                                .isFriendly(false, false, false, false), "");

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u56db\u8f6e\uff1aTickMemo\uff08\u6bcf tick \u95ee\u3001\u4f46\u7b54\u6848\u4e0d\u5e38\u53d8\u7684\u4e1c\u897f\u7684 TTL \u7f13\u5b58\uff09
        var memo = new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<String, Integer>();
        int[] calls = {0};
        java.util.function.Supplier<Integer> slow = () -> ++calls[0];
        expect("\u2605\u2605 \u7a97\u53e3\u5185\u53ea\u7b97\u4e00\u6b21\uff08\u7b2c\u4e8c\u6b21\u76f4\u63a5\u62ff\u7f13\u5b58\uff09",
                memo.get("k", 0L, 10, slow) == 1 && memo.get("k", 5L, 10, slow) == 1
                        && calls[0] == 1, String.valueOf(calls[0]));
        expect("\u2605\u2605 \u8fc7\u4e86 TTL \u624d\u91cd\u7b97", memo.get("k", 10L, 10, slow) == 2 && calls[0] == 2,
                String.valueOf(calls[0]));
        expect("\u2605 \u4e0d\u540c key \u4e92\u4e0d\u5f71\u54cd\uff08\u7f13\u5b58\u662f\u6309 key \u5206\u5f00\u7684\uff09",
                memo.get("other", 11L, 10, slow) == 3 && memo.get("k", 15L, 10, slow) == 2,
                String.valueOf(calls[0]));
        expect("\u2605 ttl<=0 \u21d2 \u4e0d\u7f13\u5b58\uff08\u6bcf\u6b21\u90fd\u7b97 \u2014\u2014 \u7528\u6765\u8868\u8fbe\u300c\u8fd9\u91cc\u4e0d\u8be5\u7f13\u5b58\u300d\uff09",
                memo.get("k", 16L, 0, slow) == 4 && memo.get("k", 16L, 0, slow) == 5, "");
        memo.invalidate("k");
        int before = calls[0];
        expect("\u2605\u2605 \u4e3b\u52a8\u5931\u6548\u80fd\u7acb\u523b\u91cd\u7b97\uff08\u5979\u6362\u4e86\u88c5\u5907\u90a3\u4e00\u523b\uff09",
                memo.get("k", 17L, 1000L, slow) == before + 1, "");
        expect("\u2605\u2605 \u5931\u6548\u540e\u91cd\u65b0\u7b97\u51fa\u6765\u7684\u503c\u4f1a\u88ab\u8bb0\u4f4f\uff08\u4e0d\u662f\u6bcf\u6b21\u90fd\u7b97\uff09",
                memo.get("k", 18L, 1000L, slow) == before + 1 && calls[0] == before + 1,
                String.valueOf(calls[0]));

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u4e94\u8f6e\uff1a\u7269\u54c1\u5206\u7c7b\u8868\uff08\u59d4\u6258\u65b9\u7b2c 2 \u6761\uff1a\u6309\u7528\u9014\u5206\u7c7b\uff09
        expect("\u2605\u2605\u2605 \u4e09\u4e2a\u67aa\u5305\u5171\u7528\u4e00\u7c7b\uff08\u5bf9\u6a21\u578b\u6765\u8bf4\u300c\u600e\u4e48\u7528\u5b83\u300d\u624d\u662f\u5206\u7c7b\u610f\u4e49\uff09",
                "gun".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                        .fromCarrier("tacz:gun"))
                        && "gun".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .fromCarrier("superbwarfare:gun"))
                        && "gun".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .fromCarrier("pillagersgun:gun")), "");
        expect("\u2605\u2605 \u8f7d\u4f53 id \u76f4\u63a5\u5f53\u7c7b\u522b\uff08\u62d4\u5200\u5251/\u9b54\u6756/\u6cd5\u672f\u4e66\u5404\u81ea\u6210\u7c7b\uff0c\u4e0d\u8bb8\u6389\u8fdb\u300c\u5176\u5b83\u300d\uff09",
                !"other".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                        .fromCarrier("slashblade:blade"))
                        && !"other".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .fromCarrier("goety:wand_with_focus"))
                        && !"other".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .fromCarrier("irons:spellbook_or_scroll")), "");
        expect("\u2605 \u8ba4\u4e0d\u51fa\u7684\u8f7d\u4f53 \u21d2 \u843d\u5230\u300c\u5176\u5b83\u300d\uff08\u4e0d\u8bb8\u51ed\u7a7a\u9020\u7c7b\u522b\uff09",
                "other".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                        .fromCarrier("\u8c01:\u4e5f\u4e0d\u662f"))
                        && "other".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .fromCarrier(null)), "");
        expect("\u2605\u2605 \u67aa\u6392\u5728\u6700\u524d\uff08\u6700\u5bb9\u6613\u56e0\u4e3a\u770b\u4e0d\u5230\u800c\u731c\u9519\uff09\uff0c\u5f39\u836f\u7d27\u968f\u5176\u540e\uff0c\u5176\u5b83\u5728\u6700\u540e",
                com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                        .orderOf(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups.KEY_GUN) == 0
                        && com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .orderOf(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                        .KEY_AMMO) == 1
                        && com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .orderOf(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                        .KEY_OTHER)
                                > com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                        .orderOf("slashblade:blade"), "");
        expect("\u2605 \u6bcf\u4e2a\u7c7b\u522b\u90fd\u6709\u4e2d\u6587\u540d\uff08\u7a7a\u540d = \u6a21\u578b\u770b\u5230\u4e00\u884c\u6ca1\u6709\u6807\u9898\u7684\u4e1c\u897f\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.ItemGroups.keysInOrder().stream()
                        .noneMatch(k -> com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .nameOf(k).isBlank()), "");
        expect("\u2605 \u5f39\u836f\u4e0e\u5f39\u836f\u76d2\u662f\u4e24\u4e2a\u7c7b\u522b\uff08\u5408\u8d77\u6765\u4f1a\u8ba9\u6a21\u578b\u5206\u4e0d\u6e05\u80fd\u76f4\u63a5\u88c5\u586b\u7684\u4e0e\u76d2\u5b50\u91cc\u7684\uff09",
                !com.touhoulittlemad.fightlikeplayer.decision.ItemGroups.KEY_AMMO
                        .equals(com.touhoulittlemad.fightlikeplayer.decision.ItemGroups
                                .KEY_AMMO_BOX), "");

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u516d\u8f6e\uff1a\u62d4\u5200\u5251\u7684"\u8eab\u4efd id"\uff08\u4e0e\u67aa\u540c\u578b\uff1a\u6240\u6709\u5177\u540d\u5200\u5171\u7528\u4e00\u4e2a\u6ce8\u518c\u540d\uff09
        expect("\u2605\u2605\u2605 \u7ffb\u8bd1\u952e \u2192 \u8eab\u4efd id\uff08Resharped \u7684\u5177\u540d\u5200\u8eab\u4efd\u5c31\u5728\u8fd9\u4e2a\u952e\u91cc\uff09",
                "slashblade:yamato".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                        .fromTranslationKey("item.slashblade.yamato"))
                        && "slashblade:slashblade_wood".equals(
                                com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                                        .fromTranslationKey("item.slashblade.slashblade_wood")), "");
        expect("\u2605\u2605 \u8ba4\u4e0d\u51fa\u7684\u952e \u21d2 null\uff08\u4e0d\u731c\uff1b\u8c03\u7528\u65b9\u9000\u56de\u6ce8\u518c\u540d\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                        .fromTranslationKey(null) == null
                        && com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                                .fromTranslationKey("") == null
                        && com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                                .fromTranslationKey("item.slashblade") == null
                        && com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                                .fromTranslationKey("item.") == null, "");
        expect("\u2605 \u5df2\u7ecf\u662f id \u7684\u5199\u6cd5\u539f\u6837\u8fd4\u56de\uff08\u6709\u7684\u9644\u5c5e\u76f4\u63a5\u5199 id\uff09",
                "slashblade:yamato".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                        .fromTranslationKey("slashblade:yamato")), "");
        expect("\u2605\u2605 \u8eab\u4efd id \u7684\u7b80\u5199\uff08\u522b\u540d\u7528\uff1a\u6a21\u578b\u53ef\u80fd\u53ea\u8bf4\u300c\u960e\u9b54\u5200\u300d/yamato\uff09",
                "yamato".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                        .pathOf("slashblade:yamato"))
                        && "ying".equals(com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                                .pathOf("ying"))
                        && com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                                .pathOf(null) == null, "");

        // \u2605\u2605 \u7b2c\u4e8c\u5341\u4e03\u8f6e\uff1a`only_focus`\uff08\u53ea\u7528\u67d0\u4e00\u9897\u6cd5\u672f/\u805a\u6676\uff09\u2014\u2014 \u4e0e ban_focus \u540c\u4e00\u53c2\u6570\u53e3\u5f84
        var of = new DirectiveBus();
        of.issue("only_focus", Map.of(), Map.of("label", "VanguardSpell"), 0, "llm", 0);
        expect("\u2605\u2605\u2605 only_focus\uff1a\u653e\u884c\u90a3\u4e00\u9897\u3001\u6321\u4e0b\u522b\u7684\u6cd5\u672f\uff08\u542b\u540c\u4e00\u65cf\u52a8\u4f5c\u91cc\u7684\u5176\u5b83\u6cd5\u672f\uff09",
                DirectiveFilter.focusAllowed(of, "SUMMON", "VanguardSpell")
                        && !DirectiveFilter.focusAllowed(of, "ATTACK_SINGLE", "FireballSpell")
                        && !DirectiveFilter.focusAllowed(of, "HEALING", "SoulHealSpell"), "");
        expect("\u2605\u2605 only_focus \u4e0e magic_only \u540c\u53e3\u5f84\uff1a\u975e\u6cd5\u672f\u52a8\u4f5c\u4e00\u5e76\u6321\u4e0b",
                !DirectiveFilter.allowed(of, "maid_native:melee_swing")
                        && !DirectiveFilter.allowed(of, "tacz:shoot")
                        && !DirectiveFilter.allowed(of, "tacz:reload")
                        && DirectiveFilter.allowed(of, "goety:cast_focus")
                        && DirectiveFilter.allowed(of, "irons:cast_spell"), "");
        expect("\u2605\u2605 \u7cbe\u786e\u6bd4\u8f83\uff08\u4e0d\u8bb8\u7528\u522b\u540d\u5f0f\u7684\u5bbd\u677e\u5339\u914d \u21d2 \u300c\u5148\u950b\u300d\u4e0d\u8be5\u547d\u4e2d\u522b\u7684\u805a\u6676\uff09",
                !DirectiveFilter.focusAllowed(of, "SUMMON", "VanguardSpellEx")
                        && !DirectiveFilter.focusAllowed(of, "SUMMON", "vanguardspell"), "");
        var ofNoLabel = new DirectiveBus();
        ofNoLabel.issue("only_focus", Map.of(), Map.of(), 0, "llm", 0);
        expect("\u2605 \u6ca1\u5e26 label \u21d2 \u4e0d\u8fc7\u6ee4\uff08\u62a4\u680f\uff1a\u5b81\u53ef\u653e\u884c\u4e5f\u4e0d\u8981\u8ba9\u5979\u7ad9\u7740\u4e0d\u52a8\uff09",
                DirectiveFilter.focusAllowed(ofNoLabel, "SUMMON", "VanguardSpell"), "");
        expect("\u2605\u2605 only_focus \u5728\u6307\u4ee4\u8868\u91cc\u3001\u4e14\u58f0\u660e\u4e86 needs\uff08\u6a21\u578b\u770b\u5f97\u89c1\u3001\u6709\u4f9d\u636e\uff09",
                DirectiveSpec.exists("only_focus")
                        && DirectiveSpec.declaredNeeds().contains("only_focus")
                        && DirectiveSpec.describeForLlm().contains("only_focus"), "");
        expect("\u2605 only_focus \u4e0e ban_focus \u7684\u53c2\u6570\u540d\u4e00\u81f4\uff08\u90fd\u662f label \u21d2 \u5b66\u4e60\u6210\u672c\u4e3a\u96f6\uff09",
                DirectiveSpec.paramsOf("only_focus").keySet().equals(
                        DirectiveSpec.paramsOf("ban_focus").keySet()), "");

        // \u2605\u2605\u2605 \u7b2c\u4e8c\u5341\u516b\u8f6e\uff1a\u6307\u4ee4**\u7ec4\u5408**\u51b2\u7a81\u65f6\u8c01\u8ba9\u4f4d\uff08\u59d4\u6258\u65b9\u5b9e\u6d4b\u300c\u67aa\u68b0\u7528\u4e0d\u51fa\u6765\u300d\uff09
        java.util.Map<String, String> clash = new java.util.LinkedHashMap<>();
        clash.put("only_item", "chat");            // \u4e3b\u4eba\uff1a\u53ea\u7528\u90a3\u628a\u67aa
        clash.put("close_in", "llm");              // \u6307\u6325\u5b98\uff1a\u8d34\u8eab\uff08\u7981\u8fdc\u7a0b\uff09
        expect("\u2605\u2605\u2605 \u7ec4\u5408\u51b2\u7a81\uff1a\u64a4\u6389\u6307\u6325\u5b98\u90a3\u6761\uff08\u4e3b\u4eba\u7684\u8bdd\u4f18\u5148\uff09",
                "close_in".equals(com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                        .pickYielding(clash)), "");
        java.util.Map<String, String> stanceFirst = new java.util.LinkedHashMap<>();
        stanceFirst.put("only_item", "chat");
        stanceFirst.put("close_in", "llm");
        stanceFirst.put("melee_only", "llm");
        expect("\u2605\u2605 \u5148\u64a4\u300c\u7ad9\u4f4d\u7ea6\u675f\u300d\u3001\u518d\u64a4\u300c\u624b\u6bb5\u9650\u5236\u300d\uff08\u7ad9\u4f4d\u6700\u4e0d\u8868\u8fbe\u4e3b\u4eba\u7684\u610f\u601d\uff09",
                "close_in".equals(com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                        .pickYielding(stanceFirst)), "");
        java.util.Map<String, String> meansOnly = new java.util.LinkedHashMap<>();
        meansOnly.put("only_item", "chat");
        meansOnly.put("ranged_only", "llm");
        expect("\u2605\u2605 \u6ca1\u6709\u7ad9\u4f4d\u7c7b\u53ef\u64a4\u65f6\uff0c\u9000\u800c\u64a4\u6307\u6325\u5b98\u7684\u624b\u6bb5\u9650\u5236",
                "ranged_only".equals(com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                        .pickYielding(meansOnly)), "");
        java.util.Map<String, String> allOwner = new java.util.LinkedHashMap<>();
        allOwner.put("only_item", "chat");
        allOwner.put("close_in", "chat");
        expect("\u2605\u2605\u2605 \u5168\u662f\u4e3b\u4eba\u81ea\u5df1\u4e0b\u7684 \u21d2 \u4e00\u6761\u90fd\u4e0d\u81ea\u52a8\u64a4\uff08\u53ea\u62a5\u544a\uff0c\u8ba9\u4e3b\u4eba\u51b3\u5b9a\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                        .pickYielding(allOwner) == null, "");
        expect("\u2605\u2605 \u4e3b\u4eba\u7684\u300c\u6307\u540d\u9053\u59d3\u300d\uff08only_item / only_focus\uff09\u6c38\u4e0d\u8ba9\u4f4d",
                com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                        .isOwnerSpecific("only_item")
                        && com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                                .isOwnerSpecific("only_focus")
                        && !com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                                .isOwnerSpecific("close_in"), "");
        expect("\u2605 \u6765\u6e90\u4f18\u5148\u7ea7\uff1a\u4e3b\u4eba(chat/player) > \u6307\u6325\u5b98(llm) > \u81ea\u52a8",
                com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                        .sourceRank("chat") == 0
                        && com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                                .sourceRank("player") == 0
                        && com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                                .sourceRank("llm") == 1
                        && com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                                .sourceRank("auto") == 2, "");

        var empty = new DirectiveBus();
        expect("\u2605 \u6ca1\u6709\u6307\u4ee4\u65f6\u5168\u90e8\u653e\u884c",
                DirectiveFilter.allowed(empty, "maid_native:melee_swing")
                        && DirectiveFilter.allowed(empty, "goety:cast_focus"), "");

        // filter() \u540c\u65f6\u8bb0\u4e0b\u88ab\u6321\u7684
        var dropped = new ArrayList<String>();
        var kept = DirectiveFilter.filter(bus, List.of("goety:cast_focus",
                "maid_native:melee_swing", "tacz:shoot"), dropped);
        expect("\u2605\u2605 filter() \u53ea\u7559\u5408\u6cd5\u7684\uff0c\u5e76\u628a\u6321\u4e0b\u7684\u8bb0\u8fdb\u6e05\u5355\uff08\u53ef\u89c1\uff09",
                kept.equals(List.of("goety:cast_focus")) && dropped.size() == 2
                        && dropped.contains("maid_native:melee_swing"),
                kept + " / " + dropped);
    }

    // ───────────── 6. \u6b65\u6cd5\u7ea6\u675f ─────────────
    private static void testGaitConstraint() {
        section("6. \u6b65\u6cd5\u7ea6\u675f\uff1a\u4ece\u6307\u4ee4\u63d0\u53d6 min/max/no_retreat/radius");

        var none = DirectiveFilter.gaitConstraint(new DirectiveBus());
        expect("\u2605 \u6ca1\u6307\u4ee4 \u21d2 \u4e0d\u6fc0\u6d3b", !none.active(), "");

        var b1 = new DirectiveBus();
        b1.issue("keep_distance", Map.of("min", 9.0), Map.of(), 0, "llm", 0);
        var k1 = DirectiveFilter.gaitConstraint(b1);
        expect("\u2605\u2605 keep_distance \u21d2 minDistance=9 \u4e14\u6fc0\u6d3b",
                k1.active() && k1.minDistance() == 9.0, k1.toString());

        var b2 = new DirectiveBus();
        b2.issue("close_in", Map.of("max", 2.0), Map.of(), 0, "llm", 0);
        var k2 = DirectiveFilter.gaitConstraint(b2);
        expect("\u2605 close_in \u21d2 maxDistance=2", k2.active() && k2.maxDistance() == 2.0, k2.toString());

        var b3 = new DirectiveBus();
        b3.issue("no_retreat", Map.of(), Map.of(), 0, "llm", 0);
        var k3 = DirectiveFilter.gaitConstraint(b3);
        expect("\u2605 no_retreat \u21d2 noRetreat=true", k3.active() && k3.noRetreat(), k3.toString());

        var b4 = new DirectiveBus();
        b4.issue("hold_position", Map.of("radius", 6.0), Map.of(), 0, "llm", 0);
        var k4 = DirectiveFilter.gaitConstraint(b4);
        expect("\u2605 hold_position \u21d2 holdRadius=6", k4.holds() && k4.holdRadius() == 6.0, k4.toString());
    }

    // ───────────── 6. ★★ 战斗场景：指令的「依据」 ─────────────

    /**
     * ★★ <b>委托方第 14 轮核实问题的判据</b>：
     * 「每个指令的下达所需要的信息，llm 都能获取到相关内容吗？」
     *
     * <p>这组断言把那次核实<b>钉死</b>：以后再加一条指令，
     * 要么声明它依赖的事实且那些事实真的会被产出，要么<b>自测直接失败</b>。
     */
    private static void testSceneFacts() {
        section("6. \u2605\u2605 \u6218\u6597\u573a\u666f\uff1a\u6bcf\u6761\u6307\u4ee4\u7684\u4f9d\u636e\u90fd\u771f\u5b9e\u5b58\u5728");

        // ① 每条指令都必须声明 needs —— 忘了写 ⇒ 这里失败（不会"悄悄缺一路"）
        List<String> noNeeds = new ArrayList<>();
        for (var s : DirectiveSpec.all()) {
            if (!DirectiveSpec.declaredNeeds().contains(s.id())) {
                noNeeds.add(s.id());
            }
        }
        expect("\u2605\u2605 \u6bcf\u6761\u6307\u4ee4\u90fd\u58f0\u660e\u4e86\u5b83\u4f9d\u8d56\u7684\u4e8b\u5b9e",
                noNeeds.isEmpty(), String.join(",", noNeeds));

        // ② 声明的键必须真实存在于权威清单
        List<String> bad = new ArrayList<>();
        for (var s : DirectiveSpec.all()) {
            for (String k : s.needs()) {
                if (!CombatScene.KEYS.contains(k)) {
                    bad.add(s.id() + "->" + k);
                }
            }
        }
        expect("\u2605\u2605 \u58f0\u660e\u7684\u952e\u5168\u90e8\u5728 CombatScene.KEYS \u91cc",
                bad.isEmpty(), String.join(",", bad));

        // ③ 场景真的把每个键都产出来了（键名写错 / 漏产出 ⇒ 这里失败）
        var scene = CombatScene.empty();
        var produced = flatten(scene.facts());
        List<String> missing = new ArrayList<>();
        for (String k : CombatScene.KEYS) {
            if (!produced.contains(k)) {
                missing.add(k);
            }
        }
        expect("\u2605\u2605 CombatScene \u771f\u7684\u4ea7\u51fa\u4e86 KEYS \u91cc\u7684\u6bcf\u4e00\u4e2a\u952e",
                missing.isEmpty(), String.join(",", missing));

        // ④ 反向：产出的键一个都不多（多出来的 = 没人登记的野字段）
        List<String> extra = new ArrayList<>();
        for (String k : produced) {
            if (!CombatScene.KEYS.contains(k)) {
                extra.add(k);
            }
        }
        expect("\u2605 \u6ca1\u6709\u672a\u767b\u8bb0\u7684\u591a\u4f59\u5b57\u6bb5\uff08KEYS \u662f\u6743\u5a01\u6e05\u5355\uff09",
                extra.isEmpty(), String.join(",", extra));

        // ⑤ 端到端：那些键真的出现在【要发给模型的 JSON】里
        String user = com.touhoulittlemad.fightlikeplayer.decision.thinking.LlmAdvisor
                .userContent(null, TuningBus.global(), null, "[]", scene);
        expect("\u2605\u2605 scene \u771f\u7684\u8fdb\u4e86 user JSON",
                user.contains("\"scene\""), "");
        List<String> notInPrompt = new ArrayList<>();
        for (String k : CombatScene.KEYS) {
            String leaf = k.contains(".") ? k.substring(k.indexOf('.') + 1) : k;
            if (!user.contains("\"" + leaf + "\"")) {
                notInPrompt.add(k);
            }
        }
        expect("\u2605 KEYS \u7684\u6bcf\u4e2a\u952e\u540d\u90fd\u80fd\u5728\u90a3\u6bb5 JSON \u91cc\u641c\u5230",
                notInPrompt.isEmpty(), String.join(",", notInPrompt));

        // ⑥ 被"她自己的指令"挡住的法术要如实标记 ——
        //    否则模型看到池子里少了东西，会以为"她本来就没这法术"，把自己刚下的指令当成事实
        var marked = CombatScene.of(1, false, null, "", 5, "minecraft:iron_sword", "empty", 3,
                true, true, false, false, false, false, false,
                List.of(new CombatScene.SpellOption("FireballSpell", "ATTACK_SINGLE", "WAND", true),
                        new CombatScene.SpellOption("HealSpell", "HEALING", "BAG", false)),
                0, 1, 2, true, 1, 6, 0.5,
                List.of(new CombatScene.ItemLine("minecraft:iron_sword", 1, "MAINHAND"),
                        new CombatScene.ItemLine("goety:wand", 1, "INVENTORY")),
                0, "minecraft:zombie", "11111111-2222-3333-4444-555555555555",
                List.of("slashblade:slash_art", "slashblade:summoned_sword", "goety:cast_focus"), 0);
        String markedJson = com.touhoulittlemad.fightlikeplayer.decision.thinking.LlmAdvisor
                .userContent(null, TuningBus.global(), null, "[]", marked);
        expect("\u2605\u2605 \u88ab\u81ea\u5df1\u6307\u4ee4\u6321\u4f4f\u7684\u6cd5\u672f\u5e26 blocked_by_your_directive \u6807\u8bb0",
                markedJson.contains("blocked_by_your_directive"), markedJson);
        // ★★ 第 15 轮：半命题指令的**参数来源**必须在场景里（否则模型填不出参数）
        expect("\u2605\u2605 scene \u91cc\u6709\u7269\u54c1\u6e05\u5355\uff08only_item/ban_item \u7684\u53c2\u6570\u6765\u6e90\uff09",
                markedJson.contains("minecraft:iron_sword") && markedJson.contains("\"items\""),
                markedJson);
        expect("\u2605\u2605 scene \u91cc\u6709\u76ee\u6807\u7684\u79cd\u7c7b\u4e0e UUID\uff08focus_entity/focus_one \u7684\u53c2\u6570\u6765\u6e90\uff09",
                markedJson.contains("minecraft:zombie")
                        && markedJson.contains("11111111-2222-3333-4444-555555555555"), "");

        // ⑦ 指令表里带 base_on（模型才知道"下这条之前先看哪些字段"）
        String table = DirectiveSpec.describeForLlm();
        expect("\u2605\u2605 \u6307\u4ee4\u8868\u91cc\u5e26 base_on", table.contains("\"base_on\""), "");
        expect("\u2605 keep_distance \u7684\u4f9d\u636e\u542b self.distance_to_target",
                table.contains("self.distance_to_target"), "");
        expect("\u2605\u2605 magic_only \u7684\u4f9d\u636e\u542b spells_available",
                table.contains("spells_available"), "");

        // ⑧ 铁魔法粗类别别名（第 14 轮补的漏洞：指令此前管不住铁魔法）
        expect("\u2605\u2605 \u7c7b\u522b\u522b\u540d ATTACK -> 0",
                DirectiveFilter.categoryIndex("ATTACK") == 0,
                String.valueOf(DirectiveFilter.categoryIndex("ATTACK")));
        expect("\u2605\u2605 \u7c7b\u522b\u522b\u540d SUPPORT -> 5",
                DirectiveFilter.categoryIndex("SUPPORT") == 5,
                String.valueOf(DirectiveFilter.categoryIndex("SUPPORT")));
        expect("\u2605 SUMMON -> 2\uff08\u4e24\u4e2a\u5e73\u53f0\u540c\u540d\uff0c\u843d\u540c\u4e00\u6863\uff09",
                DirectiveFilter.categoryIndex("SUMMON") == 2,
                String.valueOf(DirectiveFilter.categoryIndex("SUMMON")));
        expect("\u2605 \u672a\u77e5\u7c7b\u522b -> -1\uff08\u4e0d\u8bef\u5c01\u5979\uff09",
                DirectiveFilter.categoryIndex("WHATEVER") == -1, "");
    }

    // ───────────── 7. ★★ 对话通道：四字段够不够表达 26 条指令 ─────────────

    /**
     * ★★ <b>M5 的判据</b>：女仆对话模型用的是一个 {@code ITool}，
     * 它的参数表是<b>固定字段</b>（action / directive / value / text）。
     *
     * <p>⇒ 这里断言那个前提<b>对每一条指令都成立</b>：
     * 四字段足以表达全部 21 条指令的参数。
     * ★ 将来谁加了一条"两个数字参数"的指令，这里<b>当场失败</b>，
     * 而不是在游戏里静默丢掉一个参数（docs/13：可预期的失败要有可读出口）。
     */
    private static void testChatChannel() {
        section("7. \u2605\u2605 \u5bf9\u8bdd\u901a\u9053\uff1a\u56db\u5b57\u6bb5\u591f\u4e0d\u591f\u8868\u8fbe 21 \u6761\u6307\u4ee4");

        System.out.println("   参数最多的那条指令有 " + DirectiveSpec.maxNumericParamCount()
                + " 个数字 + " + DirectiveSpec.maxTextParamCount() + " 个文本");
        expect("\u2605\u2605 \u6bcf\u6761\u6307\u4ee4\u6700\u591a 1 \u4e2a\u6570\u5b57 + 1 \u4e2a\u6587\u672c\uff08\u5bf9\u8bdd\u901a\u9053\u7684\u5bb9\u91cf\uff09",
                DirectiveSpec.maxNumericParamCount() <= 1 && DirectiveSpec.maxTextParamCount() <= 1,
                DirectiveSpec.maxNumericParamCount() + "/" + DirectiveSpec.maxTextParamCount());

        List<String> notExpressible = new ArrayList<>();
        for (var s : DirectiveSpec.all()) {
            if (!DirectiveSpec.translate(s.id(), 1.0, "x").ok()) {
                notExpressible.add(s.id() + "(" + DirectiveSpec.translate(s.id(), 1.0, "x").error() + ")");
            }
        }
        expect("\u2605\u2605 \u6bcf\u4e00\u6761\u6307\u4ee4\u90fd\u80fd\u7528\u3010\u4e00\u4e2a\u6570\u5b57 + \u4e00\u4e2a\u6587\u672c\u3011\u8868\u8fbe",
                notExpressible.isEmpty(), String.join(",", notExpressible));

        var km = DirectiveSpec.translate("keep_distance", 9.0, null);
        expect("\u2605 \u6570\u5b57\u843d\u5230\u5b83\u90a3\u4e00\u4e2a\u53c2\u6570\u4e0a\uff08keep_distance.min = 9\uff09",
                km.ok() && Double.valueOf(9.0).equals(km.params().get("min")), km.toString());

        var bf = DirectiveSpec.translate("ban_focus", null, "FireballSpell");
        expect("\u2605 \u6587\u672c\u843d\u5230\u5b83\u90a3\u4e00\u4e2a\u53c2\u6570\u4e0a\uff08ban_focus.label\uff09",
                bf.ok() && "FireballSpell".equals(bf.texts().get("label")), bf.toString());

        var omitted = DirectiveSpec.translate("hold_position", null, null);
        expect("\u2605 \u4e24\u4e2a\u90fd\u6ca1\u7ed9 \u21d2 \u7a7a\u8868\uff08\u7531\u603b\u7ebf\u586b\u9ed8\u8ba4\u503c\uff0c\u4e0d\u5728\u8fd9\u91cc\u732e\u731c\uff09",
                omitted.ok() && omitted.params().isEmpty() && omitted.texts().isEmpty(),
                omitted.toString());

        var ignore = DirectiveSpec.translate("interrupt", 5.0, "x");
        expect("\u2605 \u65e0\u53c2\u6570\u7684\u6307\u4ee4\u5ffd\u7565\u591a\u4f59\u7684 value/text",
                ignore.ok() && ignore.params().isEmpty() && ignore.texts().isEmpty(),
                ignore.toString());

        var bad = DirectiveSpec.translate("no_such_directive", 1.0, null);
        expect("\u2605\u2605 \u672a\u77e5 id \u21d2 \u62a5\u9519\u4e14\u539f\u56e0\u53ef\u8bfb\uff08\u4e0d\u9ed8\u9ed8\u4e22\uff09",
                !bad.ok() && bad.error() != null && !bad.error().isBlank(), String.valueOf(bad.error()));
    }

    // ───────────── 8. ★★ 半命题指令：物品与目标 ─────────────

    /**
     * ★★ <b>委托方第十五轮的「半命题指令」判据</b>：
     * 「可以填入物品/怪物 id 的指令（比如强制把仇恨目标局限在某种生物上/某个生物上）」
     *
     * <p>这组断言钉的是**匹配规则**与**永不冻结**那两条护栏 ——
     * 因为这两条错了都很难在游戏里看出来（一个表现为"参数填了没反应"，
     * 另一个表现为"她站着不动"）。
     */
    private static void testSemiOpenDirectives() {
        section("8. \u2605\u2605 \u534a\u547d\u9898\u6307\u4ee4\uff1a\u7269\u54c1\u4e0e\u76ee\u6807");

        // ── ① 物品匹配：注册名 / 简写 / 标签 / 类型名 / 能力名 ──
        var sword = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef(
                "minecraft:iron_sword", java.util.Set.of("forge:tools/swords"),
                java.util.Set.of("SwordItem", "TieredItem"), java.util.Set.of("maid:weapon"));
        expect("\u2605 \u6ce8\u518c\u540d\u7cbe\u786e\u5339\u914d", sword.matches("minecraft:iron_sword"), "");
        expect("\u2605 \u7b80\u5199\uff08\u4e0d\u5e26\u547d\u540d\u7a7a\u95f4\uff09\u4e5f\u5339\u914d", sword.matches("iron_sword"), "");
        expect("\u2605 \u7269\u54c1\u6807\u7b7e\u5339\u914d\uff08# \u5f00\u5934\uff09", sword.matches("#forge:tools/swords"), "");
        expect("\u2605 \u7269\u54c1\u6807\u7b7e\u5339\u914d\uff08\u88f8\u6807\u7b7e\uff09", sword.matches("forge:tools/swords"), "");
        expect("\u2605 \u7c7b\u578b\u540d\u5339\u914d\uff08SwordItem\uff09", sword.matches("SwordItem"), "");
        expect("\u2605 \u80fd\u529b\u540d\u5339\u914d\uff08maid:weapon\uff09", sword.matches("maid:weapon"), "");
        expect("\u2605\u2605 \u4e0d\u76f8\u5173\u7684\u4e1c\u897f\u4e0d\u5339\u914d", !sword.matches("goety:wand"), "");
        expect("\u2605\u2605 \u7a7a\u67e5\u8be2 \u21d2 \u4ec0\u4e48\u90fd\u4e0d\u5339\u914d\uff08fail-closed\uff09", !sword.matches("  "), "");

        // ── ② 只用 X：只留 X 承载的动作；不依赖物品的动作要在主手是 X 时才留 ──
        var busOnly = new DirectiveBus();
        busOnly.issue("only_item", Map.of(), Map.of("item", "iron_sword"), 0, "player", 0);
        expect("\u2605\u2605 \u7531 X \u627f\u8f7d\u7684\u52a8\u4f5c\u653e\u884c",
                DirectiveFilter.itemGate(busOnly, sword, null, true) == null, "");
        expect("\u2605\u2605 \u7531\u522b\u7684\u4e1c\u897f\u627f\u8f7d\u7684\u52a8\u4f5c\u6321\u4e0b",
                DirectiveFilter.itemGate(busOnly, null, null, true) != null, "");
        expect("\u2605\u2605 \u4e0d\u4f9d\u8d56\u7269\u54c1\u7684\u6325\u51fb\uff1a\u4e3b\u624b\u662f X \u624d\u653e\u884c",
                DirectiveFilter.itemGate(busOnly, null, sword, true) == null, "");
        var wand = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("goety:wand",
                java.util.Set.of(), java.util.Set.of("IWand"), java.util.Set.of("maid:weapon"));
        expect("\u2605\u2605 \u4e0d\u4f9d\u8d56\u7269\u54c1\u7684\u6325\u51fb\uff1a\u4e3b\u624b\u662f\u6cd5\u6756 \u21d2 \u6321\u4e0b"
                        + "\uff08\u5c31\u662f\u300c\u53ea\u7528\u8fd1\u6218\u5374\u62ff\u6cd5\u6756\u6572\u4eba\u300d\u90a3\u4e2a\u73b0\u8c61\uff09",
                DirectiveFilter.itemGate(busOnly, null, wand, true) != null, "");

        // ── ③ 禁用 X：排除它承载的动作；它正在主手时连挥击也挡（但只在"还有得做"时）──
        var busBan = new DirectiveBus();
        busBan.issue("ban_item", Map.of(), Map.of("item", "goety:wand"), 0, "player", 0);
        expect("\u2605\u2605 \u88ab\u7981\u7528\u7269\u54c1\u627f\u8f7d\u7684\u52a8\u4f5c\u6321\u4e0b",
                DirectiveFilter.itemGate(busBan, wand, null, true) != null, "");
        expect("\u2605 \u5b83\u6b63\u5728\u4e3b\u624b\u65f6\uff0c\u4e0d\u4f9d\u8d56\u7269\u54c1\u7684\u6325\u51fb\u4e5f\u6321\u4e0b",
                DirectiveFilter.itemGate(busBan, null, wand, true) != null, "");
        expect("\u2605\u2605 \u7b2c\u4e00\u8d9f\uff08\u4e0d\u5e26\u90a3\u6761\u89c4\u5219\uff09\u4e0d\u6321 \u21d2 \u7528\u6765\u5224"
                        + "\u300c\u6321\u4e86\u4e4b\u540e\u8fd8\u6709\u6ca1\u6709\u5f97\u505a\u300d",
                DirectiveFilter.itemGate(busBan, null, wand, false) == null, "");
        expect("\u2605 \u6ca1\u6709\u4efb\u4f55\u7269\u54c1\u7c7b\u6307\u4ee4\u65f6\u5168\u90e8\u653e\u884c",
                DirectiveFilter.itemGate(new DirectiveBus(), wand, wand, true) == null, "");
        expect("\u2605 hasItemDirective \u80fd\u8ba4\u51fa\u8fd9\u4e24\u6761",
                DirectiveFilter.hasItemDirective(busOnly) && DirectiveFilter.hasItemDirective(busBan)
                        && !DirectiveFilter.hasItemDirective(new DirectiveBus()), "");

        // ── ④ 目标匹配：生物 id / 简写 / 标签 / UUID ──
        var zombie = new com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.EntityRef(
                "minecraft:zombie", java.util.Set.of("minecraft:undead"),
                "11111111-2222-3333-4444-555555555555");
        expect("\u2605 \u751f\u7269\u6ce8\u518c\u540d\u5339\u914d", zombie.matches("minecraft:zombie"), "");
        expect("\u2605 \u751f\u7269\u7b80\u5199\u5339\u914d", zombie.matches("zombie"), "");
        expect("\u2605 \u5b9e\u4f53\u6807\u7b7e\u5339\u914d", zombie.matches("#minecraft:undead"), "");
        expect("\u2605\u2605 UUID \u5339\u914d\uff08\u53ea\u6253\u67d0\u4e00\u53ea\uff09",
                zombie.matches("11111111-2222-3333-4444-555555555555"), "");
        expect("\u2605\u2605 \u5176\u5b83 UUID \u4e0d\u5339\u914d",
                !zombie.matches("99999999-2222-3333-4444-555555555555"), "");
        expect("\u2605 \u522b\u7684\u751f\u7269\u4e0d\u5339\u914d", !zombie.matches("minecraft:skeleton"), "");
        expect("\u2605 UUID \u5199\u6cd5\u5224\u5b9a", com.touhoulittlemad.fightlikeplayer.decision
                .TargetFilter.looksLikeUuid("11111111-2222-3333-4444-555555555555")
                && !com.touhoulittlemad.fightlikeplayer.decision.TargetFilter
                .looksLikeUuid("minecraft:zombie"), "");

        // ── ⑤ 三条目标指令的判据 ──
        var busFocus = new DirectiveBus();
        busFocus.issue("focus_entity", Map.of(), Map.of("entity", "zombie"), 0, "player", 0);
        expect("\u2605\u2605 focus_entity\uff1a\u5339\u914d\u7684\u653e\u884c",
                com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.reject(busFocus, zombie)
                        == null, "");
        var skel = new com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.EntityRef(
                "minecraft:skeleton", java.util.Set.of(), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        expect("\u2605\u2605 focus_entity\uff1a\u4e0d\u5339\u914d\u7684\u6321\u4e0b\u4e14\u539f\u56e0\u53ef\u8bfb",
                com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.reject(busFocus, skel)
                        != null, "");

        var busOne = new DirectiveBus();
        busOne.issue("focus_one", Map.of(), Map.of("entity",
                "11111111-2222-3333-4444-555555555555"), 0, "player", 0);
        expect("\u2605\u2605 focus_one\uff1a\u53ea\u653e\u90a3\u4e00\u53ea",
                com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.reject(busOne, zombie)
                        == null
                        && com.touhoulittlemad.fightlikeplayer.decision.TargetFilter
                        .reject(busOne, skel) != null, "");

        var busBanE = new DirectiveBus();
        busBanE.issue("ban_entity", Map.of(), Map.of("entity", "#minecraft:undead"), 0, "player", 0);
        expect("\u2605\u2605 ban_entity\uff1a\u5339\u914d\u7684\u6321\u4e0b\u3001\u5176\u4f59\u7167\u5e38",
                com.touhoulittlemad.fightlikeplayer.decision.TargetFilter.reject(busBanE, zombie)
                        != null
                        && com.touhoulittlemad.fightlikeplayer.decision.TargetFilter
                        .reject(busBanE, skel) == null, "");
        expect("\u2605 \u6ca1\u6709\u76ee\u6807\u7c7b\u6307\u4ee4\u65f6\u5168\u90e8\u653e\u884c",
                !com.touhoulittlemad.fightlikeplayer.decision.TargetFilter
                        .active(new DirectiveBus()), "");

        // ── ⑥ 互斥：两种"只打"不能同时生效 ──
        var busMutex = new DirectiveBus();
        busMutex.issue("focus_entity", Map.of(), Map.of("entity", "zombie"), 0, "player", 0);
        busMutex.issue("focus_one", Map.of(), Map.of("entity",
                "11111111-2222-3333-4444-555555555555"), 0, "player", 0);
        expect("\u2605\u2605 focus_entity \u4e0e focus_one \u4e92\u65a5\uff08\u540c\u7ec4 target\uff09",
                !busMutex.has("focus_entity") && busMutex.has("focus_one"), "");

        testItemPlanDeadlock();
        testActionWhitelist();
        testItemQueryMatching();
    }

    // ───────────── 9. ★★ 死锁防线：过滤绝不许清空候选集 ─────────────

    /**
     * ★★ <b>委托方报的 bug</b>：「半命题的指令似乎有 bug，相关测试似乎完全不奏效。」
     *
     * <p>复核后确实是真 bug（而且是**静默**的）：`only_item(X)` 逐个动作过滤时，
     * 如果 X 在**背包里**（不在主手），那么"由 X 承载的动作"一条都没有、
     * "不依赖物品的动作"又因为主手不是 X 而被挡 ⇒ **候选集为空** ⇒ 她站着不动；
     * 而"换手"只在**选中动作之后**才发生 ⇒ **剑永远到不了手上** ⇒ 死锁。
     *
     * <p>⇒ 这组断言钉住新规则：<b>过滤结果为空 ⇒ 一律回退成不过滤</b>，
     * 同时报出"该去换手"（{@link DirectiveFilter.ItemStatus#NEED_HAND}）或"她身上没有它"（{@code ABSENT}）。
     * ★ 上一版的测试只测了 `itemGate` 这个**谓词**，没测"整表会不会被清空" —— 这正是漏掉它的原因。
     */
    private static void testItemPlanDeadlock() {
        section("9. \u2605\u2605 \u6b7b\u9501\u9632\u7ebf\uff1a\u8fc7\u6ee4\u7edd\u4e0d\u8bb8\u628a\u5019\u9009\u96c6\u6e05\u7a7a");

        var sword = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("minecraft:iron_sword",
                java.util.Set.of(), java.util.Set.of("SwordItem"), java.util.Set.of());
        var wand = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("goety:wand",
                java.util.Set.of(), java.util.Set.of("IWand"), java.util.Set.of());
        List<String> ids = List.of("maid_native:melee_swing", "goety:cast_focus");
        // 载体：挥击不依赖物品（null）；施法靠法杖
        java.util.function.Function<String, com.touhoulittlemad.fightlikeplayer.carrier.ItemRef> carrier =
                id -> "goety:cast_focus".equals(id) ? wand : null;

        var bus = new DirectiveBus();
        bus.issue("only_item", Map.of(), Map.of("item", "minecraft:iron_sword"), 0, "player", 0);
        var plan = DirectiveFilter.itemPlan(bus, ids, carrier, wand, true);
        // ★★ 第十七轮续：**期望值显式改了**（第 66 条的纪律：账本/语义变了，断言必须跟着变）。
        //   旧断言是「剑在背包里 ⇒ 候选集【不为空】」—— 它是**在钉住那个 bug**：
        //   实现为了让集合非空而**放弃过滤**，于是 only_item 在"东西在背包里"时等于没下。
        //   现在换手由**指令伺服**负责（与候选集无关）⇒ 允许过滤为空、允许她换好手之前一两拍不动。
        expect("\u2605\u2605 \u5251\u5728\u80cc\u5305\u91cc \u21d2 \u8fc7\u6ee4\u771f\u7684\u751f\u6548"
                        + "\uff08\u5269\u4e0b\u7684\u90fd\u4e0d\u9760\u5251\u3001\u4e5f\u4e0d\u9760\u6cd5\u6756\uff09",
                plan.allowed().isEmpty(), plan.allowed().toString());
        expect("\u2605\u2605 \u540c\u65f6\u544a\u8bc9\u8c03\u7528\u65b9\u300c\u8be5\u53bb\u6362\u624b\u300d",
                plan.status() == DirectiveFilter.ItemStatus.NEED_HAND, plan.status().toString());
        expect("\u2605 \u56de\u9000\u65f6\u539f\u56e0\u53ef\u8bfb\uff08\u4e0d\u8bb8\u9759\u9ed8\uff09",
                !plan.why().isBlank(), plan.why());

        // 她身上根本没有那件东西 ⇒ 同样回退，但状态是 ABSENT
        var plan2 = DirectiveFilter.itemPlan(bus, ids, carrier, wand, false);
        expect("\u2605\u2605 \u5979\u8eab\u4e0a\u3010\u771f\u7684\u6ca1\u6709\u3011\u624d\u56de\u9000 + \u72b6\u6001\u4e3a ABSENT",
                !plan2.allowed().isEmpty()
                        && plan2.status() == DirectiveFilter.ItemStatus.ABSENT,
                plan2.status() + "/" + plan2.allowed());

        // 剑**在手上** ⇒ 过滤真的生效：只剩"不依赖物品的动作"
        var plan3 = DirectiveFilter.itemPlan(bus, ids, carrier, sword, true);
        expect("\u2605\u2605 \u5251\u5728\u624b\u4e0a \u21d2 \u8fc7\u6ee4\u771f\u7684\u751f\u6548\uff08\u53ea\u7559\u4e0d\u4f9d\u8d56\u7269\u54c1\u7684\u6325\u51fb\uff09",
                plan3.status() == DirectiveFilter.ItemStatus.OK
                        && plan3.allowed().equals(List.of("maid_native:melee_swing")),
                plan3.status() + "/" + plan3.allowed());

        // 禁用类：挡完没得做 ⇒ 回退
        var busBan = new DirectiveBus();
        busBan.issue("ban_item", Map.of(), Map.of("item", "goety:wand"), 0, "player", 0);
        var plan4 = DirectiveFilter.itemPlan(busBan, List.of("goety:cast_focus"), carrier, wand, true);
        expect("\u2605\u2605 \u7981\u7528\u7c7b\uff1a\u6321\u5b8c\u6ca1\u5f97\u505a \u21d2 \u56de\u9000\uff08\u5979\u7167\u5e38\u6253\uff09",
                !plan4.allowed().isEmpty(), plan4.allowed().toString());
    }

    // ───────────── 10. ★★ 动作白名单 only_actions ─────────────

    private static void testActionWhitelist() {
        section("10. \u2605\u2605 \u52a8\u4f5c\u767d\u540d\u5355\uff1a\u63a5\u4e0b\u6765\u51e0\u79d2\u53ea\u505a\u8fd9\u4e9b\u52a8\u4f5c");

        var bus = new DirectiveBus();
        bus.issue("only_actions", Map.of("seconds", 6.0),
                Map.of("actions", "slashblade:slash_art, slashblade:summoned_sword"),
                0, "chat", 0);
        expect("\u2605\u2605 \u767d\u540d\u5355\u91cc\u7684 id \u653e\u884c",
                DirectiveFilter.inActionWhitelist(bus, "slashblade:slash_art"), "");
        expect("\u2605\u2605 \u767d\u540d\u5355\u5916\u7684 id \u6321\u4e0b",
                !DirectiveFilter.inActionWhitelist(bus, "maid_native:melee_swing"), "");
        var kept = DirectiveFilter.actionWhitelistFilter(bus,
                List.of("slashblade:slash_art", "maid_native:melee_swing"), null);
        expect("\u2605 \u6574\u8868\u8fc7\u6ee4\u53ea\u7559\u767d\u540d\u5355\u91cc\u7684",
                kept.equals(List.of("slashblade:slash_art")), kept.toString());
        expect("\u2605\u2605 \u767d\u540d\u5355\u89e3\u6790\u51fa\u4e24\u9879\uff08\u9017\u53f7+\u7a7a\u683c\u90fd\u80fd\u5206\u9694\uff09",
                DirectiveFilter.actionWhitelist(bus).size() == 2,
                DirectiveFilter.actionWhitelist(bus).toString());
        expect("\u2605\u2605 \u6ca1\u5199\u767d\u540d\u5355 \u21d2 \u8fd9\u6761\u4e0d\u751f\u6548\uff08fail-open\uff0c\u4e0d\u8bb8\u56e0\u6b64\u6e05\u7a7a\uff09",
                DirectiveFilter.inActionWhitelist(newBusWithEmptyWhitelist(), "anything"), "");

        // ★★ TTL 约定：seconds 参数接到 TTL 上（"接下来几秒"）
        expect("\u2605\u2605 seconds=6 \u21d2 TTL 120 tick\uff08\u65f6\u957f\u8d70\u5df2\u6709\u7684 TTL \u673a\u5236\uff09",
                DirectiveSpec.ttlTicksFrom("only_actions", Map.of("seconds", 6.0), 0) == 120,
                String.valueOf(DirectiveSpec.ttlTicksFrom("only_actions", Map.of("seconds", 6.0), 0)));
        expect("\u2605 \u6ca1\u7ed9 seconds \u21d2 \u7528\u56de\u9000\u503c",
                DirectiveSpec.ttlTicksFrom("only_actions", Map.of(), 999) == 999, "");
    }

    // ───────────── 11. ★★ 物品查询串的分级匹配 ─────────────

    /**
     * ★★ <b>委托方第二轮实测：「我不管让她使用什么，都用的火箭筒」</b>。
     *
     * <p>复核查出的第一条链就是**匹配太严**：旧判据（{@code ItemRef#matchesOne}）要求
     * **逐字符相等**（注册名 / 简写 / 标签 / 类型名 / 能力名），模型只要写错一点点
     * （{@code AK47} 的大小写、漏了 {@code tacz:}、写 {@code rpg} 而真实 id 是
     * {@code tacz:rpg7}）就**一个都不匹配**；而"不匹配"的后果是
     * {@code itemPlan} 走 ABSENT **静默回退**（她照常用手上那把枪 = 火箭筒）。
     *
     * <p>⇒ 这组断言把新的分级（FULL/EXACT/BARE/LOOSE）、含糊的显式化、
     * 以及"编一个不存在的名字必须判 NONE"逐条钉住。
     * ★ 最后一条最要紧：**放宽不等于乱猜** —— 认不得就得认不得，这样上层才能收到
     * "她身上没有能匹配它的东西"这句如实回话（{@code DirectiveHolder#feasibility}）。
     */
    private static void testItemQueryMatching() {
        section("11. \u2605\u2605 \u7269\u54c1\u67e5\u8be2\u4e32\uff1a\u5206\u7ea7\u5339\u914d + \u542b\u7cca\u663e\u5f0f\u5316");

        var rpg = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("tacz:rpg7",
                java.util.Set.of(), java.util.Set.of("IGun"), java.util.Set.of());
        var ak = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("tacz:ak47",
                java.util.Set.of(), java.util.Set.of("IGun"), java.util.Set.of());
        var sword = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("minecraft:iron_sword",
                java.util.Set.of("forge:tools/swords"), java.util.Set.of("SwordItem"),
                java.util.Set.of("maid:weapon"));
        List<com.touhoulittlemad.fightlikeplayer.carrier.ItemRef> list = List.of(rpg, ak, sword);

        expect("\u2605 \u5168\u540d\u9010\u5b57\u7b26\u76f8\u7b49 \u21d2 FULL",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(rpg, "tacz:rpg7")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.FULL,
                "不是 FULL");
        expect("\u2605\u2605 \u7b80\u5199\uff08\u4e0d\u5e26\u547d\u540d\u7a7a\u95f4\uff09\u4e5f\u8ba4 \u21d2 BARE",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(ak, "ak47")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.BARE,
                "简写没认出来");
        expect("\u2605\u2605 \u5927\u5c0f\u5199\u4e0d\u540c\u4e5f\u8ba4 \u21d2 BARE",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(ak, "AK47")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.BARE,
                "大小写把它挡了");
        expect("\u2605\u2605\u2605 rpg \u21d2 tacz:rpg7\uff08\u5b50\u4e32\uff09\u21d2 LOOSE"
                        + "\u2014\u2014 \u8fd9\u6b63\u662f\u59d4\u6258\u4eba\u5634\u91cc\u90a3\u53e5\u300c\u706b\u7bad\u7b52\u300d",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(rpg, "rpg")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.LOOSE,
                "子串没认出来");
        expect("\u2605 \u7c7b\u578b\u540d\u76f8\u7b49 \u21d2 EXACT",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(ak, "IGun")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.EXACT,
                "类型名没认出来");
        expect("\u2605 \u6807\u7b7e\uff08\u5e26 # \u4e0e\u4e0d\u5e26\u90fd\u8ba4\uff09\u21d2 EXACT",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(sword, "#forge:tools/swords")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.EXACT
                        && com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery
                                .strength(sword, "forge:tools/swords")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.EXACT,
                "标签没认出来");
        expect("\u2605 \u591a\u6bb5\u67e5\u8be2\uff08\u9017\u53f7\u5206\u9694\uff09\u4efb\u4e00\u6bb5\u547d\u4e2d\u5373\u53ef",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery
                        .strength(sword, "tacz:ak47, iron_sword")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.BARE,
                "多段没生效");
        expect("\u2605\u2605 \u7f16\u51fa\u6765\u7684\u540d\u5b57\uff08\u5979\u8eab\u4e0a\u6ca1\u6709\uff09\u21d2 NONE"
                        + "\uff08\u653e\u5bbd\u4e0d\u7b49\u4e8e\u4e71\u731c\uff09",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(ak, "rifle")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.NONE,
                "编的名字被认了");
        expect("\u2605 \u592a\u77ed\u7684\u4e32\u4e0d\u505a\u5b50\u4e32\u5339\u914d\uff08'a' \u4e0d\u8bb8\u547d\u4e2d\u4e00\u5207\uff09",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(ak, "a")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.NONE,
                "单字符也命中了");

        var res = com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.resolve("ak47", list);
        expect("\u2605\u2605 \u89e3\u6790\u53d6\u3010\u6700\u5f3a\u3011\u547d\u4e2d\uff0c\u4e14\u80fd\u5b9a\u4f4d\u5230\u5b83\u5728\u5979\u8eab\u4e0a\u7684\u4e0b\u6807",
                !res.isEmpty() && res.best().index() == 1, res.describe());
        var amb = com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.resolve("gun", list);
        expect("\u2605\u2605 \u542b\u7cca\uff08\u540c\u6837\u5f3a\u7684\u4e24\u4ef6\uff09\u5fc5\u987b\u663e\u5f0f\u62a5\u51fa\uff0c"
                        + "\u4e0d\u8bb8\u5047\u88c5\u53ea\u6709\u4e00\u4ef6",
                amb.ambiguous(), amb.describe());
        var none = com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.resolve("rifle", list);
        expect("\u2605\u2605 \u65e0\u547d\u4e2d \u21d2 \u7a7a\u7ed3\u679c\uff08\u8c03\u7528\u65b9\u636e\u6b64\u8d70 ABSENT "
                        + "\u56de\u9000 + \u5982\u5b9e\u56de\u8bdd\uff09",
                none.isEmpty(), none.describe());
        expect("\u2605 ItemRef.matches \u4e0e ItemQuery \u662f\u540c\u4e00\u5957\u5224\u636e\uff08\u4e0d\u8bb8\u4e24\u5904\u53e3\u5f84\uff09",
                ak.matches("AK47") && !ak.matches("rifle") && rpg.matches("rpg"), "");

        // ★★ 第十七轮续：下达回话必须**把参数原样回显** ——
        //   否则连"模型到底发了哪一件"都查不出来（"不管让她用什么都是火箭筒"的第一现场）。
        // ★★ 第十七轮续（委托方 2026-10-05：「我让她只用机枪，却还是用冲锋枪扫射」）：
        //   枪的**别名**（枪型 `mg` / 枪型中文「机枪」/ 人读名「M249 机枪」）必须能匹配。
        //   它们不是注册名 —— TaCZ 所有枪共用一个注册名（`tacz:modern_kinetic_gun`），
        //   只有别名能让"只用机枪"这条指令落到**具体哪一把**上。
        var mg = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("tacz:m249",
                java.util.Set.of(), java.util.Set.of("IGun"), java.util.Set.of(),
                java.util.Set.of("m249", "mg", "机枪", "M249 机枪"));
        var smg = new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef("tacz:uzi",
                java.util.Set.of(), java.util.Set.of("IGun"), java.util.Set.of(),
                java.util.Set.of("uzi", "smg", "冲锋枪", "UZI 冲锋枪"));
        expect("\u2605\u2605 \u300c\u673a\u67aa\u300d\uff08\u4e2d\u6587\u67aa\u578b\u522b\u540d\uff09\u53ea\u547d\u4e2d\u673a\u67aa\u90a3\u4e00\u628a",
                mg.matches("机枪") && !smg.matches("机枪"), mg.describe() + "/" + smg.describe());
        expect("\u2605\u2605 \u67aa\u578b mg \u53ea\u547d\u4e2d\u673a\u67aa",
                mg.matches("mg") && !smg.matches("mg"), "");
        expect("\u2605\u2605 \u4eba\u8bfb\u540d\uff08M249 \u673a\u67aa\uff09\u4e5f\u80fd\u547d\u4e2d",
                mg.matches("M249 机枪"), "");
        expect("\u2605\u2605 \u4e24\u628a\u67aa\u4e92\u4e0d\u4e32\uff08\u673a\u67aa \u2260 \u51b2\u950b\u67aa\uff09",
                smg.matches("冲锋枪") && !mg.matches("冲锋枪") && !mg.matches("uzi"), "");
        expect("\u2605 \u67aa\u81ea\u5df1\u7684 id\uff08tacz:m249\uff09\u4ecd\u7136\u662f FULL",
                com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.strength(mg, "tacz:m249")
                        == com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.Match.FULL, "");
        expect("\u2605 \u540c\u4e00\u4e2a\u67aa\u578b\u7684\u4e24\u628a\u673a\u67aa\u90fd\u80fd\u88ab\u300c\u673a\u67aa\u300d\u547d\u4e2d"
                        + "\uff08\u522b\u540d\u5171\u4eab \u21d2 only_item \u6307\u5411\u4e00\u7c7b\uff09",
                mg.matches("机枪") && new com.touhoulittlemad.fightlikeplayer.carrier.ItemRef(
                        "tacz:rpk", java.util.Set.of(), java.util.Set.of("IGun"),
                        java.util.Set.of(), java.util.Set.of("mg", "机枪")).matches("机枪"), "");

        // ★★ 第十七轮续（委托方第 2 条）：什么算"boss 级" + 冷却窗口（纯逻辑，可离线钉住）
        var bossPolicy = com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy.class;
        expect("\u2605\u2605 \u8840\u91cf\u8fbe\u9608\u503c \u21d2 \u7b97 boss\u7ea7",
                com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy
                        .isBossLevel(150, false, 100), "");
        expect("\u2605 \u8840\u91cf\u4e0d\u591f \u21d2 \u4e0d\u7b97\uff08\u4e0d\u8981\u8fde\u5c0f\u602a\u90fd\u62a5\uff09",
                !com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy
                        .isBossLevel(20, false, 100), "");
        expect("\u2605\u2605 \u539f\u7248 boss\uff08\u672b\u5f71\u9f99/\u51cb\u7075\uff09\u65e0\u6761\u4ef6\u7b97",
                com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy
                        .isBossLevel(1, true, 100), "");
        expect("\u2605 \u51b7\u5374\u7a97\u53e3\u5185\u4e0d\u91cd\u590d\u8bf4\uff08\u8fde\u6740\u4e0d\u5237\u5c4f\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy.onCooldown(100, 120)
                        && !com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy
                                .onCooldown(100,
                                        100 + com.touhoulittlemad.fightlikeplayer.decision
                                                .BossChatPolicy.DEBOUNCE_TICKS), "");
        expect("\u2605 \u6218\u62a5\u91cc\u5e26\u4e0a\u5bf9\u65b9\u540d\u5b57\u4e0e\u8840\u91cf",
                com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy
                        .promptFor("\u672b\u5f71\u9f99", 200.0).contains("\u672b\u5f71\u9f99")
                        && com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy
                                .promptFor("\u672b\u5f71\u9f99", 200.0).contains("200"), "");

        // ★★ 第十七轮续（委托方第 1、3 条）：脱战判据与索敌迟滞（纯逻辑，可离线钉住）
        expect("\u2605\u2605 \u8fde\u7eed 5 \u79d2\u6ca1\u6709\u4ec7\u6068\u5bf9\u8c61 \u21d2 \u8131\u6218",
                com.touhoulittlemad.fightlikeplayer.decision.CombatState
                        .disengaged(1000, 1000 + com.touhoulittlemad.fightlikeplayer.decision
                                .CombatState.IDLE_TICKS), "");
        expect("\u2605 \u521a\u6253\u5b8c\uff085 \u79d2\u5185\uff09\u4e0d\u7b97\u8131\u6218",
                !com.touhoulittlemad.fightlikeplayer.decision.CombatState
                        .disengaged(1000, 1000 + 40), "");
        expect("\u2605 \u4ece\u6765\u6ca1\u6709\u8fc7\u76ee\u6807 \u21d2 \u7b97\u8131\u6218\uff08\u4e00\u4e0a\u6765\u4e0d\u4f1a\u5361\u5728\u6218\u6597\u6001\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.CombatState.disengaged(0, 99999), "");
        expect("\u2605\u2605 \u521a\u4e22\u76ee\u6807\uff083 \u79d2\u5185\uff09\u21d2 \u7528\u66f4\u5927\u7684\u9501\u5b9a\u534a\u5f84\u627e\u56de\u6765",
                com.touhoulittlemad.fightlikeplayer.decision.CombatState
                        .acquireRange(true, 24, 40) == 40.0
                        && com.touhoulittlemad.fightlikeplayer.decision.CombatState
                                .acquireRange(false, 24, 40) == 24.0, "");
        expect("\u2605 \u9501\u5b9a\u534a\u5f84\u4e0d\u5c0f\u4e8e\u4ea7\u751f\u4ec7\u6068\u534a\u5f84\uff08\u5426\u5219\u8fdf\u6ede\u65e0\u610f\u4e49\uff09",
                com.touhoulittlemad.fightlikeplayer.decision.CombatState
                        .acquireRange(true, 40, 24) == 40.0, "");

        // ★★ 第十七轮续（委托方实测：清空背包后旧 only_item 一直挂着）：
        //    "拿不到东西"的物品指令必须在宽限后自动解除（纯逻辑判据，可离线钉住）
        expect("\u2605\u2605 \u62ff\u4e0d\u5230\u4e1c\u897f\u8d85\u8fc7\u5bbd\u9650 \u21d2 \u8be5\u81ea\u52a8\u89e3\u9664",
                com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter
                        .staleItemDirective(1000, 1000 + 100, 100), "");
        expect("\u2605 \u521a\u53d1\u73b0\u62ff\u4e0d\u5230 \u21d2 \u5148\u4e0d\u89e3\u9664\uff08\u7ed9\u5979\u6361\u56de\u6765\u7684\u65f6\u95f4\uff09",
                !com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter
                        .staleItemDirective(1000, 1040, 100), "");
        expect("\u2605 \u4ece\u6765\u6ca1\u53d1\u73b0\u8fc7\uff08=0\uff09\u21d2 \u4e0d\u89e3\u9664",
                !com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter
                        .staleItemDirective(0, 99999, 100), "");
        expect("\u2605 switch_item \u7684\u63cf\u8ff0\u91cc\u5199\u660e\u4e86\u300c\u4e00\u6b21\u6027\u300d\u4e0e\u914d\u5bf9 only_item",
                com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec
                        .find("switch_item").zh().contains("一次性")
                        && com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec
                                .find("switch_item").enforce().contains("only_item"), "");

        var echoBus = new DirectiveBus();
        var echo = echoBus.issue("only_item", Map.of(), Map.of("item", "tacz:ak47"), 0, "chat", 0);
        expect("\u2605\u2605 \u4e0b\u8fbe\u56de\u8bdd\u56de\u663e\u6587\u672c\u53c2\u6570\uff08item=tacz:ak47\uff09",
                echo.message().contains("item=tacz:ak47"), echo.message());
        var echo2 = echoBus.issue("keep_distance", Map.of("min", 8.0), Map.of(), 0, "chat", 0);
        expect("\u2605 \u6570\u503c\u53c2\u6570\u540c\u6837\u56de\u663e\uff08min=8.0\uff09",
                echo2.message().contains("min=8.0"), echo2.message());
    }

    private static DirectiveBus newBusWithEmptyWhitelist() {
        var b = new DirectiveBus();
        b.issue("only_actions", Map.of(), Map.of(), 0, "chat", 0);
        return b;
    }

    /** 把一层场景事实摊平成键路径集合（"self.health_pct" 这种）。 */
    private static java.util.Set<String> flatten(Map<String, Object> facts) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Object> e : facts.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> sub) {
                for (Object k : sub.keySet()) {
                    out.add(e.getKey() + "." + k);
                }
            } else {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ───────────── \u5c0f\u5de5\u5177 ─────────────
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
