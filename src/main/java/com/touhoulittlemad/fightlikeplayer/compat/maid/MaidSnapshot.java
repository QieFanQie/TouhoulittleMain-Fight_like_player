package com.touhoulittlemad.fightlikeplayer.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.carrier.ContextFacts;
import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.carrier.SlotKind;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>游戏侧适配器</b> —— 把 {@link EntityMaid} 的当前状态翻译成纯逻辑层需要的两个记录。
 *
 * <h2>为什么需要它</h2>
 * 决策层（`decision` + `carrier`）刻意做成<b>零 Minecraft 依赖</b>，因此必须有人负责
 * "把世界翻译成字符串与数字"。这个翻译层<b>只做搬运，不做判断</b>：
 * 所有判断都在纯逻辑层（所以那些判断才能被离线断言）。
 *
 * <pre>
 *   EntityMaid ──MaidSnapshot──▶ List&lt;PossessedItem&gt; + ContextFacts
 *                                          │
 *                                 CarrierResolver（纯逻辑）
 *                                          │
 *                                  DecisionCycle（纯逻辑）
 * </pre>
 *
 * <h2>★ 一处实测修正：MAID_BACKPACK 已被 getAvailableInv 覆盖</h2>
 * {@code EntityMaid#getAvailableInv(true)} 返回的 {@code MaidInvWrapper} 内部按
 * {@code getMaidBackpackType().getAvailableMaxContainerIndex()} 取范围
 * （{@code EntityMaid.java:2299+}）⇒ **女仆背包已经包含在这个 wrapper 里**，
 * 不需要单独枚举。⇒ [09 §4.1] 的位置 5 由此落实。
 *
 * <h2>★ 位置清单（docs/09 §4.1）</h2>
 * 主手 · 副手 · 盔甲×4 · 女仆可用背包（含 TLM 女仆背包）· <b>Curios 饰品槽</b>。
 * <p>★ 2026-10-01：饰品槽那一项（原本登记为 W5 缺口）<b>已落地</b> ——
 * 见 {@code compat/curios/CuriosSlots}。它曾经是有代价的：Goety 的聚晶包是饰品，
 * 不枚举饰品就会"她有聚晶却一颗都用不了"。
 *
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.1</a>
 */
public final class MaidSnapshot {

    /** 枚举的装备槽。 */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private MaidSnapshot() {
    }

    // ───────────────────────── ★★ 每 tick 热点的小缓存（第二十四轮，性能）─────────────────────────

    /** "永久"TTL：按物品类缓存的东西在运行期不会变（用 10 分钟只是让表周期性自我清理）。 */
    private static final long PERMANENT_TTL = 20L * 60 * 10;

    /** 物品类 → 类型名集合（{@link #typeNames}）。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            net.minecraft.world.item.Item, Set<String>> TYPE_NAMES =
            new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    /** 物品类 → 物品标签（{@link #itemTags}）。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            net.minecraft.world.item.Item, Set<String>> ITEM_TAGS =
            new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    /** 物品类 → 属性能力（{@code attr:*}，{@link #capabilities}）。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            net.minecraft.world.item.Item, Set<String>> ATTR_CAPS =
            new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    /**
     * 女仆 → 附近敌人数（{@link #countNearbyEnemies}）。
     *
     * <p>★ TTL 10 tick（0.5 秒）：这个量只喂"态势偏置"（谨慎/进攻）与指挥 LLM 的场景，
     * 半秒的滞后人眼看不出来，而它每次都要做一次半径实体查询 + 逐个实体判"能不能打"。
     */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            java.util.UUID, Integer> ENEMIES = new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    /** 女仆 → 仆从数（Goety 半径扫描 / 铁魔法索引）。 */
    private static final com.touhoulittlemad.fightlikeplayer.decision.TickMemo<
            java.util.UUID, Integer> SERVANTS = new com.touhoulittlemad.fightlikeplayer.decision.TickMemo<>();

    /** 事实快照的 TTL（tick）：0.5 秒。 */
    private static final long FACT_TTL = 10;

    // ───────────────────────── 持有物 ─────────────────────────

    /**
     * 枚举女仆持有的全部物品（★ 不限主副手）。
     *
     * <p>覆盖：主手 · 副手 · 盔甲×4 · 女仆可用背包（含 TLM 女仆背包）· <b>Curios 饰品槽</b>。
     *
     * <p>★★ 2026-10-01（W5 落地）：饰品槽<b>不再是缺口</b>。为什么它不是"锦上添花"：
     * Goety 的<b>聚晶包本身就是饰品</b>，铁魔法的法书/戒指也在饰品里
     * ⇒ 不枚举饰品的话，"她有 20 颗聚晶却一颗都用不了"。
     * 载体规则（{@code carriers.json}）里 19 条有 13 条已经声明了 {@code CURIOS} 槽位
     * —— 数据层早就等这一天了（{@link SlotKind#CURIOS}）。
     */
    public static List<PossessedItem> possessed(EntityMaid maid) {
        return carried(maid).items();
    }

    /**
     * ★★ <b>「翻译后的物品」与「原始 stack」两份清单，一次枚举同时产出</b>（第二十五轮）。
     *
     * <p>为什么要绑在一起：有些事只有真的 {@code ItemStack} 才能做 —— 数弹药要把 stack 交给
     * TaCZ 的 {@code IAmmo#isAmmoOfGun(gunStack, ammoStack)}，而 {@code PossessedItem}
     * 是**纯逻辑快照**（不带 MC 类型）。两份清单必须**逐格对齐**，
     * ⇒ 与其靠"两边分别写成一样的顺序"这种口头约定（迟早漂移），不如**一次枚举同时产出**：
     * {@code items.get(i)} 与 {@code stacks.get(i)} 恒为同一件东西（构造上保证）。
     */
    public record Carried(List<PossessedItem> items, List<ItemStack> stacks) {
    }

    /** 枚举她身上全部物品（见 {@link #Carried} 的说明）。 */
    public static Carried carried(EntityMaid maid) {
        List<PossessedItem> items = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();

        add(items, stacks, maid, SlotKind.MAINHAND, maid.getMainHandItem(), 0);
        add(items, stacks, maid, SlotKind.OFFHAND, maid.getOffhandItem(), 0);

        for (int i = 0; i < ARMOR.length; i++) {
            add(items, stacks, maid, armorSlot(ARMOR[i]), maid.getItemBySlot(ARMOR[i]), i);
        }

        // ★ getAvailableInv(true) 已含 TLM 女仆背包（见类注释）—— 但它只暴露"背包等级允许的"前 N 格
        //   （没背包 = 6 格 / 小 12 / 中 24 / 大 36）。⇒ 走统一口径：可用范围 ∪ 全部 36 格，
        //   否则"换过背包之后高位格子里的东西"会变成谁都看不见的幽灵物品（委托方实测的 bug）。
        for (ItemStack s : com.touhoulittlemad.fightlikeplayer.compat.maid.MaidInventory
                .usableStacks(maid)) {
            add(items, stacks, maid, SlotKind.INVENTORY, s, indexOf(maid, s));
        }

        // ★★ Curios 饰品槽（W5）：Curios 未装 ⇒ 空表（见 CuriosSlots 的隔离性说明）
        List<ItemStack> curios = com.touhoulittlemad.fightlikeplayer.compat.curios.CuriosSlots
                .stacksOf(maid);
        for (int i = 0; i < curios.size(); i++) {
            add(items, stacks, maid, SlotKind.CURIOS, curios.get(i), i);
        }
        return new Carried(List.copyOf(items), List.copyOf(stacks));
    }

    /**
     * ★★ <b>她身上的原始 {@code ItemStack} 列表</b>（第二十五轮）—— 与 {@link #possessed}
     * <b>逐格一致</b>（主手/副手/盔甲/可用背包/饰品），但**不做任何翻译**。
     *
     * <p>为什么需要：有些事只有拿得到真的 {@code ItemStack} 才能做 ——
     * 数弹药必须把 stack 交给 TaCZ 的 {@code IAmmo#isAmmoOfGun(gun, ammo)}（它吃的是 stack），
     * 而 {@code PossessedItem} 是纯逻辑快照（不带 MC 类型）。
     * ★ 两处口径必须一致，所以这个方法和 {@link #possessed} 的枚举顺序**写在一起、一起改**。
     */
    public static List<ItemStack> carriedStacks(EntityMaid maid) {
        return carried(maid).stacks();
    }

    /**
     * ★★ <b>极便宜的"她的物品清单变了吗"指纹</b>（第二十四轮，性能）。
     *
     * <h2>为什么需要它</h2>
     * {@code SlashBladeTicker#feedItemSnapshot} 每 tick 都要把她的物品清单喂给
     * {@code MaidMemory}（"上次对话时的物品栏"那个惰上下文），而
     * {@link #possessed} **每件物品**都要做一堆翻译（枪的身份反射、类型名继承链、
     * 属性/附魔、显示名…）⇒ 光是"看看有没有变"就每秒 20 遍全套翻译 —— 这是委托方报的
     * 「一卡一卡的」里最稳定的一块开销。
     *
     * <p>⇒ 先算这个指纹（只读 {@code Item} 的 identity / 数量 / 耐久 / <b>NBT 的 hashCode</b>），
     * **没变就直接返回**；只有变了才去做那套全套翻译。
     *
     * <p>★ 为什么用 NBT 的 {@code hashCode} 而不是 gunId 之类的语义 id：
     * 这里问的是"**变没变**"，不是"是什么" —— 语义 id 需要反射（贵），而 hashCode 是一遍树遍历（便宜）。
     * ★ 覆盖范围必须与 {@link #possessed} **逐格一致**（主手/副手/盔甲/可用背包/饰品），
     * 否则会出现"物品变了但指纹没变"的漏报。
     */
    public static long itemFingerprint(EntityMaid maid) {
        long h = 1125899906842597L;                 // 任意非零种子
        for (ItemStack s : carriedStacks(maid)) {   // ★ 与 possessed 逐格一致（见 carriedStacks）
            h = mix(h, s);
        }
        return h;
    }

    private static long mix(long h, ItemStack s) {
        if (s == null || s.isEmpty()) {
            return h * 31 + 1;
        }
        var tag = s.getTag();
        long t = tag == null ? 0 : tag.hashCode();
        return ((((h * 31 + System.identityHashCode(s.getItem())) * 31 + s.getCount()) * 31
                + s.getDamageValue()) * 31) + t;
    }

    private static SlotKind armorSlot(EquipmentSlot slot) {        return switch (slot) {
            case HEAD -> SlotKind.ARMOR_HEAD;
            case CHEST -> SlotKind.ARMOR_CHEST;
            case LEGS -> SlotKind.ARMOR_LEGS;
            case FEET -> SlotKind.ARMOR_FEET;
            default -> SlotKind.INVENTORY;
        };
    }

    /**
     * 物品在女仆背包里的槽位号（**诊断/参数用，找不到就给 0**）。
     * <p>★ 与 {@code HandEquip} 的"从哪一格换上来"无关（那个自己再查一次全量背包）——
     * 这里只是为了 {@link PossessedItem#index()} 有个可读的值。
     */
    private static int indexOf(EntityMaid maid, ItemStack stack) {
        try {
            var inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (inv.getStackInSlot(i) == stack) {
                    return i;
                }
            }
        } catch (Throwable ignore) {
            // 容器异常 ⇒ 0（只是个展示用的下标）
        }
        return 0;
    }

    /**
     * 把一格物品翻译成 {@link PossessedItem}（同时把原始 stack 记进 {@code stacks}）；
     * 空格子跳过 —— ★ 两份清单必须**同时跳过**，这是"逐格对齐"的前提。
     */
    private static void add(List<PossessedItem> out, List<ItemStack> stacks, EntityMaid maid,
                            SlotKind kind, ItemStack stack, int index) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        out.add(possessedOf(maid, kind, stack, index));
        stacks.add(stack);
    }

    /**
     * ★ <b>把任意一个 {@code ItemStack} 翻译成 {@link PossessedItem}</b>（不枚举，只翻译）。
     *
     * <p>用途：执行器的<b>换手前置</b>要判断"手上这件东西满不满足某条载体规则"。
     * 载体规则（{@link com.touhoulittlemad.fightlikeplayer.carrier.CarrierRule#matches}）
     * 吃的是 {@code PossessedItem}，因此必须能把一个"候选物品"翻译成同一形态
     * ⇒ <b>换手判定与解析期判定用的是同一条规则、同一套能力/标签口径</b>，不会漂移。
     *
     * @param slot  ★ 只有<b>槽位</b>是调用方给的（因为我们是在"假设它已经在主手"）
     */
    public static PossessedItem possessedOf(EntityMaid maid, SlotKind slot, ItemStack stack, int index) {
        return new PossessedItem(
                slot,
                itemId(stack),
                itemTags(stack),
                typeNames(stack),
                capabilities(stack),
                params(maid, stack),
                Math.max(1, stack.getCount()),
                index,
                // ★ 磨损：maxDamage == 0 表示"没有耐久概念"（食物/方块/法术书）
                PossessedItem.Durability.of(stack.getDamageValue(), stack.getMaxDamage()),
                displayName(stack),
                aliases(stack));
    }

    /**
     * ★★ <b>这件东西的【身份 id】</b> —— 枪用**枪自己的 id**，其余用注册名。
     *
     * <h2>为什么不能直接用注册名（委托方 2026-10-05 实测）</h2>
     * > 「枪械获取列表有 bug，我让她拿火箭筒，她拿了一个不存在的东西
     * > `tacz:modern_kinetic_gun`」
     *
     * 根因：**TaCZ 的所有现代枪共用同一个物品**（注册名就是 `tacz:modern_kinetic_gun`），
     * 真正的身份在 NBT 里的 GunId（`IGun#getGunId`）。于是：
     * <ul>
     *   <li>物品清单按 id 去重 ⇒ **五把枪合成一行**，模型看不到任何一把的身份；</li>
     *   <li>`only_item(机枪 / ak47 / mg)` 永远匹配不上 ⇒ 静默回退 ⇒ 她照常用手上那把；</li>
     *   <li>换手谓词比的是这个共用名 ⇒ 换谁都是"第一把枪"。</li>
     * </ul>
     * ⇒ 身份一律走这里：枪 = `tacz:rpg7` 这种枪 id，其余 = 注册名。
     */
    public static String itemId(ItemStack stack) {
        String gun = gunId(stack);
        if (gun != null) {
            return gun;
        }
        // ★★ 第二十六轮：**拔刀剑与枪同型** —— Resharped 的**所有具名刀共用一个注册名**
        //   `slashblade:slashblade`（`SlashBladeDefinition` 的 `item` 默认值，`javap -c` 取证），
        //   身份在刀状态里（`getTranslationKey()` = "item.slashblade.yamato"）。
        //   ⇒ 同样按"身份 = 它自己的 id"处理；**注册名降级为别名**（见 aliases）
        //     ⇒ `only_item slashblade:slashblade` 这种老写法照样命中。
        //   ★ 读不到（没装模组/不是刀）⇒ 退回注册名，绝不猜。
        String blade = bladeId(stack);
        return blade != null ? blade : registryId(stack);
    }

    /**
     * 拔刀剑的**身份 id**（{@code slashblade:yamato}）；不是拔刀剑/读不到 ⇒ {@code null}。
     *
     * <p>★ 实际读取在 {@code SlashBladeExecutors#bladeId}（那里有完整取证说明），
     * 这里只是"物品身份"这条链路上的一个薄封装 —— 与 {@link #gunId} 完全对称。
     */
    public static String bladeId(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
            return null;
        }
        try {
            return com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors
                    .bladeId(stack);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** ★ 原始注册名（诊断/对账用：枪的注册名永远是那个共用的 `tacz:modern_kinetic_gun`）。 */
    public static String registryId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "minecraft:air" : key.toString();
    }

    // ───────────────────────── ★★ 枪：身份 / 枪型 / 弹药（第十七轮续）─────────────────────────

    /** TaCZ 是否在场（**只有"读枪型"这一步需要它**；身份走 TLM 的抽象，两个枪包都覆盖）。 */
    private static boolean taczLoaded() {
        return net.minecraftforge.fml.ModList.get().isLoaded("tacz");
    }

    /**
     * 枪自己的 id（例如 {@code tacz:rpg7}）；不是 TaCZ 的枪 ⇒ {@code null}。
     *
     * <p>取值链（与 TLM 自己的参考实现同一条路，`javap TacInnerCompat` 逐句核对过）：
     * <pre>
     *   IGun.getIGunOrNull(stack).getGunId(stack)          // com.tacz.guns.api.item.IGun
     * </pre>
     * ★ 全程 try/catch：TaCZ 的 API 变了也不能把决策层带崩（拿不到就当"没有身份"）。
     */
    public static String gunId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            // ★ 零依赖：TLM 的公开抽象。`javap -c GunCommonUtil#getGunId` 证明它
            //   先问 `SWarfareCompat.isGun/getGunId`、再问 `TacCompat.isGun/getGunId`
            //   ⇒ **卓越前线与 TaCZ 两个枪包都覆盖**（这正是我们要的"与 TLM 同口径"）。
            if (!com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                    .isInstalled()
                    || !com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                            .isGun(stack)) {
                return null;
            }
            ResourceLocation id = com.github.tartaricacid.touhoulittlemaid.compat.gun.common
                    .GunCommonUtil.getGunId(stack);
            return id == null ? null : id.toString();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 枪型（{@code pistol/rifle/smg/shotgun/sniper/mg/rpg}，小写）；取不到 ⇒ {@code null}。
     *
     * <p>取值链：{@code TimelessAPI.getCommonGunIndex(gunId).map(CommonGunIndex::getType)}
     * —— 与 {@code TacInnerCompat} 里 TLM 自己的读法逐句一致。
     */
    public static String gunType(ItemStack stack) {
        String id = gunId(stack);
        if (id == null || !taczLoaded()) {
            return null;
        }
        try {
            // ★ 零依赖反射：TaCZ 的 `TimelessAPI.getCommonGunIndex(ResourceLocation)` → `CommonGunIndex#getType()`
            //   （与 TLM 自己的 `TacInnerCompat` 读法同一条路；只是我们不做编译期链接）。
            Class<?> api = Class.forName("com.tacz.guns.api.TimelessAPI");
            Object opt = api.getMethod("getCommonGunIndex", ResourceLocation.class)
                    .invoke(null, ResourceLocation.parse(id));
            if (!(opt instanceof java.util.Optional<?> o) || o.isEmpty()) {
                return null;
            }
            Object idx = o.get();
            Object type = idx.getClass().getMethod("getType").invoke(idx);
            return type == null ? null
                    : type.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // ★ 拿不到就当"没有枪型"（评分退回族默认向量）——绝不带崩决策层
            return null;
        }
    }

    /** 枪型的中文名（lang {@code tacz.type.<type>.name}，实测「机枪」「冲锋枪」…）；取不到 ⇒ null。 */
    public static String gunTypeZh(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            String key = "tacz.type." + type + ".name";
            String s = net.minecraft.network.chat.Component.translatable(key).getString();
            return s == null || s.isBlank() || s.equals(key) ? null : s;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ★ 弹匣余量 / 射击模式：TaCZ 侧还有 `IGun#getCurrentAmmoCount` 与 `#getFireMode`，
    //   但要拿它们得先有 `IGun` 实例（`IGun.getIGunOrNull`）—— 本轮**未做**：
    //   委托方这一轮的问题是"分清是哪把枪、按枪型只用某一种"，那两样与它无关。
    //   登记为 W23 的剩余部分（要做的话同样走反射，或按 build.gradle 的规矩把许可账记清后引依赖）。

    /** 人读名（TaCZ 的枪会给「M249 机枪」这种名字；其余物品是它的显示名）。 */
    public static String displayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return stack.getHoverName().getString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * ★★ <b>这件东西"还能怎么被叫"</b>（匹配别名）—— 目前只有枪会产出：
     * <ul>
     *   <li>枪型（{@code mg} / {@code rpg} / {@code smg} …）——
     *       委托方那句「我让她只用**机枪**」要能落下来；</li>
     *   <li>枪型的中文名（「机枪」「冲锋枪」…，取自 TaCZ 的 lang）；</li>
     *   <li>人读名（「M249 机枪」）；</li>
     *   <li>枪 id 的简写（{@code m249}，与 {@code ItemQuery} 的原有分级重复但无害）。</li>
     * </ul>
     */
    public static Set<String> aliases(ItemStack stack) {
        String id = gunId(stack);
        if (id == null) {
            // ★★ 第二十六轮：**拔刀剑同样要能被"别的说法"叫到**（与枪同一条纪律）。
            //   身份 id 是 `slashblade:yamato`，但模型/主人可能说：
            //   注册名 `slashblade:slashblade`（老写法）、刀名 `yamato`、中文名「魔剑「阎魔刀」」。
            String blade = bladeId(stack);
            if (blade == null) {
                return Set.of();
            }
            Set<String> b = new HashSet<>();
            b.add(blade);
            b.add(registryId(stack));                    // ★ 注册名是别名（老写法照样命中）
            String path = com.touhoulittlemad.fightlikeplayer.decision.ItemIdentity.pathOf(blade);
            if (path != null && path.length() >= 2) {
                b.add(path);
            }
            String bladeName = displayName(stack);
            if (!bladeName.isBlank()) {
                b.add(bladeName);
                for (String word : bladeName.split("[\\s·\\-/（）()「」]+")) {
                    if (word.length() >= 2) {
                        b.add(word);
                    }
                }
            }
            return b;
        }
        Set<String> out = new HashSet<>();
        int colon = id.indexOf(':');
        if (colon >= 0 && colon + 1 < id.length()) {
            out.add(id.substring(colon + 1));
        }
        String type = gunType(stack);
        if (type != null) {
            out.add(type);
            String zh = gunTypeZh(type);
            if (zh != null) {
                out.add(zh);
            }
        }
        String name = displayName(stack);
        if (!name.isBlank()) {
            out.add(name);
            // ★ 再把名字**拆词**加一遍（「M249 机枪」⇒ 也认「M249」/「机枪」）——
            //   因为别名只做精确匹配（见 ItemQuery 里那条"`mg` 不许命中 `smg`"的说明），
            //   拆词正好补上"只说型号/只说类型"这两种说法。
            for (String word : name.split("[\\s·\\-/（）()]+")) {
                if (word.length() >= 2) {
                    out.add(word);
                }
            }
        }
        return out;
    }

    /**
     * 物品标签，形如 {@code minecraft:swords}。
     *
     * <p>★ 第二十四轮（性能）：**按物品类缓存** —— 1.20.1 的物品标签挂在 {@code Item} 上
     * （{@code stack.getTags()} 读的就是它），与 NBT/数量无关 ⇒ 缓存安全。
     */
    public static Set<String> itemTags(ItemStack stack) {
        return ITEM_TAGS.get(stack.getItem(), 0L, PERMANENT_TTL, () -> itemTagsUncached(stack));
    }

    private static Set<String> itemTagsUncached(ItemStack stack) {
        Set<String> tags = new HashSet<>();
        stack.getTags().forEach(t -> tags.add(t.location().toString()));
        return tags;
    }

    /**
     * 类型名集合（<b>全名 + 简单名都放进去</b>）。
     *
     * <p>★ 为什么收集简单名：{@code carriers.json} 里的 {@code javaInstanceOf} 规则
     * 用的是 {@code matchMode: simpleName}（只比类名），以抵御附属换包名。
     * 由适配器预先展开，匹配逻辑就只是<b>字符串集合的包含判断</b>（可离线断言）。
     */
    public static Set<String> typeNames(ItemStack stack) {
        // ★★ 第二十四轮（性能）：**按物品类缓存**。这个集合只取决于 `Item` 的类继承链
        //   ⇒ 与 NBT/数量/耐久无关 ⇒ 缓存永远安全（物品类在运行期不会变）。
        //   不缓存时的代价：`possessed()` 每 tick 对**每一件**物品都走一遍继承链 + 分配集合。
        return TYPE_NAMES.get(stack.getItem(), 0L, PERMANENT_TTL,
                () -> typeNamesUncached(stack));
    }

    private static Set<String> typeNamesUncached(ItemStack stack) {
        Set<String> names = new HashSet<>();
        Class<?> c = stack.getItem().getClass();
        while (c != null && c != Object.class) {
            names.add(c.getName());
            names.add(c.getSimpleName());
            for (Class<?> itf : c.getInterfaces()) {
                names.add(itf.getName());
                names.add(itf.getSimpleName());
            }
            c = c.getSuperclass();
        }
        return names;
    }

    /**
     * 具名能力集合。
     *
     * <p>当前提供两类：
     * <ul>
     *   <li>{@code attr:<属性id>}，例如 {@code attr:minecraft:generic.attack_damage}
     *       —— 这正是 {@code carriers.json} 里 {@code maid:weapon} 的判据
     *       （与 TLM 的 {@code isWeapon} 一致：只看 {@code ATTACK_DAMAGE} 是否存在）；</li>
     *   <li>★ {@code enchant:<附魔id>}，例如 {@code enchant:minecraft:thorns}
     *       —— 供清单里的 {@code hasEnchantment} 前置条件使用。它不是为了好看：
     *       <b>拔刀剑的「防御」需要「荆棘」附魔才会生效（否则静默失效）</b>，
     *       这是 S1 要验的两件事之一（docs/08 §S1）。此前没人喂这个能力
     *       ⇒ {@code slashblade:guard} 在游戏里【永远拿不到】。
     * </li>
     * </ul>
     */
    public static Set<String> capabilities(ItemStack stack) {
        // ★★ 第二十四轮（性能）：**属性那一半按物品类缓存**
        //   （`getAttributeModifiers` 会分配 map，而它只取决于物品类 —— NBT 不参与）
        //   ★ 附魔那一半**不缓存**：附魔是写在 NBT 上的，同一件物品的不同堆叠会不一样。
        Set<String> caps = new HashSet<>(ATTR_CAPS.get(stack.getItem(), 0L, PERMANENT_TTL,
                () -> attrCapsUncached(stack)));
        // ★ 附魔能力：键的格式必须与 CarrierResolver#checkPreconditions 里
        //   "enchant:" + （清单的 enchantment 字段）完全一致。
        var enchantments = stack.getAllEnchantments();
        for (var e : enchantments.entrySet()) {
            ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(e.getKey());
            if (id != null && e.getValue() != null && e.getValue() > 0) {
                caps.add("enchant:" + id);
            }
        }
        // ★★ 铁魔法：carriers.json 的 `irons:spellbook_or_scroll` 判据是
        //   `capability: "irons:isSpellContainer"`（"适配器算好的具名谓词"）。
        //   ⚠️ 2026-10-01 实测暴露：这个键【从来没被喂过】⇒ 铁魔法一个候选都进不了
        //   ⇒ 观感是「铁魔法法术全部无法释放」。⇒ 由 IronsExecutors（惰性守卫的独立类）算出来这里。
        //   ★ 判据是 NBT 谓词而不是类型 ⇒ 法术书与卷轴都被覆盖。
        if (net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")
                && com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsExecutors
                        .isSpellContainer(stack)) {
            caps.add("irons:isSpellContainer");
        }
        return caps;
    }

    /** 属性能力的那一半（{@code attr:*}）—— 只取决于物品类，见 {@link #capabilities}。 */
    private static Set<String> attrCapsUncached(ItemStack stack) {
        Set<String> caps = new HashSet<>();
        var mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (Map.Entry<Attribute, AttributeModifier> e : mods.entries()) {
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(e.getKey());
            if (id != null) {
                caps.add("attr:" + id);
            }
        }
        return caps;
    }

    /**
     * ★ <b>物品参数（A 档参数化的落点）</b> —— 目前<b>返回空</b>。
     *
     * <p>为什么先空着：读这些参数需要<b>各附属的类</b>（SA 要 {@code ISlashBladeState}、
     * 枪型要 TaCZ 的 {@code IGun}、聚晶类别要调律师的 {@code FocusClassifier}），
     * 而当前构建只 {@code compileOnly} 了 TLM。
     *
     * <p>⚠️ <b>后果（必须知道）</b>：没有参数 ⇒ {@code vectorOverrides} 不命中 ⇒
     * 族一律用**默认向量**（如 {@code tacz:shoot} 用族默认，而不是按枪型区分）。
     * <b>行为仍可用，只是不够精确。</b>登记为 W23。
     */
    public static Map<String, String> params(EntityMaid maid, ItemStack stack) {
        Map<String, String> p = new HashMap<>();
        // ★★ 第十七轮续：**枪的参数真的喂进来了**（W23 的那一半关闭）。
        //   为什么这件事必须做：`vectors.json` 早就按枪型写了 7 组 override
        //   （pistol/rifle/smg/shotgun/sniper/mg/rpg，火箭筒偏 AoE、步枪偏单体），
        //   `guns.json` 也按 fireMode 写了 durationOverrides —— 而此前 `params` **恒为空**
        //   ⇒ 决策层眼里"所有枪完全一样"（委托方：「我让她只用机枪，却还是用冲锋枪扫射」的
        //     另一半就是这个：两把枪在评分上无法区分）。
        String gun = gunId(stack);
        if (gun != null) {
            p.put("gunId", gun);
            String type = gunType(stack);
            if (type != null) {
                p.put("gunType", type);
            }
            // ★ fireMode / ammo 见上面的说明（本轮未做）
        }
        // TODO(W23 剩余)：SA 种 / 聚晶类别 / 法术意图 —— 同上，逐模组补
        return p;
    }

    // ───────────────────────── 情境事实 ─────────────────────────

    /**
     * 构造情境事实。
     *
     * @param maid      女仆
     * @param modsLoaded 已加载模组 id 集合
     */
    public static ContextFacts facts(EntityMaid maid, Set<String> modsLoaded) {
        return facts(maid, modsLoaded, Set.of(), Map.of());
    }

    public static ContextFacts facts(EntityMaid maid, Set<String> modsLoaded, Set<String> coolingDown) {
        return facts(maid, modsLoaded, coolingDown, Map.of());
    }

    /**
     * 构造情境事实（带"正在冷却的动作"与"自定义事实"）。
     *
     * @param coolingDown ★ 正在冷却的动作 id —— 由 {@code PlayerLikeCombat} 按
     *                    {@code 上次使用时刻 + CarrierResolver#cooldownTicksOf} 算出。
     *                    <b>此前恒为空集</b> ⇒ 瞬时动作会被每个决策周期重复选中（"疯狂执行"）。
     *                    现在它走既有的 {@code DropReason.ON_COOLDOWN} 通路，不新增机制。
     * @param customFacts ★★ <b>具名自定义事实</b> —— 喂 {@code custom} / {@code spellConditionsMet}
     *                    两类前置条件。<b>这是 2026-09-30 最要紧的一处补漏</b>：
     *                    这两类条件在前置检查里是 <b>fail-closed</b> 的（没被显式喂进来就判不满足），
     *                    而此前这里传的是空 Map ⇒ <b>凡是声明了它们的动作在游戏里永远拿不到</b>。
     *                    实测可见的后果就是「拿剑的女仆一个候选动作都没有 ⇒ 连目标都不找」。
     *                    键的约定见 {@code CarrierResolver#customKey}（优先清单里的 {@code fact} 字段）。
     */
    public static ContextFacts facts(EntityMaid maid, Set<String> modsLoaded, Set<String> coolingDown,
                                     Map<String, Boolean> customFacts) {
        LivingEntity target = maid.getTarget();
        LivingEntity owner = maid.getOwner();

        boolean hasTarget = target != null && target.isAlive();
        double dist = hasTarget ? Math.sqrt(maid.distanceToSqr(target)) : Double.MAX_VALUE;
        boolean inMelee = hasTarget && maid.isWithinMeleeAttackRange(target);

        double selfHp = maid.getMaxHealth() <= 0 ? 1.0 : maid.getHealth() / maid.getMaxHealth();
        double ownerHp = (owner == null || owner.getMaxHealth() <= 0)
                ? 1.0 : owner.getHealth() / owner.getMaxHealth();

        int enemies = ENEMIES.get(maid.getUUID(), maid.level().getGameTime(), FACT_TTL,
                () -> countNearbyEnemies(maid, 8.0));

        // ★★ 2026-10-01：**仆从数不再恒为 0** —— W23 的这一半关闭了。
        //    写法来自取证报告（_scratch/goety-servant-api.md）：遍历附近 64 格内
        //    `IServant#getTrueOwner() == maid` 的实体。⇒ `hasServant` 前置条件现在能被满足，
        //    「处死指定仆从 / 清理临时仆从」两条动作因此**真的进得了候选集**。
        //
        // ★★ 第二十四轮（性能）：这一整块**按 TTL 缓存**，而且铁魔法那一半换成走
        //    铁魔法自己的索引（`SummonManager.getSummons`）——
        //    原来它每 tick 做一次 `level.getAllEntities()`（**整个维度**）！
        int servants = SERVANTS.get(maid.getUUID(), maid.level().getGameTime(), FACT_TTL, () -> {
            int n = 0;
            if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
                n += com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                        .servantCount(maid);
            }
            if (net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")) {
                n += com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsServantOps
                        .countSummonsIndexed(maid);
            }
            return n;
        });

        return new ContextFacts(
                hasTarget,
                inMelee,
                servants,
                maid.onGround(),
                maid.isCrouching(),
                maid.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6,
                Map.of(),                   // TODO(W23)：耀魂值/法力需读各附属的 capability
                customFacts,
                Map.of(),
                coolingDown,
                modsLoaded,
                ContextFacts.Vitals.of(selfHp, ownerHp, enemies, dist));
    }

    /**
     * 数附近敌人数。
     *
     * <p>★ 用 {@code maid.canAttack} 而不是"非友方"：TLM 有自己的阵营判定
     * （{@code isAlliedTo} + 好感度），用 TLM 的判据才不会把主人算成敌人。
     *
     * <p>★★ <b>第十五轮更正：这里必须用"不含指令"的那一条</b>
     * （{@code TaskPlayerLikeCombat#canAttackIgnoringDirectives}）。
     * 因为我们在 {@code canAttack} 里加了目标筛选指令（{@code focus_entity} 等），
     * 若这里照旧走 {@code maid.canAttack}，那么"只打僵尸"时
     * <b>"附近敌人"会悄悄变成"附近僵尸数"</b> ——
     * 它同时污染态势偏置（{@code ContextBias}）与指挥 LLM 看到的 {@code scene}。
     * ★ 这是最难查的一类错：<b>看起来一切正常，只是数字的含义变了</b>。
     * ⇒ 两个概念必须分开：**"允许打吗"（含指令）**／**"本来能打吗"（中立事实）**。
     */
    private static int countNearbyEnemies(EntityMaid maid, double radius) {
        var area = maid.getBoundingBox().inflate(radius);
        int n = 0;
        for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class, area)) {
            if (e == maid || !e.isAlive()) {
                continue;
            }
            if (maid.isAlliedTo(e)) {
                continue;
            }
            if (com.touhoulittlemad.fightlikeplayer.compat.task.TaskPlayerLikeCombat
                    .canAttackIgnoringDirectives(maid, e)) {
                n++;
            }
        }
        return n;
    }

    /** 供调试：一行描述女仆当前持有物（启动/调试日志用）。 */
    public static String describePossessed(EntityMaid maid) {
        List<PossessedItem> items = possessed(maid);
        StringBuilder sb = new StringBuilder("持有 ").append(items.size()).append(" 件：");
        for (PossessedItem i : items) {
            sb.append(' ').append(i.shortName());
        }
        return sb.toString();
    }
}
