package com.touhoulittlemad.fightlikeplayer.compat.think;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.Advisor;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.BehaviorStats;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>调控顾问的运行器</b> —— 每 {@code advisor.intervalTicks} 问一次
 * 「她的风格该怎么调」，并把建议写进 {@link TuningBus}。
 *
 * <h2>★★ 线程模型：与 {@code Thinker} 同一条铁律</h2>
 * <pre>
 *   服务端主线程                                  HTTP 线程
 *   tick()  ├ 该问吗？（开关/间隔/在途）           │
 *           ├ judgeAsync(...) ─────────────────▶ │ 发请求
 *           └ return（不等待）                    │
 *   （下一 tick）poll() ─ 取走建议 ─ 写 TuningBus
 * </pre>
 * <b>回调里只写原子量</b>，绝不碰 {@code TuningBus}（它不是线程安全的），
 * 也绝不碰任何游戏状态。结果只能由主线程取走并施加。
 *
 * <h2>★ 为什么"默认只观测"是刻意的</h2>
 * 这是整个机制里唯一会<b>持续改变体系参数</b>的东西。人对"它到底在往哪调"的直觉是错的，
 * 所以第一版必须能<b>先看见它的意图</b>（{@code /flp tune advisor} 与日志），
 * 再决定是否让它真的生效。这与 docs/11 §7 的原则一致：<b>增强必须可观测、可回退</b>。
 *
 * <p>★ 与短周期判定的另一个区别：<b>顾问的统计是"每女仆一份"</b>
 * （一个在打 Boss、一个在挖矿，不该共用一份统计），但<b>它写的是全局旋钮</b>
 * —— 这是当前实现的刻意简化（旋钮是全局的），已在 docs 里登记为限制。
 */
public final class AdvisorBridge {

    private AdvisorBridge() {
    }

    /** 每个女仆的顾问状态。 */
    public static final class State {
        final BehaviorStats stats = new BehaviorStats();
        long lastSubmitTick = Long.MIN_VALUE;
        final AtomicLong inFlightGen = new AtomicLong(0);
        final AtomicLong nextGen = new AtomicLong(0);
        final AtomicReference<Map<String, Double>> pending = new AtomicReference<>();
        final AtomicReference<String> failed = new AtomicReference<>();
        final AtomicLong count = new AtomicLong();
        final AtomicLong applied = new AtomicLong();
        volatile String lastProposal = "(还没有问过)";
        volatile long lastLatencyMs;
        /** 最近一次真的生效的补丁（`/flp tune advisor` 显示它）。 */
        volatile Map<String, Double> lastApplied = Map.of();
    }

    /** 上次提问时刻（用于"太久没战斗就复原"）。 */
    private static volatile long lastCombatSeenTick = Long.MIN_VALUE;

    /**
     * 每 tick 调一次（主线程）。
     *
     * @param state      该女仆的顾问状态
     * @param need       当前需求（只用来采样"她长期偏向哪一轴"）
     * @param inCombat   现在算不算在战斗（无战斗则只采样、不提问）
     * @param healthPct  当前血量（用于受伤统计）
     */
    public static void tick(State state, TuningBus bus, EntityMaid maid, NeedVector need,
                            boolean inCombat, double healthPct, long gameTime) {
        TuningBus b = bus == null ? TuningBus.global() : bus;
        if (state == null) {
            return;
        }
        // ① 取走上一轮的建议（★ 主线程；回调只写原子引用）
        Map<String, Double> patch = state.pending.getAndSet(null);
        if (patch != null) {
            state.lastProposal = Advisor.describe(patch, b);
            boolean apply = "apply".equalsIgnoreCase(
                    FlpConfig.get(FlpConfig.ADVISOR_APPLY_MODE, "observe"));
            if (apply && !patch.isEmpty()) {
                TuningBus.ApplyResult r = b.applyAll(patch);
                state.applied.incrementAndGet();
                state.lastApplied = Map.copyOf(patch);
                log("★ 已按顾问建议调整：" + state.lastProposal
                        + (r.messages().isEmpty() ? "" : "　（" + String.join("；", r.messages()) + "）"));
            } else {
                log("顾问建议（仅观测，未生效）：" + state.lastProposal
                        + "　★ 想让它真的生效：配置里把 advisor.applyMode 设为 apply");
            }
        }
        String err = state.failed.getAndSet(null);
        if (err != null) {
            log("⚠ 顾问提问失败：" + err);
        }

        // ② 采样（每 tick 都很便宜：只是往滑窗里塞一个数）
        state.stats.noteNeed(gameTime, need);
        state.stats.noteHealth(healthPct);
        if (inCombat) {
            lastCombatSeenTick = gameTime;
        }

        // ③ 决定要不要问下一轮
        if (!FlpConfig.get(FlpConfig.ADVISOR_ENABLED, Boolean.FALSE)) {
            return;
        }
        if (!FlpConfig.thinkConfigured()) {
            return;                      // 没配 Key 就没什么可问的（与短周期共用同一份配置）
        }
        if (!inCombat && state.stats.isEmpty()) {
            return;                      // 没打过架、也没有任何统计 ⇒ 问了也白问
        }
        int interval = FlpConfig.get(FlpConfig.ADVISOR_INTERVAL_TICKS, 600);
        if (state.lastSubmitTick != Long.MIN_VALUE
                && gameTime - state.lastSubmitTick < interval) {
            return;
        }
        if (state.inFlightGen.get() != 0) {
            return;                      // 在途 ⇒ 不堆积（顾问是慢变量，丢一轮无所谓）
        }

        long gen = state.nextGen.incrementAndGet();
        state.inFlightGen.set(gen);
        state.lastSubmitTick = gameTime;
        state.count.incrementAndGet();
        long t0 = System.currentTimeMillis();

        Map<String, Object> stateJson = Advisor.state(state.stats, b, previousRoundFor(state));
        Map<String, Object> questions = Advisor.questions(advisorQuestionOverrides());
        CompletableFuture<Map<String, Object>> f;
        try {
            f = ThinkBridge.clientForAdvisor().judgeAsync(stateJson, questions);
        } catch (RuntimeException e) {
            state.inFlightGen.compareAndSet(gen, 0);
            state.failed.set(String.valueOf(e));
            return;
        }
        if (f == null) {
            state.inFlightGen.compareAndSet(gen, 0);
            state.failed.set("没有可用的 JEV 客户端（未配置 API Key？）");
            return;
        }
        f.whenComplete((resp, err2) -> {
            // ★★ HTTP 线程：只写原子量
            if (!state.inFlightGen.compareAndSet(gen, 0)) {
                return;                  // 已被判过期 ⇒ 丢弃
            }
            state.lastLatencyMs = System.currentTimeMillis() - t0;
            if (err2 != null) {
                Throwable c = err2.getCause() == null ? err2 : err2.getCause();
                state.failed.set(String.valueOf(c));
                return;
            }
            try {
                state.pending.set(Advisor.patch(resp, b));
            } catch (RuntimeException e) {
                state.failed.set("解析顾问返回失败：" + e);
            }
        });
    }

    /**
     * ★★ <b>立刻问一次</b>（忽略间隔与战斗判定）—— {@code /flp tune advisor now <女仆>} 用。
     *
     * <p>为什么要它：调控默认 600 tick（30 秒）一次，**测试时等 30 秒很难受**；
     * 而且"它到底会不会回、回什么"必须在几秒内可验证。
     * ⇒ 与 `/flp think now` 同一思路：**调试通路与真实通路走同一段代码**（只是跳过节流）。
     */
    public static void forceNow(State state) {
        if (state != null) {
            state.lastSubmitTick = Long.MIN_VALUE;
            state.inFlightGen.set(0);
        }
    }

    private static void log(String msg) {
        if (FlpConfig.get(FlpConfig.THINK_LOG_DECISIONS, Boolean.TRUE)) {
            FightLikePlayer.LOGGER.info("[FLP][advisor] {}", msg);
        }
    }

    /** 最近一次建议改的旋钮分别来自哪一层（诊断用）。 */
    private static String busDescription(State s) {
        if (s.lastApplied.isEmpty()) {
            return "（还没生效过任何调整）";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String k : s.lastApplied.keySet()) {
            parts.add(k);
        }
        return String.join(" / ", parts);
    }

    /**
     * ★★ <b>有界上下文：上一轮的建议 + 它有没有被采纳</b>
     * （委托方 2026-10-01 明确问过「llm 动态指挥是默认有上下文功能，还是没有？」）。
     *
     * <p>诚实的答案是：<b>默认没有会话历史</b>（JEV 是打分模型，不接受消息历史），
     * 但有<b>三样"记忆"</b>：一段时间的行为统计、当前旋钮值（含上次改出来的效果）、
     * 以及这一格"上一轮我建议了什么、生效了没有"。
     *
     * <p>★ 为什么只给一格、不给十格：多轮历史会让"风格"随模型自己的措辞漂移，
     * 而我们真正需要防的只有一种情况 —— <b>它反复提同一个建议却被忽略</b>
     * （那说明"建议"和"实际旋钮"之间存在断层，应当让它看见）。
     */
    private static String previousRoundFor(State state) {
        if (state == null || state.lastProposal == null || state.lastProposal.isBlank()) {
            return null;
        }
        boolean appliedOk = !state.lastApplied.isEmpty();
        String mode = FlpConfig.get(FlpConfig.ADVISOR_APPLY_MODE, "observe");
        return "Last round you proposed: " + state.lastProposal
                + (appliedOk
                ? "  [it WAS applied to the knobs]"
                : ("apply".equalsIgnoreCase(mode)
                ? "  [it was NOT applied: either you answered 'keep as it is', or the values were rejected]"
                : "  [it was NOT applied yet because the config is in observe-only mode"
                        + " -- your advice is being recorded, not enforced]"));
    }

    /** 诊断汇总（`/flp tune advisor` 用）。 */
    public static String describe(State s) {
        if (s == null) {
            return "（这个女仆还没进过决策循环）";
        }
        boolean enabled = FlpConfig.get(FlpConfig.ADVISOR_ENABLED, Boolean.FALSE);
        String mode = FlpConfig.get(FlpConfig.ADVISOR_APPLY_MODE, "observe");
        StringBuilder sb = new StringBuilder();
        sb.append("开关 = ").append(enabled ? "§a开" : "§c关")
                .append("§r　去向 = ").append("apply".equalsIgnoreCase(mode)
                        ? "§e真的改旋钮§r" : "§7仅观测（建议先用这个）§r")
                .append("　间隔 = ").append(FlpConfig.get(FlpConfig.ADVISOR_INTERVAL_TICKS, 600))
                .append(" tick\n");
        sb.append("  已提问 ").append(s.count.get()).append(" 次")
                .append("　已生效 ").append(s.applied.get()).append(" 次")
                .append("　最近往返 ").append(s.lastLatencyMs).append(" ms\n");
        sb.append("  行为统计：").append(s.stats.summary()).append('\n');
        sb.append("  最近建议：").append(s.lastProposal).append('\n');
        sb.append("  生效层：").append(busDescription(s)).append('\n');
        sb.append("§7  ").append(Advisor.describeScope()).append("§r\n");
        return sb.toString();
    }

    /** 是否需要"久不战斗就复原"（配置打开且确实闲置很久）。 */
    public static boolean shouldResetOnIdle(long gameTime) {
        if (!FlpConfig.get(FlpConfig.ADVISOR_RESET_ON_IDLE, Boolean.FALSE)) {
            return false;
        }
        return lastCombatSeenTick != Long.MIN_VALUE
                && gameTime - lastCombatSeenTick > 20L * 60 * 5;
    }

    /**
     * ★★ <b>顾问的提示词覆写</b>（配置界面可编辑，见 {@code FlpConfig#ADVISOR_QUESTION_OVERRIDES}）。
     * <p>与 {@code ThinkBridge#questionOverrides()} 同构：<b>文本变了才重新解析</b>。
     */
    public static Map<String, String> advisorQuestionOverrides() {
        String text = FlpConfig.get(FlpConfig.ADVISOR_QUESTION_OVERRIDES, "");
        if (text == null) {
            text = "";
        }
        if (!text.equals(cachedAdvisorOverrideText)) {
            cachedAdvisorOverrideText = text;
            var parsed = com.touhoulittlemad.fightlikeplayer.decision.thinking.PromptOverrides
                    .parseForAdvisor(text);
            cachedAdvisorOverrides = parsed.overrides();
            if (!parsed.isEmpty() || !parsed.ignoredKeys().isEmpty()) {
                log("顾问提示词覆写已更新：" + parsed.summary());
            }
        }
        return cachedAdvisorOverrides;
    }

    private static String cachedAdvisorOverrideText;
    private static Map<String, String> cachedAdvisorOverrides = Map.of();
}
