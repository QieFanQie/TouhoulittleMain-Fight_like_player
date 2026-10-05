package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>「她的东西按用途分类」的类别表</b>（第二十五轮，委托方第 2 条）。
 *
 * <h2>委托方原话</h2>
 * > 「在女仆可获取物品信息的部分，在信息处分类：即从『物品栏里有：A、B、C』到
 * > 『物品栏内有：拔刀剑相关：A、B；枪械相关：C、D（**每个枪支消耗的弹药种类，
 * > 以及拥有的弹药及多少（检测弹药箱）**也要写在里面，记得创造弹药盒和全类型创造弹药盒
 * > 这些特殊案例）』」
 *
 * <h2>★ 为什么"分类"这件事要有一张显式的表</h2>
 * 我们要的不是"猜它是什么"，而是<b>用已有的权威分类</b>：
 * 载体规则（{@code carriers.json}）里每条规则的 {@code carrier} 字段**本来就在回答
 * "这件东西是哪一类"**（{@code tacz:gun} / {@code slashblade:blade} / {@code goety:wand} …）。
 * ⇒ 分类 = **命中她物品的那条最专精的载体规则**（与解析器同一套「专精度抑制」口径），
 * 只有"枪 / 弹药 / 弹药盒"这三种是数据层表达不了的（它们是**同一个注册名 + NBT 变体**），
 * 由 compat 层用各自的模组 API 另判 —— 见 {@code AmmoInfo}。
 *
 * <p>★ 纯逻辑：类别名与顺序是数据，可以离线断言（{@code DirectiveSelfTest}）。
 */
public final class ItemGroups {

    private ItemGroups() {
    }

    /** 枪（含 TaCZ / 卓越前线 / Pillager's Gun —— 它们由 compat 层判定后归到这一类）。 */
    public static final String KEY_GUN = "gun";
    /** 弹药（散装子弹/箭）。
     *  <p>★ 委托方明确要求把"每支枪消耗什么弹药、她有多少"写进"枪械相关"里 ——
     *  散装弹药自己也成类（否则弹药会掉进"其它"，模型就看不到她有多少发）。 */
    public static final String KEY_AMMO = "ammo";
    /** 弹药盒（含创造弹药盒 / 全类型创造弹药盒）。 */
    public static final String KEY_AMMO_BOX = "ammo_box";
    /** 认不出来的（原版杂物、食物、方块…）。 */
    public static final String KEY_OTHER = "other";

    /**
     * 类别表：键 → 中文名。**顺序 = 展示顺序**（LinkedHashMap）。
     *
     * <p>排序原则（与 {@code CombatSceneBuilder.itemPriority} 同一条）：<b>越容易因为看不到而猜错的越靠前</b>
     * —— 枪（指令参数就是它的 id）> 弹药/弹药盒（决定"还能不能打"）> 拔刀剑 > 近战 > 远程 > 两系法术
     * > 防御 > 投掷 > 其它。
     */
    private static final Map<String, String> NAMES = buildNames();

    private static Map<String, String> buildNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KEY_GUN, "枪械");
        m.put(KEY_AMMO, "弹药");
        m.put(KEY_AMMO_BOX, "弹药盒");
        m.put("slashblade:blade", "拔刀剑");
        m.put("maid:weapon", "近战武器");
        m.put("maid:bow", "弓");
        m.put("maid:crossbow", "弩");
        m.put("maid:trident", "三叉戟");
        m.put("maid:danmaku", "弹幕");
        m.put("maid:throwable", "投掷物");
        m.put("goety:wand_with_focus", "诡厄巫法·魔杖");
        m.put("goety:wand", "诡厄巫法·魔杖（空）");
        m.put("goety:command_horn", "诡厄巫法·命令呼号");
        m.put("goety:focus_recall", "诡厄巫法·回溯聚晶");
        m.put("irons:spellbook_or_scroll", "铁魔法·法术书/卷轴");
        m.put("irons:spellbook_or_slot", "铁魔法·法术书（饰品）");
        m.put("maid:shield", "盾");
        m.put("maid:extinguisher", "灭火器");
        m.put(KEY_OTHER, "其它");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 类别的展示顺序（越小越靠前）。 */
    public static int orderOf(String key) {
        int i = 0;
        for (String k : NAMES.keySet()) {
            if (k.equals(key)) {
                return i;
            }
            i++;
        }
        return NAMES.size();                 // 认不出的键排在最后
    }

    /** 类别的中文名（认不出的键给「其它」）。 */
    public static String nameOf(String key) {
        String zh = NAMES.get(key);
        return zh == null ? NAMES.get(KEY_OTHER) : zh;
    }

    /**
     * 把一条**载体规则的 carrier 字段**翻译成类别键。
     *
     * <p>★ 三个枪包共用一类（{@code gun}）：对模型来说"命令呼号 / 魔杖 / 刀"的分类意义是
     * "**怎么用它**"，而三家的枪在指令层面完全同构（`only_item` + 枪 id）。
     */
    public static String fromCarrier(String carrier) {
        if (carrier == null || carrier.isBlank()) {
            return KEY_OTHER;
        }
        if ("tacz:gun".equals(carrier) || "superbwarfare:gun".equals(carrier)
                || "pillagersgun:gun".equals(carrier)) {
            return KEY_GUN;
        }
        return NAMES.containsKey(carrier) ? carrier : KEY_OTHER;
    }

    /** 全部类别键（按展示顺序）—— 自测与文本拼装用。 */
    public static List<String> keysInOrder() {
        return List.copyOf(NAMES.keySet());
    }
}
