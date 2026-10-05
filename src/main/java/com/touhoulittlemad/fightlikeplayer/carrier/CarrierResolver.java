package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 载体解析器 —— M2 的核心：<b>「女仆持有什么」 ⇒ 「她现在能做什么」</b>。
 *
 * <h2>它在整个架构里的位置</h2>
 * <pre>
 *   catalog/（静态数据）
 *        │  M3：加载
 *        ▼
 *   ┌─────────────────────────────────────────────┐
 *   │  CarrierResolver  ←─ 你在这里                 │
 *   │    持有物 × 载体规则 → 候选动作               │
 *   └─────────────────────────────────────────────┘
 *        │  List&lt;CandidateAction&gt;
 *        ▼
 *   DecisionCycle（M1，已完成）→ 选中动作 → 执行器（M4）
 * </pre>
 *
 * <h2>流水线（docs/09 §4.4）</h2>
 * <pre>
 * 1. 枚举持有物                      ← 由适配器提供（六处位置）
 * 2. 载体命中 → 收集候选动作
 * 3. 硬过滤（六步，顺序不可换）
 * 4. 解析向量（A 档参数化：vectorOverrides）
 * 5. 解析"需要的物品"（W 项：供执行器换手用）
 * </pre>
 *
 * <p>★ <b>纯 JVM</b>：输入是 {@link PossessedItem} 与 {@link ContextFacts}，都是字符串与布尔，
 * 因此整条流水线可以在没有游戏的情况下断言。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4</a>
 */
public final class CarrierResolver {

    private final List<ActionSpec> actions;
    private final List<CarrierRule> rules;

    /**
     * ★★ <b>执行器账本里"已实现"的动作 id</b>（来自 {@code catalog/executors.json}）。
     *
     * <p>为什么解析器必须知道它：见 {@link CatalogSource#EXECUTORS_FILE} 的说明 ——
     * "有向量、可达、但没执行器"的动作如果进了候选集，就会因为
     * <b>"没做出来 ⇒ 不扣代价 ⇒ 弹簧不动 ⇒ 下周期再选中它"</b>而<b>永久占住决策</b>，
     * 把真正能做的动作全部饿死（这是委托方实测「法术无法释放 / 拔刀剑没有动作」的根因）。
     *
     * <p>⚠️ <b>空集 = 账本没读到</b> ⇒ <b>不过滤</b>（fail-open）。这是刻意的例外：
     * 若这里 fail-closed，一个读不到的文件会让女仆<b>彻底不动</b> ——
     * 那是比"偶尔选到做不出来的动作"严重得多的失效。
     */
    private final Set<String> implementedIds;

    public CarrierResolver(List<ActionSpec> actions, List<CarrierRule> rules) {
        this(actions, rules, Set.of());
    }

    /**
     * @param implementedIds 已实现执行器的动作 id；<b>空集 = 不做过滤</b>
     */
    public CarrierResolver(List<ActionSpec> actions, List<CarrierRule> rules,
                           Set<String> implementedIds) {
        this.actions = List.copyOf(actions);
        this.rules = List.copyOf(rules);
        this.implementedIds = implementedIds == null ? Set.of() : Set.copyOf(implementedIds);
    }

    /** 是否拿到了执行器账本（诊断用）。 */
    public boolean hasExecutorLedger() {
        return !implementedIds.isEmpty();
    }

    /** 已实现执行器的动作 id（只读）。 */
    public Set<String> implementedIds() {
        return implementedIds;
    }

    /** 一次解析的完整结果。 */
    public record Resolution(
            List<CandidateAction> candidates,
            List<Dropped> dropped,
            List<String> notes,
            /**
             * ★★ <b>动作 id → 让它在女仆身上可用的那件物品</b>（只含"确实需要物品"的动作）。
             *
             * <p><b>为什么要把这个暴露出来</b>：解析阶段是<b>按女仆拥有的全部物品</b>
             * （主手 + 盔甲 + 背包 + 女仆背包 + Curios）算出候选集的，但<b>执行阶段只能操作主手</b>。
             * 两者之间的那一环就是「<b>如果动作的物品不在手上，就切到手上</b>」——
             * 它是<b>执行器的前置步骤，不是一个动作</b>（docs/09 §4.6.2）。
             *
             * <p>⚠️ 此前这一环<b>根本不存在</b>：候选集来自背包，执行器却都只看主手
             * ⇒ 实测表现为「女仆从不换物品，手上拿着啥就用啥」（委托方 2026-09-30 观察）。
             *
             * <p>★ 值不会是 null（不需要物品的动作不入表）⇒ 语义与
             * {@code ActionSpec} 的"载体为 always"一致：查不到 = 无需换手。
             */
            Map<String, PossessedItem> evidence
    ) {
        public Resolution {
            candidates = List.copyOf(candidates);
            dropped = List.copyOf(dropped);
            notes = List.copyOf(notes);
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }

        /** 空解析（副手为空等场景）。 */
        public static Resolution empty() {
            return new Resolution(List.of(), List.of(), List.of(), Map.of());
        }

        /** 该动作靠哪件物品才可用；{@code null} = 不需要物品（或该动作没进候选）。 */
        public PossessedItem evidenceOf(String actionId) {
            return evidence.get(actionId);
        }

        public boolean isEmpty() {
            return candidates.isEmpty();
        }

        /** 按原因统计丢弃数 —— 排查"能力为什么不见了"的第一入口。 */
        public Map<DropReason, Integer> droppedByReason() {
            Map<DropReason, Integer> m = new LinkedHashMap<>();
            for (Dropped d : dropped) {
                m.merge(d.reason(), 1, Integer::sum);
            }
            return m;
        }

        /** 一行摘要。 */
        public String summary() {
            return "可用 " + candidates.size() + " / 丢弃 " + dropped.size() + " " + droppedByReason();
        }
    }

    /** 一条被丢弃的动作 + 原因。 */
    public record Dropped(String actionId, DropReason reason, String detail) {
    }

    /**
     * 解析。
     *
     * @param possessed 女仆当前持有的物品（★ 已覆盖全部六处位置）
     * @param ctx       世界状态事实
     * @param config    弹簧参数（仅用于日志格式化）
     */
    // ★★ 关于"召唤位"（第十七轮续）：它**不是**动作级的门。
    //   实测 `vectors.json`：只有 `goety:cast_focus` / `irons:cast_spell` / `irons:recast`
    //   三条动作在【override】里带 REINFORCE 0.8（SUMMON 类别），而它们的**默认向量 REINFORCE = 0**
    //   ⇒ 动作级过滤会把整个施法动作丢掉（连火球都不能放）✗
    //   ⇒ 正确位置是**挑法术那一步**，见 `decision/ServantSlots` 与两个挑选器。

    public Resolution resolve(List<PossessedItem> possessed, ContextFacts ctx, SpringConfig config) {
        List<CandidateAction> candidates = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // ── 2. 载体命中：carrier → 该载体上"有物品依据"的动作 ────────
        // itemEvidence: actionId → 让它可用的物品（W 项用它来换手）
        Map<String, PossessedItem> itemEvidence = new LinkedHashMap<>();
        // ★ exhaustedEvidence: actionId → 唯一依据是"快坏了的物品" ⇒ 用于区分 ITEM_EXHAUSTED 与 NO_CARRIER
        Map<String, PossessedItem> exhaustedEvidence = new LinkedHashMap<>();

        // ── 2a. ★★ 专精度抑制（2026-10-01）：一件物品只由【最专】的规则提供动作 ──
        //   实例：拔刀剑同时命中 slashblade:blade（instanceof）与 maid:weapon（属性）
        //   ⇒ 候选里会同时出现"拔刀剑连击"与"通用近战"，她于是"拿着刀却不使刀"。
        //   规则：同一件物品上，专精度低于该物品最高值的规则**不产动作**（同精度都保留）。
        //   详见 CarrierRule#specificity。
        java.util.Map<PossessedItem, Integer> bestSpec = new java.util.IdentityHashMap<>();
        for (CarrierRule rule : rules) {
            if (!ctx.modLoaded(rule.requiresMod()) || rule.isAlways() || rule.isUnresolved()) {
                continue;
            }
            for (PossessedItem item : possessed) {
                if (rule.matches(item)) {
                    bestSpec.merge(item, rule.specificity(), Math::max);
                }
            }
        }
        int suppressed = 0;

        for (CarrierRule rule : rules) {
            if (!ctx.modLoaded(rule.requiresMod())) {
                continue;                       // 模组未装 ⇒ 整条规则不加载
            }
            if (rule.isUnresolved()) {
                notes.add("⚠ 载体规则判据未查证：" + rule.describe());
                continue;
            }
            if (rule.isAlways()) {
                // 不需要物品 ⇒ 永远命中
                for (ActionSpec a : actions) {
                    if (a.carrier().equals(rule.carrier())) {
                        itemEvidence.putIfAbsent(a.id(), null);
                    }
                }
                continue;
            }
            for (PossessedItem item : possessed) {
                if (!rule.matches(item)) {
                    continue;
                }
                // ★★ 专精度抑制：同一件物品上，只有最专的那条（或同精度的那几条）规则产动作。
                //    例：拿拔刀剑时抑制 maid:weapon（通用近战）⇒ 于是"拿着刀就使刀"。
                Integer best = bestSpec.get(item);
                if (best != null && rule.specificity() < best) {
                    suppressed++;
                    continue;
                }
                // ★ 耐久过滤：快坏掉的物品【不提供】动作。
                //   但注意：判据落在【物品】而不是【载体】——
                //   同类里只要还有一件好的，动作就仍然可用（见 ItemUsability 类注释）。
                boolean usable = ItemUsability.isUsable(item);
                for (ActionSpec a : actions) {
                    if (!a.carrier().equals(rule.carrier())) {
                        continue;
                    }
                    if (!usable) {
                        exhaustedEvidence.putIfAbsent(a.id(), item);
                        continue;
                    }
                    // ★ 同一载体的多个物品：保留优先级最高的那个（槽位优先级 + 是否有参数）
                    itemEvidence.merge(a.id(), item, (old, neu) ->
                            betterCarrier(old, neu) ? old : neu);
                }
            }
        }

        if (suppressed > 0) {
            notes.add("★ 专精度抑制：" + suppressed + " 次（同一物品上有更专的载体规则 ⇒ 泛化规则不产动作）");
        }

        // ⚠️ 这里【不能】统计 NO_EXECUTOR —— 逐条硬过滤的循环在后面（旧版把说明写在这里，
        //    结果 dropped 还是空的 ⇒ 那条 note 永远不出现。自测抓到的）。

        if (itemEvidence.isEmpty()) {
            notes.add("载体命中为空 ⇒ 女仆没有提供任何动作的物品");
        }

        // ── 3~5. 逐条硬过滤 + 向量解析 ─────────────────────────────
        for (ActionSpec a : actions) {
            PossessedItem carrierItem = itemEvidence.get(a.id());

            // 该动作没有载体依据 ⇒ 女仆做不了
            if (!itemEvidence.containsKey(a.id())) {
                // ★ 区分两种"没有"：压根没有 vs 有但都快坏了 —— 后者是"该换装备了"的信号
                PossessedItem exhausted = exhaustedEvidence.get(a.id());
                if (exhausted != null) {
                    dropped.add(new Dropped(a.id(), DropReason.ITEM_EXHAUSTED,
                            exhausted.shortName() + " " + ItemUsability.whyUnusable(exhausted)));
                } else if (hasProviderMod(a, ctx)) {
                    dropped.add(new Dropped(a.id(), DropReason.NO_CARRIER, "没有匹配的载体物品"));
                }
                continue;
            }

            // ① sourceMod 未加载
            if (!ctx.modLoaded(a.sourceMod())) {
                dropped.add(new Dropped(a.id(), DropReason.MOD_NOT_LOADED, a.sourceMod()));
                continue;
            }

            // ② 载体规则判据未查证（该载体对应的规则全是 unresolved）
            if (carrierRuleUnresolved(a.carrier())) {
                dropped.add(new Dropped(a.id(), DropReason.CARRIER_RULE_UNRESOLVED, a.carrier()));
                continue;
            }

            // ②b ★ 不是"战术选择"（维护步骤 / 姿态配置）⇒ 不进候选集
            //     见 docs/09 §2.4：让决策层去权衡"换弹"是污染评分空间
            if (!a.isChoice()) {
                dropped.add(new Dropped(a.id(), DropReason.NOT_A_CHOICE, "role=" + a.role()));
                continue;
            }

            // ②c ★★ 【本项目的关键修复】没有执行器的动作【不是选项】，是缺口。
            //   为什么必须在解析期挡：执行器回报 NO_EXECUTOR ⇒ 按设计"不扣代价"⇒ 弹簧不动
            //   ⇒ 下一周期最近邻仍然是它 ⇒ **永久占住决策**，把能做出来的动作全部饿死。
            //   实测证据（委托方 2026-10-01 会话日志）：quick_charge 被选中 118 次、
            //   summoned_sword 104、combo_b_finish 100、judgement_cut_just 98、tacz:melee 98、
            //   goety:transfer_servant_ownership 70 —— 女仆于是"站着不动，法术也永远不放"。
            if (!implementedIds.isEmpty() && !implementedIds.contains(a.id())) {
                dropped.add(new Dropped(a.id(), DropReason.NO_EXECUTOR,
                        "执行器账本里没有它（见 catalog/executors.json）"));
                continue;
            }

            // ③ reach == RC ⇒ 不可达
            if (a.isUnreachable()) {
                dropped.add(new Dropped(a.id(), DropReason.UNREACHABLE, "reach=RC"));
                continue;
            }

            // ④ 前置条件
            DropReason pre = checkPreconditions(a, carrierItem, ctx);
            if (pre != null) {
                dropped.add(new Dropped(a.id(), pre, preconditionDetail(a, carrierItem, ctx)));
                continue;
            }

            // ⑤ 状态要求
            if (!checkStateReq(a, ctx)) {
                dropped.add(new Dropped(a.id(), DropReason.STATE_REQ_FAILED, String.join(",", a.stateReq())));
                continue;
            }

            // ⑥ 互斥组
            String conflict = checkExclusivity(a, ctx);
            if (conflict != null) {
                dropped.add(new Dropped(a.id(), DropReason.EXCLUSIVITY_CONFLICT, conflict));
                continue;
            }

            // ⑦ 冷却
            if (ctx.coolingDown().contains(a.id())) {
                dropped.add(new Dropped(a.id(), DropReason.ON_COOLDOWN, a.id()));
                continue;
            }

            // ── 4. 解析向量（A 档参数化）──────────────────────────
            ActionSpec.VectorPick pick = carrierItem == null
                    ? new ActionSpec.VectorPick(a.defaultVector(), null)
                    : a.pickVector(carrierItem::param);

            NeedVector v = pick.vector();
            if (v == null) {
                dropped.add(new Dropped(a.id(), DropReason.NO_VECTOR, "清单未写 v_x"));
                continue;
            }

            // ── 5. ★ 契合度乘数（docs/09 §3.5）：静态向量 → 有效向量 ──
            //    没有这一步，女仆会在满血时刷治疗、对单个敌人放大招。
            NeedVector u = v.multiplyComponentwise(FitnessCalculator.factors(ctx))
                    .multiplyComponentwise(FitnessCalculator.noModifiers());

            candidates.add(new CandidateAction(
                    a.id(), v, u,
                    // ★ 承诺时长也按参数解析：AUTO 射击 = 一梭子（见 docs/09 §2.4）
                    carrierItem == null ? a.commitmentTicks() : a.pickCommitment(carrierItem::param),
                    a.interruptible()));

            if (pick.usedOverride()) {
                notes.add("参数化命中：" + a.id() + " ← " + pick.matchedKey());
            }
        }

        // ★★ 执行器账本的说明【必须放在逐条过滤循环之后】——
        //    否则 dropped 还是空的，这条 note 永远不会出现（自测抓到的顺序 bug）。
        if (implementedIds.isEmpty()) {
            notes.add("⚠ 没有读到执行器账本（catalog/executors.json）⇒ 【不过滤】无执行器的动作。"
                    + "此时女仆会反复选中做不出来的动作（弹簧不动 ⇒ 下周期再选中它）⇒ 请检查清单是否完整");
        } else {
            long noExec = dropped.stream().filter(d -> d.reason() == DropReason.NO_EXECUTOR).count();
            if (noExec > 0) {
                notes.add("★★ 无执行器而挡下 " + noExec
                        + " 条（它们不是选项，是已登记的计划缺口 —— 放进候选集会让女仆永久卡住）");
            }
        }

        // ★ 暴露"动作 → 依据物品"，供执行器的换手前置使用（见 Resolution#evidence 的说明）。
        //   ⚠️ itemEvidence 里对"不需要物品"的动作存的是 null，而 Map.copyOf 不接受 null
        //   ⇒ 这里过滤掉它们（语义等价：查不到 = 无需换手）。
        Map<String, PossessedItem> evidence = new LinkedHashMap<>();
        for (var en : itemEvidence.entrySet()) {
            if (en.getValue() != null) {
                evidence.put(en.getKey(), en.getValue());
            }
        }

        return new Resolution(candidates, dropped, notes, evidence);
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 两个候选物品之间取更合适的：手上优先，其次有参数的优先。 */
    private static boolean betterCarrier(PossessedItem old, PossessedItem neu) {
        if (old.slot().isHand() != neu.slot().isHand()) {
            return neu.slot().isHand();
        }
        if (old.params().isEmpty() != neu.params().isEmpty()) {
            return !neu.params().isEmpty();
        }
        return neu.slot().priority() < old.slot().priority();
    }

    /** 该动作的提供方模组装了吗（用于决定"无载体"是否值得记录）。 */
    private boolean hasProviderMod(ActionSpec a, ContextFacts ctx) {
        return ctx.modLoaded(a.sourceMod()) && !carrierRuleMissing(a.carrier());
    }

    private boolean carrierRuleMissing(String carrier) {
        return rules.stream().noneMatch(r -> r.carrier().equals(carrier));
    }

    private boolean carrierRuleUnresolved(String carrier) {
        List<CarrierRule> rs = rules.stream().filter(r -> r.carrier().equals(carrier)).toList();
        return !rs.isEmpty() && rs.stream().allMatch(CarrierRule::isUnresolved);
    }

    /**
     * 前置条件检查。
     *
     * <p>★ 关于 {@code hasItem}：清单里的 {@code hasItem} 多数只有说明文字、没有机器可读的物品引用。
     * 但**它能出现在这里，本身就说明载体规则已经从一个真实物品上命中了它**
     * ⇒ 对"由具体物品提供"的动作，{@code hasItem} 视为满足。
     * 若载体是 {@code always}（不需要物品）却声明了 {@code hasItem}，则判不满足（fail-closed）。
     *
     * @return null = 全部满足
     */
    private DropReason checkPreconditions(ActionSpec a, PossessedItem carrierItem, ContextFacts ctx) {
        for (Map<String, Object> p : a.preconditions()) {
            String type = String.valueOf(p.get("type"));
            switch (type) {
                case "hasItem" -> {
                    if (carrierItem == null) {
                        return DropReason.PRECONDITION_FAILED;
                    }
                }
                case "hasTarget" -> {
                    if (!ctx.hasTarget()) {
                        return DropReason.PRECONDITION_FAILED;
                    }
                }
                case "hasServant" -> {
                    if (ctx.servantCount() <= 0) {
                        return DropReason.PRECONDITION_FAILED;
                    }
                }
                case "hasEnchantment" -> {
                    String key = "enchant:" + p.getOrDefault("enchantment", p.getOrDefault("note", "?"));
                    if (carrierItem == null || !carrierItem.hasCapability(key)) {
                        return DropReason.PRECONDITION_FAILED;
                    }
                }
                case "resourceAtLeast" -> {
                    String res = String.valueOf(p.getOrDefault("resource", "?"));
                    Number need = (Number) p.getOrDefault("amount", 0);
                    if (ctx.resource(res) < need.doubleValue()) {
                        return DropReason.PRECONDITION_FAILED;
                    }
                }
                case "spellConditionsMet" -> {
                    // ★★ 2026-09-30 修：旧实现用【固定键 "spellConditionsMet"】——
                    //   于是 Goety 与铁魔法【共用一个键】，喂了"Goety 能施法"会连带放行
                    //   "铁魔法能施法"（fail-closed 的意义被抹掉）。
                    //   现在与 custom 同规则：优先清单里显式写的 fact，否则 "<动作id>#<下标>"。
                    //   ⇒ 游戏侧喂 {"goety:can_cast": true, "irons:can_cast": true} 即可分别控制。
                    String key = customKey(a, p);
                    if (!ctx.hasCustomFact(key) || !ctx.customFact(key)) {
                        return DropReason.PRECONDITION_CUSTOM_UNRESOLVED;
                    }
                }
                case "custom" -> {
                    // ★ fail-closed：必须被显式喂进来
                    // ★ 键的取法：优先用清单里显式写的 fact；否则用 "<动作id>#<下标>"
                    //   —— 否则所有 custom 共用一个键，喂一个"弹药够"会连带满足"法术条件满足"。
                    String key = customKey(a, p);
                    if (!ctx.hasCustomFact(key) || !ctx.customFact(key)) {
                        return DropReason.PRECONDITION_CUSTOM_UNRESOLVED;
                    }
                }
                default -> {
                    return DropReason.PRECONDITION_CUSTOM_UNRESOLVED;
                }
            }
        }
        return null;
    }

    private String preconditionDetail(ActionSpec a, PossessedItem item, ContextFacts ctx) {
        return "preconditions=" + a.preconditions().size()
                + (item == null ? " (载体为无物品规则)" : " (载体物品=" + item.shortName() + ")");
    }

    /**
     * {@code custom} 前置条件的上下文事实键。
     *
     * <p>优先用清单里显式声明的 {@code fact}；没有则退化为 {@code "<动作id>#<下标>"}。
     * <p>★ <b>为什么不能都用 "custom"</b>：那样"弹药够"与"法术条件满足"会共用一个键，
     * 喂一个就连带放行另一个 ⇒ fail-closed 失去意义。
     */
    public static String customKey(ActionSpec a, Map<String, Object> predicate) {
        Object explicit = predicate.get("fact");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit);
        }
        int idx = a.preconditions().indexOf(predicate);
        return a.id() + "#" + (idx < 0 ? "?" : idx);
    }

    private boolean checkStateReq(ActionSpec a, ContextFacts ctx) {
        for (String s : a.stateReq()) {
            boolean ok = switch (s) {
                case "GROUND" -> ctx.onGround();
                case "AERIAL" -> !ctx.onGround();
                case "MOVING" -> ctx.moving();
                case "NOT_MOVING" -> !ctx.moving();
                case "SNEAKING" -> ctx.sneaking();
                case "HAS_TARGET" -> ctx.hasTarget();
                case "NEAR_ENEMY" -> ctx.targetInMeleeRange();
                // 其余（SHEATHED / NEAR_WALL / TARGET_IN_VIEW / SERVANT_SELECTED / BUFF /
                // AFTER / TIMING_WINDOW）需要更细的世界信息 ⇒ 暂按满足处理，
                // 并已在 docs/09 §8.0b 的 WIP 表中登记
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String checkExclusivity(ActionSpec a, ContextFacts ctx) {
        for (String g : a.exclusiveGroups()) {
            if (ctx.activeExclusive().getOrDefault(g, 0) > 0) {
                return g;
            }
        }
        return null;
    }

    public List<ActionSpec> actions() {
        return actions;
    }

    public List<CarrierRule> rules() {
        return rules;
    }

    /** 按 id 找动作规格（找不到返回 null）。 */
    public ActionSpec specOf(String actionId) {
        if (actionId == null) {
            return null;
        }
        for (ActionSpec a : actions) {
            if (a.id().equals(actionId)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 按载体 id 取规则（找不到返回 null）。
     *
     * <p>★ 执行器的<b>换手前置</b>用它来判断"手上这件东西能不能满足该动作的载体"
     * —— <b>复用解析期的同一条规则</b>，而不是另写一套判据（否则两者会漂移）。
     */
    public CarrierRule ruleOf(String carrierId) {
        if (carrierId == null) {
            return null;
        }
        for (CarrierRule r : rules) {
            if (r.carrier().equals(carrierId)) {
                return r;
            }
        }
        return null;
    }

    /**
     * ★ <b>这个动作所属载体的"站位偏好"</b>（格）—— 步法的输入。
     *
     * <p>「手里拿什么就站什么距离」：弓 12 格、拔刀剑 3 格、法术 9 格。
     * 数据在 {@code catalog/carriers.json} 的 {@code preferredRange}。
     *
     * <p>★ 它<b>不是</b>动作自身的属性，而是<b>载体的属性</b> ——
     * 因此换武器时站位会自动跟着变，不需要为每条动作写一遍。
     *
     * @return 站位距离；未知动作 ⇒ {@link CarrierRule#DEFAULT_PREFERRED_RANGE}
     */
    public double preferredRangeOf(String actionId) {
        ActionSpec a = specOf(actionId);
        if (a == null) {
            return CarrierRule.DEFAULT_PREFERRED_RANGE;
        }
        for (CarrierRule r : rules) {
            if (r.carrier().equals(a.carrier())) {
                return r.preferredRange();
            }
        }
        return CarrierRule.DEFAULT_PREFERRED_RANGE;
    }

    /**
     * ★ <b>这个动作的最小重复间隔</b>（tick）—— 冷却/节奏。
     *
     * <p>权威值来自清单的 {@code cooldownTicks}；未声明时由
     * {@link ActionSpec#effectiveCooldownTicks()} 按兜底规则处理
     * （{@code max(承诺时长, 20)}）—— 兜底的存在是为了消灭"瞬时动作被 4 次/秒刷屏"。
     *
     * <p>★ 它填的是 {@link ContextFacts#coolingDown()}，因此<b>冷却过滤走的是既有的
     * {@link DropReason#ON_COOLDOWN} 通路</b>，不新增机制。
     */
    public int cooldownTicksOf(String actionId) {
        ActionSpec a = specOf(actionId);
        return a == null ? ActionSpec.DEFAULT_RHYTHM_TICKS : a.effectiveCooldownTicks();
    }

    /** 全部动作里有多少条有向量 —— 对应 WIP 表的 W2。 */
    public int vectorCoverage() {
        return (int) actions.stream().filter(ActionSpec::hasVector).count();
    }

    /** 载体 id 集合（用于自检：清单里的 carrier 是否都有规则）。 */
    public Set<String> carriersWithoutRule() {
        Set<String> out = new java.util.TreeSet<>();
        for (ActionSpec a : actions) {
            boolean found = rules.stream().anyMatch(r -> r.carrier().equals(a.carrier()));
            if (!found) {
                out.add(a.carrier());
            }
        }
        return out;
    }
}
