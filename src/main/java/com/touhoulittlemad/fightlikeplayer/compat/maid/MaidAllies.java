package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.decision.FactionRules;
import com.touhoulittlemad.fightlikeplayer.decision.LogThrottle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;

/**
 * ★★ <b>「这是不是她自己的东西」的 compat 侧查表</b>（第二十二轮）。
 *
 * <p>判据本体在纯逻辑层 {@link FactionRules}；本类只负责**把四个事实查出来**：
 * <ol>
 *   <li>是不是她自己；</li>
 *   <li>原版阵营（{@code isAlliedTo}：队伍；★ 对"她自己"也成立）；</li>
 *   <li>{@code OwnableEntity#getOwner() == 她}（原版宠物/坐骑/部分召唤物）；</li>
 *   <li>各家模组**自己的**归属记录 —— 铁魔法 {@code IMagicSummon#getSummoner()}、
 *       Goety {@code IOwned#getTrueOwner()}（委派给两个被 {@code ModList.isLoaded} 把守的
 *       compat 类，没装那个模组时那两行根本不会被执行）。</li>
 * </ol>
 *
 * <h2>★★ 为什么必须有它（委托方实测的因果链）</h2>
 * 铁魔法召唤物有一条"保护主人"的目标行为：它会打**正在攻击主人的 Mob**，
 * 也会打**正在攻击主人名下召唤物的 Mob**。玩家不是 {@code Mob} ⇒ 玩家永远不在这条路上；
 * 而女仆是 {@code Mob} ⇒ 她一旦（哪怕一瞬）把自己的召唤物设成目标，
 * 其余召唤物就会把她当成侵略者 ⇒ 整队转过来打她 ⇒ 她反击 ⇒ 自激循环。
 * ⇒ 把"自家东西"从**所有**目标判据里排除掉，那条路就永远不会被点亮。
 *
 * <p>★ 落点只有一处：{@code TaskPlayerLikeCombat#canAttackBase}（TLM 的
 * {@code EntityMaid#canAttack} 会把三个写/清目标的路径全部汇到那里 —— 见那个类的注释），
 * 因此<b>不需要 mixin，也不需要在别处再补一遍</b>。
 */
public final class MaidAllies {

    private MaidAllies() {
    }

    /**
     * 「拒绝当敌人」的日志节流器：同一女仆 + 同一种生物 10 秒一条。
     * <p>为什么需要：这条判定**每个候选目标每 tick 都会问一次**（见 {@code LogThrottle} 的说明）。
     */
    private static final LogThrottle NOTE = new LogThrottle(200);

    /**
     * 她**不该**把它当敌人吗？
     *
     * @return {@code null} = 可以当敌人；否则是一句可读理由
     */
    public static String friendlyReason(EntityMaid maid, Entity target) {
        if (maid == null || target == null) {
            return null;                 // 没有目标（或没有她）⇒ 这条判据不适用
        }
        boolean self = target == maid;
        boolean allied = target.isAlliedTo(maid);
        boolean owned = target instanceof OwnableEntity ownable && ownable.getOwner() == maid;
        boolean summoned = summonedByMaid(maid, target);
        return FactionRules.friendlyReason(self, allied, owned, summoned);
    }

    /**
     * 是不是她通过模组 API 召唤/雇佣的（铁魔法召唤物 / Goety 仆从）。
     *
     * <p>★ 两路都包在 {@code try/catch (Throwable)} 里：那两个 compat 类会引用它们各自的
     * 模组类型（未装时解析会抛 {@code NoClassDefFoundError}，那是 {@code Error} 不是
     * {@code Exception}）—— 任何一路出问题都只当"不是"，绝不连累目标选择。
     */
    private static boolean summonedByMaid(EntityMaid maid, Entity target) {
        try {
            if (com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsServantOps
                    .isMySummon(maid, target)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 铁魔法没装/查询失败 ⇒ 这一路不成立
        }
        try {
            return com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                    .isMyServant(maid, target);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ★ 可读出口：目标因为"是她自己的东西"被排除时记一条（节流，不刷屏）。
     *
     * <p>为什么要有：这条规则一旦生效，<b>"她为什么不打那只怪"</b>就成了一个新的可查询问题
     * （本项目第 19 条纪律：可预期的失败必须有用户可读的出口）。
     */
    public static void noteRefused(EntityMaid maid, LivingEntity target, String reason) {
        try {
            long now = maid.level().getGameTime();
            if (!NOTE.should(maid.getUUID() + "|" + target.getType(), now)) {
                return;
            }
            FightLikePlayer.LOGGER.info("[FLP][faction] 不当敌人：{} —— {}",
                    target.getName().getString(), reason);
        } catch (RuntimeException ignored) {
            // 诊断失败不影响判定
        }
    }
}
