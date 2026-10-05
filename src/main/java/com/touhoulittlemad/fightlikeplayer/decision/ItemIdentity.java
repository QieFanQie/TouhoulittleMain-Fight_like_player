package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.Locale;

/**
 * ★★ <b>「同一个注册名下有多个不同的东西」⇒ 必须另立身份 id</b>（第二十六轮，拔刀剑版）。
 *
 * <h2>为什么又有这一类问题（与枪械同型，第 79 条纪律）</h2>
 * 委托方实测：「女仆不认为**魔剑「阎魔刀」**是拔刀剑」。查下去发现两件事，
 * 其中一件是身份问题：
 * <pre>
 *   SlashBlade: Resharped 的**所有具名刀**都是同一个物品 `slashblade:slashblade`
 *   （`SlashBladeDefinition` 的 `item` 字段默认值就是它，`javap -c` 取证），
 *   真正的身份写在刀的状态里：{@code ISlashBladeState#getTranslationKey()} = "item.slashblade.yamato"
 *   ⇒ 与 TaCZ 的枪（共用一个注册名、身份在 NBT 的 GunId）**完全同型**。
 * </pre>
 * ⇒ 物品清单里每把刀都显示成同一个 id、`only_item` 也**指不到具体哪一把刀**。
 * 修法与枪一致：<b>身份 = 它自己的 id</b>（`slashblade:yamato`），注册名降级为**别名**
 * （这样 `only_item slashblade:slashblade` 这种老写法仍然命中）。
 *
 * <h2>★ 为什么解析函数在纯逻辑层</h2>
 * "把翻译键变成身份 id"是个纯字符串运算，而它正是**最容易写错、又最容易静默写错**的一步
 * （键的格式 `item.&lt;namespace&gt;.&lt;path&gt;`，path 里还可能带点号）。
 * 放在这里 ⇒ 可离线断言（见 {@code DirectiveSelfTest}）。
 */
public final class ItemIdentity {

    private ItemIdentity() {
    }

    /**
     * 翻译键 → 身份 id。
     *
     * <pre>
     *   "item.slashblade.yamato"      ⇒ "slashblade:yamato"
     *   "item.slashblade.slashblade_wood" ⇒ "slashblade:slashblade_wood"
     *   "slashblade:yamato"           ⇒ "slashblade:yamato"（已经是 id ⇒ 原样返回）
     *   ""、null、"item.slashblade"   ⇒ null（认不出 ⇒ 不猜，调用方退回注册名）
     * </pre>
     *
     * @return 身份 id；认不出 ⇒ {@code null}
     */
    public static String fromTranslationKey(String key) {
        if (key == null) {
            return null;
        }
        String s = key.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 已经是 `namespace:path` 形式 ⇒ 直接用（有的附属直接写 id）
        if (s.indexOf(':') > 0 && !s.startsWith("item.")) {
            return s;
        }
        String body = s.startsWith("item.") ? s.substring("item.".length()) : s;
        int dot = body.indexOf('.');
        if (dot <= 0 || dot + 1 >= body.length()) {
            return null;
        }
        String ns = body.substring(0, dot);
        String path = body.substring(dot + 1);
        if (ns.isBlank() || path.isBlank()) {
            return null;
        }
        return ns + ":" + path;
    }

    /**
     * 身份 id 的**简写**（`slashblade:yamato` ⇒ `yamato`）—— 别名用（模型可能只说刀名）。
     */
    public static String pathOf(String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
        return path.isBlank() ? null : path.toLowerCase(Locale.ROOT);
    }
}
