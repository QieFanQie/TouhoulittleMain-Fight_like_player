package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>「她身上真正能用的物品」的口径</b> —— <b>全项目只此一处</b>。
 *
 * <h2>★★ 委托方第十二轮实测暴露的坑：女仆的"可用容量"由<b>背包等级</b>决定</h2>
 * 委托方原话：「似乎 goety 魔杖在物品栏时，女仆无法把它拿到主手（哪怕主手没有东西）」
 * —— 而且**上一轮的"修法"没修好**，因为我当时以为问题在"搜了小背包而不是物品栏"。
 *
 * <p>真正的机制（读源码，两个方法**逐字一样**）：
 * <pre>
 *   EntityMaid.getAvailableInv(boolean handsFirst)   // :2299
 *       int maxContainerIndex = getMaidBackpackType().getAvailableMaxContainerIndex();
 *       RangedWrapper(maidInv, 0, maxContainerIndex)     ← ★ 只暴露前 N 格
 *   EntityMaid.getAvailableBackpackInv()             // :2309
 *       int maxContainerIndex = getMaidBackpackType().getAvailableMaxContainerIndex();
 *       RangedWrapper(maidInv, 0, maxContainerIndex)     ← ★ 与本行上面那个完全同一个范围
 * </pre>
 * 而 {@code maidInv} 的真实大小是 <b>36 格</b>（{@code MaidBackpackHandler(36)}，`:271`），
 * {@code BackpackLevel} 的容量是：
 * <pre>
 *   EMPTY_CAPACITY = 6    SMALL = 12    MIDDLE = 24    BIG = 36
 * </pre>
 * ⇒ <b>没背包的女仆只能用前 6 格；换过背包（变小）之后，原来放在高位格子里的东西
 * 就"还在她身上、但谁也看不见"。</b> 而 TLM 的换手工具与我这轮的"修法"用的都是这个范围
 * ⇒ <b>两处都找不到它</b> —— 委托方看到的现象就成立了。
 *
 * <h2>★ 处置：把范围统一成"可用范围 ∪ 全部 36 格"，并只在这一处定义</h2>
 * 为什么并入"高位格子"：那些格子里的东西是<b>玩家放进去的</b>（只是背包换过之后露不出来），
 * 说"女仆没有它"不符合事实、也不是委托方的心智模型（他的原话是"在物品栏"）。
 * <p>★ 为什么必须**全项目一处口径**：候选集（解析期）与换手（执行期）必须看到同一批物品，
 * 否则就会出现"解析期认得出、执行期找不到"（docs/13 第 38 条那个错配）。
 * ⇒ 现在的 5 个枚举点全部走 {@link #usableStacks}：
 * {@code MaidSnapshot.possessed} · {@code GoetyFocusOps.owned} ·
 * {@code IronsSpells.containers} · {@code IronsExecutors.candidates} · {@code HandEquip}。
 *
 * <p>★ 可观测：一旦真的用到了"高位格子"里的物品，会打一条日志（见 {@link #logHiddenOnce}）——
 * 这样"她为什么突然会用法杖了"永远查得到，而不是玄学。
 */
public final class MaidInventory {

    private MaidInventory() {
    }

    /** 已经提醒过"用了高位格子"的女仆（避免刷屏）。 */
    private static final Map<java.util.UUID, Boolean> WARNED = new java.util.WeakHashMap<>();

    /**
     * 她身上能用的全部物品栈（**已去重**，顺序稳定：可用范围在前、高位格子在后）。
     *
     * <p>★ 返回的是 {@code ItemStack} 的引用快照（不是副本）：调用方只读不写。
     */
    public static List<ItemStack> usableStacks(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        // ① 可用范围（TLM 语义：背包等级决定）—— 含双手（handsFirst=true）
        collect(maid == null ? null : safe(() -> maid.getAvailableInv(true)), out);
        // ② ★ 剩余的高位格子（当前背包等级之外的，仍然"在她身上"）
        int before = out.size();
        collect(safe(() -> maid.getMaidInv()), out);
        if (out.size() > before) {
            logHiddenOnce(maid, out.size() - before);
        }
        return new ArrayList<>(out.values());
    }

    /** 可用范围内的物品数（诊断用）。 */
    public static int availableCount(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        collect(safe(() -> maid.getAvailableInv(true)), out);
        return out.size();
    }

    /** 全量（36 格 + 双手）里的物品数（诊断用）。 */
    public static int totalCount(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        collect(safe(() -> maid.getMaidInv()), out);
        collect(safe(() -> maid.getAvailableInv(true)), out);
        return out.size();
    }

    /** 一行诊断："可用 6 格里有 X 件 / 全部 36 格里有 Y 件"。 */
    public static String describeCoverage(EntityMaid maid) {
        return "可用范围 " + availableCount(maid) + " 件 · 全量 " + totalCount(maid) + " 件";
    }

    /** 把某个 handler 里的非空物品并进结果（按物品 id+数量+槽位来源去重）。 */
    private static void collect(IItemHandler inv, Map<String, ItemStack> out) {
        if (inv == null) {
            return;
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.isEmpty()) {
                continue;
            }
            // ★ 去重键：同一个 ItemStack 实例只收一次（可用范围与全量必然重叠）
            String key = System.identityHashCode(s) + ":" + i + ":" + s.getCount();
            out.putIfAbsent(key, s);
        }
    }

    /** 第一次发现"只能用高位格子里的东西"时提醒一次（可观测）。 */
    private static void logHiddenOnce(EntityMaid maid, int hidden) {
        if (maid == null || hidden <= 0) {
            return;
        }
        if (WARNED.putIfAbsent(maid.getUUID(), Boolean.TRUE) == null) {
            FightLikePlayer.LOGGER.info(
                    "[FLP] ⓘ {} 身上有 {} 件物品在【当前背包等级之外】的格子里（背包换小过？）"
                            + "—— 已按「仍然带在身上」处理（可用 {} / 全量 {}）",
                    maid.getName().getString(), hidden, availableCount(maid), totalCount(maid));
        }
    }

    /** 任何一步出错都不该让调用方挂掉：容器异常 ⇒ 当空表。 */
    private static IItemHandler safe(java.util.function.Supplier<IItemHandler> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 卸载时清提示状态。 */
    public static void forget(java.util.UUID maidId) {
        WARNED.remove(maidId);
    }
}
