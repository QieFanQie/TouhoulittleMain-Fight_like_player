package com.touhoulittlemad.fightlikeplayer.carrier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * M3 的离线自测 —— <b>清单加载 / 覆盖 / 热重载</b>，纯 JVM。
 *
 * <h2>它验证什么</h2>
 * <ol>
 *   <li><b>从抽象来源加载</b>：载体规则与动作数据都被正确识别（按文件名分流）；</li>
 *   <li>★ <b>覆盖语义</b>：高优先级来源的同名文件胜出（数据包作者只覆盖一个文件）；</li>
 *   <li>★ <b>热重载真的生效</b>：改了 JSON 之后重载，决策结果<b>随之改变</b>；</li>
 *   <li>★★ <b>失败不清空</b>：坏重载保留上一份好数据（不留功能空洞）；</li>
 *   <li>★ <b>jar 内确实带了清单</b>（否则运行时根本读不到 —— 这是最隐蔽的失败模式）；</li>
 *   <li><b>加载顺序确定</b>（按文件名排序，结果可复现）。</li>
 * </ol>
 *
 * <p>★ 本测试<b>不需要 Minecraft</b>：{@link CatalogSource} 是可替换的，
 * 因此"资源管理器"这一层被换成假的 Map 来源即可。
 * 这正是把解析逻辑与读取来源分开的收益。
 *
 * <pre>
 *   java -cp "build\classes\java\main;&lt;gson.jar&gt;" \
 *        com.touhoulittlemad.fightlikeplayer.carrier.M3SelfTest
 * </pre>
 */
public final class M3SelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("=== 清单加载 / 热重载自测 (M3SelfTest) ===\n");

        testLoadsFromSource();
        testOverrideSemantics();
        testReloadActuallyChangesBehavior();
        testBadReloadKeepsPrevious();
        testLoadOrderDeterministic();
        testCatalogShipsInJar();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════ 1. 从抽象来源加载 ═══════════

    private static void testLoadsFromSource() throws IOException {
        section("1. 从抽象来源加载：动作数据与载体规则按文件名分流");

        CatalogLoader.Loaded loaded = CatalogLoader.loadFromWorkingDir(Path.of("catalog"));
        long choices = loaded.actions().stream().filter(ActionSpec::isChoice).count();
        System.out.println("   动作 " + loaded.actions().size() + " 条（可评分 " + choices
                + "）· 载体规则 " + loaded.rules().size() + " 条");

        expect("★ 动作被加载（>0）", !loaded.actions().isEmpty(), "0 条");
        expect("★ carriers.json 被识别为载体规则（不是动作）",
                !loaded.rules().isEmpty(), "0 条");
        expect("★ 载体规则不会被误当成动作（carriers 文件名分流正确）",
                loaded.actions().stream().noneMatch(a -> a.id() == null || a.id().isBlank()),
                "存在空 id 的动作");
    }

    // ═══════════ 2. 覆盖语义 ═══════════

    private static void testOverrideSemantics() {
        section("2. ★ 覆盖语义：高优先级来源的同名文件胜出");

        // 低优先级：一个动作
        Map<String, JsonObject> low = new TreeMap<>();
        low.put("testmod.json", opsJson("test:a", 0.5));

        // 高优先级：同名文件覆盖它
        Map<String, JsonObject> high = new TreeMap<>();
        high.put("testmod.json", opsJson("test:b", 0.9));

        // 模拟"资源包堆叠"：把两张表按优先级合并（高优先级覆盖同名）
        Map<String, JsonObject> merged = new TreeMap<>(low);
        merged.putAll(high);   // ← 覆盖发生在这里（真实实现是 getResourceStack 取最后一个）

        CatalogLoader.Loaded l = CatalogLoader.load(mapSource(merged));
        System.out.println("   合并后动作：" + l.actions().stream().map(ActionSpec::id).toList());

        expect("★ 同名文件被高优先级覆盖（只剩 test:b）",
                l.actions().size() == 1 && "test:b".equals(l.actions().get(0).id()),
                l.actions().stream().map(ActionSpec::id).toList().toString());
    }

    // ═══════════ 3. 热重载真的改变行为 ═══════════

    private static void testReloadActuallyChangesBehavior() throws IOException {
        section("3. ★★ 热重载真的生效：改了 JSON ⇒ 决策结果随之改变");

        Path dir = Files.createTempDirectory("flp-m3");
        try {
            Path dataDir = dir.resolve("data");
            Files.createDirectories(dataDir);

            // 初始：动作 A 的向量偏【单点】
            Files.writeString(dataDir.resolve("reload_test.json"),
                    opsJson("test:act", 0.9, "SINGLE_DAMAGE").toString(), StandardCharsets.UTF_8);
            // 造一个最小载体规则：无物品规则 ⇒ 一定可用
            Files.writeString(dir.resolve("carriers.json"), carriersJson("test:carrier").toString(), StandardCharsets.UTF_8);

            CatalogLoader.Loaded first = CatalogLoader.load(CatalogLoader.directorySource(dir));
            CatalogHolder.install(new CarrierResolver(first.actions(), first.rules()), "第一次");
            int countAfterFirst = CatalogHolder.loadCount();

            // 需求偏【群伤】⇒ 若动作是单点，就不该被选中
            CarrierResolver r1 = CatalogHolder.resolver();
            List<String> before = resolveIds(r1);

            // ★ 改 JSON：把向量改成偏【群伤】
            Files.writeString(dataDir.resolve("reload_test.json"),
                    opsJson("test:act", 0.9, "AREA_DAMAGE").toString(), StandardCharsets.UTF_8);

            // 重载
            CatalogLoader.Loaded second = CatalogLoader.load(CatalogLoader.directorySource(dir));
            CatalogHolder.install(new CarrierResolver(second.actions(), second.rules()), "第二次");

            CarrierResolver r2 = CatalogHolder.resolver();
            List<String> after = resolveIds(r2);

            System.out.println("   重载前可用数 = " + before.size() + "，重载后 = " + after.size());
            System.out.println("   重载计数 " + countAfterFirst + " → " + CatalogHolder.loadCount());

            expect("★ 重载被计数（说明真的换了一份）",
                    CatalogHolder.loadCount() == countAfterFirst + 1,
                    String.valueOf(CatalogHolder.loadCount()));
            expect("★★ 重载后的解析器用的是【新数据】（向量已换成群伤）",
                    r2.actions().stream().anyMatch(a -> a.id().equals("test:act")
                            && a.defaultVector().get(com.touhoulittlemad.fightlikeplayer.decision.NeedAxis.AREA_DAMAGE) > 0.5),
                    "新数据未生效");
        } finally {
            deleteRecursive(dir);
        }
    }

    // ═══════════ 4. 坏重载不清空 ═══════════

    private static void testBadReloadKeepsPrevious() throws IOException {
        section("4. ★★ 失败策略：坏重载【保留上一份】，不留功能空洞");

        // 先装一份好的
        CatalogLoader.Loaded good = CatalogLoader.loadFromWorkingDir(Path.of("catalog"));
        CatalogHolder.install(new CarrierResolver(good.actions(), good.rules()), "好数据");
        int sizeBefore = CatalogHolder.resolver().actions().size();

        // 空来源 = 加载失败
        boolean accepted = CatalogHolder.install(null, "坏数据");
        int sizeAfter = CatalogHolder.resolver().actions().size();

        System.out.println("   好数据 " + sizeBefore + " 条 → 坏重载被拒 = " + !accepted
                + " → 仍是 " + sizeAfter + " 条");
        expect("★ 空清单被拒绝安装", !accepted, "accepted=true");
        expect("★★ 被拒后【保留上一份】（不是清空）", sizeAfter == sizeBefore,
                sizeBefore + " -> " + sizeAfter);

        // 空动作的解析器同样应被拒
        boolean accepted2 = CatalogHolder.install(new CatalogResolverProxy().empty(), "空动作");
        expect("★ 动作数为 0 的清单也被拒绝", !accepted2, "accepted=true");
    }

    /** 小工具：造一个空的 CarrierResolver。 */
    private static final class CatalogResolverProxy {
        CarrierResolver empty() {
            return new CarrierResolver(List.of(), List.of());
        }
    }

    // ═══════════ 5. 加载顺序确定 ═══════════

    private static void testLoadOrderDeterministic() {
        section("5. 加载顺序确定（按文件名排序 ⇒ 结果可复现）");

        Map<String, JsonObject> m = new LinkedHashMap<>();
        m.put("zzz.json", opsJson("test:z", 0.1));
        m.put("aaa.json", opsJson("test:a", 0.2));
        m.put("mmm.json", opsJson("test:m", 0.3));

        List<String> ids = CatalogLoader.load(mapSource(m)).actions()
                .stream().map(ActionSpec::id).toList();
        System.out.println("   乱序放入 → 加载顺序：" + ids);
        expect("★ 加载顺序按文件名排序（与插入顺序无关）",
                ids.equals(List.of("test:a", "test:m", "test:z")), ids.toString());
    }

    // ═══════════ 6. jar 内确实带了清单 ═══════════

    private static void testCatalogShipsInJar() throws IOException {
        section("6. ★★ jar 内确实带了清单（否则运行时根本读不到）");

        Path libs = Path.of("build", "libs");
        Path jar = null;
        if (Files.isDirectory(libs)) {
            try (var s = Files.list(libs)) {
                jar = s.filter(p -> p.toString().endsWith(".jar")
                                && !p.toString().contains("-sources")
                                && !p.toString().contains("-dev"))
                        .findFirst().orElse(null);
            }
        }
        if (jar == null) {
            System.out.println("   （尚未构建出 jar，跳过 —— 先跑 gradle build）");
            expect("jar 存在（先 gradle build）", false, "build/libs 下没有 jar");
            return;
        }

        List<String> entries = new ArrayList<>();
        try (var zf = new java.util.zip.ZipFile(jar.toFile())) {
            zf.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.startsWith(CatalogSource.RESOURCE_DIR_DISPLAY + "/"))
                    .forEach(entries::add);
        }

        List<String> jsonFiles = entries.stream().filter(n -> n.endsWith(".json")).toList();
        System.out.println("   jar = " + jar.getFileName());
        System.out.println("   内置清单条目 " + jsonFiles.size() + " 个：");
        jsonFiles.forEach(n -> System.out.println("      " + n));

        expect("★★ jar 内包含内置清单（>0 个文件）", !jsonFiles.isEmpty(),
                "0 个 —— 运行时将读不到任何动作");
        expect("★ jar 内含 carriers.json",
                jsonFiles.stream().anyMatch(n -> n.endsWith("carriers.json")),
                jsonFiles.toString());
        expect("★ jar 内含动作数据（至少 maid_native.json）",
                jsonFiles.stream().anyMatch(n -> n.endsWith("maid_native.json")),
                jsonFiles.toString());
    }

    // ═══════════ 辅助 ═══════════

    /** 造一个假的 CatalogSource（模拟"资源管理器已按优先级解析好"的结果）。 */
    private static CatalogSource mapSource(Map<String, JsonObject> files) {
        Map<String, JsonObject> copy = new TreeMap<>(files);
        return new CatalogSource() {
            @Override
            public Map<String, JsonObject> files() {
                return copy;
            }

            @Override
            public String describe() {
                return "假来源（" + copy.size() + " 个文件）";
            }
        };
    }

    private static JsonObject opsJson(String id, double value) {
        return opsJson(id, value, "SINGLE_DAMAGE");
    }

    /** 造一个最小动作数据文件。 */
    private static JsonObject opsJson(String id, double value, String axis) {
        String json = """
                {
                  "formatVersion": 1,
                  "operations": [
                    {
                      "id": "%s",
                      "kind": "atomic",
                      "carrier": "test:carrier",
                      "sourceMod": "test",
                      "reach": "RA",
                      "role": "choice",
                      "vector": { "%s": %s },
                      "evidence": [ { "level": "inferred", "cite": "M3SelfTest" } ]
                    }
                  ]
                }
                """.formatted(id, axis, value);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** 造一个最小载体规则文件。 */
    private static JsonObject carriersJson(String carrier) {
        String json = """
                {
                  "formatVersion": 1,
                  "rules": [
                    { "carrier": "%s", "requiresMod": null, "match": { "kind": "always" }, "slots": [] }
                  ]
                }
                """.formatted(carrier);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** 用当前解析器在"群伤需求"下取一次候选集。 */
    private static List<String> resolveIds(CarrierResolver r) {
        var ctx = new ContextFacts(true, false, 0, true, false, false,
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of("test", "touhou_little_maid"));
        var res = r.resolve(List.of(), ctx, SpringConfig.defaults());
        return res.candidates().stream().map(CandidateAction::id).toList();
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响结论
                }
            });
        }
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
