package com.touhoulittlemad.fightlikeplayer.compat.exec.irons;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.List;

/**
 * <b>Iron's Spells 'n Spellbooks（铁魔法）执行器</b>。
 *
 * <h2>女仆施法路径（与玩家不同）</h2>
 * <pre>
 *   玩家：castSpell(...)  ──▶ attemptInitiateCast(...) ──▶ canBeCastedBy(…, Player)   ← 形参写死 Player
 *   女仆：★ 直接 onCast(Level, spellLevel, LivingEntity, CastSource, MagicData)
 * </pre>
 * ⇒ <b>不走 {@code canBeCastedBy}</b>（那是 Player 签名），因此：
 * <ul>
 *   <li><b>不检查"是否已学习"</b>；</li>
 *   <li><b>不扣蓝</b>（与 [Q7 白嫖] 的决策一致）；</li>
 *   <li>这也正是 ISS <b>自己的 mob 路径</b>的做法（{@code CastSource.MOB}）。</li>
 * </ul>
 *
 * <p>API 出处（javap 实测 irons_spellbooks 1.20.1-3.16.3）：
 * <pre>
 *   AbstractSpell#onCast(Level, int spellLevel, LivingEntity, CastSource, MagicData)
 *   ISpellContainer#isSpellContainer(ItemStack)  static · get(ItemStack) static
 *   ISpellContainer#getActiveSpells()            → List&lt;SpellSlot&gt;
 * </pre>
 *
 * <h2>⚠️ 当前实现的取舍</h2>
 * <ul>
 *   <li>取<b>第一个可用法术槽</b>（尚未按局势挑法术 —— 那需要读法术的意图/等级，属后续）；</li>
 *   <li><b>recast（二段/多段施法）暂未实现</b> —— 它需要跟踪施法会话，见 W36；</li>
 *   <li>AI 目标：ISS 的支援类法术靠 {@code Utils.preCastTargetHelper} 决定打谁，
 *       而它被 mixin 短路成"女仆的锁定目标" ⇒ <b>支援法术可能打到敌人身上</b>
 *       （这是万法皆通时代就存在的已知缺陷，见 docs/01 §6.3）。</li>
 * </ul>
 */
public final class IronsExecutors {

    private IronsExecutors() {
    }

    /**
     * 从女仆的持有物里找一个法术容器（法术书 / 卷轴），施放其中第一个法术。
     *
     * @return 是否成功发起施法
     */
    public static boolean castFirstSpell(EntityMaid maid) {
        for (ItemStack stack : candidates(maid)) {
            if (!ISpellContainer.isSpellContainer(stack)) {
                continue;
            }
            ISpellContainer container = ISpellContainer.get(stack);
            if (container == null) {
                continue;
            }
            List<SpellSlot> slots = container.getActiveSpells();
            if (slots == null || slots.isEmpty()) {
                continue;
            }
            SpellSlot slot = slots.get(0);
            AbstractSpell spell = slot.getSpell();
            if (spell == null) {
                continue;
            }
            int level = slot.getLevel();
            // ★ 不走 canBeCastedBy（Player 签名）；与 ISS 自己的 mob 路径一致
            spell.onCast(maid.level(), level, maid, CastSource.MOB,
                    MagicData.getPlayerMagicData(maid));
            return true;
        }
        return false;
    }

    /** 女仆有没有任何可施放的法术容器（{@code castFirstSpell} 的前置判据）。 */
    public static boolean hasAnySpell(EntityMaid maid) {
        for (ItemStack stack : candidates(maid)) {
            if (ISpellContainer.isSpellContainer(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★★ <b>这一件物品是不是"法术容器"</b> —— 专门给 {@code MaidSnapshot} 喂具名能力用。
     *
     * <h2>为什么必须补这个方法（2026-10-01 实测「铁魔法法术全部无法释放」的根因）</h2>
     * {@code carriers.json} 里 {@code irons:spellbook_or_scroll} 的判据写的是
     * <b>{@code capability: "irons:isSpellContainer"}</b>（设计上就是"由适配器算好的具名谓词"），
     * 但 {@code MaidSnapshot.capabilities()} 当时<b>只产出 {@code attr:*} 与 {@code enchant:*}</b>
     * ⇒ <b>这个键从来没被喂过</b> ⇒ 载体规则永不命中 ⇒
     * <b>铁魔法一个候选都进不了</b>（连"选中"都不会发生，日志里查不到任何 irons 行）。
     *
     * <p>★ 教训与 [13 第 21 条](../../../../../../../docs/13-更正记录与教训.md) 同类：
     * <b>清单声明了一个键，就必须有人真的喂它</b> —— 否则是一条静默的死路。
     * 现由 {@code tools/audit_capabilities.py} 兜底：把 carriers.json 里所有
     * {@code capability} 键与游戏侧真正投喂的键对账。
     *
     * <p>⚠️ 判据用静态谓词 {@code ISpellContainer.isSpellContainer(stack)}（即 NBT 有 {@code ISB_Spells}），
     * <b>不是类型判定</b> ⇒ 法术书与卷轴都覆盖（卷轴不在 {@code SpellBook} 继承树上）。
     */
    public static boolean isSpellContainer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return ISpellContainer.isSpellContainer(stack);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 候选物品：主手 / 副手 / 背包（{@code getAvailableInv(true)}）/ <b>饰品槽</b>。
     *
     * <p>★ 与 {@code MaidSnapshot} 的口径一致 —— <b>不限主副手</b>（委托方 M2 注记）。
     * <p>★ 2026-10-01（W5）：三项口径（本方法 / {@code IronsSpells.containers} /
     * {@code MaidSnapshot.possessed}）<b>必须逐项一致</b> ——
     * 否则"解析期认得出、执行期找不到"，正是这个项目反复踩的那类错配。
     */
    private static Iterable<ItemStack> candidates(EntityMaid maid) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        out.add(maid.getMainHandItem());
        out.add(maid.getOffhandItem());
        // ★★ 统一口径：可用范围 ∪ 全部 36 格（见 MaidInventory 的类注释）
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory
                .usableStacks(maid));
        out.addAll(com.touhoulittlemad.fightlikeplayer.compat.curios.CuriosSlots.stacksOf(maid));
        return out;
    }
}
