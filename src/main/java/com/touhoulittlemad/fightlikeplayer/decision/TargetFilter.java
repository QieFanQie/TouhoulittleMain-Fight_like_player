package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★★ <b>「只打某一类/某一只生物」的判据</b>（纯逻辑，第十五轮，委托方提议的「半命题指令」）。
 *
 * <h2>委托方原话</h2>
 * > 「进一步，我们可以开发：**半命题式指令**，即可以填入物品/怪物 id 的指令
 * > （比如**强制把仇恨目标局限在某种生物上** 或 **强制把仇恨目标局限在某个生物上**）」
 *
 * <h2>★ 三条指令 / 一个判据</h2>
 * <ul>
 *   <li>{@code focus_entity(entity)} —— **只打这一类**（生物注册名，或 {@code #标签}）；</li>
 *   <li>{@code focus_one(entity)} —— **只打这一只**（填它的 UUID，从 {@code scene.battle.target_uuid}
 *       或日志里拿；★ 用 UUID 而不是实体引用：区块卸载后实体引用会悬垂，UUID 不会）；</li>
 *   <li>{@code ban_entity(entity)} —— **这一类不打**（可以与 {@code focus_entity} 叠加）。</li>
 * </ul>
 *
 * <h2>★★ 判据放在最上游（这一条是取证结论，不是选择）</h2>
 * 目标是 TLM 的 {@code StartAttacking.create(hasWeapon, IAttackTask::findFirstValidAttackTarget)}
 * 写的脑记忆 {@code ATTACK_TARGET}（女仆的 {@code getTarget()} 直读它，二者恒等）。
 * 而所有三条路径——寻找目标（finder 的谓词）、开始攻击（{@code StartAttacking} 内的
 * {@code Mob#canAttack}）、放弃目标（{@code StopAttackingIfTargetInvalid}）——
 * <b>都要过 {@code EntityMaid#canAttack}</b>，它又委托给当前任务的
 * {@code IAttackTask#canAttack}。
 * ⇒ <b>在任务的 {@code canAttack} 里加一层过滤，三个位置同时生效，不需要 mixin、不需要事件</b>，
 * 而且"她正在打 A、你让她只打 B"时，A 会在下一 tick 被
 * {@code StopAttackingIfTargetInvalid} **自己**清掉（它的 erase 是无条件的）。
 *
 * <p>★ 本类**不含任何 MC 类型**：生物身份用 {@link EntityRef}（注册名 + 标签 + UUID）表示，
 * 因此匹配规则可以在零 MC 环境里断言。
 */
public final class TargetFilter {

    private TargetFilter() {
    }

    /**
     * 一个"候选目标"的身份（纯逻辑表示）。
     *
     * @param typeId 生物注册名，如 {@code minecraft:zombie}
     * @param tags   实体类型标签（{@code minecraft:raiders} 这种，全部带命名空间）
     * @param uuid   实体 UUID（{@code focus_one} 用；{@code null} = 未知）
     */
    public record EntityRef(String typeId, Set<String> tags, String uuid) {

        public EntityRef {
            typeId = typeId == null ? "" : typeId;
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }

        /** 一行诊断。 */
        public String describe() {
            return typeId.isEmpty() ? "(未知生物)" : typeId;
        }

        /** 这个查询串指的是这类/这只生物吗（判据见 {@link TargetFilter#matches}）。 */
        public boolean matches(String query) {
            return TargetFilter.matches(this, query);
        }
    }

    /**
     * ★ 匹配判据（**唯一一处**，文档与自测都指这里）：
     * <ol>
     *   <li>{@code query} 形如 UUID（含 {@code -} 且长度 36）⇒ 与 {@link EntityRef#uuid} 比较；</li>
     *   <li>{@code query} 以 {@code #} 开头 ⇒ 当作实体**标签**（{@code #minecraft:raiders}）；</li>
     *   <li>否则当作**生物注册名**：全名相等，或**不带命名空间的简写相等**
     *       （{@code zombie} ≡ {@code minecraft:zombie}）；</li>
     *   <li>裸标签名也认（{@code minecraft:raiders} 既可能是类型 id 也可能是标签，两边都比一次）。</li>
     * </ol>
     * ★ 空查询 ⇒ <b>什么都不匹配</b>（fail-closed）：写错的指令不会变成"打所有东西"。
     */
    public static boolean matches(EntityRef ref, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || ref == null) {
            return false;
        }
        if (looksLikeUuid(q)) {
            return q.equalsIgnoreCase(ref.uuid());
        }
        String bare = q.startsWith("#") ? q.substring(1) : q;
        if (ref.tags().contains(q) || ref.tags().contains(bare)) {
            return true;
        }
        String id = ref.typeId();
        if (id.equals(q)) {
            return true;
        }
        return id.contains(":") && id.substring(id.indexOf(':') + 1).equals(bare);
    }

    /** 是不是 UUID 写法（用于区分"某种生物"与"某一只生物"）。 */
    public static boolean looksLikeUuid(String q) {
        return q != null && q.length() == 36 && q.indexOf(':') < 0 && q.chars()
                .filter(c -> c == '-').count() == 4;
    }

    /**
     * ★★ <b>这个目标现在允许打吗</b>。
     *
     * @return {@code null} = 允许；否则是**可读的拒绝原因**（会进日志，答得上"她为什么不打它"）
     */
    public static String reject(DirectiveBus bus, EntityRef target) {
        if (bus == null) {
            return null;
        }
        if (bus.has("focus_one")) {
            String want = bus.textParam("focus_one", "entity");
            if (want != null && !want.isBlank() && !matches(target, want)) {
                return "只打指定的那一只（" + want + "），而它是 " + target.describe();
            }
        }
        if (bus.has("focus_entity")) {
            String want = bus.textParam("focus_entity", "entity");
            if (want != null && !want.isBlank() && !matches(target, want)) {
                return "只打 " + want + "，而它是 " + target.describe();
            }
        }
        if (bus.has("ban_entity")) {
            String ban = bus.textParam("ban_entity", "entity");
            if (ban != null && !ban.isBlank() && matches(target, ban)) {
                return "已指定不打 " + ban;
            }
        }
        return null;
    }

    /** 有没有"目标类"指令在生效（调用方据此决定要不要走这条更贵的路径）。 */
    public static boolean active(DirectiveBus bus) {
        return bus != null
                && (bus.has("focus_one") || bus.has("focus_entity") || bus.has("ban_entity"));
    }

    /** 这台指令现在允许打的生物写法（给日志/诊断用；也可能为空 = 不限制）。 */
    public static Set<String> queries(DirectiveBus bus) {
        Set<String> out = new LinkedHashSet<>();
        if (bus == null) {
            return out;
        }
        for (String id : new String[]{"focus_one", "focus_entity", "ban_entity"}) {
            if (bus.has(id)) {
                String v = bus.textParam(id, "entity");
                if (v != null && !v.isBlank()) {
                    out.add(id + "=" + v);
                }
            }
        }
        return out;
    }
}
