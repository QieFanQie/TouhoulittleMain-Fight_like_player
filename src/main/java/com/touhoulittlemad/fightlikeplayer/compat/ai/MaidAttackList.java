package com.touhoulittlemad.fightlikeplayer.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.DefaultMonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitTaskData;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ★★ <b>每女仆的"友好 / 中立 / 敌对"名单</b>（第十六轮，委托方第 2 条）。
 *
 * <h2>委托方原话</h2>
 * > 「考虑到女仆现在已经能获得周围是否有怪物，可否为女仆 llm 添加『**获得怪物信息**，
 * > 并在**模式设置**中编辑/添加/删除**生物友好/中立/敌对**』的能力？」
 *
 * <h2>★★ 这套能力 TLM 本来就有，我们只是把它接出来</h2>
 * TLM 的数据结构是每女仆一份 {@link AttackListData}
 * （{@code Map<实体类型, MonsterType{FRIENDLY, NEUTRAL, HOSTILE}>}），
 * 存在**任务数据**里（随存档保存），并且有自己的图形编辑器
 * （我们已经在 0.12.0 把那个界面路由回原版攻击任务了）。
 * <ul>
 *   <li><b>玩家侧</b>：任务配置界面（已可用）；</li>
 *   <li><b>模型侧</b>：本类 —— 读、写、以及"**附近有什么怪、各自算什么档**"。</li>
 * </ul>
 * ★ 判据不另造一套：写进的就是 TLM 的那份数据，所以 TLM 的选目标逻辑（
 * {@code IAttackTask#canAttack} → {@link DefaultMonsterType} / 本名单）**立刻生效**，
 * 我们的任务覆写 {@code canAttack} 时也是先问它（`super`）。
 *
 * <h2>★ 三个档位是什么意思（照 TLM 语义）</h2>
 * <ul>
 *   <li>{@code HOSTILE} —— 见着就打；</li>
 *   <li>{@code NEUTRAL} —— 只有它先打过我/主人，我才还手；</li>
 *   <li>{@code FRIENDLY} —— 永不打（当同伴看）。</li>
 * </ul>
 */
public final class MaidAttackList {

    private MaidAttackList() {
    }

    /** 读她现在的自定义名单（没有 ⇒ 空表）。 */
    public static Map<ResourceLocation, MonsterType> groups(EntityMaid maid) {
        AttackListData data = maid.getData(InitTaskData.ATTACK_LIST);
        return data == null || data.attackGroups() == null ? Map.of() : data.attackGroups();
    }

    /**
     * ★ 某个生物**现在**算哪一档：先看她自己的名单，没有再看 TLM 的默认判定。
     *
     * @return 一档；取不到默认值时返回 {@code null}
     */
    public static MonsterType classify(EntityMaid maid, LivingEntity target) {
        if (target == null) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        MonsterType custom = id == null ? null : groups(maid).get(id);
        if (custom != null) {
            return custom;
        }
        try {
            return DefaultMonsterType.getMonsterType(target);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 一行中文摘要（给 prompt 用；只列**自定义**的那几条，没自定义就明说）。 */
    public static String describeRules(EntityMaid maid) {
        var g = groups(maid);
        if (g.isEmpty()) {
            return "没有自定义名单（一切按默认判定：敌对=见着就打，中立=被打才还手，友好=不打）";
        }
        List<String> out = new ArrayList<>();
        for (var e : g.entrySet()) {
            out.add(e.getKey() + "=" + zh(e.getValue()));
        }
        return String.join("、", out);
    }

    /** 附近怪物的实况（"获得怪物信息"的那一半）。 */
    public static String describeNearby(EntityMaid maid, double radius) {
        List<String> out = new ArrayList<>();
        for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class,
                maid.getBoundingBox().inflate(radius))) {
            if (e == maid || !e.isAlive() || e == maid.getOwner()) {
                continue;
            }
            if (!(e instanceof Mob) && !(e instanceof net.minecraft.world.entity.monster.Enemy)) {
                continue;                        // 只报"可能是怪"的：怪物类，或有 AI 的生物
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            MonsterType t = classify(maid, e);
            boolean custom = id != null && groups(maid).containsKey(id);
            out.add(String.format(Locale.ROOT, "%s（%s%s，%.1f 格）",
                    id == null ? "?" : id, t == null ? "未知" : zh(t),
                    custom ? "·自定义" : "", Math.sqrt(maid.distanceToSqr(e))));
            if (out.size() >= 12) {
                break;
            }
        }
        return out.isEmpty() ? "附近没有可打的目标" : String.join("、", out);
    }

    /**
     * ★★ <b>设置某个生物类型的档位</b>（写进 TLM 的那份数据 ⇒ 立刻生效）。
     *
     * @param query 实体类型 id（{@code minecraft:zombie}）或**简写**（{@code zombie}）
     * @param type  {@code FRIENDLY} / {@code NEUTRAL} / {@code HOSTILE}
     * @return 可读结果（成功/失败原因都写清）
     */
    public static String set(EntityMaid maid, String query, String type) {
        ResourceLocation id = resolveType(query);
        if (id == null) {
            return "没有这种生物：" + query + "（要写实体类型 id，例如 minecraft:zombie）";
        }
        MonsterType t = parseType(type);
        if (t == null) {
            return "档位只认 FRIENDLY / NEUTRAL / HOSTILE（收到的是 " + type + "）";
        }
        Map<ResourceLocation, MonsterType> next = new LinkedHashMap<>(groups(maid));
        MonsterType old = next.put(id, t);
        write(maid, next);
        FightLikePlayer.LOGGER.info("[FLP][attacklist] {} → {}（原 {}）", id, t, old);
        return "已把 " + id + " 设为「" + zh(t) + "」"
                + (old == null ? "" : "（原来是「" + zh(old) + "」）");
    }

    /** 删掉某条自定义（回到默认判定）。 */
    public static String remove(EntityMaid maid, String query) {
        ResourceLocation id = resolveType(query);
        if (id == null) {
            return "没有这种生物：" + query;
        }
        Map<ResourceLocation, MonsterType> next = new LinkedHashMap<>(groups(maid));
        if (next.remove(id) == null) {
            return id + " 本来就没有自定义（当前按默认判定）";
        }
        write(maid, next);
        return "已删掉 " + id + " 的自定义 ⇒ 以后按默认判定";
    }

    /** 清空全部自定义。 */
    public static String clear(EntityMaid maid) {
        int n = groups(maid).size();
        write(maid, Map.of());
        return "已清空自定义名单（" + n + " 条）⇒ 全部回到默认判定";
    }

    private static void write(EntityMaid maid, Map<ResourceLocation, MonsterType> map) {
        AttackListData data = new AttackListData(Map.copyOf(map));
        if (map.isEmpty()) {
            maid.setData(InitTaskData.ATTACK_LIST, AttackListData.empty());
        } else {
            maid.setData(InitTaskData.ATTACK_LIST, data);
        }
    }

    /** 把查询串解析成实体类型 id（支持简写；也支持直接给一个存在的实体类型名）。 */
    private static ResourceLocation resolveType(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();
        ResourceLocation direct = ResourceLocation.tryParse(q);
        if (direct != null && BuiltInRegistries.ENTITY_TYPE.containsKey(direct)) {
            return direct;
        }
        String bare = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            if (id != null && (id.getPath().equals(bare) || id.getPath().equals(q))) {
                return id;
            }
        }
        return null;
    }

    private static MonsterType parseType(String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "FRIENDLY", "友好", "友方" -> MonsterType.FRIENDLY;
            case "NEUTRAL", "中立" -> MonsterType.NEUTRAL;
            case "HOSTILE", "敌对", "敌意" -> MonsterType.HOSTILE;
            default -> null;
        };
    }

    /** 档位的中文名。 */
    public static String zh(MonsterType t) {
        if (t == null) {
            return "未知";
        }
        return switch (t) {
            case FRIENDLY -> "友好（永不打）";
            case NEUTRAL -> "中立（被打才还手）";
            case HOSTILE -> "敌对（见着就打）";
        };
    }
}
