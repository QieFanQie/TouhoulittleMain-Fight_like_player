package com.touhoulittlemad.fightlikeplayer.decision;

import com.touhoulittlemad.fightlikeplayer.carrier.ItemRef;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>指令 → 候选集过滤</b>（M2 的落点 ①，纯逻辑）。
 *
 * <p>把"仅使用魔法攻击""保持距离""不召唤"这类指令翻译成"哪些动作被挡下"。
 * ★ 放在纯逻辑层：过滤规则可离线断言（本项目两次因把判据写在 compat 层而失去离线测试）。
 *
 * <h2>★ 动作分类（为什么它必须是一张显式的表）</h2>
 * 指令说的是<b>手段</b>（魔法/近战/远程/仆从管理/维护），而动作 id 里没有这个字段。
 * ⇒ 本类用**前缀表**分类，并且：
 * <ul>
 *   <li>分类结果对"未知 id"返回 {@link ActionClass#OTHER} —— <b>不猜</b>；</li>
 *   <li>{@code magic_only} 这种"只允许 X"的指令会**挡下 OTHER**（否则它会漏掉很多东西）；</li>
 *   <li>挡下了多少条必须**可见**（调用方把计数写进日志/`/flp whyfull`）——
 *       "候选集被指令清空"是本项目最怕的死法（docs/13 §26）。</li>
 * </ul>
 */
public final class DirectiveFilter {

    private DirectiveFilter() {
    }

    /** 动作的"手段"分类。 */
    public enum ActionClass {
        /** 施法（goety 聚晶 / 铁魔法）。 */
        SPELL,
        /** 近战与拔刀剑。 */
        MELEE,
        /** 远程武器（弓/弩/三叉戟/枪/投掷/弹幕）。 */
        RANGED,
        /** 召唤类（goety 召唤聚晶走 SPELL 的类别判定，这里指"召唤仆从"这一族动作）。 */
        SUMMON,
        /** 仆从管理（召回/姿态/清理/处死）。 */
        SERVANT,
        /** 维护与姿态（换弹、上膛、开镜…）。 */
        MAINTENANCE,
        /** 位移（撤退等）。 */
        MOVE,
        /** 认不出来（★ 不猜）。 */
        OTHER
    }

    /**
     * ★★ <b>施法体系</b>（第二十一轮新增：委托方原话「女仆似乎做不到"指定使用铁魔法"，
     * 对于她而言，铁魔法和巫法是一个类的」）。
     *
     * <p>在动作分类里，{@code goety:cast_focus}（诡厄巫法）与 {@code irons:cast_spell}
     * 等（铁魔法）**同为 {@link ActionClass#SPELL}** ⇒ 此前 `magic_only` 无法区分两者，
     * 委托方要"只用铁魔法"时只能整类放行或整类挡下。
     * ⇒ 现在**在 SPELL 之内再分一层体系**（只对 SPELL 类有意义；其余一律 {@link #NONE}）。
     */
    public enum SpellSystem {
        /** 非法术动作（近战/远程/维护/…）⇒ 体系不适用。 */
        NONE,
        /** 诡厄巫法（Goety）：聚晶 {@code goety:cast_focus}。 */
        GOETY,
        /** 铁魔法（Iron's Spells 'n Spellbooks）：{@code irons:cast_spell/cast_scroll/recast}。 */
        IRONS
    }

    /** 前缀表（顺序无关，按最长前缀优先匹配）。 */
    private static final List<String[]> PREFIX = List.of(
            // 施法
            new String[]{"goety:cast_focus", "SPELL"},
            new String[]{"irons:cast_spell", "SPELL"},
            new String[]{"irons:cast_scroll", "SPELL"},
            new String[]{"irons:recast", "SPELL"},
            // 召唤与仆从管理
            new String[]{"goety:summon", "SUMMON"},
            new String[]{"goety:execute_servant", "SERVANT"},
            new String[]{"goety:recall_servants", "SERVANT"},
            new String[]{"goety:recall_to_saved_coords", "SERVANT"},
            new String[]{"goety:set_servant_stance", "SERVANT"},
            new String[]{"goety:dismiss_temporary_servants", "SERVANT"},
            new String[]{"goety:transfer_servant_ownership", "SERVANT"},
            // 近战
            new String[]{"maid_native:melee_swing", "MELEE"},
            new String[]{"slashblade:", "MELEE"},
            // 远程
            new String[]{"maid_native:bow_shot", "RANGED"},
            new String[]{"maid_native:crossbow_shot", "RANGED"},
            new String[]{"maid_native:trident", "RANGED"},
            new String[]{"maid_native:danmaku", "RANGED"},
            new String[]{"maid_native:throw_any_item", "RANGED"},
            new String[]{"maid_native:gun_shot", "RANGED"},
            // ★★ 枪：近战用枪托算近战；开火/手雷算远程
            new String[]{"tacz:melee", "MELEE"},
            new String[]{"tacz:", "RANGED"},
            // ★★ 维护与姿态（第十七轮枪械审计 B4 修正表）：
            //   ① `tacz:fire_mode` 这条 id 【清单里根本不存在】（真 id 是 `tacz:fire_select`）；
            //   ② 它们原来排在 `tacz:` 之后，而 classify 当时是"第一条 startsWith 命中"（见那里的说明）
            //      ⇒ 全部是死条目；③ 顺手补齐清单里真实存在的另外几条（draw/attachment/inspect/
            //      cancel_reload/use_item_interact/zoom/crawl）。
            new String[]{"tacz:reload", "MAINTENANCE"},
            new String[]{"tacz:cancel_reload", "MAINTENANCE"},
            new String[]{"tacz:bolt", "MAINTENANCE"},
            new String[]{"tacz:draw", "MAINTENANCE"},
            new String[]{"tacz:attachment", "MAINTENANCE"},
            new String[]{"tacz:inspect", "MAINTENANCE"},
            new String[]{"tacz:use_item_interact", "MAINTENANCE"},
            new String[]{"tacz:aim", "MAINTENANCE"},
            new String[]{"tacz:fire_select", "MAINTENANCE"},
            new String[]{"tacz:zoom", "MAINTENANCE"},
            new String[]{"tacz:crawl", "MAINTENANCE"},
            new String[]{"maid_native:quick_charge", "MAINTENANCE"},
            new String[]{"maid_native:shield_block", "MAINTENANCE"},
            // 位移
            new String[]{"fight_like_player:disengage", "MOVE"},
            new String[]{"fight_like_player:retreat", "MOVE"});

    /**
     * 分类一个动作 id —— ★ <b>按最长前缀优先</b>。
     *
     * <p>★★ 第十七轮（枪械审计 B4）：本方法的注释从第一天就写着"最长前缀优先"，
     * 而实现是"**第一条** {@code startsWith} 命中" ⇒ 表里 `tacz:` 这条 5 字符的前缀
     * 把后面所有 `tacz:reload` / `tacz:aim` / …（11~24 字符）**全部吃掉**
     * ⇒ 那四条维护类登记**从来没有生效过**（它们被判成 RANGED）。
     * 当时没有实害（这些动作 {@code role != choice}，不进候选集），但这正是
     * "注释里写的口径"与"代码里的口径"不一致的第 N 次 ⇒ 现在让实现与注释一致。
     */
    public static ActionClass classify(String actionId) {
        if (actionId == null) {
            return ActionClass.OTHER;
        }
        String best = null;
        ActionClass hit = ActionClass.OTHER;
        for (String[] row : PREFIX) {
            String p = row[0];
            if (actionId.startsWith(p) && (best == null || p.length() > best.length())) {
                best = p;
                hit = ActionClass.valueOf(row[1]);
            }
        }
        return hit;
    }

    /**
     * ★ 动作 id → 施法体系（第二十一轮）。
     *
     * <p>★ 与 {@link #classify} 同一条纪律：**不猜** —— 非 SPELL 类一律 {@link SpellSystem#NONE}，
     * SPELL 类里认不出体系的也给 {@code NONE}（⇒ 在"只用铁魔法"下会被挡下，
     * 这是**保守**一侧：宁可让一条不认识的施法动作暂时不可用，也不让它冒充铁魔法）。
     */
    public static SpellSystem spellSystemOf(String actionId) {
        if (actionId == null || classify(actionId) != ActionClass.SPELL) {
            return SpellSystem.NONE;
        }
        if (actionId.startsWith("goety:")) {
            return SpellSystem.GOETY;
        }
        if (actionId.startsWith("irons:")) {
            return SpellSystem.IRONS;
        }
        return SpellSystem.NONE;
    }

    /**
     * ★ 当前指令是否限制了施法体系（{@code goety_only} / {@code irons_only}）。
     */
    public static boolean hasSpellSystemDirective(DirectiveBus bus) {
        return bus != null && (bus.has("goety_only") || bus.has("irons_only"));
    }

    /**
     * ★ 这个体系在当前指令下允许吗（没有体系指令 ⇒ 全部允许）。
     */
    public static boolean spellSystemAllowed(DirectiveBus bus, SpellSystem system) {
        if (bus == null) {
            return true;
        }
        if (bus.has("goety_only") && system != SpellSystem.GOETY) {
            return false;
        }
        if (bus.has("irons_only") && system != SpellSystem.IRONS) {
            return false;
        }
        return true;
    }

    /**
     * ★ <b>这条动作在当前指令下允许吗</b>。
     *
     * @param bus       指令总线（{@code null} = 没有指令 ⇒ 全部允许）
     * @param actionId  动作 id
     * @return {@code true} = 允许
     */
    public static boolean allowed(DirectiveBus bus, String actionId) {
        if (bus == null) {
            return true;
        }
        ActionClass c = classify(actionId);

        // ── 手段三选一（互斥组 means 已保证最多一条生效，这里只做"只允许 X"）──
        boolean meansRestricted = false;
        if (bus.has("magic_only")) {
            meansRestricted = true;
            if (c != ActionClass.SPELL) {
                return false;
            }
        }
        if (bus.has("melee_only")) {
            meansRestricted = true;
            if (c != ActionClass.MELEE) {
                return false;
            }
        }
        if (bus.has("ranged_only")) {
            meansRestricted = true;
            if (c != ActionClass.RANGED) {
                return false;
            }
        }
        // ── ★★ 只用某一颗法术/聚晶（第二十七轮，委托方实测：她想"仅用先锋聚晶施法"）──
        //   ★ 口径与 `magic_only` 一致：说"只用这一颗"就是"只放这一颗法术"
        //     ⇒ 非施法动作（换弹/管仆从/近战）也一并挡下，否则她会嘴上只用聚晶、手上却在砍人。
        //   ★ **标签**那一层的过滤在 {@link #focusAllowed}（挑选池）：
        //     `cast_focus` / `cast_spell` 都是**族动作**（动作级看不出是哪一颗法术），
        //     具体放哪一颗由执行期按需求向量挑 —— 这与 `ban_focus` 完全同一条通路。
        if (bus.has("only_focus")) {
            meansRestricted = true;
            if (c != ActionClass.SPELL) {
                return false;
            }
        }
        // ── 施法体系细分（第二十一轮）：`goety_only` / `irons_only` ──
        //   ★ 口径**刻意与 `magic_only` 一致**：既然"仅使用魔法攻击"会挡下非法术动作，
        //     "只用铁魔法"也必须挡下它们 —— 否则她会在"只用铁魔法"下跑去换弹、管仆从。
        //   ★ 三条体系指令同属互斥组 means ⇒ 总类（magic_only）与细分（*_only）不会并存，
        //     所以这里不必讨论"两条同时生效"的组合语义（真并存时下方按"都要满足"处理，
        //     结果是两条都不放行 = 保守一侧）。
        if (hasSpellSystemDirective(bus)) {
            meansRestricted = true;
            SpellSystem sys = spellSystemOf(actionId);
            if (c != ActionClass.SPELL) {
                return false;
            }
            if (!spellSystemAllowed(bus, sys)) {
                return false;
            }
        }
        if (meansRestricted && (c == ActionClass.OTHER || c == ActionClass.MAINTENANCE
                || c == ActionClass.MOVE || c == ActionClass.SERVANT)) {
            // ★ "只允许 X"时，维护/位移/仆从管理/认不出来的动作**也**挡下 ——
            //   否则"仅使用魔法攻击"下她照样会去换弹、撤退、管仆从。
            //   ★ 例外：位移类在"仅远程/仅魔法"下其实是必要的（风筝），但它由【步法】负责，
            //     不是动作 —— 所以这里挡下 MOVE 动作是对的（步法不受指令过滤影响）。
            return false;
        }

        // ── 不召唤 ──
        if (bus.has("no_summons")) {
            if (c == ActionClass.SUMMON || c == ActionClass.SERVANT) {
                return false;
            }
            if ("goety:cast_focus".equals(actionId)) {
                return true;                    // 聚晶层面的"不召唤"由挑选池负责（见 GoetyFocusOps）
            }
        }

        // ── 站位约束：贴身类动作在"保持距离"下禁用；远程在"贴身"下禁用 ──
        if (bus.has("keep_distance") && c == ActionClass.MELEE) {
            return false;
        }
        if (bus.has("close_in") && c == ActionClass.RANGED) {
            return false;
        }
        return true;
    }

    /**
     * ★ 过滤一个候选列表，并把"被挡下的 id"写进 {@code droppedInto}（可读出口）。
     */
    public static List<String> filter(DirectiveBus bus, List<String> actionIds,
                                      List<String> droppedInto) {
        if (bus == null || bus.activeCount() == 0) {
            return actionIds;
        }
        List<String> out = new ArrayList<>(actionIds.size());
        for (String id : actionIds) {
            if (allowed(bus, id)) {
                out.add(id);
            } else if (droppedInto != null) {
                droppedInto.add(id);
            }
        }
        return out;
    }

    // ───────────────────────── 物品类指令（半命题：only_item / ban_item）─────────────────────────

    /**
     * ★★ <b>「只用某件物品」/「禁用某件物品」的判据</b>（纯逻辑，第十五轮）。
     *
     * <h2>委托方原话</h2>
     * > 「可以加一条专门限制使用某种物品（物品 id）的指令。进一步……**半命题式指令**，
     * > 即可以填入物品/怪物 id 的指令」
     *
     * <h2>★ 语义（写在这里，因为"只用剑"这三个字有歧义）</h2>
     * <ul>
     *   <li><b>只用 X</b>：① 只保留**由 X 承载**的动作（动作当初是靠 X 才可用的）；
     *       ② **不依赖物品**的动作（通用挥击、"投掷任意物品"这类 always 载体的）
     *       只在**主手正好是 X** 时才保留。<br>
     *       ★ ② 那条正是委托方实测到的那件事：下了「只用近战」她却**用法杖敲人**
     *       （因为通用挥击不依赖武器，主手是什么就打什么）。有了 ②，「只用剑」才会
     *       先把剑换到手上、再挥 —— 而不会拿法杖敲。</li>
     *   <li><b>禁用 X</b>：① 排除由 X 承载的动作；② 若 **X 正在主手**，
     *       不依赖物品的动作也挡下（"别用法杖敲人"），
     *       ★★ 但**只有在挡下之后还有别的动作可做时**才真的挡 ——
     *       否则她就"什么都做不了"（本项目的头号禁令：不许出现空候选集导致的静默卡死）。
     *       这一条由调用方分两趟调用来实现（见 {@code PlayerLikeCombat#applyDirectives}）。</li>
     * </ul>
     *
     * @param bus                 指令总线（{@code null} = 没有指令）
     * @param carrier             让这个动作可用的那件物品；{@code null} = 该动作不依赖物品
     * @param mainHand            她主手的物品（{@code null} = 空手）
     * @param blockMainHandWielder ★ 第二趟用：是否启用「主手是禁用物 ⇒ 不依赖物品的动作也挡下」
     * @return {@code null} = 允许；否则是一句**可读的挡下原因**
     */
    public static String itemGate(DirectiveBus bus, ItemRef carrier, ItemRef mainHand,
                                  boolean blockMainHandWielder) {
        if (bus == null) {
            return null;
        }
        // ① 只用某件物品
        if (bus.has("only_item")) {
            String want = bus.textParam("only_item", "item");
            if (want != null && !want.isBlank()) {
                if (carrier != null) {
                    if (!carrier.matches(want)) {
                        return "只用 " + want + "（这个动作靠 " + carrier.describe() + " 承载）";
                    }
                } else if (mainHand == null || !mainHand.matches(want)) {
                    return "只用 " + want + "（这个动作不依赖物品，但主手是 "
                            + (mainHand == null ? "空手" : mainHand.describe()) + "）";
                }
            }
        }
        // ② 禁用某件物品
        if (bus.has("ban_item")) {
            String ban = bus.textParam("ban_item", "item");
            if (ban != null && !ban.isBlank()) {
                if (carrier != null && carrier.matches(ban)) {
                    return "已禁用 " + ban;
                }
                if (carrier == null && blockMainHandWielder && mainHand != null
                        && mainHand.matches(ban)) {
                    return "已禁用 " + ban + "（它正在主手 —— 先换掉再打）";
                }
            }
        }
        return null;
    }

    /**
     * ★★ <b>"这条物品指令已经拿不到东西了，该不该自动解除"</b>（第十七轮续，纯逻辑）。
     *
     * <p>背景（委托方实测）：清空背包之后，上一场的 `only_item(minecraft:netherite_sword)`
     * 还挂着 —— 它处于 {@link ItemStatus#ABSENT} ⇒ **不过滤候选集**（怕清空）
     * ⇒「只用 X」静默变成空操作，同时让指令伺服每 tick 白试一次。
     * ⇒ 连续 {@code graceTicks} 都拿不到 ⇒ 调用方自动解除它（留痕 + 说明）。
     *
     * @param absentSince 第一次发现"她身上没有"的世界时间（{@code 0} = 还没发现过）
     * @param now         现在
     * @param graceTicks  宽限（tick）；{@code <= 0} ⇒ 立刻算过期
     */
    public static boolean staleItemDirective(long absentSince, long now, int graceTicks) {
        return absentSince > 0 && now - absentSince >= Math.max(1, graceTicks);
    }

    /** 有没有"物品类"指令在生效（调用方据此决定要不要走那条更贵的两趟路径）。 */
    public static boolean hasItemDirective(DirectiveBus bus) {
        return bus != null && (bus.has("only_item") || bus.has("ban_item"));
    }

    // ───────────────────────── 物品类指令的"整表决策"（第十六轮修死锁）─────────────────────────

    /** 物品类过滤的结论 —— ★ 调用方靠它决定"要不要替她把东西拿到手上"。 */
    public enum ItemStatus {
        /** 没有物品类指令。 */
        NONE,
        /** 正常：过滤生效，动作集非空。 */
        OK,
        /** ★ 她要的那件东西**在她身上、但不在主手** ⇒ 调用方应该把它换到手上。 */
        NEED_HAND,
        /** ★ 她要的那件东西**她身上根本没有** ⇒ 这条指令无法生效（★ 必须回退，不许清空候选集）。 */
        ABSENT
    }

    /**
     * @param allowed ★ <b>永远不会为空</b>（除非传进来的 {@code ids} 本来就空）——
     *                这是本方法的**核心不变量**
     * @param why     可读原因（日志/诊断）
     */
    public record ItemPlan(List<String> allowed, ItemStatus status, String want, String why) {
    }

    /**
     * ★★ <b>物品类指令的整表决策</b>（纯逻辑）。
     *
     * <h2>★★ 为什么必须整表决定（第十六轮的真 bug）</h2>
     * 上一版是"逐个动作问 {@link #itemGate}"，于是出现了一条**死锁**：
     * <pre>
     *   她下 only_item(minecraft:iron_sword)，而剑在**背包里**、主手是法杖
     *     ⇒ 由剑承载的动作：一条都没有（清单里没有"由剑承载"的动作）
     *     ⇒ 不依赖物品的动作：主手不是剑 ⇒ 也全被挡
     *     ⇒ 候选集 = 空 ⇒ 决策层没有动作可选 ⇒ 她【站着不动】
     *     ⇒ 因为没选中任何动作，**换手前置永远不会被触发** ⇒ 剑永远到不了手上
     *     ⇒ 死锁（观感就是"这条指令完全不奏效"，而且一条日志都没有）
     * </pre>
     * ⇒ 判据：**"这条过滤会不会把候选集清空？清空了怎么办？"**
     * 清空**从来不是**可接受的中间状态（docs/13 第 26 条）。这里的规则：
     * <ol>
     *   <li>过滤结果为空 ⇒ **回退成不过滤**（她照常行动），原因写进 {@link ItemPlan#why()}；</li>
     *   <li>同时给出 {@link ItemStatus}，让调用方去做真正该做的事
     *       —— <b>把那件东西换到主手上</b>（下一 tick 过滤自然生效）。</li>
     * </ol>
     *
     * @param ids            已经过其它过滤的候选 id
     * @param carrierOf      actionId → 让它可用的那件物品（{@code null} = 不依赖物品）
     * @param mainHand       主手物品（{@code null} = 空手）
     * @param wantInInventory ★ 她要的东西**在她身上任何位置**是否存在（纯逻辑层查不到，由调用方喂）
     */
    public static ItemPlan itemPlan(DirectiveBus bus, List<String> ids,
                                    java.util.function.Function<String, ItemRef> carrierOf,
                                    ItemRef mainHand, boolean wantInInventory) {
        if (!hasItemDirective(bus)) {
            return new ItemPlan(ids, ItemStatus.NONE, "", "");
        }
        String want = bus.has("only_item") ? bus.textParam("only_item", "item")
                : bus.textParam("ban_item", "item");
        if (want == null || want.isBlank()) {
            return new ItemPlan(ids, ItemStatus.NONE, "", "指令没带物品参数 ⇒ 不生效");
        }
        // 第一趟：保守规则（"主手是禁用物时的随手挥击"留到第二趟）
        List<String> pass1 = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (itemGate(bus, carrierOf == null ? null : carrierOf.apply(id), mainHand, false)
                    == null) {
                pass1.add(id);
            }
        }
        // 第二趟：连"主手是禁用物时的随手挥击"也挡 —— ★ 只在挡完还有得做时才算
        List<String> pass2 = new ArrayList<>(pass1.size());
        for (String id : pass1) {
            if (itemGate(bus, carrierOf == null ? null : carrierOf.apply(id), mainHand, true)
                    == null) {
                pass2.add(id);
            }
        }
        List<String> result = pass2.isEmpty() ? pass1 : pass2;

        if (bus.has("only_item")) {
            // ★★ 第十七轮续：**这两条"主手不匹配就回退成不过滤"的特例已删除**。
            //
            //   它们让 `only_item` 在"她要的东西在背包里"时**变成空操作**
            //   —— 而那正是最常见的状态（她手上通常握着另一把枪/武器）
            //   ⇒ 委托方实测的「**only_item 完全不起效**」就是它。
            //
            //   当年写回退是为了防"过滤清空候选集 ⇒ 她站着不动"的死锁（第 68 条）：
            //   那时唯一的换手时机是"选中动作之后的换手前置"，候选集空了就永远换不了手。
            //   ★ 现在有**指令伺服**（`DirectiveHolder#itemServo`，挂 MaidTickEvent，
            //     与战斗无关、每 tick 都跑）⇒ 换手不再依赖候选集 ⇒ 死锁不复存在。
            //
            //   ⇒ 新规则只有两条（比原来少两条特例）：
            //     ① 东西在她身上 ⇒ **一律按 only_item 过滤**（不在主手 ⇒ NEED_HAND，请调用方/伺服换手）；
            //     ② 东西她身上没有 ⇒ 回退 + 大声说明（真正的"做不到"，保留）。
            if (!wantInInventory) {
                return new ItemPlan(ids, ItemStatus.ABSENT, want,
                        "「只用 " + want + "」：她身上没有这件东西 ⇒ 这段指令不会有任何效果（她照常打）");
            }
            boolean mainMatches = mainHand != null && mainHand.matches(want);
            if (mainMatches && result.isEmpty()) {
                // ★★ 第十七轮续：**护栏不许变成发呆**。
                //   她要的东西已经在手上，却一个动作都过滤不出来（例如「只用石头」）
                //   ⇒ 这时换手帮不了她（东西已在手上）⇒ 必须回退 + 如实说明，
                //     否则就是"拿着东西发呆"。
                return new ItemPlan(ids, ItemStatus.ABSENT, want,
                        "「只用 " + want + "」：它在她手上，但没有任何动作能用它 "
                                + "⇒ 这条指令不会有任何效果（她照常打）");
            }
            if (!mainMatches) {
                return new ItemPlan(result, ItemStatus.NEED_HAND, want,
                        "「只用 " + want + "」：它在她身上但不在主手 ⇒ 正在换到手上"
                                + "（换好之前她可能一两拍不动）");
            }
            return new ItemPlan(result, ItemStatus.OK, want, "");
        }
        if (result.isEmpty()) {
            // ★ 禁用类：挡完没得做 ⇒ 回退（与上一版的护栏一致）
            return new ItemPlan(ids, ItemStatus.ABSENT, want,
                    "「禁用 " + want + "」：挡完就没动作可做了 ⇒ 这条暂时不生效（她照常打）");
        }
        return new ItemPlan(result, ItemStatus.OK, want, "");
    }

    // ───────────────────────── 动作白名单（only_actions）─────────────────────────

    /**
     * ★★ <b>「接下来一段时间只做这些动作」</b>（第十六轮，委托方提议）。
     *
     * <p>委托方原话：「（动作有 id 吗？有的话，可以进一步写：**接下来几秒只进行这些动作**）」
     * ⇒ **有 id**：清单里每条动作都有自己的 id（如 {@code slashblade:slash_art}、
     * {@code goety:cast_focus}），女仆的对话模型能在上下文里看到（见 {@code FlpMaidContexts}）。
     *
     * <p>★ 判据只回答"这个 id 在不在白名单里"；**清空候选集的处理留给调用方**
     * （{@link #actionWhitelistFilter} 返回空表 = "这条现在做不了"，调用方必须回退）。
     */
    public static boolean inActionWhitelist(DirectiveBus bus, String actionId) {
        if (bus == null || !bus.has("only_actions")) {
            return true;
        }
        String list = bus.textParam("only_actions", "actions");
        if (list == null || list.isBlank()) {
            return true;                       // 没写清单 ⇒ 这条不生效（fail-open，不许因此清空）
        }
        for (String one : list.split("[,，;\\s]+")) {
            if (!one.isBlank() && one.trim().equals(actionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★ 整表级应用白名单（返回空表 = "这条指令现在一个动作也匹配不上"）。
     *
     * @param droppedInto 被白名单挡下的 id（可读出口）
     */
    public static List<String> actionWhitelistFilter(DirectiveBus bus, List<String> ids,
                                                     List<String> droppedInto) {
        if (bus == null || !bus.has("only_actions")) {
            return ids;
        }
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (inActionWhitelist(bus, id)) {
                out.add(id);
            } else if (droppedInto != null) {
                droppedInto.add(id);
            }
        }
        return out;
    }

    /** 这条指令现在想让她只做哪些动作（诊断/日志用）。 */
    public static List<String> actionWhitelist(DirectiveBus bus) {
        if (bus == null || !bus.has("only_actions")) {
            return List.of();
        }
        String list = bus.textParam("only_actions", "actions");
        if (list == null || list.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String one : list.split("[,，;\\s]+")) {
            if (!one.isBlank()) {
                out.add(one.trim());
            }
        }
        return out;
    }

    /**
     * ★★ <b>这条聚晶的类别在当前指令下允许吗</b>（落点 ④ 用；纯逻辑，只吃类别名与标签名）。
     *
     * @param category 聚晶类别名（如 {@code ATTACK_SINGLE} / {@code SUMMON}）
     * @param label    法术标签（类名），用于 {@code ban_focus}
     */
    public static boolean focusAllowed(DirectiveBus bus, String category, String label) {
        if (bus == null) {
            return true;
        }
        // ★ 第十五轮：物品类指令**不在这里**处理 ——
        //   `only_item`/`ban_item` 的参数是**物品 id**，而这里的 label 是**法术类名**，
        //   两个命名空间不能混着比（比中了是巧合，比不中才是常态，而且会给出错误的解释）。
        //   "法术必须由某根杖放出来"这件事由**动作的载体**负责（`itemGate` 看 carrier）。
        if (bus.has("no_summons") && "SUMMON".equals(category)) {
            return false;
        }
        if (bus.has("focus_category")) {
            Double want = bus.param("focus_category", "category");
            if (want != null) {
                int idx = categoryIndex(category);
                if (idx >= 0 && idx != (int) Math.round(want)) {
                    return false;
                }
            }
        }
        if (bus.has("ban_focus")) {
            String banned = bus.textParam("ban_focus", "label");
            if (banned != null && banned.equals(label)) {
                return false;
            }
        }
        // ★★ 第二十七轮：**只用某一颗**（与 ban_focus 同一个参数口径）。
        //   ★ 比较是**精确**的（与别名匹配那条纪律一致：别名的宽松匹配会让"只用先锋"命中别的聚晶）。
        //   ★ 认不出标签时（模型编了一个不存在的 label）⇒ 这里会挡下所有法术
        //     ⇒ 候选集变空 ⇒ 她会不打；这正是 `DirectiveHolder#feasibility` 在**下达那一刻**
        //     必须把"她没有这颗法术 + 她实际有哪些 label"说回去的原因（否则就是静默卡死）。
        if (bus.has("only_focus")) {
            String only = bus.textParam("only_focus", "label");
            if (only != null && !only.isBlank() && !only.equals(label)) {
                return false;
            }
        }
        return true;
    }

    /**
     * ★★ <b>带施法体系的版本</b>（第二十一轮）：{@code goety_only`/`irons_only} 也要管**法术池**。
     *
     * <p>为什么必须有这个重载：动作级过滤（{@link #allowed}）只挡下 `cast_focus` 这条**动作**，
     * 而"她有哪些法术可用"这件事是**另一条路**（Goety 聚晶池 / 铁魔法法术表）
     * ⇒ 池子照样把巫法法术喂进她的上下文，她就会以为"我还能放巫法"，
     * 于是出现委托方已经见过的那类现象：**指令说了不许，她自己不知道为什么手不动**。
     * ⇒ 让池子与动作**同一口径**（本项目的"审计口径 == 放行口径"纪律）。
     *
     * @param system 调用方**明确知道自己喂的是哪一系的法术**时传入；不确定就传
     *               {@link SpellSystem#NONE}（此时该重载 == 三参数版，绝不误伤）
     */
    public static boolean focusAllowed(DirectiveBus bus, SpellSystem system,
                                       String category, String label) {
        // ★ 只有调用方**明确报出体系**时才判体系：NONE 是"我不知道/不适用"，
        //   若拿 NONE 去比 goety_only 会把它当成"非巫法"全部挡下（误伤）。
        if (system != SpellSystem.NONE && !spellSystemAllowed(bus, system)) {
            return false;
        }
        return focusAllowed(bus, category, label);
    }

    /**
     * 类别名 → 序号（与 {@code DirectiveSpec} 里 {@code focus_category} 的说明一致）。
     *
     * <p>★★ 第 14 轮核实补上的**粗类别别名**（见 {@link #categoryIndex} 的对话）：
     * 铁魔法只有三档意图（{@code ATTACK/SUPPORT/SUMMON}），没有 Goety 那样八档细类别。
     * 这里按<b>最接近的细类别</b>归并，★ <b>归并方向永远是"偏宽松"</b>
     * （宁可多放行一个法术，也不要因为分类不准而把她整个封死 —— docs/13 第 22/25 条）：
     * <ul>
     *   <li>{@code SUMMON} → {@code SUMMON}（2，精确，没有歧义）；</li>
     *   <li>{@code ATTACK} → {@code ATTACK_SINGLE}（0）—— ISS 的攻击法术分不出单体/范围，
     *       ⇒ 记作单体：`focus_category=0` 会放行它们（它们确实能打单个目标），
     *       而 `focus_category=1`（仅范围）**不会**选中它们（不冒充范围系）；</li>
     *   <li>{@code SUPPORT} → {@code HEALING}（5）—— 支援里治疗占多数；
     *       ⇒ 要求"仅治疗"时护盾/buff 也会被放行（宽松一侧）。</li>
     * </ul>
     */
    public static int categoryIndex(String category) {
        if (category == null) {
            return -1;
        }
        return switch (category) {
            case "ATTACK_SINGLE" -> 0;
            case "ATTACK_AREA" -> 1;
            case "SUMMON" -> 2;
            case "UTILITY" -> 3;
            case "PROTECTION" -> 4;
            case "HEALING" -> 5;
            case "CONTROL" -> 6;
            case "SERVANT_MGMT" -> 7;
            // ★ 粗类别别名（铁魔法的三档意图）
            case "ATTACK" -> 0;
            case "SUPPORT" -> 5;
            // SUMMON 已在上方（两个平台同名，落在同一档）
            default -> -1;
        };
    }

    /**
     * ★★ <b>步法约束</b>（落点 ③，纯逻辑部分）。
     *
     * <p>为什么"站位"要由指令约束而不是塞进动作空间：走位在本项目里早就是**并发伺服**
     * （步法，见 docs/09 §5.8）—— 指令是对这个伺服的**上层约束**（"别贴太近"是持续意图），
     * 而不是"再做一次位移动作"。
     *
     * @param active      有没有站位类指令
     * @param minDistance 目标距离不得小于它（{@code keep_distance}；0 = 不限）
     * @param maxDistance 目标距离不得超过它（{@code close_in}；0 = 不限）
     * @param noRetreat   禁止后退（{@code no_retreat}）
     * @param holdRadius  守点半径（{@code hold_position}；0 = 不限）★ 锚点由游戏层给
     */
    public record GaitConstraint(boolean active, double minDistance, double maxDistance,
                                 boolean noRetreat, double holdRadius) {

        public static GaitConstraint none() {
            return new GaitConstraint(false, 0, 0, false, 0);
        }

        public boolean holds() {
            return holdRadius > 0;
        }
    }

    /** 从指令总线提取步法约束（纯逻辑）。 */
    public static GaitConstraint gaitConstraint(DirectiveBus bus) {
        if (bus == null) {
            return GaitConstraint.none();
        }
        Double min = bus.has("keep_distance") ? bus.param("keep_distance", "min") : null;
        Double max = bus.has("close_in") ? bus.param("close_in", "max") : null;
        Double radius = bus.has("hold_position") ? bus.param("hold_position", "radius") : null;
        boolean noRetreat = bus.has("no_retreat");
        return new GaitConstraint(
                min != null || max != null || radius != null || noRetreat,
                min == null ? 0 : min,
                max == null ? 0 : max,
                noRetreat,
                radius == null ? 0 : radius);
    }
}
