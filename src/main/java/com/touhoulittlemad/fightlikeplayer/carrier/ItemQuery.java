package com.touhoulittlemad.fightlikeplayer.carrier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★★ <b>「物品查询串」的解析器</b> —— 把 {@code only_item} / {@code ban_item} /
 * {@code switch_item} 里那串**人写的东西**落到"她身上具体是哪一件"（纯逻辑，零 MC 类型）。
 *
 * <h2>★★ 为什么必须有它（委托方 2026-10-04 实测：「我不管让她使用什么，都用的火箭筒」）</h2>
 * 复核链（三条，缺一条都会让那条指令"看起来没生效"）：
 * <ol>
 *   <li><b>查询串必须真的匹配到一件东西</b>。旧判据（{@link ItemRef#matchesOne}）要求
 *       <b>逐字符相等</b>：注册名 / 简写 / 标签 / 类型名 / 能力名，任一相等才命中。
 *       ⇒ 模型只要写错一点点（{@code AK47} 的大小写、漏了 {@code tacz:}、
 *       {@code rpg} 而真实 id 是 {@code tacz:rpg7}）就**一个都不匹配**；</li>
 *   <li>不匹配 ⇒ {@code itemPlan} 走 <b>ABSENT</b> 回退（"她照常打"，这是不许清空候选集的护栏）
 *       ⇒ 她**继续用手上那把枪**（火箭筒）；</li>
 *   <li>而且那时**没有任何回话告诉上层模型"你那个 id 是错的"** ⇒ 它以为指令生效了
 *       （这一半已由 {@code DirectiveHolder#feasibility} 补上）。</li>
 * </ol>
 * ⇒ 本类把"匹配"从**相等**放宽成**分级**，并把"含糊"显式化：
 * <pre>
 *   FULL  注册名全名逐字符相等        tacz:ak47
 *   EXACT 标签/类型名/能力名相等       IGun · #forge:tools/swords · maid:weapon
 *   BARE  简写（不带命名空间）/ 大小写不同  ak47 · TACZ:AK47 · igun
 *   LOOSE 子串包含（≥3 字符）         rpg（⇒ tacz:rpg7）· sword（⇒ minecraft:iron_sword）
 *   NONE  都不匹配
 * </pre>
 *
 * <h2>★ 一处定义，两处消费（不许再写第二份判据）</h2>
 * {@link ItemRef#matches(String)} 现在是 {@code ItemQuery.strength(...) != NONE} 的薄封装
 * ⇒ 「决策层过滤看到的」与「换手/提醒看到的」永远是同一套判据
 * （本项目吃过"两处口径不一致"的亏，见 docs/13 第 38 条）。
 *
 * <p>★ 含糊（同一个强度命中多件）**不静默**：{@link Resolution#ambiguous()} 为真时，
 * 调用方要么报出来、要么按"手上的优先"取第一件 —— 但绝不能假装只有一件。
 * 这也是为什么"宽松匹配"没有变成灾难：它只会**多报**，不会**乱换**。
 */
public final class ItemQuery {

    private ItemQuery() {
    }

    /** 匹配强度（越大越具体）。 */
    public enum Match {
        /** 完全不匹配。 */
        NONE,
        /** 后缀/包含命中（最宽松）—— 例如 {@code rpg} 命中 {@code tacz:rpg7}。 */
        LOOSE,
        /** 简写或大小写不同 —— 例如 {@code ak47} 命中 {@code tacz:ak47}。 */
        BARE,
        /** 标签/类型名/能力名相等。 */
        EXACT,
        /** 注册名全名逐字符相等（最具体）。 */
        FULL
    }

    /** 一次命中：{@code index} = 它在传入列表里的下标（用于取回原始对象）。 */
    public record Hit(int index, ItemRef item, Match match, String part) {
    }

    /**
     * 一次解析的结果。
     *
     * @param query 原始查询串
     * @param hits  命中项，**已按强度降序、同强度按原下标升序**（{@link #best()} 就是第一个）
     */
    public record Resolution(String query, List<Hit> hits) {

        public boolean isEmpty() {
            return hits.isEmpty();
        }

        /** 最强的那一件（无命中 ⇒ {@code null}）。 */
        public Hit best() {
            return hits.isEmpty() ? null : hits.get(0);
        }

        /** ★ 命中多件**且最强强度并列** ⇒ 含糊（调用方应当说清，而不是假装只有一件）。 */
        public boolean ambiguous() {
            return hits.size() > 1 && hits.get(1).match() == hits.get(0).match();
        }

        /** 命中项的注册名（按强度降序，去重）。 */
        public List<String> ids() {
            Set<String> out = new LinkedHashSet<>();
            for (Hit h : hits) {
                out.add(h.item().id());
            }
            return new ArrayList<>(out);
        }

        /** 一行诊断：{@code tacz:rpg7(LOOSE) ← rpg}。 */
        public String describe() {
            if (isEmpty()) {
                return "「" + query + "」⇒ 没有匹配";
            }
            StringBuilder sb = new StringBuilder();
            sb.append('「').append(query).append("」⇒ ");
            int n = 0;
            for (Hit h : hits) {
                if (n++ > 0) {
                    sb.append(" / ");
                }
                sb.append(h.item().id()).append('(').append(h.match()).append(')');
            }
            if (ambiguous()) {
                sb.append("　★ 含糊：最强强度命中多件 ⇒ 建议写全 id");
            }
            return sb.toString();
        }
    }

    /** 一个物品身上"可以写进指令"的东西（注册名简写 + 标签 + 类型名 + 能力名）。 */
    public static Match strength(ItemRef item, String query) {
        if (item == null || query == null || query.isBlank()) {
            return Match.NONE;
        }
        Match best = Match.NONE;
        for (String one : query.split("[,，;；\\s]+")) {
            Match m = strengthOne(item, one.trim());
            if (m.ordinal() > best.ordinal()) {
                best = m;
            }
            if (best == Match.FULL) {
                break;
            }
        }
        return best;
    }

    /** 单个查询项（不含分隔符）的强度 —— 判据顺序见类注释。 */
    public static Match strengthOne(ItemRef item, String part) {
        if (item == null || part == null || part.isEmpty()) {
            return Match.NONE;
        }
        String p = part;
        String lower = p.toLowerCase(Locale.ROOT);
        // ① 注册名全名
        if (item.id().equals(p)) {
            return Match.FULL;
        }
        // ② 标签 / 类型名 / 能力名（含带 # 的标签写法）
        String bare = p.startsWith("#") ? p.substring(1) : p;
        if (item.tags().contains(bare) || item.typeNames().contains(p)
                || item.capabilities().contains(bare)) {
            return Match.EXACT;
        }
        // ③ 简写 / 大小写不同
        if (!bare.isEmpty() && bareId(item.id()).equalsIgnoreCase(bare)) {
            return Match.BARE;
        }
        if (item.id().equalsIgnoreCase(p)) {
            return Match.BARE;
        }
        for (String t : item.typeNames()) {
            if (t.equalsIgnoreCase(p)) {
                return Match.BARE;
            }
        }
        // ★★ 别名（第十七轮续）：枪型 `mg`、「机枪」、「RPG-7 火箭筒」这类
        //   "模型/玩家说出来的另一种写法"。它们**不是**注册名，但必须能匹配 ——
        //   否则「只用机枪」永远落不到任何一把枪上（委托方实测的正是这一条）。
        //   ★★ 只做**精确/大小写不敏感**，**不做子串** —— 这条是自测当场抓出来的：
        //      `mg` 是 `smg` 的子串 ⇒ 子串匹配会让「只用机枪」连冲锋枪一起命中，
        //      而"让她只用机枪却还在用冲锋枪"**正是委托方报的那句话**。
        //      判据：**别名是我们对这个世界的定义（枪型键/枪型中文名/人读名）⇒ 必须精确；
        //      只有 id / 类型名这些"外部字符串"才允许宽松。**
        for (String a : item.aliases()) {
            if (a.equals(p)) {
                return Match.EXACT;
            }
            if (a.equalsIgnoreCase(p)) {
                return Match.BARE;
            }
        }
        // ④ 子串包含（★ 至少 3 个字符，避免 "a" 命中一切）
        //   ★ 为什么必须是"子串"而不是"后缀"：委托方嘴里那句是「火箭筒」，
        //     而真实 id 是 tacz:rpg7 —— 查询串常常是 id 的**前半段**（rpg / ak / rocket…），
        //     只做后缀匹配等于没放宽。含糊由调用方显式报出（见 Resolution#ambiguous）。
        //   ★ 门槛用 **2**（而不是 3）：中文枪型名只有两个字（「机枪」「冲锋枪」是三个，
        //     但「手枪」是两个字），按 3 会把它们全挡掉。
        if (lower.length() >= 2) {
            String bareIdLower = bareId(item.id()).toLowerCase(Locale.ROOT);
            if (bareIdLower.contains(lower) || item.id().toLowerCase(Locale.ROOT).contains(lower)) {
                return Match.LOOSE;
            }
            for (String t : item.typeNames()) {
                if (t.toLowerCase(Locale.ROOT).contains(lower)) {
                    return Match.LOOSE;
                }
            }
            for (String t : item.tags()) {
                if (t.toLowerCase(Locale.ROOT).contains(lower)) {
                    return Match.LOOSE;
                }
            }
            for (String t : item.capabilities()) {
                if (t.toLowerCase(Locale.ROOT).contains(lower)) {
                    return Match.LOOSE;
                }
            }
        }
        return Match.NONE;
    }

    /**
     * 在**她持有的一串物品**里解析这个查询串。
     *
     * @param query 指令里填的字符串（可为空 ⇒ 空结果）
     * @param items 顺序**有意义**：约定"越靠前优先级越高"（游戏侧喂进来时主手在最前）
     */
    public static Resolution resolve(String query, List<ItemRef> items) {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.isBlank() || items == null || items.isEmpty()) {
            return new Resolution(query == null ? "" : query, hits);
        }
        for (int i = 0; i < items.size(); i++) {
            ItemRef it = items.get(i);
            if (it == null) {
                continue;
            }
            Match m = strength(it, query);
            if (m != Match.NONE) {
                hits.add(new Hit(i, it, m, query.trim()));
            }
        }
        // ★ 稳定：强度降序，同强度保持原顺序（= 她身上的自然顺序，主手优先）
        hits.sort((a, b) -> Integer.compare(b.match().ordinal(), a.match().ordinal()));
        return new Resolution(query.trim(), hits);
    }

    /** 去掉命名空间的那一半（{@code tacz:ak47} → {@code ak47}）。 */
    private static String bareId(String id) {
        int i = id.indexOf(':');
        return i >= 0 && i + 1 < id.length() ? id.substring(i + 1) : id;
    }
}
