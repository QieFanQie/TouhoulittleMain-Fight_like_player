package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ★★ <b>LLM 客户端（chat/completions）—— 「动态指挥」用的那一半</b>。
 *
 * <h2>★★ 这个类存在的理由（委托方第十二轮的批评是对的）</h2>
 * 委托方原话：「我们调用两种模型：① <b>jev 评分模型</b>，用于短期思维层，这部分做的很好；
 * ② <b>LLM 模型（比如 deepseek）</b>，用于<b>动态指挥</b>，你似乎<b>完全没做</b>，
 * 并一直在修改旧的，并以为自己在做新的。」
 *
 * <p>★ <b>他说对了。</b> 本项目此前只有 {@link JevClient}（System One 打分模型），
 * 而"调控顾问"是**用 JEV 的 score 问题**去调旋钮的 —— 那**不是** LLM 动态指挥，
 * 那是"用打分模型做风格调控"。真正的 LLM（会写自然语言、能读懂一段提示词的那种）此前<b>一个字节都没有</b>。
 *
 * <h2>与 {@link JevClient} 的分工（两者并存，不是替换）</h2>
 * <table border="1">
 *   <tr><th></th><th>JevClient（① 短周期思维层）</th><th>LlmClient（② 动态指挥）</th></tr>
 *   <tr><td>模型</td><td>jev-1.13.0（System One 打分）</td><td>任意 chat 模型（deepseek-chat 等）</td></tr>
 *   <tr><td>接口</td><td>{@code POST {base}/responses}（或 DMXAPI 的 systemone）</td>
 *       <td>{@code POST {base}/chat/completions}</td></tr>
 *   <tr><td>输入</td><td>{@code state} + {@code questions}（结构化问题）</td>
 *       <td>{@code messages}（**一段可编辑的提示词** + 当前行为统计）</td></tr>
 *   <tr><td>输出</td><td>每问一个分数（机器可读，无需解析）</td>
 *       <td><b>自然语言</b>（我们只从里面抠一个 JSON 补丁，抠不到就只记录）</td></tr>
 *   <tr><td>回答什么</td><td>"此刻该往哪个方向使劲"（秒级，8 轴向量）</td>
 *       <td><b>"她该是什么脾气"</b>（十秒~分钟级，几个标量旋钮）</td></tr>
 * </table>
 *
 * <p>★ 与 {@code JevClient} 一样的三条纪律：① 默认**不联网**（开关 + key 都齐了才发）；
 * ② 网络在别的线程上跑（{@link #chatAsync}），**绝不阻塞游戏 tick**；
 * ③ 失败必须**用户可读**（{@link LlmException} 里带状态码与人话）。
 */
public final class LlmClient {

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type MAP_TYPE =
            new TypeToken<Map<String, Object>>() {
            }.getType();

    /** 默认端点：DeepSeek 的 OpenAI 兼容口（与 key 匹配时可直接用）。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";

    /** 默认模型。 */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int timeoutSeconds;
    private final HttpClient http;

    public LlmClient(String apiKey, String baseUrl, String model, String proxy, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        this.apiKey = apiKey.trim();
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);

        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, this.timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isBlank()) {
            String[] hp = proxy.trim().split(":");
            if (hp.length == 2) {
                b.proxy(ProxySelector.of(new InetSocketAddress(hp[0].trim(),
                        Integer.parseInt(hp[1].trim()))));
            }
        }
        this.http = b.build();
    }

    public LlmClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL, null, 30);
    }

    /**
     * ★ <b>真正要 POST 的 URL</b>：{@code {base}/chat/completions}。
     * <p>已经写到 {@code /chat/completions} 的（有人习惯把完整地址填进来）原样使用。
     */
    public static String endpointFor(String baseUrl) {
        String b = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/chat/completions")) {
            return b;
        }
        return b + "/chat/completions";
    }

    /**
     * 发一次对话，<b>异步</b>返回模型的文本回复。
     *
     * <p>★ 为什么只要"文本"：动态指挥的输出**注定是自然语言**（模型会写一段话，
     * 里面夹一个 JSON 补丁）。解析交给纯逻辑层 {@link LlmAdvisor#parsePatch}，
     * 这样"模型乱写"永远不会让游戏崩，只是"这一轮的建议被丢弃"。
     */
    public CompletableFuture<String> chatAsync(String systemPrompt, String userContent) {
        Map<String, Object> body = buildBody(systemPrompt, userContent, 0.2, 400);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpointFor(baseUrl)))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> parseContent(resp.statusCode(), resp.body()));
    }

    /** 同步版（离线自测 / 界面「测试连接」用）。 */
    public String chat(String systemPrompt, String userContent) {
        try {
            return chatAsync(systemPrompt, userContent).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof LlmException le) {
                throw le;
            }
            throw new LlmException(-1, "请求失败：" + c, null, c);
        }
    }

    /** 组请求体（★ 自测用它断言形状，不需要真的发请求）。 */
    public Map<String, Object> buildBody(String systemPrompt, String userContent,
                                         double temperature, int maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(msg("system", systemPrompt));
        }
        messages.add(msg("user", userContent == null ? "" : userContent));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        return body;
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** 取 {@code choices[0].message.content}；非 200 ⇒ 抛带状态码与人话的异常。 */
    @SuppressWarnings("unchecked")
    private static String parseContent(int status, String text) {
        if (status != 200) {
            throw new LlmException(status, extractError(status, text), text, null);
        }
        Object parsed;
        try {
            parsed = GSON.fromJson(text, MAP_TYPE);
        } catch (RuntimeException e) {
            throw new LlmException(status, "返回不是合法 JSON：" + e.getMessage(), text, e);
        }
        if (!(parsed instanceof Map)) {
            throw new LlmException(status, "返回不是 JSON 对象", text, null);
        }
        Object choices = ((Map<String, Object>) parsed).get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new LlmException(status, "返回里没有 choices（端点可能不是 chat 模型）", text, null);
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            throw new LlmException(status, "choices[0] 结构异常", text, null);
        }
        Object message = m.get("message");
        if (!(message instanceof Map<?, ?> mm) || !(mm.get("content") instanceof String content)) {
            throw new LlmException(status, "choices[0].message.content 不是文本", text, null);
        }
        return content;
    }

    /** 把网关的错误信息抠成人话（与 {@code JevClient} 同风格）。 */
    @SuppressWarnings("unchecked")
    static String extractError(int status, String text) {
        String hint = switch (status) {
            case 401 -> "鉴权失败（API Key 不对，或端点不是这家的）";
            case 402 -> "余额不足";
            case 404 -> "端点不存在（多半是 baseUrl 少了 /v1，或不是 OpenAI 兼容口）";
            case 429 -> "触发限流（调大 intervalTicks 或换 key）";
            case 500, 502, 503 -> "服务端错误（稍后重试）";
            default -> "HTTP " + status;
        };
        try {
            Object parsed = GSON.fromJson(text, MAP_TYPE);
            if (parsed instanceof Map<?, ?> m) {
                Object err = ((Map<String, Object>) m).get("error");
                if (err instanceof Map<?, ?> em && em.get("message") != null) {
                    return hint + " —— " + em.get("message");
                }
                if (err instanceof String s && !s.isBlank()) {
                    return hint + " —— " + s;
                }
                if (m.get("message") != null) {
                    return hint + " —— " + m.get("message");
                }
            }
        } catch (RuntimeException ignore) {
            // 不是 JSON ⇒ 用原始文本（截断）
        }
        String raw = text == null ? "" : text.strip();
        return raw.isEmpty() ? hint : hint + " —— " + raw.substring(0, Math.min(160, raw.length()));
    }

    /** 供界面显示：这一次会打到哪个地址。 */
    public String describe() {
        return "POST " + endpointFor(baseUrl) + "　model=" + model;
    }

    /** 脱敏后的 key（日志/命令用）。 */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "(未配置)";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 5) + "…" + key.substring(key.length() - 4);
    }

    /** 与 {@code JevClient.JevException} 同构：带状态码、带人话、带原始报文。 */
    public static final class LlmException extends RuntimeException {
        private final int status;
        private final String rawBody;

        public LlmException(int status, String message, String rawBody, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.rawBody = rawBody;
        }

        public int status() {
            return status;
        }

        public String rawBody() {
            return rawBody;
        }
    }

    /** 供离线自测：仅构造请求体与端点，不发请求。 */
    public String modelName() {
        return model;
    }

}
