package com.touhoulittlemad.fightlikeplayer.carrier;

import java.util.Map;
import java.util.Set;

/**
 * 载体解析所需的<b>世界状态事实</b> —— 脱离 Minecraft 的上下文。
 *
 * <p>游戏侧适配器负责把 {@code EntityMaid} 的当前状态翻译成这个记录；
 * 离线自测直接构造它。⇒ 解析逻辑可以在纯 JVM 里断言。
 *
 * @param hasTarget          是否有攻击目标
 * @param targetInMeleeRange 目标是否在近战距离内
 * @param servantCount       当前仆从数（召唤位判定）
 * @param onGround           是否在地面
 * @param sneaking           是否潜行（★ 注意 TLM 的 {@code isCrouching()} 覆写，见 docs/05 §5.2）
 * @param moving             是否在移动
 * @param resources          资源量（"耀魂值"、"法力"、"弹药数" ……）
 * @param customFacts        ★ 具名自定义事实 —— 用来喂 {@code custom} 类前置条件
 * @param activeExclusive    已被占用的互斥组 → 占用数
 * @param coolingDown        正在冷却的动作 id
 * @param modsLoaded         已加载的模组集合（{@code sourceMod} 剪枝 + {@code requiresMod} 门）
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.4</a>
 */
public record ContextFacts(
        boolean hasTarget,
        boolean targetInMeleeRange,
        int servantCount,
        boolean onGround,
        boolean sneaking,
        boolean moving,
        Map<String, Double> resources,
        Map<String, Boolean> customFacts,
        Map<String, Integer> activeExclusive,
        Set<String> coolingDown,
        Set<String> modsLoaded,
        /** ★ 量化体征 —— 态势偏置（{@link ContextBias}）与契合度乘数（{@link FitnessCalculator}）的输入。 */
        Vitals vitals
) {

    /**
     * 量化体征。
     *
     * @param selfHealthPct     自身血量百分比 0~1
     * @param ownerHealthPct    主人血量百分比 0~1（无主人为 1）
     * @param nearbyEnemies     附近敌人数
     * @param distanceToTarget  与目标距离（格）；无目标时给一个很大的值
     */
    public record Vitals(double selfHealthPct, double ownerHealthPct,
                         int nearbyEnemies, double distanceToTarget) {

        public static Vitals unknown() {
            // ★ 缺省视为"满血、无敌人、无目标" ⇒ 偏置全 0 ⇒ 死区 ⇒ 默认动作。
            //   这是【保守】的缺省：宁可什么都不做，也不要基于假数据乱打。
            return new Vitals(1.0, 1.0, 0, Double.MAX_VALUE);
        }

        public static Vitals of(double selfPct, double ownerPct, int enemies, double dist) {
            return new Vitals(selfPct, ownerPct, enemies, dist);
        }
    }

    /** 兼容构造：不带体征（旧调用点无需改动）。 */
    public ContextFacts(boolean hasTarget, boolean targetInMeleeRange, int servantCount,
                        boolean onGround, boolean sneaking, boolean moving,
                        Map<String, Double> resources, Map<String, Boolean> customFacts,
                        Map<String, Integer> activeExclusive, Set<String> coolingDown,
                        Set<String> modsLoaded) {
        this(hasTarget, targetInMeleeRange, servantCount, onGround, sneaking, moving,
                resources, customFacts, activeExclusive, coolingDown, modsLoaded, Vitals.unknown());
    }

    public ContextFacts {
        resources = resources == null ? Map.of() : Map.copyOf(resources);
        customFacts = customFacts == null ? Map.of() : Map.copyOf(customFacts);
        activeExclusive = activeExclusive == null ? Map.of() : Map.copyOf(activeExclusive);
        coolingDown = coolingDown == null ? Set.of() : Set.copyOf(coolingDown);
        modsLoaded = modsLoaded == null ? Set.of() : Set.copyOf(modsLoaded);
        vitals = vitals == null ? Vitals.unknown() : vitals;
    }

    /** 带上体征的副本。 */
    public ContextFacts withVitals(Vitals v) {
        return new ContextFacts(hasTarget, targetInMeleeRange, servantCount, onGround, sneaking,
                moving, resources, customFacts, activeExclusive, coolingDown, modsLoaded, v);
    }

    /** 全部模组都装（自测便利）。 */
    public ContextFacts withAllMods(Set<String> mods) {
        return new ContextFacts(hasTarget, targetInMeleeRange, servantCount, onGround, sneaking,
                moving, resources, customFacts, activeExclusive, coolingDown, mods);
    }

    public boolean modLoaded(String mod) {
        return mod == null || modsLoaded.contains(mod);
    }

    public double resource(String key) {
        return resources.getOrDefault(key, 0.0);
    }

    /**
     * 取自定义事实。
     * <p>★ <b>缺省为 false（fail-closed）</b> —— 清单的 schema 自己就警告过
     * "缺省意味着随时可用，属危险默认"。⇒ 没被显式喂进来的 custom 条件一律判不满足，
     * 并记成 {@link DropReason#PRECONDITION_CUSTOM_UNRESOLVED}，而不是静默放行。
     */
    public boolean customFact(String key) {
        return customFacts.getOrDefault(key, Boolean.FALSE);
    }

    public boolean hasCustomFact(String key) {
        return customFacts.containsKey(key);
    }

    /** 一个"什么都没有"的空上下文（自测地基）。 */
    public static ContextFacts empty() {
        return new ContextFacts(false, false, 0, true, false, false,
                Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
    }

    /** 便利构造器：从空上下文出发改个别字段。 */
    public ContextFacts with(boolean hasTarget, boolean hasServant) {
        return new ContextFacts(hasTarget, targetInMeleeRange, hasServant ? Math.max(1, servantCount) : 0,
                onGround, sneaking, moving, resources, customFacts, activeExclusive, coolingDown, modsLoaded);
    }
}
