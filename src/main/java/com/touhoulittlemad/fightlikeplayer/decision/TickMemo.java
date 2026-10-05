package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ★★ <b>「每 tick 都要问、但答案不会每 tick 都变」的东西，按 TTL 缓存一次</b>
 * （第二十四轮，委托方实测「该模组带来了很大的性能负担，一卡一卡的」）。
 *
 * <h2>为什么需要它（这一轮的取证）</h2>
 * 我们的每 tick 路径上有几件事是**世界扫描 / 反射 / 逐物品翻译**级别的：
 * <ul>
 *   <li>{@code IronsServantOps.countSummons} —— 原来走 {@code level.getAllEntities()}
 *       （<b>整个维度</b>的实体），而它每 tick 被 {@code MaidSnapshot.facts} 问一次；</li>
 *   <li>{@code countNearbyEnemies} —— 半径查询 + 逐个实体判"能不能打"；</li>
 *   <li>{@code customFacts} —— 一次要问 5 个探针（持有魔杖/法术容器/回溯聚晶/妖刀/刀技），
 *       每个都要**枚举一遍她的全部物品**（含饰品与背包 36 格）并读各自模组的 NBT；</li>
 *   <li>{@code typeNames}/{@code itemTags}/属性能力 —— 每件物品都要走一遍类继承链/标签表，
 *       而 {@code MaidSnapshot.possessed} 每 tick 对**每一件**物品都调它们。</li>
 * </ul>
 * 这些量的**真实变化频率**远低于每 tick（她是"捡到/丢掉/换成别的东西"时才变）。
 * ⇒ 按 TTL 缓存，**语义只差"最多晚 0.5 秒"**，而开销降到 1/N。
 *
 * <h2>★ 什么时候**不能**用它（写在这里免得被误用）</h2>
 * 凡是**她这一拍就能感觉到**的量都不要缓存：与目标的距离、她自己的血量、
 * 在途动作的状态、指令总线的内容。本类只用来包"外部世界/物品清单的派生事实"。
 *
 * <h2>★ 语义</h2>
 * <ul>
 *   <li>{@code get(key, now, ttl, compute)}：缓存未过期 ⇒ 直接返回上次的；否则重算并记时刻；</li>
 *   <li>{@code ttl <= 0} ⇒ 不缓存（每次都算，等于没用它）；</li>
 *   <li>★ 表大小有上限（{@link #MAX_ENTRIES}）：诊断/缓存设施<b>自己不许变成内存泄漏</b>
 *       —— 超限时先清过期的，仍然超就整体清空（下一次全部重算，功能不受影响）。</li>
 * </ul>
 * <p>★ 纯逻辑、无 MC 类型 ⇒ 可离线断言（见 {@code DirectiveSelfTest}）。
 */
public final class TickMemo<K, V> {

    /** 表最多多少条；超了就修剪（先过期、再整体清空）。 */
    private static final int MAX_ENTRIES = 2048;

    private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();

    private record Entry<V>(V value, long at) {
    }

    /**
     * 取（必要时重算）。
     *
     * @param key    缓存键（女仆 UUID / {@code Item} / 别的稳定标识）
     * @param now    当前 tick
     * @param ttl    有效期（tick）；{@code <= 0} = 不缓存
     * @param compute 怎么算（只在过期时调用）
     */
    public V get(K key, long now, long ttl, Supplier<V> compute) {
        if (key == null || ttl <= 0) {
            return compute.get();
        }
        Entry<V> e = cache.get(key);
        if (e != null && now - e.at() < ttl) {
            return e.value();
        }
        V fresh = compute.get();
        cache.put(key, new Entry<>(fresh, now));
        if (cache.size() > MAX_ENTRIES) {
            prune(now, ttl);
        }
        return fresh;
    }

    private void prune(long now, long ttl) {
        cache.entrySet().removeIf(en -> now - en.getValue().at() >= ttl);
        if (cache.size() > MAX_ENTRIES) {
            cache.clear();                     // 极端情况：整体清空（下次重算，功能不变）
        }
    }

    /** 主动失效（物品变了/状态变了时调用）。 */
    public void invalidate(K key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    /** 当前条目数（自测/诊断用）。 */
    public int size() {
        return cache.size();
    }
}
