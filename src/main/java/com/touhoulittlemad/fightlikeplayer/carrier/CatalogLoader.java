package com.touhoulittlemad.fightlikeplayer.carrier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 清单加载器 —— 把 {@code catalog/*.json} 读成运行时可用的规格。
 *
 * <h2>关于它属于 M2 还是 M3</h2>
 * 严格说"清单 → 运行时"是 **M3**。但 M2 若没有真实数据就只能对着假数据自测，
 * 价值会大打折扣。⇒ <b>这里先做一个只读的子集</b>（够载体解析用），
 * M3 再补上数据包热重载与 `Codec` 校验。
 *
 * <p>★ 用 **Gson**（Minecraft/Forge 自带，不新增依赖）。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.5.3 / §8</a>
 */
public final class CatalogLoader {

    private CatalogLoader() {
    }

    /**
     * 加载结果。
     *
     * @param actions              全部动作规格
     * @param rules                载体规则
     * @param implementedActionIds ★★ <b>执行器账本里标为 {@code implemented} 的动作 id</b>
     *                             —— 解析期据此把"没有执行器的动作"挡在候选集之外，
     *                             否则会出现"反复选中一个做不出来的动作"的死循环
     *                             （见 {@link CatalogSource#EXECUTORS_FILE}）。
     *                             ⚠️ 空集 = <b>账本没读到</b> ⇒ 解析器不过滤（fail-open，
     *                             否则一个读不到的文件会让女仆彻底不动），但会记一条 note。
     */
    public record Loaded(List<ActionSpec> actions, List<CarrierRule> rules,
                         Set<String> implementedActionIds,
                         /** ★ W32：法术意图表（来自 {@code catalog/data/spell_intent.json}）。 */
                         com.touhoulittlemad.fightlikeplayer.decision.thinking
                                 .SpellIntentTable.Table spellIntent) {

        public Loaded {
            implementedActionIds = implementedActionIds == null ? Set.of()
                    : Set.copyOf(implementedActionIds);
            spellIntent = spellIntent == null
                    ? com.touhoulittlemad.fightlikeplayer.decision.thinking
                    .SpellIntentTable.Table.empty()
                    : spellIntent;
        }

        /** 兼容构造：不带执行器账本与法术意图表（旧调用点与「只测解析」的自测）。 */
        public Loaded(List<ActionSpec> actions, List<CarrierRule> rules) {
            this(actions, rules, Set.of(),
                    com.touhoulittlemad.fightlikeplayer.decision.thinking
                            .SpellIntentTable.Table.empty());
        }
    }

    // ───────────────────────── ★ M3：从抽象来源加载 ─────────────────────────

    /**
     * 从任意 {@link CatalogSource} 加载。<b>解析逻辑只此一份</b>，
     * 内置数据包 / 玩家数据包 / 磁盘目录都走这里。
     *
     * <p>文件名分流：
     * <ul>
     *   <li>{@code carriers*} ⇒ 载体规则（物品 → 载体）</li>
     *   <li>★ {@code vectors*} ⇒ <b>评分表</b>（动作 → {@code v_x}）——
     *       <b>与"动作是什么"分开存放</b>，便于单独复核与重新打分</li>
     *   <li>其余含 {@code operations} 的 ⇒ 动作数据</li>
     * </ul>
     *
     * <p>★ <b>评分表的优先级高于动作数据里的内联 {@code vector}</b>：
     * 前者是权威、后者只作向后兼容 ⇒ 迁移可以逐条进行，**不会因为漏迁而静默丢分**。
     */
    public static Loaded load(CatalogSource source) {
        Map<String, JsonObject> files = source.files();
        List<CarrierRule> rules = new ArrayList<>();
        List<JsonObject> rawOps = new ArrayList<>();
        Map<String, VectorEntry> vectors = new java.util.TreeMap<>();
        Set<String> implemented = new java.util.LinkedHashSet<>();
        Map<String, String> spellIrons = new java.util.LinkedHashMap<>();
        Map<String, String> spellGoety = new java.util.LinkedHashMap<>();
        Map<String, String> spellFlags = new java.util.LinkedHashMap<>();

        List<String> names = new ArrayList<>(files.keySet());
        java.util.Collections.sort(names);

        for (String name : names) {
            JsonObject root = files.get(name);
            if (root == null) {
                continue;
            }
            if (name.startsWith("carriers")) {
                rules.addAll(toRules(root));
            } else if (name.startsWith("vectors")) {
                vectors.putAll(toVectors(root));
            } else if (name.startsWith("executors")) {
                // ★★ 执行器账本 ⇒ "哪些动作真的被实现过"（解析期据此挡住幽灵动作）
                implemented.addAll(toImplemented(root));
            } else if (name.startsWith("spell_intent")) {
                // ★★ W32：法术意图表（类简单名 → 意图/类别）
                spellIrons.putAll(stringMap(root, "irons"));
                spellGoety.putAll(stringMap(root, "goety"));
                spellFlags.putAll(stringMap(root, "flags"));
            } else if (root.has("operations")) {
                for (JsonElement e : root.getAsJsonArray("operations")) {
                    rawOps.add(e.getAsJsonObject());
                }
            }
        }

        List<ActionSpec> actions = new ArrayList<>(rawOps.size());
        for (JsonObject o : rawOps) {
            ActionSpec spec = toSpec(o);
            VectorEntry ve = vectors.get(spec.id());
            if (ve != null) {
                // ★ 评分表覆盖内联值（权威在 vectors.json）
                spec = spec.withVectors(ve.vector(), ve.overrides(), ve.provenance());
            }
            actions.add(spec);
        }
        return new Loaded(actions, rules, implemented,
                com.touhoulittlemad.fightlikeplayer.decision.thinking
                        .SpellIntentTable.fromMaps(spellIrons, spellGoety, spellFlags));
    }

    /** 把一个「字符串 → 字符串」的 JSON 对象读成 Map（法术意图表用）。 */
    public static Map<String, String> stringMap(JsonObject root, String field) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (root == null || !root.has(field) || !root.get(field).isJsonObject()) {
            return out;
        }
        for (var e : root.getAsJsonObject(field).entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }

    /**
     * 解析执行器账本 {@code executors.json}。
     *
     * <pre>
     * { "executors": [ { "kind": "slashblade", "status": "implemented",
     *                    "handles": ["slashblade:combo_a", …] }, … ] }
     * </pre>
     *
     * ★ 只收 {@code status == "implemented"} 的条目：{@code planned} 就是"已登记的计划缺口"，
     * 正是要挡在候选集外面的那一批。
     */
    public static Set<String> toImplemented(JsonObject root) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (root == null || !root.has("executors")) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray("executors")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String status = o.has("status") ? o.get("status").getAsString() : "";
            if (!"implemented".equals(status)) {
                continue;
            }
            if (!o.has("handles")) {
                continue;
            }
            for (JsonElement h : o.getAsJsonArray("handles")) {
                out.add(h.getAsString());
            }
        }
        return out;
    }

    /**
     * 评分表的一条。
     *
     * @param vector     族默认向量 / 实例向量；{@code null} = 无默认（只能靠 overrides）
     * @param overrides  按参数取值覆写
     * @param provenance 来源：{@code authored} / {@code agent} / {@code heuristic} / {@code measured}
     */
    public record VectorEntry(NeedVector vector, Map<String, NeedVector> overrides, String provenance) {
    }

    /**
     * 解析评分表。
     *
     * <pre>
     * { "vectors": {
     *     "动作id": { "vector": {轴:值,…} | null,
     *                 "overrides": { 参数取值: {轴:值,…} },
     *                 "provenance": "authored",
     *                 "reason": "为什么这么打（供人复核）" } } }
     * </pre>
     */
    public static Map<String, VectorEntry> toVectors(JsonObject root) {
        Map<String, VectorEntry> out = new java.util.TreeMap<>();
        if (!root.has("vectors")) {
            return out;
        }
        for (var en : root.getAsJsonObject("vectors").entrySet()) {
            JsonObject e = en.getValue().getAsJsonObject();
            NeedVector def = null;
            if (e.has("vector") && !e.get("vector").isJsonNull()) {
                def = toVector(e.getAsJsonObject("vector"));
            }
            Map<String, NeedVector> over = new LinkedHashMap<>();
            if (e.has("overrides")) {
                for (var oe : e.getAsJsonObject("overrides").entrySet()) {
                    over.put(oe.getKey(), toVector(oe.getValue().getAsJsonObject()));
                }
            }
            out.put(en.getKey(),
                    new VectorEntry(def, over, optString(e, "provenance", "authored")));
        }
        return out;
    }

    /**
     * 磁盘目录来源（开发与自测用）。
     * <p>读 {@code <dir>/data/*.json} 与 {@code <dir>/carriers.json}。
     */
    public static CatalogSource directorySource(Path catalogDir) throws IOException {
        Map<String, JsonObject> files = new java.util.TreeMap<>();
        Path dataDir = catalogDir.resolve("data");
        if (Files.isDirectory(dataDir)) {
            try (var s = Files.list(dataDir)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                    files.put(p.getFileName().toString(), read(p));
                }
            }
        }
        Path carriers = catalogDir.resolve(CatalogSource.CARRIERS_FILE);
        if (Files.isRegularFile(carriers)) {
            files.put(CatalogSource.CARRIERS_FILE, read(carriers));
        }
        // ★ 评分表（与动作数据分开存放，见 CatalogSource.VECTORS_FILE）
        Path vectors = catalogDir.resolve(CatalogSource.VECTORS_FILE);
        if (Files.isRegularFile(vectors)) {
            files.put(CatalogSource.VECTORS_FILE, read(vectors));
        }
        // ★★ 执行器账本（解析期据此挡住"没有执行器的幽灵动作"，见 CatalogSource.EXECUTORS_FILE）
        Path executors = catalogDir.resolve(CatalogSource.EXECUTORS_FILE);
        if (Files.isRegularFile(executors)) {
            files.put(CatalogSource.EXECUTORS_FILE, read(executors));
        }
        // ★★ W32：法术意图表
        Path spellIntent = catalogDir.resolve(CatalogSource.SPELL_INTENT_FILE);
        if (Files.isRegularFile(spellIntent)) {
            files.put(CatalogSource.SPELL_INTENT_FILE, read(spellIntent));
        }
        return new CatalogSource() {
            @Override
            public Map<String, JsonObject> files() {
                return files;
            }

            @Override
            public String describe() {
                return "磁盘目录 " + catalogDir.toAbsolutePath() + "（" + files.size() + " 个文件）";
            }
        };
    }

    /** 从工作目录下的 {@code catalog/} 加载（自测用）。 */
    public static Loaded loadFromWorkingDir(Path catalogDir) throws IOException {
        return load(directorySource(catalogDir));
    }

    private static JsonObject read(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    // ───────────────────────── 动作 ─────────────────────────

    public static List<ActionSpec> loadActions(Path file) throws IOException {
        JsonObject root = read(file);
        JsonArray ops = root.getAsJsonArray("operations");
        List<ActionSpec> out = new ArrayList<>(ops.size());
        for (JsonElement e : ops) {
            out.add(toSpec(e.getAsJsonObject()));
        }
        return out;
    }

    private static ActionSpec toSpec(JsonObject o) {
        String id = o.get("id").getAsString();
        String kind = optString(o, "kind", "atomic");
        String familyOf = optString(o, "familyOf", null);
        String carrier = optString(o, "carrier", null);
        String sourceMod = optString(o, "sourceMod", null);
        String reach = optString(o, "reach", "RA");
        String display = id;
        if (o.has("displayName")) {
            JsonElement dn = o.get("displayName");
            if (dn.isJsonObject() && dn.getAsJsonObject().has("zh_cn")) {
                display = dn.getAsJsonObject().get("zh_cn").getAsString();
            } else if (dn.isJsonPrimitive()) {
                display = dn.getAsString();
            }
        }

        // 参数名（A 档参数化要向物品问哪些键）
        List<String> paramNames = new ArrayList<>();
        if (o.has("params")) {
            paramNames.addAll(o.getAsJsonObject("params").keySet());
        }

        NeedVector def = o.has("vector") ? toVector(o.getAsJsonObject("vector")) : null;
        Map<String, NeedVector> overrides = new LinkedHashMap<>();
        if (o.has("vectorOverrides")) {
            for (var en : o.getAsJsonObject("vectorOverrides").entrySet()) {
                overrides.put(en.getKey(), toVector(en.getValue().getAsJsonObject()));
            }
        }

        List<Map<String, Object>> pres = new ArrayList<>();
        if (o.has("preconditions")) {
            for (JsonElement e : o.getAsJsonArray("preconditions")) {
                pres.add(toFlatMap(e.getAsJsonObject()));
            }
        }

        List<String> stateReq = new ArrayList<>();
        if (o.has("stateReq")) {
            for (JsonElement e : o.getAsJsonArray("stateReq")) {
                stateReq.add(e.getAsJsonObject().get("type").getAsString());
            }
        }

        Set<String> excl = new java.util.LinkedHashSet<>();
        if (o.has("exclusivity")) {
            for (JsonElement e : o.getAsJsonArray("exclusivity")) {
                excl.add(e.getAsJsonObject().get("group").getAsString());
            }
        }

        // 承诺时长：duration.kind + ticks / sustainTicks
        int commit = 0;
        boolean interruptible = true;
        if (o.has("duration")) {
            commit = commitOf(o.getAsJsonObject("duration"));
        }
        // ★ durationOverrides：按参数取值覆写承诺形状（见 docs/09 §2.4）
        Map<String, Integer> commitOverrides = new LinkedHashMap<>();
        if (o.has("durationOverrides")) {
            for (var en : o.getAsJsonObject("durationOverrides").entrySet()) {
                commitOverrides.put(en.getKey(), commitOf(en.getValue().getAsJsonObject()));
            }
        }
        if (o.has("interruptible")) {
            JsonObject i = o.getAsJsonObject("interruptible");
            if (i.has("value")) {
                interruptible = i.get("value").getAsBoolean();
            }
        }

        // ★ 最小重复间隔（节奏/冷却）：瞬时动作若无此值 ⇒ 决策层每周期都会再选它 ⇒ 刷屏。
        //   见 ActionSpec#effectiveCooldownTicks 的兜底规则。
        int cooldown = optInt(o, "cooldownTicks", 0);

        // ★★ 2026-10-03：读清单里【早就写好却一直没人读】的连段前驱（comboDeps）
        //   —— 决策层用它做"奖励连段 / 惩罚复读"（见 ActionSelector#chainAdjust）。
        List<String> comboPreds = new ArrayList<>();
        if (o.has("comboDeps") && o.get("comboDeps").isJsonArray()) {
            for (var dep : o.getAsJsonArray("comboDeps")) {
                if (dep.isJsonObject() && dep.getAsJsonObject().has("action")) {
                    comboPreds.add(dep.getAsJsonObject().get("action").getAsString());
                }
            }
        }

        return new ActionSpec(id, kind, familyOf, carrier, sourceMod, reach, display,
                paramNames, def, overrides, pres, stateReq, excl, commit, interruptible, commitOverrides,
                cooldown, optString(o, "role", "choice"), comboPreds);
    }

    /**
     * 由一个 duration 对象算出"承诺 tick 数"（INSTANT = 0）。
     *
     * <p>★★ <b>2026-09-30 修：旧实现只在 {@code CHANNEL} 分支读 {@code chargeTicks}</b>，
     * 于是 {@code slashblade:slash_art} 那样写 {@code {"kind":"COMMIT","chargeTicks":9}} 的条目
     * <b>静默算成 0</b> ⇒ 门控失效 + 收尾永不触发。
     *
     * <p>现在<b>按键名取值，不按 kind 取值</b>：
     * <ol>
     *   <li>{@code ticks}（最直接）</li>
     *   <li>否则 {@code chargeTicks + sustainTicks}（前摇 + 持续）</li>
     *   <li>都没有 ⇒ 0（真·瞬发）</li>
     * </ol>
     * ★ 这是"写错会被静默忽略"的又一处实例 —— 与轴名写错被 Gson 静默忽略同型
     * （见 docs/12 §10 的不变量表）。因此新增校验脚本 {@code tools/audit_durations.py}
     * 把"有执行器但承诺=0"的动作显式列出来。
     */
    private static int commitOf(JsonObject d) {
        int ticks = optInt(d, "ticks", 0);
        if (ticks > 0) {
            return ticks;
        }
        return optInt(d, "chargeTicks", 0) + optInt(d, "sustainTicks", 0);
    }

    /** 把前置条件压成扁平 map（只取顶层标量字段）。 */
    private static Map<String, Object> toFlatMap(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (var en : o.entrySet()) {
            JsonElement v = en.getValue();
            if (v.isJsonPrimitive()) {
                m.put(en.getKey(), v.getAsString());
            } else if (v.isJsonObject()) {
                JsonObject sub = v.getAsJsonObject();
                // note.zh_cn 提升为 note，便于日志
                if (sub.has("zh_cn")) {
                    m.put(en.getKey(), sub.get("zh_cn").getAsString());
                }
            }
        }
        return m;
    }

    private static NeedVector toVector(JsonObject v) {
        List<Object> pairs = new ArrayList<>();
        for (NeedAxis axis : NeedAxis.values()) {
            if (v.has(axis.name())) {
                pairs.add(axis);
                pairs.add(v.get(axis.name()).getAsDouble());
            }
        }
        if (pairs.isEmpty()) {
            return NeedVector.zeros();
        }
        return NeedVector.of(pairs.toArray());
    }

    // ───────────────────────── 载体规则 ─────────────────────────

    public static List<CarrierRule> loadRules(Path file) throws IOException {
        return toRules(read(file));
    }

    /** 从一个已解析的 JSON 根对象里取出载体规则（供 {@link #load} 复用）。 */
    public static List<CarrierRule> toRules(JsonObject root) {
        List<CarrierRule> out = new ArrayList<>();
        if (!root.has("rules")) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray("rules")) {
            JsonObject r = e.getAsJsonObject();
            String carrier = r.get("carrier").getAsString();
            String mod = optString(r, "requiresMod", null);
            Set<SlotKind> slots = EnumSet.noneOf(SlotKind.class);
            if (r.has("slots")) {
                for (JsonElement s : r.getAsJsonArray("slots")) {
                    slots.add(SlotKind.valueOf(s.getAsString()));
                }
            }
            CarrierRule.Matcher m = toMatcher(r.getAsJsonObject("match"));
            // ★ 站位偏好（步法的输入）—— 没写就交给 CarrierRule 的兜底值
            double range = r.has("preferredRange") ? r.get("preferredRange").getAsDouble() : 0;
            out.add(new CarrierRule(carrier, mod, slots, m, null, range));
        }
        return out;
    }

    private static CarrierRule.Matcher toMatcher(JsonObject m) {
        String kind = m.get("kind").getAsString();
        return switch (kind) {
            case "always" -> new CarrierRule.Matcher.Always();
            case "itemId" -> new CarrierRule.Matcher.ByItemId(
                    m.get("itemId").getAsString(),
                    m.has("fallback") ? toMatcher(m.getAsJsonObject("fallback")) : null);
            case "itemTag" -> new CarrierRule.Matcher.ByItemTag(m.get("tag").getAsString());
            case "javaInstanceOf" -> new CarrierRule.Matcher.ByType(
                    m.get("target").getAsString(),
                    !m.has("matchMode") || "simpleName".equals(m.get("matchMode").getAsString()));
            case "capability" -> new CarrierRule.Matcher.ByCapability(m.get("key").getAsString());
            case "itemAttribute" -> new CarrierRule.Matcher.ByAttribute(m.get("attribute").getAsString());
            default -> new CarrierRule.Matcher.Unresolved(
                    optString(m, "reason", "未知的 match.kind=" + kind));
        };
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static String optString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int optInt(JsonObject o, String key, int def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }
}
