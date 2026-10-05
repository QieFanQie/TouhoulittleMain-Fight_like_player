package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>指令冲突时"谁让位"</b>（第二十八轮，委托方实测的"枪械用不出来"）。
 *
 * <h2>委托方那次的现象与证据（日志原文）</h2>
 * <pre>
 *   close_in  ← llm ：已下达持续指令 close_in（贴身缠斗）max=3      ← 动态指挥官下的
 *   melee_only← llm ：已下达持续指令 melee_only
 *   only_item ← chat：已下达持续指令 only_item  item=tacz:uzi      ← 主人下的
 *   [directive] main 被指令挡下：tacz:shoot, maid_native:bow_shot, maid_native:trident …
 *   [main] → fight_like_player:disengage   ← **唯一活着的候选是"撤退"**
 * </pre>
 * `close_in` 按设计禁用**远程**类，`only_item` 只留"由她指定的那件承载"的动作
 * ⇒ 两条**各自都合理**的指令，交集是空的（这不是任何一条错了，而是**组合**没人管）。
 *
 * <h2>★★ 判据（本项目的头号禁令在"组合层"的漏网）</h2>
 * "绝不允许候选集变成空的"这条纪律，此前只做在**单条指令内部**
 * （物品 ABSENT 回退、白名单看门狗、"过滤为空就回退"）。而**跨指令组合**没人管
 * ⇒ 空集换个地方出现（这里表现为"只剩下撤退"）。
 *
 * <h2>★ 让位规则（写在这里，因为它必须可离线断言）</h2>
 * <ol>
 *   <li><b>主人的话优先</b>：{@code player} / {@code chat}（她自己对话，说话的人就是主人）
 *       <b>永远不让位</b>给 {@code llm}（动态指挥官 —— 它看不到主人说了什么）；</li>
 *   <li>让位顺序：先撤 {@code llm} 的**站位约束**（{@code close_in}/{@code keep_distance}/
 *       {@code hold_position}/{@code no_retreat}）—— 它最不"表达主人的意思"；
 *       再撤 {@code llm} 的**手段限制**（{@code melee_only}/{@code ranged_only}/{@code magic_only}/
 *       {@code goety_only}/{@code irons_only}/{@code only_focus}）；</li>
 *   <li>主人的"指名道姓"（{@code only_item}/{@code ban_item}/{@code only_focus}/{@code ban_focus}）
 *       一律不让位 —— 那是主人最具体的意图；</li>
 *   <li>若只剩主人的指令在互相打架 ⇒ <b>不自动撤任何一条</b>（回来只报告，让主人自己决定）：
 *       自动撤销主人的指令比"她只会撤退"更糟。</li>
 * </ol>
 */
public final class ConflictResolver {

    private ConflictResolver() {
    }

    /** 站位约束类（最该为别人的意思让位）。 */
    private static final Set<String> STANCE = Set.of(
            "close_in", "keep_distance", "hold_position", "no_retreat");

    /** 手段限制类（比站位"更像主人的意思"，所以后让位）。 */
    private static final Set<String> MEANS = Set.of(
            "melee_only", "ranged_only", "magic_only", "goety_only", "irons_only", "only_focus");

    /** 主人的"指名道姓"—— 永不让位。 */
    private static final Set<String> OWNER_SPECIFIC = Set.of(
            "only_item", "ban_item", "only_focus", "ban_focus", "focus_one", "focus_entity",
            "ban_entity", "only_actions");

    /** 来源优先级：越小越"该赢"。 */
    public static int sourceRank(String source) {
        if (source == null) {
            return 2;
        }
        String s = source.toLowerCase(Locale.ROOT);
        if ("player".equals(s) || "chat".equals(s)) {
            return 0;                       // 主人的话
        }
        if ("llm".equals(s)) {
            return 1;                       // 动态指挥官（看不到主人说了什么）
        }
        return 2;                           // 自动（看门狗/脱战清理等）
    }

    /**
     * 冲突已经发生（候选集里一个"攻击类"都不剩）⇒ 该撤哪一条。
     *
     * @param actives 当前生效的**持续**指令：id → 来源（{@code player}/{@code chat}/{@code llm}/…）
     * @return 该撤销的指令 id；{@code null} = 没有可以自动撤的（只报告）
     */
    public static String pickYielding(Map<String, String> actives) {
        if (actives == null || actives.isEmpty()) {
            return null;
        }
        String bestStance = null;
        String bestMeans = null;
        int bestStanceRank = Integer.MAX_VALUE;
        int bestMeansRank = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : actives.entrySet()) {
            String id = e.getKey();
            if (OWNER_SPECIFIC.contains(id)) {
                continue;                   // 主人的指名道姓 ⇒ 不动
            }
            int rank = sourceRank(e.getValue());
            if (rank == 0) {
                continue;                   // 主人自己的指令 ⇒ 不自动撤（见类注释第 4 条）
            }
            if (STANCE.contains(id) && rank < bestStanceRank) {
                bestStance = id;
                bestStanceRank = rank;
            } else if (MEANS.contains(id) && rank < bestMeansRank) {
                bestMeans = id;
                bestMeansRank = rank;
            }
        }
        return bestStance != null ? bestStance : bestMeans;
    }

    /** 这条指令是不是"让位类"（站位/手段）—— 诊断与自测用。 */
    public static boolean isYieldingClass(String id) {
        return STANCE.contains(id) || MEANS.contains(id);
    }

    /** 这条指令是不是"主人的指名道姓"（永不让位）。 */
    public static boolean isOwnerSpecific(String id) {
        return OWNER_SPECIFIC.contains(id);
    }

    /** 该让位的那一类的中文说法（回话用：「撤掉自动指挥的**贴身**指令」）。 */
    public static String describeClass(String id) {
        if (STANCE.contains(id)) {
            return "站位约束";
        }
        if (MEANS.contains(id)) {
            return "手段限制";
        }
        return "指令";
    }

    /** 全部会让位的 id（自测用）。 */
    public static List<String> yieldingIds() {
        List<String> out = new java.util.ArrayList<>(STANCE);
        out.addAll(MEANS);
        return List.copyOf(out);
    }
}
