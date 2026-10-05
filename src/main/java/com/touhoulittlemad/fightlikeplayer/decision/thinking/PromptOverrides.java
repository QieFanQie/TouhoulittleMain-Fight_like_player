package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>提示词覆写表</b> —— 让委托方在<b>游戏内的配置界面</b>里改"要问模型什么"
 * （委托方 2026-10-01：「使得配置界面可以配置 apikey &amp; 编辑提示词」）。
 *
 * <h2>为什么提示词是"每轴一句话"，而不是一大段 system prompt</h2>
 * 因为本项目的思维层<b>不是聊天</b>：JEV 只回答 {@code score} 问题
 * （见 {@link JevClient} 的类注释），而<b>每个问题的 {@code instructions} 就是提示词本体</b>
 * （它决定模型怎么理解这条轴）。所以"编辑提示词"在这里的准确形态是：
 * <b>覆写每一条轴的问法</b>。
 *
 * <h2>★ 格式（刻意选最不容易写错的形态）</h2>
 * <pre>
 *   # 以 # 开头的行是注释，空行忽略
 *   single_damage = How much does the current situation call for hitting ONE target hard?
 *   mobility = In which direction should she move right now?
 * </pre>
 * <ul>
 *   <li>键 = 轴 id（{@link ThinkPrompt#AXIS_OF} 的键）或旋钮名（{@link Advisor#TUNABLE}）；</li>
 *   <li><b>没写的键沿用内置问法</b> ⇒ 只想改一条就只写一条；</li>
 *   <li><b>写错的键被丢弃并在日志里点名</b> —— 这是 docs/13 的纪律：
 *       静默忽略一个拼错的键，等于让人以为它生效了。</li>
 * </ul>
 *
 * <p>★ 本类刻意是<b>纯逻辑</b>（无 {@code net.minecraft}、无 Forge）：
 * 解析规则要能被离线自测断言，而不是只能靠"进游戏试一次"。
 *
 * @see ThinkPrompt#questions(Map)
 * @see Advisor#questions(Map)
 */
public final class PromptOverrides {

    private PromptOverrides() {
    }

    /** 解析的结果：覆写表 + 被丢弃的键（诊断用）。 */
    public record Parsed(Map<String, String> overrides, java.util.List<String> ignoredKeys) {

        /** 空结果（配置留空时的常态）。 */
        public static Parsed empty() {
            return new Parsed(Map.of(), java.util.List.of());
        }

        public boolean isEmpty() {
            return overrides.isEmpty();
        }

        /** 一行摘要（日志/命令用）。 */
        public String summary() {
            if (overrides.isEmpty() && ignoredKeys.isEmpty()) {
                return "（未覆写，用内置问法）";
            }
            StringBuilder sb = new StringBuilder("覆写 ").append(overrides.size()).append(" 条");
            if (!ignoredKeys.isEmpty()) {
                sb.append("；★ 丢弃了 ").append(ignoredKeys.size()).append(" 个无法识别的键：")
                        .append(String.join(", ", ignoredKeys));
            }
            return sb.toString();
        }
    }

    /**
     * 解析多行文本。
     *
     * @param text 配置里的多行字符串；{@code null}/空白 ⇒ 空结果
     * @param knownKeys ★ <b>合法的键集合</b> —— 不在其中的键会被丢弃并登记
     *                  （"写错一个键"必须看得见，而不是静默失效）
     */
    public static Parsed parse(String text, java.util.Set<String> knownKeys) {
        if (text == null || text.isBlank()) {
            return Parsed.empty();
        }
        Map<String, String> out = new LinkedHashMap<>();
        java.util.List<String> ignored = new java.util.ArrayList<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                ignored.add(line.length() > 24 ? line.substring(0, 24) + "…" : line);
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.isEmpty()) {
                ignored.add(key + "（空问法）");
                continue;
            }
            if (knownKeys != null && !knownKeys.isEmpty() && !knownKeys.contains(key)) {
                ignored.add(key);
                continue;
            }
            out.put(key, value);
        }
        return new Parsed(Map.copyOf(out), java.util.List.copyOf(ignored));
    }

    /** 便捷：用 {@link ThinkPrompt} 的轴 id 当合法键集合。 */
    public static Parsed parseForThink(String text) {
        return parse(text, ThinkPrompt.AXIS_OF.keySet());
    }

    /** 便捷：用 {@link Advisor} 的可调旋钮当合法键集合。 */
    public static Parsed parseForAdvisor(String text) {
        return parse(text, new java.util.LinkedHashSet<>(Advisor.TUNABLE));
    }

    /**
     * ★ 把覆写渲染成"可以贴回配置里"的模板（供 GUI 的默认内容与文档用）。
     * <p>内置问法都是长句，模板里只放空的骨架 + 注释，避免用户误以为必须全文照抄。
     */
    public static String templateForThink() {
        StringBuilder sb = new StringBuilder("# 覆写 JEV 的问法：<轴 id> = <问法>\n"
                + "# 没写的轴沿用内置问法。合法轴 id：\n");
        for (String id : ThinkPrompt.AXIS_OF.keySet()) {
            sb.append("#   ").append(id).append("（").append(ThinkPrompt.ZH_OF.get(id)).append("）\n");
        }
        return sb.toString();
    }

    /** 顾问那一半的模板。 */
    public static String templateForAdvisor() {
        StringBuilder sb = new StringBuilder("# 覆写调控顾问的问法：<旋钮名> = <问法>\n"
                + "# 没写的旋钮沿用内置问法。合法旋钮名：\n");
        for (String id : Advisor.TUNABLE) {
            sb.append("#   ").append(id).append('\n');
        }
        return sb.toString();
    }
}
