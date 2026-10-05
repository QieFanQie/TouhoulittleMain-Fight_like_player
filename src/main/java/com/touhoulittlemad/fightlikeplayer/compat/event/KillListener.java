package com.touhoulittlemad.fightlikeplayer.compat.event;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * ★★ <b>击杀归因</b>（第十六轮）：谁杀的，算在谁头上。
 *
 * <h2>委托方原话</h2>
 * > 「把**上一次对话到这次对话期间杀了什么，有多少**也纳入女仆 llm 的知晓内容。」
 *
 * <h2>★ 为什么不直接读 TLM 的记录（取证结论）</h2>
 * TLM 有 {@code MaidKillRecordManager}，但它的字段（{@code totalCount/slimeCount/…}）
 * <b>全是 private 且没有 getter</b>，而且它自己的归因只有一句
 * {@code source.getEntity() instanceof EntityMaid} ⇒
 * <b>她的仆从/刀气/法术杀的，TLM 一个都不记</b>。⇒ 我们自己听 {@code LivingDeathEvent}。
 *
 * <h2>★ 归因阶梯（从最确定到最间接）</h2>
 * <ol>
 *   <li>伤害的"发起者"就是她本人（{@code DamageSource#getEntity()}）；</li>
 *   <li>发起者是<b>她的仆从</b>（在 {@code GoetyServantOps.myServants(maid)} 名单里）；</li>
 *   <li>发起者是<b>她名下的投射物/实体</b>（拔刀剑刀气、幻影剑、弓箭…）——
 *       顺着 {@code Projectile#getOwner()} / {@code OwnableEntity#getOwner()} 找一层或两层；</li>
 *   <li>都不是 ⇒ 不算她的（宁可少记，也不要把别人的击杀算到她头上）。
 * </ol>
 * ⚠️ <b>两侧都会触发</b>（本项目 {@code HurtListener} 已经踩过这条）⇒ 必须挡掉客户端，
 * 否则计数翻倍。
 */
public final class KillListener {

    /** 顺便也在对话里说得出"她杀了什么"——这里只记名字。 */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        // ★ 两侧都会触发：不挡客户端就会翻倍（本项目 HurtListener 的教训）
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        LivingEntity dead = event.getEntity();
        Entity killer = attribution(event.getSource().getEntity(), event.getSource()
                .getDirectEntity());
        if (killer == null) {
            return;
        }
        for (EntityMaid maid : servantsOf(killer)) {
            String what = dead.getType().builtInRegistryHolder().key().location().toString();
            MaidMemory.noteKill(maid, what);
            // ★★ 第十七轮续（委托方第 2 条）：**击败 boss 级单位 ⇒ 主动触发一次女仆 LLM**。
            //   判据（阈值/白名单）在纯逻辑层 BossChatPolicy；这里只做归因之后的转发。
            BossChatTrigger.onKill(maid, dead,
                    dead.level() instanceof net.minecraft.server.level.ServerLevel sl
                            ? sl.getGameTime() : 0L);
        }
    }

    /** 顺一遍归因阶梯，返回"算谁杀的"（{@code null} = 不算任何女仆的）。 */
    private static Entity attribution(Entity source, Entity direct) {
        Entity e = source != null ? source : direct;
        for (int depth = 0; e != null && depth < 3; depth++) {
            if (e instanceof EntityMaid) {
                return e;
            }
            Entity owner = ownerOf(e);
            if (owner == null || owner == e) {
                return e;                        // 停在最上层的发起者（可能是仆从/投射物）
            }
            e = owner;
        }
        return e;
    }

    private static Entity ownerOf(Entity e) {
        if (e instanceof Projectile p && p.getOwner() != null) {
            return p.getOwner();
        }
        if (e instanceof OwnableEntity o && o.getOwner() != null) {
            return o.getOwner();
        }
        return null;
    }

    /**
     * 这个"凶手"名下的女仆（通常是 0 或 1 个）。
     *
     * <p>★ 覆盖：她就是凶手本人（最直接）；或者是<b>她名下的东西</b>（已由
     * {@link #attribution} 顺着 owner 链还原成她）；或者是<b>她的 Goety 仆从</b>
     * （那类实体的 owner 不一定是她 ⇒ 用现有名单比对）。
     */
    private static java.util.List<EntityMaid> servantsOf(Entity killer) {
        java.util.List<EntityMaid> out = new java.util.ArrayList<>(1);
        if (killer instanceof EntityMaid maid) {
            out.add(maid);
            return out;
        }
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
                return out;
            }
            var level = killer.level();
            if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
                return out;
            }
            // 在她附近找"名下有这个仆从"的女仆（仆从不会离主人太远：用 64 格筛一遍）
            for (EntityMaid maid : server.getEntitiesOfClass(EntityMaid.class,
                    killer.getBoundingBox().inflate(64.0))) {
                if (com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                        .myServants(maid).contains(killer)) {
                    out.add(maid);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            FightLikePlayer.LOGGER.debug("[FLP] 击杀归因（仆从）失败：{}", ex.toString());
        }
        return out;
    }
}
