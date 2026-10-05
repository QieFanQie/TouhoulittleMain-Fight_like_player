package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.decision.LogThrottle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

/**
 * ★★ <b>「她的召唤物不该把目标指着她」的修缮伺服</b>（第二十二轮）。
 *
 * <h2>委托方实测</h2>
 * > 「存在女仆的铁魔法召唤物（召唤出的僵尸、召唤出的骷髅、召唤出的恼鬼）会索敌女仆的问题。
 * > 虽然不会对女仆造成伤害，但是仇恨会在女仆身上，同样女仆也会攻击其召唤物。」
 *
 * <h2>★ 因果链（取证：反编译铁魔法 3.16.3 的 {@code GenericProtectOwnerTargetGoal}）</h2>
 * <pre>
 *   召唤物会打「正在攻击主人的 Mob」，**也会打「正在攻击主人名下召唤物的 Mob」**
 *     ↑ 主人是玩家时：玩家不是 Mob ⇒ 这条扫描永远看不到他 ⇒ 玩家侧没有这个问题
 *     ↑ 主人是女仆时：**她是 Mob** ⇒ 她一旦把自己的召唤物设成目标
 *         ⇒ 同队其它召唤物把她当侵略者 ⇒ 整队转过来打她 ⇒ 她反击 ⇒ 自激循环
 * </pre>
 *
 * <h2>这道修缮在整个修法里的位置</h2>
 * <ol>
 *   <li><b>堵因</b>（主修）：{@code TaskPlayerLikeCombat#canAttackBase} 把"她自己的东西"
 *       从**所有**目标判据里排除掉 ⇒ 那条扫描永远不会再被她点亮
 *       （见 {@link MaidAllies} / {@code FactionRules}）；</li>
 *   <li><b>修缮存量</b>（本类）：那一瞬间**已经被设成"目标是女仆"**的召唤物不会自己松手 ——
 *       另一方的目标行为不会因为我方判据变化而清目标 ⇒ 这里把它清掉（并留痕）。</li>
 * </ol>
 *
 * <h2>★★ 三条设计约束（都是本项目交过学费的纪律）</h2>
 * <ul>
 *   <li><b>节流到 1 秒一次</b>（{@link #CHECK_INTERVAL_TICKS}）：不做成"每 tick 抢写"
 *       —— 那会与铁魔法自己的目标行为形成拉锯（同一个字段两个写入者）。
 *       1 秒的延迟无害：它们本来就伤不到她；</li>
 *   <li><b>挂在每 tick 的驱动器上</b>（{@code DirectiveTicker}），<b>不是</b>挂在她打架时的行为层
 *       —— 判据还是那一句："她不打架的时候，这件事还会发生吗？" 会 ⇒ 不能挂在有条件才跑的地方；</li>
 *   <li><b>可观测</b>：每次修缮记一条（按"女仆+生物类型"节流），
 *       否则"她为什么突然不打那只东西了/那些召唤物为什么改目标了"就查不出来。</li>
 * </ul>
 */
public final class SummonAllyGuard {

    private SummonAllyGuard() {
    }

    /** 多久查一次（tick）。20 = 1 秒 —— 见类注释的"不做成每 tick 抢写"。 */
    public static final int CHECK_INTERVAL_TICKS = 20;

    /** 搜索半径（格）：召唤物由各自的"跟随主人"行为保持在主人附近（最松的是恼鬼 35 格）。 */
    private static final double SCAN_RADIUS = 40.0;

    /** 每个女仆上次检查的时刻。 */
    private static final java.util.Map<java.util.UUID, Long> LAST_CHECK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 修缮日志节流（同一女仆 + 同一生物类型 10 秒一条）。 */
    private static final LogThrottle NOTE = new LogThrottle(200);

    /**
     * 一秒一次地检查"她的召唤物里有没有把目标指着她的"，有就清掉。
     *
     * <p>★ 只看<b>她的</b>召唤物/仆从（{@link MaidAllies#friendlyReason}），
     * 别的生物的目标一概不碰 —— 单一职责，也避免影响铁魔法自己的"护卫"语义。
     */
    public static void tick(EntityMaid maid, long now) {
        try {
            if (maid == null || maid.level().isClientSide()) {
                return;
            }
            Long last = LAST_CHECK.get(maid.getUUID());
            if (last != null && now - last < CHECK_INTERVAL_TICKS) {
                return;
            }
            LAST_CHECK.put(maid.getUUID(), now);

            // ★★ 第二十四轮（性能）：**她一只召唤物都没有时，这个伺服必须零开销**。
            //   原来无条件做一次半径 40 的 `Mob` 查询 —— 在怪物密集的整合包里那是每 20 tick
            //   一次不小的开销，而"她没有召唤物"恰恰是最常见的情况。
            //   判据用铁魔法自己的索引（O(她有几只)，见 IronsServantOps#countSummonsIndexed）
            //   + Goety 的半径扫描（那个本来就带半径，且只在装了 Goety 时跑）。
            if (!hasAnySummon(maid)) {
                return;
            }

            List<Mob> near = maid.level().getEntitiesOfClass(Mob.class,
                    maid.getBoundingBox().inflate(SCAN_RADIUS));
            for (Mob mob : near) {
                if (mob.getTarget() != maid) {
                    continue;                       // 没指着她 ⇒ 什么都不做（绝大多数情况）
                }
                if (MaidAllies.friendlyReason(maid, mob) == null) {
                    continue;                       // 不是她的东西 ⇒ 那是真的敌人在打她，别插手
                }
                mob.setTarget(null);
                try {
                    mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                } catch (RuntimeException ignored) {
                    // 不是脑生物（铁魔法召唤物用的是目标行为）⇒ 清 setTarget 就够了
                }
                if (NOTE.should(maid.getUUID() + "|" + mob.getType(), now)) {
                    FightLikePlayer.LOGGER.info("[FLP][faction] 修缮：她的 {} 把目标指着她 ⇒ 已清掉"
                            + "（玩家侧不会出现这种情况；主因已在目标判据里堵掉）",
                            mob.getName().getString());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 伺服绝不许把 tick 打崩
            FightLikePlayer.LOGGER.info("[FLP][faction] 召唤物修缮出错（本次跳过）：{}", e.toString());
        }
    }

    /** 女仆卸载/死亡时清状态。 */
    public static void forget(java.util.UUID maidId) {
        LAST_CHECK.remove(maidId);
        SERVANT_AT.remove(maidId);
    }

    /** 每女仆上次问"她有没有召唤物"的时刻（见 {@link #SERVANT_TTL}）。 */
    private static final java.util.Map<java.util.UUID, Long> SERVANT_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** "她有没有召唤物"的缓存时长（tick）：2 秒。 */
    private static final long SERVANT_TTL = 40;

    /** 上一次的答案（省下每 2 秒一次的铁魔法索引查询：那要解析 UUID → 实体）。 */
    private static final java.util.Map<java.util.UUID, Boolean> SERVANT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ★ 她此刻有没有召唤物/仆从（**带 2 秒缓存**，第二十四轮的性能门）。
     *
     * <p>为什么可以缓存：这个门的用途只是"要不要去做那件修缮的事"——
     * 多算 2 秒不会有任何后果（真正要被清的是"它的目标指着她"，那种状态不会因为晚 2 秒发生变化）。
     */
    private static boolean hasAnySummon(EntityMaid maid) {
        Long at = SERVANT_AT.get(maid.getUUID());
        Boolean cached = SERVANT_CACHE.get(maid.getUUID());
        long now = maid.level().getGameTime();
        if (at != null && cached != null && now - at < SERVANT_TTL) {
            return cached;
        }
        boolean any;
        try {
            any = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsServantOps
                    .countSummonsIndexed(maid) > 0;
            if (!any) {
                any = com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                        .servantCount(maid) > 0;
            }
        } catch (RuntimeException | LinkageError e) {
            any = false;                       // 模组没装/查询失败 ⇒ 当没有（这个门本来就只是优化）
        }
        SERVANT_AT.put(maid.getUUID(), now);
        SERVANT_CACHE.put(maid.getUUID(), any);
        return any;
    }
}
