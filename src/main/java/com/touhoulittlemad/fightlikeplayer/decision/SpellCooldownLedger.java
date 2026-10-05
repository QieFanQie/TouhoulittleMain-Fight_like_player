package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * ★★ <b>「法术自己的冷却」账本</b> —— 纯逻辑，可离线断言。
 *
 * <h2>为什么必须有它（委托方第十一轮提问：「铁魔法/巫法的前摇、冷却是不是都被取消了？」）</h2>
 * <b>答案是：前摇与冷却都被跳过了 —— 两者的权威来源都是法术自己，而我们没读。</b>
 *
 * <table border="1">
 *   <tr><th>模组</th><th>前摇</th><th>冷却</th></tr>
 *   <tr><td><b>铁魔法</b>（ISS）</td>
 *       <td>玩家路径 {@code attemptInitiateCast} 会先等 {@code getEffectiveCastTime(level, entity)} 个 tick
 *           （LONG/CONTINUOUS）；而我们与 ISS 自己的 mob 路径一样<b>直接调 {@code onCast}</b>
 *           —— 那是<b>效果</b>钩子（默认实现只播一个音效）⇒ <b>前摇被跳过</b></td>
 *       <td>{@code castSpell(...)} 里 {@code MagicHelper.MAGIC_MANAGER.addCooldown(serverPlayer, ...)}，
 *           形参是 <b>{@code ServerPlayer}</b> ⇒ 对女仆<b>从来没有冷却</b></td></tr>
 *   <tr><td><b>诡厄巫法</b>（Goety）</td>
 *       <td>★ <b>没有被跳过</b>：蓄力类按 {@code castUp} 等前摇、定长引导类按 {@code castDuration} 引导
 *           （第九/十轮落地，见 {@code GoetyChannel.Mode}）</td>
 *       <td>玩家侧走 {@code SEHelper.addCooldown(player, ...)} ⇒ 同样是 <b>Player-only</b>；女仆无冷却</td></tr>
 * </table>
 *
 * <p>⇒ 两个模组的冷却都能用一个数表达：<b>法术自己报的冷却 tick 数</b>
 * （ISS {@code AbstractSpell#getSpellCooldown()}、Goety {@code ISpell#spellCooldown(caster)}）。
 * 本类就是那个账本：<b>记下"这颗法术什么时候才能再放"，选法术时把没到的排除掉。</b>
 *
 * <h2>★ 为什么账本按「法术标签」而不是按「动作」</h2>
 * {@code goety:cast_focus} 一条动作覆盖<b>所有</b>聚晶，{@code irons:cast_spell} 覆盖所有法术
 * ⇒ 冷却属于<b>法术</b>，不属于动作。放在动作层会让"放了火球之后连治疗也放不了"。
 *
 * <p>★ 三条纪律：
 * <ol>
 *   <li><b>钳制</b>：第三方给的值可能离谱（0 / 负数 / 六位数）⇒ 落库前 clamp 到 {@code [0, MAX]}；</li>
 *   <li><b>fail-open</b>：读不到冷却（抛异常）⇒ 当 0 处理（宁可多放，不可卡死）；
 *       这与"数据缺字段不能静默变成永久禁用"是同一条纪律；</li>
 *   <li><b>可观测</b>：被冷却挡下的法术要能说出来（调用方拿 {@link #describe} 写日志）。</li>
 * </ol>
 */
public final class SpellCooldownLedger {

    /** 冷却上限（tick）：5 分钟。超过它的值一律视为"第三方写错了"。 */
    public static final int MAX_COOLDOWN_TICKS = 20 * 300;

    /** 标签 → 可以再放的时刻。 */
    private final Map<String, Long> readyAt = new HashMap<>();

    /**
     * 记一次使用。
     *
     * @param label    法术标签（类名 / 法术 id）
     * @param now      当前时刻（tick）
     * @param cooldown 这次之后要等多少 tick（{@code <= 0} ⇒ 不记，看作无冷却）
     */
    public void markUsed(String label, long now, int cooldown) {
        if (label == null || label.isBlank()) {
            return;
        }
        int c = Math.max(0, Math.min(MAX_COOLDOWN_TICKS, cooldown));
        if (c <= 0) {
            readyAt.remove(label);
            return;
        }
        readyAt.put(label, now + c);
    }

    /** 这颗法术现在能不能放。 */
    public boolean isReady(String label, long now) {
        Long t = readyAt.get(label);
        return t == null || now >= t;
    }

    /** 还要等多少 tick（0 = 现在就能放）。 */
    public int remaining(String label, long now) {
        Long t = readyAt.get(label);
        if (t == null || now >= t) {
            return 0;
        }
        long left = t - now;
        return (int) Math.min(Integer.MAX_VALUE, left);
    }

    /** 一个"现在不能放"的判据（给 {@code SpellPicker} 用）。 */
    public Predicate<String> notReady(long now) {
        return label -> !isReady(label, now);
    }

    /**
     * 一句话说明"哪些法术还在冷却"（诊断用）。
     *
     * @param labels 关心的标签集合；空 ⇒ 返回空串
     */
    public String describe(Iterable<String> labels, long now) {
        StringBuilder sb = new StringBuilder();
        if (labels == null) {
            return "";
        }
        for (String l : labels) {
            int left = remaining(l, now);
            if (left <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(l).append('(').append(left).append("t)");
        }
        return sb.toString();
    }

    /** 当前有多少条在冷却（诊断用）。 */
    public int size() {
        return readyAt.size();
    }

    /** 清空（脱战时调用，避免"上一场战斗的冷却"跟着她走）。 */
    public void clear() {
        readyAt.clear();
    }
}
