package com.touhoulittlemad.fightlikeplayer.compat.gun;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;


import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * ★★ <b>「这支枪吃什么弹药、她还有多少」</b>（第二十五轮，委托方第 2 条）。
 *
 * <h2>委托方原话</h2>
 * > 「每个枪支消耗的弹药种类，以及拥有的弹药及多少（**检测弹药箱**）也要写在里面，
 * > 记得**创造弹药盒**和**全类型创造弹药盒**这些特殊案例」
 *
 * <h2>★★ 取证结论（反编译 + javap，见 {@code _scratch/ammo_research/FINDINGS.md}）</h2>
 * <ol>
 *   <li><b>TLM 没有任何弹药 API</b> —— {@code getAmmoCount/getAmmoType/isAmmo} 这些名字
 *       全 jar 都不存在；TLM 自己的换弹路径（{@code TacInnerCompat#performGunAttack} 的
 *       {@code NO_AMMO} 分支）**只扫散装 {@code IAmmo}，完全不认弹药盒**
 *       （1817 个反编译文件里 {@code IAmmoBox|AmmoBox|CreativeAmmo} 命中 0）。
 *       ⇒ 弹药盒那一半**只能我们自己算**（这也正是委托方点名要的那一半）。</li>
 *   <li><b>TaCZ</b>（反射，1.1.7 与 1.1.8 API 逐字相同）：
 *       <pre>
 *   枪 → 弹药种类： TimelessAPI.getCommonGunIndex(gunId) → getGunData().getAmmoId()   // ResourceLocation
 *   弹药物品：      IAmmo.getIAmmoOrNull(stack) / isAmmoOfGun(gunStack, ammoStack)     // ★ 枪在前！
 *   弹药盒：        IAmmoBox（**没有静态工厂** ⇒ 只能 Class.isInstance + 注册名 tacz:ammo_box）
 *                   isAmmoBoxOfGun(gunStack, boxStack) / getAmmoCount(stack)
 *                   isCreative(stack) / isAllTypeCreative(stack)
 *                   ★ getAmmoCount 在创造变体上返回 Integer.MAX_VALUE ⇒ **不能直接打印**
 *   弹匣内：        IGun.getIGunOrNull(stack).getCurrentAmmoCount(stack)
 *   权威算法：      GunHudOverlay.handleInventoryAmmo —— 我们照抄它（散装累加、弹药盒累加、
 *                   遇到创造变体直接"无限"）</pre></li>
 *   <li><b>卓越前线（superbwarfare）</b>：它自己就把答案做好了 ——
 *       {@code GunData.from(stack).countBackupAmmo(entity)} / {@code hasInfiniteBackupAmmo(entity)}
 *       （含背包与弹药盒）。它的枪 id **就是物品注册名**（TLM 的 {@code GunCommonUtil} 已覆盖它）。</li>
 *   <li><b>Pillager's Gun</b>：引擎里**没有弹药盒、也没有"无限弹药"这个概念**，
 *       而且 TLM 不认它的枪（{@code GunCommonUtil.isGun} 只查 tacz + superbwarfare）
 *       ⇒ 本类对它只做"尽力而为"的散装弹药计数（{@code GunItem#getAmmo} → 弹药物品 → 数数量）。</li>
 * </ol>
 *
 * <h2>★ 输出口径（写在这里，免得各处不一致）</h2>
 * <ul>
 *   <li>有限：<code>弹药 7.62x39mm：备弹 128 发（弹匣内 30；含弹药盒 60）</code>；</li>
 *   <li>创造弹药盒：<code>弹药 5.56x45mm：无限（创造弹药盒）</code>；</li>
 *   <li>全类型创造弹药盒：<code>弹药 火箭弹：无限（全类型创造弹药盒）</code>
 *       —— ★ <b>绝不打印 {@code Integer.MAX_VALUE}</b>（那是 API 的哨兵值，不是数字）；</li>
 *   <li>拿不到弹药种类（模组 API 变了）：<code>弹药：未知（未能从模组读到）</code>
 *       —— ★ 可预期的失败必须有可读出口，不许静默留空。</li>
 * </ul>
 */
public final class AmmoInfo {

    private AmmoInfo() {
    }

    private static final String TACZ = "tacz";
    private static final String SW = "superbwarfare";
    private static final String PG = "pillagers_gun";

    // ───────────────────────── 对外：两句话 ─────────────────────────

    /**
     * 给一支枪生成"弹药"那一句（不是枪 ⇒ {@code null}）。
     *
     * <p>★ 每次都会遍历她的物品数一遍散装弹药与弹药盒 —— 调用方是"上下文/场景"级
     * （不是每 tick），并且 {@code MaidItems} 会按女仆缓存一次。
     */
    public static String describe(EntityMaid maid, ItemStack gunStack) {
        if (maid == null || gunStack == null || gunStack.isEmpty()) {
            return null;
        }
        String gunId = MaidSnapshot.gunId(gunStack);          // TLM 公开抽象：TaCZ + 卓越前线都覆盖
        String ns = namespaceOf(gunId);
        try {
            if (TACZ.equals(ns)) {
                return describeTacz(maid, gunStack, gunId);
            }
            if (SW.equals(ns)) {
                return describeSuperbWarfare(maid, gunStack);
            }
        } catch (Throwable t) {
            // 反射/模组 API 出问题 ⇒ 给可读出口，绝不把上下文生成带崩
            FightLikePlayer.LOGGER.debug("[FLP] 读弹药失败（{}）：{}", gunId, t.toString());
            return "弹药：未知（读取失败）";
        }
        // 不是 TLM 认识的枪（例如 Pillager's Gun）⇒ 尽力而为
        return describeFallback(maid, gunStack);
    }

    /**
     * 这件东西是"弹药/弹药盒"吗？是 ⇒ 给一行可读描述；不是 ⇒ {@code null}。
     *
     * <p>用途：物品分类时把它们从"其它"里捞出来（委托方要求"检测弹药箱"）。
     */
    public static String ammoItemLine(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            String tacz = taczAmmoLine(stack);
            if (tacz != null) {
                return tacz;
            }
        } catch (Throwable ignored) {
            // 继续往下试别的枪包
        }
        return byRegistryNameLine(stack);
    }

    /** 是不是弹药/弹药盒（分类用；{@link #ammoItemLine} 非空即真）。 */
    public static boolean isAmmoLike(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return ammoItemLine(stack) != null;
    }

    /** 弹药盒类（与散装弹药分开归类）。 */
    public static boolean isAmmoBox(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            if (taczIsAmmoBox(stack)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 继续按注册名判
        }
        String id = registryId(stack);
        return id != null && id.contains("ammo_box");
    }

    // ───────────────────────── TaCZ ─────────────────────────

    private static String describeTacz(EntityMaid maid, ItemStack gun, String gunId)
            throws ReflectiveOperationException {
        String ammoName = taczAmmoDisplayName(taczAmmoIdOfGun(gunId));
        int loaded = taczLoadedRounds(gun);
        int loose = 0;
        int inBoxes = 0;
        boolean allTypeCreative = false;
        boolean creative = false;
        for (ItemStack stack : herStacks(maid)) {
            if (taczIsAmmoOfGun(gun, stack)) {
                loose += Math.max(1, stack.getCount());
                continue;
            }
            if (taczIsAmmoBox(stack) && taczIsAmmoBoxOfGun(gun, stack)) {
                if (taczBoxIsAllTypeCreative(stack)) {
                    allTypeCreative = true;
                } else if (taczBoxIsCreative(stack)) {
                    creative = true;
                } else {
                    inBoxes += taczBoxCount(stack);
                }
            }
        }
        return compose(ammoName, loose, inBoxes, loaded, creative, allTypeCreative);
    }

    /** 枪 → 弹药 id（{@code TimelessAPI.getCommonGunIndex(gunId).get().getGunData().getAmmoId()}）。 */
    private static ResourceLocation taczAmmoIdOfGun(String gunId) throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.tacz.guns.api.TimelessAPI");
        Object opt = api.getMethod("getCommonGunIndex", ResourceLocation.class)
                .invoke(null, ResourceLocation.parse(gunId));
        if (!(opt instanceof java.util.Optional<?> o) || o.isEmpty()) {
            return null;
        }
        Object index = o.get();
        Object gunData = index.getClass().getMethod("getGunData").invoke(index);
        Object ammoId = gunData.getClass().getMethod("getAmmoId").invoke(gunData);
        return ammoId instanceof ResourceLocation rl ? rl : null;
    }

    /** 弹药 id → 人读名（走 TaCZ 自己的 lang 键，取不到就退回 id）。 */
    private static String taczAmmoDisplayName(ResourceLocation ammoId) {
        if (ammoId == null) {
            return null;
        }
        try {
            Class<?> api = Class.forName("com.tacz.guns.api.TimelessAPI");
            Object opt = api.getMethod("getCommonAmmoIndex", ResourceLocation.class)
                    .invoke(null, ammoId);
            if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                Object pojo = o.get().getClass().getMethod("getPojo").invoke(o.get());
                Object key = pojo.getClass().getMethod("getName").invoke(pojo);
                if (key != null) {
                    String s = Component.translatable(key.toString()).getString();
                    if (s != null && !s.isBlank() && !s.equals(key.toString())) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 拿不到名字不是错误 —— 下面用 id 兜底
        }
        return ammoId.toString();
    }

    /** 弹匣里已经装了几发（{@code IGun#getCurrentAmmoCount}）。拿不到 ⇒ -1。 */
    private static int taczLoadedRounds(ItemStack gun) {
        try {
            Class<?> igun = Class.forName("com.tacz.guns.api.item.IGun");
            Object inst = igun.getMethod("getIGunOrNull", ItemStack.class).invoke(null, gun);
            if (inst == null) {
                return -1;
            }
            Object n = igun.getMethod("getCurrentAmmoCount", ItemStack.class).invoke(inst, gun);
            return n instanceof Integer i ? i : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 这堆东西是不是"这支枪能用的散装弹药"（★ 参数顺序：枪在前）。 */
    private static boolean taczIsAmmoOfGun(ItemStack gun, ItemStack ammo) {
        try {
            Class<?> iammo = Class.forName("com.tacz.guns.api.item.IAmmo");
            Object a = iammo.getMethod("getIAmmoOrNull", ItemStack.class).invoke(null, ammo);
            if (a == null) {
                return false;
            }
            Object ok = iammo.getMethod("isAmmoOfGun", ItemStack.class, ItemStack.class)
                    .invoke(a, gun, ammo);
            return ok instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean taczIsAmmoBox(ItemStack stack) throws ReflectiveOperationException {
        Class<?> box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
        return box.isInstance(stack.getItem());
    }

    private static boolean taczIsAmmoBoxOfGun(ItemStack gun, ItemStack boxStack) {
        try {
            Class<?> box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
            Object ok = box.getMethod("isAmmoBoxOfGun", ItemStack.class, ItemStack.class)
                    .invoke(boxStack.getItem(), gun, boxStack);
            return ok instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean taczBoxIsCreative(ItemStack boxStack) {
        return taczBoxBool(boxStack, "isCreative");
    }

    private static boolean taczBoxIsAllTypeCreative(ItemStack boxStack) {
        return taczBoxBool(boxStack, "isAllTypeCreative");
    }

    private static boolean taczBoxBool(ItemStack boxStack, String method) {
        try {
            Class<?> box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
            Object v = box.getMethod(method, ItemStack.class).invoke(boxStack.getItem(), boxStack);
            return v instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 弹药盒里存了几发（★ 创造变体会返回 {@code Integer.MAX_VALUE} —— 调用方在此之前已经分流）。 */
    private static int taczBoxCount(ItemStack boxStack) {
        try {
            Class<?> box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
            Object n = box.getMethod("getAmmoCount", ItemStack.class).invoke(boxStack.getItem(), boxStack);
            if (n instanceof Integer i) {
                return i == Integer.MAX_VALUE ? -1 : Math.max(0, i);   // 防御：哨兵值不当数字
            }
        } catch (Throwable ignored) {
            // 读不到 ⇒ 0
        }
        return 0;
    }

    /** TaCZ 的弹药/弹药盒 → 一行描述（不是就返回 null）。 */
    private static String taczAmmoLine(ItemStack stack) {
        try {
            Class<?> box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
            if (box.isInstance(stack.getItem())) {
                ResourceLocation ammo = taczAmmoIdOfBox(stack);
                String name = taczAmmoDisplayName(ammo);
                if (taczBoxIsAllTypeCreative(stack)) {
                    return "全类型创造弹药盒（任何枪 ⇒ 无限弹药）";
                }
                if (taczBoxIsCreative(stack)) {
                    return "创造弹药盒（" + name + " ⇒ 无限）";
                }
                return "弹药盒（" + name + "，内含 " + Math.max(0, taczBoxCount(stack)) + " 发）";
            }
            Class<?> iammo = Class.forName("com.tacz.guns.api.item.IAmmo");
            Object a = iammo.getMethod("getIAmmoOrNull", ItemStack.class).invoke(null, stack);
            if (a != null) {
                Object ammoId = iammo.getMethod("getAmmoId", ItemStack.class).invoke(a, stack);
                String name = ammoId instanceof ResourceLocation rl
                        ? taczAmmoDisplayName(rl) : ammoId.toString();
                return "弹药 " + name + " x" + Math.max(1, stack.getCount());
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static ResourceLocation taczAmmoIdOfBox(ItemStack boxStack) {
        try {
            Class<?> box = Class.forName("com.tacz.guns.api.item.IAmmoBox");
            Object id = box.getMethod("getAmmoId", ItemStack.class).invoke(boxStack.getItem(), boxStack);
            return id instanceof ResourceLocation rl ? rl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ───────────────────────── 卓越前线 ─────────────────────────

    private static String describeSuperbWarfare(EntityMaid maid, ItemStack gun) {
        try {
            Class<?> gunData = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
            Object from = gunData.getMethod("from", ItemStack.class).invoke(null, gun);
            if (from == null) {
                return null;                       // 不是卓越前线的枪
            }
            Object infinite = gunData.getMethod("hasInfiniteBackupAmmo", net.minecraft.world.entity.Entity.class)
                    .invoke(from, maid);
            String type = swAmmoTypeName(from);
            if (infinite instanceof Boolean b && b) {
                return "弹药 " + type + "：无限（创造弹药盒）";
            }
            Object n = gunData.getMethod("countBackupAmmo", net.minecraft.world.entity.Entity.class)
                    .invoke(from, maid);
            int count = n instanceof Integer i ? i : 0;
            return "弹药 " + type + "：备弹 " + count + " 发";
        } catch (Throwable t) {
            FightLikePlayer.LOGGER.debug("[FLP] 卓越前线弹药读取失败：{}", t.toString());
            return "弹药：未知（读取失败）";
        }
    }

    /** 卓越前线的弹药类型名（{@code selectedAmmoConsumer().ammo} / {@code getPlayerAmmoType()}）。 */
    private static String swAmmoTypeName(Object gunData) {
        for (String m : new String[]{"getPlayerAmmoType", "selectedAmmoConsumer", "getAmmoType"}) {
            try {
                Method method = gunData.getClass().getMethod(m);
                Object v = method.invoke(gunData);
                String s = swUnwrap(v);
                if (s != null) {
                    return s;
                }
            } catch (Throwable ignored) {
                // 试下一个方法名（版本差异）
            }
        }
        return "未知";
    }

    private static String swUnwrap(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s.isBlank() ? null : s;
        }
        if (v instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT);
        }
        // AmmoConsumer 之类的记录：里面有个 `ammo` 字符串
        for (String m : new String[]{"ammo", "getAmmo", "toString"}) {
            try {
                Method method = v.getClass().getMethod(m);
                Object inner = method.invoke(v);
                if (inner instanceof String s && !s.isBlank() && !s.contains("@")) {
                    return s;
                }
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }

    // ───────────────────────── 兜底（Pillager's Gun 等） ─────────────────────────

    /**
     * 不是 TLM 认识的枪时的尽力而为：按类名找 {@code getAmmo()}（Pillager's Gun 的
     * {@code GunItem#getAmmo()} 直接给弹药物品），再数她有多少那个物品。
     */
    private static String describeFallback(EntityMaid maid, ItemStack gun) {
        try {
            Object ammoItem = gun.getItem().getClass().getMethod("getAmmo").invoke(gun.getItem());
            if (!(ammoItem instanceof net.minecraft.world.item.Item item)) {
                return null;
            }
            int have = 0;
            for (ItemStack s : herStacks(maid)) {
                if (s.getItem() == item) {
                    have += Math.max(1, s.getCount());
                }
            }
            String name = BuiltInRegistries.ITEM.getKey(item).toString();
            return "弹药 " + name + "：备弹 " + have + " 发";
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ───────────────────────── 按注册名的兜底（弹药类物品的归类） ─────────────────────────

    /**
     * 注册名里带 {@code ammo} 的东西（卓越前线的弹药/弹药盒、Pillager's Gun 的弹药…）。
     *
     * <p>★ 卓越前线的弹药与弹药盒**都是普通物品**（枪 id 就是注册名）⇒ 判注册名就够了，
     * 不需要反射；创造弹药盒是**独立物品** {@code superbwarfare:creative_ammo_box}。
     */
    private static String byRegistryNameLine(ItemStack stack) {
        String id = registryId(stack);
        if (id == null || !id.contains("ammo")) {
            return null;
        }
        int n = Math.max(1, stack.getCount());
        if (id.contains("all_type_creative")) {
            return "全类型创造弹药盒（任何枪 ⇒ 无限弹药）";
        }
        if (id.contains("creative")) {
            return "创造弹药盒（" + shortName(id) + " ⇒ 无限）";
        }
        if (id.contains("ammo_box")) {
            return "弹药盒（" + shortName(id) + "）x" + n;
        }
        return "弹药 " + shortName(id) + " x" + n;
    }

    private static String shortName(String registryId) {
        String s = registryId;
        int i = s.indexOf(':');
        if (i >= 0) {
            s = s.substring(i + 1);
        }
        return s.replace("_ammo", "").replace("_", " ");
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 有限/无限的统一拼装（口径只写在这里一处）。 */
    private static String compose(String ammoName, int loose, int inBoxes, int loaded,
                                  boolean creative, boolean allTypeCreative) {
        String name = ammoName == null ? "未知" : ammoName;
        if (allTypeCreative) {
            return "弹药 " + name + "：无限（全类型创造弹药盒）";
        }
        if (creative) {
            return "弹药 " + name + "：无限（创造弹药盒）";
        }
        StringBuilder sb = new StringBuilder("弹药 ").append(name).append("：备弹 ")
                .append(loose + inBoxes).append(" 发");
        List2 extra = new List2();
        if (loaded >= 0) {
            extra.add("弹匣内 " + loaded);
        }
        if (inBoxes > 0) {
            extra.add("含弹药盒 " + inBoxes);
        }
        if (!extra.isEmpty()) {
            sb.append("（").append(String.join("；", extra.items)).append("）");
        }
        return sb.toString();
    }

    /** 极小的字符串列表（只为拼括号里的备注；避免为它引入依赖）。 */
    private static final class List2 {
        private final java.util.List<String> items = new java.util.ArrayList<>(2);

        private void add(String s) {
            items.add(s);
        }

        private boolean isEmpty() {
            return items.isEmpty();
        }
    }

    private static String namespaceOf(String id) {
        if (id == null) {
            return null;
        }
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(0, i);
    }

    private static String registryId(ItemStack stack) {
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? null : id.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

        /**
     * 把她的物品按"原始 ItemStack"过一遍（{@code MaidSnapshot.carriedStacks} —— 与
     * {@code possessed} 逐格一致的口径）。
     *
     * <p>★ 为什么必须拿原始 stack：TaCZ 的 {@code IAmmo#isAmmoOfGun(gunStack, ammoStack)}
     * 吃的是 stack；而 {@code PossessedItem} 是纯逻辑快照（不带 MC 类型）。
     */
    private static java.util.List<ItemStack> herStacks(EntityMaid maid) {
        try {
            return MaidSnapshot.carriedStacks(maid);
        } catch (RuntimeException | LinkageError e) {
            return java.util.List.of();
        }
    }
}
