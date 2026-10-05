package com.touhoulittlemad.fightlikeplayer.compat.exec.goety;

import com.Polarice3.Goety.api.items.magic.IWand;
import com.Polarice3.Goety.api.magic.IChargingSpell;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.magic.Spell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.Polarice3.Goety.utils.WandUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.decision.SpellChannelPolicy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Goety 法术的「通道」驱动</b> —— 让长按类法术真的能放出来。
 *
 * <h2>★★ 为什么必须有它（委托方 2026-10-01 实测「炽燃升腾聚晶无法释放，但会卡住其他行为」）</h2>
 * 根因是<b>我们把法术的执行路径走错了</b>。Goety 的法术有<b>三条完全不同的生命期</b>：
 * <pre>
 *   ① 瞬发（castDuration == 0）      ：SpellResult(…)                       ← 我们原来只走这条
 *   ② 定长引导（0 &lt; castDuration）   ：startSpell → 每 tick useSpell → stopSpell
 *   ③ 蓄力长按（IChargingSpell）      ：startSpell → 每 tick useSpell
 *                                      ＋ 前摇过后【每 Cooldown() tick 一次 SpellResult】
 *                                      → 松手 stopSpell
 * </pre>
 * 而 {@code mobSpellResult}（我们原来唯一的路径）只会调到 {@code SpellResult}。
 * ⇒ <b>把工作写在 {@code startSpell} 里的法术，（在我们这里）什么都不会发生</b>。
 * 实测样本：{@code FlameStrikeSpell}（= 炽燃升腾）的活儿全在
 * {@code startSpell}（{@code FlameStrikeSpell.java:68-146}）⇒ 我们调 {@code SpellResult}
 * 等于空转，却<b>回报了「做了」</b> ⇒ 决策层照扣代价、照进承诺 ⇒
 * 她反复选同一个法术、什么都不发生 —— 委托方看到的「卡住其他行为」。
 *
 * <h2>★★★ castDuration 不是「时长」—— 72000 是哨兵值（本轮修掉的最危险一处）</h2>
 * {@code IChargingSpell.defaultCastDuration() == 72000}（{@code IChargingSpell.java:17-19}，
 * 即 1 小时）语义是<b>「按到松手为止，硬上限一小时」</b>，<b>不是</b>「持续 72000 tick」。
 * 若按 {@code castDuration > 0 ⇒ 引导那么久} 直译，箭雨/光束这类法术会把女仆
 * <b>锁死 1000+ tick（一分钟）</b>——比原来的 bug 更糟。⇒ 本类把三条生命期分开，
 * 并对 ②③ 各自给出<b>有界</b>的引导长度（见 {@link Mode}）。
 *
 * <p>源码证据（Goety 2.5.56.5）：
 * <pre>
 *   FlameStrikeSpell.startSpell(...)  { int warmUp = castDuration - 10; …建火柱… }   // ② 的活儿
 *   ArrowRainSpell extends EverChargeSpell extends ChargingSpell implements IChargingSpell
 *   EverChargeSpell.Cooldown() == 0   ⇒ 前摇一过【每 tick】就一次 SpellResult（= 每 tick 一发）
 *   DarkWand.onUseTick:  CastTime==1 → startSpell；每 tick → useSpell；
 *                        蓄力类前摇过后 COOL++ ≥ Cooldown → MagicResults（→ SpellResult）
 *   DarkWand.releaseUsing → stopSpell
 * </pre>
 * ⇒ 本项目<b>自己实现那个驱动</b>（女仆没有物品输入路径，见 GoetyExecutors 的类注释）。
 *
 * <h2>★★ 为什么不能直接复用 {@code MagicResults}</h2>
 * {@code DarkWand.MagicResults} 的第一行是
 * {@code if (spell != null &amp;&amp; caster instanceof Player playerEntity)}，
 * 非玩家的 {@code else} 分支<b>只放白烟 + 灭火音，根本不施法</b>
 * （{@code DarkWand.java:762-845}）⇒ 蓄力类的「每一发」只能由我们直接调
 * {@code ISpell#SpellResult}（这正是 {@code mobSpellResult} 走的路，{@code Spell.java:49-61}）。
 *
 * <h2>★ 四条来自「调律师」的已验证陷阱（[docs/05 §5.2](../../../../../../../docs/05-许可与代码复用.md)）</h2>
 * <ol>
 *   <li><b>{@code Pose.CROUCHING} 会被当成潜行</b>：{@code isCrouching()} 为真
 *       ⇒ {@code Spell.isShifting(caster)} 为真 ⇒ <b>召唤类法术变成"召回/清除"分支</b>
 *       （42 个法术类受影响；{@code ghastly}/{@code illusion}/{@code vexing} 更狠：<b>直接杀掉自己的仆从</b>）。
 *       调律师的做法是在自己的实体上覆写 {@code isCrouching() → false}；
 *       我们<b>不能改 TLM 的实体</b> ⇒ 只能在施法前<b>强制解除蹲姿并复核</b>。</li>
 *   <li><b>{@code AbstractBeam} 靠 {@code isUsingItem()} 存活</b>：
 *       {@code AbstractBeam.tick()} 里 {@code if (itemBase && !isSpellCasting(owner)) discard()}，
 *       而 {@code isSpellCasting = isUsingItem() && IWand && 有聚晶}
 *       ⇒ 通道期间必须<b>持续满足</b>（自愈式 {@code startUsingItem}），
 *       否则光束刚生成就自毁。</li>
 *   <li><b>瞄准必须在 {@code startSpell} 之前</b>：{@code VoidRift}/{@code FlameStrike}/
 *       {@code AbyssalBeam}/{@code ChipRain} 在 {@code startSpell} 内部就沿视线 rayTrace
 *       ⇒ 朝向不对就<b>朝旧方向打偏</b>。⇒ 每 tick 都重新对准。</li>
 *   <li><b>第三方附属抛异常会崩服</b>：{@code conditionsMet}/{@code castDuration}/{@code CastingSound}
 *       由附属实现。⇒ 所有调用点 catch-all ⇒ ERROR + <b>运行期拉黑该法术</b> + 收尾复位。
 *       ★ 这正是委托方要的「检测到坏的就把这一步跳过、强行进入下一个循环」。</li>
 * </ol>
 *
 * <p>★ 有一件事我们<b>刻意不做</b>：灵魂（Soul Energy）消耗。玩家侧走
 * {@code SEHelper}，而 <b>Goety 自己的仆从</b>走 {@code mobSpellResult} —— 同样不查灵魂。
 * 女仆与后者同构 ⇒ 不引入灵魂账本（W23 的缺口之一，已在 docs 登记）。
 */
public final class GoetyChannel {

    private GoetyChannel() {
    }

    /**
     * ★★ <b>法术的生命期形态</b> —— 本类最要紧的判据（旧实现只有"是/不是通道"两分，
     * 于是把 72000 当成了时长）。
     */
    public enum Mode {
        /** 瞬发：只调一次 {@code SpellResult}（走 {@code mobSpellResult}）。 */
        INSTANT,
        /** 定长引导：活儿在 {@code startSpell}/{@code useSpell}，长度 = 法术自己的 {@code castDuration}。 */
        CHANNEL,
        /** 蓄力长按：{@code castDuration} 是 72000 哨兵 ⇒ <b>长度由我们自己定</b>，前摇后每 tick/每冷却一发。 */
        CHARGING
    }

    /**
     * ★ 大于它就<b>不信任</b> {@code castDuration}（视为蓄力长按类，长度我们自己定）。
     * <p>★ 判据本体在纯逻辑层 {@link SpellChannelPolicy#MAX_SANE_CHANNEL}
     * —— 那边可离线断言，compat 层只转发（本项目踩过两次的坑：判据写在 compat 里就没法自测）。
     */
    private static final int MAX_SANE_CHANNEL = SpellChannelPolicy.MAX_SANE_CHANNEL;

    /**
     * ★ 蓄力长按类的引导长度 = {@code castUp + 这个余量}，并夹在 {@code [20, 60]} 内。
     *
     * <p>为什么必须有界：蓄力类的 {@code castDuration} 是 72000（哨兵），
     * 而 {@code EverChargeSpell.Cooldown() == 0} ⇒ 前摇一过<b>每 tick 一发</b>
     * ⇒ 长度就是"发数"。玩家可以按满 100 发（{@code ArrowRainDuration}），
     * 女仆要的是<b>一轮有效输出</b>而不是站桩一分钟 ⇒ 取 20 tick 余量。
     * 这是我们的<b>重评估周期</b>，不是源码常量（源码里没有这个量）。
     */
    /**
     * ★ 长按类引导长度的上下界 —— 与算术一起在纯逻辑层
     * （{@link SpellChannelPolicy#MIN_HOLD} / {@link SpellChannelPolicy#MAX_HOLD}）。
     */
    private static final int CHARGING_MIN_HOLD = SpellChannelPolicy.MIN_HOLD;
    private static final int CHARGING_MAX_HOLD = SpellChannelPolicy.MAX_HOLD;

    /** 一次通道的状态。 */
    private static final class Channel {
        final ISpell spell;
        final SpellStat stats;
        final Mode mode;
        /** 我们自己认定的本次引导总长（tick）。 */
        final int hold;
        /** 蓄力/长按类的前摇（{@code castUp}）；非蓄力类为 0。 */
        final int warmUp;
        /** 硬上限：即使上面算错（或第三方改了口径），也不要永远卡住。 */
        final int hardCap;
        int castTime;
        /** 蓄力类的「距上一发过了几 tick」（对应 DarkWand 写在法杖 NBT 里的 COOL）。 */
        int cool;
        /** 蓄力类已发几发（对应法杖 NBT 里的 SHOTS）。 */
        int shots;
        /** 起手时杖里装的是哪颗聚晶（法术类名）—— 用来发现"她中途换了聚晶"。 */
        final String focusClassAtStart;
        /** 是否已经派发过 {@code SpellResult}（定长引导类只在**自然收尾**时派发一次）。 */
        boolean fired;
        /** ★ 第十七轮：潜行跳发只报一次（蓄力类会反复走到那一句，否则刷屏）。 */
        boolean sneakWarned;
        /** ★ 起手时她有没有仇恨目标 —— 决定"目标没了"是否该收尾（自增益类本来就没有目标）。 */
        boolean hadTargetAtStart;
        /**
         * ★★ <b>上一次被推进的时刻</b>（世界时间）。
         * <p>用途：判定"这个通道是不是陈旧的（没人推进它）"—— 见 {@link #STALE_TICKS}。
         */
        long lastTickAt = Long.MIN_VALUE;

        Channel(ISpell spell, SpellStat stats, Mode mode, int hold, int warmUp) {
            this.spell = spell;
            this.stats = stats;
            this.mode = mode;
            this.hold = hold;
            this.warmUp = warmUp;
            this.focusClassAtStart = spell.getClass().getSimpleName();
            // ★ 鲁棒性：hold + 60 是"宽限"，用于兜住"某个法术的 useSpell 迟迟不收"
            this.hardCap = Math.min(20 * 60, hold + 60);
        }
    }

    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();

    /**
     * ★★ <b>通道的独立驱动器（与行为层解耦）</b> —— 委托方第十二轮实测「法杖还是切不出来」的根因。
     *
     * <h2>故障链（从委托方的日志一眼看出）</h2>
     * <pre>
     *   13:48:51 goety 引导起手：CorruptedBeamSpell（CHARGING·60 tick）
     *   13:48:51 [FLP] 正在引导法术 ⇒ 本 tick 不换手        ← 通道守卫挡住了换手
     *   13:49:03 goety 引导中断：法杖已不在主手              ← ★ 12 秒后才结束
     * </pre>
     * 通道每 tick 靠 {@code ActionExecutors.tickInFlight} 推进，而它只在
     * {@code PlayerLikeCombat.tick} 里被调用 —— <b>而行为层只在"有目标 + 战斗任务"时运行</b>。
     * ⇒ 目标一死（或一时没有目标）就<b>没人推进通道</b>：
     * <ol>
     *   <li>通道<b>永不结束</b> ⇒ {@code isChanneling} 恒为真；</li>
     *   <li>通道守卫（"正在引导法术 ⇒ 不换手"）于是<b>永久挡住换手</b>
     *       ⇒ <b>法杖再也换不上来</b>（委托方看到的"切不出来"）；</li>
     *   <li>12 秒后行为层偶然又跑了一次，才推进到"法杖不在手 ⇒ 中断"。</li>
     * </ol>
     *
     * <h2>两道修法</h2>
     * <ol>
     *   <li><b>独立驱动器</b>：本表记下"谁在引导"，由 {@code compat/event/SpellTickDriver}
     *       每服务器 tick 推进一次 —— 与行为层是否运行<b>无关</b>；</li>
     *   <li><b>陈旧判定</b>：{@link #isChanneling} 只在"最近 {@value #STALE_TICKS} tick 内
     *       真的被推进过"时才算在引导 ⇒ 即使驱动器也没跑到，守卫也不会永久卡住换手。</li>
     * </ol>
     */
    private static final Map<UUID, java.lang.ref.WeakReference<EntityMaid>> OWNERS = new HashMap<>();

    /**
     * 超过这么久没被推进 ⇒ 认为通道已陈旧（不再算"正在引导"）。
     * <p>取 40 tick（2 秒）：远大于"正常每 tick 推进"的间隔，又远小于用户能忍受的卡顿。
     */
    private static final int STALE_TICKS = 40;

    /**
     * ★★ <b>运行期黑名单</b>：抛过异常的法术在本次进程内不再尝试。
     *
     * <p>来自调律师的经验（"运行期自愈拉黑"）：第三方附属的法术实现可能有 bug，
     * 而<b>一次异常不该让女仆每次都去撞同一面墙</b>。
     * ⇒ 拉黑 + 记录原因（可查），并把聚晶归还（我们不动背包，所以只需不再选它）。
     */
    private static final Map<String, String> BLACKLIST = new HashMap<>();

    /** 通道推进结果。 */
    public enum Progress {
        /** 她没在引导法术。 */
        NONE,
        /** 正在引导（本 tick 已推进一次）。 */
        RUNNING,
        /** 引导结束（正常收尾 / 被打断 / 出错）—— 调用方应回报完成。 */
        DONE
    }

    // ───────────────────────── 查询 ─────────────────────────

    public static boolean isChanneling(EntityMaid maid) {
        Channel c = CHANNELS.get(maid.getUUID());
        if (c == null) {
            return false;
        }
        // ★★ 陈旧判定（第十二轮）：只在"最近真的被推进过"时才算在引导。
        //    否则一个没人推进的通道会永久挡住换手 —— 那正是"法杖切不出来"的根因。
        long now = maid.level().getGameTime();
        if (c.lastTickAt != Long.MIN_VALUE && now - c.lastTickAt > STALE_TICKS) {
            FightLikePlayer.LOGGER.info(
                    "[FLP] goety 通道已陈旧（{} tick 没被推进，法术 {}）⇒ 当作已结束，放行换手",
                    now - c.lastTickAt, c.spell.getClass().getSimpleName());
            abort(maid);
            return false;
        }
        return true;
    }

    /** ★ 正在引导的女仆（供独立驱动器遍历；已清掉死掉/卸载的）。 */
    public static java.util.List<EntityMaid> channelOwners() {
        java.util.List<EntityMaid> out = new java.util.ArrayList<>(OWNERS.size());
        var it = OWNERS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            EntityMaid maid = e.getValue().get();
            if (maid == null || !maid.isAlive() || maid.isRemoved()) {
                it.remove();
                CHANNELS.remove(e.getKey());
                continue;
            }
            out.add(maid);
        }
        return out;
    }

    public static boolean isBlacklisted(String spellName) {
        return BLACKLIST.containsKey(spellName);
    }

    /** 黑名单快照（诊断用）。 */
    public static Map<String, String> blacklist() {
        return Map.copyOf(BLACKLIST);
    }

    /** 当前引导到第几 tick（-1 = 没在引导）。 */
    public static int castTimeOf(EntityMaid maid) {
        Channel c = CHANNELS.get(maid.getUUID());
        return c == null ? -1 : c.castTime;
    }

    // ───────────────────────── 判形态 / 起手 ─────────────────────────

    /**
     * ★★ <b>判定这个法术的生命期形态</b>（见 {@link Mode}）。
     *
     * <p>判据本体由附属实现（{@code castDuration}），因此<b>整段 catch-all</b>。
     *
     * @return 形态；<b>{@code null} = 判不出来（已拉黑）</b> ⇒ 调用方应当成"没做出来"
     */
    public static Mode modeOf(EntityMaid maid, ItemStack wand, ISpell spell) {
        if (spell instanceof IChargingSpell) {
            // ★★★ 蓄力长按类：castDuration 恒为 72000（哨兵），不能当长度用
            return Mode.CHARGING;
        }
        int duration;
        try {
            duration = spell.castDuration(maid, wand);
        } catch (RuntimeException e) {
            blacklist(spell, "castDuration 抛异常：" + e);
            return null;
        }
        if (duration <= 0) {
            return Mode.INSTANT;
        }
        if (duration > MAX_SANE_CHANNEL) {
            // ★ 不信任这么大的"时长"（只可能是哨兵/被改坏了）⇒ 按长按类自己管长度
            if (FightLikePlayer.LOGGER.isDebugEnabled()) {
                FightLikePlayer.LOGGER.debug(
                        "[FLP] goety 法术 {} 的 castDuration={} 超出可信上限 {} ⇒ 按「蓄力长按类」处理（长度我们自己定）",
                        spell.getClass().getSimpleName(), duration, MAX_SANE_CHANNEL);
            }
            return Mode.CHARGING;
        }
        return Mode.CHANNEL;
    }

    /**
     * 这个法术是不是"需要引导"（{@code startSpell}/{@code useSpell}/{@code stopSpell}）。
     *
     * <p>保留旧签名（{@code GoetyExecutors} 与自测在用）；判据已改为 {@link #modeOf}。
     */
    public static boolean isChannel(EntityMaid maid, ItemStack wand, ISpell spell) {
        Mode m = modeOf(maid, wand, spell);
        return m == Mode.CHANNEL || m == Mode.CHARGING;
    }

    /**
     * ★ 蓄力长按类的引导长度 = {@code castUp + 持续预算}，并夹在 {@code [20, 200]} 内。
     *
     * <h2>★★ 为什么要"持续预算"而不是原来那个固定 +20（委托方第九轮实测修正）</h2>
     * 委托方实测：「女仆释放**持续时间长的法术**时，比如**腐化、水蛭**聚晶，可以成功释放」——
     * 而这两颗的源码默认值恰好说明问题：
     * <pre>
     *   CorruptionChargeUp = 0    CorruptionDuration = 0（0 = 【无限】）
     *   LeechingChargeUp   = 0    LeechingDuration   = 0（0 = 【无限】）
     *   EverChargeSpell.Cooldown() == 0  ⇒ 前摇一过【每 tick 一发】
     * </pre>
     * 玩家侧的语义是「**按住不放就一直放，松手才停**」⇒ 长度是玩家决定的，
     * 而原来是 {@code castUp+20}（这两颗就是 20 tick = 1 秒）⇒ 刚放出光束就收，
     * 观感就是"放了但立刻停/反复重放"。
     * <p>⇒ 长度改由【配置旋钮】{@code goety.sustainTicks}（默认 60）决定，
     * 并且**目标一死就立刻收**（见 {@link #tick}）—— 那才是"持续时间长"的正确语义：
     * <b>不是固定时长，而是"情况需要多久就多久，但有上限"</b>。
     */
    private static int sustainBudget() {
        try {
            return com.touhoulittlemad.fightlikeplayer.config.FlpConfig
                    .get(com.touhoulittlemad.fightlikeplayer.config.FlpConfig.GOETY_SUSTAIN_TICKS, 60);
        } catch (Throwable t) {
            return 60;                       // 配置读不到也不能让施法挂掉
        }
    }

    /** 蓄力长按类的引导长度（我们的重评估周期）—— 算术本体在纯逻辑层。 */
    private static int chargingHold(int castUp) {
        return SpellChannelPolicy.chargingHold(castUp, sustainBudget());
    }

    /**
     * 起手一个通道。
     *
     * @param mode 由 {@link #modeOf} 判定（调用方已判过，避免二次求值抛异常）
     * @return 是否成功起手（false ⇒ 调用方应当成"没做出来"）
     */
    public static boolean begin(EntityMaid maid, ItemStack wand, ISpell spell, Mode mode, SpellStat stats) {
        if (!(maid.level() instanceof ServerLevel server) || mode == null || mode == Mode.INSTANT) {
            return false;
        }
        String name = spell.getClass().getSimpleName();
        if (BLACKLIST.containsKey(name)) {
            return false;
        }

        // ── 长度：定长类信法术，长按类信我们自己 ──
        int hold;
        int warmUp = 0;
        if (mode == Mode.CHARGING) {
            try {
                warmUp = spell instanceof IChargingSpell c ? c.castUp(maid, wand) : 0;
            } catch (RuntimeException e) {
                blacklist(spell, "castUp 抛异常：" + e);
                return false;
            }
            hold = chargingHold(warmUp);
        } else {
            try {
                hold = Math.max(1, spell.castDuration(maid, wand));
            } catch (RuntimeException e) {
                blacklist(spell, "castDuration 抛异常：" + e);
                return false;
            }
        }
        Channel ch = new Channel(spell, stats, mode, hold, warmUp);
        var t0 = maid.getTarget();
        ch.hadTargetAtStart = t0 != null && t0.isAlive();

        // ★★ 陷阱 1（调律师的坑）：把蹲姿解除掉 —— 否则 isShifting 为真，
        //    召唤类法术会走"清除/召回"分支，ghastly/illusion/vexing 甚至直接杀掉自己的仆从。
        boolean wasShifting = clearShifting(maid);

        // ★ 陷阱 3：startSpell 之前必须重新对准（rayTrace 在它内部）
        com.touhoulittlemad.fightlikeplayer.compat.exec.AimHelper.faceTarget(maid);

        // ★★ 陷阱 2（`isUsingItem`）—— 第九轮改法见类注释：**我们不调 startUsingItem**。
        //    理由不是"不需要"，而是"代价大于收益"：见 tryStartUsing 的说明（已删）。
        //    光束靠 `itemBase=false` 存活（`relaxBeams`），因此不再需要这个状态。

        try {
            spell.startSpell(server, maid, wand, stats);
        } catch (RuntimeException e) {
            blacklist(spell, "startSpell 抛异常：" + e);
            return false;
        }
        ch.lastTickAt = maid.level().getGameTime();
        CHANNELS.put(maid.getUUID(), ch);
        OWNERS.put(maid.getUUID(), new java.lang.ref.WeakReference<>(maid));
        if (FightLikePlayer.LOGGER.isDebugEnabled()) {
            FightLikePlayer.LOGGER.debug("[FLP] goety 引导起手：{}（{}·{} tick{}·前摇 {}）", name, mode, ch.hold,
                    wasShifting ? "，★ 曾处于蹲姿已解除" : "", warmUp);
        }
        return true;
    }

    // ───────────────────────── 每 tick ─────────────────────────

    /**
     * 推进通道一 tick。<b>必须由主线程每 tick 调一次</b>（`ActionExecutors#tickInFlight` 负责）。
     */
    public static Progress tick(EntityMaid maid) {
        Channel ch = CHANNELS.get(maid.getUUID());
        if (ch == null) {
            return Progress.NONE;
        }
        if (!(maid.level() instanceof ServerLevel server)) {
            abort(maid);
            return Progress.DONE;
        }
        // 法杖不在手上了（被换走 / 被打落）⇒ 收尾
        ItemStack wand = maid.getMainHandItem();
        if (!(wand.getItem() instanceof IWand)) {
            FightLikePlayer.LOGGER.info("[FLP] goety 引导中断：法杖已不在主手");
            abort(maid);
            return Progress.DONE;
        }

        // ★★ 聚晶被换掉了 ⇒ 立刻收尾（委托方第九轮实测：「主动为其切换聚晶后，
        //    一开始释放的是旧的那个（哪怕不在背包中）」）。
        //    根因：通道握着的是**起手时那个 ISpell 对象**，而聚晶已经被换成别的
        //    ⇒ 我们会继续用旧法术驱动（甚至它的聚晶已经不在她身上）。
        //    判据直接看"杖里现在装的是哪颗" —— 与起手时不一致就收。
        ISpell nowSpell = spellInWand(wand);
        if (nowSpell != null && !nowSpell.getClass().getSimpleName().equals(ch.focusClassAtStart)) {
            FightLikePlayer.LOGGER.info("[FLP] goety 引导收尾：杖内聚晶已被换成 {}（起手时是 {}）⇒ 停止旧法术",
                    nowSpell.getClass().getSimpleName(), ch.focusClassAtStart);
            finish(maid, ch, wand, true);
            return Progress.DONE;
        }

        ch.castTime++;
        ch.lastTickAt = server.getGameTime();     // ★ 陈旧判定用（见 STALE_TICKS）

        // ★★ 陷阱 1（每 tick 复核）—— 第十七轮加固，见下面那段说明。
        //    旧版只有"清"、没有"查"：清掉之后到 useSpell 之间她完全可能**又蹲下**
        //    —— TLM 的 `MaidClimbTask` 每 tick 都在写 `setShiftKeyDown`，
        //       而 `Spell#isShifting`（`Spell.java:71-73`）读的正是
        //       `isCrouching() || isShiftKeyDown()` 且"手上有杖"
        //    ⇒ 那一拍 useSpell 就走"清除/召回"分支，ghastly / illusion / vexing
        //      **直接杀掉她自己的全部仆从**（委托方第 4 条的"有时还是会触发"）。
        //    ⇒ 现在**清完立刻用同一个判据（法术自己的 isShifting）复核**，不过就中止本次引导。
        boolean wasShifting = clearShifting(maid);
        if (shiftingNow(maid, ch)) {
            FightLikePlayer.LOGGER.info("[FLP] ★ goety 引导中止：{} 的中途她仍在潜行"
                            + "（潜行会把这个法术换成清除/召回分支，会杀掉自己的仆从）"
                            + "⇒ 本次不释放{}",
                    ch.spell.getClass().getSimpleName(), wasShifting ? "（已尝试清蹲姿）" : "");
            finish(maid, ch, wand, true);
            return Progress.DONE;
        }
        // ★ 陷阱 3（每 tick）：持续对准
        com.touhoulittlemad.fightlikeplayer.compat.exec.AimHelper.faceTarget(maid);

        try {
            // ★ 玩家侧的调用序：CastTime==1 时 startSpell（已在 begin 里做），此后每 tick useSpell
            ch.spell.useSpell(server, maid, wand, ch.castTime, ch.stats);
            if (ch.mode == Mode.CHARGING) {
                // ★ 蓄力类：前摇过后按"每 Cooldown() 一发"派发 SpellResult（这是它们真正的活儿）
                chargeCadence(server, maid, wand, ch);
            }
        } catch (RuntimeException e) {
            blacklist(ch.spell, "useSpell/SpellResult 抛异常：" + e);
            abort(maid);
            return Progress.DONE;
        }

        // ★★ 目标没了 ⇒ 立刻收尾（**所有形态**，不只是蓄力类）。
        //    委托方第九轮实测：「怪死了也不会停止」—— 原因是收尾判据只覆盖了蓄力类，
        //    而定长引导类（光束/持续类）会一直引导到 hold 用完为止。
        if (targetLost(maid, ch)) {
            FightLikePlayer.LOGGER.info("[FLP] goety 引导收尾：目标已消失/死亡 ⇒ 停止 {}", ch.focusClassAtStart);
            finish(maid, ch, wand, true);
            return Progress.DONE;
        }

        if (ch.castTime >= ch.hold) {
            // ★★★ 定长引导类的"开火"发生在【自然收尾】那一刻 —— 这是本轮修掉的最大一处遗漏。
            //    玩家侧：use duration 走完 ⇒ `finishUsingItem` ⇒ `MagicResults` ⇒ **`SpellResult`**
            //    （`DarkWand.java:549-568`）。而我们此前在 CHANNEL 分支里**从不调 SpellResult**，
            //    只调 startSpell/useSpell ⇒ 委托方实测的**熔岩炸弹**（`LavaballSpell`，
            //    `castDuration = 40`、活儿全在 `SpellResult`）**永远不发射**，
            //    观感就是"释放到一半被打断、然后重新放"。
            //    ★ 只在**自然收尾**派发：被打断 / 目标没了 / 硬上限 / 换聚晶 ⇒ 不派发
            //      （与玩家"提前松手 = 不放出去"一致）。
            if (ch.mode == Mode.CHANNEL && !ch.fired) {
                fire(server, maid, wand, ch);
            }
            finish(maid, ch, wand, true);
            return Progress.DONE;
        }
        if (ch.castTime >= ch.hardCap) {
            // ★ 鲁棒性：某个法术的 useSpell 迟迟不收 —— 强制收尾，别永远卡住
            FightLikePlayer.LOGGER.info("[FLP] goety 引导超过硬上限（{} tick）⇒ 强制收尾：{}",
                    ch.hardCap, ch.spell.getClass().getSimpleName());
            finish(maid, ch, wand, false);
            return Progress.DONE;
        }
        return Progress.RUNNING;
    }

    /** 杖里现在装着的那颗法术（拿不到 ⇒ null）。 */
    private static ISpell spellInWand(ItemStack wand) {
        try {
            if (wand.getItem() instanceof IWand iwand) {
                return iwand.getSpell(wand);
            }
        } catch (RuntimeException ignore) {
            // 附属实现可能抛 —— 这里只用于"换聚晶了吗"，判不出来就当没换
        }
        return null;
    }

    /**
     * 目标是不是已经没了（死了 / 消失了 / 被清空）。
     *
     * <p>★ 只有<b>本来就有目标</b>的引导才适用：光环/自增益类引导（护盾、飞行、念力）
     * 不需要目标，它们的"目标"是空的，此时**不判**（否则它们会被立刻收尾）。
     * ⇒ 判据是"起手时看得到目标，而现在没了"。
     */
    private static boolean targetLost(EntityMaid maid, Channel ch) {
        var t = maid.getTarget();
        if (t == null || !t.isAlive()) {
            // 她的仇恨目标为空 —— 对"需要目标的法术"来说就是打空了。
            // 用"这颗法术是不是需要目标"来兜：蓄力类的前摇过后必须要目标；
            // 定长引导类则看它有没有真的在打人（由第三人称实现决定），这里保守一点：
            // 只要**曾经有目标**（进入引导时非空）且现在没了，就收尾。
            return ch.hadTargetAtStart;
        }
        return false;
    }

    /**
     * ★★ <b>蓄力长按类的"每一发"</b> —— 逐行对应 {@code DarkWand.onUseTick} 的 COOL/SHOTS 逻辑。
     *
     * <p>玩家侧那段是：
     * <pre>
     *   if (CastTime &gt;= castUp || castUp &lt;= 0) {
     *       COOL++;
     *       if (COOL &gt;= Cooldown(caster, stack, SHOTS)) { COOL = 0; if (shotsNumber &gt; 0) SHOTS++; MagicResults(...); }
     *   }
     * </pre>
     * 我们只把 {@code MagicResults} 换成 {@code SpellResult}
     * （前者对非玩家只放白烟，见类注释）。
     *
     * <p>★ {@code COOL} 与 {@code SHOTS} 在玩家侧存在<b>法杖的 NBT</b> 里
     * （也是"CastTime"键的来源）。我们不写别的模组的 NBT，
     * ⇒ 这两个计数放在 {@link Channel} 里，随本次引导一起生灭（= 玩家"按下到松手"一轮）。
     */
    private static void chargeCadence(ServerLevel server, EntityMaid maid, ItemStack wand, Channel ch) {
        boolean pastWarmUp = ch.warmUp <= 0 || ch.castTime >= ch.warmUp;
        if (!pastWarmUp) {
            return;
        }
        ch.cool++;
        int cooldown = Math.max(1, cooldownOf(maid, wand, ch));
        if (ch.cool < cooldown) {
            return;
        }
        ch.cool = 0;
        if (shotsNumberOf(maid, wand, ch) > 0) {
            ch.shots++;
        }
        // ★ 这一句才是"蓄力法术真的放出来"的落点（抛异常由 tick 的 catch 处理 ⇒ 拉黑 + 收尾）
        fire(server, maid, wand, ch);
    }

    /**
     * ★★ <b>派发一次 {@code SpellResult}（= 蓄力类的"一发"、定长引导类的"最后一下"）</b>。
     *
     * <p>两件事必须一起做，缺一不可：
     * <ol>
     *   <li>调 {@code SpellResult}（活儿在它里面 —— 例如 {@code LavaballSpell} /
     *       {@code ArrowRainSpell} / {@code CorruptedBeamSpell}）；</li>
     *   <li>★★ <b>把刚生成的"绑定物品"的法术实体松绑</b>（{@code relaxBeams}）——
     *       见那里的说明：这是"大量白烟"与"光束活不下来"两难的破解点。</li>
     * </ol>
     */
    private static void fire(ServerLevel server, EntityMaid maid, ItemStack wand, Channel ch) {
        // ★★ 第十七轮：**最后一道闸**。`SpellResult` 是"真正干活"的那一半，
        //    而潜行变体的分支判定在法术内部（同一个 `isShifting`）⇒
        //    这里若在潜行，宁可这一发不放（不可逆的杀仆从不能靠"上面大概查过了"）。
        clearShifting(maid);
        if (shiftingNow(maid, ch)) {
            ch.fired = true;                    // 不再重试（否则蓄力类每 Cooldown 一次都撞这里）
            if (!ch.sneakWarned) {
                ch.sneakWarned = true;
                FightLikePlayer.LOGGER.info("[FLP] ★ goety 跳过这一发 {}：派发时她仍在潜行"
                        + "（潜行变体 = 清除/召回，会杀掉自己的仆从）", ch.spell.getClass().getSimpleName());
            }
            return;
        }
        ch.fired = true;
        ch.spell.SpellResult(server, maid, wand, ch.stats);
        relaxBeams(server, maid);
    }

    /**
     * ★★ <b>把"以物品使用为存活条件"的光束松绑</b>（{@code itemBase → false}）。
     *
     * <h2>为什么必须有这一步（第九轮的核心取舍）</h2>
     * {@code AbstractBeam.tick()}（{@code AbstractBeam.java:96}）：
     * <pre>
     *   if (owner == null || !owner.isAlive() || (this.itemBase &amp;&amp; !MobUtil.isSpellCasting(owner))) discard();
     * </pre>
     * 而 {@code CorruptedBeamSpell.SpellResult} 建光束时是 {@code setItemBase(true)}（`:70`）
     * ⇒ <b>不满足 {@code isSpellCasting} 就立刻自毁</b>；而
     * {@code isSpellCasting = isUsingItem() &amp;&amp; 杖 &amp;&amp; 有聚晶}
     * ⇒ 要光束活着就得让女仆"正在使用物品"。
     *
     * <p>★ <b>但 `startUsingItem` 正是"大量白烟"的根因</b>（委托方第九轮实测）：
     * 法杖的 {@code getUseDuration} 读的是 NBT 里的「Cast Time」（{@code DarkWand.java:535-541}），
     * 而 <b>Mob 的持有物不跑 {@code inventoryTick}</b> ⇒ 那个键不存在 ⇒ 使用时长为 0
     * ⇒ 使用者<b>当 tick 就走完</b> ⇒ {@code finishUsingItem} → {@code MagicResults}
     * → 非玩家分支 → <b>{@code failParticles}（10~44 个 CLOUD）+ 灭火音</b>。
     * 我们每 tick 都会补一次 {@code startUsingItem} ⇒ <b>每 tick 冒一团白烟</b>。
     * （这正是 [docs/05 §5.2](../../../../../../../docs/05-许可与代码复用.md) 记录的那条白烟链，
     * 而我此前判断"我们绕开了物品路径所以不需要自备惰性法杖（W35）"——
     * **对法术效果是对的，对这条副作用是错的**。）
     *
     * <p>⇒ 两条都不让步的做法：<b>不碰 {@code startUsingItem}，改为把光束的存活条件改掉。</b>
     * {@code AbstractBeam#setItemBase} 是 public ⇒ 松绑后光束按<b>自己的生命周期</b>存活，
     * 不再依赖"女仆正在使用物品"；而收尾时我们显式 {@code discard}（{@link #discardBeams}）
     * ⇒ <b>既没有白烟，也不会漏掉"该停就停"。</b>
     */
    private static void relaxBeams(ServerLevel server, EntityMaid maid) {
        try {
            for (var beam : server.getEntitiesOfClass(
                    com.Polarice3.Goety.common.entities.projectiles.AbstractBeam.class,
                    maid.getBoundingBox().inflate(3.0D),
                    b -> b.getOwner() == maid)) {
                beam.setItemBase(false);
            }
        } catch (Throwable t) {
            // 附属换了实体类 / 没有光束 ⇒ 什么都不用做（绝不让它影响施法）
        }
    }

    /** ★ 收尾时把她的光束清掉（对应玩家"松手 ⇒ 法术结束"）。 */
    private static void discardBeams(ServerLevel server, EntityMaid maid) {
        try {
            for (var beam : server.getEntitiesOfClass(
                    com.Polarice3.Goety.common.entities.projectiles.AbstractBeam.class,
                    maid.getBoundingBox().inflate(4.0D),
                    b -> b.getOwner() == maid)) {
                beam.discard();
            }
        } catch (Throwable t) {
            // 同上：清不掉也不影响别的
        }
    }

    private static int cooldownOf(EntityMaid maid, ItemStack wand, Channel ch) {
        try {
            if (ch.spell instanceof IChargingSpell c) {
                return c.Cooldown(maid, wand, ch.shots);
            }
        } catch (RuntimeException ignore) {
            // 附属算不出冷却 ⇒ 按 0（= 每 tick 一发，与 EverChargeSpell 同口径）
        }
        return 0;
    }

    private static int shotsNumberOf(EntityMaid maid, ItemStack wand, Channel ch) {
        try {
            if (ch.spell instanceof IChargingSpell c) {
                return c.shotsNumber(maid, wand);
            }
        } catch (RuntimeException ignore) {
            // 同上
        }
        return 0;
    }

    /** 正常收尾（含 {@code stopSpell}）。 */
    private static void finish(EntityMaid maid, Channel ch, ItemStack wand, boolean callStop) {
        CHANNELS.remove(maid.getUUID());
        OWNERS.remove(maid.getUUID());
        if (callStop) {
            try {
                // ★ 调律师的缺口清单里写着"stopSpell 只在 interrupt 调" —— 我们**每次都调**
                ch.spell.stopSpell((ServerLevel) maid.level(), maid, wand,
                        IWand.getFocus(wand), ch.castTime, ch.stats);
            } catch (RuntimeException e) {
                blacklist(ch.spell, "stopSpell 抛异常：" + e);
            }
        }
        // ★ 收尾 = 玩家松手 ⇒ 她的光束该消失了（否则屏幕上会留一道"幽灵光束"）
        if (maid.level() instanceof ServerLevel server) {
            discardBeams(server, maid);
        }
        if (FightLikePlayer.LOGGER.isDebugEnabled()) {
            FightLikePlayer.LOGGER.debug("[FLP] goety 引导收尾：{}（{} tick·{} 发{}）",
                    ch.spell.getClass().getSimpleName(), ch.castTime, ch.shots,
                    ch.fired ? "" : "·未派发");
        }
    }

    /** 中断（被打断 / 目标没了 / 出错）—— 也要调 {@code stopSpell} 并清掉光束。 */
    public static void abort(EntityMaid maid) {
        Channel ch = CHANNELS.remove(maid.getUUID());
        OWNERS.remove(maid.getUUID());
        if (ch == null) {
            return;
        }
        ItemStack wand = maid.getMainHandItem();
        if (wand.getItem() instanceof IWand && maid.level() instanceof ServerLevel server) {
            try {
                ch.spell.stopSpell(server, maid, wand, IWand.getFocus(wand), ch.castTime, ch.stats);
            } catch (RuntimeException e) {
                blacklist(ch.spell, "stopSpell(中断) 抛异常：" + e);
            }
            discardBeams(server, maid);
        }
    }

    /** 脱战 / 卸载时清干净。 */
    public static void forget(UUID maidId) {
        CHANNELS.remove(maidId);
        OWNERS.remove(maidId);
    }

    // ───────────────────────── 三个小工具（每条都对应一个已验证的坑） ─────────────────────────

    /**
     * ★★ <b>解除"潜行"</b> —— 对应调律师的坑 1。
     *
     * <p>为什么不能像调律师那样覆写 {@code isCrouching()}：那是<b>他们自己的实体</b>，
     * 而 {@code EntityMaid} 是 TLM 的类，我们改不了。
     * ⇒ 只能在<b>施法前把姿态清掉</b>，并<b>复核</b>（复核结果会写日志，所以"没清掉"看得见）。
     *
     * @return 当时是否处于潜行/蹲姿（调用方据此打日志）
     */
    public static boolean clearShifting(EntityMaid maid) {
        boolean was = false;
        try {
            if (maid.isCrouching()) {
                was = true;
                maid.setPose(net.minecraft.world.entity.Pose.STANDING);
            }
            if (maid.isShiftKeyDown()) {
                was = true;
                maid.setShiftKeyDown(false);          // TLM 的 MaidClimbTask 也会写这个 flag
            }
        } catch (RuntimeException ignore) {
            // 清不掉也不能让施法挂掉；下一 tick 会再试一次
        }
        return was;
    }

    /**
     * ★★ <b>她此刻是否处于"潜行施法"状态</b> —— 用<b>法术自己</b>的判据复核（第十七轮）。
     *
     * <p>为什么要复核而不是"清过就算"：{@code Spell#isShifting(LivingEntity)} 的展开是
     * <pre>
     *   (caster.isCrouching() || caster.isShiftKeyDown()) && !WandUtil.findWand(caster).isEmpty()
     * </pre>
     * （{@code javap -c com.Polarice3.Goety.common.magic.Spell} 逐条核对过：
     * {@code m_6047_} = {@code isCrouching}、{@code m_6144_} = {@code isShiftKeyDown}、
     * 后半段是 {@code WandUtil.findWand}）—— 它是<b>每次调用时现算</b>的，
     * 而 {@code EntityMaid} 的姿态由 TLM 自己的任务每 tick 改写
     * ⇒ 我们<b>清完到用之间</b>完全可能又被改回潜行。
     *
     * <p>★ 判不出来时 <b>fail-closed</b>（当作"在潜行"）：宁可这一发不放，
     * 也不要去赌一次不可逆的"杀掉自己所有仆从"。
     */
    private static boolean shiftingNow(EntityMaid maid, Channel ch) {
        if (!(ch.spell instanceof Spell sp)) {
            return false;                       // 不是 Spell（理论上不会）⇒ 没有潜行分支
        }
        try {
            return sp.isShifting(maid);
        } catch (RuntimeException e) {
            FightLikePlayer.LOGGER.info("[FLP] ★ goety 的 isShifting 判定异常 ⇒ 按「潜行」处理"
                    + "（fail-closed：宁可这一发不放，也不误入杀仆从的分支）：{}", e.toString());
            return true;
        }
    }

    // ────────────────── ★★ 为什么本类【没有】startUsingItem / stopUsing ──────────────────

    /*
     * 委托方第九轮实测：「女仆释放持续时间长的法术时（腐化、水蛭聚晶），可以成功释放，
     * 但是会有**大量白烟**。」
     *
     * 根因（读源码 + 对账调律师的四环链，见 docs/05 §5.2）：
     *
     *   startUsingItem(MAIN_HAND)
     *     ↓ 法杖的 getUseDuration 读 NBT「Cast Time」（DarkWand.java:535-541）
     *     ↓ 而 Mob 的持有物【不跑 inventoryTick】⇒ 该键不存在 ⇒ 使用时长为 0
     *   ⇒ 同一 tick 就走完 ⇒ finishUsingItem ⇒ MagicResults
     *     ↓ MagicResults 整段在 caster instanceof Player 里
     *   ⇒ 非玩家掉进 else ⇒ failParticles：随机 10~44 个 CLOUD（白烟）+ 灭火音
     *
     * 而我们**每 tick 都会补一次** startUsingItem ⇒ 每 tick 冒一团白烟 —— 正是看到的现象。
     * ★ 我此前判定「我们绕开了物品路径，所以不需要自备惰性 IWand（W35）」：
     *   **对法术效果是对的，对这条副作用是错的。**
     *
     * ⇒ 修法不是自备惰性法杖（调律师的路线，要额外的物品与 NBT 搬迁），
     *   而是把「为什么需要 isUsingItem」这件事本身去掉：
     *   唯一需要它的是光束实体（AbstractBeam 的 itemBase 判据），
     *   而那个标志位有 public setter ⇒ relaxBeams 松绑 + discardBeams 收尾。
     *   **副作用消失，光束照常存活。**
     *
     * ★ 判据（已写进 docs/13）：**"这个 API 是我需要的，还是它的副作用是我能承受的？"**
     *   —— 之前只问了后者的一半。
     */



    /** 拉黑一个法术（本次进程内不再尝试）+ 记原因。 */
    private static void blacklist(ISpell spell, String reason) {
        String name = spell.getClass().getSimpleName();
        if (BLACKLIST.putIfAbsent(name, reason) == null) {
            FightLikePlayer.LOGGER.error(
                    "[FLP] ★ goety 法术 {} 出错 ⇒ 【本次进程内拉黑】（{}）。"
                            + "她会跳过它、继续用别的 —— 见 /flp whyfull 或本行日志",
                    name, reason);
        }
    }

    /** 给 {@code GoetyExecutors} 用的统计（诊断）。 */
    public static String summary() {
        return "goety 引导：在引导 " + CHANNELS.size() + " 个 · 已拉黑 " + BLACKLIST.size() + " 个法术";
    }

    /** 她当前在引导什么（诊断：`/flp think last` 之类的输出里用得上）。 */
    public static String describe(EntityMaid maid) {
        Channel c = CHANNELS.get(maid.getUUID());
        if (c == null) {
            return "无";
        }
        return c.spell.getClass().getSimpleName() + "（" + c.mode + " " + c.castTime + "/" + c.hold
                + " tick·" + c.shots + " 发）";
    }

    /** 供自测/诊断：统计"这个法术会被判成哪个形态"（不落任何状态）。 */
    public static Mode modeOfForTest(EntityMaid maid, ItemStack wand, ISpell spell) {
        return modeOf(maid, wand, spell);
    }

    /** 供自测：蓄力类的引导长度算法（纯函数，便于离线断言）。 */
    public static int chargingHoldForTest(int castUp) {
        return chargingHold(castUp);
    }

    /** 供自测：可信上限常量。 */
    public static int maxSaneChannelForTest() {
        return MAX_SANE_CHANNEL;
    }

    /** 取当前引导用的枚举名（诊断）。 */
    public static String modeNameOf(EntityMaid maid) {
        Channel c = CHANNELS.get(maid.getUUID());
        return c == null ? "-" : c.mode.name();
    }

    /** 供诊断：{@code WandUtil} 的统计口径透出（避免调用方重复 import）。 */
    static SpellStat statsOf(EntityMaid maid, ISpell spell) {
        return WandUtil.getStats(maid, spell);
    }
}
