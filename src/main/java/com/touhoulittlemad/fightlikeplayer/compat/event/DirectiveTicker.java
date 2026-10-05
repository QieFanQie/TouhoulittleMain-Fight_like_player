package com.touhoulittlemad.fightlikeplayer.compat.event;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder;

import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * ★★ <b>指令驱动器</b>（第十七轮续）—— 挂在 TLM 自己的 {@link MaidTickEvent} 上。
 *
 * <h2>★★ 为什么必须有它（委托方实测的「瞬间指令完全不起效」）</h2>
 * 在这之前，指令的每 tick 推进（① 清过期 ② 执行待办的瞬间指令）写在
 * {@code PlayerLikeCombat#tick} 的最前面，而那个行为被<b>脑门控在"她有攻击目标"</b>上：
 * <pre>
 *   PlayerLikeCombat.create() → ctx.group(registered(LOOK_TARGET), present(ATTACK_TARGET))
 * </pre>
 * 后果很具体（三条实测全落在同一条上）：
 * <ul>
 *   <li>玩家在她<b>没打架</b>时说「处死所有仆从」⇒ 那条瞬间指令<b>永远不执行</b>，
 *       60 tick（3 秒）后被 {@code DirectiveBus#INSTANT_MAX_AGE_TICKS} 判成
 *       "放太久"作废（日志里那句"下达后 60 tick 内没被执行（她当时没在战斗）"就是这个）；</li>
 *   <li>「把某件东西换到手上」（{@code switch_item}）同理；</li>
 *   <li>「只使用某件物品」（{@code only_item}）的换手步骤也在那个行为里
 *       ⇒ 非战斗时"拿出来"永远不发生。</li>
 * </ul>
 * ★ 这与第十六轮给刀技 / 引导法术加独立驱动器是<b>同一条教训的第三次出现</b>：
 * <b>凡是"每 tick 必须前进"的东西，都不能挂在"有条件才跑"的循环上</b>
 * （docs/13 第 53 条）。
 *
 * <h2>为什么用 {@code MaidTickEvent}</h2>
 * 它是 TLM 的公开 API 事件（{@code api.event}），语义就是"这个女仆跑了 tick"：
 * 只对在场的女仆触发、不需要自己维护名册、没装 TLM 时也不会被加载。
 * ★ 反编译确认它在 {@code EntityMaid#tick()}（{@code m_8119_}）里派发 ⇒
 * 每 tick 一次，且早于本 tick 的脑行为（于是"瞬间"仍然是最快的那个语义）。
 *
 * <p>★ 本类<b>只引用我们自己的类与 MC 类型</b> ⇒ 无条件注册（不需要模组守卫）。
 */
public final class DirectiveTicker {

    /** ★ 最近一次跑过的世界时间（-1 = 从没跑过）—— `/flp gun` 用它回答"驱动器在不在跑"。 */
    private static volatile long lastTickAt = -1;
    /** ★ 累计跑了多少次（诊断用）。 */
    private static volatile long tickCount;

    /**
     * ★ 一行诊断：**驱动器到底有没有在跑**。
     *
     * <p>为什么需要它：委托方连着两轮报"瞬间指令不生效"，而"驱动器没挂上/旧 jar"
     * 与"指令逻辑写错"在外部**长得一模一样**。⇒ 让 `/flp gun` 直接把这件事印出来。
     */
    public static String describe() {
        if (lastTickAt < 0) {
            return "§c从未跑过§r（MaidTickEvent 没触发过 ⇒ 瞬间指令不会执行；"
                    + "多半是游戏没完全重启，或还跑着旧 jar）";
        }
        return "§a在跑§r（已 " + tickCount + " 次，最近一次 @世界时间 " + lastTickAt + "）";
    }

    /**
     * 每 tick：
     * <ol>
     *   <li>{@link DirectiveHolder#tick} —— 清过期 + 执行待办的瞬间指令（与战斗无关）；</li>
     *   <li>{@link DirectiveHolder#itemServo} —— 把指令要的东西换到手上（同上）；</li>
     *   <li>{@code OffhandStance#tick} —— 副手姿态伺服（举盾是状态，不是动作）；</li>
     *   <li>{@code ExtinguishServo#tick} —— 灭火器伺服（着火与"在不在打仗"无关）；</li>
     *   <li>{@code ActionExecutors#tickInFlight} —— 在途动作推进（引导/弹匣/蓄力/刀技起手）。</li>
     * </ol>
     * ★ 后两个是**同一类问题的第三、四个实例**（伺服被挂在"有条件才跑"的循环上）——
     * 判据见 docs/13 第 77 条那一句：**"她不打架的时候，这个东西还会跑吗？"**
     */
    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        var maid = event.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        long now = maid.level().getGameTime();
        lastTickAt = now;
        tickCount++;
        try {
            DirectiveHolder.tick(maid, now);
            DirectiveHolder.itemServo(maid);
            // ★★ ⑤ 索敌与"脱战"（第十七轮续，委托方第 1、3 条）：
            //   她**没目标时行为层根本不跑** ⇒ 索敌只能挂在这里；
            //   "脱战"（连续 5 秒无仇恨对象）也在这里判定（可选：脱战满 5 秒解除持续指令）。
            com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat
                    .tickTargeting(maid, now);
            com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat
                    .tickDisengage(maid, now);

            // ★★ ⑦ 召唤物"别打主人"修缮（第二十二轮，委托方实测：铁魔法召唤物会索敌女仆）。
            //   主因（她把自己的召唤物当目标 ⇒ 别的召唤物的"保护主人"行为把她当侵略者）
            //   已经在目标判据里堵掉了（`TaskPlayerLikeCombat#canAttackBase`）；
            //   这一步是**修缮存量**：那一瞬间已经被设成"目标是女仆"的召唤物，
            //   不会自己松手（它的目标行为不会因为我方判据变化而清目标）⇒ 一秒一次把它清掉。
            //   ★ 为什么放在这里：与索敌同一条纪律 —— **她不打架时这件事也要做**
            //     （她的召唤物在什么状态下都可能把目标指着她）。
            com.touhoulittlemad.fightlikeplayer.compat.maid.SummonAllyGuard.tick(maid, now);

            // ★★ ⑥ 在途动作（引导法术 / 枪械弹匣 / 蓄力射击 / 刀技起手）**必须每 tick 推进**。
            //   它是本项目"每 tick 要前进的东西不能挂在有条件才跑的循环上"的**第四个实例**：
            //   原来挂在"她有攻击目标"的行为里 ⇒ 目标一没就**停摆**（法术不结算、弹匣不再开火）。
            //   完成后回行为层开承诺门（门在那里）。
            if (com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors
                    .tickInFlight(maid, now)
                    == com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors
                            .Progress.COMPLETED) {
                com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat
                        .noteInFlightCompleted(maid);
            }
            // ★★ ③ 副手姿态伺服（举盾是"状态"不是"动作" ⇒ 与战斗无关）。
            //   它原来挂在同一个被门控的行为里 ⇒ 非战斗时"举盾"状态不维持，
            //   连 `stance_guard`（瞬间指令）都因此看不出效果。
            com.touhoulittlemad.fightlikeplayer.compat.behavior.OffhandStance.tick(maid);
            // ★★ ④ 灭火器伺服：她跟着你跑的时候着火是常态，而灭火**不能**要求"她正在打仗"。
            //   第二个参数 = "主循环是否在忙"（忙的时候不借主手，免得打断挥刀/引导）。
            com.touhoulittlemad.fightlikeplayer.compat.behavior.ExtinguishServo
                    .tick(maid, com.touhoulittlemad.fightlikeplayer.compat.exec
                            .ActionExecutors.isBusy(maid), now);
        } catch (RuntimeException | LinkageError e) {
            // ★ 驱动器绝不能把 tick 打崩（第三方 API 可能抛）
            FightLikePlayer.LOGGER.info("[FLP][directive] 指令驱动器出错（本 tick 跳过）：{}",
                    e.toString());
        }
    }
}
