package com.touhoulittlemad.fightlikeplayer.compat.event;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;
import com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory;

import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * ★★ <b>刀技的每 tick 挂钩</b>（第十六轮）—— 挂在 <b>TLM 自己的 {@code MaidTickEvent}</b> 上。
 *
 * <h2>★ 为什么必须有一个"独立于行为层"的挂钩</h2>
 * 我们的行为层（{@code PlayerLikeCombat}）只在 <b>她有攻击目标</b>时运行
 * （脑行为门控 {@code present(ATTACK_TARGET)}）。
 * 而刀技是**多 tick 的动作**：起手之后要蓄力、松手、再把连段一帧帧推完 ——
 * 如果推进挂在行为层上，那么"她在放 SA 的过程中目标死了/仇恨没了"就会让
 * <b>推进停摆</b> ⇒ 她永远举着刀、连段的伤害与特效永久丢失。
 * ⇒ 推进必须独立（这与我们第十二轮给引导法术做独立驱动器是同一条教训）。
 *
 * <h2>为什么用 {@code MaidTickEvent} 而不是服务器 tick</h2>
 * 它是 TLM 的<b>公开 API 事件</b>（{@code api.event} 包），语义就是"这个女仆跑了 tick"：
 * 只对**在场的女仆**触发，不需要我们自己维护名册，也不会在没装 TLM 时被加载。
 * ★ 参考实现（万法皆通 1.9.0 的 {@code MaidSpellEventHandler.onMaidTick}）用的正是它。
 */
public final class SlashBladeTicker {

    /** 只有装了这个模组才注册（否则类里引用的拔刀剑类型会 NoClassDefFoundError）。 */
    public static boolean available() {
        return net.minecraftforge.fml.ModList.get().isLoaded("slashblade");
    }

    /**
     * 每 tick：如果她正在放刀技，就推一帧。
     *
     * <p>★ 判据本体在 {@code SlashArtTimeline}（纯逻辑、有自测）；这里只做转发。
     */
    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        var maid = event.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        // ★ 顺手喂"她此刻的物品清单"（★ 只在变化时记录）—— 供**惰上下文**里的
        //   "上次及以前的物品栏"用（见 MaidMemory#noteItems）。
        feedItemSnapshot(maid);
        if (!SlashBladeChannel.isRunning(maid)) {
            return;                                  // ★ 绝大多数 tick 会在这里返回（零开销）
        }
        try {
            SlashBladeChannel.tick(maid, maid.level().getGameTime());
        } catch (RuntimeException | LinkageError e) {
            // ★ 驱动器自己绝不能把 tick 打崩：出错就喊一声并放弃本次刀技（可读出口）
            FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技推进出错（放弃本次）：{}", e.toString());
            SlashBladeChannel.forget(maid.getUUID());
        }
    }

    /** 每 tick 喂一次物品快照（变化时才会真的记）。 */
    private static void feedItemSnapshot(EntityMaid maid) {
        try {
            // ★★ 第二十四轮（性能，委托方报「一卡一卡的」）：
            //   这一步以前是**每 tick 生成一次完整物品清单**（每件物品都要做枪身份反射 /
            //   类型名继承链 / 属性附魔 / 显示名），而清单其实几分钟才变一次。
            //   ⇒ 先算一个只读 Item identity / 数量 / 耐久 / NBT-hash 的指纹；没变就直接返回。
            if (!MaidMemory.itemFingerprintChanged(maid,
                    MaidSnapshot.itemFingerprint(maid))) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (var it : MaidSnapshot.possessed(maid)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(it.itemId()).append('x').append(Math.max(1, it.count()));
            }
            MaidMemory.noteItems(maid, sb.toString());
        } catch (RuntimeException | LinkageError e) {
            // 记不下来不影响战斗
        }
    }
}
