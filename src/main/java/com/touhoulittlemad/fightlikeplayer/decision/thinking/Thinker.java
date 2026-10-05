package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>短周期思维层</b> —— 把「问 JEV」变成一件<b>绝不阻塞决策</b>的事。
 *
 * <h2>★★ 线程模型（本类最重要的部分，写错了会崩服）</h2>
 * <pre>
 *   服务端主线程                         HTTP 线程（JevClient 的 sendAsync 回调）
 *   ─────────────                        ─────────────────────────────────────
 *   tick(gameTime, scene)
 *     ├ 该问吗？（开关/间隔/在途/超时）
 *     ├ transport.judgeAsync(...) ──────▶  发请求…
 *     │   （立刻返回，不等待）                │
 *     └ return  ← tick 到此结束              └ 完成 → 只写 AtomicReference + AtomicLong
 *
 *   （下一个 tick）poll()  ← 主线程取走结果 → 发布 SpringImpact.bias
 * </pre>
 * <b>铁律：HTTP 回调里【只允许】写原子引用与计数器。</b>
 * 绝不能在回调里碰 {@code EntityMaid}、{@code SpringSpace}、日志的 Minecraft 上下文 ——
 * 那些对象不是线程安全的。<b>结果只能由主线程 {@link #poll()} 取走并施加</b>。
 *
 * <h2>★★ 为什么"慢"是特性而不是缺陷</h2>
 * 决策周期是 5 tick（0.25s），而 JEV 一次往返约 0.3~1.5 秒 ⇒ 思维层<b>天生比决策慢 4~6 倍</b>。
 * ⇒ 它只能当<b>慢变量</b>用：偶尔推弹簧一把，然后让弹簧自己衰减。
 * 若每个 tick 都问一次，既没有意义（局势没变）又白烧额度。
 *
 * <h2>★ 失败绝不传染</h2>
 * 任何失败（未配置 / 网络错 / 超时 / 置信度太低 / 缺轴）都只是<b>这一包作废</b>：
 * 不发布影响器，弹簧沿用上一次的状态。<b>关掉思维层时行为不能变坏</b>
 * （docs/11 §7 的验收原则：思维层是"增强"而非"必需"）。
 *
 * <h2>★ 可替换的 {@link Transport}</h2>
 * 生产用 {@link JevClient#judgeAsync}；离线自测用假的 Transport
 * ⇒ <b>"提交节流 / 在途去重 / 超时重试 / 失败不传染"这些机械行为全部可以断言</b>，
 * 不需要联网（这正是不让"不可离线验证的外部模型"污染架构验证的做法，docs/11 §6）。
 *
 * @see ThinkPrompt
 * @see JevClient
 */
public final class Thinker {

    /** 与外部世界打交道的那一层（可替换）。 */
    public interface Transport {
        CompletableFuture<Map<String, Object>> judge(Object state, Map<String, Object> questions);
    }

    /**
     * 一次运行的参数（由调用方从配置读出来 —— ★ 纯逻辑层不依赖 Forge）。
     *
     * @param enabled        总开关
     * @param intervalTicks  提交间隔（tick）
     * @param strength       偏置强度（JEV 的 [-1,1] 乘上它）
     * @param minConfidence  置信度门槛（低于它整包丢弃）
     * @param observeOnly    true = 只记录不推弹簧
     * @param timeoutTicks   在途超过这么多 tick 即视为超时（允许重新提交）
     * @param maxMissingAxes 允许缺失的轴数上限（超过就整包丢弃；0 = 缺一条就不用）
     */
    public record Settings(boolean enabled, int intervalTicks, double strength,
                           double minConfidence, boolean observeOnly, int timeoutTicks,
                           int maxMissingAxes) {

        public static Settings disabled() {
            return new Settings(false, 30, 0.35, 0.0, false, 600, 0);
        }
    }

    /** 一次成功的判定。 */
    public record Outcome(NeedVector bias, ThinkPrompt.Verdict verdict, ThinkScene scene,
                          String label, Map<String, Object> response, long latencyMs,
                          boolean applied) {
    }

    /** 诊断计数（`/flp think` 用）。 */
    public record Stats(long submitted, long succeeded, long failed, long dropped,
                        long staleDiscarded, double lastLatencyMs,
                        String lastError, String lastDropReason) {
    }

    private final Transport transport;

    // ★ 跨线程只用这几样东西：原子引用 + 原子计数
    private final AtomicReference<Outcome> completed = new AtomicReference<>();
    private final AtomicReference<String> failed = new AtomicReference<>();
    private final AtomicReference<String> dropped = new AtomicReference<>();
    /**
     * ★★ <b>在途"代次"</b>（0 = 无在途）—— 而不是一个简单的计数器。
     *
     * <p>为什么必须用代次：旧请求超时被放弃后，它的回调<b>可能随后才到达</b>。
     * 若用计数器，那个迟到的回调会把"新一代正在途"的状态清成 0
     * ⇒ 主线程以为没在途，于是并发发出第二个请求 ⇒ 在途数失控。
     * 用代次 + {@code compareAndSet(myGen, 0)} ⇒ <b>迟到的旧结果只能被丢弃，无法影响新一代</b>。
     */
    private final AtomicLong inFlightGen = new AtomicLong(0);
    private final AtomicLong nextGen = new AtomicLong(0);
    private final AtomicLong statStale = new AtomicLong();
    private final AtomicLong statSubmitted = new AtomicLong();
    private final AtomicLong statSucceeded = new AtomicLong();
    private final AtomicLong statFailed = new AtomicLong();
    private final AtomicLong statDropped = new AtomicLong();
    private final AtomicLong lastLatencyMs = new AtomicLong();
    private final AtomicLong lastSubmitMs = new AtomicLong(Long.MIN_VALUE);

    // ★ 只在主线程读写
    private long lastSubmitTick = Long.MIN_VALUE;
    private long inFlightSinceTick = Long.MIN_VALUE;
    private ThinkScene inFlightScene;
    private String lastLabel;
    private volatile Settings settings = Settings.disabled();

    /**
     * ★★ <b>提示词覆写</b>（轴 id → 问法）—— 由 {@code ThinkBridge} 每次从配置读进来
     * （配置界面可编辑，见 {@code FlpConfig#THINK_QUESTION_OVERRIDES}）。
     *
     * <p>为什么放在这里而不是让本类去读配置：本包是<b>纯逻辑层</b>
     * （{@code check_isolation.py} 断言它 0 引用 {@code net.minecraft}），
     * 而 Forge 配置类必然引用 Forge ⇒ 配置的读取只能在 compat 层做，
     * 这里只收<b>已经解析好的普通 Map</b>。
     */
    private volatile Map<String, String> questionOverrides = Map.of();

    public Thinker(Transport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport 不能为空");
        }
        this.transport = transport;
    }

    public void setSettings(Settings s) {
        this.settings = s == null ? Settings.disabled() : s;
    }

    /** 设置提示词覆写（{@code null} = 用内置问法）。 */
    public void setQuestionOverrides(Map<String, String> overrides) {
        this.questionOverrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /** 当前生效的覆写条数（诊断用）。 */
    public int questionOverrideCount() {
        return questionOverrides.size();
    }

    public Settings settings() {
        return settings;
    }

    /** 是否有请求在途。 */
    public boolean inFlight() {
        return inFlightGen.get() != 0;
    }

    // ───────────────────────── 主线程：每 tick 调一次 ─────────────────────────

    /**
     * 该问就问，<b>立刻返回</b>。
     *
     * @param gameTime 世界时间（tick）
     * @param scene    局势快照；{@code null} = 本 tick 不问
     */
    public void tick(long gameTime, ThinkScene scene) {
        Settings s = settings;
        if (!s.enabled() || scene == null) {
            return;
        }
        // 在途：只有"超时"才允许重新提交（否则同一个女仆会堆积请求）
        if (inFlightGen.get() != 0) {
            boolean stale = inFlightSinceTick != Long.MIN_VALUE
                    && gameTime - inFlightSinceTick >= s.timeoutTicks();
            if (!stale) {
                return;
            }
            statDropped.incrementAndGet();
            dropped.set("在途超时（" + (gameTime - inFlightSinceTick) + " tick）⇒ 放弃旧包，重新提交");
            inFlightGen.set(0);          // ★ 换代号：迟到的旧回调再也改不动在途状态
        }
        // 间隔节流（★ 第一次立刻就問，不必等一个间隔）
        if (lastSubmitTick != Long.MIN_VALUE && gameTime - lastSubmitTick < s.intervalTicks()) {
            return;
        }

        final long myGen = nextGen.incrementAndGet();
        inFlightScene = scene;
        lastSubmitTick = gameTime;
        inFlightSinceTick = gameTime;
        inFlightGen.set(myGen);
        statSubmitted.incrementAndGet();
        lastSubmitMs.set(System.currentTimeMillis());

        final long submittedAt = System.currentTimeMillis();
        final double strength = s.strength();
        final double minConf = s.minConfidence();
        final int maxMissing = s.maxMissingAxes();
        final boolean observeOnly = s.observeOnly();
        final ThinkScene sceneForResult = scene;

        CompletableFuture<Map<String, Object>> f;
        try {
            f = transport.judge(scene.toState(), ThinkPrompt.questions(questionOverrides));
        } catch (RuntimeException e) {
            // 提交阶段就炸了（本地预校验 / 线程池拒绝）：当作失败，不传染
            onFailure(myGen, e, submittedAt);
            return;
        }
        if (f == null) {
            onFailure(myGen, new IllegalStateException("transport 返回 null"), submittedAt);
            return;
        }

        f.whenComplete((resp, err) -> {
            // ★★ 这里在 HTTP 线程上 —— 只允许写原子量，绝不碰游戏状态。
            //    先抢代次：抢不到说明这一包已被判超时（或已有更新的一包），直接丢弃。
            if (!inFlightGen.compareAndSet(myGen, 0)) {
                statStale.incrementAndGet();
                return;
            }
            try {
                if (err != null) {
                    Throwable cause = err.getCause() == null ? err : err.getCause();
                    failed.set(cause instanceof JevClient.JevException je
                            ? explain(je) : String.valueOf(cause));
                    statFailed.incrementAndGet();
                    return;
                }
                ThinkPrompt.Verdict v = ThinkPrompt.verdict(resp, strength);
                if (!v.missing().isEmpty() && v.missing().size() > maxMissing) {
                    dropped.set("缺 " + v.missing().size() + " 条轴的答案（上限 " + maxMissing + "）");
                    statDropped.incrementAndGet();
                    return;
                }
                double minC = v.minConfidence();
                if (minC < minConf) {
                    dropped.set(String.format(java.util.Locale.ROOT,
                            "置信度 %.2f < 门槛 %.2f", minC, minConf));
                    statDropped.incrementAndGet();
                    return;
                }
                long latency = System.currentTimeMillis() - submittedAt;
                lastLatencyMs.set(latency);
                statSucceeded.incrementAndGet();
                completed.set(new Outcome(v.vector(), v, sceneForResult,
                        labelOf(sceneForResult, v), resp, latency, !observeOnly));
            } catch (RuntimeException e) {
                failed.set("解析返回失败：" + e);
                statFailed.incrementAndGet();
            }
        });
    }

    private void onFailure(long myGen, Throwable e, long submittedAt) {
        inFlightGen.compareAndSet(myGen, 0);
        failed.set(e instanceof JevClient.JevException je ? explain(je) : String.valueOf(e));
        statFailed.incrementAndGet();
        inFlightSinceTick = Long.MIN_VALUE;
        lastLatencyMs.set(System.currentTimeMillis() - submittedAt);
    }

    /** ★ 把「无可用渠道」翻译成人话（它是请求体错误的伪装，见 {@link JevClient}）。 */
    static String explain(JevClient.JevException je) {
        if (je.looksLikeFakeChannelError()) {
            return "HTTP " + je.status() + " 「无可用渠道」—— ★ 这通常是【请求体不合法】的伪装，"
                    + "不是分组/余额/密钥问题：" + je.getMessage();
        }
        return "HTTP " + je.status() + " " + je.getMessage();
    }

    private static String labelOf(ThinkScene scene, ThinkPrompt.Verdict v) {
        return String.format(java.util.Locale.ROOT,
                "JEV（敌%d·血%.0f%%·%s）", scene.enemyCount(), scene.selfHealthPct() * 100,
                v.summary());
    }

    // ───────────────────────── 主线程：取走结果 ─────────────────────────

    /**
     * 取走一个已完成的判定（没有则返回 {@code null}）。
     *
     * <p>★ 必须由主线程调用；调用方拿到后负责发布 {@code SpringImpact.bias}。
     */
    public Outcome poll() {
        Outcome o = completed.getAndSet(null);
        if (o != null) {
            lastLabel = o.label();
        }
        return o;
    }

    /** 取走一条失败信息（没有则 {@code null}）。 */
    public String pollError() {
        return failed.getAndSet(null);
    }

    /** 取走一条丢弃原因（没有则 {@code null}）。 */
    public String pollDrop() {
        return dropped.getAndSet(null);
    }

    /** 最近一次成功判定的标签。 */
    public String lastLabel() {
        return lastLabel;
    }

    public Stats stats() {
        return new Stats(statSubmitted.get(), statSucceeded.get(), statFailed.get(),
                statDropped.get(), statStale.get(), lastLatencyMs.get(),
                failed.get(), dropped.get());
    }

    /** 把开关关掉并清空在途状态（脱战 / 手动关闭）。 */
    public void reset() {
        inFlightGen.set(0);              // ★ 换代号 ⇒ 迟到的回调只会计入 stale
        inFlightSinceTick = Long.MIN_VALUE;
        inFlightScene = null;
        lastSubmitTick = Long.MIN_VALUE;
        completed.set(null);
        failed.set(null);
        dropped.set(null);
    }

    /** 在途的那个快照（诊断用）。 */
    public ThinkScene inFlightScene() {
        return inFlightScene;
    }
}
