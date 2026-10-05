package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>指令总线</b>（每女仆一份）—— 计划文档 [docs/15](../docs/15-LLM指令系统计划.md) 的 M1。
 *
 * <h2>它在体系里的位置（第 5 层，独立于既有通道）</h2>
 * <pre>
 *   LLM / 玩家 / 女仆对话
 *         │  下达 / 取消
 *         ▼
 *   DirectiveBus（本类）· 瞬间队列 + 持续集合（带 TTL）
 *         │  查询（每 tick）
 *         ▼
 *   ① 候选集过滤  ② 执行器前置  ③ 步法约束  ④ 法术/聚晶挑选  ⑤ 瞬间动作直调
 *         ▼
 *   决策层（弹簧 + 最近邻）—— 【不感知指令的存在】，它只在一个变小的候选集里选
 * </pre>
 *
 * <h2>★ 三条设计纪律（每一条都来自本项目踩过的坑）</h2>
 * <ol>
 *   <li><b>TTL 必须有上限</b>：持续指令默认 {@code spec.defTtlTicks}，钳到
 *       {@link DirectiveSpec#MAX_TTL_TICKS} ⇒ "永不取消的指令"不会变成第二个永久卡死
 *       （docs/13 §26：逐条堵漏永远堵不完，所以"自动过期"是必须的）；</li>
 *   <li><b>未知 id 明确丢弃并登记</b>（不静默）：LLM 编造指令名时看得见；</li>
 *   <li><b>同一互斥组自动让位</b>：下达 {@code magic_only} 会自动取消
 *       {@code melee_only}/{@code ranged_only}（{@code DirectiveSpec.mutexGroup}）；
 *       否则会同时"只许魔法"和"只许近战" ⇒ 候选集空 ⇒ 她什么都不做。</li>
 * </ol>
 *
 * <p>★ 本类**纯逻辑**（无 MC 类型）⇒ `DirectiveSelfTest` 可离线断言全部规则。
 */
public final class DirectiveBus {

    /** 一条"正在生效的持续指令"。 */
    public record Active(String id, Map<String, Double> params, Map<String, String> texts,
                         long untilTick, String source) {

        public String describe(long now) {
            StringBuilder sb = new StringBuilder(id);
            if (params != null && !params.isEmpty()) {
                sb.append('(');
                boolean f = true;
                for (Map.Entry<String, Double> e : params.entrySet()) {
                    if (!f) {
                        sb.append(',');
                    }
                    f = false;
                    sb.append(e.getKey()).append('=').append(fmt(e.getValue()));
                }
                if (texts != null) {
                    for (Map.Entry<String, String> e : texts.entrySet()) {
                        if (!f) {
                            sb.append(',');
                        }
                        f = false;
                        sb.append(e.getKey()).append('=').append(e.getValue());
                    }
                }
                sb.append(')');
            }
            long left = Math.max(0, untilTick - now);
            sb.append("　剩余 ").append(left / 20).append(" s");
            if (source != null && !source.isBlank()) {
                sb.append("　来源 ").append(source);
            }
            return sb.toString();
        }
    }

    /** 一次下达的结果（用于可读反馈）。 */
    public record IssueResult(boolean ok, String message, List<String> autoCancelled) {
    }

    /** 持续指令：id → 生效状态。 */
    private final Map<String, Active> sustained = new LinkedHashMap<>();
    /** 瞬间指令队列（下达即入队；由游戏侧每 tick 取走执行）。 */
    private final Deque<Active> instantQueue = new ArrayDeque<>();
    /** 最近一次"被丢弃的未知 id"（诊断出口）。 */
    private final List<String> lastRejected = new ArrayList<>();
    /** 最近一次自动取消的（互斥）指令，用于日志。 */
    private final List<String> lastAutoCancelled = new ArrayList<>();
    /** ★ 最近一次因放太久而作废的**瞬间**指令（见 {@link #INSTANT_MAX_AGE_TICKS}）。 */
    private final List<String> lastStaleInstant = new ArrayList<>();

    // ───────────────────────── 下达 / 取消 ─────────────────────────

    /**
     * 下达一条指令。
     *
     * @param id      指令 id（必须 {@link DirectiveSpec#exists}）
     * @param params  参数（可为 null；越界/未知参数会被钳制或丢弃并登记）
     * @param texts   文本参数（例如 {@code ban_focus} 的 label）
     * @param ttlTicks 持续指令的存活 tick（{@code <= 0} ⇒ 用规格默认值；一律钳到上限）
     * @param source  来源（"llm" / "player" / "chat"）
     * @param now     当前 tick
     */
    public IssueResult issue(String id, Map<String, Double> params, Map<String, String> texts,
                             int ttlTicks, String source, long now) {
        DirectiveSpec.Spec spec = DirectiveSpec.find(id);
        if (spec == null) {
            lastRejected.add(id == null ? "(null)" : id);
            return new IssueResult(false,
                    "未知指令 " + id + "（已丢弃；可用指令见 /flp directive list）", List.of());
        }
        Map<String, Double> clean = new LinkedHashMap<>();
        List<String> droppedParams = new ArrayList<>();
        if (params != null) {
            for (Map.Entry<String, Double> e : params.entrySet()) {
                DirectiveSpec.ParamSpec ps = spec.params() == null ? null : spec.params().get(e.getKey());
                if (ps == null) {
                    droppedParams.add(e.getKey());
                    continue;
                }
                clean.put(e.getKey(), ps.clamp(e.getValue() == null ? ps.def() : e.getValue()));
            }
        }
        // 缺的参数补默认值（这样查询侧不必再判空）
        if (spec.params() != null) {
            for (Map.Entry<String, DirectiveSpec.ParamSpec> e : spec.params().entrySet()) {
                if (!e.getValue().text() && !clean.containsKey(e.getKey())) {
                    clean.put(e.getKey(), e.getValue().def());
                }
            }
        }
        Map<String, String> cleanTexts = new LinkedHashMap<>();
        if (texts != null) {
            for (Map.Entry<String, String> e : texts.entrySet()) {
                DirectiveSpec.ParamSpec ps = spec.params() == null ? null : spec.params().get(e.getKey());
                if (ps != null && ps.text() && e.getValue() != null && !e.getValue().isBlank()) {
                    cleanTexts.put(e.getKey(), e.getValue().trim());
                } else {
                    droppedParams.add(e.getKey());
                }
            }
        }

        // 互斥：同组的其它持续指令自动让位
        List<String> autoCancelled = new ArrayList<>();
        if (spec.kind() == DirectiveSpec.Kind.SUSTAINED) {
            for (String other : DirectiveSpec.sameMutexGroup(id)) {
                if (sustained.remove(other) != null) {
                    autoCancelled.add(other);
                }
            }
            lastAutoCancelled.clear();
            lastAutoCancelled.addAll(autoCancelled);

            int ttl = ttlTicks > 0 ? ttlTicks : spec.defTtlTicks();
            ttl = Math.max(20, Math.min(DirectiveSpec.MAX_TTL_TICKS, ttl));
            sustained.put(id, new Active(id, Map.copyOf(clean), Map.copyOf(cleanTexts),
                    now + ttl, source));
        } else {
            instantQueue.addLast(new Active(id, Map.copyOf(clean), Map.copyOf(cleanTexts),
                    now, source));
        }
        String msg = (spec.kind() == DirectiveSpec.Kind.INSTANT ? "已下达瞬间指令 " : "已下达持续指令 ")
                + id + "（" + spec.zh() + "）";
        // ★★ 第十七轮续：**把参数原样回显**。
        //   原来这句话只说"已下达 only_item（只用某件物品）"，**不写它要的是哪一件**
        //   ⇒ 委托方（和我）都无法从日志判断"模型到底发了什么"（`tacz:ak47`？`rifle`？空？），
        //     而"不管让她用什么她都用火箭筒"的第一现场就在这句话里。
        //   ★ 这与 docs/13 第 19 条同源：**凡可预期的失败都必须有一条用户可读的出口** ——
        //     参数回显让"模型发的"与"她实际做的"可以直接对账。
        if (!cleanTexts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cleanTexts.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            msg += "　参数：" + sb;
        }
        if (!clean.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Double> e : clean.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            msg += (cleanTexts.isEmpty() ? "　参数：" : " ") + sb;
        }
        if (!autoCancelled.isEmpty()) {
            msg += "　★ 自动取消了同组指令：" + String.join(",", autoCancelled);
            // ★★ 第二十一轮：把"同一次回话里下了两条互斥指令"这件事说透。
            //   实测（2026-10-05 日志）：主人说"只用铁魔法"，模型**在同一次回话里**
            //   先下 irons_only、再下 goety_only ⇒ 互斥规则让**后一条顶掉前一条**
            //   ⇒ 她用了**主人没要的那一系**。模型只能靠这句回话发现自己下错了。
            msg += "　⚠ 同一互斥组只能有一条生效 —— 如果你刚才是想问「哪一系魔法」，"
                    + "请只保留主人真正要的那一条（" + id + "），并取消/别再下另一条。";
        }
        if (!droppedParams.isEmpty()) {
            msg += "　⚠ 丢弃了无法识别的参数：" + String.join(",", droppedParams);
        }
        return new IssueResult(true, msg, List.copyOf(autoCancelled));
    }

    /** 取消一条持续指令（返回是否真的取消了）。 */
    public boolean cancel(String id) {
        return sustained.remove(id) != null;
    }

    /** 取消全部（脱战/卸载/玩家喊停时用）。 */
    public List<String> cancelAll() {
        List<String> ids = new ArrayList<>(sustained.keySet());
        sustained.clear();
        instantQueue.clear();
        return ids;
    }

    /**
     * ★★ <b>瞬间指令的保鲜期</b>（tick）：3 秒内没被执行就作废。
     *
     * <p>★ 为什么必须有（第 14 轮发现的坑）：瞬间指令的执行点在
     * {@code PlayerLikeCombat.tick} 的最前面，而**那个行为只在"她有攻击目标"时才运行**
     * （脑行为的门控）。⇒ 玩家在她没在打架时说「举盾」，
     * 这条指令会**一直躺在队列里**，直到她某次进入战斗才突然执行 ——
     * 那时它已经与玩家的意图无关了，观感上就是"两分钟前说的话突然生效"。
     *
     * <p>★ 语义上"瞬间"= "立刻"：**晚 3 秒就不叫立刻了**。
     * ⇒ 过期即作废，并记进 {@link #lastStaleInstant()}（可读出口，不静默）。
     */
    public static final int INSTANT_MAX_AGE_TICKS = 60;

    /** 每 tick 推进：清掉过期的持续指令。返回**本次刚过期**的 id（可读出口）。 */
    public List<String> tick(long now) {
        List<String> expired = new ArrayList<>();
        var it = sustained.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now >= e.getValue().untilTick()) {
                expired.add(e.getKey());
                it.remove();
            }
        }
        // ★ 瞬间指令的保鲜期（见 INSTANT_MAX_AGE_TICKS 的说明）
        lastStaleInstant.clear();
        var iq = instantQueue.iterator();
        while (iq.hasNext()) {
            Active a = iq.next();
            if (now - a.untilTick() > INSTANT_MAX_AGE_TICKS) {
                lastStaleInstant.add(a.id());
                iq.remove();
            }
        }
        return expired;
    }

    /** 最近一次因"放太久没人执行"而作废的瞬间指令 id（诊断出口）。 */
    public List<String> lastStaleInstant() {
        return List.copyOf(lastStaleInstant);
    }

    /** 取走一条瞬间指令（没有则 null）。 */
    public Active pollInstant() {
        return instantQueue.pollFirst();
    }

    // ───────────────────────── 查询（五个落点用）─────────────────────────

    public boolean has(String id) {
        return sustained.containsKey(id);
    }

    /** 参数（没有该指令 ⇒ null；没有该参数 ⇒ 规格默认值）。 */
    public Double param(String id, String key) {
        Active a = sustained.get(id);
        if (a == null) {
            return null;
        }
        Double v = a.params().get(key);
        if (v != null) {
            return v;
        }
        DirectiveSpec.ParamSpec ps = DirectiveSpec.paramsOf(id).get(key);
        return ps == null || ps.text() ? null : ps.def();
    }

    /** 文本参数（例如 {@code ban_focus} 的 label）。 */
    public String textParam(String id, String key) {
        Active a = sustained.get(id);
        return a == null ? null : a.texts().get(key);
    }

    /** 正在生效的持续指令（只读快照）。 */
    public Map<String, Active> active() {
        return Map.copyOf(sustained);
    }

    public int activeCount() {
        return sustained.size();
    }

    /** 最近被丢弃的未知 id（诊断）。 */
    public List<String> lastRejected() {
        return List.copyOf(lastRejected);
    }

    /** 清掉"最近被丢弃"的记录（命令读走后调）。 */
    public void clearRejected() {
        lastRejected.clear();
    }

    /** 一行摘要（诊断/日志）。 */
    public String summary(long now) {
        if (sustained.isEmpty() && instantQueue.isEmpty()) {
            return "（无指令）";
        }
        StringBuilder sb = new StringBuilder();
        for (Active a : sustained.values()) {
            if (sb.length() > 0) {
                sb.append("　");
            }
            sb.append(a.describe(now));
        }
        if (!instantQueue.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("　");
            }
            sb.append("待执行瞬间指令 ").append(instantQueue.size()).append(" 条");
        }
        return sb.toString();
    }

    /**
     * ★★ <b>给 LLM 看的"正在生效"列表</b>（JSON，进 prompt 的 {@code directives_active}）。
     */
    public String activeJson(long now) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Active a : sustained.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(a.id()).append('"');
            if (!a.params().isEmpty() || !a.texts().isEmpty()) {
                sb.append(",\"params\":{");
                boolean f = true;
                for (Map.Entry<String, Double> e : a.params().entrySet()) {
                    if (!f) {
                        sb.append(',');
                    }
                    f = false;
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                }
                for (Map.Entry<String, String> e : a.texts().entrySet()) {
                    if (!f) {
                        sb.append(',');
                    }
                    f = false;
                    sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
                }
                sb.append('}');
            }
            sb.append(",\"remaining_ticks\":").append(Math.max(0, a.untilTick() - now));
            sb.append(",\"issued_by\":\"").append(a.source() == null ? "?" : a.source()).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.valueOf(Math.round(v * 100) / 100.0);
    }
}
