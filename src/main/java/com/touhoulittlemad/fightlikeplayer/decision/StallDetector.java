package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>卡死检测的判据与后果</b> —— 纯逻辑，可离线断言。
 *
 * <h2>为什么它必须在 {@code decision/}（而不是写在行为层里）</h2>
 * 委托方 2026-10-01 第 3 条要求：「为了防止女仆被某个动作卡住，可以引入一些检测机制，
 * 在坏的情况下跳过该步骤强行进入下一个循环」。
 * 这条策略有<b>明确的可断言性质</b>（什么时候算卡住、停放多久、停放会过期），
 * 而"能不能离线断言"正是本项目的纪律（docs/13）：<b>凡是有判据的地方就放在纯逻辑层</b>，
 * 否则只能"进游戏试试看"。behavior 层只负责把世界状态翻译成这里的入参。
 *
 * <h2>★★ 判据为什么是"世界有没有变化"，而不是"动作成不成功"</h2>
 * 项目此前已经有两道保险丝，都不管这一种最坏情况：
 * <ul>
 *   <li>「无执行器」过滤 —— 管"压根没人实现"；</li>
 *   <li>连败退避 —— 管"<b>回报失败</b>的动作"；</li>
 *   <li>法术拉黑 / 引导硬上限 —— 管"抛异常 / 收不了尾"。</li>
 * </ul>
 * 而实测的 {@code FlameStrikeSpell}（炽燃升腾）属于最坏的一种：
 * <b>回报成功、照扣代价，但世界里什么都没发生</b>
 * （它的活儿在 {@code startSpell} 里，而我们当时只调了 {@code SpellResult}）。
 * 这种"成功但没用"<b>既不增加连败、也不抛异常</b> ⇒ 前三道全都不响。
 * ⇒ 只能从<b>结果</b>上判：她在战斗，却在一段时间里没有任何可观测变化。
 */
public final class StallDetector {

    private StallDetector() {
    }

    /**
     * ★ 多久"毫无可观测变化"就判定卡住（tick）。
     *
     * <p>120 tick = 6 秒。取值理由：<b>下限</b>由"最长合法在途"决定
     * （goety 引导的硬上限就是 120 tick）——比它短会把正常的长引导误判成卡死；
     * <b>上限</b>由体感决定 —— 再长用户已经明显觉得"她傻了"。
     */
    public static final int STALL_TICKS = 120;

    /** ★ 判定卡住后，把嫌疑动作<b>停放</b>的基础时长（tick，按连续命中次数倍增）。 */
    public static final int PARK_TICKS = 200;

    /**
     * ★ 停放时长的上限。
     * <p>为什么要上限：停放是"临时让位"，不是"永久禁用"。
     * 永久禁用属于<b>数据/执行器缺口的职责</b>（应当被修掉），
     * 而不是让一个通用保险丝悄悄把某个动作从她的世界里删掉。
     */
    public static final int PARK_TICKS_MAX = 20 * 60;

    /**
     * ★★ <b>进展指纹</b> —— 粗粒度，只看"结果"。
     *
     * <p>太细（带上 {@code tickCount}）⇒ 永远在变，看门狗永不触发；
     * 太粗（只看目标在不在）⇒ 正常对打也会被误判。
     * 这五项覆盖了两类真实进展：<b>打中了</b>（目标血量）、<b>在接近/拉开</b>（距离），
     * 外加她自己的状态与位置。
     *
     * @param targetId   目标实体 id（换目标 = 有进展）
     * @param targetHp   目标血量（★ 调用方取整，避免浮点抖动导致"永远有变化"）
     * @param distance   距离（★ 同理取整）
     * @param selfHp     自己的血量（取整）
     * @param blockPos   自己所在方块坐标（打包成一个 long）
     */
    public static String progressKey(int targetId, int targetHp, int distance, int selfHp, long blockPos) {
        return targetId + "|" + targetHp + "|" + distance + "|" + selfHp + "|" + blockPos;
    }

    /** 无目标时的指纹（脱战）。 */
    public static final String NO_TARGET_KEY = "-";

    /**
     * ★ 是否判她"卡住"。
     *
     * @param lastProgressAt 上一次"有进展"的时刻
     * @param now            当前时刻
     * @param inCombat       ★ 必须为真才判 —— 脱战站着不动是<b>正常的</b>
     */
    public static boolean isStalled(long lastProgressAt, long now, boolean inCombat) {
        return inCombat && now - lastProgressAt >= STALL_TICKS;
    }

    /**
     * ★ 第 {@code strike} 次判卡住时，嫌疑动作要停放多久。
     *
     * <p>倍增（200 / 400 / 600 …，封顶 1200）而不是固定值：
     * 偶发的一次卡顿只让位 10 秒；同一个动作反复把她卡住，就让位得越来越久，
     * 于是别的动作/回退动作有充分机会被选到。
     */
    public static int parkTicksFor(int strike) {
        int n = Math.max(1, strike);
        long v = (long) PARK_TICKS * n;
        return (int) Math.min(PARK_TICKS_MAX, v);
    }

    /**
     * ★ 停放是否仍然生效。
     * <p>"过期即解禁"是这套机制能自我收敛的关键：<b>不会有人被永久拉黑。</b>
     */
    public static boolean isParked(Long until, long now) {
        return until != null && until > now;
    }

    /** 把停放表里已过期的项清掉（避免 map 无限增长）。 */
    public static void prune(Map<String, Long> parkedUntil, long now) {
        if (parkedUntil == null || parkedUntil.isEmpty()) {
            return;
        }
        parkedUntil.entrySet().removeIf(e -> !isParked(e.getValue(), now));
    }

    /**
     * ★ 把停放表并进"冷却中"集合（<b>复用既有的冷却通路</b>，不新增机制）。
     *
     * <p>为什么刻意复用：解析期已经有一条 {@code DropReason.ON_COOLDOWN} 的丢弃原因，
     * 于是"被停放"的效果在 {@code /flp why} 里<b>本来就能看见</b> ——
     * 新增一套"停放"通路只会多一个没人看得见的静默状态。
     */
    public static void mergeInto(Map<String, Long> parkedUntil, long now, java.util.Set<String> cooling) {
        if (parkedUntil == null || parkedUntil.isEmpty() || cooling == null) {
            return;
        }
        for (Map.Entry<String, Long> e : parkedUntil.entrySet()) {
            if (isParked(e.getValue(), now)) {
                cooling.add(e.getKey());
            }
        }
    }

    /** 供诊断/自测：一个空的停放表。 */
    public static Map<String, Long> newParkedTable() {
        return new LinkedHashMap<>();
    }
}
