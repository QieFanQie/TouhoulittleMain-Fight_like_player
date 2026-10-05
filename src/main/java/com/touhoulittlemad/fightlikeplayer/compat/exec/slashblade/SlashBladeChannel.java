package com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.decision.SlashArtTimeline;

import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.slasharts.SlashArts;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * ★★ <b>刀技（SA）与连段的「逐帧驱动器」</b> —— 第十六轮，修掉"SA 触发了却零效果"。
 *
 * <h2>★ 病灶（取证结论）</h2>
 * 我们此前只调一次 {@code doChargeAction}；而 SA 的伤害都在
 * {@code ComboState#tickAction} 里，它只由 {@code Item#inventoryTick} 驱动 ——
 * 女仆的持有物**永远不会**被引擎调到那个方法（1.20.1 全库唯一调用者是玩家的 {@code Inventory}）。
 *
 * <h2>★★ 解法（照参考实现：万法皆通 1.9.0 的 {@code SlashBladeProvider}）</h2>
 * <ol>
 *   <li><b>起手</b>：{@code startUsingItem(MAIN_HAND)} 蓄力 ⇒ 到
 *       {@code getFullChargeTicks + SlashArts.getJustReceptionSpan/2} ⇒
 *       {@code releaseUsing(level, maid, timeLeft)} + {@code releaseUsingItem()}；</li>
 *   <li><b>推进</b>：进连段之后**每 tick** 调
 *       {@code stack.getItem().inventoryTick(stack, level, maid, 0, true)}；
 *       ★★ <b>这一行就是全部秘密</b> —— 我们不是去复刻拔刀剑的内部两行
 *       （{@code resolvCurrentComboState} / {@code tickAction}），
 *       而是**把物品那一帧自己推一下**：它内部本来就会做那两件事，
 *       而且顺带把物品自己的记账（耐久/粒子/进阶）也做了。</li>
 *   <li><b>收尾</b>：{@code comboSeq} 回到 {@code NONE} ⇒ 正常打完；
 *       超过 {@link SlashArtTimeline#COMBO_BUDGET_TICKS} ⇒ 强制收尾（不许无限举刀）。</li>
 * </ol>
 * ★ 判据本体在纯逻辑层 {@link SlashArtTimeline}（可离线断言），这里只做 API 调用与状态持有。
 * ★ 挂在 <b>TLM 的 {@code MaidTickEvent}</b> 上（不是服务器 tick）——
 * 与参考实现同构，而且天然只对"在场的女仆"跑。
 */
public final class SlashBladeChannel {

    private SlashBladeChannel() {
    }

    /** 每个女仆一份（WeakHashMap：她卸载了自然消失）。 */
    private static final Map<UUID, State> RUNNING = new WeakHashMap<>();

    /** 一次 SA 的运行状态。 */
    private static final class State {
        int releaseTick;
        long startedAt;
        boolean released;
        long comboStartedAt = -1;
        String comboId = "";
        SlashArtTimeline.End end = SlashArtTimeline.End.NONE;
        String note = "";
    }

    // ───────────────────────── 起手 ─────────────────────────

    /**
     * 开始一次刀技：进入蓄力（{@code startUsingItem}）。
     *
     * @return 是否真的开始（失败原因写进日志，且**都是可读的**）
     */
    public static boolean start(EntityMaid maid, long now) {
        ItemStack stack = maid.getMainHandItem();
        if (!SlashBladeExecutors.isUsableBlade(maid)) {
            FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技不可用：主手不是可用的刀（{}）",
                    stack.isEmpty() ? "空手" : stack.getHoverName().getString());
            return false;
        }
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        ISlashBladeState state = opt.isPresent() ? opt.resolve().orElse(null) : null;
        if (state == null) {
            FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技不可用：这把刀没有拔刀剑状态");
            return false;
        }
        if (state.isSealed()) {
            // ★ 封印刀不出手（与官方 SummonedSwordArts/SA 同一条前置）
            FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技不可用：刀被封印了");
            return false;
        }
        if (!hasSlashArt(stack, state)) {
            FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技不可用：这把刀没有配 SA（SlashArts）");
            return false;
        }
        if (isRunning(maid)) {
            return false;                       // 已经在放了，不重入
        }
        int charge = state.getFullChargeTicks(maid);
        int justSpan = justReceptionSpan(maid);
        State s = new State();
        s.releaseTick = SlashArtTimeline.releaseTick(charge, justSpan);
        s.startedAt = now;
        RUNNING.put(maid.getUUID(), s);
        maid.startUsingItem(InteractionHand.MAIN_HAND);
        FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技起手：蓄力到第 {} tick 松手（满蓄力 {}，"
                + "justSpan {}，SA={}）", s.releaseTick, charge, justSpan, state.getSlashArtsKey());
        return true;
    }

    private static int justReceptionSpan(EntityMaid maid) {
        try {
            return SlashArts.getJustReceptionSpan(maid);
        } catch (RuntimeException | LinkageError e) {
            return 0;                            // 取不到就退回"满蓄力立刻松手"
        }
    }

    /** 这把刀有没有配 SA（与参考实现的 {@code hasSlashArt} 同判据）。 */
    public static boolean hasSlashArt(ItemStack stack, ISlashBladeState state) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var key = state.getSlashArtsKey();
        return key != null && !key.equals(mods.flammpfeil.slashblade.registry.SlashArtsRegistry.NONE
                .getId());
    }

    // ───────────────────────── 每 tick ─────────────────────────

    /**
     * 每 tick 推一下（由 {@code MaidTickEvent} 调用）。
     *
     * <h3>三个阶段（判据在 {@link SlashArtTimeline}）</h3>
     * <ol>
     *   <li>蓄力中：没到点就等；到点就 {@code releaseUsing} + {@code releaseUsingItem()}；</li>
     *   <li>连段中：★★ **每 tick 调 {@code inventoryTick} 推它**；</li>
     *   <li>结束：记录原因、清状态。</li>
     * </ol>
     */
    public static void tick(EntityMaid maid, long now) {
        State s = RUNNING.get(maid.getUUID());
        if (s == null || s.end.finished()) {
            return;
        }
        ItemStack stack = maid.getMainHandItem();
        if (stack.isEmpty()) {
            finish(maid, s, SlashArtTimeline.End.ABORTED_NOT_USING, "刀不在主手了");
            return;
        }
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        ISlashBladeState state = opt.isPresent() ? opt.resolve().orElse(null) : null;
        if (state == null) {
            finish(maid, s, SlashArtTimeline.End.ABORTED_NOT_USING, "拔刀剑状态没了");
            return;
        }
        boolean comboActive = state.getComboSeq() != null
                && !state.getComboSeq().equals(ComboStateRegistry.NONE.getId());

        if (!s.released) {
            // ── ① 蓄力阶段 ──
            if (!maid.isUsingItem()) {
                finish(maid, s, SlashArtTimeline.End.ABORTED_NOT_USING, "蓄力被打断");
                return;
            }
            int ticksUsing = maid.getTicksUsingItem();
            if (!SlashArtTimeline.shouldRelease(ticksUsing, s.releaseTick)) {
                return;
            }
            release(maid, stack, state, s, now);
            return;
        }

        // ── ② 连段阶段 ──
        long comboElapsed = s.comboStartedAt < 0 ? 0 : now - s.comboStartedAt;
        SlashArtTimeline.End end = SlashArtTimeline.comboEnd(comboActive, comboElapsed,
                SlashArtTimeline.COMBO_BUDGET_TICKS);
        if (end.finished()) {
            if (end == SlashArtTimeline.End.TIMEOUT) {
                state.setComboSeq(ComboStateRegistry.NONE.getId());   // ★ 替她收尾（不许无限举刀）
            }
            finish(maid, s, end, end == SlashArtTimeline.End.TIMEOUT
                    ? "超时（" + comboElapsed + " tick）" : "连段 " + s.comboId + " 打完");
            return;
        }
        if (SlashArtTimeline.shouldDriveCombo(comboActive, comboElapsed,
                SlashArtTimeline.COMBO_BUDGET_TICKS)) {
            // ★★ 这一行就是本轮修的东西：把物品那一帧自己推一下。
            //   拔刀剑的 ItemSlashBlade#inventoryTick 内部会做
            //   resolvCurrentComboState + ComboState#tickAction（伤害/位移/特效都在里面）。
            try {
                stack.getItem().inventoryTick(stack, maid.level(), maid, 0, true);
            } catch (RuntimeException | LinkageError e) {
                finish(maid, s, SlashArtTimeline.End.ABORTED_NO_COMBO, "推进时报错：" + e);
            }
        }
    }

    /** 松手：先 {@code releaseUsing} 让它自己结算"松手那一帧"，再 {@code releaseUsingItem}。 */
    private static void release(EntityMaid maid, ItemStack stack, ISlashBladeState state, State s,
                                long now) {
        s.released = true;
        int useDuration = stack.getUseDuration();
        int timeLeft = Math.max(0, useDuration - maid.getTicksUsingItem());
        try {
            stack.releaseUsing(maid.level(), maid, timeLeft);
        } catch (RuntimeException | LinkageError e) {
            finish(maid, s, SlashArtTimeline.End.ABORTED_NO_COMBO, "releaseUsing 报错：" + e);
            return;
        }
        maid.releaseUsingItem();
        var combo = state.getComboSeq();
        boolean active = combo != null && !combo.equals(ComboStateRegistry.NONE.getId());
        if (!SlashArtTimeline.releaseSucceeded(active)) {
            finish(maid, s, SlashArtTimeline.End.ABORTED_NO_COMBO, "松手后 comboSeq 仍是 NONE");
            return;
        }
        s.comboStartedAt = now;
        s.comboId = String.valueOf(combo);
        FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技松手成功 ⇒ 进入连段 {}（由此开始每 tick 推进）",
                s.comboId);
    }

    private static void finish(EntityMaid maid, State s, SlashArtTimeline.End end, String note) {
        s.end = end;
        s.note = note;
        RUNNING.remove(maid.getUUID());
        FightLikePlayer.LOGGER.info("[FLP][slashblade] 刀技结束（{}）：{}", end.zh, note);
    }

    // ───────────────────────── 查询 ─────────────────────────

    /** 她此刻是不是正在放刀技（供承诺门控用）。 */
    public static boolean isRunning(EntityMaid maid) {
        State s = RUNNING.get(maid.getUUID());
        return s != null && !s.end.finished();
    }

    /** 脱战/卸载时清干净（否则"上一场的 SA"会跟着她）。 */
    public static void forget(UUID maidId) {
        RUNNING.remove(maidId);
    }

    /** 一行诊断（命令/日志）。 */
    public static String describe(EntityMaid maid) {
        State s = RUNNING.get(maid.getUUID());
        if (s == null) {
            return "没有正在进行的刀技";
        }
        if (!s.released) {
            return "蓄力中（第 " + maid.getTicksUsingItem() + "/" + s.releaseTick + " tick）";
        }
        return "连段中：" + s.comboId;
    }
}
