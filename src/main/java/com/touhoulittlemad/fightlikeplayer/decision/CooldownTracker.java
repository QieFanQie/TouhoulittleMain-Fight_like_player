package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * <b>冷却账本</b> —— "哪些动作现在还在冷却里"（纯逻辑，零 Minecraft 依赖）。
 *
 * <h2>★ 为什么它必须独立成类（2026-09-30）</h2>
 * 承诺时长（{@code commitTicks}）只回答"这一次做完没有"。它对<b>瞬时动作</b>
 * （施放瞬发法术、开一枪）给不出任何节奏 ⇒ 决策循环每 {@code decisionInterval}(5 tick)
 * 就会重新选中同一招 ⇒ <b>4 次/秒刷屏</b>（S0 实测的「执行处疯狂执行」）。
 *
 * <p>⇒ 引入"最小重复间隔"：{@code now − lastUsed < cooldownOf(id)} ⇒ 仍在冷却。
 * 冷却集合喂进 {@code ContextFacts.coolingDown}，走<b>既有的</b>
 * {@link com.touhoulittlemad.fightlikeplayer.carrier.DropReason#ON_COOLDOWN} 通路
 * —— 不新增过滤机制，因此也能被离线断言。
 *
 * <h2>为什么把它放在纯逻辑层</h2>
 * 这段逻辑<b>必须被游戏侧和回放自测共用</b>。曾经它只写在游戏侧
 * （{@code PlayerLikeCombat} 私有的一个小方法），结果是<b>回放自测里没有冷却</b>
 * ⇒ 自测看到"同一个动作被连续执行 200 次"这种行为，而游戏里其实被冷却挡住了
 * —— 测试与游戏行为不一致，正是"离线全绿、游戏里不对"的温床。
 * ⇒ 现在两侧共用本类。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.3</a>
 */
public final class CooldownTracker {

    /** 动作 id → 上次使用的时刻（游戏 tick）。 */
    private final Map<String, Long> lastUsed = new HashMap<>();

    /** 记录一次使用。 */
    public void markUsed(String actionId, long now) {
        if (actionId != null) {
            lastUsed.put(actionId, now);
        }
    }

    /** 上次使用时刻；从未用过返回 {@code Long.MIN_VALUE}。 */
    public long lastUsedAt(String actionId) {
        return lastUsed.getOrDefault(actionId, Long.MIN_VALUE);
    }

    /**
     * ★★ <b>实际生效的最小重复间隔</b> —— 数据冷却 × 节奏旋钮 × <b>连败退避</b>。
     *
     * <h2>为什么放在纯逻辑层</h2>
     * <b>游戏侧与回放自测必须用同一份实现</b>（这是本类存在的原始理由）。
     * 这条规则原来只写在 {@code PlayerLikeCombat} 里 ⇒ 自测的 {@code GameLoop} 用的是
     * "裸冷却"，于是 <b>{@code cooldown.scale} 在自测里完全不生效</b>
     * —— 自测会得出"缩短冷却没用"的错误结论（实际发生过一次）。
     *
     * @param base            数据里的冷却（{@code ActionSpec#effectiveCooldownTicks}）
     * @param consecutiveFail 连续失败次数（0 = 没失败过）
     * @param bus             运行期旋钮
     */
    public static int effectiveCooldownTicks(int base, int consecutiveFail, TuningBus bus) {
        TuningBus b = bus == null ? TuningBus.global() : bus;
        double scale = b.cooldownScale();
        double backoff = 1.0;
        if (consecutiveFail > 0) {
            // ★ 失败 ⇒ 退避（越连续越久），上限由旋钮给 —— "选中却做不出来"不再永久占位
            backoff = Math.min(b.failureBackoffCap(), 1.0 + consecutiveFail);
        }
        long v = Math.round(base * scale * backoff);
        return (int) Math.max(1, Math.min(20 * 60, v));
    }

    /** 是否仍在冷却。 */
    public boolean isCoolingDown(String actionId, long now, ToIntFunction<String> cooldownOf) {
        long used = lastUsedAt(actionId);
        if (used == Long.MIN_VALUE) {
            return false;
        }
        return now - used < cooldownOf.applyAsInt(actionId);
    }

    /**
     * 当前仍在冷却的动作集合 —— 直接喂给
     * {@code ContextFacts.coolingDown}。
     *
     * @param candidateIds 需要检查的动作 id（通常就是清单里的全部动作）
     * @param cooldownOf   动作 → 最小重复间隔（tick）；游戏侧传
     *                     {@code CarrierResolver#cooldownTicksOf}
     */
    public Set<String> coolingDown(Iterable<String> candidateIds, long now, ToIntFunction<String> cooldownOf) {
        Set<String> out = new HashSet<>();
        for (String id : candidateIds) {
            if (isCoolingDown(id, now, cooldownOf)) {
                out.add(id);
            }
        }
        return out;
    }

    public void reset() {
        lastUsed.clear();
    }

    /** 诊断：当前记录了几条。 */
    public int size() {
        return lastUsed.size();
    }
}
