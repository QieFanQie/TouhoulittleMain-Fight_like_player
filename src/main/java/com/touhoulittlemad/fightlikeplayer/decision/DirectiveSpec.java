package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>指令的种类与语义</b> —— 纯逻辑，可离线断言。
 *
 * <h2>指令是什么（与"动作"、"旋钮"划清界限）</h2>
 * <table border="1">
 *   <tr><th></th><th>动作（动作层）</th><th>旋钮（{@link TuningBus}）</th><th>指令（本类）</th></tr>
 *   <tr><td>回答什么</td><td><b>这一步做什么</b></td><td><b>连续量的多少</b>（快慢/强度）</td>
 *       <td><b>用哪一类手段、遵守什么约束</b></td></tr>
 *   <tr><td>形态</td><td>离散、可选、要打分</td><td>0~2 的倍数</td>
 *       <td><b>开关式</b>（有无）+ 少量参数</td></tr>
 *   <tr><td>谁下</td><td>决策层（弹簧 + 最近邻）</td><td>JEV（评分类）</td>
 *       <td><b>LLM / 玩家 / 女仆对话</b></td></tr>
 * </table>
 * ★ 边界（委托方 2026-10-02 的裁决）：**评分类归 JEV，指令类归 LLM。**
 *
 * <p>★ 本类**不含任何 MC 类型**：白名单、TTL 上限、互斥、参数钳制都能离线断言
 * （本项目两次踩过 {@code NoClassDefFoundError} 之后立的规矩，见 docs/13 §29）。
 *
 * @see DirectiveBus
 * @see <a href="../../../../../../../docs/15-LLM指令系统计划.md">docs/15 §2（指令表）</a>
 */
public final class DirectiveSpec {

    private DirectiveSpec() {
    }

    /** 瞬间 = 下达后立刻执行一次；持续 = 一直生效到取消/超时。 */
    public enum Kind {
        INSTANT, SUSTAINED
    }

    /**
     * 参数规格。
     *
     * @param text 这是个**文本**参数（例如法术类名）；此时 min/max/def 无意义
     */
    public record ParamSpec(String zh, double def, double min, double max, boolean text) {

        public static ParamSpec of(String zh, double def, double min, double max) {
            return new ParamSpec(zh, def, min, max, false);
        }

        public static ParamSpec text(String zh) {
            return new ParamSpec(zh, 0, 0, 0, true);
        }

        /** 把值钳到允许区间（文本参数原样返回）。 */
        public double clamp(double v) {
            if (text) {
                return v;
            }
            return Math.max(min, Math.min(max, v));
        }
    }

    /**
     * 一条指令的说明书（= 计划文档 §2 的表，落到代码里）。
     *
     * @param id          稳定标识（LLM 只认这个）
     * @param kind        瞬间 / 持续
     * @param zh          中文名（给玩家看）
     * @param enforce     强制手段**一句话**（会进 LLM 的 prompt，也会进 `/flp directive`）
     * @param params      参数规格（名 → 规格）；空 = 无参数
     * @param mutexGroup  互斥组（同组只能有一条生效；{@code null} = 不参与互斥）
     * @param defTtlTicks 持续指令的默认存活 tick（瞬间为 0）
     */
    public record Spec(String id, Kind kind, String zh, String enforce,
                       Map<String, ParamSpec> params, String mutexGroup, int defTtlTicks) {

        public boolean hasParams() {
            return params != null && !params.isEmpty();
        }

        /**
         * ★ <b>下达这条指令，模型必须知道哪些事实</b>（{@link CombatScene} 的键路径）。
         *
         * <p>★ 委托方第十四轮的核实问题：「每个指令的下达所需要的信息，llm 都能获取到相关内容吗？」
         * 这个方法是那次核实的**固化形式**：
         * <ul>
         *   <li>每条指令在此声明它依赖的事实；</li>
         *   <li>{@code DirectiveSelfTest} 断言 ① 21 条指令**每条都声明了**
         *       （忘了写 ⇒ 自测失败，不会"悄悄缺一路"）② 声明的键**全部真实存在于**
         *       {@link CombatScene#KEYS}（声明了却没人产出 ⇒ 自测失败）。</li>
         * </ul>
         * ★ 与第 56 条（"每个位置都算了吗"）同族，这次管的是**输入侧**：
         * 「模型要做这个判断，它需要什么？我给了吗？」
         */
        public List<String> needs() {
            return NEEDS.getOrDefault(id, List.of());
        }
    }

    /**
     * id → 该指令依赖的事实键（{@link CombatScene} 的键路径）。
     * ★ 键名必须与 {@link CombatScene} 的产出**逐字相同** —— 由自测守住。
     */
    private static final Map<String, List<String>> NEEDS = buildNeeds();

    private static Map<String, List<String>> buildNeeds() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // 手段选择：不知道她手上/包里有什么，"仅用某类手段"就是在瞎指挥
        m.put("magic_only", List.of("spells_available", "equipment.has_wand",
                "equipment.has_spellbook_or_scroll"));
        // ★ 细分到体系（第二十一轮）：要下达"只用铁魔法"，她至少得知道
        //   ① 她会哪些法术（spells_available）② 铁魔法那套载体在不在她身上
        m.put("goety_only", List.of("spells_available", "equipment.has_wand"));
        m.put("irons_only", List.of("spells_available", "equipment.has_spellbook_or_scroll"));
        m.put("melee_only", List.of("equipment.has_melee_weapon", "self.main_hand"));
        m.put("ranged_only", List.of("equipment.has_gun", "equipment.has_bow", "spells_available"));
        m.put("no_summons", List.of("spells_available", "servants"));
        // 站位：距离与步法
        m.put("keep_distance", List.of("self.distance_to_target", "self.gait"));
        m.put("close_in", List.of("self.distance_to_target", "self.gait"));
        m.put("hold_position", List.of());
        m.put("no_retreat", List.of());
        // 装备与资源
        m.put("no_item_switch", List.of("equipment.inventory_items", "self.main_hand"));
        m.put("conserve_ammo", List.of("equipment.has_gun"));
        m.put("focus_category", List.of("spells_available"));
        m.put("ban_focus", List.of("spells_available"));
        // ★ 第二十七轮：与 ban_focus 同一组依据（模型要**看到**她的 label 才填得出来）
        m.put("only_focus", List.of("spells_available"));
        // ★ 半命题（第十五轮）：要知道"她身上到底有什么"才敢填参数 ——
        //   `equipment.items` 就是那张清单（委托方问的"llm 已经能拿到背包物品列表了吗"，
        //   答案是**在此之前不能**：此前只有 equipment.has_* 那几个布尔）
        m.put("only_item", List.of("equipment.items", "self.main_hand"));
        m.put("ban_item", List.of("equipment.items", "self.main_hand"));
        m.put("focus_entity", List.of("battle.has_target", "battle.target_kind"));
        m.put("focus_one", List.of("battle.has_target", "battle.target_uuid"));
        m.put("ban_entity", List.of("battle.has_target", "battle.target_kind"));
        // ★ 动作白名单：模型必须**看到**她自己有哪些动作 id 才填得出来
        //   （由 compat 的 FlpMaidContexts 把清单喂进她的上下文）
        m.put("only_actions", List.of("actions_available"));
        m.put("protect_owner", List.of("owner.health_pct", "owner.distance"));
        // 瞬间
        m.put("interrupt", List.of("self.in_flight_action"));
        m.put("disengage", List.of("self.health_pct", "battle.enemies_nearby"));
        m.put("reload_now", List.of("equipment.has_gun"));
        m.put("recall_servants", List.of("servants"));
        m.put("dismiss_servants", List.of("servants"));
        m.put("stance_guard", List.of("equipment.has_shield"));
        m.put("stance_attack", List.of("equipment.has_shield"));
        m.put("heal_now", List.of("spells_available", "self.health_pct"));
        m.put("switch_item", List.of("equipment.items", "self.main_hand"));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 声明过 needs 的 id 集合（自测用它检查"有没有漏声明"）。 */
    public static java.util.Set<String> declaredNeeds() {
        return NEEDS.keySet();
    }

    /** 持续指令的 TTL 上限（10 分钟）—— ★ 防"永久卡死"（docs/13 §26 的教训）。 */
    public static final int MAX_TTL_TICKS = 20 * 60 * 10;

    /**
     * ★★ <b>只能由「主人/玩家」下达的指令</b>（第二十一轮，委托方原话：
     * 「如果主人没明说禁用召唤，则不进行禁用召唤的指令」）。
     *
     * <p>本项目有两条写入指令总线的 LLM 通路：<b>她自己的对话</b>（{@code flp_directive}，
     * source={@code chat}，说话的人就是主人）与<b>动态指挥官</b>（{@code LlmAdvisor}，
     * source={@code llm}）。后者<b>看不到主人说了什么</b> —— 它只吃战斗统计与场景 JSON
     * ⇒ 它下这两条必然是"自作主张"：
     * <ul>
     *   <li>{@code dismiss_servants}：**不可逆地处死**她名下全部仆从 —— 破坏性动作绝不能由
     *       一个看不见主人意图的通道决定；</li>
     *   <li>{@code no_summons}：这是一条**持续**限制，主人没说要，它就不该存在
     *       （正是委托方这一条要求的字面含义）。</li>
     * </ul>
     *
     * <p>★ 判据写在纯逻辑层（不写在 compat 层）⇒ 可离线断言；
     * {@code DirectiveHolder#issue} 是唯一执行者（口径只有一处）。
     * ★ 顺带记录一个已交过学费的教训：**口径不能只写在提示词里** ——
     * LlmAdvisor 的规则 9 也写了这两条，但那只是"说明"，真正的门在这里。
     */
    private static final Map<String, String> PLAYER_ONLY = Map.of(
            "dismiss_servants", "清除仆从是不可逆的处死，只能由主人明说",
            "no_summons", "主人没明说禁用召唤时不许自作主张");

    /**
     * ★ 这条指令是否"只能由主人下达"。
     *
     * @return {@code null} = 自动指挥通道也可以下；否则是一句可读理由
     */
    public static String playerOnlyReason(String id) {
        return id == null ? null : PLAYER_ONLY.get(id);
    }

    /** 声明过的"只能由主人下达"的 id（自测用来钉住它没被悄悄删掉）。 */
    public static java.util.Set<String> playerOnlyIds() {
        return PLAYER_ONLY.keySet();
    }

    /**
     * ★ <b>指令全集</b>（29 条：21 条持续 + 8 条瞬间）。
     *
     * <p>★ 加指令只需要在这里加一行：① 白名单自动生效 ② LLM 的 prompt **自动包含它**
     * （由 {@link #describeForLlm()} 生成，不是手写进提示词）③ `/flp directive list` 自动显示。
     * <p>★ <b>别在这里写死条数之外的东西</b>：条数只作参考，自测断言的是"每条都声明了 needs"
     * 而不是"一共几条"（写死条数会逼着以后每次加指令都来改注释 —— 正是本项目的口径漂移源）。
     */
    private static final List<Spec> ALL = List.of(
            // ── 持续：手段选择（互斥组 means：五选一 —— 总类 1 + 细分 2 + 近战/远程 2）──
            new Spec("magic_only", Kind.SUSTAINED, "仅使用魔法攻击（任意一系：铁魔法 + 诡厄巫法）",
                    "过滤候选集：只保留施法类动作。★ 铁魔法与诡厄巫法**都算魔法**（这是【总类】）；"
                            + "只许某一系时才用 goety_only / irons_only",
                    Map.of(), "means", 20 * 120),
            // ★★ 第二十一轮：委托方原话「女仆似乎做不到"指定使用铁魔法"，对于她而言，
            //   铁魔法和巫法是一个类的」⇒ 在"仅使用魔法"之下**再分一档施法体系**。
            //   总类 magic_only **保留**（她要"随便什么魔法都行"时说这一条）。
            new Spec("goety_only", Kind.SUSTAINED, "仅使用诡厄巫法",
                    "过滤候选集：只保留诡厄巫法的施法动作（goety:cast_focus），"
                            + "铁魔法与非法术动作一并挡下。★ 与 irons_only 互斥（同组 means）："
                            + "同一次回话里只许下其中一条，否则后一条会静默顶掉前一条",
                    Map.of(), "means", 20 * 120),
            new Spec("irons_only", Kind.SUSTAINED, "仅使用铁魔法",
                    "过滤候选集：只保留铁魔法的施法动作（irons:cast_spell/cast_scroll/recast），"
                            + "诡厄巫法与非法术动作一并挡下。★ 与 goety_only 互斥（同组 means）："
                            + "同一次回话里只许下其中一条，否则后一条会静默顶掉前一条",
                    Map.of(), "means", 20 * 120),
            new Spec("melee_only", Kind.SUSTAINED, "仅使用近战/刀技",
                    "过滤候选集：只保留近战与拔刀剑动作",
                    Map.of(), "means", 20 * 120),
            new Spec("ranged_only", Kind.SUSTAINED, "仅使用远程",
                    "过滤候选集：只保留弓/弩/枪/投掷类",
                    Map.of(), "means", 20 * 120),
            new Spec("no_summons", Kind.SUSTAINED, "不召唤",
                    "过滤候选集与聚晶池：排除召唤类动作与召唤类聚晶",
                    Map.of(), null, 20 * 120),
            // ── 持续：站位约束（互斥组 distance：二选一）──
            new Spec("keep_distance", Kind.SUSTAINED, "保持距离攻击",
                    "步法在距离小于 min 时强制后撤，并禁用贴身类动作",
                    Map.of("min", ParamSpec.of("最小距离（格）", 8, 3, 24)), "distance", 20 * 120),
            new Spec("close_in", Kind.SUSTAINED, "贴身缠斗",
                    "步法强制接近到不超过 max 格，并禁用远程",
                    Map.of("max", ParamSpec.of("最大距离（格）", 3, 1, 12)), "distance", 20 * 120),
            new Spec("hold_position", Kind.SUSTAINED, "守点不动",
                    "步法 hold：限制在下达位置 radius 格内",
                    Map.of("radius", ParamSpec.of("半径（格）", 4, 1, 32)), null, 20 * 120),
            new Spec("no_retreat", Kind.SUSTAINED, "禁止后退",
                    "步法禁用 away/disengage 方向（只允许靠近或原地）",
                    Map.of(), null, 20 * 120),
            // ── 持续：装备与资源 ──
            new Spec("no_item_switch", Kind.SUSTAINED, "不换手",
                    "换手前置直接跳过 ⇒ 候选集只由主手现有物品决定",
                    Map.of(), null, 20 * 120),
            new Spec("conserve_ammo", Kind.SUSTAINED, "省弹药（点射）",
                    "枪械由「一梭子」改成「点射 shots 发」",
                    Map.of("shots", ParamSpec.of("每次几发", 2, 1, 20)), null, 20 * 120),
            new Spec("focus_category", Kind.SUSTAINED, "只用某一类聚晶",
                    "聚晶挑选池只保留该类别（0=ATTACK_SINGLE 1=ATTACK_AREA 2=SUMMON 3=UTILITY "
                            + "4=PROTECTION 5=HEALING 6=CONTROL 7=SERVANT_MGMT）",
                    Map.of("category", ParamSpec.of("类别序号", 0, 0, 7)), null, 20 * 120),
            new Spec("ban_focus", Kind.SUSTAINED, "禁用某颗聚晶/法术",
                    "从挑选池里排除该标签（法术类名）",
                    Map.of("label", ParamSpec.text("法术类名，例如 FireballSpell")), null, 20 * 120),
            // ★★ 第二十七轮（委托方实测：「我需要你**仅使用先锋聚晶**施法」）：
            //   她只能编一个物品 id（`goety:vanguard_focus`），而**聚晶常常装在聚晶包/魔杖里、
            //   不是独立物品** ⇒ `only_item` **结构上**指不到它（伺服当场报"她身上没有"）。
            //   ⇒ 补一条"只用某一颗法术/聚晶"，参数与 `ban_focus` **完全同一个口径**
            //     （她法术清单里那个 `label=`）—— 与"禁用某颗"配成一对，学习成本为零。
            new Spec("only_focus", Kind.SUSTAINED, "只用某一颗法术/聚晶",
                    "挑选池只保留该标签（法术类名）—— 与 ban_focus 同一个参数口径；"
                            + "★ 同时挡下非法术动作（与 magic_only 同口径）。"
                            + "★ 参数**不是物品 id**：聚晶常装在聚晶包/魔杖里",
                    Map.of("label", ParamSpec.text("法术类名/标签，例如 VanguardSpell —— "
                            + "照抄她法术清单里的 label=")), null, 20 * 120),
            // ── 持续：半命题（★ 第十五轮，委托方提议）——参数是【物品 id / 生物 id】──
            new Spec("only_item", Kind.SUSTAINED, "只允许用某件物品",
                    "只保留由它承载的动作；不依赖物品的动作（通用挥击）也只在主手是它时才允许",
                    Map.of("item", ParamSpec.text("物品 id / 标签 / 类型名，例如 minecraft:iron_sword、"
                            + "#forge:tools/swords、SwordItem")), "item", 20 * 120),
            new Spec("ban_item", Kind.SUSTAINED, "禁用某件物品",
                    "排除由它承载的动作；它正在主手时不依赖物品的挥击也挡下（换掉之后还有得做才生效）",
                    Map.of("item", ParamSpec.text("物品 id / 标签 / 类型名")), null, 20 * 120),
            new Spec("focus_entity", Kind.SUSTAINED, "只打某一类生物",
                    "目标筛选：她只对匹配的生物产生/保留仇恨（其余的一律放下）",
                    Map.of("entity", ParamSpec.text("生物 id / 实体标签，例如 minecraft:zombie、"
                            + "#minecraft:raiders")), "target", 20 * 120),
            new Spec("focus_one", Kind.SUSTAINED, "只打指定的那一只生物",
                    "目标筛选：只保留 UUID 匹配的那一只（其余的一律放下）",
                    Map.of("entity", ParamSpec.text("那一只的 UUID（见 scene.battle.target_uuid）")),
                    "target", 20 * 120),
            new Spec("ban_entity", Kind.SUSTAINED, "不打某一类生物",
                    "目标筛选：放下对匹配生物的仇恨，也不再选它们（其余照常）",
                    Map.of("entity", ParamSpec.text("生物 id / 实体标签")), null, 20 * 120),
            // ── 持续：动作白名单（★ 第十六轮，委托方提议：「接下来几秒只进行这些动作」）──
            new Spec("only_actions", Kind.SUSTAINED, "只做指定的这些动作",
                    "候选集只留白名单里的动作 id（★ 一个都匹配不上时这条自动失效，绝不清空候选集）",
                    Map.of("actions", ParamSpec.text("动作 id，逗号分隔，例如 slashblade:slash_art,"
                                    + "minecraft:melee_swing"),
                            "seconds", ParamSpec.of("持续秒数（用它当 TTL）", 6, 1, 600)),
                    null, 20 * 6),
            new Spec("protect_owner", Kind.SUSTAINED, "优先护主",
                    "优先选择「护友」类动作（走指令通道，不动弹簧）",
                    Map.of(), null, 20 * 120),
            // ── 瞬间 ──
            new Spec("interrupt", Kind.INSTANT, "中断当前动作",
                    "中止在途动作（引导/蓄力/枪械）并松开手",
                    Map.of(), null, 0),
            new Spec("disengage", Kind.INSTANT, "脱战撤离",
                    "清掉攻击目标 + 强制后撤 N tick",
                    Map.of("ticks", ParamSpec.of("持续 tick", 60, 20, 600)), null, 0),
            new Spec("reload_now", Kind.INSTANT, "立刻换弹",
                    "触发一次换弹（TaCZ 自己处理）",
                    Map.of(), null, 0),
            new Spec("recall_servants", Kind.INSTANT, "召回全部仆从",
                    "把所有仆从拉回身边",
                    Map.of(), null, 0),
            new Spec("dismiss_servants", Kind.INSTANT, "处死她的全部仆从",
                    "★ 不可逆：处死她名下**所有**仆从（Goety 仆从 + 铁魔法召唤物），"
                            + "★ 不看是否限时；★ 她本人不会自己触发这条（对应动作已 role=directive）",
                    Map.of(), null, 0),
            new Spec("stance_guard", Kind.INSTANT, "立刻举盾",
                    "强制举盾 N tick（覆盖副手姿态的自动判断）",
                    Map.of("ticks", ParamSpec.of("持续 tick", 60, 20, 600)), null, 0),
            new Spec("stance_attack", Kind.INSTANT, "立刻收盾进攻",
                    "强制放下盾（解除 stance_guard）",
                    Map.of(), null, 0),
            new Spec("heal_now", Kind.INSTANT, "立刻治疗",
                    "若持有治疗类法术/药水，强制选它一次",
                    Map.of(), null, 0),
            // ★★ 第十六轮（委托方要求）：立刻切换物品。
            new Spec("switch_item", Kind.INSTANT, "立刻切换物品（一次性）",
                    "把那件物品换到主手（默认先中断当前动作；找不到就明确说没有）。"
                            + "★ 它只是换一次手 —— 她随时会被别的动作换走；"
                            + "要她【一直用】那件东西，请下 only_item（同一个值）",
                    Map.of("item", ParamSpec.text("物品 id / 标签 / 类型名，例如 minecraft:iron_sword"),
                            "interrupt", ParamSpec.of("是否先中断当前动作（1=是，0=否）", 1, 0, 1)),
                    null, 0));

    /** 全部指令（顺序稳定；LLM 看到的就是这个顺序）。 */
    public static List<Spec> all() {
        return ALL;
    }

    /** 按 id 查（找不到 ⇒ {@code null}）。 */
    public static Spec find(String id) {
        if (id == null) {
            return null;
        }
        for (Spec s : ALL) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    public static boolean exists(String id) {
        return find(id) != null;
    }

    /** 同一互斥组里的其它 id（用于"下达 A 就自动取消同组的 B"）。 */
    public static List<String> sameMutexGroup(String id) {
        Spec me = find(id);
        if (me == null || me.mutexGroup() == null) {
            return List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Spec s : ALL) {
            if (!s.id().equals(id) && me.mutexGroup().equals(s.mutexGroup())) {
                out.add(s.id());
            }
        }
        return out;
    }

    /**
     * ★★ <b>给 LLM 看的指令表</b>（自动生成 —— 加一条指令不用改提示词）。
     *
     * <p>输出是一段 JSON 数组文本（{@code directives_available} 字段的值）。
     */
    public static String describeForLlm() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Spec s : ALL) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("\n  {")
                    .append("\"id\":\"").append(s.id()).append('"')
                    .append(",\"kind\":\"").append(s.kind() == Kind.INSTANT ? "instant" : "sustained")
                    .append('"')
                    .append(",\"zh\":\"").append(s.zh()).append('"');
            if (s.hasParams()) {
                sb.append(",\"params\":{");
                boolean f2 = true;
                for (Map.Entry<String, ParamSpec> e : s.params().entrySet()) {
                    if (!f2) {
                        sb.append(',');
                    }
                    f2 = false;
                    ParamSpec p = e.getValue();
                    if (p.text()) {
                        sb.append('"').append(e.getKey()).append("\":\"").append(p.zh()).append('"');
                    } else {
                        sb.append('"').append(e.getKey()).append("\":{\"zh\":\"").append(p.zh())
                                .append("\",\"default\":").append(num(p.def()))
                                .append(",\"min\":").append(num(p.min()))
                                .append(",\"max\":").append(num(p.max())).append('}');
                    }
                }
                sb.append('}');
            }
            sb.append(",\"enforce\":\"").append(s.enforce()).append('"');
            if (s.kind() == Kind.SUSTAINED) {
                sb.append(",\"default_ttl_seconds\":").append(s.defTtlTicks() / 20);
            }
            // ★ 告诉模型"下这条之前先看哪些事实"（键名与 scene 字段逐字对应）
            if (!s.needs().isEmpty()) {
                sb.append(",\"base_on\":[");
                boolean f3 = true;
                for (String n : s.needs()) {
                    if (!f3) {
                        sb.append(',');
                    }
                    f3 = false;
                    sb.append('"').append(n).append('"');
                }
                sb.append(']');
            }
            sb.append('}');
        }
        return sb.append("\n]").toString();
    }

    /** 一行中文清单（给玩家/命令用）。 */
    public static String describeForPlayer() {
        StringBuilder sb = new StringBuilder();
        for (Spec s : ALL) {
            sb.append("  ").append(s.kind() == Kind.INSTANT ? "⚡" : "∞").append(' ')
                    .append("§f").append(s.id()).append("§7　").append(s.zh());
            if (s.hasParams()) {
                sb.append("　");
                boolean f = true;
                for (Map.Entry<String, ParamSpec> e : s.params().entrySet()) {
                    if (!f) {
                        sb.append(' ');
                    }
                    f = false;
                    ParamSpec p = e.getValue();
                    if (p.text()) {
                        sb.append(e.getKey()).append("=<文本>");
                    } else {
                        sb.append(e.getKey()).append("=").append(num(p.def()))
                                .append("(").append(num(p.min())).append('~').append(num(p.max()))
                                .append(")");
                    }
                }
            }
            sb.append('\n');
            if (!s.needs().isEmpty()) {
                sb.append("      §8依据：§7").append(String.join(" ", s.needs())).append('\n');
            }
        }
        return sb.toString();
    }

    /** 数字的紧凑写法（1.0 → 1，8.5 → 8.5）。 */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.valueOf(Math.round(v * 100) / 100.0);
    }

    /** 某个指令的参数规格表（副本，外部改不动）。 */
    public static Map<String, ParamSpec> paramsOf(String id) {
        Spec s = find(id);
        return s == null || s.params() == null ? Map.of() : new LinkedHashMap<>(s.params());
    }

    // ───────────────────────── 参数翻译（对话通道用） ─────────────────────────

    /**
     * 翻译结果。
     *
     * @param error ★ 非 {@code null} = 这条指令**用这四个字段表达不了**（原因写在这里，别静默）
     */
    public record Args(Map<String, Double> params, Map<String, String> texts, String error) {

        public static Args bad(String why) {
            return new Args(Map.of(), Map.of(), why);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * ★★ <b>把"一个数字 + 一个文本"翻译成某条指令的参数</b>（M5 的对话通道用；纯逻辑，可离线断言）。
     *
     * <h2>★ 为什么要有这个方法（而不是写在工具类里）</h2>
     * 女仆的对话模型通过一个 {@code ITool} 下指令，而 {@code ITool} 的参数表是**固定字段**的
     * （{@code action / directive / value / text} 四个）。它成立的前提是一条假设：
     * <b>「21 条指令里，每条最多一个数字参数 + 最多一个文本参数」</b>。
     *
     * <p>★ 假设不能只是假设 —— 所以翻译逻辑放在**纯逻辑层**，
     * 由 {@code DirectiveSelfTest} 对**每一条**指令断言"这四个字段够用"。
     * 将来谁加了一条两参数的指令，<b>自测当场失败</b>（而不是在游戏里静默丢掉一个参数）。
     *
     * @param value 数字（{@code null} = 没给 ⇒ 用规格里的默认值）
     * @param text  文本（{@code null}/空白 = 没给）
     */
    public static Args translate(String id, Double value, String text) {
        Spec s = find(id);
        if (s == null) {
            return Args.bad("未知指令 " + id);
        }
        if (!s.hasParams()) {
            return new Args(Map.of(), Map.of(), null);
        }
        // ★★ 第十六轮放宽：**一个数字 + 一个文本**都能用（`only_actions` 要 actions 文本 +
        //   seconds 数字）。四字段通道的容量就是"各一个"，超过它才叫表达不了。
        long numeric = s.params().values().stream().filter(p -> !p.text()).count();
        long textual = s.params().values().stream().filter(ParamSpec::text).count();
        if (numeric > 1 || textual > 1) {
            return Args.bad("指令 " + id + " 需要 " + numeric + " 个数字 + " + textual
                    + " 个文本，对话通道（value/text）表达不了");
        }
        Map<String, Double> params = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, ParamSpec> e : s.params().entrySet()) {
            ParamSpec ps = e.getValue();
            if (ps.text()) {
                if (text != null && !text.isBlank()) {
                    texts.put(e.getKey(), text.trim());
                }
            } else if (value != null && !value.isNaN()) {
                params.put(e.getKey(), value);
            }
        }
        return new Args(Map.copyOf(params), Map.copyOf(texts), null);
    }

    /** 数字参数的个数上限（自测用：证明四字段通道没被撑破）。 */
    public static int maxNumericParamCount() {
        int m = 0;
        for (Spec s : ALL) {
            m = Math.max(m, (int) (s.hasParams() ? s.params().values().stream()
                    .filter(p -> !p.text()).count() : 0));
        }
        return m;
    }

    /** 文本参数的个数上限（自测用）。 */
    public static int maxTextParamCount() {
        int m = 0;
        for (Spec s : ALL) {
            m = Math.max(m, (int) (s.hasParams() ? s.params().values().stream()
                    .filter(ParamSpec::text).count() : 0));
        }
        return m;
    }

    /**
     * ★★ <b>从参数里取 TTL</b>：若这条指令有一个叫 {@code seconds} 的数字参数，就用它当存活时间。
     *
     * <p>为什么要有这条约定（第十六轮）：委托方要的是「**接下来几秒**只进行这些动作」——
     * "几秒"是**时长**，而不是动作的某个属性。与其再发明一套"时长字段"，
     * 不如把它接到**已有的 TTL 机制**上（同一套钳制、同一套过期日志）。
     * ⇒ 只在 {@link #ttlTicksFrom} 这一处解释这个约定（单一出口）。
     *
     * @param fallback 没给 seconds 时用什么（{@code <= 0} = 用规格默认值）
     */
    public static int ttlTicksFrom(String id, Map<String, Double> params, int fallback) {
        if (params == null) {
            return fallback;
        }
        Double seconds = params.get("seconds");
        if (seconds == null) {
            return fallback;
        }
        return (int) Math.round(seconds * 20);
    }

    /** 全部指令里参数最多的那条有几个参数（自测用：证明四字段通道没到极限）。 */
    public static int maxParamCount() {
        int m = 0;
        for (Spec s : ALL) {
            m = Math.max(m, s.hasParams() ? s.params().size() : 0);
        }
        return m;
    }
}
