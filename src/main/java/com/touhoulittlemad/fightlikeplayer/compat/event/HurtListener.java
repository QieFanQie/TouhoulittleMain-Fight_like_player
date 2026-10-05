package com.touhoulittlemad.fightlikeplayer.compat.event;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat;

import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <b>受伤监听</b> —— 把"被打了一下"变成一个即时的弹簧冲量（委托方机制 ①）。
 *
 * <h2>为什么用事件，而不是继续用"血量阈值"</h2>
 * {@code ContextBias} 里已经有"血量 &lt; 40% ⇒ 自保需求 +0.6"这类**阈值规则**，
 * 它们只在**跨越阈值的瞬间**改变偏置；而"被砍了一刀"是一个<b>事件</b>，
 * 应当有即时的冲量 —— 玩家的反应也是"这一下疼 ⇒ 先顾一下命"。
 *
 * <p>⇒ 本类只做一件事：把事件翻译成对弹簧空间的一次 {@code bias} 发布
 * （规则是纯函数 {@code SpringDynamics#onHurt}，可离线断言）。
 * <b>它不决定她该做什么</b> —— 格挡、拉开、喝药都由决策层按最近邻去选。
 *
 * <h2>只处理女仆</h2>
 * 其它实体一律直接返回（本模组只关心车万女仆）。服务端才推弹簧
 * （{@code LivingHurtEvent} 两侧都会触发，但弹簧状态只在服务端）。
 */
@Mod.EventBusSubscriber(modid = FightLikePlayer.MOD_ID)
public final class HurtListener {

    private HurtListener() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;                             // 只关心女仆
        }
        if (maid.level().isClientSide()) {
            return;                             // 弹簧状态只在服务端
        }
        double max = maid.getMaxHealth();
        if (max <= 0) {
            return;
        }
        // ★ LivingHurtEvent 的 apply 之前：此时 maid.getHealth() 还是【受伤前】的值
        double after = Math.max(0.0, maid.getHealth() - event.getAmount());
        PlayerLikeCombat.onHurt(maid, event.getAmount(), after / max);
    }
}
