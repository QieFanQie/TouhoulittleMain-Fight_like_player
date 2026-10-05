package com.touhoulittlemad.fightlikeplayer.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * <b>模组配置</b> —— 委托方要求：<b>「要写好 API 配置接口，做到在 MC 的模组配置界面配置。」</b>
 *
 * <h2>为什么是 {@code ForgeConfigSpec}</h2>
 * 只有用它注册的配置才会：① 自动落盘成 {@code config/fight_like_player-common.toml}；
 * ② 出现在模组列表的 <b>Config</b> 按钮后面（见下）；③ 支持 {@code /reload} 之外的热改。
 *
 * <h2>★★ 一个必须写下来的取证结论（差点做错）</h2>
 * 我原本以为"用 {@code ForgeConfigSpec} 注册配置，Forge 会自动给一个配置界面"。
 * <b>在 Forge 47.3.22 上这是错的</b> —— 反汇编 {@code forge-1.20.1-47.3.22-universal.jar}
 * 后确认：整个 Forge 里与"配置界面"相关的类<b>只有</b>
 * {@code net.minecraftforge.client.ConfigScreenHandler}（+ 内部 record {@code ConfigScreenFactory}），
 * 而它只是个<b>扩展点</b>：
 * <pre>
 *   getScreenFactoryFor(IModInfo) → mc.getCustomExtension(ConfigScreenFactory.class)
 * </pre>
 * ⇒ <b>不注册这个扩展点，模组列表里根本不会出现 Config 按钮。</b>
 * （内置的 {@code ConfigurationScreen} 是 NeoForge / 更高版本才有的东西，这里不存在。）
 * ⇒ 因此本项目自带一个配置界面：{@code compat/client/FlpConfigScreen.java}，
 * 由 {@code compat/client/FlpClientSetup.java} 在<b>仅客户端</b>注册。
 *
 * <h2>★ 安全默认</h2>
 * {@link #THINK_ENABLED} 默认 <b>false</b>、{@link #THINK_API_KEY} 默认空
 * ⇒ <b>开箱不会发出任何网络请求</b>。要启用必须显式打开开关并填 key。这是刻意的：
 * 一个行为逻辑模组不该在用户不知情时把局势数据发到外部服务。
 *
 * @see com.touhoulittlemad.fightlikeplayer.decision.thinking.JevClient
 * @see <a href="../../../../../../docs/11-感知层与思维层设计.md">docs/11 §5</a>
 */
public final class FlpConfig {

    private FlpConfig() {
    }

    public static final ForgeConfigSpec SPEC;

    // ───────────────────────── 思维层（短周期 JEV）─────────────────────────

    /** 总开关。 */
    public static final ForgeConfigSpec.BooleanValue THINK_ENABLED;

    /** 端点前缀（要带 {@code /v1}）。 */
    public static final ForgeConfigSpec.ConfigValue<String> THINK_BASE_URL;

    /** API Key。⚠️ 明文存在 config 里 —— 单机/自用可以，别提交到仓库。 */
    public static final ForgeConfigSpec.ConfigValue<String> THINK_API_KEY;

    /** 模型名。 */
    public static final ForgeConfigSpec.ConfigValue<String> THINK_MODEL;

    /** 代理，形如 {@code 127.0.0.1:7897}；留空 = 不使用代理。 */
    public static final ForgeConfigSpec.ConfigValue<String> THINK_PROXY;

    /** 提交间隔（tick）。20 tick = 1 秒。 */
    public static final ForgeConfigSpec.IntValue THINK_INTERVAL_TICKS;

    /** 单次请求超时（秒）。超时 ⇒ 本包丢弃，沿用旧弹簧（绝不阻塞决策）。 */
    public static final ForgeConfigSpec.IntValue THINK_TIMEOUT_SECONDS;

    /** ★ 偏置强度：把 JEV 的 [-1,1] 打分缩放成弹簧偏置的倍数。 */
    public static final ForgeConfigSpec.DoubleValue THINK_BIAS_STRENGTH;

    /** 置信度门槛（0 = 一律采用）。 */
    public static final ForgeConfigSpec.DoubleValue THINK_MIN_CONFIDENCE;

    /** 只在战斗任务中调用（默认 true，避免女仆发呆时白烧额度）。 */
    public static final ForgeConfigSpec.BooleanValue THINK_ONLY_IN_COMBAT;

    /** 同时在途的请求上限（>1 时不同女仆可并行）。 */
    public static final ForgeConfigSpec.IntValue THINK_MAX_IN_FLIGHT;

    /** 是否把每次判定写进日志（debug 级）。 */
    public static final ForgeConfigSpec.BooleanValue THINK_LOG_DECISIONS;

    /** 判定结果的存放位置：{@code bias}（推弹簧）或 {@code observe}（只记录不推）。 */
    public static final ForgeConfigSpec.ConfigValue<String> THINK_APPLY_MODE;

    /**
     * ★★ <b>提示词覆写</b>（思维层）—— 委托方要求「配置界面可以编辑提示词」。
     *
     * <p>格式（多行）：{@code <轴 id> = <问法>}；{@code #} 开头是注释，空行忽略。
     * 没写的轴<b>沿用内置问法</b>；写错的键<b>被丢弃并在日志里点名</b>。
     * 合法键见 {@code PromptOverrides.templateForThink()} 输出的清单。
     */
    public static final ForgeConfigSpec.ConfigValue<String> THINK_QUESTION_OVERRIDES;

    // ───────────────────────── ★★ 调控顾问（LLM 调风格）─────────────────────────

    /** 顾问总开关（默认 false）。 */
    public static final ForgeConfigSpec.BooleanValue ADVISOR_ENABLED;

    /** 顾问提问间隔（tick，默认 600 = 30 秒）。 */
    public static final ForgeConfigSpec.IntValue ADVISOR_INTERVAL_TICKS;

    /** 顾问结果的去向：{@code observe}（只记录，默认）或 {@code apply}（真的改旋钮）。 */
    public static final ForgeConfigSpec.ConfigValue<String> ADVISOR_APPLY_MODE;

    /** 长时间不战斗时是否把顾问调过的旋钮恢复默认。 */
    public static final ForgeConfigSpec.BooleanValue ADVISOR_RESET_ON_IDLE;

    /** ★★ 提示词覆写（顾问层）：{@code <旋钮名> = <问法>}，见 {@link #THINK_QUESTION_OVERRIDES}。 */
    public static final ForgeConfigSpec.ConfigValue<String> ADVISOR_QUESTION_OVERRIDES;

    // ───────────────────────── ★★ Goety 引导长度 ─────────────────────────

    /**
     * ★★ <b>蓄力长按类法术的"持续预算"</b>（tick）—— 委托方第九轮实测驱动。
     *
     * <p>腐化/水蛭这类聚晶的源码默认值是 {@code castUp = 0、Duration = 0}（**0 = 无限**），
     * 玩家侧语义是「按住不放就一直放，松手才停」⇒ 长度由玩家决定。
     * 女仆没有"手"，所以长度必须由我们给：<b>这个旋钮就是它</b>。
     * <p>引导总长 = {@code castUp + sustainTicks}（夹在 20~200 tick），
     * 并且<b>目标一死就立刻收</b>（那才是"持续时间长"的正确语义）。
     */
    public static final ForgeConfigSpec.IntValue GOETY_SUSTAIN_TICKS;

    // ───────────────────────── ★★ 枪械：以"一梭子"为单位 ─────────────────────────

    /**
     * ★★ <b>枪械一次动作最多打几发</b>（委托方第九轮：「冲锋枪、步枪这类枪械，
     * 我希望以打完一梭子为单位，现在是每次打两三发」）。
     */
    public static final ForgeConfigSpec.IntValue GUN_MAGAZINE_SHOTS;

    /**
     * ★★ 一次枪械动作最多持续多少 tick（兜底：万一"一直在打"的判据出错，也不能永远打）。
     */
    public static final ForgeConfigSpec.IntValue GUN_MAGAZINE_TICKS;

    // ─────────────── ★★ LLM 动态指挥（委托方第十二轮点名要的那一半）───────────────

    /**
     * ★★ <b>LLM 动态指挥</b> —— 与上面那套 {@code thinking.*}（JEV）<b>不是一回事</b>：
     * <ul>
     *   <li>{@code thinking.*} ⇒ <b>JEV 打分模型</b>，短周期"此刻该往哪使劲"（8 轴向量推弹簧）；</li>
     *   <li>{@code llm.*} ⇒ <b>chat 模型</b>（deepseek 等），慢周期"她该是什么脾气"（几个标量旋钮），
     *       ★ <b>提示词可编辑</b>。</li>
     * </ul>
     * ★ 默认关闭：一个行为模组不该在用户不知情时把局势数据发到外部服务。
     */
    public static ForgeConfigSpec.BooleanValue LLM_ENABLED;

    /** LLM 端点（OpenAI 兼容）：实际会 POST 到 {@code {它}/chat/completions}。 */
    public static ForgeConfigSpec.ConfigValue<String> LLM_BASE_URL;

    /** LLM 的 API Key —— 与 JEV 的 key 是<b>两份</b>（两家通常不是同一个 key）。 */
    public static ForgeConfigSpec.ConfigValue<String> LLM_API_KEY;

    /** LLM 模型名，例如 {@code deepseek-chat}。 */
    public static ForgeConfigSpec.ConfigValue<String> LLM_MODEL;

    /** 代理，形如 {@code 127.0.0.1:7897}；留空 = 直连。 */
    public static ForgeConfigSpec.ConfigValue<String> LLM_PROXY;

    /** ★★ <b>系统提示词（可编辑）</b>—— 这是"动态指挥"真正的接口。留空 = 用内置提示词。 */
    public static ForgeConfigSpec.ConfigValue<String> LLM_SYSTEM_PROMPT;

    /** 提问间隔（tick，默认 1200 = 60 秒）。★ 慢变量：调太快既烧额度又会让她随模型抖动。 */
    public static ForgeConfigSpec.IntValue LLM_INTERVAL_TICKS;

    /** 超时（秒）。 */
    public static ForgeConfigSpec.IntValue LLM_TIMEOUT_SECONDS;

    /** ★ 索敌（产生仇恨）半径，格。 */
    public static final String COMBAT_TARGET_ACQUIRE_RANGE = "combat.targetAcquireRange";

    /** ★ 锁定（刚丢目标 3 秒内）半径，格。 */
    public static final String COMBAT_TARGET_KEEP_RANGE = "combat.targetKeepRange";

    /** ★ 脱战（连续 5 秒无仇恨对象）后是否自动解除持续指令。 */
    public static final String DIRECTIVE_CLEAR_ON_DISENGAGE = "directive.clearOnDisengage";

    /** 索敌（产生仇恨）半径。 */
    public static ForgeConfigSpec.IntValue TARGET_ACQUIRE_RANGE;

    /** 锁定半径（比索敌更大 ⇒ 目标稍微跑远也不丢）。 */
    public static ForgeConfigSpec.IntValue TARGET_KEEP_RANGE;

    /** 脱战是否自动解除指令（默认关）。 */
    public static ForgeConfigSpec.BooleanValue CLEAR_ON_DISENGAGE;

    /** ★ 击败强敌后主动说一句（第十七轮续，委托方第 2 条）。 */
    public static ForgeConfigSpec.BooleanValue LLM_BOSS_CHAT;

    /** ★ "boss 级"的最大生命值阈值（原版末影龙/凋灵无条件算）。 */
    public static ForgeConfigSpec.IntValue LLM_BOSS_HEALTH_THRESHOLD;

    /** 结果去向：{@code observe}（默认，只记录）或 {@code apply}（经白名单+钳制真的改旋钮）。 */
    public static ForgeConfigSpec.ConfigValue<String> LLM_APPLY_MODE;

    /** 是否把每次回复写进日志（便于调提示词）。 */
    public static ForgeConfigSpec.BooleanValue LLM_LOG;

    /**
     * ★★ 是否允许【女仆的对话大模型】通过 {@code flp_directive} 工具下达战斗指令（M5，第十三轮）。
     * <p>默认开：玩家跟她说「拉开距离」时，她真的会拉开 —— 走的是与指挥 LLM 同一条指令总线。
     */
    public static ForgeConfigSpec.BooleanValue LLM_TOOL_DIRECTIVES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        // ★★ 第十七轮续（委托方第 1、3 条）：索敌距离 + "脱战"行为。
        b.push("combat");
        TARGET_ACQUIRE_RANGE = b
                .comment("索敌（产生仇恨）半径，格。★ 只影响'她自己找目标'：已锁定的目标照打。")
                .defineInRange("targetAcquireRange", 24, 4, 64);
        TARGET_KEEP_RANGE = b
                .comment("锁定半径，格：她刚刚还有目标（3 秒内）时用这个更大的半径把目标找回来",
                        "  —— 避免'目标稍微跑远一点就不打了'。★ 应当 ≥ 上面那个。")
                .defineInRange("targetKeepRange", 40, 8, 96);
        b.pop();

        b.push("directive");
        CLEAR_ON_DISENGAGE = b
                .comment("★ 脱战（连续 5 秒没有仇恨对象）后自动解除全部持续指令。默认关。",
                        "  开着：脱战满 5 秒会把 keep_distance / only_item 这类指令一并撤掉并说明；",
                        "  ★ 无论开关如何，TTL（默认 2 分钟、上限 10 分钟）始终是硬上限。")
                .define("clearOnDisengage", false);
        b.pop();

        b.comment("── 思维层（短周期）：用 JEV 判定当前局势，产出一个 8 轴决策向量推弹簧 ──",
                "★ 默认关闭。打开后需要填 API Key 才会真正发请求。",
                "★ 它只是【增强】：关掉它，行为退回「态势规则 + 弹簧（ContextBias）」那一层，不会变坏。")
                .push("thinking");

        THINK_ENABLED = b
                .comment("总开关。false = 完全不联网（默认）。")
                .define("enabled", false);

        THINK_BASE_URL = b
                .comment("端点。★ 两家常用中转站的【路径不同】，填错就会一直失败：",
                        "  ① JEV 官方（api.new.bi）：填 https://api.new.bi/v1",
                        "     ⇒ 实际会 POST 到 {上面的值}/responses（请求体含 input 占位符）",
                        "  ② DMXAPI（www.dmxapi.cn）：填它文档里的完整路径 https://www.dmxapi.cn/typesafe/v1/systemone",
                        "     ⇒ 原样使用（请求体按它的形状：model/state/questions，不含 input）",
                        "★ 配置界面第 ① 页有两家的【预设按钮】，并有「会 POST 到 …」一行显示实际地址；",
                        "  点「测试连接」可以当场验证（成功会显示往返毫秒数）。",
                        "★ 实测记录：key sk-2VMz…E15 在 api.new.bi 上返回 HTTP 200；在 dmxapi.cn 上返回 401")
                .define("baseUrl", "https://api.new.bi/v1");

        THINK_API_KEY = b
                .comment("API Key（Bearer 令牌）。留空 = 视为未配置，不会发请求。",
                        "⚠️ 明文存放在 config/fight_like_player-common.toml 里，请勿把该文件提交到公开仓库。")
                .define("apiKey", "");

        THINK_MODEL = b
                .comment("模型名。JEV 目前是 jev-1.13.0（别名 jev-latest 需在控制台放行）。")
                .define("model", "jev-1.13.0");

        THINK_PROXY = b
                .comment("HTTP 代理，形如 127.0.0.1:7897。留空 = 直连。")
                .define("proxy", "");

        THINK_INTERVAL_TICKS = b
                .comment("提交间隔（tick）。20 tick = 1 秒。委托方要求 1~1.5 秒一次 ⇒ 20~30。",
                        "⚠️ 它比决策周期（5 tick）慢得多 —— 这是刻意的：思维层是【慢变量】，",
                        "   每个 tick 都问一次既没意义又烧额度。")
                .defineInRange("intervalTicks", 30, 1, 20 * 60);

        THINK_TIMEOUT_SECONDS = b
                .comment("单次请求超时（秒）。超时即丢弃该包，弹簧沿用上一个结果。")
                .defineInRange("timeoutSeconds", 30, 1, 300);

        THINK_BIAS_STRENGTH = b
                .comment("★ 偏置强度：JEV 的打分归一到 [-1,1] 后乘上这个数，再加到弹簧点上。",
                        "轴的上限多为 1.0（生存类 2.0）⇒ 0.3~0.5 是「明显推一把但不接管」的量级。",
                        "★ 0 = 只观测不推动（配合 applyMode=observe 用）。")
                .defineInRange("biasStrength", 0.35, 0.0, 2.0);

        THINK_MIN_CONFIDENCE = b
                .comment("置信度门槛：min(各轴 confidence) 低于它就整包丢弃（0 = 一律采用）。",
                        "★ 这是「低置信度不冒险」的落点。")
                .defineInRange("minConfidence", 0.0, 0.0, 1.0);

        THINK_ONLY_IN_COMBAT = b
                .comment("只在战斗任务中调用（推荐 true）。false = 女仆任何时候都可能被询问。")
                .define("onlyInCombat", true);

        THINK_MAX_IN_FLIGHT = b
                .comment("同时在途的请求上限。1 = 全局串行（最省额度）。")
                .defineInRange("maxInFlight", 2, 1, 32);

        THINK_LOG_DECISIONS = b
                .comment("把每次判定（局势摘要 + 原始返回 + 生成的向量）写进日志（debug 级）。")
                .define("logDecisions", true);

        THINK_APPLY_MODE = b
                .comment("结果的去向：",
                        "  bias    = 发布 SpringImpact.bias 推弹簧（默认）",
                        "  observe = 只记录，不推弹簧（用于「先看它判断得准不准」）")
                .defineInList("applyMode", "bias", List.of("bias", "observe"));

        THINK_QUESTION_OVERRIDES = b
                .comment("★★ 提示词覆写（每行一条：<轴 id> = <问法>；# 开头是注释）。",
                        "合法轴 id（8 条）：single_damage / area_damage / heal_survival /",
                        "  mitigation_survival / control / mobility / reinforce / ally_care",
                        "★ 没写的轴沿用内置问法 ⇒ 只想改一条就只写一条。",
                        "★ 只改【问法】，不改等级与映射：等级数量一变，score→向量 的分段映射就错了。",
                        "★ 写错的键会被丢弃，并在日志里点名（不静默失效）。",
                        "⚠️ 覆写会立刻影响下一次提问（间隔 = intervalTicks），无需重启。")
                .define("questionOverrides", "");

        b.pop();

        // ── ★★ 调控顾问（LLM 决定"风格"，不决定"战斗"）──
        b.comment("── 调控顾问：让模型看【一段时间的行为统计】，只调几个标量旋钮 ──",
                "★ 它【不】决定这一步做什么（那是短周期判定与决策层的活）。",
                "★ 默认关闭、默认只观测。详见 docs/11 §5.1c。")
                .push("advisor");

        ADVISOR_ENABLED = b
                .comment("总开关。默认 false —— 顾问是增强，不是必需。")
                .define("enabled", false);

        ADVISOR_INTERVAL_TICKS = b
                .comment("多久问一次（tick）。20 tick = 1 秒。★ 调控是【慢变量】⇒ 默认 600（30 秒）。",
                        "调太勤会：① 烧额度 ② 让体系随模型抖动，反而更不稳定。")
                .defineInRange("intervalTicks", 600, 20, 20 * 60 * 30);

        ADVISOR_APPLY_MODE = b
                .comment("结果的去向：",
                        "  observe = 只把「模型想怎么调」写进日志，不真的改旋钮（★ 默认，建议先用几天）",
                        "  apply   = 经白名单 + 钳制后真的写进 TuningBus")
                .defineInList("applyMode", "observe", List.of("observe", "apply"));

        ADVISOR_RESET_ON_IDLE = b
                .comment("长时间没在战斗时，把顾问调过的旋钮恢复默认（避免「战后的脾气」留在和平时期）。")
                .define("resetOnIdle", false);

        ADVISOR_QUESTION_OVERRIDES = b
                .comment("★★ 提示词覆写（每行一条：<旋钮名> = <问法>；# 开头是注释）。",
                        "合法旋钮名（5 条）：satisfaction.damage / satisfaction.all / cooldown.scale /",
                        "  bias.aggression / bias.caution",
                        "★ 格式与回退规则与 thinking.questionOverrides 完全相同。")
                .define("questionOverrides", "");

        b.pop();

        // ── ★★ Goety 引导长度（第九轮实测驱动）──
        b.comment("── Goety 法术：引导长度 ──",
                "★ 腐化/水蛭这类聚晶在源码里是『按住不放就一直放，松手才停』（Duration=0 表示无限）",
                "⇒ 女仆没有手，长度必须由这里给。★ 目标一死会立刻收，所以这个值只是上限。")
                .push("goety");

        GOETY_SUSTAIN_TICKS = b
                .comment("蓄力长按类法术的持续预算（tick）。20 tick = 1 秒。",
                        "引导总长 = 法术自己的前摇 castUp + 这个值（夹在 20~200 tick）。",
                        "★ 太小 ⇒ 光束/持续伤害刚出来就收（观感「放了但立刻停」）；",
                        "★ 太大 ⇒ 她会站桩很久（但目标一死就收，所以主要影响「打活靶」的时长）。")
                .defineInRange("sustainTicks", 60, 0, 180);

        b.pop();

        // ── ★★ 枪械：以"一梭子"为单位 ──
        b.comment("── 枪械：一次动作打多少 ──",
                "★ 委托方要求『以打完一梭子为单位（开始-若没满就换弹-打空弹夹-换弹-结束）』。",
                "★ 女仆一次动作 = 连续开火，直到弹夹打空（换弹）或到达下面两个上限之一。")
                .push("gun");

        GUN_MAGAZINE_SHOTS = b
                .comment("一次动作最多打几发（冲锋枪/步枪的弹夹通常在 20~30 发）。",
                        "★ 打空之后 TaCZ 自己会触发换弹（换弹时间算在这一次动作里）；",
                        "★ 若她身上没有备用弹药，连续两次『长等待』就判定为打不出去 ⇒ 提前结束。")
                .defineInRange("magazineShots", 30, 1, 200);

        GUN_MAGAZINE_TICKS = b
                .comment("一次动作最多持续多少 tick（兜底上限，20 tick = 1 秒）。")
                .defineInRange("magazineTicks", 200, 10, 1200);

        b.pop();

        // ── ★★ LLM 动态指挥（与 JEV 那一段【并列但独立】）──
        b.comment("── LLM 动态指挥：用 chat 模型（deepseek 等）决定她的【长期风格】 ──",
                "★★ 请注意与上面 thinking 段的区别：",
                "  thinking.* ⇒ JEV【打分模型】，短周期「此刻该往哪使劲」（8 轴向量）。",
                "  llm.*      ⇒ 【chat 模型】，慢周期「她该是什么脾气」（几个标量旋钮）+ 可编辑提示词。",
                "★ 两者的 key 是【两份】（通常不是同一家的 key）。",
                "★ 默认关闭：打开并填好 key 才会发请求。",
                "★ 结果默认只观测：先看它建议得对不对，再决定要不要真的生效。")
                .push("llm");

        LLM_ENABLED = b
                .comment("总开关。false = 完全不联网（默认）。")
                .define("enabled", false);

        LLM_BASE_URL = b
                .comment("端点（OpenAI 兼容）。默认 https://api.deepseek.com/v1",
                        "⇒ 实际会 POST 到 {上面的值}/chat/completions",
                        "★ 若你的端点本身就是完整地址（.../chat/completions），也会原样使用。")
                .define("baseUrl", "https://api.deepseek.com/v1");

        LLM_API_KEY = b
                .comment("LLM 的 API Key（Bearer 令牌）。留空 = 视为未配置，不会发请求。",
                        "⚠️ 明文存在 config 里 —— 单机自用可以，别把该文件提交到仓库。")
                .define("apiKey", "");

        LLM_MODEL = b
                .comment("模型名，例如 deepseek-chat / gpt-4o-mini / qwen-plus（取决于你的端点支持什么）。")
                .define("model", "deepseek-chat");

        LLM_PROXY = b
                .comment("HTTP 代理，形如 127.0.0.1:7897。留空 = 直连。")
                .define("proxy", "");

        LLM_SYSTEM_PROMPT = b
                .comment("★★ 系统提示词 —— 这是「动态指挥」真正的接口，随便改。",
                        "★ 留空 = 用内置提示词（见 LlmAdvisor.DEFAULT_PROMPT）。",
                        "★ 模型被要求只输出一个 JSON 对象（键 = 旋钮名，值 = 数字）；",
                        "  我们会容错解析：抠不出 JSON 就只记录、不生效，绝不会因为模型乱写而崩。",
                        "★ 提示词里请务必写清「只输出 JSON」与各旋钮的取值范围。")
                .define("systemPrompt", "");

        LLM_INTERVAL_TICKS = b
                .comment("多久问一次（tick）。20 tick = 1 秒。★ 默认 1200（60 秒）——" +
                        "这是【慢变量】：调太快既烧额度，也会让她随模型来回抖。")
                .defineInRange("intervalTicks", 1200, 100, 20 * 60 * 30);

        LLM_TIMEOUT_SECONDS = b
                .comment("单次请求超时（秒）。超时即丢弃该轮，旋钮保持不动。")
                .defineInRange("timeoutSeconds", 40, 5, 300);

        LLM_APPLY_MODE = b
                .comment("结果的去向：",
                        "  observe = 只把「模型建议」写进日志/命令（★ 默认，建议先看几天）",
                        "  apply   = 经白名单 + 钳制后真的写进 TuningBus")
                .defineInList("applyMode", "observe", List.of("observe", "apply"));

        LLM_LOG = b
                .comment("把每次回复的原文写进日志（调提示词时打开）。")
                .define("logReplies", false);

        LLM_TOOL_DIRECTIVES = b
                .comment("★★ 允许【女仆的对话大模型】通过 flp_directive 工具给她下达战斗指令（M5）。",
                        "  这就是「你直接跟她说『拉开距离打』」那条路 —— 与指挥 LLM、玩家命令走同一条指令总线。",
                        "  ★ 默认开。关掉之后工具还在（模型仍能看到），但调用会被明确拒绝并说明原因。")
                .define("toolDirectives", true);

        // ★★ 第十七轮续（委托方第 2 条）：击败"boss 级单位"后主动触发一次对话。
        //   "什么叫 boss 级"在整合包里没有统一答案 ⇒ 用**最大生命值阈值**
        //   （+ 原版末影龙/凋灵的显式白名单）。调大=只认真正的大家伙；调小=精英怪也算。
        LLM_BOSS_CHAT = b
                .comment("击败强敌后让她主动说一句（触发一次女仆对话）。",
                        "  ★ 需要她配了 LLM（没配时静默跳过，不会往聊天栏里刷配置提示）。")
                .define("bossChat", true);
        LLM_BOSS_HEALTH_THRESHOLD = b
                .comment("多少点生命算『boss 级』（原版末影龙/凋灵无条件算）。")
                .defineInRange("bossHealthThreshold", 100, 20, 2000);

        b.pop();

        SPEC = b.build();
    }

    // ───────────────────────── 读取辅助（带兜底，永不在运行时抛异常）─────────────────────────

    /**
     * 读取一个配置值，<b>任何异常都返回兜底值</b>。
     *
     * <p>★ 为什么必须这样：配置在"模组加载前/配置未加载"或"被手工改坏"时会抛。
     * 一个行为逻辑模组不该因为一行配置死掉整个 tick 循环 —— 兜底成"关闭"永远是安全的。
     */
    public static <T> T get(java.util.function.Supplier<T> value, T fallback) {
        try {
            T v = value.get();
            return v == null ? fallback : v;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 是否已具备"可以发请求"的最低条件（开关打开 + key 非空 + baseUrl 非空）。 */
    public static boolean thinkConfigured() {
        return get(THINK_ENABLED, Boolean.FALSE)
                && !get(THINK_API_KEY, "").isBlank()
                && !get(THINK_BASE_URL, "").isBlank();
    }

    /** key 的脱敏形式（只用于日志/命令输出，绝不打印全文）。 */
    public static String maskedKey() {
        String k = get(THINK_API_KEY, "");
        if (k.isBlank()) {
            return "(未配置)";
        }
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, 5) + "…" + k.substring(k.length() - 4);
    }
}
