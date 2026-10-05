package com.touhoulittlemad.fightlikeplayer.compat.exec.irons;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.SpringConfig;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>铁魔法：枚举女仆【所有能放的法术】，并按当前需求挑一个放</b>。
 *
 * <h2>★★ 为什么必须有它（委托方 2026-10-01 第 1 条观察）</h2>
 * 委托方：「我观测到女仆在使用 iron 法术时，<b>总是使用法术书的第一个法术</b>。」
 *
 * <p>原因很直接：旧实现是 {@code container.getActiveSpells().get(0)}
 * —— <b>一个硬编码的下标</b>。一本法术书里有好几个法术，她永远只用第一个，
 * 于是"她会用铁魔法"这件事在观感上退化成"她只会一个法术"。
 *
 * <h2>★ 修法：把"放哪个法术"交回给决策层已有的那套逻辑</h2>
 * 决策层已经算出了一个<b>需求向量 {@code p}</b>（"此刻该往哪个方向使劲"）。
 * 而 {@code catalog/vectors.json} 里 {@code irons:cast_spell} 早就有按
 * <b>法术意图</b>（{@code ATTACK / SUPPORT / SUMMON}）的 {@code overrides}：
 * <pre>
 *   ATTACK  → 单点 0.7 / 群伤 0.3
 *   SUPPORT → 回血 0.5 / 免伤 0.2 / 强化 0.3 / 护友 0.7
 *   SUMMON  → 强化 0.8（单点 −0.2）
 * </pre>
 * ⇒ <b>于是"挑法术"就是同一件事：在【她真正拥有的】法术里，选那个意图向量离 {@code p} 最近的。</b>
 * 不需要新机制、不需要再喂一份评分表，而且与"某个动作该不该做"用的是同一个判据。
 *
 * <h2>⚠️ 意图分类的证据等级（必须说清）</h2>
 * 分类<b>不是勘测结论</b>，而是<b>按法术 id 关键词 + 学派</b>做的推断
 * （见 {@link #classify(AbstractSpell)}）。这与 W23/W32（法术意图/聚晶分类器）是同一件事，
 * 只是先用最小的实现让"她会用第二个法术"成立。
 * ⇒ <b>分类结果会打进日志</b>（{@code /flp whyfull} 与此处的 INFO），所以<b>错了看得见</b>。
 *
 * @see com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyFocusOps（聚晶侧的同型实现）
 */
public final class IronsSpells {

    private IronsSpells() {
    }

    /**
     * ★★ 「指令挡下这个铁魔法」的日志节流器（第二十一轮）：同一女仆 + 同一颗法术 10 秒内只记一条。
     * <p>与 Goety 侧同一个理由：这条判定的调用者是**每 tick 重建的法术池**。
     */
    private static final com.touhoulittlemad.fightlikeplayer.decision.LogThrottle BLOCK_LOG =
            new com.touhoulittlemad.fightlikeplayer.decision.LogThrottle(200);

    /** 法术意图 —— 与 {@code vectors.json} 里 {@code irons:cast_spell.overrides} 的键<b>逐字一致</b>。 */
    public enum Intent {
        ATTACK, SUPPORT, SUMMON
    }

    /**
     * 一个"她现在真的能放的法术"。
     *
     * @param container 哪个容器（法术书/卷轴）里的
     * @param slotIndex 容器里的第几个法术槽
     * @param spell     法术
     * @param level     法术等级
     * @param intent    意图（推断，见类注释）
     * @param label     人读标签（法术 id），日志与诊断用
     */
    public record Option(ItemStack container, int slotIndex, AbstractSpell spell, int level,
                         Intent intent, String label) {

        @Override
        public String toString() {
            return label + "(" + intent + " Lv" + level + ")";
        }
    }

    /**
     * 女仆主手/副手/背包<b>/饰品槽</b>里的全部物品（★ 与 {@code IronsExecutors.candidates} 同口径）。
     *
     * <p>★ 2026-10-01（W5）：加上饰品槽 —— 铁魔法的法书也能放在饰品里（例如术士腰带一类），
     * 而且这条口径必须与 {@code MaidSnapshot.possessed} 一致，否则会出现
     * "解析期认得出、执行期找不到"的错配。
     */
    public static List<ItemStack> containers(EntityMaid maid) {
        List<ItemStack> out = new ArrayList<>();
        out.add(maid.getMainHandItem());
        out.add(maid.getOffhandItem());
        // ★★ 统一口径：可用范围 ∪ 全部 36 格（见 MaidInventory 的类注释）
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory
                .usableStacks(maid));
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.curios.CuriosSlots.stacksOf(maid));
        return out;
    }

    /** 枚举她拥有的全部法术（去重：同一个法术 id 只保留<b>等级最高</b>的那个）。 */
    public static List<Option> options(EntityMaid maid) {
        return options(maid, com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(maid));
    }

    /**
     * ★★ <b>她"到底有没有"这些法术标签（不设任何门）</b>（第二十七轮）。
     *
     * <h2>为什么必须有它（与 {@code canAttackIgnoringDirectives} 同型）</h2>
     * {@link #options(EntityMaid, DirectiveBus)} 会应用两类门：**召唤位满 ⇒ 召唤类法术不入选**
     * 与**她自己指令的过滤**。这两条对"她要放什么"是对的，但对
     * **"她到底有没有这一颗"**是错的 —— 委托方实测的那次正好踩在这上面：
     * 先锋聚晶是 **SUMMON** 类，而她的召唤位是满的
     * （日志：`召唤位已满 ⇒ 跳过召唤聚晶：VanguardSpell`）
     * ⇒ 走被门控的枚举会得出"她没有这颗"的**假否定**，而反馈里说错话比不说更糟。
     *
     * <p>⇒ 这个入口**只用来看"有没有"**（`only_focus`/`ban_focus` 的参数核对），
     * <b>绝不用来决定"放不放"</b>（那两件事的判断必须分开，否则又变成"两个口径互相污染"）。
     */
    public static java.util.List<String> ownedLabels(EntityMaid maid) {
        java.util.List<String> out = new ArrayList<>();
        if (maid == null) {
            return out;
        }
        try {
            for (Option o : options(maid, null, false)) {
                out.add(o.label());
            }
        } catch (RuntimeException | LinkageError e) {
            // 读不到 ⇒ 空表（调用方据此说"一个都放不出来"）
        }
        return out;
    }

    /**
     * ★★ 同 {@link #options(EntityMaid)}，但<b>可以指定用哪条总线来过滤</b>。
     *
     * <p>★ {@code bus == null} ⇒ <b>不过滤</b>（给指挥 LLM 的"战斗场景"用 ——
     * 理由见 {@code GoetyFocusOps.available(EntityMaid, DirectiveBus)}：场景必须看到真正的池子）。
     */
    public static List<Option> options(EntityMaid maid,
                                       com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus bus) {
        return options(maid, bus, true);
    }

    /**
     * @param applyGates ★ {@code false} = **不设任何门**（不看召唤位、不看指令）——
     *                   只给"她到底有没有这一颗"的参数核对用，见 {@link #ownedLabels}
     */
    private static List<Option> options(EntityMaid maid,
                                        com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus bus,
                                        boolean applyGates) {
        List<Option> all = new ArrayList<>();
        // ★★ 召唤位（第十七轮续）：仆从数（含 Goety 仆从与铁魔法召唤物）达上限 ⇒
        //    召唤类法术不进池子。判据只有一处：decision/ServantSlots。
        boolean canSummon = com.touhoulittlemad.fightlikeplayer.decision.ServantSlots
                .summonAllowed(com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                        .GoetyServantOps.servantCount(maid)
                        + com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                                .IronsServantOps.countSummons(maid));
        for (ItemStack stack : containers(maid)) {
            if (stack.isEmpty() || !ISpellContainer.isSpellContainer(stack)) {
                continue;
            }
            ISpellContainer container = ISpellContainer.get(stack);
            if (container == null) {
                continue;
            }
            List<SpellSlot> slots = container.getActiveSpells();
            if (slots == null) {
                continue;
            }
            for (int i = 0; i < slots.size(); i++) {
                SpellSlot slot = slots.get(i);
                if (slot == null || slot.getSpell() == null) {
                    continue;
                }
                AbstractSpell spell = slot.getSpell();
                all.add(new Option(stack, i, spell, slot.getLevel(),
                        classify(spell), labelOf(spell)));
            }
        }
        // ★ 同一个法术在多本书里重复时，留等级最高的那个
        Map<String, Option> best = new java.util.LinkedHashMap<>();
        for (Option o : all) {
            // ★★ 第 14 轮核实补上的漏洞：**指令此前只管住了 Goety 聚晶，管不住铁魔法**。
            //   `focus_category`（只用某类聚晶）与 `ban_focus`（禁用某颗法术）只在
            //   {@code GoetyFocusOps.add(...)} 里过滤 ⇒ 委托方下了"只用治疗"，
            //   她的铁魔法攻击法术照样会入选 —— 指令看起来生效了，其实只生效了一半。
            //   ★ 修在【枚举阶段】（与 goety 同一条纪律：不能"选中后再拒绝"）。
            if (applyGates && !com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter
                    .focusAllowed(bus, com.touhoulittlemad.fightlikeplayer.decision
                            .DirectiveFilter.SpellSystem.IRONS,
                    o.intent().name(), o.label())) {
                // ★★ 第二十一轮：这条判定**每 tick 都被问一次**（池子由 irons:can_cast 事实重建）
                //   ⇒ 与 Goety 同一条纪律：节流，否则 debug 日志被它刷爆。
                if (BLOCK_LOG.should(maid.getUUID() + "|" + o.label(),
                        maid.level().getGameTime())) {
                    FightLikePlayer.LOGGER.debug("[FLP] 指令挡下这个铁魔法：{}（{}）", o.label(),
                            o.intent());
                }
                continue;
            }
            // ★★ 召唤位满 ⇒ 召唤类法术不进池子（第十七轮续；与 Goety 同一条判据）
            //   委托方实测的"召唤恼鬼用一次后很难再用"：主因是**铁魔法自己的 150 秒冷却**
            //   （`javap SummonVexSpell` = setCooldownSeconds(150.0)），而这条门保证她
            //   在**已经有召唤物**时不会把召唤法术再排进池子。
            if (applyGates && o.intent() == Intent.SUMMON && !canSummon) {
                FightLikePlayer.LOGGER.debug("[FLP] 召唤位已满 ⇒ 跳过召唤法术：{}", o.label());
                continue;
            }
            Option old = best.get(o.label());
            if (old == null || o.level() > old.level()) {
                best.put(o.label(), o);
            }
        }
        return List.copyOf(best.values());
    }

    /** 法术 id（{@code irons_spellbooks:fireball} 这种）。取不到时退回类名。 */
    public static String labelOf(AbstractSpell spell) {
        try {
            // ★ ISS 的 id 出口是 AbstractSpell#getSpellResource()（javap 实测 3.16.3）
            ResourceLocation id = spell.getSpellResource();
            if (id != null) {
                return id.toString();
            }
        } catch (RuntimeException ignore) {
            // 自定义法术可能在注册前就取 id ⇒ 退回类名
        }
        return spell.getClass().getSimpleName();
    }

    /**
     * ★ <b>意图分类</b> —— <b>关键词推断</b>（证据等级：推断，不是勘测；见类注释的警告）。
     *
     * <p>顺序有意：<b>支援 → 召唤 → 攻击</b>。
     * 因为"治疗/护盾"类法术的名字里几乎不含攻击词，而反过来不然
     * （例如 {@code blood_slash} 是攻击、{@code blood_heal} 是支援）。
     */
    public static Intent classify(AbstractSpell spell) {
        // ★★ W32：先查【已复核的表】（按类简单名），查不到才退回关键词
        String cls = spell.getClass().getSimpleName();
        var byTable = com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntent
                .ironsIntent(cls, cls + " " + labelOf(spell));
        // ★ 两个枚举逐字同名（vectors.json 的 overrides 就按这些名字索引）⇒ 按名字转，
        //   不一致会在这里立刻抛出来，而不是静默挑错法术。
        return Intent.valueOf(byTable.name());
    }

    /**
     * 纯字符串版的分类（委托给纯逻辑层的 {@link SpellIntent}）。
     *
     * <p>★ 关键词表在纯逻辑层 —— 那样才能离线断言"这个法术会被判成哪一类"。
     * 本方法只是把 compat 层能拿到的名字送进去。
     */
    public static Intent classifyName(String raw) {
        // 纯逻辑层的枚举与这里的枚举【逐字同名】（因为 vectors.json 的 overrides 就是按这些名字索引的）
        // ⇒ 按名字转一次，保证两边的键永远一致；不一致会在这里立刻抛出来，而不是静默挑错法术。
        return Intent.valueOf(com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntent
                .ironsIntentByName(raw).name());
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★★ <b>按需求从"她真正拥有的法术"里挑一个</b>。
     *
     * <p>判据与决策层选动作<b>完全一致</b>：意图向量离 {@code need} 最近的意图胜出；
     * 同一意图里有多个法术时，选<b>最久没用的那个</b>（避免又变成「永远只用同一个」）。
     *
     * <p>★ 判据本体在纯逻辑层 {@code SpellPicker}（与 Goety 共用同一份，且可离线断言）。
     */
    public static Option pick(List<Option> options, NeedVector need,
                              Map<String, NeedVector> overrides, SpringConfig config,
                              Map<String, Long> lastUsed, long now) {
        return pick(options, need, overrides, config, lastUsed, now, java.util.Set.of());
    }

    /**
     * ★★ 同上，但可以<b>排除还在自己冷却里的法术</b>（第十一轮：冷却的权威来源是法术自己）。
     *
     * @param excluded 不参与挑选的标签（{@code AbstractSpell#getSpellCooldown()} 还没到）
     * @return 选中的法术；<b>全部被排除</b>时 {@code null}（调用方据此说"都在冷却"）
     */
    public static Option pick(List<Option> options, NeedVector need,
                              Map<String, NeedVector> overrides, SpringConfig config,
                              Map<String, Long> lastUsed, long now, java.util.Set<String> excluded) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        java.util.List<com.touhoulittlemad.fightlikeplayer.decision.thinking
                .SpellPicker.Choice> choices = new java.util.ArrayList<>(options.size());
        for (Option o : options) {
            choices.add(new com.touhoulittlemad.fightlikeplayer.decision.thinking
                    .SpellPicker.Choice(o.label(), o.intent().name()));
        }
        int idx = com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellPicker
                .pickIndex(choices, need, overrides,
                        config == null ? SpringConfig.defaults() : config, lastUsed, excluded);
        return idx < 0 ? null : options.get(idx);
    }

    /**
     * ★★ <b>这颗法术要多久才能再放</b>（ISS 自己的数）。
     *
     * <p>{@code AbstractSpell#getSpellCooldown()} = {@code COOLDOWN_IN_SECONDS × 20}（tick）。
     * <p>★ 为什么必须由我们读：玩家侧 {@code castSpell(...)} 里
     * {@code MagicHelper.MAGIC_MANAGER.addCooldown(serverPlayer, ...)} 的形参是
     * <b>{@code ServerPlayer}</b> ⇒ <b>女仆从来没有冷却</b>（第十一轮取证）。
     * <p>读不到（抛异常）⇒ 返回 0（fail-open：宁可多放，不能把法术永久禁掉）。
     */
    public static int cooldownTicksOf(Option option) {
        if (option == null || option.spell() == null) {
            return 0;
        }
        try {
            return Math.max(0, option.spell().getSpellCooldown());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * ★★ <b>这颗法术的前摇要多少 tick</b>（ISS 自己的数）。
     *
     * <p>玩家侧 {@code attemptInitiateCast} 会先等 {@code getEffectiveCastTime(level, entity)} 个 tick
     * （LONG / CONTINUOUS 才有；INSTANT 为 0）。而我们与 ISS 自己的 mob 路径一样直接调
     * {@code onCast} —— 那是<b>效果</b>钩子 ⇒ <b>前摇被跳过</b>（第十一轮取证）。
     * <p>⇒ 我们**自己补上这段前摇**（见 {@code ActionExecutors.Phase.CAST_WINDUP}）。
     */
    public static int windupTicksOf(EntityMaid maid, Option option) {
        if (option == null || option.spell() == null) {
            return 0;
        }
        try {
            var type = option.spell().getCastType();
            if (type == null || type == io.redspace.ironsspellbooks.api.spells.CastType.INSTANT
                    || type == io.redspace.ironsspellbooks.api.spells.CastType.NONE) {
                return 0;
            }
            return Math.max(0, option.spell().getEffectiveCastTime(option.level(), maid));
        } catch (Throwable t) {
            return 0;                      // 读不到 ⇒ 不补前摇（fail-open，绝不让施法卡住）
        }
    }

    /**
     * 施放选中的法术 —— <b>走 ISS 自己的 mob 路径</b>（{@code CastSource.MOB}）。
     *
     * <p>★ 与 TLM/万法皆通的做法一致：不经 {@code canBeCastedBy}（那是 Player 签名）
     * ⇒ 不检查"是否学习过"、不扣蓝。
     */
    public static boolean cast(EntityMaid maid, Option option) {
        if (option == null || option.spell() == null) {
            return false;
        }
        try {
            option.spell().onCast(maid.level(), option.level(), maid, CastSource.MOB,
                    MagicData.getPlayerMagicData(maid));
            FightLikePlayer.LOGGER.info("[FLP] 铁魔法施放：{}（从 {} 第 {} 槽）",
                    option.label(), option.container().getHoverName().getString(),
                    option.slotIndex() + 1);
            return true;
        } catch (RuntimeException e) {
            FightLikePlayer.LOGGER.info("[FLP] 铁魔法施放失败：{} —— {}", option.label(), e.toString());
            return false;
        }
    }
}
