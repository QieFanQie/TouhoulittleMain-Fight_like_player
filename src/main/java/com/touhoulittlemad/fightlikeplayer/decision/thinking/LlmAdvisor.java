package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>LLM 动态指挥</b> —— 用<b>真正的 LLM</b>（chat 模型）决定"她该是什么脾气"。
 *
 * <h2>★★ 与「调控顾问」（{@link Advisor}）的分工 —— 两者不是一回事</h2>
 * <table border="1">
 *   <tr><th></th><th>{@link Advisor}（JEV 调风格）</th><th>本类（LLM 动态指挥）</th></tr>
 *   <tr><td>用哪个模型</td><td>jev-1.13.0（<b>打分</b>模型，只答 score）</td>
 *       <td><b>chat 模型</b>（deepseek-chat 等，会读提示词、会写话）</td></tr>
 *   <tr><td>输入</td><td>每个旋钮一个 {@code score} 问题</td>
 *       <td><b>一大段可编辑的提示词</b> + 当前行为统计 + 旋钮表</td></tr>
 *   <tr><td>输出</td><td>0~4 的分数（映射是代码写死的）</td>
 *       <td><b>自然语言</b>（我们只从里面抠一个 JSON 补丁）</td></tr>
 *   <tr><td>能表达什么</td><td>"这个旋钮调高/调低/不动"</td>
 *       <td>★ <b>带理由的风格决定</b>：可以写"她老是被围就往后站一点，别贴脸"</td></tr>
 * </table>
 *
 * <p>★ 为什么两条都留着：它们是**两种不同的接口形态**，各有代价 ——
 * 打分模型不会有解析风险但也表达不了理由；LLM 能表达理由但输出必须**容错解析**。
 * 委托方要的是后者（"llm 动态指挥"），而本轮之前项目里只有前者。
 *
 * <h2>★ 输出的容错解析（这是本类唯一需要小心的部分）</h2>
 * LLM 会写一段话，中间夹一个 JSON。⇒ {@link #parsePatch} 的规则：
 * <ol>
 *   <li>先从回复里抠出**第一个平衡的 {@code {...}}**（支持 ```json 围栏、支持前后有解释文字）；</li>
 *   <li>只认白名单里的键（{@link TuningBus} 自己那份），**多写的键直接丢**并登记；</li>
 *   <li>值必须是数字（或能解析成数字的字符串），**越界由 {@code TuningBus} 钳制**；</li>
 *   <li>抠不到 / 不是 JSON / 全是无效键 ⇒ 返回空表（<b>这一轮算"模型没给建议"</b>，绝不乱猜）。</li>
 * </ol>
 * ⇒ <b>模型写错永远不会让游戏崩，只会让"这一轮的建议被丢弃"，而且原因可查。</b>
 */
public final class LlmAdvisor {

    private LlmAdvisor() {
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final java.lang.reflect.Type MAP_TYPE =
            new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>() {
            }.getType();

    /**
     * 默认系统提示词（**可编辑**，配置界面第 ④ 页那个多行框）。
     *
     * <p>★ 写法上的三条讲究（都是为了让"模型乱写"的概率尽量低）：
     * ① 明确它<b>不能</b>决定单步动作；② 明确**只输出 JSON**、并给出确切格式；
     * ③ 给出可调键名与取值范围，免得它编造键名。
     */
    public static final String DEFAULT_PROMPT = """
            你是一名 Minecraft 战斗女仆的【战术指挥官】。
            你不能指定她这一秒做什么动作（那由她的决策层负责），
            你只能通过【下达/取消指令】来管理她的战斗方式。

            你会收到一段 JSON：
            - `directives_available`：**所有可用的指令**（含 id、类型、参数、`base_on` 与「强制手段」说明）
            - `directives_active`   ：**当前正在生效的持续指令**（含剩余时间）
            - `scene`               ：**她此刻的具体处境**（这是你下达指令的**主要依据**）
            - `recent_behaviour`    ：她最近一段时间的战斗统计（200 tick 滑窗）

            `scene` 里有：
            - `self`      ：她的血量、是否着火、**正在做的动作**（`in_flight_action`，空 = 闲着）、
                            当前步法、与目标的距离、双手物品
            - `equipment` ：她到底有没有近战武器/法杖/法术书/枪/弓/盾/灭火器，背包里几件东西
            - `spells_available`：**她真正拥有的**法术与聚晶（`label` 是它的名字、`category` 是类别）。
                            若某一颗带 `blocked_by_your_directive: true`，
                            意思是「**你自己下的指令**正在挡着它」—— 那不是她没这法术。
            - `servants`  ：她现在的仆从数
            - `owner`     ：主人的血量与距离（-1 = 没有主人）
            - `battle`    ：有无目标、周围敌人数量、最近敌人距离、目标血量

            请只输出一个 JSON 对象：
            {
              "issue":  [ {"id": "指令id", "params": {"参数名": 数值}} , ... ],
              "cancel": [ "指令id", ... ],
              "why":    "一句话依据"
            }

            规则：
            1. 只输出 JSON，不要解释、不要 Markdown 代码块、不要多余文字。
            2. `id` 必须来自 `directives_available`；**不要编造**。
            3. 不想改的指令不要出现在 issue/cancel 里；没有要改的就输出空数组。
            4. 参数必须落在 `directives_available` 给出的 min~max 内。
            5. ★ **下任何指令之前，先看那条指令的 `base_on` 列出的字段**，并到 `scene` 里读它们。
               例：`magic_only` 的 `base_on` 含 `spells_available` —— 若她的法术池是空的，
               **不要**下 `magic_only`（那等于让她什么都别做）。
            6. `ban_focus` / `focus_category` 的参数必须取 `scene.spells_available` 里**真实存在**的
               `label` / `category`；不要凭印象写一个法术名。
            7. 结合 `recent_behaviour` 与 `scene` 一起判断：
               - 频繁受伤 ⇒ 考虑 keep_distance（拉开）或 stance_guard（举盾，需 `equipment.has_shield`）
               - 远程武器在手却被人贴着打 ⇒ keep_distance；贴不上去 ⇒ close_in
               - 主人被打（`owner.health_pct` 低或 `owner.distance` 小）⇒ protect_owner
               - 她正在引导/蓄力（`in_flight_action` 非空）而你希望她改手段 ⇒ 先 interrupt
            8. 幅度克制：一次最多下达 2 条、取消 2 条。
            9. ★★ **仆从那三条你不许自作主张**（委托方第二十一条要求）：
               - `dismiss_servants`（清除仆从）是**不可逆的处死**；`no_summons`（不许召唤）
                 会让她的召唤类法术全部失效 —— 这两条**只有主人明说才允许**，
                 而你**看不到主人说了什么** ⇒ 你**永远不要**下它们（代码也会直接拒绝）。
               - `recall_servants`（召集仆从）只是把她们叫回来，可以下（例：她们被卡在远处）。
               - 主人要「清除/召集仆从」时，会直接对她本人说 —— 那不是你的活。
            """;

    /** 解析结果：补丁 + 被丢弃的键 + 为什么（诊断出口）。 */
    public record Parsed(Map<String, Double> patch, List<String> droppedKeys, String note) {

        public static Parsed empty(String note) {
            return new Parsed(Map.of(), List.of(), note);
        }

        public boolean isEmpty() {
            return patch.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (patch.isEmpty()) {
                sb.append("（没有可用的建议）");
            } else {
                for (Map.Entry<String, Double> e : patch.entrySet()) {
                    if (sb.length() > 0) {
                        sb.append('　');
                    }
                    sb.append(e.getKey()).append('→')
                            .append(String.format(java.util.Locale.ROOT, "%.2f", e.getValue()));
                }
            }
            if (!droppedKeys.isEmpty()) {
                sb.append("　★ 丢弃了 ").append(droppedKeys.size())
                        .append(" 个无法识别的键：").append(String.join(",", droppedKeys));
            }
            if (note != null && !note.isBlank()) {
                sb.append("　（").append(note).append("）");
            }
            return sb.toString();
        }
    }

    /** 发出去的 user 内容（= 喂给模型的那段 JSON）。 */
    /**
     * ★★ 同上，但带上【指令】（十三轮，docs/15 的 M4）与【战斗场景】（十四轮核实）。
     *
     * @param activeDirectivesJson {@code directives_active} 的 JSON（由 {@code DirectiveBus#activeJson} 生成）；
     *                             {@code null} = 没有正在生效的指令
     * @param scene                ★ 她此刻的**具体处境**（{@link CombatScene}）；{@code null} = 不带（自测/退回）
     */
    public static String userContent(BehaviorStats stats, TuningBus bus, String lastProposal,
                                     String activeDirectivesJson, CombatScene.Scene scene) {
        TuningBus b = bus == null ? TuningBus.global() : bus;
        Map<String, Object> out = new LinkedHashMap<>();
        // ★★ 第 14 轮核实：统计量回答"她最近打得怎么样"，
        //   但"她现在手上有什么、池子里有什么法术、主人在哪"这些**场景事实**此前**没给过**
        //   ⇒ 于是 magic_only / focus_category / conserve_ammo / protect_owner 这些指令，
        //     模型没有依据去下、也没有依据去撤。现在它在这段 JSON 里。
        if (scene != null) {
            out.put("scene", scene.facts());
        }
        out.put("recent_behaviour", stats == null ? Map.of() : stats.toState());
        out.put("directives_active", activeDirectivesJson == null ? "[]" : activeDirectivesJson);
        out.put("directives_available_note",
                "可用指令在下面的 instructions 里（由代码自动生成）；"
                        + "你只能从里面选 id。");
        Map<String, Object> knobs = new LinkedHashMap<>();
        for (TuningBus.Knob k : b.knobs()) {
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("now", b.get(k.key()));
            kv.put("default", k.def());
            kv.put("min", k.min());
            kv.put("max", k.max());
            kv.put("zh", k.zhName());
            knobs.put(k.key(), kv);
        }
        out.put("knobs_note", "（评分类的旋钮已由 JEV 负责，你不需要动它们；仅供参考）");
        out.put("knobs", knobs);
        if (lastProposal != null && !lastProposal.isBlank()) {
            out.put("last_round_you_said", lastProposal);
        }
        return GSON.toJson(out);
    }

    public static String userContent(BehaviorStats stats, TuningBus bus, String lastProposal,
                                     String activeDirectivesJson) {
        return userContent(stats, bus, lastProposal, activeDirectivesJson, null);
    }

    public static String userContent(BehaviorStats stats, TuningBus bus, String lastProposal) {
        TuningBus b = bus == null ? TuningBus.global() : bus;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recent_behaviour", stats == null ? Map.of() : stats.toState());
        Map<String, Object> knobs = new LinkedHashMap<>();
        for (TuningBus.Knob k : b.knobs()) {
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("now", b.get(k.key()));
            kv.put("default", k.def());
            kv.put("min", k.min());
            kv.put("max", k.max());
            kv.put("zh", k.zhName());
            knobs.put(k.key(), kv);
        }
        out.put("knobs", knobs);
        if (lastProposal != null && !lastProposal.isBlank()) {
            out.put("last_round_you_said", lastProposal);
        }
        return GSON.toJson(out);
    }

    /**
     * ★★ <b>从模型的自然语言回复里抠出补丁</b>（容错解析，见类注释的四条规则）。
     */
    public static Parsed parsePatch(String reply, TuningBus bus) {
        if (reply == null || reply.isBlank()) {
            return Parsed.empty("回复是空的");
        }
        TuningBus b = bus == null ? TuningBus.global() : bus;
        String json = extractFirstJsonObject(reply);
        if (json == null) {
            return Parsed.empty("回复里没有 JSON 对象（这一轮只记录）");
        }
        Map<String, Object> raw = null;
        try {
            raw = GSON.fromJson(json, MAP_TYPE);
        } catch (RuntimeException e) {
            return Parsed.empty("抠出来的片段解析失败：" + e.getMessage());
        }
        if (raw == null) {
            return Parsed.empty("抠出来的片段不是合法 JSON");
        }
        Map<String, Double> patch = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            if (!isTunable(b, key)) {
                dropped.add(key);
                continue;
            }
            Double v = asDouble(e.getValue());
            if (v == null) {
                dropped.add(key + "(非数字)");
                continue;
            }
            // ★ 越界由总线钳制（它自己会记下"被钳制"这件事）——
            //   这里只做"显然离谱"的拒绝：NaN/Infinity
            if (v.isNaN() || v.isInfinite()) {
                dropped.add(key + "(NaN)");
                continue;
            }
            patch.put(key, v);
        }
        return new Parsed(Map.copyOf(patch), List.copyOf(dropped), null);
    }

    // ────────────── ★★ 指令订单（docs/15 的 M4）──────────────

    /** 一次指令订单：要下达的 + 要取消的 + 理由 + 被丢弃的。 */
    public record Orders(java.util.List<Issue> issue, java.util.List<String> cancel,
                         String why, java.util.List<String> dropped) {

        /** 一条待下达的指令。 */
        public record Issue(String id, Map<String, Double> params, Map<String, String> texts) {
        }

        public boolean isEmpty() {
            return issue.isEmpty() && cancel.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Issue i : issue) {
                if (sb.length() > 0) {
                    sb.append("，");
                }
                sb.append("+下达 ").append(i.id());
                if (!i.params().isEmpty()) {
                    sb.append(i.params());
                }
            }
            for (String c : cancel) {
                if (sb.length() > 0) {
                    sb.append("，");
                }
                sb.append("-取消 ").append(c);
            }
            if (sb.length() == 0) {
                sb.append("（没有要改的指令）");
            }
            if (why != null && !why.isBlank()) {
                sb.append("　依据：").append(why);
            }
            if (!dropped.isEmpty()) {
                sb.append("　★ 丢弃了 ").append(dropped.size())
                        .append(" 项：").append(String.join(",", dropped));
            }
            return sb.toString();
        }
    }

    /**
     * ★★ <b>从模型的回复里抠出指令订单</b>（容错解析）。
     *
     * <p>规则与旋钮补丁同一条纪律（见类注释），另加三条指令专属的：
     * <ol>
     *   <li><b>id 必须在白名单里</b>（编造的直接丢弃并登记）；</li>
     *   <li><b>参数按规格钳制</b>（越界不报错，钳到边界）；</li>
     *   <li><b>cancel 先于 issue 应用</b>（在调用方保证）—— 否则"换指令"会先下后取，白忙一场。</li>
     * </ol>
     */
    public static Orders parseOrders(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Orders(java.util.List.of(), java.util.List.of(), null,
                    java.util.List.of("回复是空的"));
        }
        String json = extractFirstJsonObject(reply);
        if (json == null) {
            return new Orders(java.util.List.of(), java.util.List.of(), null,
                    java.util.List.of("回复里没有 JSON 对象"));
        }
        Map<String, Object> raw;
        try {
            raw = GSON.fromJson(json, MAP_TYPE);
        } catch (RuntimeException e) {
            return new Orders(java.util.List.of(), java.util.List.of(), null,
                    java.util.List.of("抠出的片段不是合法 JSON"));
        }
        if (raw == null) {
            return new Orders(java.util.List.of(), java.util.List.of(), null,
                    java.util.List.of("解析结果为空"));
        }
        java.util.List<String> dropped = new java.util.ArrayList<>();
        java.util.List<Orders.Issue> issue = new java.util.ArrayList<>();
        Object issueObj = raw.get("issue");
        if (issueObj instanceof java.util.List<?> list) {
            for (Object o : list) {
                Orders.Issue parsed = parseOneIssue(o, dropped);
                if (parsed != null) {
                    issue.add(parsed);
                }
            }
        } else if (issueObj != null) {
            dropped.add("issue(不是数组)");
        }
        java.util.List<String> cancel = new java.util.ArrayList<>();
        Object cancelObj = raw.get("cancel");
        if (cancelObj instanceof java.util.List<?> list) {
            for (Object o : list) {
                String id = String.valueOf(o);
                if (com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.exists(id)) {
                    cancel.add(id);
                } else {
                    dropped.add("cancel:" + id);
                }
            }
        }
        String why = raw.get("why") == null ? null : String.valueOf(raw.get("why"));
        return new Orders(java.util.List.copyOf(issue), java.util.List.copyOf(cancel), why,
                java.util.List.copyOf(dropped));
    }

    /** 解析 issue 里的一项（不合法 ⇒ null + 登记）。 */
    private static Orders.Issue parseOneIssue(Object o, java.util.List<String> dropped) {
        if (!(o instanceof Map<?, ?> m)) {
            String id = String.valueOf(o);          // 只给了 id 字符串（容错）
            if (com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.exists(id)) {
                return new Orders.Issue(id, Map.of(), Map.of());
            }
            dropped.add("issue:" + id);
            return null;
        }
        String id = m.get("id") == null ? null : String.valueOf(m.get("id"));
        var spec = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.find(id);
        if (spec == null) {
            dropped.add("issue:" + id);
            return null;
        }
        Map<String, Double> params = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        Object p = m.get("params");
        if (p instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                String key = String.valueOf(e.getKey());
                var ps = spec.params() == null ? null : spec.params().get(key);
                if (ps == null) {
                    dropped.add(id + "." + key);
                    continue;
                }
                if (ps.text()) {
                    texts.put(key, String.valueOf(e.getValue()));
                } else {
                    Double v = asDouble(e.getValue());
                    if (v == null) {
                        dropped.add(id + "." + key + "(非数字)");
                    } else {
                        params.put(key, ps.clamp(v));
                    }
                }
            }
        }
        return new Orders.Issue(id, Map.copyOf(params), Map.copyOf(texts));
    }

    /** 键是不是"可调的旋钮"（白名单 = 总线自己那份）。 */
    public static boolean isTunable(TuningBus bus, String key) {
        if (bus == null || key == null) {
            return false;
        }
        for (TuningBus.Knob k : bus.knobs()) {
            if (k.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★ 抠出回复里<b>第一个平衡的</b> {@code {...}}。
     *
     * <p>要处理的三种真实形态：
     * <pre>
     *   {"satisfaction.damage": 0.6}                          ← 纯 JSON
     *   ```json\n{...}\n```                                   ← 代码块
     *   她的问题在于太激进。{...} 建议这样调。                 ← 前后有解释
     * </pre>
     * ★ 必须**按括号配对**扫，不能"找第一个 { 和最后一个 }" ——
     * 后者在"回复里有两个 JSON"或"解释文字里带 }"时会抠出一段非法文本。
     */
    public static String extractFirstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;                    // 跳过转义字符
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
            start = text.indexOf('{', start + 1);   // 这一段不配对 ⇒ 从下一个 { 再试
        }
        return null;
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
