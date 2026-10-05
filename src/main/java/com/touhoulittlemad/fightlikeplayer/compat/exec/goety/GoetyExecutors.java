package com.touhoulittlemad.fightlikeplayer.compat.exec.goety;

import com.Polarice3.Goety.api.items.magic.IWand;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.magic.Spell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.Polarice3.Goety.utils.WandUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.world.item.ItemStack;

/**
 * <b>Goety（诡厄巫法）执行器</b>。
 *
 * <h2>★★ 为什么不走物品路径（源码证据）</h2>
 * {@code DarkWand} 有三个入口都会汇到 {@code MagicResults}：
 * <pre>
 *   onUseTick       :449 → :488    按住右键蓄力/引导时每 tick
 *   finishUsingItem :549 → :555    松手/用尽
 *   use             :571 → :614    ★ 形参写死 Player
 * </pre>
 * 而 {@code MagicResults} 的第一行是：
 * <pre>
 *   if (spell != null &amp;&amp; caster instanceof Player playerEntity) {   // ★ 763 行：硬门控在 Player
 *       ...扣魂 / 冷却 / 施法...
 *   } else {
 *       this.failParticles(worldIn, caster);                        // 白烟
 *       worldIn.playSound(..., SoundEvents.FIRE_EXTINGUISH, ...);   // 灭火音
 *   }
 * </pre>
 * ⇒ <b>女仆不是 Player ⇒ 白烟 + 灭火音，且根本不施法。</b>
 *
 * <p>⇒ 本类<b>直接调 {@link Spell#mobSpellResult}</b>（Goety 自己的仆从也是这么调的），
 * <b>完全不经过物品路径</b> ⇒ 天然没有白烟。
 *
 * <h2>⚠️ 已知边界：光束类法术需要"正在使用 IWand"</h2>
 * 以光束实体为载体的法术（{@code AbstractBeam}）靠 {@code isUsingItem()} 存活，
 * 要求 {@code isUsingItem() && 用着 IWand && 杖里有聚晶} 三者同时为真。
 * ⇒ 本类**不**做 {@code startUsingItem}（那会触发 {@code DarkWand.onUseTick} ⇒ 白烟回来），
 * 因此<b>光束类法术在当前实现下不会存活</b>。
 *
 * <p>要支持它们，必须<b>自备一把 {@code onUseTick} 空实现的 {@code IWand} 物品</b>
 * （调律师的 {@code TunerWand} 就是这个东西）。登记为 <b>W35</b>。
 *
 * @see <a href="../../../../../../../../docs/10-开发计划.md">docs/10 §1.8</a>
 */
public final class GoetyExecutors {

    private GoetyExecutors() {
    }

    /**
     * 施法的结果 —— ★ 从 {@code boolean} 升级为枚举，<b>因为"为什么没放出来"必须可见</b>。
     *
     * <p>委托方连续两轮报「从未观测到女仆成功施法 / goety 法术仍然不可行」，
     * 而执行器只返回一个 {@code false} ⇒ 三种完全不同的原因混在一起，无法定位。
     * （这是 docs/13 第 19 条那条纪律的又一次应用：**可预期的失败必须有可读的出口**。）
     */
    public enum Result {
        /** 真的发起施法了 */
        OK,
        /** 主手不是法杖（{@code IWand}）—— 通常是换手前置没生效 */
        NO_WAND,
        /** ★ 她和她的聚晶包/物品栏里<b>一颗能用的聚晶都没有</b>（或装不进法杖） */
        NO_FOCUS,
        /** ★ 通道类法术已起手（后续由 {@code GoetyChannel#tick} 每 tick 推进） */
        CHANNEL_STARTED,
        /** 有聚晶，但它不是 Goety 的 {@code Spell} 子类 ⇒ 走不了 {@code mobSpellResult} */
        NOT_A_SPELL,
        /** ★ 法术自己的 {@code conditionsMet} 不满足 ⇒ 跳过（当作"没做出来"，不扣代价） */
        CONDITIONS_NOT_MET,
        /** ★ 这个法术已经出错并被【运行期拉黑】*/
        BLACKLISTED,
        /** ★ 调用第三方实现时抛异常（已拉黑） */
        ERROR,
        /** ★ 她仍在潜行，而这颗聚晶的潜行变体会杀掉自己的仆从 ⇒ 跳过（见 cast 里的说明） */
        SNEAK_CLEAR_RISK,
        /**
         * ★★ 她仍在潜行 ⇒ <b>一概不施法</b>（2026-10-01 收紧）。
         *
         * <p>理由见 {@code cast} 里的"潜行硬规则"：Goety 里有 42 个法术类会因为
         * {@code isShifting} 为真而走<b>完全不同的分支</b>（多为"召回/清除"语义），
         * 而我们的框架给清除类动作<b>另有专门的动作</b>
         * （{@code goety:dismiss_temporary_servants}）⇒ 施法路径里永远不该出现它。
         */
        SNEAK_BRANCH_RISK
    }

    /**
     * ★★ <b>按需求挑一颗聚晶 → 装进法杖（调换 or 填充）→ 施放</b>。
     *
     * <h2>与旧版的区别（委托方 2026-10-01 第 2 条）</h2>
     * 旧版只用<b>主手法杖里已经装着的那一颗</b>聚晶 —— 女仆背包里囤一堆聚晶等于不存在。
     * 现在：{@link GoetyFocusOps#available} 把<b>杖内 + 物品栏 + 聚晶包/多晶大袋</b>
     * 里的聚晶全列出来，按当前需求向量挑一颗，
     * 再用 {@link GoetyFocusOps#install} 装进法杖（**有旧的就调换位置，没有就填充**，与玩家侧同构）。
     *
     * @param need      当前需求向量（弹簧点）；{@code null} ⇒ 退回"最久没用的一颗"
     * @param overrides {@code goety:cast_focus} 的 {@code vectorOverrides}（按类别打分）
     */
    public static Result cast(EntityMaid maid, com.touhoulittlemad.fightlikeplayer.decision.NeedVector need,
                              java.util.Map<String, com.touhoulittlemad.fightlikeplayer.decision.NeedVector> overrides,
                              com.touhoulittlemad.fightlikeplayer.decision.SpringConfig config,
                              java.util.Map<String, Long> lastUsed, long now) {
        ItemStack wand = maid.getMainHandItem();
        if (!(wand.getItem() instanceof IWand)) {
            return Result.NO_WAND;
        }
        var options = GoetyFocusOps.available(maid);
        if (options.isEmpty()) {
            return Result.NO_FOCUS;
        }
        GoetyFocusOps.Focus chosen = pick(options, need, overrides, config, lastUsed, now,
                excludedLabels(maid, options, now));
        if (chosen == null) {
            // ★ 区分"一颗都没有"与"都在自己的冷却里" —— 后者是正常现象，不该报成失败原因不明
            var cd = SPELL_COOLDOWNS.computeIfAbsent(maid.getUUID(),
                    k -> new com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger());
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (GoetyFocusOps.Focus f : options) {
                labels.add(f.label());
            }
            String cooling = cd.describe(labels, now);
            if (!cooling.isEmpty()) {
                FightLikePlayer.LOGGER.debug("[FLP] goety：可用的聚晶都在自己的冷却里（{}）", cooling);
            }
            return Result.NO_FOCUS;
        }
        if (!GoetyFocusOps.install(maid, wand, chosen)) {
            return Result.NO_FOCUS;      // 装不上 ⇒ 也算"没有可用的聚晶"（且 install 已记日志）
        }
        ISpell spell = chosen.spell();
        if (!(spell instanceof Spell s)) {
            return Result.NOT_A_SPELL;
        }
        String name = spell.getClass().getSimpleName();

        // ★★ 运行期黑名单：抛过异常的法术不再尝试（委托方要的"跳过坏的、进入下一个循环"）
        if (GoetyChannel.isBlacklisted(name)) {
            FightLikePlayer.LOGGER.debug("[FLP] goety 跳过已拉黑的法术：{}", name);
            return Result.BLACKLISTED;
        }

        // ★★ 潜行硬规则（委托方实测「刚召唤就清除」的根因）：
        //    Goety 的 `Spell#isShifting(caster)` 一为真，**42 个法术类**就会走完全不同的分支
        //    （多是"召回 / 清除 / 命令"语义；ghastly/illusion/vexing 三种更是【杀掉自己所有的仆从】）。
        //    委托方的疑问是：「女仆会主动触发清除吗？」—— **设计上不会**：
        //    清除是 `goety:dismiss_temporary_servants` 这条【专门动作】的职责，
        //    施法路径里出现它一定是 bug（他就是"刚召唤就清除"的目击者）。
        //    ⇒ 2026-10-01 收紧为【硬规则】：先强制解除蹲姿，**复核仍潜行就一概不施法**。
        //      （旧版只拦那 3 种会杀仆从的，其余照放 —— 于是"召唤变清除"这类静默走偏仍可能发生。）
        com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel.clearShifting(maid);
        boolean sneakClears = com.touhoulittlemad.fightlikeplayer.decision.thinking
                .SpellIntentTable.current()
                .hasFlag(name, "SNEAK_CLEARS_SERVANTS");
        try {
            if (s.isShifting(maid)) {
                if (sneakClears) {
                    FightLikePlayer.LOGGER.info(
                            "[FLP] ★ 跳过 goety 法术 {}：她仍处于潜行状态，而这颗聚晶的潜行变体"
                                    + "会【杀掉自己的仆从】（清除不能自动触发）", name);
                    return Result.SNEAK_CLEAR_RISK;
                }
                FightLikePlayer.LOGGER.info(
                        "[FLP] ★ 跳过 goety 法术 {}：她仍处于潜行状态（蹲姿清不掉）⇒ "
                                + "不施法 —— 潜行会把这个法术换成另一套分支语义（清除/召回），"
                                + "而清除是 goety:dismiss_temporary_servants 的职责", name);
                return Result.SNEAK_BRANCH_RISK;
            }
        } catch (RuntimeException e) {
            // ★★ 第十七轮：原来是"按未潜行处理" = **fail-open** ⇒ 判不出来就照放，
            //    而那正是"偶尔杀掉全部仆从"的又一个可能来源。⇒ 改成 **fail-closed**：
            //    判不出来就不施法（少放一发，好过不可逆地清掉她的仆从）。
            FightLikePlayer.LOGGER.info("[FLP] 跳过 goety 法术 {}：潜行状态判定异常"
                    + "（fail-closed，避免误入清除/召回分支）：{}", name, e.toString());
            return Result.SNEAK_BRANCH_RISK;
        }

        // ★★ 前置条件：{@code Spell#conditionsMet} 由法术自己实现（例如 KillingSpell 要求有目标）
        //    ⇒ 不满足时**必须当"没做出来"**，而不是照样回报 OK —— 否则就会出现
        //      「什么都没发生，但决策层以为做了」⇒ 卡住其他行为（委托方实测的另一半）。
        try {
            if (!s.conditionsMet(maid.level(), maid)) {
                FightLikePlayer.LOGGER.info("[FLP] goety 法术 {} 的条件不满足（conditionsMet=false）⇒ 跳过",
                        name);
                return Result.CONDITIONS_NOT_MET;
            }
        } catch (RuntimeException e) {
            FightLikePlayer.LOGGER.error("[FLP] goety 法术 {} 的 conditionsMet 抛异常：{}", name, e);
            return Result.ERROR;
        }

        SpellStat stats = WandUtil.getStats(maid, s);

        // ★★★ 关键分流：三条生命期（瞬发 / 定长引导 / 蓄力长按）各有各的驱动方式。
        //     旧实现只有"瞬发"一条 ⇒ 把活儿写在 startSpell 里的法术（FlameStrike = 炽燃升腾）
        //     什么都没发生，我们却回报 OK ⇒ 决策层照扣代价 ⇒ 她反复撞同一面墙
        //     —— 委托方看到的「无法释放，但会卡住其他行为」。
        //     ★ 形态判定同时修掉了另一处更危险的误读：IChargingSpell 的 castDuration 是
        //       **72000 哨兵值**（"按到松手为止"），不是时长 ⇒ 直译会把女仆锁死一分钟。
        GoetyChannel.Mode mode = GoetyChannel.modeOf(maid, wand, s);
        if (mode == null) {
            return Result.ERROR;                 // 判不出来（已拉黑）⇒ 当作没做出来
        }
        if (mode != GoetyChannel.Mode.INSTANT) {
            if (GoetyChannel.begin(maid, wand, s, mode, stats)) {
                // ★ 引导类也要记冷却：它"占着"这颗聚晶直到引导结束（免得紧接着又选它）
                markCooldown(maid, wand, chosen, now);
                if (FightLikePlayer.LOGGER.isDebugEnabled()) {
                    FightLikePlayer.LOGGER.debug("[FLP] goety 引导 {}（{}·{}·{}）", chosen.label(),
                            chosen.category(), chosen.source(), mode);
                }
                return Result.CHANNEL_STARTED;
            }
            FightLikePlayer.LOGGER.info("[FLP] goety 法术 {} 判为 {} 但起手失败 ⇒ 当作没做出来", name, mode);
            return Result.ERROR;
        }

        // 瞬发：走 mobSpellResult（绕开物品路径的 Player 硬门）
        try {
            s.mobSpellResult(maid, wand);
        } catch (RuntimeException e) {
            FightLikePlayer.LOGGER.error("[FLP] goety 法术 {} 的 SpellResult 抛异常：{}", name, e);
            return Result.ERROR;
        }
        // ★★ 记下这颗聚晶自己的冷却（玩家侧走 SEHelper，对女仆从来没有 ⇒ 由我们记账）
        markCooldown(maid, wand, chosen, now);
        if (FightLikePlayer.LOGGER.isDebugEnabled()) {
            FightLikePlayer.LOGGER.debug("[FLP] goety 施放 {}（{}·{}）", chosen.label(),
                    chosen.category(), chosen.source());
        }
        return Result.OK;
    }

    /**
     * ★★ 记账：这颗聚晶多久之后才能再放。
     * <p>来源是<b>聚晶自己报的数</b>（{@code ISpell#spellCooldown}）—— 见 {@link #cooldownTicksOf}。
     */
    static void markCooldown(EntityMaid maid, ItemStack wand, GoetyFocusOps.Focus focus, long now) {
        if (focus == null) {
            return;
        }
        SPELL_COOLDOWNS.computeIfAbsent(maid.getUUID(),
                        k -> new com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger())
                .markUsed(focus.label(), now, cooldownTicksOf(maid, wand, focus));
    }

    /**
     * ★ 按需求在「她拥有的聚晶」里挑一颗。
     *
     * <p>★ 判据本体在纯逻辑层 {@code SpellPicker} —— <b>与铁魔法共用同一份</b>：
     * 两者都是「在带类别标签的候选里，选类别向量离需求最近、同类里最久没用过的那个」。
     */
    static GoetyFocusOps.Focus pick(java.util.List<GoetyFocusOps.Focus> options,
                                    com.touhoulittlemad.fightlikeplayer.decision.NeedVector need,
                                    java.util.Map<String, com.touhoulittlemad.fightlikeplayer.decision.NeedVector> overrides,
                                    com.touhoulittlemad.fightlikeplayer.decision.SpringConfig config,
                                    java.util.Map<String, Long> lastUsed, long now) {
        return pick(options, need, overrides, config, lastUsed, now, java.util.Set.of());
    }

    /**
     * ★★ 同上，但可排除<b>还在自己冷却里</b>的聚晶（第十一轮）。
     *
     * <p>★ 冷却的权威来源是<b>聚晶自己的数</b>（{@code ISpell#spellCooldown(caster)}）：
     * 玩家侧走 {@code SEHelper.addCooldown(player, ...)} —— <b>Player-only</b> ⇒ 女仆从来没有冷却。
     */
    static GoetyFocusOps.Focus pick(java.util.List<GoetyFocusOps.Focus> options,
                                    com.touhoulittlemad.fightlikeplayer.decision.NeedVector need,
                                    java.util.Map<String, com.touhoulittlemad.fightlikeplayer.decision.NeedVector> overrides,
                                    com.touhoulittlemad.fightlikeplayer.decision.SpringConfig config,
                                    java.util.Map<String, Long> lastUsed, long now,
                                    java.util.Set<String> excluded) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        java.util.List<com.touhoulittlemad.fightlikeplayer.decision.thinking
                .SpellPicker.Choice> choices = new java.util.ArrayList<>(options.size());
        for (GoetyFocusOps.Focus f : options) {
            choices.add(new com.touhoulittlemad.fightlikeplayer.decision.thinking
                    .SpellPicker.Choice(f.label(), f.category().name()));
        }
        int idx = com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellPicker
                .pickIndex(choices, need, overrides,
                        config == null
                                ? com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults()
                                : config,
                        lastUsed, excluded);
        return idx < 0 ? null : options.get(idx);
    }

    /** ★★ 每个女仆的聚晶冷却账本（与铁魔法各记一份，因为标签空间不同）。 */
    static final java.util.Map<java.util.UUID,
            com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger> SPELL_COOLDOWNS =
            new java.util.WeakHashMap<>();

    private static java.util.Set<String> excludedLabels(EntityMaid maid,
                                                        java.util.List<GoetyFocusOps.Focus> options,
                                                        long now) {
        var cd = SPELL_COOLDOWNS.computeIfAbsent(maid.getUUID(),
                k -> new com.touhoulittlemad.fightlikeplayer.decision.SpellCooldownLedger());
        java.util.Set<String> out = new java.util.HashSet<>();
        for (GoetyFocusOps.Focus f : options) {
            if (!cd.isReady(f.label(), now)) {
                out.add(f.label());
            }
        }
        return out;
    }

    /**
     * ★★ <b>这颗聚晶自己的冷却要等多久</b>（Goety 的数）。
     *
     * <p>{@code ISpell#spellCooldown(caster)} = {@code defaultSpellCooldown() × 冷却减免}。
     * <p>读不到（抛异常）⇒ 0（fail-open：宁可多放，不能把聚晶永久禁掉）。
     */
    static int cooldownTicksOf(EntityMaid maid, ItemStack wand, GoetyFocusOps.Focus focus) {
        if (focus == null || focus.spell() == null) {
            return 0;
        }
        try {
            return Math.max(0, focus.spell().spellCooldown(maid));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 施放主手法杖里装着的聚晶。
     *
     * <p>API 出处（Goety 2.5.56.5 源码）：
     * <ul>
     *   <li>{@code IWand.getSpell(ItemStack)} —— {@code IWand.java:59-62}（default 方法）</li>
     *   <li>{@code Spell.mobSpellResult(LivingEntity, ItemStack)} —— 见
     *       {@code IceChunkSpell().mobSpellResult(this.iceologer, staff)}（CryologerServant.java:600）</li>
     * </ul>
     *
     * <h2>★ 为什么用 {@code mobSpellResult} 而不是物品路径</h2>
     * {@code MagicResults} 的第一行是 {@code if (spell != null && caster instanceof Player player)}
     * ⇒ <b>女仆不是 Player ⇒ 白烟 + 灭火音，且根本不施法</b>（见类注释）。
     * 而 {@code mobSpellResult} 是 Goety 自己的仆从走的路径 ⇒ 天然支持 LivingEntity。
     *
     * @return 见 {@link Result}
     */
    public static Result cast(EntityMaid maid) {
        ItemStack wand = maid.getMainHandItem();
        if (!(wand.getItem() instanceof IWand iwand)) {
            return Result.NO_WAND;
        }
        var spell = iwand.getSpell(wand);
        if (spell == null) {
            return Result.NO_FOCUS;
        }
        if (spell instanceof Spell s) {
            s.mobSpellResult(maid, wand);
            return Result.OK;
        }
        return Result.NOT_A_SPELL;
    }

    /** 主手是不是一把装着聚晶的法杖（{@code cast} 的前置判据）。 */
    public static boolean canCast(EntityMaid maid) {
        ItemStack wand = maid.getMainHandItem();
        if (!(wand.getItem() instanceof IWand iwand)) {
            return false;
        }
        return iwand.getSpell(wand) != null;
    }

    /**
     * ★★ <b>女仆【拥有的】任意一件法杖里有没有聚晶</b> —— 用于喂 {@code goety:can_cast} 事实。
     *
     * <h2>为什么不能直接用 {@link #canCast}（只看主手）来喂这个事实</h2>
     * 解析期（算候选集）与执行期（换手）是<b>两个时刻</b>：
     * <ul>
     *   <li>执行器有<b>换手前置</b>：选定动作后会把法杖换到主手再施法 ⇒ <b>执行期看主手是对的</b>；</li>
     *   <li>但<b>前置条件</b>在解析期判定：那时法杖可能还在背包/副手
     *       ⇒ 若这里只看主手，{@code goety:cast_focus} 会在<b>候选阶段就被丢掉</b>
     *       ⇒ 女仆永远选不到它 ⇒ <b>换手再强也没机会跑</b>。</li>
     * </ul>
     * ⇒ 这就是委托方 2026-10-01 实测「**从未观测到女仆成功施法**」的原因之一。
     * ⇒ "能不能施法"这个事实必须按<b>拥有物</b>判定（与 {@code IronsExecutors#hasAnySpell} 同一口径）。
     */
    /**
     * ★★ <b>女仆【拥有的】任意一颗聚晶</b> —— 用于喂 {@code goety:can_cast} 事实。
     *
     * <h2>为什么要按"拥有物"而不是"主手"判定</h2>
     * 解析期（算候选集）与执行期（换手/装聚晶）是<b>两个时刻</b>：
     * <ul>
     *   <li>执行器有<b>换手 + 装聚晶</b>前置 ⇒ 执行期看主手是对的；</li>
     *   <li>但<b>前置条件</b>在解析期判定：那时法杖和聚晶可能都在背包里
     *       ⇒ 若只看主手，{@code goety:cast_focus} 会在<b>候选阶段就被丢掉</b>
     *       ⇒ 女仆永远选不到它。</li>
     * </ul>
     *
     * <p>★★ 2026-10-01 扩展：判据从"杖里有聚晶"放宽为
     * <b>「有法杖」且「任意位置（物品栏 / 聚晶包 / 多晶大袋 / 杖内）有一颗能用的聚晶」</b>
     * —— 因为现在已经会<b>自动装聚晶</b>（{@link GoetyFocusOps#install}）。
     */
    public static boolean canCastWithAnyHeldWand(EntityMaid maid) {
        if (!hasAnyWand(maid)) {
            return false;
        }
        boolean ok = !GoetyFocusOps.available(maid).isEmpty();
        if (!ok) {
            // ★★ 第十二轮：这条"有杖但一颗聚晶都用不了"的**可读出口**（委托方实测过这个死胡同：
            //   法杖在背包里、聚晶装在杖里时，前置判 false ⇒ 动作进不了候选集 ⇒ 她"不作为"，
            //   而当时**什么日志都没有**）。⇒ 每次状态变化时打一条，把三个来源都列清楚。
            logNoFocusOnce(maid);
        }
        return ok;
    }

    /** 每个女仆只在"从可施法变成不可施法"时提醒一次（避免每 tick 刷屏）。 */
    private static final java.util.Map<java.util.UUID, Boolean> NO_FOCUS_WARNED =
            new java.util.WeakHashMap<>();

    private static void logNoFocusOnce(EntityMaid maid) {
        if (NO_FOCUS_WARNED.putIfAbsent(maid.getUUID(), Boolean.TRUE) != null) {
            return;
        }
        int wands = 0;
        boolean anyFocusItem = false;
        boolean anyBag = false;
        for (ItemStack s : heldStacks(maid)) {
            if (s.getItem() instanceof IWand) {
                wands++;
            }
            if (s.getItem() instanceof com.Polarice3.Goety.api.items.magic.IFocus) {
                anyFocusItem = true;
            }
            if (GoetyFocusOps.bagOf(s) != null) {
                anyBag = true;
            }
        }
        FightLikePlayer.LOGGER.info(
                "[FLP] ⓘ goety：她有 {} 把法杖，但【一颗能用的聚晶都没有】⇒ 施法动作进不了候选集，她不会去换手。"
                        + "　散装聚晶：{}　聚晶包/大袋：{}　—— 请把聚晶放进背包、或装进法杖、或放进聚晶包里"
                        + "（三种都算，见 /flp whyfull）",
                wands, anyFocusItem ? "有" : "无", anyBag ? "有" : "无");
    }

    /** 她身上（主手/副手/背包）有没有法杖 —— 换手前置会把主手那件换上来。 */
    public static boolean hasAnyWand(EntityMaid maid) {
        for (ItemStack stack : heldStacks(maid)) {
            if (stack.getItem() instanceof IWand) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主手 / 副手 / <b>全部背包</b> / 饰品槽里的全部物品栈。
     *
     * <p>★★ 第十二轮：改用统一口径 {@code MaidInventory}（可用范围 ∪ 全部 36 格）+ 饰品槽。
     * 为什么：<b>前置条件的判据必须与"换手够得到的范围"一致</b> ——
     * 否则会出现「前置说没有法杖 ⇒ 动作进不了候选集 ⇒ 明明在物品栏里却永远不施法」，
     * 或者反过来「前置说有 ⇒ 换手却够不到」（委托方实测的那两种现象的根源都在这里）。
     */
    private static java.util.List<ItemStack> heldStacks(EntityMaid maid) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        out.add(maid.getMainHandItem());
        out.add(maid.getOffhandItem());
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory.usableStacks(maid));
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.curios.CuriosSlots.stacksOf(maid));
        return out;
    }
}
