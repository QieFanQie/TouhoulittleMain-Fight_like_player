package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.touhoulittlemad.fightlikeplayer.decision.TargetFilter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★ <b>把"活的实体"翻译成 {@link TargetFilter.EntityRef}</b>（游戏侧适配器）。
 *
 * <p>纯逻辑层的目标判据只认字符串（注册名 / 标签 / UUID）——
 * 与 {@code MaidSnapshot} 把物品翻译成 {@code PossessedItem} 是同一个套路：
 * <b>游戏类型留在 compat 层，判据留在 decision 层</b>（这样匹配规则能离线断言）。
 */
public final class TargetRefs {

    private TargetRefs() {
    }

    /** 取它的身份（{@code null} 实体 ⇒ {@code null}）。 */
    public static TargetFilter.EntityRef of(Entity e) {
        if (e == null) {
            return null;
        }
        return new TargetFilter.EntityRef(typeId(e.getType()), tagsOf(e.getType()),
                e.getUUID().toString());
    }

    /** 生物注册名（取不到 ⇒ 空串，判据里视为"认不出来"）。 */
    public static String typeId(EntityType<?> type) {
        if (type == null) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? "" : id.toString();
    }

    /**
     * 实体类型的**数据标签**（{@code #minecraft:raiders} 这类）。
     * ★ 取不到就是空集（fail-visible：命令/日志里能看到"标签为空"）。
     */
    public static Set<String> tagsOf(EntityType<?> type) {
        Set<String> out = new LinkedHashSet<>();
        if (type == null) {
            return out;
        }
        try {
            type.builtInRegistryHolder().tags().forEach(t -> {
                ResourceLocation loc = t.location();
                if (loc != null) {
                    out.add(loc.toString());
                }
            });
        } catch (RuntimeException | LinkageError ignore) {
            // 数据包未加载/标签查询不可用 ⇒ 空集（判据退化为"只按注册名匹配"）
        }
        return out;
    }
}
