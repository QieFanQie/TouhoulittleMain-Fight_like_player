package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.function.Predicate;

/**
 * ★★ <b>把物品从"她的全部可用背包"换到手上</b> —— 主手与副手共用。
 *
 * <h2>★★ 为什么不能用 TLM 的 {@code TaskEquipUtil.tryEquipFromBackpack}（委托方第十一轮实测）</h2>
 * 委托方原话：「似乎 <b>goety 魔杖在物品栏时，女仆无法把它拿到主手</b>（哪怕主手没有东西）。」
 *
 * <p>根因（读 TLM 源码即得，一行）：
 * <pre>
 *   TaskEquipUtil.tryEquipFromBackpack(...)   // TaskEquipUtil.java:21
 *       var backpack = maid.getAvailableBackpackInv();     ← ★ 只有 TLM 的【小背包】
 *       int slot = ItemsUtil.findStackSlot(backpack, predicate);
 * </pre>
 * 而女仆随身携带东西的地方是 <b>27 格物品栏</b>（{@code getAvailableInv(true)}，
 * 它<b>包含</b>小背包，见 {@link MaidSnapshot} 的类注释）
 * ⇒ <b>只有"放进 TLM 小背包"的东西才换得上来</b>，放物品栏里的法杖永远换不上来。
 *
 * <p>⇒ 本类改用 {@code getAvailableInv(true)}（全量）+ 自己做"取出—放回"的交换。
 * 语义与 TLM 的版本一致（<b>把手上原来的东西放回被取走的那一格</b>，不凭空丢东西），
 * 只是搜索范围从"小背包"扩到"她真正能用的全部背包"。
 *
 * <h2>★ 为什么主手和副手要共用一份</h2>
 * 委托方对副循环的要求是「举盾 = <b>确认副手上是否是盾并把盾放在副手上</b>」——
 * 也就是说<b>副手也要能换</b>。而"换到手上"这件事只有一个正确写法，
 * 两份实现必然会漂移（本项目已经吃过"两处口径不一致"的亏，见 docs/13 第 38 条）。
 */
public final class HandEquip {

    private HandEquip() {
    }

    /**
     * 确保手上是满足 {@code want} 的物品；不是就从背包换上来。
     *
     * <h2>★★ 第十二轮修：范围必须与候选集完全一致（否则"解析期认得出、执行期找不到"）</h2>
     * 搜索顺序：
     * <ol>
     *   <li><b>{@code getAvailableInv(true)}</b>（TLM 语义：背包等级允许的前 N 格 + 双手）
     *       —— 优先，因为这是 TLM 自己认为"她够得到"的范围；</li>
     *   <li><b>{@code getMaidInv()}（全部 36 格）</b> —— 兜底。★ 为什么需要：背包换小之后，
     *       原来放在高位格子里的东西<b>还在她身上却谁也看不见</b>
     *       （{@code BackpackLevel}：无背包 = 6 / 小 12 / 中 24 / 大 36）。
     *       委托方实测的"法杖在物品栏却拿不到主手"就是这一种。</li>
     * </ol>
     * ★ 两处理由同一个 helper（{@link MaidInventory}）保证"候选集看到的东西 = 换手够得到的东西"。
     *
     * @param hand {@link InteractionHand#MAIN_HAND} 或 {@link InteractionHand#OFF_HAND}
     * @return 换手后手上有合规物品 ⇒ true
     */
    public static boolean equipFromInventory(EntityMaid maid, InteractionHand hand,
                                             Predicate<ItemStack> want) {
        ItemStack cur = maid.getItemInHand(hand);
        if (!cur.isEmpty() && want.test(cur)) {
            return true;                       // 已经在手上
        }
        // ① TLM 的可用范围
        if (swapIn(maid, hand, want, maid.getAvailableInv(true), cur)) {
            return true;
        }
        // ② ★ 兜底：全部 36 格（背包换小之后被"藏起来"的那些）
        if (swapIn(maid, hand, want, maid.getMaidInv(), cur)) {
            FightLikePlayer.LOGGER.info(
                    "[FLP] ★ 换手（{}）用到了【当前背包等级之外】的格子 —— 见 docs/13 第 50 条：{}",
                    hand == InteractionHand.OFF_HAND ? "副手" : "主手", MaidSnapshot.itemId(cur));
            return true;
        }
        return false;                          // 她身上真的没有这件东西
    }

    /** 在给定容器里找一件、换到手上并把手上的原物放回那一格。 */
    private static boolean swapIn(EntityMaid maid, InteractionHand hand, Predicate<ItemStack> want,
                                  IItemHandler inv, ItemStack cur) {
        int slot = findSlot(inv, want);
        if (slot < 0) {
            return false;
        }
        ItemStack picked = inv.extractItem(slot, inv.getStackInSlot(slot).getCount(), false);
        if (picked.isEmpty()) {
            return false;
        }
        // ★ 把手上的原物放回【刚被取走的那一格】—— 与 TLM 的做法一致，绝不凭空吞掉东西
        if (!cur.isEmpty()) {
            ItemStack leftover = inv.insertItem(slot, cur, false);
            if (!leftover.isEmpty()) {
                // 那一格放不下（理论上不会：刚清空过）⇒ 退回去，避免物品消失
                inv.insertItem(slot, picked, false);
                FightLikePlayer.LOGGER.debug("[FLP] 换手放弃：{} 放不回背包", MaidSnapshot.itemId(cur));
                return false;
            }
        }
        maid.setItemInHand(hand, picked);
        FightLikePlayer.LOGGER.debug("[FLP] ★ 换手（{}）：{} → {}",
                hand == InteractionHand.OFF_HAND ? "副手" : "主手",
                cur.isEmpty() ? "(空手)" : MaidSnapshot.itemId(cur), MaidSnapshot.itemId(picked));
        return true;
    }

    /** 找第一个满足条件的格子（空格子不算）。 */
    private static int findSlot(IItemHandler inv, Predicate<ItemStack> want) {
        if (inv == null) {
            return -1;
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && want.test(s)) {
                return i;
            }
        }
        return -1;
    }
}
