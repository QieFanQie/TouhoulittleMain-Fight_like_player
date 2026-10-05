package com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>拔刀剑执行器</b>（M5 的"可做子集"）—— 只实现<b>不吃输入状态</b>的那几类操作。
 *
 * <h2>★★ 为什么"可做子集"只有这些（判据同 W34/Guard）</h2>
 * 拔刀剑的动作分两类：
 * <table border="1">
 *   <tr><th>类别</th><th>入口</th><th>女仆可用</th></tr>
 *   <tr><td><b>斩击类</b></td>
 *       <td>{@code AttackManager.doSlash(LivingEntity, …)}（<b>public static</b>，只吃 LivingEntity）</td>
 *       <td>✅ <b>可用</b> —— 这正是 TLM 的 {@code SlashBladeCompat} 内部调的那一个，
 *           而 TLM 的包装层被硬门控在 {@code TaskAttack.UID}（W34）⇒ <b>我们绕开包装、直调它</b></td></tr>
 *   <tr><td><b>刀技（SA）</b></td>
 *       <td>{@code ISlashBladeState#doChargeAction(LivingEntity, int elapsed)}（default 方法）</td>
 *       <td>✅ <b>可用</b> —— 只要 {@code elapsed > 2} 就结算（我们传满蓄力 tick）</td></tr>
 *   <tr><td>瞬步 / 借力跳 / 踢跃 / SSA / 格挡</td>
 *       <td>挂在 {@code InputCommandEvent} 或 {@code IInputState} 上</td>
 *       <td>❌ <b>不可用</b> —— 女仆没有客户端输入（同 {@code Guard}）⇒ 委托方已决定**不做**</td></tr>
 * </table>
 *
 * <h2>⚠️ 本实现的诚实边界（登记 W41）</h2>
 * 我们调 {@code doSlash} <b>而不是</b>跑 {@code ComboState} 的帧时间线
 * （那由 {@code ItemSlashBlade#inventoryTick → cs.tickAction} 驱动，是否对 Mob 成立尚未定论）。
 * ⇒ 每次调用 = <b>一次即时的斩击判定</b>（伤害/击退/角度由我们给的表决定），
 * 而**不是**"它那一招的逐帧编排"。观感上"她在挥刀并且打得到人"成立；
 * 但如果你要的是"连击第 3 段才有那一下挑空"，那需要更深的接入（见 W41）。
 *
 * <p>★ 这让 13 条拔刀剑 RA 里的一部分**真的可用**，也验证了那条更重要的结论：
 * **`AttackManager.doSlash` 是公开静态方法 ⇒ TLM 的门控只在它自己的包装层，不是拦路虎。**
 */
public final class SlashBladeExecutors {

    private SlashBladeExecutors() {
    }

    /** 主手刀是否可用（有 capability 且未损坏）。 */
    public static boolean isUsableBlade(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }
        var state = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        if (!state.isPresent()) {
            return false;
        }
        return !state.map(ISlashBladeState::isBroken).orElse(true);
    }

    /** 这把刀的状态（不是刀 ⇒ {@code null}）。 */
    public static ISlashBladeState stateOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
            return opt.isPresent() ? opt.resolve().orElse(null) : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * ★★ <b>这把刀的"身份 id"</b>（第二十六轮）—— {@code slashblade:yamato} 这种。
     *
     * <h2>为什么必须有它（与枪同型的坑，第 79 条纪律的第二次发作）</h2>
     * Resharped 的**所有具名刀共用一个物品注册名** `slashblade:slashblade`
     * （`SlashBladeDefinition` 的 `item` 默认值，`javap -c` 取证），
     * 真正的身份在刀状态里：{@code getTranslationKey()} = {@code "item.slashblade.yamato"}。
     * ⇒ 不给它另立身份，物品清单里每把刀都长一样，`only_item` 也指不到具体哪一把。
     *
     * <p>★ 解析（`item.&lt;ns&gt;.&lt;path&gt;` → `ns:path`）在纯逻辑层
     * {@code ItemIdentity#fromTranslationKey}，可以离线断言。
     *
     * @return 刀的身份 id；不是刀/读不到 ⇒ {@code null}（调用方退回注册名）
     */
    public static String bladeId(ItemStack stack) {
        ISlashBladeState state = stateOf(stack);
        if (state == null) {
            return null;
        }
        try {
            return com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity
                    .fromTranslationKey(state.getTranslationKey());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * ★★ <b>她身上（不限主手）的全部拔刀剑</b>（第二十六轮）—— 提示词要按**拥有**报。
     *
     * <h2>为什么需要它（委托方实测的原话）</h2>
     * > 「主人~ 可是我现在**手头没有可用的拔刀剑**呢，仓库里的魔剑「阎魔刀」**好像不算拔刀剑**哦…」
     *
     * 根因：`flp_blade` / `flp_phantom_sword` 这两个**每回合都看得见**的上下文只按**主手**判，
     * 而她的刀在背包里 ⇒ 上下文说"手上没有可用的刀"，同时物品清单说那是拔刀剑
     * ⇒ 她看到矛盾，就自己编了个解释（"那把不算拔刀剑"）。
     * ★ 而决策层**一直**是按"拥有物"算候选的（`LoopSplit.mainItems` 含背包/饰品，
     * 执行器还有**换手前置**）⇒ 是**提示词与自己的候选集口径不一致**（第 18 条的纪律没落到提示词上）。
     *
     * <p>判据用**载体规则**（`slashblade:blade`）而不是另写一遍类型名 —— 口径只有一处。
     *
     * @return 手里的排最前，其后按枚举顺序；每项都能拿到原始 stack 与槽位
     */
    public static List<OwnedBlade> ownedBlades(EntityMaid maid) {
        List<OwnedBlade> out = new ArrayList<>();
        if (maid == null || !net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
            return out;
        }
        try {
            var resolver = com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder.resolver();
            if (resolver == null) {
                return out;
            }
            var rule = resolver.ruleOf("slashblade:blade");
            if (rule == null) {
                return out;
            }
            var carried = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot.carried(maid);
            for (int i = 0; i < carried.items().size(); i++) {
                var it = carried.items().get(i);
                if (!rule.matches(it)) {
                    continue;
                }
                ItemStack stack = i < carried.stacks().size() ? carried.stacks().get(i) : null;
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                out.add(new OwnedBlade(stack, it.slot(), it.itemId(), it.displayName(),
                        stateOf(stack), it.slot()
                                == com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.MAINHAND));
            }
            // ★ 手里的排最前（提示词先说"手上"，再说"身上别处"）
            out.sort(java.util.Comparator.comparingInt(b -> b.inHand() ? 0 : 1));
        } catch (RuntimeException | LinkageError e) {
            FightLikePlayer.LOGGER.debug("[FLP][slashblade] 枚举她的刀失败：{}", e.toString());
        }
        return out;
    }

    /** 她身上的一把刀（见 {@link #ownedBlades}）。 */
    public record OwnedBlade(ItemStack stack,
                             com.touhoulittlemad.fightlikeplayer.carrier.SlotKind slot,
                             String identityId,
                             String displayName,
                             ISlashBladeState state,
                             boolean inHand) {
    }

    /**
     * 一次斩击。
     *
     * @param roll       挥击角度（度）
     * @param comboRatio 连击倍率（伤害/气势的比例，TLM 用 1.0，各连击段用 0.244~1.0）
     * @return 是否真的挥出去了
     */
    public static boolean slash(EntityMaid maid, float roll, double comboRatio) {
        if (!isUsableBlade(maid)) {
            return false;
        }
        // ★ 与 TLM 的 SlashBladeCompat.swingSlashBlade 同一调用（只是不带它的任务门控）
        AttackManager.doSlash(maid, roll, Vec3.ZERO, false, false, comboRatio, KnockBacks.smash);
        return true;
    }

    /**
     * ★★ <b>幻影剑</b>（{@code slashblade:summoned_sword}）—— 第十六轮补上。
     *
     * <h2>为什么此前它"根本没有"</h2>
     * 它不在执行器账本里 ⇒ 在**解析期**就按 {@code NO_EXECUTOR} 被丢掉
     * （历史日志里它被选中过 104 次，每次都被判"无执行器"）。
     *
     * <h2>★ 照官方语义实现（`SummonedSwordArts:101-145`）</h2>
     * 前置三件：刀是 <b>BEWITCHED</b>、**力量附魔 > 0**、**耀魂值 ≥ {@code SUMMON_SWORD_COST}**；
     * 然后扣耀魂、朝"锁定目标（或视线 40 格命中点）"生成一把幻影剑。
     *
     * <h2>★★ 伤害为什么只有 1~几 点（这不是 bug，是官方如此）</h2>
     * 官方入口写死 {@code ServerPlayer}，但**方法体只用到 {@code LivingEntity}**，
     * 所以女仆可以照做。★ 而幻影剑实体的自身伤害是
     * {@code hurt(src, (int)getDamage() * scale)} —— **不乘攻击力**
     * （1.9.65 javap 实测），{@code setDamage(powerLevel)} ⇒ 力量 I 就只有约 1 点。
     * 参考实现（万法皆通）为此**额外补一刀**（攻击力 × 0.85），代价是连段类变成约 1.85 倍。
     * <b>我们刻意不抄那个补刀</b>：我们的伤害来源已经统一在实体那边，
     * 再叠一刀会变成两套账（本项目最忌讳的"同一个效果两份口径"）。
     * ⇒ 幻影剑在这里是**压制/补刀用的小招**，与玩家按同一个键得到的效果**完全一致**。
     *
     * @return 是否真的放出去了
     */
    public static boolean summonSword(EntityMaid maid) {
        // ★ 前置判据只有一份（canSummonSword），这里再用一次得到**可读原因**
        String blocker = summonSwordBlocker(maid);
        if (blocker != null) {
            FightLikePlayer.LOGGER.info("[FLP][slashblade] 幻影剑不可用：{}", blocker);
            return false;
        }
        ItemStack stack = maid.getMainHandItem();
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        ISlashBladeState state = opt.resolve().orElse(null);
        int powerLevel = stack.getEnchantmentLevel(net.minecraft.world.item.enchantment
                .Enchantments.POWER_ARROWS);
        int cost = summonSwordCost();
        state.setProudSoulCount(state.getProudSoulCount() - cost);

        // ④ 目标点：锁定目标的身中，或视线 40 格的命中点（官方同）
        var level = maid.level();
        Vec3 start = maid.getEyePosition(1.0f);
        Vec3 targetPos;
        LivingEntity target = maid.getTarget();
        if (target != null && target.isAlive()) {
            targetPos = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
        } else {
            Vec3 end = start.add(maid.getLookAngle().scale(40.0));
            var hit = level.clip(new net.minecraft.world.level.ClipContext(start, end,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, maid));
            targetPos = hit.getLocation();
        }

        // ⑤ 生成（官方同：左右交错、初速 3.0、朝向目标）
        var ss = new mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword(
                mods.flammpfeil.slashblade.SlashBlade.RegistryEvents.SummonedSword, level);
        double side = maid.getRandom().nextBoolean() ? 1.0 : -1.0;
        Vec3 right = new Vec3(0, 0, 1).yRot(-maid.getYRot() * ((float) Math.PI / 180F));
        Vec3 pos = start.add(right.scale(side));
        ss.setPos(pos.x, pos.y, pos.z);
        ss.setDamage(powerLevel);
        Vec3 dir = targetPos.subtract(pos).normalize();
        ss.shoot(dir.x, dir.y, dir.z, 3.0f, 0.0f);
        ss.setShooter(maid);
        ss.setColor(state.getColorCode());
        ss.setRoll(maid.getRandom().nextFloat() * 360.0f);
        level.addFreshEntity(ss);
        FightLikePlayer.LOGGER.info("[FLP][slashblade] 幻影剑射出（力量 {}，扣耀魂 {}，剩 {}）",
                powerLevel, cost, state.getProudSoulCount());
        return true;
    }

    /** 幻影剑的耀魂消耗（配置读不到时退回官方默认 2）。 */
    private static int summonSwordCost() {
        try {
            return mods.flammpfeil.slashblade.SlashBladeConfig.SUMMON_SWORD_COST.get();
        } catch (RuntimeException | LinkageError e) {
            return 2;
        }
    }

    /**
     * ★★ <b>她现在能不能放幻影剑</b>（三条前置一起判）—— 供**解析期**过滤用。
     *
     * <p>委托方报的 bug：「我让女仆只用幻影剑，但完全没效果」。根因之一是
     * 幻影剑的前置**只在执行期判** ⇒ 它照样进候选、每次 UNAVAILABLE ⇒ 连败退避把它停放 ⇒
     * 观感是"下了指令什么都没发生"。
     * ⇒ 现在把它提成 {@code custom} 事实（{@code slashblade:can_summon_sword}），
     * 做不到就**不进候选**（与 {@code goety:can_cast} 同一条纪律）。
     */
    public static boolean canSummonSword(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (!isUsableBlade(maid)) {
            return false;
        }
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        ISlashBladeState state = opt.isPresent() ? opt.resolve().orElse(null) : null;
        if (state == null) {
            return false;
        }
        try {
            if (!mods.flammpfeil.slashblade.item.SwordType.from(stack)
                    .contains(mods.flammpfeil.slashblade.item.SwordType.BEWITCHED)) {
                return false;
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        if (stack.getEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.POWER_ARROWS)
                <= 0) {
            return false;
        }
        return state.getProudSoulCount() >= summonSwordCost();
    }

    /** 主手这把刀有没有配 SA（{@code slashblade:slash_art} 的前置，同为解析期事实）。 */
    public static boolean hasSlashArtInHand(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (!isUsableBlade(maid)) {
            return false;
        }
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        ISlashBladeState state = opt.isPresent() ? opt.resolve().orElse(null) : null;
        return state != null
                && com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel
                .hasSlashArt(stack, state);
    }

    /**
     * ★ <b>幻影剑为什么放不了</b>（可读原因；日志与对话上下文都用它）。
     *
     * @return {@code null} = 能放；否则是一句给人/给模型看的话
     */
    public static String summonSwordBlocker(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (!isUsableBlade(maid)) {
            return "主手没有可用的刀";
        }
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        ISlashBladeState state = opt.isPresent() ? opt.resolve().orElse(null) : null;
        if (state == null) {
            return "这把东西不是拔刀剑";
        }
        try {
            if (!mods.flammpfeil.slashblade.item.SwordType.from(stack)
                    .contains(mods.flammpfeil.slashblade.item.SwordType.BEWITCHED)) {
                return "这把刀不是妖刀（缺 BEWITCHED）";
            }
        } catch (RuntimeException | LinkageError e) {
            return "读不出剑型";
        }
        if (stack.getEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.POWER_ARROWS)
                <= 0) {
            return "刀上没有【力量】附魔";
        }
        int cost = summonSwordCost();
        if (state.getProudSoulCount() < cost) {
            return "耀魂值不足（" + state.getProudSoulCount() + "/" + cost + "）";
        }
        return null;
    }

    /**
     * 触发刀技（SA）：把"松手那一帧"的结算直接做出来。
     *
     * <p>我们传 {@code elapsed = getFullChargeTicks(maid)}（默认 9）——
     * 正好落在 {@code [fullCharge, fullCharge+justSpan)} 的 <b>Jackpot</b> 区间
     * （{@code ISlashBladeState.java:335-339}）。⇒ 女仆**不做时序博弈**，
     * 她的"手法"由决策层决定，而不是靠按帧数卡窗口（那对 Mob 没有意义）。
     *
     * @return 是否真的触发（返回 {@code none} 或异常都算没触发）
     */
    public static boolean slashArt(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (!isUsableBlade(maid)) {
            return false;
        }
        var opt = stack.getCapability(CapabilitySlashBlade.BLADESTATE);
        if (!opt.isPresent()) {
            return false;
        }
        ISlashBladeState state = opt.resolve().orElse(null);
        if (state == null) {
            return false;
        }
        int elapsed = state.getFullChargeTicks(maid);
        ResourceLocation combo = state.doChargeAction(maid, elapsed);
        boolean fired = combo != null
                && !combo.equals(mods.flammpfeil.slashblade.registry.ComboStateRegistry.NONE.getId());
        if (FightLikePlayer.LOGGER.isDebugEnabled()) {
            FightLikePlayer.LOGGER.debug("[FLP][slashblade] SA 触发：elapsed={} → {}", elapsed, combo);
        }
        return fired;
    }
}
