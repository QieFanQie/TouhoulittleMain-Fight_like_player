package com.touhoulittlemad.fightlikeplayer.compat.think;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.carrier.ContextFacts;
import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.Gait;
import com.touhoulittlemad.fightlikeplayer.decision.SpringImpact;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.JevClient;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.ThinkScene;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.Thinker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * <b>思维层 ↔ 游戏侧的桥</b> —— 把"能查到的东西"翻译成 {@link ThinkScene}，
 * 再把判定的结果<b>作为一个影响器发布到弹簧空间</b>。
 *
 * <h2>★ 本类只做三件事</h2>
 * <ol>
 *   <li><b>翻译</b>：{@code EntityMaid + ContextFacts + 候选集} → {@link ThinkScene}（纯数据）；</li>
 *   <li><b>驱动</b>：每 tick 调 {@link Thinker#tick}（立刻返回），并<b>取走</b>上一包结果；</li>
 *   <li><b>发布</b>：{@code SpringImpact.bias(vec, "think:jev", label)} —— ★ <b>与 {@code /flp bias} 命令完全同一个接口</b>。</li>
 * </ol>
 *
 * <h2>★★ 为什么"翻译"要单独隔出来</h2>
 * 因为它是<b>唯一可能说谎的地方</b>：模型只能看到我们喂给它的东西。
 * 若翻译层漏了"主人快死了"，JEV 再准也不可能判断出该去救主人。
 * ⇒ 因此 {@link ThinkScene} 是纯 record、{@link ThinkScene#toState()} 是纯函数，
 * 离线自测可以逐字段断言"喂进去的到底是什么"。
 *
 * <h2>★ 客户端缓存的失效判定</h2>
 * {@link JevClient} 一旦构造就固定了端点/密钥/代理，而这些<b>能在游戏里改</b>
 * （模组配置界面）⇒ 用一个"签名"比较：签名变了就重建客户端。
 * 否则用户改完 key 以为生效了，实际还在用旧的 —— 这种静默不生效最难查。
 *
 * @see Thinker
 * @see <a href="../../../../../../docs/11-感知层与思维层设计.md">docs/11 §5.1</a>
 */
public final class ThinkBridge {

    private ThinkBridge() {
    }

    /** 影响器的来源标签（日志里认它）。 */
    public static final String SOURCE = "think:jev";

    /** 已构造的客户端 + 它的配置签名（签名变了就重建）。 */
    private static JevClient client;
    private static String clientSignature = "";

    /** 传给 Thinker 的 Transport —— 直接转发到 {@link JevClient#judgeAsync}。 */
    public static Thinker.Transport transport() {
        return (state, questions) -> {
            JevClient c = client();
            if (c == null) {
                // ★ 未配置 ⇒ 返回一个"已失败"的 future，而不是抛异常：
                //   Thinker 会把它计成一次失败并记录原因（可见），且不影响决策。
                java.util.concurrent.CompletableFuture<Map<String, Object>> f =
                        new java.util.concurrent.CompletableFuture<>();
                f.completeExceptionally(new JevClient.JevException(0,
                        "思维层未配置（开关关闭或 API Key 为空）—— 见 模组配置界面 → Config",
                        null, null));
                return f;
            }
            return c.judgeAsync(state, questions);
        };
    }

    /**
     * 取（必要时重建）JevClient。
     *
     * <p>★ 只在服务端主线程调用 ⇒ 不需要额外加锁；{@code client} 用 volatile 是为了让
     * 其它读取者（如命令线程）看到完整对象。
     */
    private static JevClient client() {
        if (!FlpConfig.thinkConfigured()) {
            return null;
        }
        String key = FlpConfig.get(FlpConfig.THINK_API_KEY, "");
        String url = FlpConfig.get(FlpConfig.THINK_BASE_URL, JevClient.DEFAULT_BASE_URL);
        String model = FlpConfig.get(FlpConfig.THINK_MODEL, JevClient.DEFAULT_MODEL);
        String proxy = FlpConfig.get(FlpConfig.THINK_PROXY, "");
        int timeout = FlpConfig.get(FlpConfig.THINK_TIMEOUT_SECONDS, 30);
        String sig = url + "|" + model + "|" + proxy + "|" + timeout + "|" + key.hashCode();
        if (client != null && sig.equals(clientSignature)) {
            return client;
        }
        try {
            client = new JevClient(key, url, model, proxy, timeout);
            clientSignature = sig;
            FightLikePlayer.LOGGER.info("[FLP][think] 已（重新）构造 JEV 客户端：{} model={} key={}",
                    client.baseUrl(), client.model(), FlpConfig.maskedKey());
        } catch (RuntimeException e) {
            client = null;
            FightLikePlayer.LOGGER.warn("[FLP][think] 构造 JEV 客户端失败：{}", e.toString());
        }
        return client;
    }

    /** 由配置生成 Thinker 的运行参数。 */
    public static Thinker.Settings settings() {
        boolean enabled = FlpConfig.thinkConfigured();
        int interval = FlpConfig.get(FlpConfig.THINK_INTERVAL_TICKS, 30);
        double strength = FlpConfig.get(FlpConfig.THINK_BIAS_STRENGTH, 0.35);
        double minConf = FlpConfig.get(FlpConfig.THINK_MIN_CONFIDENCE, 0.0);
        boolean observe = !"bias".equalsIgnoreCase(
                FlpConfig.get(FlpConfig.THINK_APPLY_MODE, "bias"));
        // 超时换算成 tick：配置给的是秒，而节流用的是 tick（20 tick = 1 秒）
        int timeoutTicks = FlpConfig.get(FlpConfig.THINK_TIMEOUT_SECONDS, 30) * 20 + 40;
        // ★ 允许缺 1 条轴（模型偶尔会漏问一条）；缺 2 条以上就整包丢弃。
        return new Thinker.Settings(enabled, Math.max(1, interval), strength,
                minConf, observe, timeoutTicks, 1);
    }

    /**
     * ★★ <b>提示词覆写</b>（配置界面可编辑）—— 每 tick 读一次，但<b>只在文本真的变了才重新解析</b>。
     *
     * <p>★ 为什么要缓存：{@code parse} 要切行、要去重、要校验键 —— 每 tick × 每个女仆做一遍
     * 纯属浪费（配置可能几小时不变）。这里的判据是"文本没变 ⇒ 用上次的结果"。
     *
     * <p>★ 为什么要"每次读配置"而不是"启动时读一次"：委托方要求的是
     * <b>在游戏里改完就能生效</b>，不该要求重启。
     */
    public static Map<String, String> questionOverrides() {
        String text = FlpConfig.get(FlpConfig.THINK_QUESTION_OVERRIDES, "");
        if (text == null) {
            text = "";
        }
        if (!text.equals(cachedOverrideText)) {
            cachedOverrideText = text;
            var parsed = com.touhoulittlemad.fightlikeplayer.decision.thinking.PromptOverrides
                    .parseForThink(text);
            cachedOverrides = parsed.overrides();
            if (!parsed.isEmpty() || !parsed.ignoredKeys().isEmpty()) {
                log("提示词覆写已更新：" + parsed.summary());
            }
        }
        return cachedOverrides;
    }

    /** 覆写文本的缓存（判据是"文本变了没"）。 */
    private static String cachedOverrideText;

    /** 解析结果的缓存。 */
    private static Map<String, String> cachedOverrides = Map.of();

    /**
     * 每 tick 调一次：<b>先取走上一包结果并发布影响器，再决定要不要问下一包</b>。
     *
     * <p>★ 顺序很重要：<b>先把结果推进弹簧，再去问</b> ——
     * 否则刚拿到的判定要等到下一次 tick 才生效，白慢一拍。
     *
     * @param thinker    该女仆的 Thinker
     * @param maid       女仆
     * @param facts      本 tick 已翻译好的事实（★ 复用，不重新枚举）
     * @param candidates 她此刻能做的动作（主循环候选集）
     * @param lastAction 最近一次提交的动作 id
     * @param gait       当前步法
     * @param publish    把影响器发布到弹簧空间（由调用方提供，因为空间在 {@code Loops} 里）
     */
    public static void tick(Thinker thinker, EntityMaid maid, ContextFacts facts,
                            List<String> candidates, String lastAction, Gait gait,
                            long gameTime, BiConsumer<SpringImpact, Thinker.Outcome> publish) {
        if (thinker == null) {
            return;
        }
        thinker.setSettings(settings());
        thinker.setQuestionOverrides(questionOverrides());

        // ── ① 取走上一包（★ 主线程；Thinker 保证结果只由原子引用跨线程传递）──
        Thinker.Outcome o = thinker.poll();
        if (o != null) {
            log("判定到达（%d ms，%s）：%s".formatted(o.latencyMs(), o.applied() ? "已施加" : "仅观测",
                    o.verdict().summary()));
            if (o.applied()) {
                // ★★ 与 /flp bias 完全同一个接口 —— 这正是"发布函数"机制当初改出来的目的
                publish.accept(SpringImpact.bias(o.bias(), SOURCE, o.label()), o);
            }
        }
        String err = thinker.pollError();
        if (err != null) {
            // ★ 失败必须【用户可读】（docs/13 第 19 条）—— 不是只写 debug 日志
            log("⚠ 判定失败：" + err);
        }
        String drop = thinker.pollDrop();
        if (drop != null) {
            log("⚠ 判定作废：" + drop);
        }

        // ── ② 决定要不要问下一包 ──
        boolean onlyInCombat = FlpConfig.get(FlpConfig.THINK_ONLY_IN_COMBAT, Boolean.TRUE);
        if (onlyInCombat && !facts.hasTarget() && facts.vitals().nearbyEnemies() <= 0) {
            return;                      // 无目标无敌人 ⇒ 不值得花额度
        }
        thinker.tick(gameTime, scene(maid, facts, candidates, lastAction, gait));
    }

    // ───────────────────────── 翻译 ─────────────────────────

    /** ★★ 把游戏状态翻译成纯数据快照（本项目的"唯一可能说谎的地方"，故单独成方法便于断言）。 */
    public static ThinkScene scene(EntityMaid maid, ContextFacts facts,
                                   List<String> candidates, String lastAction, Gait gait) {
        var v = facts.vitals();
        double dist = v.distanceToTarget();
        // ★ 无目标时 Vitals 给的是 Double.MAX_VALUE ⇒ 转成 -1（"距离无效"），
        //   否则模型会看到一个 1.8e308 这种荒谬的数。
        double nearest = (!facts.hasTarget() || dist >= 1.0e6) ? -1.0 : dist;

        return new ThinkScene(
                taskId(maid),
                clamp01(v.selfHealthPct()),
                clamp01(v.ownerHealthPct()),
                maid.getOwner() != null,
                Math.max(0, v.nearbyEnemies()),
                nearest,
                facts.hasTarget(),
                facts.targetInMeleeRange(),
                false,                       // hurtRecently：由 SpringDynamics 的冲量体现，这里不重复喂
                facts.servantCount(),
                itemId(maid.getMainHandItem()),
                itemId(maid.getOffhandItem()),
                gait == null ? "(none)" : gait.direction().name().toLowerCase(java.util.Locale.ROOT),
                lastAction == null ? "(none)" : lastAction,
                cap(candidates));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 截断 + 去重（按"族前缀"去重，避免 14 条刀技把提示词塞满）。 */
    private static List<String> cap(List<String> candidates) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> families = new java.util.HashSet<>();
        if (candidates != null) {
            for (String id : candidates) {
                int c = id.indexOf(':');
                String fam = c < 0 ? id : id.substring(0, c + 1) + id.substring(c + 1).split("_")[0];
                if (families.add(fam)) {
                    out.add(id);
                }
                if (out.size() >= ThinkScene.MAX_ACTIONS) {
                    break;
                }
            }
            // ★ 去重之后若还有余量，把剩下的补齐（让模型看到更全的选项）
            for (String id : candidates) {
                if (out.size() >= ThinkScene.MAX_ACTIONS) {
                    break;
                }
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static String taskId(EntityMaid maid) {
        try {
            var t = maid.getTask();
            return t == null || t.getUid() == null ? "(none)" : t.getUid().toString();
        } catch (RuntimeException e) {
            return "(unknown)";
        }
    }

    private static String itemId(net.minecraft.world.item.ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "(empty)";
        }
        var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        return k == null ? s.getItem().toString() : k.toString();
    }

    private static void log(String msg) {
        if (FlpConfig.get(FlpConfig.THINK_LOG_DECISIONS, Boolean.TRUE)) {
            // ★ info 级而不是 debug：委托方要能【在日志里看见】思维层到底做了什么/为什么没做
            FightLikePlayer.LOGGER.info("[FLP][think] {}", msg);
        }
    }

    /** 强制作废当前客户端缓存（配置改完之后想立刻生效时用）。 */
    public static void invalidateClient() {
        client = null;
        clientSignature = "";
    }

    /**
     * ★ 给调控顾问用的客户端 —— <b>与短周期判定共用同一个实例</b>。
     *
     * <p>为什么共用：同一份 Key/端点/代理配置 ⇒ 各建一个客户端只会让"改了 Key 但只有一个生效"
     * 这种静默不一致出现。顾问需要的额外东西（更慢的节奏）由它自己的间隔控制。
     */
    public static JevClient clientForAdvisor() {
        return client();
    }
}
