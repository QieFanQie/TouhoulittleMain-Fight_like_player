package com.touhoulittlemad.fightlikeplayer.decision.thinking;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <b>JEV 客户端</b> —— 零第三方依赖（只用 JDK 的 {@code java.net.http} + Gson 做 JSON）、
 * <b>不含任何 Minecraft 类型</b> ⇒ 可以在纯 JVM 里离线自测。
 *
 * <h2>JEV 是什么（与"普通大模型"完全不同）</h2>
 * JEV 是 TypeSafe AI 的 <b>System One 决策模型</b>：<b>它不写一个字</b>，只输出结构化判断。
 * 给它一段上下文 {@code state} 和一组问题 {@code questions}，它<b>并行</b>返回每个问题的判定
 * —— 带概率、带置信度、带加权分数。输出天生是给 {@code if} 读的。
 * ⇒ 这正是「思维层」需要的东西：<b>我们要的不是文章，是"此刻该往哪个方向使劲"</b>。
 *
 * <h2>★★ 三条实测结论（来自 {@code jev-使用指南.md}，都已在真实接口上跑过）</h2>
 * <ol>
 *   <li><b>只能用 {@code POST {base}/responses}，请求体固定四个字段</b>
 *       {@code model / input / state / questions}；
 *       {@code input} 是<b>占位符</b>（JEV 不用它，但缺了直接报 {@code input is required}）。</li>
 *   <li><b>绝不能走 {@code /v1/chat/completions}</b> —— ① 它永远路由不到渠道（jev 的
 *       {@code supported_endpoint_types} 里根本没有 {@code openai}）；② 就算通了计费也是错的
 *       （JEV 报的是 {@code input_tokens}，而 chat 规范要 {@code prompt_tokens} ⇒ 网关退化成估算）。</li>
 *   <li>★★ <b>「无可用渠道」这句报错是假的</b>：网关按"请求体是否合法"选渠道，
 *       只要请求体有一处不合法（缺 {@code state}、{@code score} 只给 1 档、{@code type} 写成大写…），
 *       它<b>不告诉你哪里错</b>，而是统一返回
 *       「分组 auto 下模型 xxx 的可用渠道不存在」—— 极具误导性（会让人去查分组/余额/密钥）。
 *       ⇒ 因此本类内置 {@link #validate}：<b>在本地先把非法请求拦下来</b>，否则你永远看不到真正的原因。
 *       （实测：401/403/404 与"缺 input"是真的会暴露，只有 {@code state}/{@code questions}
 *       内部的问题被吞。）
 * </ol>
 *
 * <h2>★ 为什么把 {@code validate} 做成 public static</h2>
 * 这样离线自测可以直接断言"这些非法请求会被本地拦下"，
 * 而不是只能靠"线上试一次看看" —— 那是不可复现的。
 *
 * @see ThinkPrompt
 * @see Thinker
 * @see <a href="../../../../../../../docs/11-感知层与思维层设计.md">docs/11 §5.1</a>
 */
public final class JevClient {

    /** 端点前缀（★ 要带 {@code /v1}）。 */
    public static final String DEFAULT_BASE_URL = "https://api.new.bi/v1";

    /** 当前可用版本。别名 {@code jev-latest} 需要令牌显式放行。 */
    public static final String DEFAULT_MODEL = "jev-1.13.0";

    /** 本地预校验用的边界（实测值）。 */
    public static final int CHOICE_MIN = 1;
    public static final int CHOICE_MAX = 255;
    public static final int SCORE_MIN = 2;
    public static final int SCORE_MAX = 10;

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration requestTimeout;
    private final HttpClient http;

    /**
     * @param apiKey         Bearer 令牌（不能为空）
     * @param baseUrl        端点前缀，缺省 {@link #DEFAULT_BASE_URL}
     * @param model          模型名，缺省 {@link #DEFAULT_MODEL}
     * @param proxy          {@code host:port}，空/null = 直连
     * @param timeoutSeconds 单次请求超时（秒）
     */
    public JevClient(String apiKey, String baseUrl, String model, String proxy, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("缺少 API Key");
        }
        this.apiKey = apiKey.trim();
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = base.replaceAll("/+$", "");
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.requestTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));

        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isBlank()) {
            String[] hp = proxy.trim().split(":");
            if (hp.length >= 2) {
                b.proxy(ProxySelector.of(new InetSocketAddress(hp[0], Integer.parseInt(hp[1].trim()))));
            }
        }
        this.http = b.build();
    }

    public JevClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL, null, 30);
    }

    /** 端点（只读，诊断用）。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 模型名（只读，诊断用）。 */
    public String model() {
        return model;
    }

    // ───────────────────────── 核心调用 ─────────────────────────

    /**
     * 同步提交一次判定。
     *
     * <p>⚠️ <b>不要在服务端主线程上调它</b>（会卡 tick）。游戏侧一律走
     * {@link #judgeAsync}，见 {@link Thinker} 的线程模型说明。
     *
     * @param state     {@code String} / {@code Map} / {@code List} 都行
     * @param questions 问题集合（key = 自定义问题 ID）
     * @return 上游返回的原始 JSON（已解析成 Map），正常时含 {@code answers} / {@code model} / {@code usage}
     * @throws JevException 任何失败（含本地预校验失败、HTTP 非 200、返回不是 JSON 对象）
     */
    public Map<String, Object> judge(Object state, Map<String, Object> questions) {
        validate(state, questions);
        HttpRequest req = buildRequest(state, questions);
        try {
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(resp.statusCode(), resp.body());
        } catch (IOException e) {
            throw new JevException(0, "网络错误：" + e, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevException(0, "被中断", null, e);
        }
    }

    /**
     * ★★ <b>异步提交</b> —— 游戏侧唯一应该用的入口。
     *
     * <p>返回的 future <b>绝不携带异常</b>：失败也被包装成 {@link JevException} 并
     * {@code completeExceptionally}（调用方在 {@code whenComplete} 里统一处理），
     * 这样"忘记 catch"不会变成静默丢包。
     */
    public CompletableFuture<Map<String, Object>> judgeAsync(Object state,
                                                             Map<String, Object> questions) {
        CompletableFuture<Map<String, Object>> out = new CompletableFuture<>();
        try {
            validate(state, questions);
        } catch (RuntimeException e) {
            out.completeExceptionally(e);
            return out;
        }
        HttpRequest req = buildRequest(state, questions);
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        Throwable cause = err instanceof java.util.concurrent.CompletionException
                                && err.getCause() != null ? err.getCause() : err;
                        out.completeExceptionally(
                                new JevException(0, "网络错误：" + cause, null, cause));
                        return;
                    }
                    try {
                        out.complete(parseResponse(resp.statusCode(), resp.body()));
                    } catch (RuntimeException e) {
                        out.completeExceptionally(e);
                    }
                });
        return out;
    }

    /**
     * ★★ <b>真正要 POST 的 URL</b> —— 由 baseUrl 决定用哪一套中转站口径。
     *
     * <h2>为什么需要有这个函数（委托方第十二轮实测）</h2>
     * 委托方原话：「你改的 APIKEY 端口是<b>一开始 jev 的端口，完全错了</b>。」
     * —— 同一份 key 可能属于<b>两家不同的中转站</b>，而它们的路径**完全不同**：
     * <pre>
     *   JEV 官方中转（api.new.bi）  : POST {base}/responses                请求体 model/input/state/questions
     *   DMXAPI（www.dmxapi.cn）     : POST {base}/typesafe/v1/systemone    请求体 model/state/questions
     * </pre>
     * 之前代码写死 {@code baseUrl + "/responses"} ⇒ 把 baseUrl 改成 DMXAPI 的域名后
     * 会打到 {@code https://www.dmxapi.cn/responses}（404/无渠道），而错误信息只说"连接失败"
     * ⇒ 用户完全看不出是"路径不对"。
     *
     * <p>判据（纯字符串，可离线断言）：baseUrl 已经指向 {@code .../systemone} 或含
     * {@code /typesafe} ⇒ 原样使用；否则按 JEV 官方口径补 {@code /responses}。
     *
     * @param baseUrl 配置里的端点（可带尾斜杠）
     * @return 完整 URL
     */
    public static String endpointFor(String baseUrl) {
        String b = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.contains("/typesafe") || b.endsWith("/systemone")) {
            return b;                          // DMXAPI 那一套：端点本身就是完整路径
        }
        // ★ 只填了域名（或域名 + "/"）⇒ 补 /v1。★ 为什么值得做：`https://api.new.bi`
        //   拼出来是 `https://api.new.bi/responses` ⇒ 404，而报错完全看不出"少了 /v1"。
        //   这一步让"少写一段"从静默失败变成能跑（配合界面上那行「会 POST 到 …」可见）。
        String afterScheme = b.contains("://") ? b.substring(b.indexOf("://") + 3) : b;
        if (!afterScheme.contains("/")) {
            return b + "/v1/responses";
        }
        return b + "/responses";
    }

    /** 这一次请求用的是哪一套中转站口径（给界面显示/诊断用）。 */
    public static String styleOf(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl;
        return (b.contains("/typesafe") || b.endsWith("/systemone"))
                ? "DMXAPI TypeSafe" : "JEV 官方（/responses）";
    }

    private HttpRequest buildRequest(Object state, Map<String, Object> questions) {
        String url = endpointFor(baseUrl);
        boolean dmx = url.contains("/typesafe") || url.endsWith("/systemone");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (!dmx) {
            // ★ 占位符：JEV 官方口径要求 input 存在（缺了报 "input is required"），但不用它的值；
            //   DMXAPI 的示例请求体里没有这个字段 ⇒ 那一套不发它。
            body.put("input", "placeholder");
        }
        body.put("state", state);
        body.put("questions", questions);

        return HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseResponse(int status, String text) {
        if (status != 200) {
            throw new JevException(status, extractError(text), text, null);
        }
        Object parsed;
        try {
            parsed = GSON.fromJson(text, MAP_TYPE);
        } catch (RuntimeException e) {
            throw new JevException(status, "返回不是合法 JSON：" + e.getMessage(), text, e);
        }
        if (!(parsed instanceof Map)) {
            throw new JevException(status, "返回不是 JSON 对象", text, null);
        }
        return (Map<String, Object>) parsed;
    }

    /** 组请求体（★ 自测用它断言"请求体的形状"，不需要真的发请求）。 */
    public Map<String, Object> buildBody(Object state, Map<String, Object> questions) {
        String url = endpointFor(baseUrl);
        boolean dmx = url.contains("/typesafe") || url.endsWith("/systemone");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (!dmx) {
            body.put("input", "placeholder");   // ★ 与 buildRequest 保持同一口径（两处必须一起改）
        }
        body.put("state", state);
        body.put("questions", questions);
        return body;
    }

    /** 请求体序列化后的 JSON 文本（★ 自测用它断言字段名与结构）。 */
    public String buildBodyJson(Object state, Map<String, Object> questions) {
        return GSON.toJson(buildBody(state, questions));
    }

    /** 把网关的 {@code error.message} 抠出来，取不到就返回原始报文。 */
    static String extractError(String text) {
        try {
            Object o = GSON.fromJson(text, Object.class);
            if (o instanceof Map<?, ?> m) {
                Object err = m.get("error");
                if (err instanceof Map<?, ?> em && em.get("message") != null) {
                    return String.valueOf(em.get("message"));
                }
                if (m.get("message") != null) {
                    return String.valueOf(m.get("message"));
                }
            }
        } catch (RuntimeException ignore) {
            // 不是 JSON，原样返回
        }
        return text == null ? "(空响应)" : text;
    }

    // ───────────────────────── ★ 本地预校验 ─────────────────────────

    /**
     * ★★ <b>本地预校验</b> —— 本类最重要的一个方法。
     *
     * <p>理由见类注释第 ③ 条：网关会把<b>所有请求体内部的错误</b>统一伪装成
     * 「无可用渠道」⇒ 不在这里拦下来，排查成本极高。
     *
     * <p>覆盖的规则（都来自实测）：
     * <ul>
     *   <li>{@code state} 必须是 String / Map / List（<b>空串可以</b>，{@code null} 与数字不行）；</li>
     *   <li>{@code questions} 不能为空对象；</li>
     *   <li>每个问题的 {@code type} 只能是 {@code noul}/{@code choice}/{@code score}（<b>区分大小写</b>）；</li>
     *   <li>★ 三类问题的 {@code instructions} <b>都必填且不能是空串</b>
     *       （官方文档说 choice/score 可选，<b>实测是错的</b>）；</li>
     *   <li>{@code choice.criteria} 必须是<b>对象</b>，{@code score.criteria} 必须是<b>数组</b>（写反即失败）；</li>
     *   <li>{@code choice} 选项数 1~255；{@code score} 等级数 2~10。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 任一条不满足（消息里直接写清"哪里错了"）
     */
    public static void validate(Object state, Map<String, Object> questions) {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为 null（必须是 String / Map / List）");
        }
        if (!(state instanceof String || state instanceof Map || state instanceof List)) {
            throw new IllegalArgumentException("state 必须是 String / Map / List，当前是 "
                    + state.getClass().getSimpleName());
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions 不能为空，至少要提 1 个问题");
        }
        for (Map.Entry<String, Object> e : questions.entrySet()) {
            String id = e.getKey();
            Object raw = e.getValue();
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("问题 " + id + " 必须是对象（Map）");
            }
            Map<?, ?> q = (Map<?, ?>) raw;

            Object typeObj = q.get("type");
            String type = typeObj == null ? "" : String.valueOf(typeObj);
            if (!type.equals("noul") && !type.equals("choice") && !type.equals("score")) {
                throw new IllegalArgumentException("问题 " + id + " 的 type 非法：「" + type
                        + "」，只能是 noul / choice / score（区分大小写）");
            }

            Object instr = q.get("instructions");
            if (instr == null || String.valueOf(instr).isBlank()) {
                throw new IllegalArgumentException("问题 " + id
                        + " 缺少 instructions（实测三类问题的 instructions 都必填且不能为空串）");
            }

            Object criteria = q.get("criteria");
            if (type.equals("choice")) {
                if (!(criteria instanceof Map)) {
                    throw new IllegalArgumentException("问题 " + id
                            + " 的 choice.criteria 必须是对象（选项名 → 判定标准），不能是数组");
                }
                int n = ((Map<?, ?>) criteria).size();
                if (n < CHOICE_MIN || n > CHOICE_MAX) {
                    throw new IllegalArgumentException("问题 " + id + " 的 choice.criteria 必须是 "
                            + CHOICE_MIN + "~" + CHOICE_MAX + " 个选项，当前 " + n + " 个");
                }
            } else if (type.equals("score")) {
                if (!(criteria instanceof List)) {
                    throw new IllegalArgumentException("问题 " + id
                            + " 的 score.criteria 必须是数组（等级由低到高），不能是对象");
                }
                int n = ((List<?>) criteria).size();
                if (n < SCORE_MIN || n > SCORE_MAX) {
                    throw new IllegalArgumentException("问题 " + id + " 的 score.criteria 必须是 "
                            + SCORE_MIN + "~" + SCORE_MAX + " 个等级，当前 " + n + " 个");
                }
            }
        }
    }

    // ───────────────────────── 问题构造器 ─────────────────────────

    /** {@code noul}：是非判定，返回「是」的概率 0~1。 */
    public static Map<String, Object> noul(String instructions) {
        require(instructions, "noul");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "noul");
        q.put("instructions", instructions);
        return q;
    }

    /** {@code noul}（带 true/false 的语义边界）。 */
    public static Map<String, Object> noul(String instructions, String trueMeaning,
                                           String falseMeaning) {
        Map<String, Object> q = noul(instructions);
        Map<String, String> c = new LinkedHashMap<>();
        c.put("true", trueMeaning);
        c.put("false", falseMeaning);
        q.put("criteria", c);
        return q;
    }

    /** {@code choice}：从枚举里单选（{@code criteria} = 选项名 → 判定标准，1~255 个）。 */
    public static Map<String, Object> choice(String instructions, Map<String, String> criteria) {
        require(instructions, "choice");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "choice");
        q.put("instructions", instructions);
        q.put("criteria", new LinkedHashMap<>(criteria));
        return q;
    }

    /** {@code score}：有序等级打分（{@code levels} 由低到高，2~10 个），返回概率加权连续分。 */
    public static Map<String, Object> score(String instructions, String... levels) {
        require(instructions, "score");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "score");
        q.put("instructions", instructions);
        q.put("criteria", List.of(levels));
        return q;
    }

    private static void require(String instructions, String type) {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException(type + " 的 instructions 不能为空");
        }
    }

    // ───────────────────────── 读值 ─────────────────────────

    /**
     * 取 {@code answers} 节点。
     *
     * <p>★ 为什么要"搜"而不是直接 {@code get("answers")}：
     * 实测网关有时会把结果<b>包一层信封</b>（如 {@code {"data":{"answers":{...}}}}）。
     * ⇒ 递归找第一个名为 {@code answers} 的对象，兼容两种形态。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> answers(Map<String, Object> response) {
        Object a = findKey(response, "answers", 0);
        if (a instanceof Map) {
            return (Map<String, Object>) a;
        }
        return Map.of();
    }

    private static Object findKey(Object node, String key, int depth) {
        if (depth > 4 || !(node instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) node;
        Object direct = m.get(key);
        if (direct != null) {
            return direct;
        }
        for (Object v : m.values()) {
            Object found = findKey(v, key, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> answerOf(Map<String, Object> response, String id) {
        Object a = answers(response).get(id);
        return a instanceof Map ? (Map<String, Object>) a : null;
    }

    /**
     * ★★ <b>{@code answers} 的键顺序不固定</b>（实测按字典序重排）
     * ⇒ 一律按问题 ID 取值，<b>不要依赖下标</b>。本类的读值全部按 ID。
     */
    public static String choiceValue(Map<String, Object> response, String id) {
        Map<String, Object> a = answerOf(response, id);
        Object v = a == null ? null : a.get("choice");
        return v == null ? null : String.valueOf(v);
    }

    /** {@code noul} 的「是」概率（0~1）；缺失返回 {@link Double#NaN}。 */
    public static double noulProbability(Map<String, Object> response, String id) {
        Map<String, Object> a = answerOf(response, id);
        return a == null ? Double.NaN : num(a.get("noul"), "noul");
    }

    public static boolean noulAsBoolean(Map<String, Object> response, String id, double threshold) {
        double p = noulProbability(response, id);
        return !Double.isNaN(p) && p >= threshold;
    }

    /** ★ {@code score} 的连续分（可能是小数，如 1.2 —— 那是概率加权的结果，不是 bug）。 */
    public static double scoreValue(Map<String, Object> response, String id) {
        Map<String, Object> a = answerOf(response, id);
        return a == null ? Double.NaN : num(a.get("score"), "score");
    }

    /** 置信度；{@code noul} <b>不返回</b> confidence ⇒ 这里返回 {@code null}。 */
    public static Double confidence(Map<String, Object> response, String id) {
        Map<String, Object> a = answerOf(response, id);
        if (a == null || a.get("confidence") == null) {
            return null;
        }
        double d = num(a.get("confidence"), "confidence");
        return Double.isNaN(d) ? null : d;
    }

    /** 概率分布（★ 对象，不是数组；{@code score} 的键是字符串 {@code "0"}..{@code "n"}）。 */
    public static Map<String, Double> probabilities(Map<String, Object> response, String id) {
        return numMap(response, id, "probabilities");
    }

    /** 等级名表（{@code score} 才有）。 */
    public static Map<String, String> legend(Map<String, Object> response, String id) {
        Map<String, Object> a = answerOf(response, id);
        Map<String, String> out = new LinkedHashMap<>();
        if (a == null) {
            return out;
        }
        Object p = a.get("legend");
        if (p instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /** 取用量（计费依据是 {@code input_tokens}）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> usage(Map<String, Object> response) {
        Object u = response == null ? null : response.get("usage");
        return u instanceof Map ? (Map<String, Object>) u : Map.of();
    }

    private static Map<String, Double> numMap(Map<String, Object> response, String id, String field) {
        Map<String, Object> a = answerOf(response, id);
        Map<String, Double> out = new LinkedHashMap<>();
        if (a == null) {
            return out;
        }
        Object p = a.get(field);
        if (p instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), num(e.getValue(), field));
            }
        }
        return out;
    }

    private static double num(Object o, String field) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignore) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    // ───────────────────────── 异常 ─────────────────────────

    /** 任何 JEV 交互失败（含本地预校验）。 */
    public static final class JevException extends RuntimeException {
        private final int status;
        private final String rawBody;

        public JevException(int status, String message, String rawBody, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.rawBody = rawBody;
        }

        /** HTTP 状态码；{@code 0} = 还没发出去（本地校验/网络层失败）。 */
        public int status() {
            return status;
        }

        /** 原始报文（排查用，可能为 null）。 */
        public String rawBody() {
            return rawBody;
        }

        /** ★ 是不是那个"假报错"（网关把请求体错误伪装成无可用渠道）。 */
        public boolean looksLikeFakeChannelError() {
            String m = getMessage();
            return m != null && (m.contains("可用渠道") || m.contains("get_channel_failed"));
        }
    }
}
