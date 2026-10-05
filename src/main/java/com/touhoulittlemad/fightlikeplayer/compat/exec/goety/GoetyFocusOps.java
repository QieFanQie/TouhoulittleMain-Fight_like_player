package com.touhoulittlemad.fightlikeplayer.compat.exec.goety;

import com.Polarice3.Goety.api.items.magic.IFocus;
import com.Polarice3.Goety.api.items.magic.IWand;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.api.magic.ISummonSpell;
import com.Polarice3.Goety.api.magic.SpellType;
import com.Polarice3.Goety.common.items.handler.FocusBagItemHandler;
import com.Polarice3.Goety.common.items.handler.SoulUsingItemHandler;
import com.Polarice3.Goety.common.magic.Spell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Goety：把"她拥有的所有聚晶"变成可选的施法，并在施法前装进法杖</b>。
 *
 * <h2>★★ 为什么必须有它（委托方 2026-10-01 第 2 条）</h2>
 * 委托方：「现在魔杖内有聚晶时已经可以施法了，但：可以看一下<b>万法皆通</b> ——
 * 一般来说 Goety 的聚晶存储在<b>聚晶包 / 多晶大袋</b>中。女仆的动作选项里应包含
 * <b>所有可选的法术</b>（物品栏内聚晶、聚晶包/多晶大袋中聚晶、杖内聚晶），
 * 并在判定释放该法术后释放（<b>若杖内有旧聚晶则调换位置，若没有则填充</b>）。」
 *
 * <p>旧实现只认<b>主手法杖里已经装着的那一颗</b>聚晶 ⇒ 女仆背包里囤了一堆聚晶等于不存在。
 *
 * <h2>★ 取证：Goety 自己是怎么做的（源码，不是猜）</h2>
 * <pre>
 *   法杖里的聚晶槽  : IWand.getFocus(stack)
 *                     → SoulUsingItemHandler.get(stack).getSlot()
 *                     ⇒ 法杖有一个 ItemStackHandler，槽位 0 放聚晶，isItemValid = instanceof IFocus
 *   聚晶包 / 多晶大袋: FocusBag（FocusPack extends FocusBag）
 *                     → 物品自带 capability: FocusBagItemHandler
 *                     ⇒ 与法杖同构，只是格数不同
 *   ★ 玩家侧的"调换"  : CSwapFocusPacket.swapFocus(int swapSlot, Player)（原样三行）
 *                       ItemStack wandFocus = wandHandler.getSlot();
 *                       ItemStack bagFocus  = bagHandler.getStackInSlot(swapSlot);
 *                       bagHandler.setStackInSlot(swapSlot, wandFocus);   // 旧的放回包里
 *                       wandHandler.extractItem();
 *                       wandHandler.insertItem(bagFocus);                 // 新的装进杖里
 * </pre>
 * ⇒ 本类<b>逐字复刻</b>那三行（只是把 {@code Player} 换成女仆自己的容器）：
 * <b>「有旧的就调换位置，没有就填充」</b>正是 {@code swapFocus} 的语义
 * （{@code setStackInSlot(bagSlot, wandFocus)} 在 {@code wandFocus} 为空时就是把包那一格清空）。
 *
 * <h2>⚠️ 意图分类的证据等级</h2>
 * 与铁魔法那侧同一条声明：分类是<b>按聚晶/法术的类与属性推断</b>的（见 {@link #classify}），
 * <b>不是勘测结论</b>。★ 分类结果会打进日志，所以<b>错了看得见</b>。
 * 彻底的方案仍是 W14/W32（读调律师的 {@code FocusClassifier} 或自研分类器）。
 *
 * <h2>⚠️ 已知限制</h2>
 * 聚晶包若被放在 <b>Curios 饰品槽</b>（{@code FocusBag} 实现了 {@code ICurioItem}），
 * 也已能扫到 —— 2026-10-01 W5 落地（{@link #owned} 里加了饰品槽）。
 */
public final class GoetyFocusOps {

    private GoetyFocusOps() {
    }

    /** 聚晶来源（诊断用：日志里能看出"这颗是从哪拿的"）。 */
    public enum Source {
        WAND("杖内"), INVENTORY("物品栏"), BAG("聚晶包/大袋");

        /** 中文显示名（日志与命令用）。 */
        public final String zh;

        Source(String zh) {
            this.zh = zh;
        }
    }

    /**
     * 一颗"她现在真的能用的聚晶"。
     *
     * @param stack   聚晶物品栈
     * @param spell   它对应的法术（{@code IFocus#getSpell()}）
     * @param category 战术类别（推断，见 {@link #classify}）
     * @param source  从哪找到的
     * @param bagSlot ★ 若来自聚晶包，是包里的第几格；否则 {@code -1}
     *                —— 调换时要把杖里的旧聚晶放回<b>这一格</b>
     * @param label   人读标签（法术 id / 类名）
     */
    public record Focus(ItemStack stack, ISpell spell, Category category, Source source,
                        int bagSlot, String label) {

        @Override
        public String toString() {
            return label + "[" + category + "·" + source.zh + "]";
        }
    }

    /**
     * 战术类别 —— 与 {@code vectors.json} 里 {@code goety:cast_focus.overrides} 的键<b>逐字一致</b>。
     * <p>★ 这就是"W23 拿不到 focusCategory"时那个缺口的<b>临时替代</b>：
     * 我们不再依赖调律师的 {@code FocusClassifier}，而是从法术自身的属性推。
     */
    public enum Category {
        ATTACK_SINGLE, ATTACK_AREA, SUMMON, UTILITY, PROTECTION, HEALING, CONTROL, SERVANT_MGMT
    }

    // ───────────────────────── 枚举 ─────────────────────────

    /**
     * 女仆主手/副手/背包<b>/饰品槽</b>里的全部物品。
     *
     * <p>★★ 2026-10-01（W5）：饰品槽那一项是<b>必须的</b>，不是补充 ——
     * <b>Goety 的聚晶包/多晶大袋本身就是 Curios 饰品</b>
     * （{@code FocusBag}/{@code FocusPack} 实现 {@code ICurioItem}）。
     * 女仆把包戴在饰品槽里时，只扫物品栏会<b>整包看不见</b>
     * ⇒ 观感就是"她明明有一堆聚晶，却永远只用杖里那颗（或干脆没得用）"。
     */
    public static List<ItemStack> owned(EntityMaid maid) {
        List<ItemStack> out = new ArrayList<>();
        out.add(maid.getMainHandItem());
        out.add(maid.getOffhandItem());
        // ★★ 统一口径：可用范围 ∪ 全部 36 格（见 MaidInventory 的类注释 —— 背包等级会隐藏高位格子）
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory
                .usableStacks(maid));
        // ★ Curios 饰品槽（Curios 未装 ⇒ 空表，见 CuriosSlots 的隔离性说明）
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.curios.CuriosSlots.stacksOf(maid));
        return out;
    }

    /**
     * ★★ <b>她拥有的全部聚晶</b>（杖内 + 物品栏 + 聚晶包/多晶大袋）。
     *
     * <p>同一个法术只保留一份（先找到的优先：杖内 &gt; 物品栏 &gt; 包），
     * 避免"同一颗聚晶既在包里又在手上"时列出两遍。
     */
    public static List<Focus> available(EntityMaid maid) {
        // ★ 指令总线（M2 落点 ④）：一次取好，下面逐颗判"这颗在当前指令下允不允许"
        return available(maid, com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .of(maid));
    }

    /**
     * ★★ 同 {@link #available(EntityMaid)}，但<b>可以指定用哪条总线来过滤</b>。
     *
     * <p>★ {@code bus == null} ⇒ <b>不过滤</b>：这是给<b>指挥 LLM 的"战斗场景"</b>用的
     * （见 {@code CombatSceneBuilder}）。理由很重要：
     * 若场景里也按指令过滤，那么她下了"只用治疗"之后，
     * 模型看到的法术池就<b>只剩治疗</b> ⇒ 它会以为"她本来就没有攻击法术"
     * ⇒ 把自己刚下的指令当成既成事实，**越走越偏**。
     * ⇒ 场景必须看到**真正的池子**，被挡住的那几颗另行标记。
     */
    public static List<Focus> available(EntityMaid maid,
                                        com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus bus) {
        Map<String, Focus> bySpell = new java.util.LinkedHashMap<>();
        // ★★ 召唤位（第十七轮续）：仆从数已达上限 ⇒ **召唤类聚晶不进池子**。
        //   为什么必须在这里（而不是候选集过滤）：`goety:cast_focus` 是一条**动作**，
        //   它的召唤性是**按聚晶类别**表达的（override SUMMON）⇒ 动作级过滤会把火球也一起挡掉。
        //   ★ 判据只有一处：{@link com.touhoulittlemad.fightlikeplayer.decision.ServantSlots}。
        boolean canSummon = com.touhoulittlemad.fightlikeplayer.decision.ServantSlots
                .summonAllowed(com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                        .GoetyServantOps.servantCount(maid)
                        + com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                                .IronsServantOps.countSummons(maid));
        if (!canSummon) {
            FightLikePlayer.LOGGER.debug("[FLP] goety：{}", com.touhoulittlemad.fightlikeplayer
                    .decision.ServantSlots.describe(GoetyServantOps.servantCount(maid)
                            + com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                                    .IronsServantOps.countSummons(maid)));
        }
        return collect(maid, bus, canSummon);
    }

    /**
     * ★★ <b>她"到底有没有"这些聚晶标签（不设任何门）</b>（第二十七轮）。
     *
     * <p>为什么要与 {@link #available} 分开：那个会应用**召唤位门**与**她自己指令的过滤**，
     * 而这两条对"她要放什么"是对的、对**"她到底有没有这一颗"**是错的。
     * 委托方实测那次正好踩在上面：**先锋聚晶是 SUMMON 类，而她的召唤位是满的**
     * （日志：`召唤位已满 ⇒ 跳过召唤聚晶：VanguardSpell`）
     * ⇒ 走被门控的枚举会得出"她没有这颗"的**假否定**，比不说更糟。
     *
     * <p>⇒ 只用于 `only_focus`/`ban_focus` 的**参数核对**，绝不用于"放不放"。
     */
    public static java.util.List<String> ownedLabels(EntityMaid maid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (maid == null
                || !net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
            return out;
        }
        try {
            for (Focus f : collect(maid, null, true)) {   // ★ bus=null 且"可以召唤" ⇒ 无门
                out.add(f.label());
            }
        } catch (RuntimeException | LinkageError e) {
            // 读不到 ⇒ 空表
        }
        return out;
    }

    /** 真正的枚举（门由调用方决定 —— 见 {@link #available} 与 {@link #ownedLabels}）。 */
    private static List<Focus> collect(EntityMaid maid,
                                       com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus bus,
                                       boolean canSummon) {
        Map<String, Focus> bySpell = new java.util.LinkedHashMap<>();

        // ① 杖内（最高优先 —— 已经在手上，装都不用装）
        ItemStack wand = maid.getMainHandItem();
        if (wand.getItem() instanceof IWand) {
            add(bySpell, maid, wand, IWand.getFocus(wand), Source.WAND, -1, bus, canSummon);
        }
        // ★★★ ①b 第十二轮修（委托方实测「法杖在背包里就取不出来、不作为」的真正根因）：
        //   **装在背包里那把法杖里的聚晶，此前根本没被枚举** —— 上面那条只看主手那把杖。
        //   ⇒ 若她只带着一把杖（聚晶装在杖里）、身上没有散装聚晶/聚晶包，
        //     那么 available() 返回空 ⇒ customFacts 的 goety:can_cast = false
        //     ⇒ `goety:cast_focus` 在【解析期】就被丢掉 ⇒ 她连"换手"都不会去试 ⇒ 什么都不做。
        //   ★ 而法杖在手上时，① 那条能找到它 ⇒ 一切正常 —— 与委托方的观察完全一致：
        //     「法杖放手上的时候就能放」。
        for (ItemStack s : owned(maid)) {
            if (s.getItem() instanceof IWand && s != wand) {
                // ★ getFocus 是 IWand 的【静态接口方法】⇒ 必须用接口名调用
                add(bySpell, maid, s, IWand.getFocus(s), Source.WAND, -1, bus, canSummon);
            }
        }
        // ② 物品栏 / 副手 / 背包里的散装聚晶
        for (ItemStack s : owned(maid)) {
            if (!s.isEmpty() && s.getItem() instanceof IFocus) {
                add(bySpell, maid, s, s, Source.INVENTORY, -1, bus, canSummon);
            }
        }
        // ③ ★ 聚晶包 / 多晶大袋里的聚晶（委托方明确要求的那一半）
        for (ItemStack s : owned(maid)) {
            FocusBagItemHandler bag = bagOf(s);
            if (bag == null) {
                continue;
            }
            for (int i = 0; i < bag.getSlots(); i++) {
                ItemStack f = bag.getStackInSlot(i);
                if (!f.isEmpty() && f.getItem() instanceof IFocus) {
                    add(bySpell, maid, s, f, Source.BAG, i, bus, canSummon);
                }
            }
        }
        return List.copyOf(bySpell.values());
    }

    /**
     * ★★ 「指令挡下这颗聚晶」的日志节流器（第二十一轮）：同一女仆 + 同一颗聚晶 10 秒内只记一条。
     * <p>为什么必须有：这条判定的调用者是**每 tick 重建的挑选池**（由 {@code can_cast} 事实驱动）
     * ⇒ 一次会话能把 debug 日志刷到 4.7 MB，真正要看的行全被埋掉。
     */
    private static final com.touhoulittlemad.fightlikeplayer.decision.LogThrottle BLOCK_LOG =
            new com.touhoulittlemad.fightlikeplayer.decision.LogThrottle(200);

    /**
     * ★ 收一颗聚晶进池子。
     *
     * <p>★ 第二十一轮：多了一个 {@code maid} 参数 —— 只为了给"指令挡下"的日志做节流
     * （那条判定**每 tick 都被问一次**，不节流会把 debug 日志刷爆，见 {@link #BLOCK_LOG}）。
     */
    private static void add(Map<String, Focus> out, EntityMaid maid, ItemStack owner, ItemStack focusStack,
                            Source source, int bagSlot,
                            com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus bus,
                            boolean canSummon) {
        if (focusStack == null || focusStack.isEmpty()
                || !(focusStack.getItem() instanceof IFocus iFocus)) {
            return;
        }
        ISpell spell = iFocus.getSpell();
        if (spell == null) {
            return;                      // 空聚晶（没有法术）⇒ 放不出来，不算选项
        }
        // ★★ 行为标记：会对施法者自己造成伤害的法术【不进候选】
        //    —— 委托方实测「有的法术会卡住其他行为」，而这一类更糟：它会伤到她自己。
        //    ★ 必须在【枚举阶段】就排除（而不是"选中后再拒绝"）——
        //      否则又变成"反复选中一个做不出来的动作"（docs/13 第 22/25 条）。
        String cls = spell.getClass().getSimpleName();
        String flag = com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntentTable
                .current().flagOf(cls);
        if ("SELF_HARM".equals(flag)) {
            FightLikePlayer.LOGGER.debug("[FLP] 跳过自伤法术：{}（flag=SELF_HARM）", cls);
            return;
        }
        // ★★ 指令对挑选池的过滤（M2 落点 ④，十三轮）：「不召唤」「只用某类聚晶」「禁用某颗」。
        //   ★ 在【枚举阶段】就排除（而不是选中后拒绝）—— 与上面那条 SELF_HARM 同一条纪律。
        String label = spellLabel(spell);
        Category cat = classify(spell);
        if (!com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter.focusAllowed(
                bus, com.touhoulittlemad.fightlikeplayer.decision
                        .DirectiveFilter.SpellSystem.GOETY,
                cat.name(), label)) {
            // ★★ 第二十一轮：这条判定**每 tick 都会被问一次**（池子由 can_cast 事实重建）
            //   ⇒ 不节流的话它会刷屏（实测一次会话 4.7 MB 的 debug.log 主要就是它）。
            if (BLOCK_LOG.should(maid.getUUID() + "|" + label, maid.level().getGameTime())) {
                FightLikePlayer.LOGGER.debug("[FLP] 指令挡下这颗聚晶：{}（{}）", label, cat);
            }
            return;
        }
        // ★★ 召唤位满 ⇒ 召唤类聚晶不进池子（第十七轮续：文档承诺过、代码一直没做）
        if (cat == Category.SUMMON && !canSummon) {
            FightLikePlayer.LOGGER.debug("[FLP] 召唤位已满 ⇒ 跳过召唤聚晶：{}", label);
            return;
        }
        out.putIfAbsent(label, new Focus(focusStack.copy(), spell, cat, source,
                bagSlot, label));
    }

    /** 取物品上的聚晶包能力；不是聚晶包就返回 {@code null}。 */
    public static FocusBagItemHandler bagOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            IItemHandler h = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            return h instanceof FocusBagItemHandler bag ? bag : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 聚晶/法术的人读标签。
     *
     * <p>⚠️ Goety 的法术<b>没有</b>一个像 ISS 那样好用的 {@code SpellRegistry#getKey}，
     * 所以这里用<b>类名</b>（如 {@code FireballSpell} → {@code FireballSpell}）。
     * 它同时是 {@link #classify} 的输入 ⇒ <b>标签的可读性直接影响分类准确度</b>，
     * 因此诊断输出里会把类名与推断出的类别一起打出来（错了看得见）。
     */
    public static String spellLabel(ISpell spell) {
        return spell == null ? "(null)" : spell.getClass().getSimpleName();
    }

    // ───────────────────────── 分类（推断） ─────────────────────────

    /**
     * ★ <b>战术类别</b> —— 按<b>法术自身的证据</b>推（证据等级：推断）。
     *
     * <p>判据顺序（从"最确定"到"最兜底"）：
     * <ol>
     *   <li>{@code spell instanceof ISummonSpell} ⇒ {@code SUMMON}（<b>这是接口级证据，最硬</b>）；</li>
     *   <li>类名/法术名含 治疗系关键词 ⇒ {@code HEALING}；含 护盾/防护系 ⇒ {@code PROTECTION}；</li>
     *   <li>含 控制系（减速/冻结/定身）⇒ {@code CONTROL}；含 仆从管理系 ⇒ {@code SERVANT_MGMT}；</li>
     *   <li>{@code defaultStats().getPotency() > 0}（有强度 = 有伤害）⇒ 按
     *       {@code getRadius() > 0} 分 {@code ATTACK_AREA} / {@code ATTACK_SINGLE}；</li>
     *   <li>都推不出来 ⇒ {@code UTILITY}（★ 保守：它不会被当成攻击去打人）。</li>
     * </ol>
     */
    public static Category classify(ISpell spell) {
        if (spell == null) {
            return Category.UTILITY;
        }
        // ① 接口级证据最硬（不查表）
        if (spell instanceof ISummonSpell) {
            return Category.SUMMON;
        }
        // ② ★★ W32：查【已复核的表】（按类简单名）
        String cls = spell.getClass().getSimpleName();
        String school = spell instanceof Spell s ? String.valueOf(s.getSpellType()) : "";
        var byTable = com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntent
                .goetyCategory(cls, cls + " " + school);
        if (byTable != null) {
            // ★ 两个枚举逐字同名（vectors.json 的 overrides 就按这些名字索引）⇒ 按名字转
            return Category.valueOf(byTable.name());
        }
        // ③ 关键词（表里没有的新法术）→ ④ 属性 → ⑤ UTILITY
        Category byName = classifyName(cls + " " + school);
        if (byName != null) {
            return byName;
        }
        if (spell instanceof Spell s) {
            SpellStat st = s.defaultStats();
            if (st != null) {
                if (st.getPotency() > 0) {
                    return st.getRadius() > 0 ? Category.ATTACK_AREA : Category.ATTACK_SINGLE;
                }
                if (st.getRadius() > 0) {
                    return Category.ATTACK_AREA;
                }
            }
        }
        return Category.UTILITY;
    }

    /**
     * 纯字符串版的关键词分类 —— <b>委托给纯逻辑层的 {@link com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntent}</b>。
     *
     * <p>★ 关键词表在纯逻辑层：那样才能离线断言分类对不对（本项目对"推断类判据"的一贯要求）。
     * 本方法只负责把 compat 层能拿到的名字送进去。
     */
    public static Category classifyName(String raw) {
        var c = com.touhoulittlemad.fightlikeplayer.decision.thinking.SpellIntent
                .goetyCategoryByName(raw);
        if (c == null) {
            return null;
        }
        // 纯逻辑层的枚举与兼容层的枚举<b>逐字同名</b> ⇒ 按名字转一次（两边的键必须一致，
        // 因为 vectors.json 的 overrides 就是按这些名字索引的）
        return Category.valueOf(c.name());
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ───────────────────────── 安装（调换 or 填充） ─────────────────────────

    /**
     * ★★ <b>把指定聚晶装进法杖</b> —— 逐字复刻 {@code CSwapFocusPacket#swapFocus} 的语义：
     * <b>杖里有旧的 ⇒ 与它调换位置；杖里没有 ⇒ 直接填充。</b>
     *
     * <p>来源在聚晶包里时，旧的会被放回<b>包里的那一格</b>（与玩家侧完全一致）；
     * 来源在普通物品栏时，旧聚晶只能"消失"（女仆没有玩家那种"手上拿着再右键"的交互），
     * ⇒ 这种情况下我们<b>把它塞回背包</b>（`getAvailableInv` 里找空位），塞不下才丢。
     *
     * @return 是否装好了（装好后主手杖里就是这颗聚晶）
     */
    public static boolean install(EntityMaid maid, ItemStack wand, Focus focus) {
        if (wand == null || wand.isEmpty() || !(wand.getItem() instanceof IWand)) {
            return false;
        }
        if (focus == null || focus.stack() == null || focus.stack().isEmpty()) {
            return false;
        }
        try {
            ItemStack current = IWand.getFocus(wand);
            if (!current.isEmpty() && current.getItem() == focus.stack().getItem()
                    && ItemStack.isSameItemSameTags(current, focus.stack())) {
                return true;             // 已经就是这颗 ⇒ 无需任何操作
            }
            SoulUsingItemHandler wandHandler = SoulUsingItemHandler.get(wand);
            ItemStack old = wandHandler.getSlot();

            if (focus.source() == Source.BAG) {
                // ★ 与玩家侧 CSwapFocusPacket.swapFocus 完全同构：旧的放回包里那一格
                ItemStack bagStack = findBagStack(maid, focus);
                FocusBagItemHandler bag = bagOf(bagStack);
                if (bag == null || focus.bagSlot() < 0) {
                    return false;
                }
                ItemStack newFocus = bag.getStackInSlot(focus.bagSlot());
                if (newFocus.isEmpty()) {
                    return false;        // 包里的东西被别处动过（不应发生）⇒ 保守放弃
                }
                bag.setStackInSlot(focus.bagSlot(), old);
                wandHandler.extractItem();
                wandHandler.insertItem(newFocus);
                logInstall(old, focus, "调换");
                return true;
            }

            // 来源在物品栏 / 杖内 ⇒ 直接填充（旧的塞回背包）
            ItemStack taken = takeFromInventory(maid, focus.stack());
            if (taken.isEmpty()) {
                return false;
            }
            wandHandler.extractItem();
            wandHandler.insertItem(taken);
            if (!old.isEmpty()) {
                stashBack(maid, old);
            }
            logInstall(old, focus, "填充");
            return true;
        } catch (RuntimeException e) {
            FightLikePlayer.LOGGER.info("[FLP] 装聚晶失败（{}）：{}", focus.label(), e.toString());
            return false;
        }
    }

    private static void logInstall(ItemStack old, Focus focus, String what) {
        FightLikePlayer.LOGGER.info("[FLP] ★ 装聚晶（{}）：{} ← {}（之前杖里是 {}）",
                what, focus.label(), focus.source().zh,
                old == null || old.isEmpty() ? "(空)" : old.getHoverName().getString());
    }

    private static ItemStack findBagStack(EntityMaid maid, Focus focus) {
        for (ItemStack s : owned(maid)) {
            FocusBagItemHandler bag = bagOf(s);
            if (bag == null || focus.bagSlot() < 0 || focus.bagSlot() >= bag.getSlots()) {
                continue;
            }
            ItemStack inSlot = bag.getStackInSlot(focus.bagSlot());
            if (!inSlot.isEmpty() && inSlot.getItem() == focus.stack().getItem()) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 从女仆背包里取出与 {@code want} 同类的那一件（返回取到的那一件，原槽位置空）。 */
    private static ItemStack takeFromInventory(EntityMaid maid, ItemStack want) {
        IItemHandler inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, want)) {
                return inv.extractItem(i, s.getCount(), false);
            }
        }
        // 也可能就在主手（女仆手上直接攥着一颗聚晶）—— 那种情况由换手前置处理，
        // 这里不冒险从主手抽走（会把法杖换掉）。
        return ItemStack.EMPTY;
    }

    /** 把旧聚晶塞回背包；塞不下就只能丢（并记一条日志，不静默）。 */
    private static void stashBack(EntityMaid maid, ItemStack old) {
        IItemHandler inv = maid.getAvailableInv(true);
        ItemStack rest = old.copy();
        for (int i = 0; i < inv.getSlots() && !rest.isEmpty(); i++) {
            rest = inv.insertItem(i, rest, false);
        }
        if (!rest.isEmpty()) {
            FightLikePlayer.LOGGER.info("[FLP] 换下来的旧聚晶塞不回背包（{}），已丢弃", old.getHoverName().getString());
        }
    }

    /** SpellType 的中文名（诊断输出用）。 */
    public static String spellTypeName(ISpell spell) {
        if (spell instanceof Spell s) {
            SpellType t = s.getSpellType();
            return t == null ? "?" : t.getBaseName();
        }
        return "?";
    }
}
