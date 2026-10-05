package com.touhoulittlemad.fightlikeplayer.compat.event;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * ★★ <b>法术实体的"孤儿清扫"</b> —— 委托方第十一轮实测驱动。
 *
 * <h2>委托方原话</h2>
 * <blockquote>
 * 「敌人死完了<b>腐化聚晶并没有马上消失</b>，但我主动切换聚晶时马上切换了。
 * 且敌人死了之后，只要不主动切换聚晶并发动下一次攻击，<b>不论换成什么模式，腐化光束都会一直进行（真的有伤害）</b>。」
 * </blockquote>
 *
 * <h2>根因（是上一轮修白烟时带出来的副作用）</h2>
 * 上一轮为了让光束不再依赖 {@code isUsingItem()}（那是"每 tick 一团白烟"的来源），
 * 我们把光束的 {@code itemBase} 置为 {@code false} ⇒ 它<b>按自己的生命周期存活</b>。
 * 而 {@code AbstractBeam} 原本正是靠 {@code itemBase && !isSpellCasting(owner)} 来自毁的
 * ⇒ 松绑之后<b>没有任何东西会再杀掉它</b>：
 * 我们的通道收尾时有 {@code discardBeams}，但那是"引导结束"那一刻；
 * 如果那一刻因为任何原因没走到（战斗结束、行为层根本不跑、女仆被换走……），
 * <b>光束就成了孤儿，带着伤害一直挂在那里</b>。
 *
 * <h2>★ 修法：把"该由谁负责"写成一个不变量</h2>
 * <pre>
 *   不变量：女仆没有在引导法术时，她名下【不应该存在】任何光束实体。
 * </pre>
 * 每 {@value #PERIOD_TICKS} tick 扫一遍所有维度里的 {@code AbstractBeam}：
 * 主人是女仆、而那个女仆<b>没在引导</b> ⇒ 清掉。
 *
 * <p>★ 为什么用"清扫"而不是"在更多地方补 discard"：前者是一条<b>可断言的不变量</b>
 * （无论从哪条路径漏掉，都会被下一轮清扫兜住），后者是逐条堵漏
 * —— 本项目已经为"逐条堵漏永远堵不完"付过学费（docs/13 第 26 条）。
 *
 * <p>★ 为什么不在女仆自己的 tick 里做：行为层只在"有目标 + 战斗任务"时才运行
 * ⇒ 脱战那一刻正是漏掉的时刻。⇒ 必须由<b>与行为层无关的</b>服务端 tick 来做。
 */
@Mod.EventBusSubscriber(modid = FightLikePlayer.MOD_ID)
public final class SpellEntityCleaner {

    private SpellEntityCleaner() {
    }

    /** 清扫周期（tick）。1 秒一次足够 —— 光束最长也就多活 1 秒。 */
    private static final int PERIOD_TICKS = 20;

    private static int counter;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // ★★ 每 tick 先推进"正在引导的法术"（第十二轮）—— 这一步【必须】与行为层解耦：
        //    行为层只在"有目标 + 战斗任务"时运行，而引导不能因此停摆
        //    （停摆会永久挡住换手 ⇒ 委托方实测的"法杖切不出来"）。
        driveChannels();
        if (++counter < PERIOD_TICKS) {
            return;
        }
        counter = 0;
        try {
            sweep(event.getServer());
        } catch (Throwable t) {
            // ★ 清扫绝不能影响服务器：附属类缺失（NoClassDefFoundError）/ 实体类变了 ⇒ 静默跳过
            FightLikePlayer.LOGGER.debug("[FLP] 法术实体清扫跳过：{}", t.toString());
        }
    }

    /**
     * ★★ <b>独立推进所有正在引导的通道</b>（与行为层是否运行无关）。
     *
     * <p>只遍历"确实在引导的那些女仆"（{@code GoetyChannel.channelOwners()}）⇒ 极便宜
     * （通常 0~1 个），不需要扫全图。
     */
    private static void driveChannels() {
        try {
            for (EntityMaid maid : com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                    .GoetyChannel.channelOwners()) {
                var p = com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                        .GoetyChannel.tick(maid);
                if (p == com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                        .GoetyChannel.Progress.DONE) {
                    // ★ 通道结束了，但行为层可能没在跑（没有目标）⇒ 把执行层那份在途状态也收干净，
                    //   否则它会一直挂着 Phase.CHANNEL（要等行为层下次跑起来才被清）。
                    com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors
                            .releaseChannelInFlight(maid);
                }
            }
        } catch (Throwable t) {
            FightLikePlayer.LOGGER.debug("[FLP] 通道驱动器跳过：{}", t.toString());
        }
    }

    /** 真正干活的那一遍（单独拆出来便于自测/阅读）。 */
    private static void sweep(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var beam : level.getEntitiesOfClass(
                    com.Polarice3.Goety.common.entities.projectiles.AbstractBeam.class,
                    // ★ 全图范围：光束可能被留在很远的地方（她打一枪换个地方）
                    new net.minecraft.world.phys.AABB(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7),
                    b -> true)) {
                var owner = beam.getOwner();
                if (!(owner instanceof EntityMaid maid)) {
                    continue;                   // 别的模组的仆从/玩家施放的 ⇒ 不归我们管
                }
                // ★★ 第十二轮补充：**目标已死也算"没有理由留着"**。
                //   委托方实测「敌人死完了腐化光束并没有马上消失」的另一半原因是：
                //   她的 ATTACK_TARGET 还指着尸体 ⇒ 她一轮轮重新起手 ⇒ isChanneling 恒真
                //   ⇒ 只按"没在引导"判就永远不清。⇒ 判据加上"目标活着"。
                //   （上游已经会抹掉死目标的记忆，这里是第二道：即使记忆还没被清掉也不留孤儿。）
                var t = maid.getTarget();
                boolean hasLiveTarget = t != null && t.isAlive();
                if (com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel
                        .isChanneling(maid) && hasLiveTarget) {
                    continue;                   // 她正在对着活目标引导 ⇒ 光束是"活的"，留着
                }
                beam.discard();
                removed++;
            }
        }
        if (removed > 0) {
            FightLikePlayer.LOGGER.info(
                    "[FLP] ★ 清扫了 {} 道「孤儿光束」（主人已不在引导）", removed);
        }
    }
}
