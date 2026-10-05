package com.touhoulittlemad.fightlikeplayer.compat.exec.irons;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import io.redspace.ironsspellbooks.entity.mobs.IMagicSummon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>铁魔法（{@code irons_spellbooks}）召唤物的「解散」实现</b>
 * —— 第十七轮新增（委托方第 5 条：「主动为处死所有仆从的指令添加一个铁魔法的部分」）。
 *
 * <h2>为什么单独一个类（而不是写进 {@code GoetyServantOps}）</h2>
 * 铁魔法与 Goety 是两套<b>互不相干</b>的 API：
 * <ul>
 *   <li>Goety 的仆从是 {@code com.Polarice3.Goety.api.entities.IOwned}（自己那一套）；</li>
 *   <li>铁魔法的召唤物是 {@link IMagicSummon}（见下），<b>不实现</b> {@code IOwned}。</li>
 * </ul>
 * ⇒ 两者的代码各自留在自己的 {@code compat.exec.*} 包里，谁也不 import 谁
 * （"没装那个模组也能加载"这条纪律靠"引用只出现在被把守的方法里"来保证）。
 *
 * <h2>★★ 官方入口（取证，不猜）</h2>
 * {@code javap} 于 {@code [Iron的法术与魔法书] irons_spellbooks-1.20.1-3.16.3.jar}：
 * <pre>
 *   io.redspace.ironsspellbooks.entity.mobs.IMagicSummon
 *       public default Entity getSummoner();      ← 归属（召唤者）
 *       public abstract void onUnSummon();        ← ★ 解散（唯一官方入口）
 *   SummonedZombie / SummonedSkeleton / SummonedVex / SummonedPolarBear / SummonedHorse
 *       均 implements IMagicSummon
 * </pre>
 * 而 {@code SummonedZombie#onUnSummon} 的字节码（{@code javap -c}）是：
 * <pre>
 *   if (!level.isClientSide) { MagicManager.spawnParticles(…, ParticleTypes.POOF …); }
 *   this.setRemovalReason(Entity.RemovalReason.DISCARDED);   ← m_142467_
 * </pre>
 * ⇒ <b>{@code onUnSummon()} 就是"让它消失"的正确做法</b>
 * （我们自己 {@code discard()} 会漏掉粒子与它自己的清理）。
 *
 * <h2>⚠️ 没装铁魔法时绝不能炸</h2>
 * 本类引用了 {@code io.redspace.ironsspellbooks.*} 的<b>接口</b>
 * ⇒ 未装该模组时解析该类型会抛 {@code NoClassDefFoundError}（{@code Error}，不是 {@code Exception}）。
 * 因此：① 每个入口第一句就是 {@link #available()}；
 * ② 循环体整段包在 {@code catch (Throwable)} 里 —— 拿不到就当"没有"，绝不连累调用方。
 * （{@code build.gradle} 里铁魔法是 {@code compileOnly}，本类永不被它反向依赖。）
 */
public final class IronsServantOps {

    private IronsServantOps() {
    }

    /** 模组 id（与 {@code build.gradle} 的 {@code libs:irons_spellbooks} 一致）。 */
    private static final String MOD_ID = "irons_spellbooks";

    /** 是否装了铁魔法。★ 任何碰 {@link IMagicSummon} 的代码都必须在它之后。 */
    public static boolean available() {
        return net.minecraftforge.fml.ModList.get().isLoaded(MOD_ID);
    }

    /**
     * 归属判据：<b>她是召唤者</b>。
     *
     * <p>两路都认（任一成立即算她的）：
     * <ol>
     *   <li>{@link IMagicSummon#getSummoner()} {@code == maid}（铁魔法自己的记录）；</li>
     *   <li>{@code OwnableEntity#getOwner() == maid}（少数召唤物走原版归属）。</li>
     * </ol>
     */
    private static boolean ownedBy(Entity e, EntityMaid maid) {
        if (!(e instanceof IMagicSummon ms)) {
            return false;                       // 根本不是铁魔法的召唤物
        }
        if (ms.getSummoner() == maid) {
            return true;
        }
        return e instanceof OwnableEntity oe && oe.getOwner() == maid;
    }

    /**
     * 她名下的全部在场召唤物（同一维度、活着、不是她自己）。
     *
     * <p>⚠️ <b>这一条走 {@code level.getAllEntities()}（整个维度）</b> —— 只许用在
     * <b>指令</b>那种"必须尽量不漏"的场合（处死/如实反馈）。★ **每 tick 的事实统计请用
     * {@link #countSummonsIndexed}</b>（走铁魔法自己的索引，O(她有几只)）。
     */
    public static List<LivingEntity> mySummons(EntityMaid maid) {
        List<LivingEntity> out = new ArrayList<>();
        if (maid == null || !available()) {
            return out;
        }
        try {
            if (!(maid.level() instanceof ServerLevel level)) {
                return out;
            }
            // ★ 用 getAllEntities（全维度）而不是半径查询：指令的语义是"**所有**仆从"
            for (Entity e : level.getAllEntities()) {
                if (e == maid || !(e instanceof LivingEntity le) || !le.isAlive()) {
                    continue;
                }
                if (ownedBy(e, maid)) {
                    out.add(le);
                }
            }
        } catch (Throwable t) {
            FightLikePlayer.LOGGER.info("[FLP] 铁魔法召唤物枚举失败（按「一个都没有」处理）：{}", t.toString());
            return new ArrayList<>();
        }
        return out;
    }

    /**
     * ★★ <b>毫秒级口径：用铁魔法自己的索引数她的召唤物</b>（第二十四轮，性能）。
     *
     * <h2>为什么原来的写法是性能灾难</h2>
     * {@link #mySummons} 走 {@code level.getAllEntities()} —— 那是**整个维度**的实体表，
     * 而它每 tick 被 {@code MaidSnapshot.facts} 问一次（"她有几个仆从"这个事实）
     * ⇒ 实体一多就是每秒 20 次全维度遍历（委托方报的"一卡一卡的"）。
     *
     * <h2>★ 官方索引（反编译取证：{@code SummonManager}）</h2>
     * <pre>
     *   SummonManager.getSummons(Entity owner) : Set&lt;UUID&gt;
     *       ⇒ ownerToSummons.getOrDefault(owner.getUUID(), Set.of())     ← 按主人 UUID 的索引
     *   SummonManager.setOwner(summon, owner)  里会 startTrackingSummon(owner, summon)
     *       ⇒ 凡是走召唤法术建出来的召唤物都在索引里（我们的执行器正是走那条路）
     * </pre>
     * ⇒ 计数变成 O(她的召唤数) 而不是 O(全维度实体数)，而且**口径就是铁魔法自己的账**。
     *
     * <p>★ 与 {@link #mySummons} 的关系：那个多一条"原版归属"兜底（{@code OwnableEntity}），
     * 用于**指令**（"处死她名下全部召唤物"，必须尽量不漏）；本方法只认铁魔法索引，
     * 用于**每 tick 的事实统计**（慢半拍无所谓，漏一只也不影响"她有几个"的量级判断）。
     * 两处的差别写在各自的 javadoc 里，不许互相冒充。
     */
    public static int countSummonsIndexed(EntityMaid maid) {
        if (maid == null || !available()) {
            return 0;
        }
        try {
            if (!(maid.level() instanceof ServerLevel level)) {
                return 0;
            }
            int n = 0;
            for (java.util.UUID id : io.redspace.ironsspellbooks.capabilities.magic.SummonManager
                    .getSummons(maid)) {
                Entity e = level.getEntity(id);          // ★ O(1) 查表，不遍历世界
                if (e instanceof LivingEntity le && le.isAlive()) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;                                     // 索引读不到 ⇒ 当"没有"（不连累调用方）
        }
    }

    /** 她名下有 B 只召唤物（★ 用于指令下达时的**如实反馈**）。 */
    public static int countSummons(EntityMaid maid) {
        return mySummons(maid).size();
    }

    /**
     * ★★ <b>这一只（任意实体）是不是她名下的召唤物</b>（第二十二轮）。
     *
     * <p>用途：目标判据（{@code MaidAllies}）—— 她**永远不该**把自己的召唤物当敌人
     * （玩家侧本来就没有这条路；而她是 {@code Mob}，一旦把召唤物设成目标，
     * 别的召唤物的"保护主人"行为就会把她当侵略者 ⇒ 整队转过来打她）。
     *
     * <p>★ 与 {@link #mySummons(EntityMaid)} 共用同一个归属判据 {@code ownedBy}，
     * 口径只有一处。★ 未装铁魔法时（或查询失败）返回 {@code false}：
     * 这里问的是"是不是她的"，答错成"是"会让她的目标凭空消失，答错成"不是"只是维持现状。
     */
    public static boolean isMySummon(EntityMaid maid, Entity e) {
        if (maid == null || e == null || !available()) {
            return false;
        }
        try {
            return ownedBy(e, maid);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解散她名下<b>全部</b>铁魔法召唤物（走官方 {@link IMagicSummon#onUnSummon()}）。
     *
     * @return 实际解散了几只（0 ⇒ 她一个召唤物都没有）
     */
    public static int dismissAllSummons(EntityMaid maid) {
        int n = 0;
        for (LivingEntity e : mySummons(maid)) {
            try {
                ((IMagicSummon) e).onUnSummon();
                n++;
            } catch (Throwable t) {
                FightLikePlayer.LOGGER.info("[FLP] 铁魔法召唤物 {} 解散失败（跳过）：{}",
                        e.getName().getString(), t.toString());
            }
        }
        return n;
    }
}
