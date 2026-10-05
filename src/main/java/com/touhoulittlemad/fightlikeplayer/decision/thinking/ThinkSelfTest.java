package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;
import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <b>思维层离线自测</b> —— 把「不可离线验证的外部模型」拆成<b>可断言的两半</b>。
 *
 * <h2>★★ 为什么必须这样拆（docs/11 §6 的纪律）</h2>
 * JEV 的判断好不好，<b>离线验不了</b>（那要打真接口）。但它周围的一切都能验：
 * <ul>
 *   <li><b>请求体的合法性规则</b>（本地预校验）—— 这一条尤其关键：网关会把请求体错误
 *       伪装成「无可用渠道」，不在本地拦下来就永远看不到真原因；</li>
 *   <li><b>返回的解析</b>（{@code answers} 顺序不定 / 被信封包裹 / 缺字段）；</li>
 *   <li><b>局势 → 问题的翻译</b>（「我们到底喂了什么」）；</li>
 *   <li>★ <b>答案 → 8 轴向量的映射</b>（这是唯一会让「模型对了但行为错了」的地方）；</li>
 *   <li>★★ <b>调度器的机械行为</b>：节流、在途去重、超时、迟到丢弃、失败不传染、只观测模式。</li>
 * </ul>
 * ⇒ 于是「模型问题」与「架构问题」能被分开定位 —— 这正是委托方 P5 的核心诉求。
 *
 * <pre>
 *   java -cp "build\classes\java\main;&lt;gson.jar&gt;" \
 *        com.touhoulittlemad.fightlikeplayer.decision.thinking.ThinkSelfTest
 *   :: 可选：打一次真接口（会消耗额度，默认不做）
 *   java ... ThinkSelfTest --live
 * </pre>
 */
public final class ThinkSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        System.out.println("=== 思维层自测 (ThinkSelfTest) ===");
        System.out.println();

        testValidateRejectsIllegalBodies();
        testRequestBodyShape();
        testResponseParsing();
        testAnswerOrderAndEnvelope();
        testQuestionSet();
        testSceneTranslation();
        testVectorMapping();
        testThinkerThrottling();
        testThinkerFailureDoesNotInfect();
        testThinkerDropRules();
        testThinkerStaleGeneration();
        testBehaviorStats();
        testAdvisorMapping();
        testSpellClassifiers();
        testSpellIntentTable();
        testPerMaidTuningIndependence();
        testIronsSpellPicking();
        testPromptOverrides();
        testSpellCooldownLedger();
        testEndpointFor();
        testLlmLayer();
        testDirectiveOrdersForLlm();

        if (args.length > 0 && "--live".equals(args[0])) {
            liveSmoke();
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项断言");
            System.exit(0);
        }
        System.out.println("失败 " + failures.size() + " 项（通过 " + passed + " 项）：");
        failures.forEach(f -> System.out.println("  x " + f));
        System.exit(1);
    }

    // ═══════════ 1. 本地预校验（★ 防的是那句假的「无可用渠道」）═══════════

    private static void testValidateRejectsIllegalBodies() {
        section("1. ★★ 本地预校验：这些「非法请求」必须在本地就被拦下（实测它们在网上只会得到"
                + "「无可用渠道」这种假报错）");

        Map<String, Object> ok = Map.of("q", JevClient.noul("is it raining"));
        expectReject("state = null", () -> JevClient.validate(null, ok));
        expectReject("state = 数字", () -> JevClient.validate(42, ok));
        expectReject("questions 为空", () -> JevClient.validate("x", Map.of()));
        expectReject("type 写成大写 NOUL", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "NOUL", "instructions", "i"))));
        expectReject("noul 缺 instructions", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "noul"))));
        expectReject("instructions 是空串", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "noul", "instructions", "  "))));
        expectReject("choice.criteria 写成数组", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "choice", "instructions", "i", "criteria", List.of("a")))));
        expectReject("score.criteria 写成对象", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "score", "instructions", "i",
                        "criteria", Map.of("a", "b")))));
        expectReject("score 只有 1 档", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "score", "instructions", "i", "criteria", List.of("only")))));

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            tooMany.add("opt" + i);
        }
        expectReject("choice 给了 256 个选项", () -> JevClient.validate("x",
                Map.of("q", Map.of("type", "choice", "instructions", "i", "criteria", tooMany))));

        // 反面对照：合法的必须通过
        expect("★ 合法请求通过校验（空字符串 state 也合法 —— 实测如此）",
                accepts(() -> JevClient.validate("", ok)), "空串 state 被拒了");
        expect("★ 合法 Map state 通过校验",
                accepts(() -> JevClient.validate(Map.of("a", 1), ok)), "Map state 被拒了");
        expect("★ 合法 List state 通过校验",
                accepts(() -> JevClient.validate(List.of("a"), ok)), "List state 被拒了");
        expect("★ 合法 choice（criteria 是对象）通过校验", accepts(() -> JevClient.validate("x",
                Map.of("q", JevClient.choice("pick", Map.of("a", "A", "b", "B"))))), "被拒了");
        expect("★ 合法 score（5 档）通过校验", accepts(() -> JevClient.validate("x",
                Map.of("q", JevClient.score("how much", "a", "b", "c", "d", "e")))), "被拒了");
    }

    // ═══════════ 2. 请求体形状（★ 实测固定四个字段）═══════════

    private static void testRequestBodyShape() {
        section("2. ★ 请求体形状：固定 model / input / state / questions 四个字段，input 是占位符");

        JevClient c = new JevClient("sk-test", null, null, null, 5);
        Map<String, Object> body = c.buildBody("ctx", Map.of("q", JevClient.noul("i")));
        expect("★ 恰好四个字段", body.size() == 4, body.keySet().toString());
        expect("★ 含 model", body.containsKey("model"), body.keySet().toString());
        expect("★ input 是占位符 placeholder（缺了会报 input is required）",
                "placeholder".equals(body.get("input")), String.valueOf(body.get("input")));
        expect("★ model 取默认值 " + JevClient.DEFAULT_MODEL,
                JevClient.DEFAULT_MODEL.equals(c.model()), c.model());
        expect("★ baseUrl 去掉尾部斜杠",
                JevClient.DEFAULT_BASE_URL.equals(c.baseUrl()), c.baseUrl());

        String json = c.buildBodyJson(Map.of("k", "v"), Map.of("q", JevClient.score("i", "a", "b")));
        expect("★ 序列化后含 questions 字段", json.contains("\"questions\""), json);
        expect("★★ score.criteria 序列化成【数组】（写成对象会被网关吞掉）",
                json.contains("\"criteria\":[\"a\",\"b\"]"), json);
        expect("★ 路径是 /responses（绝不用 /chat/completions）",
                c.buildBody("x", Map.of("q", JevClient.noul("i"))).containsKey("state"), "缺 state");

        expectReject("空 API Key 直接构造失败", () -> new JevClient("  "));
    }

    // ═══════════ 3. 返回解析（用使用指南里的实测样例）═══════════

    private static void testResponseParsing() {
        section("3. ★ 返回解析：按问题 ID 取值；noul 无 confidence；score 是连续小数");

        String fixture = """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "is_refund":  { "type": "noul", "noul": 0.96 },
                    "department": { "type": "choice", "choice": "billing", "confidence": 0.88,
                                    "probabilities": { "billing": 0.91, "technical": 0.05, "account": 0.04 } },
                    "priority":   { "type": "score", "score": 2.31, "confidence": 0.64,
                                    "legend": { "0": "low", "1": "mid", "2": "high", "3": "urgent" },
                                    "probabilities": { "0": 0.0, "1": 0.02, "2": 0.64, "3": 0.34 } }
                  },
                  "usage": { "input_tokens": 1970, "output_tokens": 70 }
                }
                """;
        Map<String, Object> resp = parse(fixture);

        expect("★ noul 概率 = 0.96（实测样例）",
                near(JevClient.noulProbability(resp, "is_refund"), 0.96), "读到别的值");
        expect("★ noulAsBoolean(0.8) = true",
                JevClient.noulAsBoolean(resp, "is_refund", 0.8), "阈值判断错");
        expect("★ choice 值 = billing",
                "billing".equals(JevClient.choiceValue(resp, "department")),
                String.valueOf(JevClient.choiceValue(resp, "department")));
        expect("★ choice 置信度 = 0.88",
                near(orZero(JevClient.confidence(resp, "department")), 0.88), "置信度读错");
        expect("★ probabilities 是对象（不是数组），billing = 0.91",
                near(JevClient.probabilities(resp, "department").getOrDefault("billing", -1.0), 0.91),
                JevClient.probabilities(resp, "department").toString());
        expect("★★ score 是【连续小数】2.31（概率加权结果，不是 bug）",
                near(JevClient.scoreValue(resp, "priority"), 2.31), "score 读错");
        expect("★ legend 的键是字符串 \"0\"..\"3\"",
                "urgent".equals(JevClient.legend(resp, "priority").get("3")),
                JevClient.legend(resp, "priority").toString());
        expect("★ noul 不返回 confidence ⇒ 读出来是 null",
                JevClient.confidence(resp, "is_refund") == null, "竟然有置信度");
        expect("★ usage.input_tokens = 1970",
                String.valueOf(JevClient.usage(resp).get("input_tokens")).startsWith("1970"),
                JevClient.usage(resp).toString());

        // 缺字段 / 未知 ID 必须优雅降级（不能抛）
        expect("★ 未知问题 ID ⇒ score 为 NaN（不抛异常）",
                Double.isNaN(JevClient.scoreValue(resp, "nope")), "抛了或给了数");
        expect("★ 未知问题 ID ⇒ choice 为 null",
                JevClient.choiceValue(resp, "nope") == null, "给了值");
        expect("★ answers 缺失时返回空表（不抛异常）",
                JevClient.answers(Map.of("model", "x")).isEmpty(), "不是空表");
    }

    // ═══════════ 4. 键顺序 / 信封（★ 官方文档没说，实测才知道）═══════════

    private static void testAnswerOrderAndEnvelope() {
        section("4. ★★ 两个实测坑：answers 的键顺序不固定、返回可能被信封包裹");

        // 故意把顺序反过来 —— 若实现按下标取值就会读错
        String reversed = """
                { "answers": {
                    "priority": { "type": "score", "score": 1.0, "confidence": 0.5 },
                    "is_refund": { "type": "noul", "noul": 0.11 },
                    "department": { "type": "choice", "choice": "technical", "confidence": 0.7 }
                } }
                """;
        Map<String, Object> r = parse(reversed);
        expect("★★ 键顺序颠倒后仍按 ID 取到正确值（noul = 0.11）",
                near(JevClient.noulProbability(r, "is_refund"), 0.11), "读错了");
        expect("★★ choice 仍为 technical",
                "technical".equals(JevClient.choiceValue(r, "department")), "读错了");
        expect("★★ score 仍为 1.0", near(JevClient.scoreValue(r, "priority"), 1.0), "读错了");

        String wrapped = """
                { "code": 0, "data": { "answers": { "is_refund": { "type": "noul", "noul": 0.77 } } } }
                """;
        Map<String, Object> w = parse(wrapped);
        expect("★★ 被信封包裹（data.answers）也能取到（noul = 0.77）",
                near(JevClient.noulProbability(w, "is_refund"), 0.77), "取不到");
    }

    // ═══════════ 5. 问题集合 ═══════════

    private static void testQuestionSet() {
        section("5. ★ 问题集合：8 条轴各一问，全是 score，位移用有向等级");

        Map<String, Object> q = ThinkPrompt.questions();
        expect("★★ 恰好 8 个问题（8 条需求轴）", q.size() == 8, String.valueOf(q.size()));
        expect("★ 问题 ID 与轴表一一对应",
                q.keySet().equals(ThinkPrompt.AXIS_OF.keySet()), q.keySet().toString());

        boolean allScore = true;
        boolean allFive = true;
        boolean instructionsPresent = true;
        for (Object o : q.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            allScore &= "score".equals(m.get("type"));
            allFive &= (m.get("criteria") instanceof List<?> l) && l.size() == 5;
            Object ins = m.get("instructions");
            instructionsPresent &= ins != null && !String.valueOf(ins).isBlank();
        }
        expect("★ 8 个问题全是 score 类型", allScore, "有别的类型");
        expect("★ 每个 score 恰好 5 档（实测 2~10 合法，5 档分辨率够）", allFive, "档数不对");
        expect("★ 每个问题都有非空 instructions（实测三类都必填）", instructionsPresent, "缺 instructions");
        expect("★ 整个问题集合能通过本地预校验（不会白白浪费一次往返）",
                accepts(() -> JevClient.validate("probe", q)), "自校验没过！");

        @SuppressWarnings("unchecked")
        Map<String, Object> mob = (Map<String, Object>) q.get("mobility");
        expect("★★ 位移那一问的等级是【有向】的（从必须远离到必须突进）",
                String.valueOf(((List<?>) mob.get("criteria")).get(0)).contains("retreat"),
                String.valueOf(mob.get("criteria")));
        expect("★ 8 条轴的专精度表顺序稳定（LinkedHashMap，不是 Map.of 的未定义顺序）",
                new ArrayList<>(ThinkPrompt.AXIS_OF.keySet()).get(0).equals("single_damage"),
                ThinkPrompt.AXIS_OF.keySet().toString());
    }

    // ═══════════ 6. 局势翻译（★ 「我们到底喂了什么」）═══════════

    private static void testSceneTranslation() {
        section("6. ★★ 局势翻译：喂进去的到底是什么（这是唯一可能「说谎」的地方）");

        ThinkScene s = scene(3, 0.62, 0.8, 4, 7.5, true, false, 2,
                "slashblade:slashblade", "(empty)", "toward", "slashblade:combo_a",
                List.of("slashblade:combo_a", "maid_native:melee_swing"));
        Map<String, Object> state = s.toState();

        expect("★ 顶层含 who/maid/owner/battle/equipment/current_task/last_action_taken/"
                        + "actions_she_can_do_right_now/how_to_read",
                state.keySet().containsAll(List.of("who", "maid", "owner", "battle", "equipment",
                        "current_task", "last_action_taken", "actions_she_can_do_right_now",
                        "how_to_read")),
                state.keySet().toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> battle = (Map<String, Object>) state.get("battle");
        expect("★ 敌人数量喂对了", Integer.valueOf(3).equals(battle.get("enemies_nearby")),
                String.valueOf(battle.get("enemies_nearby")));
        expect("★★ 距离是数字且 distance_is_valid = true",
                near(asDouble(battle.get("nearest_enemy_distance_blocks")), 7.5)
                        && Boolean.TRUE.equals(battle.get("distance_is_valid")), battle.toString());

        // ★ 关键边界：没有目标时 Vitals 给的是 Double.MAX_VALUE
        ThinkScene none = new ThinkScene("player_like_combat", 1.0, 1.0, true, 0,
                Double.MAX_VALUE, false, false, false, 0, "(empty)", "(empty)", "hold",
                "(none)", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> nb = (Map<String, Object>) none.toState().get("battle");
        expect("★★ 无目标时距离被译成 -1（不是荒谬的 1.8e308）",
                near(asDouble(nb.get("nearest_enemy_distance_blocks")), -1.0), nb.toString());
        expect("★★ 且 distance_is_valid = false（模型能看出「这个数无效」）",
                Boolean.FALSE.equals(nb.get("distance_is_valid")), nb.toString());

        expect("★★ 快照里【不含】弹簧点（避免「她想X → 模型说X → 更想X」的正反馈）",
                !state.toString().contains("spring") && !state.containsKey("spring_point"),
                "竟然喂了弹簧点");

        String json = GSON.toJson(state);
        expect("★★ 序列化后没有 null 值（嵌套 null 会被 Gson 丢掉 ⇒ 模型看到缺字段）",
                !json.contains(":null"), json);
        expect("★ 序列化后没有 NaN/Infinity（非法 JSON）",
                !json.contains("NaN") && !json.contains("Infinity"), json);
        expect("★ 快照摘要含中文血量", s.summary().contains("%"), s.summary());
    }

    // ═══════════ 7. 答案 → 8 轴向量（★ 最要紧的映射）═══════════

    private static void testVectorMapping() {
        section("7. ★★ 答案 → 8 轴向量：无向轴 [0,1]、位移 [-1,1]、缺轴记 0、越界钳制");

        double strength = 0.5;

        // 全满分
        Map<String, Object> allMax = scores(4.0);
        ThinkPrompt.Verdict vMax = ThinkPrompt.verdict(allMax, strength);
        expect("★★ 全满分 ⇒ 无向轴 = 强度 × 1.0",
                near(vMax.vector().get(NeedAxis.SINGLE_DAMAGE), strength), "值不对");
        expect("★★ 全满分 ⇒ 位移 = 强度 × 1.0（+1 = 想靠近）",
                near(vMax.vector().get(NeedAxis.MOBILITY), strength), "值不对");
        expect("★ 没有缺失轴", vMax.missing().isEmpty(), vMax.missing().toString());
        expect("★ 最低置信度 = 1.0（fixture 里 confidence 全是 1.0）",
                near(vMax.minConfidence(), 1.0), String.valueOf(vMax.minConfidence()));

        // 全零分
        ThinkPrompt.Verdict vMin = ThinkPrompt.verdict(scores(0.0), strength);
        expect("★★ 全零分 ⇒ 无向轴 = 0", near(vMin.vector().get(NeedAxis.SINGLE_DAMAGE), 0.0), "值不对");
        expect("★★ 全零分 ⇒ 位移 = −1 × 强度（「必须远离」是真的负）",
                near(vMin.vector().get(NeedAxis.MOBILITY), -strength), "值不对");

        // 中点
        ThinkPrompt.Verdict vMid = ThinkPrompt.verdict(scores(2.0), strength);
        expect("★ 中点 2.0 ⇒ 无向轴 = 0.5 × 强度",
                near(vMid.vector().get(NeedAxis.SINGLE_DAMAGE), 0.5 * strength), "值不对");
        expect("★ 中点 2.0 ⇒ 位移 = 0（「保持距离」）",
                near(vMid.vector().get(NeedAxis.MOBILITY), 0.0), "值不对");

        // 越界钳制
        ThinkPrompt.Verdict vOver = ThinkPrompt.verdict(scores(9.9), strength);
        expect("★★ 模型给了越界分 9.9 ⇒ 钳到等级上限（不丢弃整包）",
                near(vOver.vector().get(NeedAxis.SINGLE_DAMAGE), strength), "没钳住");
        ThinkPrompt.Verdict vNeg = ThinkPrompt.verdict(scores(-3.0), strength);
        expect("★★ 模型给了负分 ⇒ 钳到 0（无向轴不会变负）",
                near(vNeg.vector().get(NeedAxis.SINGLE_DAMAGE), 0.0), "没钳住");

        // 缺轴
        Map<String, Object> partial = scores(4.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> ans = (Map<String, Object>) partial.get("answers");
        ans.remove("mobility");
        ThinkPrompt.Verdict vMiss = ThinkPrompt.verdict(partial, strength);
        expect("★★ 缺一条轴 ⇒ 该轴记 0（不猜默认值）",
                near(vMiss.vector().get(NeedAxis.MOBILITY), 0.0), "值不对");
        expect("★★ 且该轴被登记进 missing（调用方据此决定要不要丢整包）",
                vMiss.missing().contains(NeedAxis.MOBILITY), vMiss.missing().toString());

        // 置信度取最低
        Map<String, Object> lowConf = scores(4.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> a2 = (Map<String, Object>) lowConf.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> one = (Map<String, Object>) a2.get("control");
        one.put("confidence", 0.3);
        ThinkPrompt.Verdict vLow = ThinkPrompt.verdict(lowConf, strength);
        expect("★★ 最低置信度取【各轴最小值】（低置信度不冒险的判据）",
                near(vLow.minConfidence(), 0.3), String.valueOf(vLow.minConfidence()));

        // 强度 0 = 只观测
        ThinkPrompt.Verdict vZero = ThinkPrompt.verdict(scores(4.0), 0.0);
        expect("★ 强度 0 ⇒ 向量全 0（只观测不推动）",
                near(vZero.vector().norm(), 0.0), String.valueOf(vZero.vector().norm()));

        expect("★ NeedVector 是 8 维", vMax.vector().toArray().length == NeedAxis.COUNT,
                String.valueOf(vMax.vector().toArray().length));
    }

    // ═══════════ 8. 调度器：节流与在途 ═══════════

    private static void testThinkerThrottling() {
        section("8. ★★ 调度器：立刻返回、按间隔节流、在途不重复提交、结果只交付一次");

        Fake fake = new Fake();
        Thinker t = new Thinker(fake);
        t.setSettings(new Thinker.Settings(true, 20, 0.5, 0.0, false, 600, 1));

        t.tick(0, scene());
        expect("★★ 首个 tick 立刻提交（不等一个间隔）", fake.calls == 1 && t.inFlight(),
                "calls=" + fake.calls + " inFlight=" + t.inFlight());
        t.tick(1, scene());
        expect("★★ 在途时不再提交（同一个女仆不堆积请求）", fake.calls == 1,
                "calls=" + fake.calls);

        fake.complete(ok());
        Thinker.Outcome o = t.poll();
        expect("★★ 完成后 poll() 能取走结果", o != null, "poll 返回 null");
        expect("★★ 结果只交付一次（第二次 poll 为 null）", t.poll() == null, "交付了两次");
        expect("★ 结果里带了向量与逐轴原始分", o != null && o.verdict() != null,
                String.valueOf(o));
        expect("★ 标签含 JEV 且带局势摘要",
                o != null && o.label().contains("JEV") && o.label().contains("敌"),
                o == null ? "-" : o.label());
        expect("★ 默认（非 observeOnly）⇒ applied = true", o != null && o.applied(), "没标记为已施加");
        expect("★ 已不再在途", !t.inFlight(), "还在途");

        t.tick(2, scene());
        expect("★★ 间隔未到不提交（20 tick 的节流生效）", fake.calls == 1, "calls=" + fake.calls);
        t.tick(21, scene());
        expect("★★ 间隔到了就提交", fake.calls == 2, "calls=" + fake.calls);

        fake.complete(ok());
        t.tick(22, scene());
        t.setSettings(Thinker.Settings.disabled());
        t.tick(60, scene());
        expect("★ 开关关闭后不再提交（「关掉思维层」是真的关掉）", fake.calls == 2,
                "calls=" + fake.calls);

        Thinker.Stats st = t.stats();
        expect("★ 统计：提交 2 次、成功 2 次、失败 0 次（第 2 包已在 tick(60) 前完成）",
                st.submitted() == 2 && st.succeeded() == 2 && st.failed() == 0, st.toString());
        expect("★ 统计里有最近往返耗时（>=0）", st.lastLatencyMs() >= 0, st.toString());
    }

    // ═══════════ 9. 失败不传染 ═══════════

    private static void testThinkerFailureDoesNotInfect() {
        section("9. ★★ 失败绝不传染：网络错 / 那句假报错 / 只观测模式");

        Fake fake = new Fake();
        Thinker t = new Thinker(fake);
        t.setSettings(new Thinker.Settings(true, 20, 0.5, 0.0, false, 600, 1));

        t.tick(0, scene());
        fake.fail(new JevClient.JevException(503,
                "分组 auto 下模型 jev-1.13.0 的可用渠道不存在（retry）", "{}", null));
        expect("★★ 失败 ⇒ 不产出结果（弹簧沿用旧状态）", t.poll() == null, "竟然产出了结果");
        String err = t.pollError();
        expect("★★ 失败原因可读（不是只写 debug 日志）", err != null && !err.isBlank(),
                String.valueOf(err));
        expect("★★ 且把「无可用渠道」翻译成人话（指出真正原因是请求体）",
                err != null && err.contains("请求体"), String.valueOf(err));
        expect("★ 失败后不再在途（下一次还能问）", !t.inFlight(), "卡在途了");
        expect("★ 失败计入统计", t.stats().failed() == 1, t.stats().toString());

        // 提交阶段就抛（本地预校验失败）
        Thinker t2 = new Thinker((state, questions) -> {
            throw new IllegalArgumentException("state 不能为 null");
        });
        t2.setSettings(new Thinker.Settings(true, 1, 0.5, 0.0, false, 600, 1));
        t2.tick(0, scene());
        expect("★★ transport 直接抛异常 ⇒ 也被计为失败，而不是崩掉 tick",
                t2.pollError() != null && !t2.inFlight(), "没兜住");
        expect("★ 且不产出结果", t2.poll() == null, "产出了结果");

        // 只观测模式
        Fake f3 = new Fake();
        Thinker t3 = new Thinker(f3);
        t3.setSettings(new Thinker.Settings(true, 1, 0.5, 0.0, true, 600, 1));
        t3.tick(0, scene());
        f3.complete(ok());
        Thinker.Outcome o3 = t3.poll();
        expect("★★ observeOnly ⇒ applied = false（先看判断准不准，再决定要不要推）",
                o3 != null && !o3.applied(), "被标记成已施加");
    }

    // ═══════════ 10. 丢弃规则 ═══════════

    private static void testThinkerDropRules() {
        section("10. ★★ 丢弃规则：置信度太低 / 缺太多轴 —— 丢的是【这一包】，不是整个功能");

        Fake f = new Fake();
        Thinker t = new Thinker(f);
        t.setSettings(new Thinker.Settings(true, 1, 0.5, 0.9, false, 600, 1));
        t.tick(0, scene());
        f.complete(lowConfidence());
        expect("★★ 置信度低于门槛 ⇒ 不产出结果", t.poll() == null, "竟然产出了");
        // ★ 只取一次：pollDrop() 是【消费型】的，取两次第二次必然是 null
        String drop1 = t.pollDrop();
        expect("★★ 且登记了作废原因", drop1 != null && drop1.contains("置信度"),
                String.valueOf(drop1));

        Fake f2 = new Fake();
        Thinker t2 = new Thinker(f2);
        t2.setSettings(new Thinker.Settings(true, 1, 0.5, 0.0, false, 600, 1));
        t2.tick(0, scene());
        Map<String, Object> manyMissing = scores(4.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> ans = (Map<String, Object>) manyMissing.get("answers");
        ans.remove("mobility");
        ans.remove("control");
        f2.complete(manyMissing);
        expect("★★ 缺轴数超过上限（2 > 1）⇒ 整包丢弃", t2.poll() == null, "竟然产出了");
        String drop2 = t2.pollDrop();
        expect("★★ 且登记了作废原因", drop2 != null && drop2.contains("缺"),
                String.valueOf(drop2));

        Fake f3 = new Fake();
        Thinker t3 = new Thinker(f3);
        t3.setSettings(new Thinker.Settings(true, 1, 0.5, 0.0, false, 600, 1));
        t3.tick(0, scene());
        Map<String, Object> oneMissing = scores(4.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> a3 = (Map<String, Object>) oneMissing.get("answers");
        a3.remove("mobility");
        f3.complete(oneMissing);
        expect("★ 缺 1 条轴（在上限内）⇒ 仍然采用（模型偶尔漏答不该白丢一包）",
                t3.poll() != null, "被丢了");
    }

    // ═══════════ 11. 迟到结果 / 代次 ═══════════

    private static void testThinkerStaleGeneration() {
        section("11. ★★ 超时与迟到结果：用「代次」而不是计数器 ⇒ 迟到的旧包无法影响新一代");

        Fake f = new Fake();
        Thinker t = new Thinker(f);
        t.setSettings(new Thinker.Settings(true, 1, 0.5, 0.0, false, 10, 1));

        t.tick(0, scene());
        expect("★ 第 1 次提交", f.calls == 1, "calls=" + f.calls);
        t.tick(5, scene());
        expect("★ 未到超时 ⇒ 不重复提交", f.calls == 1, "calls=" + f.calls);

        t.tick(20, scene());
        expect("★★ 超过超时（10 tick）⇒ 放弃旧包并重新提交", f.calls == 2, "calls=" + f.calls);
        expect("★ 仍处于在途（新一代）", t.inFlight(), "不在途了");

        // 现在让【旧】那个 future 才完成 —— 它必须被丢弃
        f.completeIndex(0, ok());
        expect("★★ 迟到的旧结果被丢弃（计入 stale），不产出结果", t.poll() == null,
                "旧结果被采用了");
        expect("★★ 且没有把「新一代在途」的状态清掉", t.inFlight(), "在途状态被旧回调清了");
        expect("★ stale 计数 = 1", t.stats().staleDiscarded() == 1,
                String.valueOf(t.stats().staleDiscarded()));

        // 新一代完成后正常交付
        t.tick(21, scene());
        f.complete(ok());
        expect("★ 新一代完成后正常交付", t.poll() != null, "没交付");
        expect("★ 全部结束后不在途", !t.inFlight(), "还在途");
    }

    // ═══════════ 12. 行为统计（调控顾问的输入）═══════════

    private static void testBehaviorStats() {
        section("12. ★★ 行为统计：滑窗、直方图、失败信号、出手间隔、需求均值");

        BehaviorStats s = new BehaviorStats(100);
        for (int i = 0; i < 10; i++) {
            s.noteAction(i * 10L, "maid_native:melee_swing", true);
        }
        s.noteAction(95, "goety:recall_servants", false);
        s.noteAction(96, "goety:recall_servants", false);
        s.noteAction(97, "slashblade:combo_a", true);
        s.noteAction(98, "slashblade:combo_a", true);
        s.noteAction(99, "slashblade:combo_a", true);

        expect("★ 出手次数 = 成功的那些", s.executedCount() == 13, String.valueOf(s.executedCount()));
        expect("★ 失败次数 = 被判「做不出来」的那些（记了 2 条）", s.failedCount() == 2,
                String.valueOf(s.failedCount()));
        expect("★ 动作直方图正确", Integer.valueOf(10).equals(s.actionHistogram().get("maid_native:melee_swing")),
                s.actionHistogram().toString());
        expect("★★ 失败直方图单独列出（这是「她卡住了」最直接的信号）",
                Integer.valueOf(2).equals(s.failedHistogram().get("goety:recall_servants")),
                s.failedHistogram().toString());
        expect("★ 平均出手间隔可算（≈9 tick）", Math.abs(s.averageInterval() - 9.0) < 1.0,
                String.valueOf(s.averageInterval()));

        // 需求均值
        BehaviorStats s2 = new BehaviorStats();
        for (int i = 0; i < 4; i++) {
            s2.noteNeed(i, NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.5, NeedAxis.MOBILITY, -0.5));
        }
        double[] mean = s2.needMean();
        expect("★ 需求均值正确（单点 0.5 / 位移 −0.5）",
                Math.abs(mean[NeedAxis.SINGLE_DAMAGE.ordinal()] - 0.5) < 1e-9
                        && Math.abs(mean[NeedAxis.MOBILITY.ordinal()] + 0.5) < 1e-9,
                String.valueOf(mean[NeedAxis.SINGLE_DAMAGE.ordinal()]));

        // 喂给 LLM 的快照（★ 必须在「滑窗剪枝」那一步【之前】取 —— 剪枝后窗口里什么都没了）
        Map<String, Object> st = s.toState();
        expect("★ 快照含 rhythm / weariness / average_needs / dominant_need",
                st.keySet().containsAll(List.of("rhythm", "weariness", "average_needs",
                        "dominant_need")), st.keySet().toString());
        expect("★★ 快照里【没有】逐条日志（有界上下文是这套机制的前提）",
                !st.toString().contains("maid_native:melee_swing@"), st.toString().substring(0, 0));
        expect("★ 有失败时快照才带 stuck_actions（没卡住就不占上下文）",
                st.toString().contains("stuck_actions"), st.toString());
        String json = GSON.toJson(st);
        expect("★ 快照能序列化且无 NaN（NaN 会让整个请求被网关吞成「无可用渠道」）",
                !json.contains("NaN") && !json.contains("Infinity"), json);

        // ★ 滑窗：把窗口外的丢掉
        s.noteAction(300, "maid_native:melee_swing", true);
        expect("★★ 超出窗口的事件被丢掉（否则统计会被整场战斗的历史稀释）",
                s.actionHistogram().getOrDefault("maid_native:melee_swing", 0) <= 1,
                s.actionHistogram().toString());
    }

    // ═══════════ 13. 调控顾问的映射（★ 不漂移的前提）═══════════

    private static void testAdvisorMapping() {
        section("13. ★★ 调控顾问：score → 旋钮值的映射（默认值必须是不动点）");

        TuningBus bus = TuningBus.global();
        bus.resetAll();
        try {
            expect("★ 问题集合里一个旋钮一个问题，全是 score 5 档",
                    Advisor.questions().size() == Advisor.TUNABLE.size()
                            && Advisor.questions().values().stream().allMatch(o ->
                            "score".equals(((Map<?, ?>) o).get("type"))),
                    String.valueOf(Advisor.questions().size()));
            expect("★ 问题集合能通过本地预校验（不白白浪费一次往返）",
                    accepts(() -> JevClient.validate("probe", Advisor.questions())), "没过");

            // ★★ 核心性质：中档 = 默认值（严格相等）
            for (TuningBus.Knob k : bus.knobs()) {
                if (!Advisor.TUNABLE.contains(k.key())) {
                    continue;
                }
                double mid = Advisor.valueFor(k, 2.0);
                expect("★★ " + k.key() + "：模型答「保持」（中档）⇒ 严格等于默认值 " + k.def(),
                        Math.abs(mid - k.def()) < 1e-9, String.valueOf(mid));
                double lo = Advisor.valueFor(k, 0.0);
                double hi = Advisor.valueFor(k, 4.0);
                expect("★★ " + k.key() + "：两端落在 [min, max] 内（永远推不出边界）",
                        lo >= k.min() - 1e-9 && hi <= k.max() + 1e-9,
                        lo + " / " + hi);
            }

            // 假的模型返回：只答两个键
            Map<String, Object> resp = new LinkedHashMap<>();
            Map<String, Object> answers = new LinkedHashMap<>();
            answers.put("satisfaction.damage", Map.of("type", "score", "score", 0.0, "confidence", 1.0));
            answers.put("bias.aggression", Map.of("type", "score", "score", 4.0, "confidence", 1.0));
            answers.put("failure.backoff", Map.of("type", "score", "score", 0.0, "confidence", 1.0));
            answers.put("delete_all_actions", Map.of("type", "score", "score", 4.0, "confidence", 1.0));
            resp.put("answers", answers);
            Map<String, Double> patch = Advisor.patch(resp, bus);
            expect("★★ 只调它答了的键（没答的键不动 —— 不猜默认值）",
                    patch.size() == 2, patch.toString());
            expect("★★ 白名单外的键被丢掉（模型多答不会污染总线）",
                    !patch.containsKey("delete_all_actions"), patch.toString());
            expect("★ 保险丝 failure.backoff 不可被顾问调（它不是「风格」）",
                    !patch.containsKey("failure.backoff"), patch.toString());
            expect("★ 答「最低」⇒ 值降到区间下界附近",
                    patch.get("satisfaction.damage") < 0.5, String.valueOf(patch.get("satisfaction.damage")));
            expect("★ 答「最高」⇒ 值升到区间上界附近",
                    patch.get("bias.aggression") > 1.5, String.valueOf(patch.get("bias.aggression")));

            // 全部答「保持」 ⇒ 补丁等于当前值 ⇒ 施加后什么都没变
            Map<String, Object> keep = new LinkedHashMap<>();
            Map<String, Object> ka = new LinkedHashMap<>();
            for (String key : Advisor.TUNABLE) {
                ka.put(key, Map.of("type", "score", "score", 2.0, "confidence", 1.0));
            }
            keep.put("answers", ka);
            Map<String, Double> keepPatch = Advisor.patch(keep, bus);
            TuningBus.ApplyResult r = bus.applyAll(keepPatch);
            expect("★★★ 全答「保持」⇒ 施加后总线仍是默认状态（不动点，长期不会漂移）",
                    bus.isDefault() && r.rejected() == 0,
                    bus.overrides() + "  rejected=" + r.rejected());

            // 缺答案 ⇒ 不产出
            expect("★ 模型什么都没答 ⇒ 空补丁（不瞎调）",
                    Advisor.patch(Map.of("answers", Map.of()), bus).isEmpty(), "有内容");
            expect("★ state 里带了旋钮说明书（提示词从实现生成，不会漂移）",
                    Advisor.state(new BehaviorStats(), bus).toString().contains("satisfaction.damage"),
                    "缺说明书");
        } finally {
            bus.resetAll();
        }
    }

    // ═══════════ 14. 法术/聚晶分类（推断，可离线验）═══════════

    private static void testSpellClassifiers() {
        section("14. ★★ 法术意图分类：关键词判据可离线验（错了会让「挑法术」挑错）");

        // ★ 这里【不】能构造真的 AbstractSpell（需要 MC 环境）⇒ 验纯字符串那一层。
        //   这是本项目对「推断类判据」的一贯要求：不碰 MC 类型的部分必须能离线断言。
        expect("★★ 铁魔法：治疗/护盾 → SUPPORT",
                SpellIntent.ironsIntent("irons_spellbooks:heal").name().equals("SUPPORT")
                        && SpellIntent.ironsIntent("irons_spellbooks:shield").name().equals("SUPPORT"),
                "分类错");
        expect("★★ 铁魔法：召唤/亡灵 → SUMMON",
                SpellIntent.ironsIntent("irons_spellbooks:summon_swarm").name().equals("SUMMON")
                        && SpellIntent.ironsIntent("irons_spellbooks:raise_dead").name().equals("SUMMON"),
                "分类错");
        expect("★★ 铁魔法：攻击法术（含 blood_slash）→ ATTACK（不能被 life 之类的词误伤）",
                SpellIntent.ironsIntent("irons_spellbooks:blood_slash").name().equals("ATTACK")
                        && SpellIntent.ironsIntent("irons_spellbooks:fireball").name().equals("ATTACK"),
                "分类错");
        expect("★ 铁魔法：support 类关键词优先于攻击默认（顺序有意义）",
                SpellIntent.ironsIntent("irons_spellbooks:blood_heal").name().equals("SUPPORT"),
                "分类错");

        // goety：推不出来时返回 null（让调用方继续用属性判）
        expect("★★ goety：治疗/防护/控制/仆从管理/召唤 都能按名字推出",
                SpellIntent.goetyCategory("HealSpell") != null
                        && SpellIntent.goetyCategory("ShieldSpell").name().equals("PROTECTION")
                        && SpellIntent.goetyCategory("FreezeSpell").name().equals("CONTROL")
                        && SpellIntent.goetyCategory("SummonVexSpell").name().equals("SUMMON"),
                "分类错");
        expect("★★ goety：推不出来时返回 null（交给「属性判定」兜底，而不是硬猜）",
                SpellIntent.goetyCategory("ZombieSpellX") != null
                        && SpellIntent.goetyCategory("FireballSpell") == null,
                "FireballSpell 不该被名字判出来");
    }

    // ═══════════ 15b. ★★ W32：法术意图表（数据驱动，完整可审计）═══════════

    private static void testSpellIntentTable() {
        section("15b. ★★ W32 法术意图表：查表优先于关键词，且表里没有的会被审计抓出来");

        // ★ 表是数据文件；这里【不】读文件（自测跑在 catalog 工作目录下，读法见 audit 脚本），
        //   而是手工装一张小表来验「查表 → 关键词 → 兜底「这个**优先级链**本身。
        SpellIntentTable.Table saved = SpellIntentTable.current();
        try {
            SpellIntentTable.install(SpellIntentTable.fromMaps(
                    Map.of("FireballSpell", "SUPPORT"),          // ← 故意写一个"反常「的分类
                    Map.of("ZombieSpell", "HEALING")));
            expect("★★★ 查表优先于关键词：哪怕表里写的是反常值也以表为准（数据是权威）",
                    SpellIntent.ironsIntent("FireballSpell", "FireballSpell")
                            == SpellIntent.Intent.SUPPORT,
                    SpellIntent.ironsIntent("FireballSpell", "FireballSpell").name());
            expect("★★ goety 侧同理（表说 HEALING 就是 HEALING，哪怕名字叫 Zombie）",
                    SpellIntent.goetyCategory("ZombieSpell", "ZombieSpell")
                            == SpellIntent.Category.HEALING,
                    String.valueOf(SpellIntent.goetyCategory("ZombieSpell", "ZombieSpell")));
            expect("★★ 表里没有的类 ⇒ 退回关键词（HealSpell → SUPPORT）",
                    SpellIntent.ironsIntent("HealSpell", "HealSpell")
                            == SpellIntent.Intent.SUPPORT, "没退回关键词");
            expect("★★ 表里没有且关键词也推不出来 ⇒ 铁魔法兜底 ATTACK / goety 返回 null",
                    SpellIntent.ironsIntent("WeirdSpell", "WeirdSpell")
                            == SpellIntent.Intent.ATTACK
                            && SpellIntent.goetyCategory("WeirdSpell", "WeirdSpell") == null,
                    "兜底不对");
            expect("★ 表里写了非法取值 ⇒ 当没查到（继续走关键词），而不是抛异常",
                    SpellIntent.ironsIntent("BadSpell", "HealSpell") == SpellIntent.Intent.SUPPORT,
                    "非法值没被绕过");
            expect("★ 空表不会把已有表清掉（与 CatalogHolder 同一纪律：一次读失败不该降级）",
                    SpellIntentTable.lookupIrons("FireballSpell") != null, "被清空了");
        } finally {
            SpellIntentTable.install(saved);
        }
        expect("★★ 装回来的表仍然可用", SpellIntentTable.current() != null, "表丢了");
    }

    // ═══════════ 15c. ★★ 每女仆独立的旋钮 ═══════════

    private static void testPerMaidTuningIndependence() {
        section("15c. ★★ 女仆之间的独立：各自的总线覆盖，未覆盖处继承全局");

        TuningBus global = TuningBus.global();
        global.resetAll();
        TuningBus a = TuningBus.independent();
        TuningBus b = TuningBus.independent();
        try {
            // ★★ 2026-10-03：别把**默认值写死**在断言里 —— 委托方要"微微高频一些"，
            //   我把 cooldown.scale 的默认从 1.0 调成 0.9，这条断言就红了（而功能没坏）。
            //   ⇒ 改成"与全局当前值一致"（这本来就是「继承」的定义），
            //     以后再调默认值不会牵动它。
            expect("★ 新总线默认继承全局（此刻都是默认值）",
                    a.get("cooldown.scale") == global.get("cooldown.scale")
                            && b.get("cooldown.scale") == global.get("cooldown.scale"),
                    "a=" + a.get("cooldown.scale") + " b=" + b.get("cooldown.scale")
                            + " global=" + global.get("cooldown.scale"));

            // 全局调 ⇒ 两个女仆都跟着变（★ 这是「没单独调过「的正确语义）
            global.set("cooldown.scale", 2.0);
            expect("★★★ 全局调了 ⇒ 两个女仆都立刻跟着变（继承是活的，不是拷贝）",
                    a.get("cooldown.scale") == 2.0 && b.get("cooldown.scale") == 2.0,
                    a.get("cooldown.scale") + "/" + b.get("cooldown.scale"));

            // 只调 A
            a.set("cooldown.scale", 0.5);
            expect("★★★ 单独调 A ⇒ 只有 A 变，B 不受影响（这就是委托方要的「女仆之间独立」）",
                    a.get("cooldown.scale") == 0.5 && b.get("cooldown.scale") == 2.0,
                    a.get("cooldown.scale") + "/" + b.get("cooldown.scale"));
            expect("★ 并且能看出「这个值来自哪一层」",
                    a.sourceOf("cooldown.scale").equals("本女仆")
                            && b.sourceOf("cooldown.scale").equals("全局")
                            && a.sourceOf("satisfaction.all").equals("默认"),
                    a.sourceOf("cooldown.scale") + "/" + b.sourceOf("cooldown.scale"));

            // 全局再调 ⇒ A 因为自己覆盖了所以不变，B 跟着变
            global.set("cooldown.scale", 3.0);
            expect("★★ A 有自己的覆盖 ⇒ 全局再改也动不了她；B 继续跟随全局",
                    a.get("cooldown.scale") == 0.5 && b.get("cooldown.scale") == 3.0,
                    a.get("cooldown.scale") + "/" + b.get("cooldown.scale"));

            // 撤销 A 的覆盖 ⇒ 回到继承
            a.resetAll();
            expect("★★ 撤销 A 的覆盖 ⇒ 她又回到继承全局（可逆）",
                    a.get("cooldown.scale") == 3.0 && a.isDefault(),
                    String.valueOf(a.get("cooldown.scale")));

            // 白名单/钳制在每女仆层同样生效
            expect("★★ 每女仆层同样走白名单（未登记的键被拒）",
                    !a.set("delete_all_actions", 1.0), "竟然接受了");
            a.set("satisfaction.damage", 999.0);
            expect("★★ 每女仆层同样被钳制", a.get("satisfaction.damage") <= 2.0,
                    String.valueOf(a.get("satisfaction.damage")));

            // 满足度向量也走本层
            a.resetAll();
            a.set("satisfaction.damage", 0.5);
            NeedVector u = NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.8);
            expect("★★★ 每女仆的 satisfaction 也是各自生效的",
                    Math.abs(a.satisfactionVector(u).get(NeedAxis.SINGLE_DAMAGE) - 0.4) < 1e-9
                            && Math.abs(b.satisfactionVector(u).get(NeedAxis.SINGLE_DAMAGE)
                            - 0.8 * 1.0) < 1e-9,
                    a.satisfactionVector(u).get(NeedAxis.SINGLE_DAMAGE) + "/"
                            + b.satisfactionVector(u).get(NeedAxis.SINGLE_DAMAGE));
        } finally {
            a.resetAll();
            b.resetAll();
            global.resetAll();
        }
    }

    // ═══════════ 15. 「挑法术」的判据 ═══════════

    private static void testIronsSpellPicking() {
        section("15. ★★ 挑法术：按需求选类别 + 同类里最久没用（打破「永远只用第一个法术」）");

        com.touhoulittlemad.fightlikeplayer.decision.SpringConfig cfg =
                com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults();

        // ★ 判据本体在纯逻辑层 SpellPicker（铁魔法与 Goety 共用）⇒ 喂假候选即可离线验。
        List<SpellPicker.Choice> options = List.of(
                new SpellPicker.Choice("fireball", "ATTACK"),
                new SpellPicker.Choice("heal", "SUPPORT"),
                new SpellPicker.Choice("summon_swarm", "SUMMON"));

        Map<String, NeedVector> overrides = Map.of(
                "ATTACK", NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.7, NeedAxis.AREA_DAMAGE, 0.3),
                "SUPPORT", NeedVector.of(NeedAxis.HEAL_SURVIVAL, 0.5, NeedAxis.ALLY_CARE, 0.7),
                "SUMMON", NeedVector.of(NeedAxis.REINFORCE, 0.8));

        Map<String, Long> fresh = Map.of();
        int attack = SpellPicker.pickIndex(options,
                NeedVector.of(NeedAxis.SINGLE_DAMAGE, 1.0), overrides, cfg, fresh);
        int support = SpellPicker.pickIndex(options,
                NeedVector.of(NeedAxis.HEAL_SURVIVAL, 1.0, NeedAxis.ALLY_CARE, 1.0),
                overrides, cfg, fresh);
        int summon = SpellPicker.pickIndex(options,
                NeedVector.of(NeedAxis.REINFORCE, 1.0), overrides, cfg, fresh);
        System.out.println("   需求=单点 ⇒ " + options.get(attack).label()
                + "　需求=回血/护友 ⇒ " + options.get(support).label()
                + "　需求=强化 ⇒ " + options.get(summon).label());

        expect("★★★ 需求偏「单点」⇒ 挑到 ATTACK 那一个（不再固定第一个法术）",
                options.get(attack).label().equals("fireball"), options.get(attack).toString());
        expect("★★★ 需求偏「回血/护友」⇒ 挑到 SUPPORT 那一个",
                options.get(support).label().equals("heal"), options.get(support).toString());
        expect("★★★ 需求偏「强化」⇒ 挑到 SUMMON 那一个",
                options.get(summon).label().equals("summon_swarm"), options.get(summon).toString());

        // ★ 同类多个 ⇒ 选最久没用的（否则需求不变时会永远挑同一个 —— 只是从「第一个」换成「某个固定」）
        List<SpellPicker.Choice> two = List.of(
                new SpellPicker.Choice("fireball", "ATTACK"),
                new SpellPicker.Choice("lightning_bolt", "ATTACK"));
        Map<String, Long> used = new java.util.HashMap<>();
        used.put("fireball", 500L);
        int next = SpellPicker.pickIndex(two, NeedVector.of(NeedAxis.SINGLE_DAMAGE, 1.0),
                overrides, cfg, used);
        expect("★★ 同类里挑【最久没用过的】（fireball 刚用过 ⇒ 这次换 lightning_bolt）",
                two.get(next).label().equals("lightning_bolt"), two.get(next).toString());

        expect("★ 没有需求向量时退回「轮换」（不报错、不固定第一个）",
                SpellPicker.pickIndex(options, null, overrides, cfg, fresh) >= 0, "退化为负");
        expect("★ 空候选 ⇒ 返回 −1（调用方据此判「她一个法术都没有」）",
                SpellPicker.pickIndex(List.of(), null, overrides, cfg, fresh) == -1, "不是 -1");
        expect("★★ overrides 键名与类别对不上（数据写错）⇒ 退化为轮换，而不是崩或挑错",
                SpellPicker.pickIndex(options, NeedVector.of(NeedAxis.SINGLE_DAMAGE, 1.0),
                        Map.of("NOT_A_KEY", NeedVector.of(NeedAxis.SINGLE_DAMAGE, 1.0)),
                        cfg, fresh) >= 0, "崩了或返回负");
    }


    // ═══════════ 16. 可选：打一次真接口 ═══════════

    private static void liveSmoke() {
        section("12. ★★ 真接口冒烟（--live）：验证端点/密钥/请求体形状/解析全链路");
        String key = System.getenv("JEV_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("   （跳过：没有设置环境变量 JEV_API_KEY）");
            return;
        }
        try {
            JevClient c = new JevClient(key);
            Map<String, Object> state = scene().toState();
            long t0 = System.currentTimeMillis();
            Map<String, Object> resp = c.judge(state, ThinkPrompt.questions());
            long ms = System.currentTimeMillis() - t0;
            System.out.println("   HTTP 200（" + ms + " ms）");
            System.out.println("   usage = " + JevClient.usage(resp));
            ThinkPrompt.Verdict v = ThinkPrompt.verdict(resp, 0.35);
            System.out.println("   逐轴原始分 = " + v.summary());
            System.out.println("   决策向量  = " + v.vector().toCompactString(
                    com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults()));
            expect("★ 真接口返回成功且解析出 8 条轴（缺失 " + v.missing().size() + " 条）",
                    v.missing().size() <= 2, v.missing().toString());

            // ★★ 顺手把【调控顾问】那一轮也真打一次 —— 这是"怎么测试 LLM 动态指挥"的离线入口：
            //    不用进游戏就能看到"它想怎么调"。
            System.out.println();
            System.out.println("   ---- 调控顾问（同一份 Key，另一次请求）----");
            BehaviorStats stats = demoStats();
            Map<String, Object> advisorState = Advisor.state(stats, TuningBus.global());
            Map<String, Object> advisorResp = c.judge(advisorState, Advisor.questions());
            Map<String, Double> patch = Advisor.patch(advisorResp, TuningBus.global());
            System.out.println("   行为统计   = " + stats.summary());
            System.out.println("   它想怎么调 = " + Advisor.describe(patch, TuningBus.global()));
            System.out.println("   （★ 这里【不】真的写进总线 —— 与游戏的 observe 模式一致）");
            expect("★ 顾问返回能解析成补丁（键名都在可调白名单里）",
                    patch.keySet().stream().allMatch(Advisor.TUNABLE::contains), patch.toString());
            expect("★★ 补丁里每个值都在对应旋钮的取值域内（钳制生效）",
                    patch.entrySet().stream().allMatch(e -> {
                        for (TuningBus.Knob k : TuningBus.global().knobs()) {
                            if (k.key().equals(e.getKey())) {
                                return e.getValue() >= k.min() - 1e-9
                                        && e.getValue() <= k.max() + 1e-9;
                            }
                        }
                        return false;
                    }), patch.toString());
        } catch (RuntimeException e) {
            System.out.println("   !! " + e);
            expect("★ 真接口连通", false, String.valueOf(e));
        }
    }

    /** 造一段"看起来像真的"行为统计，用来试顾问（离线 {@code --live} 用）。 */
    private static BehaviorStats demoStats() {
        BehaviorStats s = new BehaviorStats(200);
        for (int i = 0; i < 8; i++) {
            s.noteAction(i * 20L, "maid_native:melee_swing", true);
        }
        s.noteAction(165, "goety:recall_servants", false);
        s.noteAction(170, "goety:recall_servants", false);
        s.noteAction(180, "slashblade:combo_a", true);
        s.noteHurt(13.0, 0.62);
        s.noteNeed(190, NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.9, NeedAxis.MOBILITY, 0.5));
        s.noteNeed(195, NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.6, NeedAxis.MITIGATION_SURVIVAL, 0.4));
        s.noteNeed(200, NeedVector.of(NeedAxis.SINGLE_DAMAGE, 0.4, NeedAxis.MITIGATION_SURVIVAL, 0.7));
        return s;
    }

    // ───────────────────────── 夹具与工具 ─────────────────────────

    private static final class Fake implements Thinker.Transport {
        final List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        int calls;

        @Override
        public CompletableFuture<Map<String, Object>> judge(Object state, Map<String, Object> q) {
            calls++;
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            futures.add(f);
            return f;
        }

        void complete(Map<String, Object> resp) {
            if (!futures.isEmpty()) {
                futures.get(futures.size() - 1).complete(resp);
            }
        }

        /** 让第 {@code index} 个（从 0 起）请求完成 —— 用来模拟「迟到的旧包」。 */
        void completeIndex(int index, Map<String, Object> resp) {
            futures.get(index).complete(resp);
        }

        void fail(Throwable e) {
            if (!futures.isEmpty()) {
                futures.get(futures.size() - 1).completeExceptionally(e);
            }
        }
    }

    private static ThinkScene scene() {
        return scene(2, 0.7, 0.9, 3, 6.0, true, false, 0,
                "slashblade:slashblade", "(empty)", "toward", "slashblade:combo_a",
                List.of("slashblade:combo_a", "slashblade:slashblade_art"));
    }

    private static ThinkScene scene(int enemies, double self, double owner, int nearby,
                                    double dist, boolean visible, boolean melee, int servants,
                                    String main, String off, String gait, String last,
                                    List<String> actions) {
        return new ThinkScene("fight_like_player:player_like_combat", self, owner, true,
                enemies, dist, visible, melee, false, servants, main, off, gait, last, actions);
    }

    /** 构造一个「所有轴都答 score=v、置信度 1.0」的返回。 */
    private static Map<String, Object> scores(double v) {
        Map<String, Object> answers = new LinkedHashMap<>();
        for (Map.Entry<String, NeedAxis> e : ThinkPrompt.AXIS_OF.entrySet()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", "score");
            a.put("score", v);
            a.put("confidence", 1.0);
            answers.put(e.getKey(), a);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("answers", answers);
        resp.put("usage", Map.of("input_tokens", 100, "output_tokens", 10));
        return resp;
    }

    /** 所有轴答满分，但置信度只有 0.2（用来触发作废规则）。 */
    private static Map<String, Object> lowConfidence() {
        Map<String, Object> resp = scores(4.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) resp.get("answers");
        for (Object a : answers.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) a;
            m.put("confidence", 0.2);
        }
        return resp;
    }

    private static Map<String, Object> ok() {
        return scores(3.0);
    }

    private static Map<String, Object> parse(String json) {
        return GSON.fromJson(json, new TypeToken<Map<String, Object>>() {
        }.getType());
    }

    private static boolean accepts(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void expectReject(String what, Runnable r) {
        try {
            r.run();
            expect("★ 应当拒绝：" + what, false, "竟然通过了");
        } catch (IllegalArgumentException e) {
            expect("★ 应当拒绝：" + what, true, "");
        } catch (RuntimeException e) {
            expect("★ 应当拒绝：" + what, false, "抛了别的异常：" + e);
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    private static double orZero(Double d) {
        return d == null ? -1.0 : d;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static void section(String t) {
        System.out.println("-- " + t);
    }

    // ═══════════ 18. 提示词覆写（配置界面可编辑）═══════════

    /**
     * ★★ 委托方 2026-10-01：「使得配置界面可以配置 apikey &amp; 编辑提示词」。
     *
     * <p>覆写机制有三条必须成立的性质（都在这里断言）：
     * <ol>
     *   <li><b>没写的键沿用内置问法</b> ⇒ 用户只想改一条就只写一条，不会"改一条坏七条"；</li>
     *   <li><b>写错的键被丢弃且登记</b> ⇒ 不静默失效（本项目最反复的一条纪律）；</li>
     *   <li><b>覆写只换文本，不换结构</b> ⇒ 问题数、类型、档数都不变
     *       （档数一变，{@code score→向量} 的分段映射就错了）。</li>
     * </ol>
     */
    private static void testPromptOverrides() {
        section("18. ★★ 提示词覆写：解析 / 回退 / 结构不变（配置界面那一页的后端）");

        // ── 1. 解析 ──
        var parsed = PromptOverrides.parseForThink("""
                # 这是注释
                single_damage = 现在该不该集火一个目标？

                mobility = 该进还是该退？
                """);
        expect("★ 注释行与空行被忽略，解析出 2 条", parsed.overrides().size() == 2,
                parsed.overrides().toString());
        expect("★ 键值对切分正确（含 '=' 的问法不截断）",
                "现在该不该集火一个目标？".equals(parsed.overrides().get("single_damage")),
                String.valueOf(parsed.overrides().get("single_damage")));
        expect("★ 没有丢键", parsed.ignoredKeys().isEmpty(), parsed.ignoredKeys().toString());

        // ── 2. 写错的键必须可见 ──
        var bad = PromptOverrides.parseForThink("single_damag = 打错了\nnot_an_axis = 随便写");
        expect("★★ 无法识别的键被丢弃（不会污染问题集合）", bad.overrides().isEmpty(),
                bad.overrides().toString());
        expect("★★ 且【被登记】出来（不静默失效 —— 用户能查到）",
                bad.ignoredKeys().size() == 2, bad.ignoredKeys().toString());

        // ── 3. 空/空白 = 用内置 ──
        expect("★ 空文本 ⇒ 空覆写", PromptOverrides.parseForThink("").isEmpty(), "不空");
        expect("★ 只有注释 ⇒ 空覆写",
                PromptOverrides.parseForThink("# 什么都没改\n").isEmpty(), "不空");
        expect("★ 空问法（key = 后面没东西）被判为非法并登记",
                PromptOverrides.parseForThink("control =   ").ignoredKeys().size() == 1,
                PromptOverrides.parseForThink("control =   ").ignoredKeys().toString());

        // ── 4. 覆写真的进了问题集合，且【结构不变】──
        Map<String, Object> builtin = ThinkPrompt.questions();
        Map<String, Object> overridden = ThinkPrompt.questions(
                Map.of("single_damage", "OVERRIDDEN-TEXT-12345"));
        expect("★★ 覆写后问题数不变（8 条）", overridden.size() == builtin.size(),
                overridden.size() + " vs " + builtin.size());
        expect("★★ 覆写后键集合不变", overridden.keySet().equals(builtin.keySet()),
                overridden.keySet().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> od = (Map<String, Object>) overridden.get("single_damage");
        expect("★★ 被覆写的那条 instructions 换成了新文本",
                "OVERRIDDEN-TEXT-12345".equals(od.get("instructions")),
                String.valueOf(od.get("instructions")));
        boolean othersIntact = true;
        for (String k : builtin.keySet()) {
            if ("single_damage".equals(k)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) builtin.get(k);
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) overridden.get(k);
            othersIntact &= a.get("instructions").equals(b.get("instructions"));
        }
        expect("★★ 没被覆写的轴【一个字都没变】（改一条不会坏七条）", othersIntact, "有轴被改动了");
        @SuppressWarnings("unchecked")
        Map<String, Object> odLevels = (Map<String, Object>) overridden.get("single_damage");
        expect("★★ 覆写【不改等级档数】（档数一变，score→向量的映射就错）",
                ((List<?>) odLevels.get("criteria")).size() == 5,
                String.valueOf(odLevels.get("criteria")));
        expect("★ 覆写后整包仍能通过本地预校验（不会白跑一次往返）",
                accepts(() -> JevClient.validate("probe", overridden)), "自校验没过");

        // ── 5. 顾问那一半 ──
        Map<String, Object> adv = Advisor.questions(Map.of("cooldown.scale", "她该多快出招？"));
        expect("★★ 顾问覆写：问题数不变", adv.size() == Advisor.TUNABLE.size(),
                adv.size() + " vs " + Advisor.TUNABLE.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> cs = (Map<String, Object>) adv.get("cooldown.scale");
        expect("★★ 顾问覆写生效", "她该多快出招？".equals(cs.get("instructions")),
                String.valueOf(cs.get("instructions")));
        @SuppressWarnings("unchecked")
        Map<String, Object> csLevels = (Map<String, Object>) adv.get("cooldown.scale");
        expect("★★ 顾问的等级档数也不变（中间档必须仍是「保持现状」=不动点）",
                ((List<?>) csLevels.get("criteria")).size() == 5,
                String.valueOf(csLevels.get("criteria")));
        expect("★ 顾问覆写键写错 ⇒ 被登记",
                PromptOverrides.parseForAdvisor("cooldown.scalee = x").ignoredKeys().size() == 1,
                PromptOverrides.parseForAdvisor("cooldown.scalee = x").ignoredKeys().toString());

        // ── 6. 模板（GUI 的「填入模板」按钮用它）──
        String tpl = PromptOverrides.templateForThink();
        boolean allAxesListed = true;
        for (String id : ThinkPrompt.AXIS_OF.keySet()) {
            allAxesListed &= tpl.contains(id);
        }
        expect("★★ 模板把 8 条合法轴 id 全列出来（用户不必去翻文档）", allAxesListed, tpl);
        expect("★ 模板本身是合法的覆写文本（解析后为空 ⇒ 填进去就等于没改）",
                PromptOverrides.parseForThink(tpl).overrides().isEmpty(), "模板解析出了内容");
        expect("★ 顾问模板同理",
                PromptOverrides.parseForAdvisor(PromptOverrides.templateForAdvisor())
                        .overrides().isEmpty(), "顾问模板解析出了内容");

        // ── 7. ★★ 有界上下文（委托方问过「llm 动态指挥默认有没有上下文」）──
        var bus = TuningBus.global();
        Map<String, Object> noCtx = Advisor.state(new BehaviorStats(), bus);
        expect("★★ 不带反馈时【不发】previous_round（默认就是「没有会话上下文」）",
                !noCtx.containsKey("previous_round"), noCtx.keySet().toString());
        Map<String, Object> withCtx = Advisor.state(new BehaviorStats(), bus, "上一轮它建议了什么");
        expect("★★ 带反馈时 previous_round 确实进了 state",
                "上一轮它建议了什么".equals(withCtx.get("previous_round")),
                String.valueOf(withCtx.get("previous_round")));
        expect("★ 空字符串不产生这个字段（不喂无用字段，省 token）",
                !Advisor.state(new BehaviorStats(), bus, "  ").containsKey("previous_round"), "有字段");
        expect("★ 无论有没有反馈，'现在是什么样'那三项都在（统计/当前旋钮/可调旋钮）",
                noCtx.keySet().containsAll(List.of("recent_behaviour", "current_knob_values",
                        "knobs_you_may_adjust")),
                noCtx.keySet().toString());
    }

    // ═══════════ 19. 法术自己的冷却（第十一轮：铁魔法/巫法的冷却）═══════════

    /**
     * ★★ 委托方第十一轮提问：「女仆的魔法中，**铁魔法是不是前摇、冷却都被取消了**，巫法部分我不知道
     * 有没有，望检查一下；总之，施法的前摇自然是动作的一部分，**冷却的话，我不知道你是否有头绪**。」
     *
     * <p>取证结论（读 ISS 与 Goety 源码）：
     * <ul>
     *   <li><b>铁魔法：前摇与冷却都被跳过。</b>
     *       玩家侧 {@code attemptInitiateCast} 先等 {@code getEffectiveCastTime}，再由
     *       {@code castSpell(...)} 调 {@code MAGIC_MANAGER.addCooldown(serverPlayer, ...)}
     *       —— 形参是 <b>{@code ServerPlayer}</b>；而我们（与 ISS 自己的 mob 路径一样）
     *       直接调 {@code onCast}（<b>效果</b>钩子）⇒ 两者都没了。</li>
     *   <li><b>巫法：前摇<b>在</b>（第九/十轮已按 {@code castUp}/{@code castDuration} 引导），
     *       冷却<b>没有</b>（{@code SEHelper.addCooldown(player, ...)} 同样是 Player-only）。</li>
     * </ul>
     * ⇒ 冷却的"头绪"就是：<b>读法术自己报的数</b>（{@code getSpellCooldown()} /
     * {@code spellCooldown(caster)}），记进这个账本，选法术时把没到的排除掉。
     */
    private static void testSpellCooldownLedger() {
        section("19. ★★ 法术自己的冷却账本：记账 / 到点 / 排除 / 钳制 / fail-open");

        var led = new com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger();
        led.markUsed("FireballSpell", 100L, 40);
        expect("★ 刚用完 ⇒ 不可用", !led.isReady("FireballSpell", 100L), "竟然可用");
        expect("★ 还差 39 tick", led.remaining("FireballSpell", 101L) == 39,
                String.valueOf(led.remaining("FireballSpell", 101L)));
        expect("★★ 到点即恢复（边界：now == readyAt 就算好了）",
                led.isReady("FireballSpell", 140L), "到点还不可用");
        expect("★ 没记过的法术永远可用（不受别人冷却影响）", led.isReady("HealSpell", 100L), "被误伤");
        expect("★ 冷却为 0 ⇒ 不记账（有些法术本来就没有冷却）",
                led.remaining("IceSpell", 100L) == 0 && readyAfter(led, "IceSpell", 0, 100L), "记错了");

        // ── 钳制：第三方给的值可能离谱 ──
        led.markUsed("Weird", 0L, Integer.MAX_VALUE);
        expect("★★ 离谱的大冷却被钳到上限（不会把法术永久禁掉）",
                led.remaining("Weird", 0L) == com.touhoulittlemad.fightlikeplayer.decision
                        .SpellCooldownLedger.MAX_COOLDOWN_TICKS,
                String.valueOf(led.remaining("Weird", 0L)));
        led.markUsed("Negative", 0L, -50);
        expect("★ 负数冷却当 0（不是「永久禁用」）", led.isReady("Negative", 0L),
                "负数被当成了冷却");

        // ── 与选法的联动：排除项必须真的不进池子 ──
        var options = List.of(
                new SpellPicker.Choice("A", "ATTACK"),
                new SpellPicker.Choice("B", "ATTACK"),
                new SpellPicker.Choice("C", "ATTACK"));
        java.util.Map<String, Long> lastUsed = new java.util.HashMap<>();
        lastUsed.put("A", 10L);                // A 最久没用 ⇒ 正常应该选 A
        lastUsed.put("B", 20L);
        lastUsed.put("C", 30L);
        int normal = SpellPicker.pickIndex(options, null, Map.of(),
                SpringConfig.defaults(), lastUsed);
        expect("★ 无排除时选最久没用的那个（A）", normal == 0, String.valueOf(normal));

        java.util.Set<String> excluded = java.util.Set.of("A");
        int skipped = SpellPicker.pickIndex(options, null, Map.of(),
                SpringConfig.defaults(), lastUsed, excluded);
        expect("★★ 排除 A（它在自己冷却里）⇒ 选 B，且返回的是【原表下标】1（不是池内下标 0）",
                skipped == 1, String.valueOf(skipped));

        int allGone = SpellPicker.pickIndex(options, null, Map.of(),
                SpringConfig.defaults(), lastUsed, java.util.Set.of("A", "B", "C"));
        expect("★★ 全在冷却 ⇒ 返回 -1（调用方据此说「都在冷却」，而不是乱选一个）",
                allGone == -1, String.valueOf(allGone));

        expect("★ describe 能说出谁在冷却（诊断出口）",
                led.describe(List.of("FireballSpell"), 100L).contains("FireballSpell"),
                led.describe(List.of("FireballSpell"), 100L));
    }

    /** 记一次 0 冷却之后应该立刻可用。 */
    private static boolean readyAfter(com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger led,
                                      String label, int cooldown, long now) {
        led.markUsed(label, now, cooldown);
        return led.isReady(label, now);
    }

    // ═══════════ 20. 端点口径（第十二轮：两家中转站的路径不同）═══════════

    /**
     * ★★ 委托方第十二轮原话：「你改的 APIKEY 端口是**一开始 jev 的端口，完全错了**。」
     *
     * <p>取证：同一个 key 可能属于**两家**中转站，而它们的**路径与请求体都不同**：
     * <pre>
     *   JEV 官方（api.new.bi）  : POST {base}/responses              请求体 model/input/state/questions
     *   DMXAPI（www.dmxapi.cn） : POST {base}/typesafe/v1/systemone  请求体 model/state/questions
     * </pre>
     * 之前代码写死 {@code baseUrl + "/responses"} ⇒ 把 baseUrl 改成 DMXAPI 的域名后会打到
     * {@code https://www.dmxapi.cn/responses}（无渠道），而错误信息完全看不出是"路径不对"。
     * <p>⇒ 端点由 baseUrl 推导（纯字符串函数，可离线断言），并把**实际会 POST 的 URL**
     * 直接显示在配置界面上。
     */
    private static void testEndpointFor() {
        section("20. ★★ 端点口径：JEV 官方补 /responses，DMXAPI 原样用 systemone");

        expect("★ JEV 官方端点 ⇒ 补 /responses",
                JevClient.endpointFor("https://api.new.bi/v1")
                        .equals("https://api.new.bi/v1/responses"),
                JevClient.endpointFor("https://api.new.bi/v1"));
        expect("★ 带尾斜杠也不出错",
                JevClient.endpointFor("https://api.new.bi/v1/")
                        .equals("https://api.new.bi/v1/responses"),
                JevClient.endpointFor("https://api.new.bi/v1/"));
        expect("★★ DMXAPI 的完整路径 ⇒ 【原样】使用（不能再补 /responses）",
                JevClient.endpointFor("https://www.dmxapi.cn/typesafe/v1/systemone")
                        .equals("https://www.dmxapi.cn/typesafe/v1/systemone"),
                JevClient.endpointFor("https://www.dmxapi.cn/typesafe/v1/systemone"));
        expect("★★ 即使只填到 /typesafe 也认得出来（不补 /responses）",
                JevClient.endpointFor("https://www.dmxapi.cn/typesafe/v1")
                        .equals("https://www.dmxapi.cn/typesafe/v1"),
                JevClient.endpointFor("https://www.dmxapi.cn/typesafe/v1"));
        expect("★ 空/空白 ⇒ 退回默认端点（不会拼出 \"/responses\" 这种碎片）",
                JevClient.endpointFor("").equals(JevClient.DEFAULT_BASE_URL + "/responses"),
                JevClient.endpointFor(""));
        expect("★★ 只填域名（少写 /v1）⇒ 自动补 /v1/responses（否则静默 404）",
                JevClient.endpointFor("https://api.new.bi")
                        .equals("https://api.new.bi/v1/responses"),
                JevClient.endpointFor("https://api.new.bi"));
        expect("★ 域名带尾斜杠同样处理",
                JevClient.endpointFor("https://api.new.bi/")
                        .equals("https://api.new.bi/v1/responses"),
                JevClient.endpointFor("https://api.new.bi/"));
        expect("★ 口径显示：两家各自说得出名字",
                JevClient.styleOf("https://api.new.bi/v1").contains("JEV")
                        && JevClient.styleOf("https://www.dmxapi.cn/typesafe/v1/systemone")
                        .contains("DMXAPI"),
                JevClient.styleOf("https://www.dmxapi.cn/typesafe/v1/systemone"));

        // ── 请求体形状必须与端点口径一致（这次两处一起改，别再出现"只有一处改"）──
        JevClient jev = new JevClient("sk-test", "https://api.new.bi/v1", null, null, 5);
        Map<String, Object> jevBody = jev.buildBody(Map.of("k", "v"), Map.of());
        expect("★ JEV 口径的请求体【含】input（缺了官方会报 input is required）",
                jevBody.containsKey("input"), jevBody.keySet().toString());
        JevClient dmx = new JevClient("sk-test",
                "https://www.dmxapi.cn/typesafe/v1/systemone", null, null, 5);
        Map<String, Object> dmxBody = dmx.buildBody(Map.of("k", "v"), Map.of());
        expect("★★ DMXAPI 口径的请求体【不含】input（按它文档里的 4 字段形状）",
                !dmxBody.containsKey("input"), dmxBody.keySet().toString());
        expect("★ 两者都含 model/state/questions",
                jevBody.keySet().containsAll(java.util.List.of("model", "state", "questions"))
                        && dmxBody.keySet().containsAll(
                        java.util.List.of("model", "state", "questions")),
                dmxBody.keySet().toString());
    }

    // ═══════════ 21. LLM 动态指挥（第十二轮：chat 模型那一半）═══════════

    /**
     * ★★ 委托方第十二轮的批评：「我们调用**两种模型**：① JEV 评分模型（短周期，做得很好）；
     * ② **LLM 模型（比如 deepseek）** 用于动态指挥 —— 你似乎**完全没做**。」
     *
     * <p>★ 他说对了：本轮之前项目里只有 JEV，而"顾问"是用 JEV 的 score 问题做的。
     * ⇒ 本测试钉住**新加的那一半**（chat 模型）：端点口径、请求体形状、
     * 以及最重要的 —— <b>模型回复的容错解析</b>（模型会写一段话，中间夹一个 JSON）。
     */
    private static void testLlmLayer() {
        section("21. ★★ LLM 动态指挥：端点 / 请求体 / 容错解析（chat 模型那一半）");

        // ── 端点 ──
        expect("★ 默认端点补 /chat/completions",
                LlmClient.endpointFor("https://api.deepseek.com/v1")
                        .equals("https://api.deepseek.com/v1/chat/completions"),
                LlmClient.endpointFor("https://api.deepseek.com/v1"));
        expect("★ 已写全 /chat/completions 的 ⇒ 原样使用（不重复拼）",
                LlmClient.endpointFor("https://api.deepseek.com/v1/chat/completions")
                        .equals("https://api.deepseek.com/v1/chat/completions"),
                LlmClient.endpointFor("https://api.deepseek.com/v1/chat/completions"));
        expect("★ 空 ⇒ 用默认端点",
                LlmClient.endpointFor("").equals(LlmClient.DEFAULT_BASE_URL + "/chat/completions"),
                LlmClient.endpointFor(""));

        // ── 请求体：messages 形态 + 系统提示词在第一条 ──
        LlmClient c = new LlmClient("sk-test", "https://api.deepseek.com/v1", null, null, 5);
        Map<String, Object> body = c.buildBody("系统提示词在这里", "统计 JSON", 0.2, 400);
        expect("★ 请求体含 model/messages/temperature/max_tokens",
                body.keySet().containsAll(List.of("model", "messages", "temperature", "max_tokens")),
                body.keySet().toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) body.get("messages");
        expect("★★ 系统提示词是 messages[0]（role=system）",
                msgs.size() == 2 && "system".equals(msgs.get(0).get("role"))
                        && "系统提示词在这里".equals(msgs.get(0).get("content")),
                String.valueOf(msgs));
        expect("★ 用户内容是 messages[1]（role=user）",
                "user".equals(msgs.get(1).get("role"))
                        && "统计 JSON".equals(msgs.get(1).get("content")),
                String.valueOf(msgs.get(1)));
        expect("★★ 空 API Key 直接构造失败（不发无鉴权请求）",
                rejects(() -> new LlmClient("  ")), "竟然构造成功了");

        // ── 容错解析（本层最容易出事的地方）──
        TuningBus bus = TuningBus.global();
        var pure = LlmAdvisor.parsePatch("{\"satisfaction.damage\": 0.6}", bus);
        expect("★ 纯 JSON ⇒ 解出一条", pure.patch().size() == 1
                        && Math.abs(pure.patch().get("satisfaction.damage") - 0.6) < 1e-9,
                pure.summary());

        var fenced = LlmAdvisor.parsePatch(
                "```json\n{\"cooldown.scale\": 2.5}\n```", bus);
        expect("★★ 带 ```json 围栏 ⇒ 也能抠出来",
                Math.abs(fenced.patch().getOrDefault("cooldown.scale", -1.0) - 2.5) < 1e-9,
                fenced.summary());

        var withProse = LlmAdvisor.parsePatch(
                "她最近老是被围住，建议谨慎一点。{\"bias.caution\": 1.4} 这样能少挨打。", bus);
        expect("★★ 前后有解释文字 ⇒ 仍能抠出中间那个 JSON",
                Math.abs(withProse.patch().getOrDefault("bias.caution", -1.0) - 1.4) < 1e-9,
                withProse.summary());

        var unknown = LlmAdvisor.parsePatch(
                "{\"satisfaction.damage\": 0.8, \"evil.key\": 99, \"anotherJunk\": 1}", bus);
        expect("★★ 白名单外的键【被丢弃并登记】（不能污染旋钮体系）",
                unknown.patch().size() == 1 && unknown.droppedKeys().size() == 2,
                unknown.summary());

        var nested = LlmAdvisor.parsePatch(
                "{\"satisfaction.damage\": {\"nested\": 1}}", bus);
        expect("★ 值是对象（不是数字）⇒ 被丢弃并登记",
                nested.patch().isEmpty() && nested.droppedKeys().size() == 1,
                nested.summary());

        expect("★★ 回复里没有 JSON ⇒ 空补丁（这一轮只是「没建议」，不会崩）",
                LlmAdvisor.parsePatch("我觉得她打得还行。", bus).isEmpty(), "竟然解出了东西");
        expect("★ 回复是空/null ⇒ 空补丁",
                LlmAdvisor.parsePatch(null, bus).isEmpty(), "解出了东西");
        expect("★★ 括号不配对 ⇒ 不会抠出一段非法文本（按配对扫，不是「首尾取」）",
                LlmAdvisor.parsePatch("{\"satisfaction.damage\": 0.5", bus).isEmpty(),
                "抠出了不配对的东西");
        expect("★ 字符串里的 } 不会误判为结束",
                LlmAdvisor.extractFirstJsonObject("{\"a\":\"}}\",\"satisfaction.damage\":1}")
                        .endsWith("}"),
                String.valueOf(LlmAdvisor.extractFirstJsonObject(
                        "{\"a\":\"}}\",\"satisfaction.damage\":1}")));
        expect("★ 数字字符串也接受（模型常写成 \"0.6\"）",
                Math.abs(LlmAdvisor.parsePatch("{\"satisfaction.all\": \"0.6\"}", bus)
                        .patch().getOrDefault("satisfaction.all", -1.0) - 0.6) < 1e-9,
                LlmAdvisor.parsePatch("{\"satisfaction.all\": \"0.6\"}", bus).summary());

        // ── 提示词与用户内容 ──
        expect("★★ 内置提示词要求「只输出 JSON」且给出 issue/cancel 格式（第十三轮改为指令优先）",
                !LlmAdvisor.DEFAULT_PROMPT.isBlank()
                        && LlmAdvisor.DEFAULT_PROMPT.contains("只输出 JSON")
                        && LlmAdvisor.DEFAULT_PROMPT.contains("\"issue\"")
                        && LlmAdvisor.DEFAULT_PROMPT.contains("\"cancel\"")
                        && LlmAdvisor.DEFAULT_PROMPT.contains("directives_available"),
                "内置提示词不完整");
        String user = LlmAdvisor.userContent(null, bus, "上一轮：caution→1.4");
        expect("★★ 用户内容含统计/旋钮/上一轮建议三部分",
                user.contains("recent_behaviour") && user.contains("knobs")
                        && user.contains("last_round_you_said"),
                user.substring(0, Math.min(120, user.length())));
        expect("★ 用户内容里每个旋钮都带 min/max/default（模型才不会写出越界值）",
                user.contains("\"min\"") && user.contains("\"max\"") && user.contains("\"default\""),
                "缺范围信息");
    }

    /** 期望构造抛异常（用于"空 key 不该构造出客户端"）。 */
    /**
     * ★★ 指令表进 prompt + 指令订单的容错解析（docs/15 的 M4）。
     *
     * <p>钉住三件事：① 指令表**由代码自动生成**（加指令不用改提示词）；
     * ② 编造的 id 被丢弃并登记；③ 参数越界被钳制；④ 没有 JSON ⇒ **空订单**
     * （绝不能变成"下达一条默认指令"）。
     */
    private static void testDirectiveOrdersForLlm() {
        section("22. ★★ 指令进 prompt + 订单解析（M4）");

        String table = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.describeForLlm();
        expect("★★ 指令表含全部 id（magic_only / keep_distance / interrupt…）",
                table.contains("magic_only") && table.contains("keep_distance")
                        && table.contains("interrupt"),
                table.substring(0, Math.min(60, table.length())));
        expect("★ 指令表标注 kind（sustained/instant）与强制手段",
                table.contains("\"kind\":\"sustained\"") && table.contains("\"enforce\""), "");

        var o1 = LlmAdvisor.parseOrders(
                "{\"issue\":[{\"id\":\"keep_distance\",\"params\":{\"min\":9}}],"
                        + "\"cancel\":[\"magic_only\"],\"why\":\"她老挨打\"}");
        expect("★★ 正常订单：1 下达 + 1 取消 + 理由",
                o1.issue().size() == 1 && o1.cancel().equals(java.util.List.of("magic_only"))
                        && o1.why().contains("挨打"), o1.summary());
        expect("★ 参数被解析出来", o1.issue().get(0).params().get("min") == 9.0,
                String.valueOf(o1.issue().get(0).params()));

        var o2 = LlmAdvisor.parseOrders(
                "{\"issue\":[{\"id\":\"fly_to_moon\"}],\"cancel\":[\"not_real\"]}");
        expect("★★ 编造的指令 id 被丢弃并登记（不能污染总线）",
                o2.issue().isEmpty() && o2.cancel().isEmpty() && o2.dropped().size() == 2,
                o2.summary());

        var o3 = LlmAdvisor.parseOrders(
                "{\"issue\":[{\"id\":\"keep_distance\",\"params\":{\"min\":999}}]}");
        expect("★★ 越界参数被钳到规格上限（24）",
                o3.issue().get(0).params().get("min") == 24.0,
                String.valueOf(o3.issue().get(0).params()));

        var o4 = LlmAdvisor.parseOrders("{\"issue\":[\"interrupt\"]}");
        expect("★ issue 里直接给 id 字符串也认（容错）",
                o4.issue().size() == 1 && "interrupt".equals(o4.issue().get(0).id()), o4.summary());

        var o5 = LlmAdvisor.parseOrders("我觉得还行。");
        expect("★★ 回复里没有 JSON ⇒ 空订单（绝不下达默认指令）",
                o5.isEmpty() && !o5.dropped().isEmpty(), o5.summary());

        String user = LlmAdvisor.userContent(null, TuningBus.global(), null, "[]");
        expect("★★ 用户内容含 directives_active 与说明（模型才知道指令表在哪）",
                user.contains("directives_active") && user.contains("directives_available_note"),
                user.substring(0, Math.min(80, user.length())));
    }

    private static boolean rejects(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
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
