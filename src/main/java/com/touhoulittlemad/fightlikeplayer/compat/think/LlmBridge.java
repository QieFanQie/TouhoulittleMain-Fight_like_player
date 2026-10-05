package com.touhoulittlemad.fightlikeplayer.compat.think;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;
import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.LlmAdvisor;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.LlmClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ★★ <b>LLM 动态指挥的桥</b> —— 把「chat 模型」接进行为层（委托方第十二轮点名要的那一半）。
 *
 * <h2>★★ 与 {@code AdvisorBridge} 的关系：并列，不是替代</h2>
 * <ul>
 *   <li>{@code AdvisorBridge} ⇒ <b>JEV 打分模型</b>（短周期那一半的兄弟），每个旋钮一个 score 问题；</li>
 *   <li>本类 ⇒ <b>chat 模型</b>（deepseek 等），一整段可编辑提示词 + 容错解析。</li>
 * </ul>
 * ★ 两条可以同时开：JEV 那一半管"此刻该往哪使劲"，LLM 这一半管"她该是什么脾气"。
 *
 * <h2>★ 三条铁律（与 {@code Thinker} / {@code AdvisorBridge} 完全一致）</h2>
 * <ol>
 *   <li><b>绝不阻塞游戏 tick</b>：网络在 {@link CompletableFuture} 上跑，
 *       回调<b>只写原子引用</b>（{@link State#pending}），主线程下一 tick 再取；</li>
 *   <li><b>结果必须过白名单 + 钳制</b>：走 {@link TuningBus#applyAll}（它自己会拒绝未知键、
 *       钳制越界值、并记录"被钳制"这件事）—— 模型乱写推不爆体系；</li>
 *   <li><b>失败可读</b>：{@link State#lastError} 里是翻成人话的原因（401 / 余额 / 阶段…），
 *       `/flp tune llm` 能直接看到。</li>
 * </ol>
 */
public final class LlmBridge {

    private LlmBridge() {
    }

    /** 每个女仆的 LLM 指挥状态。 */
    public static final class State {
        /** 行为统计（与 JEV 那一半独立；它反映"这段时间她打得怎么样"）。 */
        final com.touhoulittlemad.fightlikeplayer.decision.thinking.BehaviorStats stats =
                new com.touhoulittlemad.fightlikeplayer.decision.thinking.BehaviorStats();
        long lastSubmitTick = Long.MIN_VALUE;
        final AtomicLong inFlightGen = new AtomicLong(0);
        final AtomicLong nextGen = new AtomicLong(0);
        /** 回调写这里；主线程取走（★ 跨线程只传这个原子引用）。 */
        final AtomicReference<LlmAdvisor.Parsed> pending = new AtomicReference<>();
        /** ★ 指令订单（下一步的主线）。 */
        final AtomicReference<LlmAdvisor.Orders> pendingOrders = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicLong count = new AtomicLong();
        final AtomicLong applied = new AtomicLong();
        volatile String lastReply = "(还没有问过)";
        volatile String lastApplied = "（无）";
        volatile long lastLatencyMs;
        volatile String lastError = "（无）";
    }

    /**
     * 每 tick 调一次（主线程）。
     *
     * @param state    该女仆的状态
     * @param bus      该女仆自己的旋钮总线（★ 结果只写她这一条，不动别的女仆）
     * @param need     当前需求（只用于采样"她长期偏向哪一轴"）
     * @param inCombat 现在算不算在战斗（没打过架就不问，省额度）
     */
    public static void tick(State state, TuningBus bus, EntityMaid maid, NeedVector need,
                            boolean inCombat, double healthPct, long gameTime) {
        if (state == null) {
            return;
        }
        TuningBus b = bus == null ? TuningBus.global() : bus;

        // ① 采样
        state.stats.noteNeed(gameTime, need);
        state.stats.noteHealth(healthPct);

        // ② 取走上一轮的结果（★ 主线程）

        // ★★ 取走上一轮的【指令订单】并应用（十三轮，M4）
        LlmAdvisor.Orders orders = state.pendingOrders.getAndSet(null);
        if (orders != null) {
            state.lastReply = orders.summary();
            boolean apply = "apply".equalsIgnoreCase(
                    FlpConfig.get(FlpConfig.LLM_APPLY_MODE, "observe"));
            if (apply && !orders.isEmpty()) {
                // ★★ 【cancel 先于 issue】—— 否则"换指令"会先下后取。
                for (String id : orders.cancel()) {
                    // ★★ source = llm ⇒ 撤不掉"玩家/对话下达"的指令（见 DirectiveHolder#cancel）
                    String cr = com.touhoulittlemad.fightlikeplayer.compat.directive
                            .DirectiveHolder.cancel(maid, id, gameTime, "llm");
                    log("动态指挥取消 " + id + " ⇒ " + cr);
                }
                for (LlmAdvisor.Orders.Issue i : orders.issue()) {
                    com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                            .issue(maid, i.id(), i.params(), i.texts(), 0, "llm", gameTime);
                }
                state.applied.incrementAndGet();
                state.lastApplied = orders.summary();
                log("★ 已按 LLM 指令执行：" + orders.summary());
            } else {
                log("LLM 指令（" + (apply ? "无需修改" : "仅观测，未生效")
                        + "）：" + orders.summary()
                        + (apply ? "" : "　★ 想让它真的生效：llm.applyMode 设为 apply"));
            }
        }
        LlmAdvisor.Parsed parsed = state.pending.getAndSet(null);
        if (parsed != null) {
            state.lastReply = parsed.summary();
            boolean apply = "apply".equalsIgnoreCase(
                    FlpConfig.get(FlpConfig.LLM_APPLY_MODE, "observe"));
            if (apply && !parsed.isEmpty()) {
                TuningBus.ApplyResult r = b.applyAll(parsed.patch());
                state.applied.incrementAndGet();
                state.lastApplied = parsed.summary();
                log("★ 已按 LLM 建议调整：" + parsed.summary()
                        + (r.messages().isEmpty() ? "" : "　（" + String.join("；", r.messages()) + "）"));
            } else {
                log("LLM 建议（仅观测，未生效）：" + parsed.summary()
                        + (apply ? "" : "　★ 想让它真的生效：llm.applyMode 设为 apply"));
            }
        }
        String lastErr = state.error.getAndSet(null);
        if (lastErr != null) {
            state.lastError = lastErr;
            log("⚠ LLM 指挥失败：" + lastErr);
        }

        // ③ 决定要不要问下一轮
        if (!FlpConfig.get(FlpConfig.LLM_ENABLED, Boolean.FALSE)) {
            return;
        }
        if (!configured()) {
            return;                            // 没配 key/端点 ⇒ 不问
        }
        if (!inCombat && state.stats.isEmpty()) {
            return;                            // 没打过架 ⇒ 问了也白问（省额度）
        }
        int interval = FlpConfig.get(FlpConfig.LLM_INTERVAL_TICKS, 1200);
        if (state.lastSubmitTick != Long.MIN_VALUE
                && gameTime - state.lastSubmitTick < interval) {
            return;
        }
        if (state.inFlightGen.get() != 0) {
            return;                            // 在途 ⇒ 不堆积（慢变量，丢一轮无所谓）
        }

        LlmClient client;
        try {
            client = client();
        } catch (RuntimeException e) {
            state.lastError = "构造客户端失败：" + e.getMessage();
            return;
        }
        if (client == null) {
            return;
        }

        long gen = state.nextGen.incrementAndGet();
        state.inFlightGen.set(gen);
        state.lastSubmitTick = gameTime;
        state.count.incrementAndGet();
        long t0 = System.currentTimeMillis();

        final String user = LlmAdvisor.userContent(state.stats, b, state.lastReply,
                com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                        .of(maid).activeJson(gameTime),
                // ★★ 第 14 轮：把"她此刻的具体处境"一起喂进去（见 CombatSceneBuilder）
                CombatSceneBuilder.build(maid));
        final String prompt = systemPrompt();
        CompletableFuture<String> f;
        try {
            f = client.chatAsync(prompt, user);
        } catch (RuntimeException e) {
            state.inFlightGen.compareAndSet(gen, 0);
            state.lastError = String.valueOf(e);
            return;
        }
        if (f == null) {
            state.inFlightGen.compareAndSet(gen, 0);
            state.lastError = "transport 返回 null";
            return;
        }
        f.whenComplete((reply, err) -> {
            // ★★ 这一段跑在 HTTP 线程上：只允许写原子量，绝不碰游戏状态
            if (!state.inFlightGen.compareAndSet(gen, 0)) {
                return;                        // 已被更新的一轮取代 ⇒ 丢弃
            }
            state.lastLatencyMs = System.currentTimeMillis() - t0;
            if (err != null) {
                Throwable c = err.getCause() == null ? err : err.getCause();
                state.error.set(c instanceof LlmClient.LlmException le
                        ? le.getMessage() : c.toString());
                return;
            }
            if (FightLikePlayer.LOGGER.isDebugEnabled()
                    || FlpConfig.get(FlpConfig.LLM_LOG, Boolean.FALSE)) {
                FightLikePlayer.LOGGER.info("[FLP][llm] 回复（{} ms）：{}",
                        state.lastLatencyMs, reply);
            }
            // ★ 解析是纯逻辑、无副作用的 ⇒ 可以在这个线程里做（结果用原子引用交回主线程）
            // ★★ 第十三轮：LLM 的主职责是【下达指令】（docs/15）——
            //   旋钮（评分类）已由 JEV 负责（委托方 2026-10-02 的裁决）。
            state.pendingOrders.set(LlmAdvisor.parseOrders(reply));
            state.lastReply = reply == null ? "(空)" : reply.strip();
        });
    }

    /** 是否已具备"可以发请求"的最低条件。 */
    public static boolean configured() {
        return !FlpConfig.get(FlpConfig.LLM_API_KEY, "").isBlank()
                && !FlpConfig.get(FlpConfig.LLM_BASE_URL, "").isBlank();
    }

    /** 系统提示词：配置里有就用配置的，留空则用内置。 */
    public static String systemPrompt() {
        String p = FlpConfig.get(FlpConfig.LLM_SYSTEM_PROMPT, "");
        String base = (p == null || p.isBlank()) ? LlmAdvisor.DEFAULT_PROMPT : p;
        // ★★ 可用指令表由代码【自动生成】并附在提示词后面
        //   —— 这样加一条指令不用改提示词，也不会出现"提示词写了代码里没有"。
        return base + "\n\n【可用指令表】\n"
                + com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.describeForLlm();
    }

    /** 按配置构造客户端（★ 每次都用最新配置 ⇒ 界面上改完就生效，不必重启）。 */
    public static LlmClient client() {
        String key = FlpConfig.get(FlpConfig.LLM_API_KEY, "");
        if (key.isBlank()) {
            return null;
        }
        return new LlmClient(key,
                FlpConfig.get(FlpConfig.LLM_BASE_URL, LlmClient.DEFAULT_BASE_URL),
                FlpConfig.get(FlpConfig.LLM_MODEL, LlmClient.DEFAULT_MODEL),
                FlpConfig.get(FlpConfig.LLM_PROXY, ""),
                FlpConfig.get(FlpConfig.LLM_TIMEOUT_SECONDS, 40));
    }

    /** 诊断汇总（`/flp tune llm` 用）。 */
    public static String describe(State s) {
        boolean enabled = FlpConfig.get(FlpConfig.LLM_ENABLED, Boolean.FALSE);
        String mode = FlpConfig.get(FlpConfig.LLM_APPLY_MODE, "observe");
        String url = FlpConfig.get(FlpConfig.LLM_BASE_URL, LlmClient.DEFAULT_BASE_URL);
        StringBuilder sb = new StringBuilder();
        sb.append("LLM 动态指挥：开关 = ").append(enabled ? "§a开" : "§c关")
                .append("§r　去向 = ").append("apply".equalsIgnoreCase(mode)
                        ? "§e真的改旋钮§r" : "§7仅观测§r")
                .append("　间隔 = ").append(FlpConfig.get(FlpConfig.LLM_INTERVAL_TICKS, 1200))
                .append(" tick\n");
        sb.append("  端点 = ").append(LlmClient.endpointFor(url))
                .append("　模型 = ").append(FlpConfig.get(FlpConfig.LLM_MODEL, LlmClient.DEFAULT_MODEL))
                .append("　Key = ").append(LlmClient.maskKey(
                        FlpConfig.get(FlpConfig.LLM_API_KEY, ""))).append('\n');
        String prompt = FlpConfig.get(FlpConfig.LLM_SYSTEM_PROMPT, "");
        sb.append("  提示词 = ").append(prompt == null || prompt.isBlank()
                ? "§7（内置，共 " + LlmAdvisor.DEFAULT_PROMPT.length() + " 字）§r"
                : "§a自定义（" + prompt.length() + " 字）§r").append('\n');
        if (s == null) {
            sb.append("  §7（这个女仆还没进过决策循环）§r\n");
            return sb.toString();
        }
        sb.append("  已提问 ").append(s.count.get()).append(" 次")
                .append("　已生效 ").append(s.applied.get()).append(" 次")
                .append("　最近往返 ").append(s.lastLatencyMs).append(" ms\n");
        sb.append("  行为统计：").append(s.stats.summary()).append('\n');
        sb.append("  最近建议：").append(s.lastReply).append('\n');
        sb.append("  最近失败：").append(s.lastError).append('\n');
        return sb.toString();
    }

    /** 立刻问一次（跳过等待间隔）—— 给 `/flp tune llm now` 用。 */
    public static void forceNow(State s) {
        if (s != null) {
            s.lastSubmitTick = Long.MIN_VALUE;
        }
    }

    private static void log(String msg) {
        FightLikePlayer.LOGGER.info("[FLP][llm] {}", msg);
    }
}
