package com.touhoulittlemad.fightlikeplayer.compat.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>Curios 饰品槽枚举</b>（W5，2026-10-01 落地）。
 *
 * <h2>为什么女仆的饰品槽必须枚举（而不是"锦上添花"）</h2>
 * 委托方实测「万法皆通里聚晶存在【聚晶包/多晶大袋】中」，而
 * <b>Goety 的聚晶包本身就是一件 Curios 饰品</b>（{@code FocusBag}/{@code FocusPack}
 * 实现 {@code ICurioItem}）⇒ 女仆把包戴在饰品槽里时，<b>只扫物品栏是扫不到包的</b>，
 * 包里的聚晶于是全部不可见（"她明明有 20 颗聚晶，却一颗都用不了"）。
 * 铁魔法一侧同理：饰品（戒指/项链）与法书都在 Curios 里。
 *
 * <p>TLM 的女仆<b>确实有饰品槽</b>：{@code MaidCurioSlot} /
 * {@code CuriosContainer} / {@code CuriosSlotRef}（TLM 源码 {@code compat/curios} 包）
 * ⇒ 这不是"她大概没有"，是<b>已经确认有的</b>。
 *
 * <h2>★ 隔离性：Curios 未装时本类也必须安全</h2>
 * {@code build.gradle} 里 Curios 是 <b>{@code compileOnly}</b>（绝不 runtimeOnly）
 * ⇒ 未装 Curios 的整合包里，本类<b>可以被加载但不可被调用</b>。
 * 因此：<b>①</b> 先问 {@code ModList}；<b>②</b> 整段 {@code catch (Throwable)}
 * （{@code NoClassDefFoundError} 是 {@code Error} 不是 {@code Exception}，
 * 只 catch {@code Exception} 会漏）。这条纪律与 {@code check_isolation.py} 的分组一致：
 * compat 层允许引用第三方，但入口层不允许。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.1（位置 8：饰品槽）</a>
 */
public final class CuriosSlots {

    private CuriosSlots() {
    }

    /** 已经警告过一次（避免每 tick 刷屏）。 */
    private static boolean warned;

    /** Curios 是否装了（纯 {@code ModList} 查询，不碰 Curios 的类）。 */
    public static boolean isAvailable() {
        try {
            return ModList.get().isLoaded("curios");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 她饰品槽里的全部非空物品（<b>顺序 = Curios 的槽位类型顺序</b>）。
     *
     * <p>Curios 未装 / API 出任何差错 ⇒ 返回空表（<b>绝不抛</b>）：
     * 饰品只是"多一个来源"，拿不到不该让整个决策循环挂掉。
     */
    public static List<ItemStack> stacksOf(LivingEntity entity) {
        if (entity == null || !isAvailable()) {
            return List.of();
        }
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity);
            var handler = opt.resolve().orElse(null);
            if (handler == null) {
                return List.of();                 // 没有饰品能力（比如普通生物）
            }
            List<ItemStack> out = new ArrayList<>();
            for (var entry : handler.getCurios().entrySet()) {
                var stacks = entry.getValue().getStacks();
                if (stacks == null) {
                    continue;
                }
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack s = stacks.getStackInSlot(i);
                    if (s != null && !s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                com.touhoulittlemad.fightlikeplayer.FightLikePlayer.LOGGER.warn(
                        "[FLP] ⚠ Curios 饰品槽读取失败（已降级为「没有饰品」，本进程内不再重复警告）：{}",
                        t.toString());
            }
            return List.of();
        }
    }

    /** 诊断用：一行描述她的饰品（`/flp whyfull` 之类的地方）。 */
    public static String describe(LivingEntity entity) {
        if (!isAvailable()) {
            return "（未装 Curios）";
        }
        List<ItemStack> items = stacksOf(entity);
        if (items.isEmpty()) {
            return "（空）";
        }
        StringBuilder sb = new StringBuilder("饰品 ").append(items.size()).append(" 件：");
        for (ItemStack s : items) {
            sb.append(' ').append(s.getHoverName().getString());
        }
        return sb.toString();
    }
}
