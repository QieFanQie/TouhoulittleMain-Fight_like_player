package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * ★★ <b>法术「引导长度」的判据</b> —— 纯逻辑，可离线断言。
 *
 * <h2>为什么它必须在这里（而不是写在 Goety 的通道驱动里）</h2>
 * 这是本项目踩过两次的同一个坑（[docs/13 §29](../docs/13-更正记录与教训.md)）：
 * {@code GoetyChannel} 的签名里带着 {@code EntityMaid}/{@code ItemStack}，
 * 离线的自测**一碰它就 {@code NoClassDefFoundError: LivingEntity}**
 * ⇒ 等于放弃了对这段判据的离线断言。
 * ⇒ 判据本身不含 MC 类型 ⇒ 放到 {@code decision/} 层，双方都调它：
 * <b>游戏侧（{@code GoetyChannel}）与离线自测用的是同一份算术。</b>
 *
 * <h2>★★ 三条由委托方实测定型的规则</h2>
 * <ol>
 *   <li><b>72000 不是时长，是哨兵。</b>
 *       {@code IChargingSpell.defaultCastDuration() == 72000}（= 1 小时）语义是
 *       「按住不放就一直放」的**硬上限**。⇒ 超过 {@link #MAX_SANE_CHANNEL} 的值一律**不信**，
 *       按"长按类"自己管长度。若不这么做，箭雨/光束会被引导 **72000 tick**（硬上限 1200）
 *       —— 女仆被锁死一整分钟。</li>
 *   <li><b>定长引导类的长度 = 法术自己的 {@code castDuration}。</b>
 *       例：熔岩炸弹（{@code LavaballDuration} 默认 **40**）、火焰升腾（**60**）、
 *       召回（**160**）。短于它就会出现委托方实测的「**释放到一半被自己打断重新放**」。</li>
 *   <li><b>蓄力长按类的长度 = 前摇 {@code castUp} + 持续预算</b>（配置旋钮）。
 *       例：腐化/水蛭的源码默认是 {@code castUp = 0}、{@code Duration = 0}（**0 = 无限**），
 *       玩家语义是"按住不放就一直放" ⇒ 女仆没有手，长度必须由我们给。
 *       ★ 但它只是**上限**：目标一死就立刻收（那才是"持续时间长"的正确语义）。</li>
 * </ol>
 */
public final class SpellChannelPolicy {

    private SpellChannelPolicy() {
    }

    /**
     * ★ 大于它就<b>不信任</b> {@code castDuration}（视为蓄力长按类，长度我们自己定）。
     *
     * <p>理由：真实用例的最大值是 {@code recall = 160}（{@code SpellConfig} 的默认值），
     * 而 72000 是哨兵。10 秒（200 tick）之上只可能是哨兵或被改坏的值。
     */
    public static final int MAX_SANE_CHANNEL = 200;

    /** 长按类引导长度的下界：再短就是"短到看不见"（委托方实测过这个现象）。 */
    public static final int MIN_HOLD = 20;

    /** 长按类引导长度的上界（即使配置旋钮给得很大）。 */
    public static final int MAX_HOLD = 200;

    /** 持续预算的默认值（tick）—— 与 {@code FlpConfig.GOETY_SUSTAIN_TICKS} 的默认值一致。 */
    public static final int DEFAULT_SUSTAIN_TICKS = 60;

    /** {@code castDuration} 是不是一个"可信的时长"（而不是 72000 那种哨兵）。 */
    public static boolean isSaneChannel(int castDuration) {
        return castDuration > 0 && castDuration <= MAX_SANE_CHANNEL;
    }

    /**
     * 蓄力长按类的引导长度。
     *
     * @param castUp      法术自己的前摇（{@code IChargingSpell#castUp}）
     * @param sustainTicks 持续预算（配置旋钮；{@code <= 0} ⇒ 用默认值）
     */
    public static int chargingHold(int castUp, int sustainTicks) {
        int sustain = sustainTicks <= 0 ? DEFAULT_SUSTAIN_TICKS : sustainTicks;
        int hold = Math.max(0, castUp) + sustain;
        return Math.max(MIN_HOLD, Math.min(MAX_HOLD, hold));
    }

    /** 兜底：用默认持续预算。 */
    public static int chargingHold(int castUp) {
        return chargingHold(castUp, DEFAULT_SUSTAIN_TICKS);
    }
}
