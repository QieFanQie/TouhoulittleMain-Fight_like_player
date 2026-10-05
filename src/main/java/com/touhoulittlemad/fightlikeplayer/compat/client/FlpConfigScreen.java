package com.touhoulittlemad.fightlikeplayer.compat.client;

import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.JevClient;
import com.touhoulittlemad.fightlikeplayer.decision.thinking.LlmClient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>模组配置界面</b> —— 委托方要求「做到在 MC 的模组配置界面配置」。
 *
 * <h2>为什么必须自己写这个类</h2>
 * 见 {@link FlpClientSetup} 的取证：Forge 47.3.22 <b>只提供扩展点、不提供内置配置界面</b>
 * ⇒ 不写它，模组列表里连 Config 按钮都不会出现。
 *
 * <h2>★ 三条设计取舍（都为"稳定、不出事"）</h2>
 * <ol>
 *   <li><b>分三页而不是滚动列表</b>：每页最多 5 行 ⇒ 在任何 GUI 缩放下都放得下，
 *       不需要自己实现滚动（滚动要处理裁剪、滚轮、越界 —— 那是更多 bug 的地方）。</li>
 *   <li><b>Key 用"切换明文/掩码"而不是格式化器</b>：
 *       {@code EditBox} 的 formatter 会参与 {@code getValue()} 的取值路径
 *       （一旦理解错就会<b>把掩码当密钥存进去</b>）。这里改成"隐藏时把框设为不可编辑、
 *       显示掩码预览"，真实值单独保存 ⇒ <b>不可能存错</b>。</li>
 *   <li><b>保存前逐个校验</b>，任何一项不合法就<b>不关界面、不改配置</b>，并把原因显示出来。
 *       默默存下一个非法值（如 {@code intervalTicks=abc}）会让思维层静默不工作。</li>
 * </ol>
 *
 * <p>★ 校验规则的取值域与 {@link FlpConfig} 里的 {@code defineInRange} <b>保持一致</b>：
 * 界面挡住的东西，配置文件层面也挡住了 —— 两处不一致就会出现"界面存下去，
 * 下次加载时被 Forge 拒掉并回退默认值"这种极难查的现象。
 */
public final class FlpConfigScreen extends Screen {

    /** 每页的行（行定义见 {@link #buildPage}）。 */
    private static final int PAGES = 4;
    private static final String[] PAGE_TITLES = {"① JEV 连接（短周期思维层）", "② 节奏与强度", "③ 行为与诊断",
            "④ LLM 动态指挥（chat 模型）", "⑤ JEV 问法覆写"};

    /** LLM 页与 JEV 问法页的索引 —— 它们各有自己的布局，不走"每行一个控件"。 */
    private static final int PAGE_LLM = 3;
    private static final int PAGE_PROMPTS = 4;

    /**
     * 行高与行起点。
     *
     * <h2>★★ 第十二轮重排：改成「标签在上、输入框在下」（委托方两次报「UI 挤占」）</h2>
     * 旧布局是"标签在左、输入框在右"（标签从 {@code mid-150} 开始，输入框从 {@code mid+8} 开始）
     * ⇒ 标签只有 <b>158 px</b> 可用，而中文标签 + 提示一长就直接**压在输入框上**
     * （第十一轮我把 Key 那一行的标签加长，反而更严重了）。
     * <p>⇒ 结构性修法：<b>标签独占一行，输入框占满下面一整行</b> —— 无论标签多长都不可能重叠。
     * 代价是每行从 26 变成 {@value #ROW_H} px，所以行数必须收着用（每页 ≤ 5 行）。
     */
    private static final int ROW_H = 28;

    /** 标签行的 y 偏移量；输入框画在它下面 {@value #FIELD_DY} px 处。 */
    private static final int FIELD_DY = 11;

    private static final int ROW_TOP = 60;

    private final Screen parent;

    /** 草稿：文本类配置（★ 保存时才写入 ForgeConfigSpec，取消则丢弃）。 */
    private final Map<String, String> draft = new LinkedHashMap<>();
    /** 草稿：布尔类配置。 */
    private final Map<String, Boolean> flags = new LinkedHashMap<>();

    private String apiKeyReal = "";
    private boolean apiKeyVisible;
    private boolean loaded;
    private int page;
    private String error;

    /** 需要在 {@link #render} 里画的标签。 */
    private final List<Label> labels = new ArrayList<>();

    /** 一个标签的绘制位置。 */
    private record Label(Component text, int x, int y) {
    }

    public FlpConfigScreen(Screen parent) {
        super(Component.literal("玩家拟人战法 · 配置"));
        this.parent = parent;
    }

    // ───────────────────────── 装载 / 初始化 ─────────────────────────

    private void loadDrafts() {
        apiKeyReal = FlpConfig.get(FlpConfig.THINK_API_KEY, "");
        flags.put("enabled", FlpConfig.get(FlpConfig.THINK_ENABLED, Boolean.FALSE));
        draft.put("baseUrl", FlpConfig.get(FlpConfig.THINK_BASE_URL, JevClient.DEFAULT_BASE_URL));
        draft.put("model", FlpConfig.get(FlpConfig.THINK_MODEL, JevClient.DEFAULT_MODEL));
        draft.put("proxy", FlpConfig.get(FlpConfig.THINK_PROXY, ""));
        draft.put("intervalTicks", String.valueOf(FlpConfig.get(FlpConfig.THINK_INTERVAL_TICKS, 30)));
        draft.put("timeoutSeconds", String.valueOf(FlpConfig.get(FlpConfig.THINK_TIMEOUT_SECONDS, 30)));
        draft.put("biasStrength", String.valueOf(FlpConfig.get(FlpConfig.THINK_BIAS_STRENGTH, 0.35)));
        draft.put("minConfidence", String.valueOf(FlpConfig.get(FlpConfig.THINK_MIN_CONFIDENCE, 0.0)));
        draft.put("maxInFlight", String.valueOf(FlpConfig.get(FlpConfig.THINK_MAX_IN_FLIGHT, 2)));
        draft.put("applyMode", FlpConfig.get(FlpConfig.THINK_APPLY_MODE, "bias"));
        flags.put("onlyInCombat", FlpConfig.get(FlpConfig.THINK_ONLY_IN_COMBAT, Boolean.TRUE));
        flags.put("logDecisions", FlpConfig.get(FlpConfig.THINK_LOG_DECISIONS, Boolean.TRUE));
        // ★★ LLM 动态指挥（第 ④ 页，第十二轮）：与 JEV 那一套【完全独立】的一份配置
        flags.put("llmEnabled", FlpConfig.get(FlpConfig.LLM_ENABLED, Boolean.FALSE));
        // ★ 第 15 轮：女仆对话模型的下令权限（默认开）
        flags.put("toolDirectives", FlpConfig.get(FlpConfig.LLM_TOOL_DIRECTIVES, Boolean.TRUE));
        draft.put("llmApiKey", FlpConfig.get(FlpConfig.LLM_API_KEY, ""));
        draft.put("llmBaseUrl", FlpConfig.get(FlpConfig.LLM_BASE_URL, LlmClient.DEFAULT_BASE_URL));
        draft.put("llmModel", FlpConfig.get(FlpConfig.LLM_MODEL, LlmClient.DEFAULT_MODEL));
        draft.put("llmProxy", FlpConfig.get(FlpConfig.LLM_PROXY, ""));
        draft.put("llmPrompt", FlpConfig.get(FlpConfig.LLM_SYSTEM_PROMPT, ""));
        draft.put("llmInterval", String.valueOf(FlpConfig.get(FlpConfig.LLM_INTERVAL_TICKS, 1200)));
        draft.put("llmApplyMode", FlpConfig.get(FlpConfig.LLM_APPLY_MODE, "observe"));
        // ★★ 提示词（第 ⑤ 页）：原样读入，留空 = 用内置问法
        draft.put("thinkPrompt", FlpConfig.get(FlpConfig.THINK_QUESTION_OVERRIDES, ""));
        draft.put("advisorPrompt", FlpConfig.get(FlpConfig.ADVISOR_QUESTION_OVERRIDES, ""));
        loaded = true;
    }

    @Override
    protected void init() {
        if (!loaded) {
            loadDrafts();
        }
        labels.clear();

        int mid = this.width / 2;
        // ★★ 第十二轮：标签与输入框【同一左边界】、上下排布 ⇒ 永远不会互相挤占
        int labelX = Math.max(6, mid - 170);

        // ── 分页标签 + 页内行 ──
        if (page == PAGE_PROMPTS) {
            // ★★ JEV 问法覆写页：不走"每行一个控件"的框架 —— 它是两个多行编辑框
            buildPromptPage(labelX);
        } else if (page == PAGE_LLM) {
            // ★★ LLM 动态指挥页：两个多行框（提示词）+ 若干单行框，自己排
            buildLlmPage(labelX);
        } else {
            for (Row row : buildPage(page)) {
                labels.add(new Label(row.label(), labelX, row.widget().getY() - FIELD_DY));
                addRenderableWidget(row.widget());
            }
        }

        // ── 底部按钮 ──
        int bottom = this.height - 28;
        int bw = 104;
        int gap = 6;
        int total = bw * 3 + gap * 2;
        int bx = mid - total / 2;

        addRenderableWidget(Button.builder(Component.literal("◀ 上一页"), b -> switchPage(-1))
                .bounds(bx, bottom, 74, 20).build());
        addRenderableWidget(Button.builder(Component.literal("保存并关闭"), b -> save())
                .bounds(bx + 74 + gap, bottom, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(bx + 74 + gap + bw + gap, bottom, 74, 20).build());
        // 恢复默认放到右上角，避免误点
        addRenderableWidget(Button.builder(Component.literal("恢复默认"), b -> restoreDefaults())
                .bounds(this.width - 90, 16, 82, 18).build());

        // ★★ 两家常用中转站的预设（第十二轮）—— 端点路径不同，手打极容易错
        //    点一下就填好 baseUrl，配合上面那行「会 POST 到 …」立刻能看出对不对。
        int py = ROW_TOP + ROW_H * 2 + FIELD_DY;      // 与"端点 baseUrl"那一行同一行
        addRenderableWidget(Button.builder(Component.literal("用 JEV 官方"), b -> {
            draft.put("baseUrl", JevClient.DEFAULT_BASE_URL);
            draft.putIfAbsent("model", JevClient.DEFAULT_MODEL);
            rebuildWidgets();
        }).bounds(fieldX() + fieldW() - 176, py, 86, 18).build());
        addRenderableWidget(Button.builder(Component.literal("用 DMXAPI"), b -> {
            draft.put("baseUrl", "https://www.dmxapi.cn/typesafe/v1/systemone");
            draft.putIfAbsent("model", JevClient.DEFAULT_MODEL);
            rebuildWidgets();
        }).bounds(fieldX() + fieldW() - 86, py, 86, 18).build());

        // ★★ 「测试连接」—— 委托方说「不知道怎么测试」⇒ 把测试做进界面：
        //    直接用**当前草稿里的**端点/Key/模型发一次真实请求，把结果写在界面上。
        //    ★ 用草稿而不是已保存的配置 ⇒ 可以"先测再存"。
        addRenderableWidget(Button.builder(Component.literal("测试连接"), b -> testConnection())
                .bounds(this.width - 90, 38, 82, 18).build());
    }

    /**
     * ★★ <b>测试连接</b> —— 发一次真实的 JEV 请求，把结果直接显示在界面上。
     *
     * <h2>为什么必须有这个按钮</h2>
     * 委托方原话：「**配置界面输入不了 llm 的 apikey。不知道怎么测试。**」
     * ⇒ 一个"填了 key 却不知道有没有生效"的配置界面是不完整的：
     * 判断链路（端点 / Key / 模型名 / 代理 / 请求体形状 / 解析）**必须能在界面上一次问清**，
     * 否则用户只能进游戏打一架、再翻日志猜。
     *
     * <p>★ 三条实现纪律：
     * <ol>
     *   <li><b>不阻塞**渲染线程**</b>：网络在 {@code CompletableFuture} 上跑，界面只显示"测试中…"；</li>
     *   <li><b>用草稿值</b>（端点是刚改的那个，而不是已保存的）；</li>
     *   <li><b>结果必须说人话</b>：成功给往返毫秒数，失败给可读原因
     *       （401 / 超时 / DNS / 模型名不对 —— 这些都从 {@code JevClient.JevException} 里翻出来）。</li>
     * </ol>
     */
    private void testConnection() {
        String url = draft.getOrDefault("baseUrl", JevClient.DEFAULT_BASE_URL).trim();
        String key = apiKeyReal == null ? "" : apiKeyReal.trim();
        String model = draft.getOrDefault("model", JevClient.DEFAULT_MODEL).trim();
        String proxy = draft.getOrDefault("proxy", "").trim();
        if (key.isEmpty()) {
            testState = TestState.done(false, "§cAPI Key 是空的 —— 先在上面那一行输入 Key（点一下输入框就能打字）");
            return;
        }
        testState = TestState.running();
        long t0 = System.currentTimeMillis();
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> {
                    try {
                        JevClient c = new JevClient(key, url, model,
                                proxy.isEmpty() ? null : proxy, 20);
                        // 一次最小但真实的判定：一条 score 问题（与思维层走完全同一条路）
                        Map<String, Object> probe = Map.of("probe", JevClient.score(
                                "Is this a connection test? Answer with any level.",
                                "no", "low", "medium", "high", "yes"));
                        c.judge(Map.of("who", "connection test"), probe);
                        return "§a✅ 连接成功（" + (System.currentTimeMillis() - t0) + " ms，模型 "
                                + model + "）";
                    } catch (Throwable t) {
                        return "§c❌ 失败：" + explain(t);
                    }
                })
                .thenAccept(msg -> {
                    // ★ 回到客户端线程再碰界面状态
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> testState = TestState.done(true, msg));
                    } else {
                        testState = TestState.done(true, msg);
                    }
                });
    }

    /** 把异常翻成人话（用户看得到的那一句）。 */
    private static String explain(Throwable t) {
        String cls = t.getClass().getSimpleName();
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (t instanceof JevClient.JevException) {
            return msg.isBlank() ? cls : msg;
        }
        if (t instanceof java.net.UnknownHostException) {
            return "域名解析失败（端点写错？还是没网？）：" + msg;
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "超时（网络慢 / 需要代理？）";
        }
        if (t instanceof java.net.ConnectException) {
            return "连不上（需要代理？）：" + msg;
        }
        return cls + (msg.isBlank() ? "" : "：" + msg);
    }

    /** 测试连接的状态（线程安全：只读一个 volatile 字符串）。 */
    private record TestState(boolean testing, String message) {
        static TestState running() {
            return new TestState(true, "§7测试中…（最多 20 秒）");
        }

        static TestState done(boolean ignored, String message) {
            return new TestState(false, message);
        }
    }

    private volatile TestState testState;

    /** 一行：标签 + 控件。 */
    private record Row(Component label, net.minecraft.client.gui.components.AbstractWidget widget) {
    }

    private List<Row> buildPage(int p) {
        List<Row> rows = new ArrayList<>();
        switch (p) {
            case 0 -> {
                // ★★ 第十一轮：**API Key 放到第一行**（委托方实测「看不见 apikey 在哪填」）。
                //    它在第 3 行时会被看成一堆端点/模型里的普通一行；而它是**必填项**。
                rows.add(apiKeyRow());
                rows.add(toggle("enabled", "思维层总开关", "关 = 完全不联网"));
                rows.add(text("baseUrl", "端点 baseUrl", "必须带 /v1"));
                rows.add(text("model", "模型 model", "如 jev-1.13.0"));
                rows.add(text("proxy", "代理 host:port", "留空 = 直连"));
            }
            case 1 -> {
                rows.add(text("intervalTicks", "提交间隔（tick）", "20 tick = 1 秒，推荐 20~30"));
                rows.add(text("timeoutSeconds", "单次超时（秒）", "超时即丢包，沿用旧弹簧"));
                rows.add(text("biasStrength", "偏置强度", "0 = 只观测；0.3~0.5 为推荐量级"));
                rows.add(text("minConfidence", "置信度门槛", "低于它整包丢弃；0 = 不拦"));
                rows.add(toggle("applyMode", "结果去向", "bias = 推弹簧；observe = 只记录"));
            }
            default -> {
                rows.add(toggle("onlyInCombat", "仅战斗中调用", "避免女仆发呆时白烧额度"));
                rows.add(toggle("logDecisions", "把判定写进日志", "info 级：能在 latest.log 里看见"));
                rows.add(text("maxInFlight", "在途请求上限", "1 = 全局串行，最省额度"));
                // ★★ 第 15 轮（M5）：女仆的【对话模型】能不能给她下战斗指令。
                //   ★ 放在"行为"页而不是 LLM 页：它管的是**她的行为**，
                //     而且 LLM 那一页已经排满（key/端点/模型/提示词框/三个按钮）。
                rows.add(toggle("toolDirectives", "允许女仆对话里下令",
                        "跟她说「拉开距离打」真的照办（工具 flp_directive）"));
            }
        }
        return rows;
    }

    /**
     * ★★ <b>第 ④ 页：提示词编辑</b>（委托方 2026-10-01：「使得配置界面可以配置 apikey &amp; 编辑提示词」）。
     *
     * <h2>为什么用两个多行框，而不是"每个轴一个输入框"</h2>
     * 覆写的格式是<b>每行一条</b>（{@code <键> = <问法>}）⇒ 多行框天然贴合这个格式，
     * 而且 8 条轴 + 5 个旋钮共 13 个输入框会超出任何 GUI 缩放下的可用空间。
     * <p>★ 多行框用原版的 {@link net.minecraft.client.gui.components.MultiLineEditBox}：
     * 它自带换行与滚动，不必自己实现（自己实现滚动正是 {@link #PAGES} 分页想避开的那类 bug）。
     *
     * <h2>★ 校验：不合法就不让保存</h2>
     * 与其它页同一条纪律 —— 写错的键<b>不会静默失效</b>：
     * 保存前用 {@code PromptOverrides} 试解析一遍，把"无法识别的键"直接显示出来
     * （它在日志里也会被点名，但用户此刻就站在这里，当场告诉他最省事）。
     */
    private void buildPromptPage(int labelX) {
        int boxX = fieldX();
        int boxW = fieldW();
        // ★★ 第十二轮：高度按窗口自适应（旧实现写死 64×2 + 固定按钮宽 ⇒ 小窗口下越界/重叠）
        int avail = Math.max(90, this.height - (ROW_TOP + FIELD_DY) - 76);
        int boxH = Math.max(36, (avail - 34) / 2);
        int y1 = ROW_TOP;
        int y2 = y1 + FIELD_DY + boxH + 26;

        labels.add(new Label(Component.literal("思维层问法覆写 §7（每行：轴 id = 问法；# 开头是注释）"),
                boxX, y1));
        thinkPromptBox = new net.minecraft.client.gui.components.MultiLineEditBox(
                this.font, boxX, y1 + FIELD_DY, boxW, boxH,
                Component.literal("留空 = 用内置问法"), Component.literal("思维层问法覆写"));
        thinkPromptBox.setCharacterLimit(4000);
        thinkPromptBox.setValue(draft.getOrDefault("thinkPrompt", ""));
        thinkPromptBox.setValueListener(v -> draft.put("thinkPrompt", v));
        addRenderableWidget(thinkPromptBox);

        labels.add(new Label(Component.literal("顾问问法覆写 §7（每行：旋钮名 = 问法）"),
                boxX, y2));
        advisorPromptBox = new net.minecraft.client.gui.components.MultiLineEditBox(
                this.font, boxX, y2 + FIELD_DY, boxW, boxH,
                Component.literal("留空 = 用内置问法"), Component.literal("顾问问法覆写"));
        advisorPromptBox.setCharacterLimit(4000);
        advisorPromptBox.setValue(draft.getOrDefault("advisorPrompt", ""));
        advisorPromptBox.setValueListener(v -> draft.put("advisorPrompt", v));
        addRenderableWidget(advisorPromptBox);

        // 三个"模板/清空"按钮：按可用宽度三等分（任何宽度下都不重叠、不越界）
        int by = y2 + FIELD_DY + boxH + 6;
        int gap = 4;
        int btnW = Math.max(56, (boxW - gap * 2) / 3);
        addRenderableWidget(Button.builder(Component.literal("思维层模板"), b -> {
            thinkPromptBox.setValue(com.touhoulittlemad.fightlikeplayer.decision.thinking
                    .PromptOverrides.templateForThink());
            draft.put("thinkPrompt", thinkPromptBox.getValue());
        }).bounds(boxX, by, btnW, 18).build());
        addRenderableWidget(Button.builder(Component.literal("顾问模板"), b -> {
            advisorPromptBox.setValue(com.touhoulittlemad.fightlikeplayer.decision.thinking
                    .PromptOverrides.templateForAdvisor());
            draft.put("advisorPrompt", advisorPromptBox.getValue());
        }).bounds(boxX + btnW + gap, by, btnW, 18).build());
        addRenderableWidget(Button.builder(Component.literal("清空（用内置）"), b -> {
            thinkPromptBox.setValue("");
            advisorPromptBox.setValue("");
            draft.put("llmApiKey", "");
        draft.put("llmBaseUrl", LlmClient.DEFAULT_BASE_URL);
        draft.put("llmModel", LlmClient.DEFAULT_MODEL);
        draft.put("llmProxy", "");
        draft.put("llmPrompt", "");
        draft.put("llmInterval", "1200");
        draft.put("llmApplyMode", "observe");
        flags.put("llmEnabled", Boolean.FALSE);
        draft.put("thinkPrompt", "");
            draft.put("advisorPrompt", "");
        }).bounds(boxX + (btnW + gap) * 2, by, btnW, 18).build());
    }

    /** 提示词页的两个多行框（其它页为 null）。 */
    private net.minecraft.client.gui.components.MultiLineEditBox thinkPromptBox;
    private net.minecraft.client.gui.components.MultiLineEditBox advisorPromptBox;

    /** ④ LLM 页：系统提示词框 + LLM 的 key 框（其它页为 null）。 */
    private net.minecraft.client.gui.components.MultiLineEditBox llmPromptBox;
    private EditBox llmKeyBox;

    /**
     * ★★ <b>第 ④ 页：LLM 动态指挥</b>（第十二轮 —— 委托方点名要的那一半）。
     *
     * <h2>为什么它必须与第 ① 页分开</h2>
     * 委托方说得很清楚：「我们调用<b>两种模型</b>：① JEV 评分模型…② <b>LLM 模型（比如 deepseek）</b>
     * 用于动态指挥」。两者的 **key / 端点 / 模型 / 开关都是两份**，
     * 混在一页里必然让人以为"填一次就够"。
     *
     * <p>布局：4 行单行框（Key / 端点 / 模型 / 间隔与去向）+ 一个大号**系统提示词**多行框
     * + 「填入内置提示词」「测试连接」按钮；页眉下方仍显示**实际会 POST 到哪**。
     */
    private void buildLlmPage(int labelX) {
        int x = labelX;
        int w = fieldW();
        int y = ROW_TOP;

        labels.add(new Label(Component.literal("§eLLM 的 API Key§7（与 ① 页的 JEV key 是【两份】）"),
                x, y));
        llmKeyBox = new EditBox(this.font, x, y + FIELD_DY, w - 46, 18, Component.literal("LLM Key"));
        llmKeyBox.setMaxLength(240);
        llmKeyBox.setEditable(true);
        llmKeyBox.setValue(draft.getOrDefault("llmApiKey", ""));
        llmKeyBox.setResponder(v -> draft.put("llmApiKey", v));
        llmKeyBox.setFormatter((text, cursor) -> net.minecraft.util.FormattedCharSequence.forward(
                (llmKeyVisible || llmKeyBox.isFocused()) ? text : mask(text),
                net.minecraft.network.chat.Style.EMPTY));
        llmKeyBox.setHint(Component.literal("点这里直接输入（聚焦即明文）"));
        addRenderableWidget(llmKeyBox);
        addRenderableWidget(Button.builder(Component.literal(llmKeyVisible ? "隐藏" : "显示"),
                        b -> {
                            llmKeyVisible = !llmKeyVisible;
                            rebuildWidgets();
                        })
                .bounds(x + w - 42, y + FIELD_DY, 42, 18).build());
        y += ROW_H;

        llmRow(x, w, y, "llmBaseUrl", "端点（OpenAI 兼容）",
                "默认 https://api.deepseek.com/v1 ⇒ 实际 POST 到 {它}/chat/completions",
                LlmClient.DEFAULT_BASE_URL);
        y += ROW_H;
        llmRow(x, w, y, "llmModel", "模型", "例如 deepseek-chat", LlmClient.DEFAULT_MODEL);
        // ★★ 第十二轮补：LLM 页要有【自己的】端点预设 —— 委托方反馈
        //   「llm 的 api 界面那里的按钮选项还是『使用 jev 官方/中转』不太对应」。
        //   ① 页的预设是**评分模型（JEV 中转站）**的；这一页是**chat 模型**，服务商不是一回事。
        int presetY = y + FIELD_DY;
        addRenderableWidget(Button.builder(Component.literal("DeepSeek 官方"), b -> {
            draft.put("llmBaseUrl", "https://api.deepseek.com/v1");
            draft.put("llmModel", "deepseek-chat");
            rebuildWidgets();
        }).bounds(x + w - 240, presetY, 112, 18).build());
        addRenderableWidget(Button.builder(Component.literal("OpenAI"), b -> {
            draft.put("llmBaseUrl", "https://api.openai.com/v1");
            draft.put("llmModel", "gpt-4o-mini");
            rebuildWidgets();
        }).bounds(x + w - 124, presetY, 60, 18).build());
        addRenderableWidget(Button.builder(Component.literal("自定义"), b -> {
            draft.put("llmBaseUrl", "");
            rebuildWidgets();
        }).bounds(x + w - 60, presetY, 60, 18).build());
        y += ROW_H;

        // ── 系统提示词（这一页的重点）──
        labels.add(new Label(Component.literal(
                "§e系统提示词§7（动态指挥真正的接口；留空 = 内置提示词）"), x, y + ROW_H - 8));
        int boxTop = y + ROW_H + FIELD_DY - 6;
        int boxH = Math.max(40, this.height - boxTop - 64);
        llmPromptBox = new net.minecraft.client.gui.components.MultiLineEditBox(
                this.font, x, boxTop, w, boxH,
                Component.literal("留空 = 用内置提示词（要求模型只输出一个 JSON 对象）"),
                Component.literal("LLM 系统提示词"));
        llmPromptBox.setCharacterLimit(8000);
        llmPromptBox.setValue(draft.getOrDefault("llmPrompt", ""));
        llmPromptBox.setValueListener(v -> draft.put("llmPrompt", v));
        addRenderableWidget(llmPromptBox);
        // ★ 让编译器知道 by 用到了（按钮排在提示词框下面）
        int btnY = Math.min(boxTop + boxH + 4, this.height - 32);
        addRenderableWidget(Button.builder(Component.literal("填入内置提示词"), b -> {
            llmPromptBox.setValue(com.touhoulittlemad.fightlikeplayer.decision.thinking
                    .LlmAdvisor.DEFAULT_PROMPT);
            draft.put("llmPrompt", llmPromptBox.getValue());
        }).bounds(x, btnY, 110, 18).build());
        addRenderableWidget(Button.builder(Component.literal("测试 LLM 连接"), b -> testLlm())
                .bounds(x + 114, btnY, 110, 18).build());
        addRenderableWidget(Button.builder(
                        Component.literal("开/关（当前 " + (Boolean.TRUE.equals(flags.get("llmEnabled"))
                                ? "开" : "关") + "）"),
                        b -> {
                            flags.put("llmEnabled", !Boolean.TRUE.equals(flags.get("llmEnabled")));
                            rebuildWidgets();
                        })
                .bounds(x + 228, btnY, 120, 18).build());
    }

    /** LLM 页的一行单行框。 */
    private void llmRow(int x, int w, int y, String key, String label, String hint, String def) {
        labels.add(new Label(Component.literal(label), x, y));
        EditBox box = new EditBox(this.font, x, y + FIELD_DY, w, 18, Component.literal(label));
        box.setMaxLength(240);
        box.setValue(draft.getOrDefault(key, def));
        box.setHint(Component.literal(hint));
        box.setResponder(v -> draft.put(key, v));
        addRenderableWidget(box);
    }

    private boolean llmKeyVisible;      /** ④ 页 key 的明文开关（与 ① 页互不影响）。 */

    /**
     * ★★ <b>测试 LLM 连接</b> —— 与 ① 页那个按钮同构，但打的是 <b>chat/completions</b>。
     * <p>★ 为什么两个都要：两家端点/两份 key，用 JEV 的测试按钮测不出 LLM 的配置对不对。
     */
    private void testLlm() {
        String url = draft.getOrDefault("llmBaseUrl", LlmClient.DEFAULT_BASE_URL).trim();
        String key = draft.getOrDefault("llmApiKey", "").trim();
        String model = draft.getOrDefault("llmModel", LlmClient.DEFAULT_MODEL).trim();
        String proxy = draft.getOrDefault("llmProxy", "").trim();
        if (key.isEmpty()) {
            testState = TestState.done(true, "§cLLM 的 API Key 是空的 —— 先在上面那一行输入");
            return;
        }
        testState = TestState.running();
        long t0 = System.currentTimeMillis();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                LlmClient c = new LlmClient(key, url, model,
                        proxy.isEmpty() ? null : proxy, 25);
                String reply = c.chat("你是一个连接测试。只回复两个字：正常", "请确认收到。");
                String head = reply == null ? "(空)" : reply.strip();
                if (head.length() > 40) {
                    head = head.substring(0, 40) + "…";
                }
                return "§a✅ LLM 连接成功（" + (System.currentTimeMillis() - t0) + " ms，"
                        + model + "）回复：" + head;
            } catch (Throwable t) {
                return "§c❌ LLM 失败：" + explain(t);
            }
        }).thenAccept(msg -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> testState = TestState.done(true, msg));
            } else {
                testState = TestState.done(true, msg);
            }
        });
    }

    // ───────────────────────── 控件工厂 ─────────────────────────

    /**
     * 控件区的左上角 x —— ★★ 与标签<b>同一左边界</b>（第十二轮：上下排布，不再左右挤占）。
     */
    private int fieldX() {
        return Math.max(6, this.width / 2 - 170);
    }

    /** 控件宽度（★ 占满右侧可用宽度，随窗口收缩）。 */
    private int fieldW() {
        return Math.min(340, Math.max(80, this.width - fieldX() - 12));
    }

    private Row text(String key, String label, String hint) {
        EditBox box = new EditBox(this.font, fieldX(), rowYFor(key), fieldW(), 18,
                Component.literal(label));
        box.setMaxLength(240);
        box.setValue(draft.getOrDefault(key, ""));
        box.setHint(Component.literal(hint));
        box.setResponder(v -> draft.put(key, v));
        return new Row(Component.literal(label), box);
    }

    /**
     * ★ API Key 行 —— <b>永远可编辑</b>；不显示时用 {@code formatter} 打码。
     *
     * <h2>★★ 2026-10-01 第十轮修：之前它<b>输不进字</b>（委托方实测「配置界面输入不了 llm 的 apikey」）</h2>
     * 旧实现是"隐藏时把框设为 {@code setEditable(false)}" ⇒ 用户看到的是一个**能点、能聚焦、
     * 但打不进去任何字**的输入框 —— 而且没有任何提示告诉他要点右边的「显示」。
     * <p>⇒ 现在的做法是<b>框永远可编辑</b>，只在**没聚焦且没点显示**时用
     * {@link EditBox#setFormatter} 把显示内容打码：
     * <ul>
     *   <li>点进去（聚焦）⇒ 自动显示真实值 ⇒ 可以直接改，
     *       无需先点「显示」（那个按钮仍然保留，作为"我就想一直看明文"的开关）；</li>
     *   <li>★ formatter <b>只影响渲染，不影响 {@code getValue()}</b>
     *       —— 旧注释担心的"把掩码当密钥存进去"在 formatter 用法下不会发生
     *       （那时担心的是拿 formatter 去做"取值"，那是另一回事）。</li>
     * </ul>
     */
    private Row apiKeyRow() {
        int y = rowYFor("apiKey");
        EditBox box = new EditBox(this.font, fieldX(), y, fieldW() - 46, 18,
                Component.literal("API Key"));
        box.setMaxLength(240);
        box.setValue(apiKeyReal);
        box.setEditable(true);                     // ★★ 永远可编辑（修"输不进字"）
        box.setResponder(v -> apiKeyReal = v);
        // ★ 只改渲染：没聚焦、也没点"显示"时打码
        //   ⚠️ 1.20.1 的 setFormatter 要的是 FormattedCharSequence（不是 Component）——
        //   写成 Component 会报 "bad return type in lambda expression"（本轮踩过）。
        box.setFormatter((text, cursor) -> net.minecraft.util.FormattedCharSequence.forward(
                (apiKeyVisible || box.isFocused()) ? text : mask(text),
                net.minecraft.network.chat.Style.EMPTY));
        box.setHint(Component.literal("点这里直接输入；回车/点击别处即保存草稿"));
        Button eye = Button.builder(
                        Component.literal(apiKeyVisible ? "隐藏" : "显示"),
                        b -> {
                            apiKeyVisible = !apiKeyVisible;
                            rebuildWidgets();
                        })
                .bounds(fieldX() + fieldW() - 42, y, 42, 18).build();

        // ★ 这一行有两个控件 ⇒ 按钮直接挂到屏幕上（与文本框横向不重叠）
        addRenderableWidget(eye);
        // ★★ 第十一轮：标签写明"在这里输入"，并在框里给出引导语
        //   （委托方实测「看不见 apikey 在哪填」⇒ 光有一个写着 API Key 的框是不够的）
        return new Row(Component.literal("§eAPI Key§7（必填 · 点右边的框直接打字）"), box);
    }

    private Row toggle(String key, String label, String hint) {
        Button b = Button.builder(stateLabel(key), btn -> {
            setFlag(key, !isFlag(key));
            btn.setMessage(stateLabel(key));
        }).bounds(fieldX(), rowYFor(key), fieldW(), 18).build();
        return new Row(Component.literal(label + " §7(" + hint + ")"), b);
    }

    /** 行号 → y（★ 与 {@code init()} 里的遍历顺序保持一致）。 */
    private int rowYFor(String key) {
        int idx = rowIndex(key);
        return ROW_TOP + idx * ROW_H + FIELD_DY;      // ★ 输入框画在标签下面
    }

    private static int rowIndex(String key) {
        return switch (key) {
            // ★★ 第十一轮重排（Key 提到第一行）：这里的行号必须与 buildPage 的 add 顺序**逐个对应**
            case "apiKey" -> 0;
            case "enabled" -> 1;
            case "baseUrl" -> 2;
            case "model" -> 3;
            case "proxy" -> 4;
            case "intervalTicks" -> 0;
            case "timeoutSeconds" -> 1;
            case "biasStrength" -> 2;
            case "minConfidence" -> 3;
            case "applyMode" -> 4;
            case "onlyInCombat" -> 0;
            case "logDecisions" -> 1;
            case "maxInFlight" -> 2;
            default -> 0;
        };
    }

    private boolean isFlag(String key) {
        if ("applyMode".equals(key)) {
            return "bias".equalsIgnoreCase(draft.getOrDefault("applyMode", "bias"));
        }
        return Boolean.TRUE.equals(flags.get(key));
    }

    private void setFlag(String key, boolean value) {
        if ("applyMode".equals(key)) {
            draft.put("applyMode", value ? "bias" : "observe");
        } else {
            flags.put(key, value);
        }
    }

    private Component stateLabel(String key) {
        if ("applyMode".equals(key)) {
            return Component.literal("bias".equalsIgnoreCase(draft.getOrDefault("applyMode", "bias"))
                    ? "推弹簧 (bias)" : "只观测 (observe)");
        }
        return Component.literal(isFlag(key) ? "§a开" : "§c关");
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "(未配置)";
        }
        if (key.length() <= 8) {
            return "••••••••";
        }
        return key.substring(0, 5) + "••••••" + key.substring(key.length() - 4);
    }

    // ───────────────────────── 交互 ─────────────────────────

    private void switchPage(int delta) {
        page = (page + delta + PAGES) % PAGES;
        error = null;
        rebuildWidgets();
    }

    private void restoreDefaults() {
        draft.put("baseUrl", JevClient.DEFAULT_BASE_URL);
        draft.put("model", JevClient.DEFAULT_MODEL);
        draft.put("proxy", "");
        draft.put("intervalTicks", "30");
        draft.put("timeoutSeconds", "30");
        draft.put("biasStrength", "0.35");
        draft.put("minConfidence", "0.0");
        draft.put("maxInFlight", "2");
        draft.put("applyMode", "bias");
        flags.put("onlyInCombat", Boolean.TRUE);
        flags.put("logDecisions", Boolean.TRUE);
        flags.put("enabled", Boolean.FALSE);
        apiKeyReal = "";
        apiKeyVisible = false;
        draft.put("llmApiKey", "");
        draft.put("llmBaseUrl", LlmClient.DEFAULT_BASE_URL);
        draft.put("llmModel", LlmClient.DEFAULT_MODEL);
        draft.put("llmProxy", "");
        draft.put("llmPrompt", "");
        draft.put("llmInterval", "1200");
        draft.put("llmApplyMode", "observe");
        flags.put("llmEnabled", Boolean.FALSE);
        flags.put("toolDirectives", Boolean.TRUE);
        draft.put("thinkPrompt", "");
        draft.put("advisorPrompt", "");
        error = "已恢复为默认值（★ 尚未保存，点「保存并关闭」才会写入）";
        rebuildWidgets();
    }

    /** ★ 校验 + 写入 + 落盘；任一步失败都<b>不关闭界面</b>。 */
    private void save() {
        String problem = validate();
        if (problem != null) {
            error = "§c" + problem;
            return;
        }
        try {
            FlpConfig.THINK_ENABLED.set(Boolean.TRUE.equals(flags.get("enabled")));
            FlpConfig.THINK_BASE_URL.set(draft.getOrDefault("baseUrl", JevClient.DEFAULT_BASE_URL).trim());
            FlpConfig.THINK_API_KEY.set(apiKeyReal == null ? "" : apiKeyReal.trim());
            FlpConfig.THINK_MODEL.set(draft.getOrDefault("model", JevClient.DEFAULT_MODEL).trim());
            FlpConfig.THINK_PROXY.set(draft.getOrDefault("proxy", "").trim());
            FlpConfig.THINK_INTERVAL_TICKS.set(parseInt("intervalTicks", 30));
            FlpConfig.THINK_TIMEOUT_SECONDS.set(parseInt("timeoutSeconds", 30));
            FlpConfig.THINK_BIAS_STRENGTH.set(parseDouble("biasStrength", 0.35));
            FlpConfig.THINK_MIN_CONFIDENCE.set(parseDouble("minConfidence", 0.0));
            FlpConfig.THINK_MAX_IN_FLIGHT.set(parseInt("maxInFlight", 2));
            FlpConfig.THINK_APPLY_MODE.set("bias".equalsIgnoreCase(
                    draft.getOrDefault("applyMode", "bias")) ? "bias" : "observe");
            FlpConfig.THINK_ONLY_IN_COMBAT.set(Boolean.TRUE.equals(flags.get("onlyInCombat")));
            FlpConfig.THINK_LOG_DECISIONS.set(Boolean.TRUE.equals(flags.get("logDecisions")));
            // ★★ 提示词（第 ④ 页）：原样存；解析与回退规则在执行侧（PromptOverrides）
            FlpConfig.THINK_QUESTION_OVERRIDES.set(draft.getOrDefault("thinkPrompt", "").strip());
            FlpConfig.ADVISOR_QUESTION_OVERRIDES.set(draft.getOrDefault("advisorPrompt", "").strip());
            // ★★ LLM 动态指挥（第 ④ 页）
            FlpConfig.LLM_ENABLED.set(Boolean.TRUE.equals(flags.get("llmEnabled")));
            FlpConfig.LLM_API_KEY.set(draft.getOrDefault("llmApiKey", "").trim());
            FlpConfig.LLM_BASE_URL.set(draft.getOrDefault("llmBaseUrl",
                    LlmClient.DEFAULT_BASE_URL).trim());
            FlpConfig.LLM_MODEL.set(draft.getOrDefault("llmModel", LlmClient.DEFAULT_MODEL).trim());
            FlpConfig.LLM_PROXY.set(draft.getOrDefault("llmProxy", "").trim());
            FlpConfig.LLM_SYSTEM_PROMPT.set(draft.getOrDefault("llmPrompt", ""));
            FlpConfig.LLM_INTERVAL_TICKS.set(parseIntOr("llmInterval", 1200));
            FlpConfig.LLM_APPLY_MODE.set("apply".equalsIgnoreCase(
                    draft.getOrDefault("llmApplyMode", "observe")) ? "apply" : "observe");
            // ★★ M5：女仆对话里能不能下令（默认开）
            FlpConfig.LLM_TOOL_DIRECTIVES.set(Boolean.TRUE.equals(flags.get("toolDirectives")));
            FlpConfig.SPEC.save();
            FlpClientSetup.onConfigSaved();      // ★ 让按旧配置构造的 HTTP 客户端失效
        } catch (RuntimeException e) {
            error = "§c保存失败：" + e;
            return;
        }
        onClose();
    }

    private String validate() {
        String url = draft.getOrDefault("baseUrl", "").trim();
        if (url.isEmpty()) {
            return "端点 baseUrl 不能为空。";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "端点 baseUrl 必须以 http:// 或 https:// 开头（当前：" + url + "）。";
        }
        if (draft.getOrDefault("model", "").isBlank()) {
            return "模型 model 不能为空。";
        }
        String proxy = draft.getOrDefault("proxy", "").trim();
        if (!proxy.isEmpty()) {
            String[] hp = proxy.split(":");
            if (hp.length != 2) {
                return "代理格式应为 host:port（当前：" + proxy + "）。";
            }
            try {
                Integer.parseInt(hp[1].trim());
            } catch (NumberFormatException e) {
                return "代理端口不是数字：" + hp[1];
            }
        }
        String[] ranges = {
                "intervalTicks 必须是 1~1200 的整数", "timeoutSeconds 必须是 1~300 的整数",
                "maxInFlight 必须是 1~32 的整数"};
        String[] keys = {"intervalTicks", "timeoutSeconds", "maxInFlight"};
        int[][] lim = {{1, 1200}, {1, 300}, {1, 32}};
        for (int i = 0; i < keys.length; i++) {
            try {
                int v = Integer.parseInt(draft.getOrDefault(keys[i], "").trim());
                if (v < lim[i][0] || v > lim[i][1]) {
                    return ranges[i] + "（当前：" + v + "）。";
                }
            } catch (NumberFormatException e) {
                return ranges[i] + "（当前：" + draft.getOrDefault(keys[i], "") + "）。";
            }
        }
        double strength = numOrNaN("biasStrength");
        if (Double.isNaN(strength) || strength < 0.0 || strength > 2.0) {
            return "偏置强度必须是 0~2 的数（当前：" + draft.getOrDefault("biasStrength", "") + "）。";
        }
        double conf = numOrNaN("minConfidence");
        if (Double.isNaN(conf) || conf < 0.0 || conf > 1.0) {
            return "置信度门槛必须是 0~1 的数（当前：" + draft.getOrDefault("minConfidence", "") + "）。";
        }
        if (Boolean.TRUE.equals(flags.get("enabled")) && (apiKeyReal == null || apiKeyReal.isBlank())) {
            return "打开了总开关但 API Key 为空 —— 这样不会发出任何请求。"
                    + "请填 Key，或把总开关关掉。";
        }
        // ★★ 提示词：试解析一遍，把无法识别的键【当场】说出来（而不是让它静默失效）
        var thinkParsed = com.touhoulittlemad.fightlikeplayer.decision.thinking.PromptOverrides
                .parseForThink(draft.getOrDefault("thinkPrompt", ""));
        if (!thinkParsed.ignoredKeys().isEmpty()) {
            return "思维层提示词里有无法识别的行：" + String.join(", ", thinkParsed.ignoredKeys())
                    + "（合法轴 id 见「填入模板」按钮）";
        }
        var advisorParsed = com.touhoulittlemad.fightlikeplayer.decision.thinking.PromptOverrides
                .parseForAdvisor(draft.getOrDefault("advisorPrompt", ""));
        if (!advisorParsed.ignoredKeys().isEmpty()) {
            return "顾问提示词里有无法识别的行：" + String.join(", ", advisorParsed.ignoredKeys())
                    + "（合法旋钮名见「填入模板」按钮）";
        }
        return null;
    }

    /** 同 {@link #parseInt}，但带范围钳制（LLM 间隔必须是正数）。 */
    private int parseIntOr(String key, int fallback) {
        int v = parseInt(key, fallback);
        return v <= 0 ? fallback : v;
    }

    private int parseInt(String key, int fallback) {
        try {
            return Integer.parseInt(draft.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double parseDouble(String key, double fallback) {
        try {
            return Double.parseDouble(draft.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double numOrNaN(String key) {
        try {
            return Double.parseDouble(draft.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.literal("§7" + PAGE_TITLES[page] + "　（第 " + (page + 1) + " / " + PAGES + " 页）"),
                this.width / 2, 18, 0xA0A0A0);

        for (Label row : labels) {
            g.drawString(this.font, row.text(), row.x(), row.y(), 0xE0E0E0);
        }

        // ★★ 第十二轮：状态行挪到【页眉下面】（原来画在 height-44/-32，会压住最后一行的输入框）
        int statusX = Math.max(6, this.width / 2 - 170);
        String base = draft.getOrDefault("baseUrl", JevClient.DEFAULT_BASE_URL).trim();
        String status = FlpConfig.thinkConfigured()
                ? "§a已就绪：" + JevClient.styleOf(base)
                : (Boolean.TRUE.equals(flags.get("enabled"))
                ? "§e已开开关但 Key 为空 ⇒ 仍不会联网"
                : "§7开关关闭 ⇒ 不会联网");
        g.drawString(this.font, Component.literal(status), statusX, 30, 0xFFFFFF);

        // ★★ 「实际会 POST 到哪」直接写在界面上 —— 委托方第十二轮说「端点完全错了」，
        //    而两家中转站的路径不同（JEV: {base}/responses ｜ DMXAPI: {base}/typesafe/v1/systemone），
        //    看不见这一行就只能靠猜。⇒ 这一行是"端点对不对"的唯一可视证据。
        g.drawString(this.font,
                Component.literal("§7会 POST 到 §f" + JevClient.endpointFor(base)),
                statusX, 42, 0xFFFFFF);

        // ★★ 「测试连接」的结果 —— 直接画在界面上（不必去翻日志）
        TestState ts = testState;
        if (ts != null) {
            g.drawString(this.font, Component.literal(ts.message()), statusX, 54, 0xFFFFFF);
        }

        if (error != null) {
            g.drawString(this.font, Component.literal(error), statusX, this.height - 42, 0xFF8080);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // ★ 暂停世界：配置界面不该在女仆打架时被后台 tick 干扰（也便于看清即时效果）
        return true;
    }
}
