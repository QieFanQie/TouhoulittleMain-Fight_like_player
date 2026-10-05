package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import java.util.List;
import java.util.Map;

/**
 * <b>一次判定的"局势快照"</b> —— 思维层的输入，<b>纯数据、不含任何 Minecraft 类型</b>。
 *
 * <h2>★★ 一个刻意的设计决定：这里<b>不</b>放弹簧点</h2>
 * 弹簧点 {@code p} 表示"她此刻内部倾向于什么"。把它喂给 JEV 看起来很有用
 * （"玩家也知道自己刚才在干什么"），但它会造成<b>正反馈回路</b>：
 * <pre>
 *   p[SINGLE] 高 → JEV 看到"她想单体" → 判定 "single 很重要" → 再加偏置 → p[SINGLE] 更高 → …
 * </pre>
 * ⇒ 因此本快照<b>只描述客观局势</b>。"积累的惯性"由弹簧本身负责 ——
 * 那是它的本职，不需要思维层再复述一遍。
 *
 * <h2>★ 为什么用纯 record 而不是直接拼 Map</h2>
 * 这样"喂给模型的东西"是<b>可断言</b>的：离线自测可以构造一个快照，断言生成的
 * {@code state} 里有哪些字段、没有哪些字段 —— 而不是只能靠读代码猜。
 *
 * @param task             当前任务 id（非战斗任务会被上层直接跳过）
 * @param selfHealthPct    女仆血量百分比 0~1
 * @param ownerHealthPct   主人血量百分比 0~1；无主人时与 {@code selfHealthPct} 相同
 * @param ownerPresent     有主人在附近
 * @param enemyCount       附近敌对目标数（0 = 无）
 * @param nearestEnemyDist 最近敌人距离（格）；无敌人时为 {@code -1}
 * @param targetVisible    当前目标可见
 * @param inMeleeRange     已在近战距离内
 * @param hurtRecently     最近 3 秒内受过伤
 * @param servantCount     她的仆从数（Goety）
 * @param mainHand         主手物品注册名（空手 = {@code "(empty)"}）
 * @param offHand          副手物品注册名
 * @param gait             当前步法名（{@code advance}/{@code retreat}/…）
 * @param lastAction       最近一次提交的动作 id（没有则 {@code "(none)"}）
 * @param availableActions 她此刻真的能做的动作 id（★ 已按族去重并截断）
 *
 * @see ThinkPrompt
 */
public record ThinkScene(
        String task,
        double selfHealthPct,
        double ownerHealthPct,
        boolean ownerPresent,
        int enemyCount,
        double nearestEnemyDist,
        boolean targetVisible,
        boolean inMeleeRange,
        boolean hurtRecently,
        int servantCount,
        String mainHand,
        String offHand,
        String gait,
        String lastAction,
        List<String> availableActions
) {

    /** 快照的字段数上限（防止把整个候选集塞进提示词）。 */
    public static final int MAX_ACTIONS = 24;

    /** ★ 距离超过这个格数就视为"距离无效"（不是"很远"）。 */
    public static final double MAX_MEANINGFUL_DISTANCE = 1.0e4;

    /** ★ 无效距离的哨兵值（会以 {@code distance_is_valid=false} 的形式告诉模型）。 */
    public static final double INVALID_DISTANCE = -1.0;

    public ThinkScene {
        task = or(task, "(unknown)");
        mainHand = or(mainHand, "(empty)");
        offHand = or(offHand, "(empty)");
        gait = or(gait, "(none)");
        lastAction = or(lastAction, "(none)");
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
        // ★★ 距离必须在【构造期】就规整掉，不能留到 toState()。
        //   根因（自测抓到的真 bug）：`ContextFacts.Vitals.unknown()` 在"无目标"时给的是
        //   `Double.MAX_VALUE`，而 `round()` 会算 `Math.round(MAX_VALUE * 100)` ⇒ 溢出成
        //   `Long.MAX_VALUE` ⇒ 模型收到 `9.223372036854776E16` 这种荒谬的数，
        //   而且 `>= 0` 判定会让它以为"距离有效"。
        nearestEnemyDist = normalizeDistance(nearestEnemyDist);
    }

    private static double normalizeDistance(double d) {
        if (!(d >= 0.0) || d > MAX_MEANINGFUL_DISTANCE) {
            return INVALID_DISTANCE;
        }
        return d;
    }

    private static String or(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * 转成喂给 JEV 的 {@code state}。
     *
     * <p>★ <b>英文键 + 英文值</b>：JEV 的训练语言以英文为主
     * （使用指南 §7「中文可用但建议自测精度」）⇒ 送进模型的文本一律英文，
     * 只有<b>给委托方看的日志与命令输出</b>才用中文。
     */
    public Map<String, Object> toState() {
        Map<String, Object> maid = new java.util.LinkedHashMap<>();
        maid.put("health_pct", round(selfHealthPct));
        maid.put("recently_hurt", hurtRecently);
        maid.put("servants_under_command", servantCount);

        Map<String, Object> owner = new java.util.LinkedHashMap<>();
        owner.put("present", ownerPresent);
        owner.put("health_pct", round(ownerHealthPct));

        Map<String, Object> battle = new java.util.LinkedHashMap<>();
        battle.put("enemies_nearby", enemyCount);
        // ★ 不用 null 做哨兵：嵌套的 null 会被 Gson 默认丢掉（JSON 里索性没有这个键），
        //   那反而让模型看到一个"缺字段"。用 -1 并显式说明。
        battle.put("nearest_enemy_distance_blocks", round(nearestEnemyDist));
        battle.put("distance_is_valid", nearestEnemyDist >= 0);
        battle.put("target_visible", targetVisible);
        battle.put("already_in_melee_range", inMeleeRange);

        Map<String, Object> equip = new java.util.LinkedHashMap<>();
        equip.put("main_hand", mainHand);
        equip.put("off_hand", offHand);
        equip.put("movement_stance", gait);

        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("who", "A combat maid companion in Minecraft, fighting alongside her owner.");
        state.put("maid", maid);
        state.put("owner", owner);
        state.put("battle", battle);
        state.put("equipment", equip);
        state.put("current_task", task);
        state.put("last_action_taken", lastAction);
        state.put("actions_she_can_do_right_now", availableActions);
        state.put("how_to_read", "Judge ONLY from the facts above. "
                + "Do not assume anything not listed. "
                + "If a fact says there are no enemies, she is not in a fight.");
        return state;
    }

    /** 保留两位小数（★ 必须先挡掉非有限值，否则 {@code Math.round(MAX_VALUE*100)} 会溢出）。 */
    private static Double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 1.0e12) {
            return null;
        }
        return Math.round(v * 100.0) / 100.0;
    }

    /** 一行摘要（日志与 {@code /flp think} 用）。 */
    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "血%.0f%%/主%.0f%% 敌%d 最近%.1f格 可见=%s 近战=%s 仆从%d 主手=%s 步法=%s 上次=%s 可选%d条",
                selfHealthPct * 100, ownerHealthPct * 100, enemyCount, nearestEnemyDist,
                targetVisible, inMeleeRange, servantCount, mainHand, gait, lastAction,
                availableActions.size());
    }
}
