package com.touhoulittlemad.fightlikeplayer.carrier;

import java.util.List;
import java.util.Set;

/**
 * 载体规则 —— 「物品 → 载体」的映射，从 {@code catalog/carriers.json} 加载。
 *
 * <p>与清单里「动作 → 载体」的 {@code carrier} 字段方向相反，两者合起来才能
 * 从「女仆持有什么」推出「她能做什么」。
 *
 * @param carrier     载体 id（与清单里的 {@code carrier} 对应）
 * @param requiresMod 需要的模组；{@code null} = 无条件
 * @param slots       该载体在哪些槽位有效；{@code always} 规则为空
 * @param matcher     匹配方式
 * @param note        说明（仅供日志/调试）
 * @param preferredRange ★ <b>拿着这类东西时希望站多远</b>（格）。{@code 0} = 未声明 ⇒ 用兜底值。
 *                       这是「<b>步法</b>」的输入（见 {@code decision/GaitSelector}）：
 *                       <b>手里拿什么，就站什么距离</b> —— 与玩家一致（弓手拉开、剑士贴身），
 *                       也是「按手持物分发」在<b>站位</b>维度上的同一条原理。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.3 / §5.10</a>
 */
public record CarrierRule(
        String carrier,
        String requiresMod,
        Set<SlotKind> slots,
        Matcher matcher,
        String note,
        double preferredRange
) {

    /** 未声明站位偏好时的兜底：近战贴身距离。 */
    public static final double DEFAULT_PREFERRED_RANGE = 2.5;

    /** 匹配方式。 */
    public sealed interface Matcher {

        /** 简短描述，用于日志与失败原因。 */
        String describe();

        /** 无物品规则：永远命中（{@code maid:body}）。 */
        record Always() implements Matcher {
            @Override
            public String describe() {
                return "always";
            }
        }

        /** 按注册名匹配。{@code fallback} 可为 null。 */
        record ByItemId(String itemId, Matcher fallback) implements Matcher {
            @Override
            public String describe() {
                return "itemId=" + itemId + (fallback != null ? " | fallback:" + fallback.describe() : "");
            }
        }

        /** 按物品标签匹配（最稳）。 */
        record ByItemTag(String tag) implements Matcher {
            @Override
            public String describe() {
                return "itemTag=" + tag;
            }
        }

        /**
         * 按类型匹配。{@code simpleName=true} 时只比简单名
         * —— 用于抵御附属换包名（学调律师 {@code ApiCompatLayer} 的思路）。
         */
        record ByType(String target, boolean simpleName) implements Matcher {
            @Override
            public String describe() {
                return "instanceof " + target + (simpleName ? " (simpleName)" : "");
            }
        }

        /** 按具名能力匹配（适配器算好的谓词，如 {@code irons:isSpellContainer}）。 */
        record ByCapability(String key) implements Matcher {
            @Override
            public String describe() {
                return "capability=" + key;
            }
        }

        /** 按物品属性匹配（如 {@code ATTACK_DAMAGE}）。 */
        record ByAttribute(String attribute) implements Matcher {
            @Override
            public String describe() {
                return "attribute=" + attribute;
            }
        }

        /**
         * ★ <b>未能查证</b>：规则存在但判据未知。
         *
         * <p><b>它永不命中</b>（fail-closed），并会在 {@link Resolution} 里记成
         * {@link DropReason#CARRIER_RULE_UNRESOLVED} ⇒ <b>缺口是显式的，不是静默失败。</b>
         */
        record Unresolved(String reason) implements Matcher {
            @Override
            public String describe() {
                return "UNRESOLVED: " + reason;
            }
        }
    }

    public CarrierRule {
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier 不能为空");
        }
        if (matcher == null) {
            throw new IllegalArgumentException("matcher 不能为空");
        }
        slots = slots == null ? Set.of() : Set.copyOf(slots);
        preferredRange = preferredRange <= 0 ? DEFAULT_PREFERRED_RANGE : preferredRange;
    }

    /** 是否是"不需要任何物品"的规则。 */
    public boolean isAlways() {
        return matcher instanceof Matcher.Always;
    }

    public boolean isUnresolved() {
        return matcher instanceof Matcher.Unresolved;
    }

    /**
     * 判定一件持有物是否命中本规则。
     * <p>★ 纯字符串比对 —— <b>不含任何 Minecraft 类型</b>。
     */
    public boolean matches(PossessedItem item) {
        if (!slots.isEmpty() && !slots.contains(item.slot())) {
            return false;
        }
        return matchesMatcher(matcher, item);
    }

    /**
     * ★★ <b>载体规则的"专精度"</b> —— 用于"一件物品只由最专的规则提供动作"。
     *
     * <h2>为什么需要它（2026-10-01 实测反馈）</h2>
     * 一把<b>拔刀剑</b>会同时命中两条规则：
     * {@code slashblade:blade}（{@code instanceof ItemSlashBlade}）与
     * {@code maid:weapon}（有 {@code ATTACK_DAMAGE} 属性）。
     * ⇒ 候选集里同时出现"拔刀剑连击"与"通用近战挥击"，两者向量又很接近
     * ⇒ <b>她常常只用通用近战，看起来"手里拿着刀却不使刀"</b>。
     *
     * <p>而玩家的行为不是这样：手上有拔刀剑时，左键走的是<b>刀自己的连击系统</b>
     * （{@code ComboCommands} 路由），<b>不会走"通用近战"那条路</b>；
     * TLM 的 {@code TaskAttack} 同理（拿刀时走 {@code SlashBladeCompat}）。
     *
     * <p>⇒ 规则：<b>同一件物品上，只有专精度最高的那条（或那几条）规则产出动作</b>，
     * 更泛化的规则被抑制。这既修好了拔刀剑，也一劳永逸地避免"专有载体与泛化载体撞车"。
     * ★ 注意<b>同精度不互相抑制</b>：{@code tacz:gun} 与 {@code maid:gun} 都是
     * {@code instanceof IGun} ⇒ 两条都保留（这是对的：它们提供的是不同动作面）。
     *
     * <p>取值（大 = 更专）：
     * {@code itemId 50 > itemTag 40 > javaInstanceOf 30 > capability 25 > itemAttribute 10 > always 0}
     */
    public int specificity() {
        Matcher m = matcher;
        if (m instanceof Matcher.ByItemId) {
            return 50;
        }
        if (m instanceof Matcher.ByItemTag) {
            return 40;
        }
        if (m instanceof Matcher.ByType) {
            return 30;
        }
        if (m instanceof Matcher.ByCapability) {
            return 25;
        }
        if (m instanceof Matcher.ByAttribute) {
            return 10;
        }
        return 0;                       // Always / Unresolved（不针对具体物品）
    }

    private static boolean matchesMatcher(Matcher m, PossessedItem item) {
        if (m instanceof Matcher.Always) {
            return false; // always 规则不针对具体物品，由 resolver 单独处理
        }
        if (m instanceof Matcher.ByItemId byId) {
            if (item.itemId().equals(byId.itemId())) {
                return true;
            }
            return byId.fallback() != null && matchesMatcher(byId.fallback(), item);
        }
        if (m instanceof Matcher.ByItemTag byTag) {
            return item.itemTags().contains(byTag.tag());
        }
        if (m instanceof Matcher.ByType byType) {
            if (item.isType(byType.target())) {
                return true;
            }
            // 允许用简单名匹配全名集合
            return byType.simpleName() && item.typeNames().stream()
                    .anyMatch(n -> n.equals(byType.target()) || n.endsWith("." + byType.target()));
        }
        if (m instanceof Matcher.ByCapability byCap) {
            return item.hasCapability(byCap.key());
        }
        if (m instanceof Matcher.ByAttribute byAttr) {
            return item.hasCapability("attr:" + byAttr.attribute());
        }
        // Unresolved ⇒ 永不命中
        return false;
    }

    /** 供日志用的一行描述。 */
    public String describe() {
        return carrier + " [mod=" + (requiresMod == null ? "-" : requiresMod)
                + ", slots=" + (slots.isEmpty() ? "n/a" : slots) + "] " + matcher.describe();
    }

    /** 便利：构造 always 规则。 */
    public static CarrierRule always(String carrier) {
        return new CarrierRule(carrier, null, Set.of(), new Matcher.Always(), null, 0);
    }

    /** 便利：构造按类型的规则。 */
    public static CarrierRule byType(String carrier, String mod, List<SlotKind> slots, String target) {
        return new CarrierRule(carrier, mod, Set.copyOf(slots), new Matcher.ByType(target, true), null, 0);
    }
}
