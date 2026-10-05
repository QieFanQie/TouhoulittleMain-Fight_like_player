package com.touhoulittlemad.fightlikeplayer.carrier;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★★ <b>一件物品的「可匹配身份」</b> —— 纯逻辑（无 MC 类型），供**指令**里的
 * 「只用某件物品 / 禁用某件物品」用（docs/15 的「半命题指令」）。
 *
 * <h2>委托方原话（第十五轮）</h2>
 * > 「既然 llm 已经可以获得背包里可用物品列表了（可以吗？），那可以加一条
 * > 专门限制使用某种物品（物品 id）的指令。进一步，我们可以开发：**半命题式指令**，
 * > 即可以填入物品/怪物 id 的指令」
 *
 * <h2>★ 匹配判据（<b>一处定义</b>，不许在别处再写一份）</h2>
 * 指令里那个字符串（{@code only_item} / {@code ban_item} 的 {@code item}）按以下顺序尝试匹配，
 * <b>任一命中即算匹配</b>：
 * <ol>
 *   <li>**注册名精确相等**：{@code minecraft:iron_sword}、{@code goety:wand}；</li>
 *   <li>★★ <b>（第十七轮新增）宽松档</b>：简写 {@code ak47}、大小写不同 {@code AK47}、
 *       后缀 {@code rpg}（⇒ {@code tacz:rpg7}）—— 见 {@link ItemQuery} 的分级表。
 *       <b>委托方实测的"不管让她用什么都是火箭筒"就死在这一档上</b>：
 *       旧判据要求逐字符相等，模型写错一点就一个都不匹配，而"不匹配"的后果是
 *       <b>静默回退</b>（她照常用手上那把枪），且没人告诉模型它的 id 是错的；</li>
 *   <li>**物品标签**：写成 {@code #forge:tools/swords} 或裸 {@code forge:tools/swords}
 *       （{@link PossessedItem#itemTags} 里存的是标签全名）；</li>
 *   <li>**类型名**：{@code SwordItem}、{@code IGun}、{@code IWand} 这类
 *       （{@link PossessedItem#typeNames}，与 {@code carriers.json} 的 {@code javaInstanceOf} 同口径）；</li>
 *   <li>**能力名**：{@code maid:weapon} 这类（{@link PossessedItem#capabilities}）。</li>
 * </ol>
 * ★ 也接受**没有命名空间的简写**（例如 {@code iron_sword}）—— 与 `minecraft:iron_sword` 的后半段相等即算命中。
 * 这是为了让模型/玩家少写错；代价是理论上可能撞名（例如两个模组各有一个 {@code wand}），
 * 所以**日志里永远打全名**（"她把 minecraft:iron_sword 当成了 X"）。
 *
 * <p>★ 为什么要做一个专门的类型而不是直接用 {@link PossessedItem}：
 * 判断需要的东西只有"注册名 + 标签 + 类型名 + 能力名"这四样，
 * 抽出来之后<b>纯逻辑层可以在零 MC 环境里断言匹配规则</b>（本项目两次踩过
 * {@code NoClassDefFoundError} 之后立的规矩，见 docs/13 §29）。
 */
public record ItemRef(String id, Set<String> tags, Set<String> typeNames, Set<String> capabilities,
                      java.util.Set<String> aliases) {

    /** 兼容构造（旧调用点四参即可）。 */
    public ItemRef(String id, Set<String> tags, Set<String> typeNames, Set<String> capabilities) {
        this(id, tags, typeNames, capabilities, Set.of());
    }

    public ItemRef {
        id = id == null ? "" : id;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        typeNames = typeNames == null ? Set.of() : Set.copyOf(typeNames);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }

    /** 从一个"她持有的物品"抽出来（★ 别名也带上：枪型 / 枪型中文名）。 */
    public static ItemRef of(PossessedItem item) {
        return item == null ? null
                : new ItemRef(item.itemId(), item.itemTags(), item.typeNames(),
                        item.capabilities(), item.aliases());
    }

    /** 一行诊断（日志/命令用；**永远打全名**）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(id.isEmpty() ? "(无名)" : id);
        if (!typeNames.isEmpty()) {
            sb.append('[').append(String.join("/", typeNames)).append(']');
        }
        return sb.toString();
    }

    /**
     * 这个查询串指的**就是**这件物品吗？
     *
     * <p>★★ 第十六轮（委托方问「only_item 可以填不止一个参数吗？（比如"只用法术、弓箭"）」）：
     * <b>可以</b> —— 用逗号/顿号/分号/空格分隔多个查询，**命中任意一个即算匹配**。
     * ★ 这一处是所有物品查询的**唯一匹配入口** ⇒ {@code only_item} / {@code ban_item} /
     * {@code switch_item} **一起**获得这个能力，不会出现"有的指令支持、有的不支持"。
     *
     * @param query 指令里填的字符串（可含多个，逗号分隔）；{@code null}/空白 ⇒ **不匹配任何东西**（fail-closed）
     */
    public boolean matches(String query) {
        // ★★ 第十七轮：判据**搬到 {@link ItemQuery}**（分级匹配 + 含糊显式化），这里只做转发。
        //   ★ 为什么必须转发而不是各写一份：本项目吃过"两处口径不一致"的亏（docs/13 第 38 条）——
        //     一旦"过滤时说不匹配、换手时又说匹配"，观感就是"这条指令时灵时不灵"。
        return ItemQuery.strength(this, query) != ItemQuery.Match.NONE;
    }

    /** 便于测试/日志的简短形式。 */
    @Override
    public String toString() {
        return describe();
    }

    /** 这件物品身上所有"可以用在指令里"的写法（给 LLM 的提示列表用）。 */
    public Set<String> aliases() {
        Set<String> out = new LinkedHashSet<>();
        if (!id.isEmpty()) {
            out.add(id);
            if (id.contains(":")) {
                out.add(id.substring(id.indexOf(':') + 1));
            }
        }
        out.addAll(aliases);
        out.addAll(tags);
        out.addAll(typeNames);
        out.addAll(capabilities);
        return out;
    }
}
