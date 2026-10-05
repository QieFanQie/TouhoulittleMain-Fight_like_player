package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.carrier.CarrierRule;
import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder;
import com.touhoulittlemad.fightlikeplayer.compat.gun.AmmoInfo;
import com.touhoulittlemad.fightlikeplayer.decision.ItemGroups;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ★★ <b>「她的物品清单」按用途分类</b>（第二十五轮，委托方第 2 条）。
 *
 * <h2>委托方原话</h2>
 * > 「在女仆可获取物品信息的部分，在信息处分类：即从『物品栏里有：A、B、C』到
 * > 『物品栏内有：拔刀剑相关：A、B；枪械相关：C、D（每个枪支消耗的弹药种类，
 * > 以及拥有的弹药及多少（检测弹药箱）也要写在里面，记得创造弹药盒和全类型创造弹药盒
 * > 这些特殊案例）』」
 *
 * <h2>★ 分类判据（**不猜**：用已有的权威分类）</h2>
 * <ol>
 *   <li><b>枪</b>：TLM 的公开抽象 {@code GunCommonUtil.isGun}（TaCZ + 卓越前线都覆盖）；</li>
 *   <li><b>弹药 / 弹药盒</b>：各枪包自己的 API（TaCZ 走 {@code IAmmo}/{@code IAmmoBox} 反射，
 *       卓越前线按注册名 —— 见 {@link AmmoInfo}）；★ 委托方点名要的"弹药箱检测"在这一类里；</li>
 *   <li><b>其余</b>：<b>命中她的那一条最专精的载体规则</b>（{@code carriers.json} 的
 *       {@code carrier} 字段本来就在回答"这是哪一类"），与解析器的「专精度抑制」同一口径
 *       ⇒ <b>不会出现"清单里的分类"与"决策层眼里的分类"不一致</b>（本项目最怕的那类漂移）。</li>
 * </ol>
 *
 * <h2>★ 输出长什么样（一行一类，id 照抄可用）</h2>
 * <pre>
 * 枪械：tacz:ak47(AK47 突击步枪)x1@MAINHAND ｜ 弹药 7.62x39mm：备弹 128 发（弹匣内 30；含弹药盒 60）
 * 弹药：弹药 7.62x39mm x64@INVENTORY
 * 弹药盒：全类型创造弹药盒（任何枪 ⇒ 无限弹药）@INVENTORY
 * 拔刀剑：slashblade:slashblade_wood(无名刀)x1@MAINHAND
 * 诡厄巫法·魔杖：goety:nameless_staff(无名魔杖)x1@INVENTORY
 * </pre>
 * ★ 物品那一段的格式**没变**（{@code id(名字)x数量@槽位}）—— 只多了类别前缀，
 * 因为模型要照抄的是 id（既有提示词与工具描述都依赖这个格式）。
 */
public final class MaidItems {

    private MaidItems() {
    }

    /**
     * ★★ <b>按用途分类的完整清单</b>（给女仆看的；`flp_items` 那个惰上下文）。
     *
     * <p>★ 性能：每个物品都要问载体规则 + 枪走一遍反射 ⇒ 调用方是"上下文/场景"级
     * （不是每 tick）；这里再按女仆缓存 0.5 秒，避免同一次对话里被连续问两遍
     * （TLM 可能既算 prompt 又算工具）。
     */
    public static String describe(EntityMaid maid) {
        if (maid == null) {
            return "Empty";
        }
        return CACHE.get(maid.getUUID(), maid.level().getGameTime(), CACHE_TTL,
                () -> describeUncached(maid));
    }

    /** 分类清单的缓存（0.5 秒）：弹药数这类量在"看清单"的尺度上不需要每 tick 刷新。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            java.util.UUID, String> CACHE =
            new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    private static final long CACHE_TTL = 10;

    private static String describeUncached(EntityMaid maid) {
        // ★★ 一份"翻译后的物品"+ 一份"原始 stack"，**逐格对齐**（由 MaidSnapshot.carried 保证）
        MaidSnapshot.Carried carried = MaidSnapshot.carried(maid);
        List<PossessedItem> items = carried.items();
        if (items.isEmpty()) {
            return "Empty";
        }
        // ★ 类别顺序写在一处（ItemGroups），这里只按它排
        Map<String, List<String>> groups = new TreeMap<>(
                java.util.Comparator.comparingInt(ItemGroups::orderOf).thenComparing(k -> k));
        for (int i = 0; i < items.size(); i++) {
            PossessedItem it = items.get(i);
            ItemStack raw = i < carried.stacks().size() ? carried.stacks().get(i) : null;
            String key = classify(it, raw);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(line(maid, it, raw));
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            parts.add(ItemGroups.nameOf(e.getKey()) + "：" + String.join("，", e.getValue()));
        }
        return String.join("\n", parts);
    }

    /**
     * 一个 {@link PossessedItem} → 类别键。
     */
    private static String classify(PossessedItem it, ItemStack raw) {
        if (raw != null) {
            try {
                if (AmmoInfo.isAmmoBox(raw)) {
                    return ItemGroups.KEY_AMMO_BOX;
                }
                if (AmmoInfo.isAmmoLike(raw)) {
                    return ItemGroups.KEY_AMMO;
                }
            } catch (RuntimeException | LinkageError e) {
                // 单件判定失败 ⇒ 退回载体规则
            }
        }
        if (it.param("gunId") != null) {
            return ItemGroups.KEY_GUN;              // ★ 枪的身份（TLM 抽象算出来的）
        }
        return ItemGroups.fromCarrier(mostSpecificCarrier(it));
    }

    /**
     * 命中这件物品的**最专精**载体规则的 {@code carrier} 字段。
     *
     * <p>★ 与解析器的专精度抑制同一条纪律：专化载体优先于泛化载体
     * （一把拔刀剑同时命中 {@code slashblade:blade} 与 {@code maid:weapon} ⇒ 归"拔刀剑"）。
     */
    private static String mostSpecificCarrier(PossessedItem it) {
        var resolver = CatalogHolder.resolver();
        if (resolver == null) {
            return null;
        }
        String best = null;
        int bestSpec = Integer.MIN_VALUE;
        for (CarrierRule rule : resolver.rules()) {
            if (rule.isAlways() || rule.isUnresolved()) {
                continue;                            // 「无物品规则」与「未勘测的缺口」不参与分类
            }
            try {
                if (rule.matches(it) && rule.specificity() > bestSpec) {
                    bestSpec = rule.specificity();
                    best = rule.carrier();
                }
            } catch (RuntimeException ignored) {
                // 单条规则出错不影响其它规则
            }
        }
        return best;
    }

    /**
     * 一件物品那一行：{@code id(名字)x数量@槽位}，枪再多一句弹药。
     *
     * <p>★ 槽位照旧写出来（{@code only_item} 与"换手"都靠它判断"要不要先拿到手上"）。
     */
    private static String line(EntityMaid maid, PossessedItem it, ItemStack raw) {
        String name = it.displayName() == null || it.displayName().isBlank()
                ? "" : "(" + it.displayName() + ")";
        StringBuilder sb = new StringBuilder();
        sb.append(it.itemId()).append(name).append('x').append(Math.max(1, it.count()))
                .append('@').append(it.slot().name());
        // ★★ 弹药那一句只对"枪"加（委托方点名要求）
        if (it.param("gunId") != null && raw != null) {
            try {
                String ammo = AmmoInfo.describe(maid, raw);
                if (ammo != null && !ammo.isBlank()) {
                    sb.append(" ｜ ").append(ammo);
                }
            } catch (RuntimeException | LinkageError e) {
                sb.append(" ｜ 弹药：未知（读取失败）");
            }
        }
        return sb.toString();
    }
}
