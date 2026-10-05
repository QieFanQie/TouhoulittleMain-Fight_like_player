package com.touhoulittlemad.fightlikeplayer.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.NumberParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder;
import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>M5：让女仆的「对话大模型」能下达战斗指令</b>（委托方第十三轮的第 2 条）。
 *
 * <h2>委托方原话</h2>
 * 「也许可以为女仆的大模型对话配置一个 skill，以便于其控制指挥 llm。」
 *
 * <h2>★ 这条路为什么成立（两个模型，各管一段）</h2>
 * <pre>
 *   玩家说话 ─→ 女仆的对话 LLM（TLM 自带的那套）
 *                 │  ← 它本来只会聊天、换任务、跟随……
 *                 │  ★ 现在多了一个工具：flp_directive
 *                 ↓
 *            指令总线 DirectiveBus（本项目）
 *                 │
 *                 ↓
 *        她的决策层照旧选动作，只是候选集/步法被指令改写（docs/15 的 M2）
 * </pre>
 * ★ <b>单向</b>：对话模型只能"下指令"，不能指定她这一秒做什么动作 ——
 * 动作永远由决策层（弹簧 + 最近邻）选。这条边界与指挥 LLM 完全一致（docs/15 §1）。
 *
 * <h2>★★ 参数为什么长这样（只有 4 个字段，却能覆盖 21 条指令）</h2>
 * 21 条指令里，<b>20 条的参数都是"一个数字"</b>（min/max/radius/shots/category/ticks），
 * 只有 {@code ban_focus} 要一个文本（法术名）。所以：
 * <ul>
 *   <li>{@code action} —— issue / cancel / cancel_all / list；</li>
 *   <li>{@code directive} ★ <b>枚举值就是全部指令 id</b>（照抄 TLM 自己的
 *       {@code UseSkillTool} 的做法）⇒ <b>模型在 schema 层面就编不出不存在的 id</b>；</li>
 *   <li>{@code value} —— 那个数字（省略 ⇒ 用该指令的默认值）；</li>
 *   <li>{@code text} —— 那个文本（目前只有 ban_focus 用）。</li>
 * </ul>
 * ⇒ 加一条新指令时，这个工具的 <b>枚举值自动多一个</b>，不用改这里的代码。
 *
 * <h2>★ 安全与可观测</h2>
 * <ul>
 *   <li>开关：{@code llm.toolDirectives}（默认开）。关掉时**明确回话**，而不是装作没听见；</li>
 *   <li>白名单与钳制全在 {@link DirectiveSpec} / {@code DirectiveBus} 里（这里不另造一套）；</li>
 *   <li>成功与失败都回一句**可读**的话给模型，并且落 INFO 日志（玩家在日志里查得到）。</li>
 * </ul>
 */
public final class FlpDirectiveTool implements ITool<FlpDirectiveTool.Order> {

    /** 工具 id（模型看到的名字）。 */
    public static final String TOOL_ID = "flp_directive";

    private static final String ACTION = "action";
    private static final String DIRECTIVE = "directive";
    private static final String VALUE = "value";
    private static final String TEXT = "text";

    /** ★ 指令 id 全集（枚举值 ⇒ 模型只能从这里选）。 */
    private static String[] directiveIds() {
        List<String> ids = new ArrayList<>();
        for (DirectiveSpec.Spec s : DirectiveSpec.all()) {
            ids.add(s.id());
        }
        return ids.toArray(String[]::new);
    }

    private static final Codec<Order> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf(ACTION).forGetter(Order::action),
            Codec.STRING.optionalFieldOf(DIRECTIVE, "").forGetter(Order::directive),
            Codec.DOUBLE.optionalFieldOf(VALUE, Double.NaN).forGetter(Order::value),
            Codec.STRING.optionalFieldOf(TEXT, "").forGetter(Order::text)
    ).apply(inst, Order::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return ("Change how the maid fights, by issuing or cancelling a combat directive.\n"
                + "This does NOT pick her next action; her own decision layer still does that. "
                + "A directive only narrows what she is allowed to do and how she moves.\n"
                + "Use action=issue with one directive id to start it, action=cancel to stop one, "
                + "action=cancel_all to stop everything, action=list to see what is active now.\n"
                + "Sustained directives expire by themselves; you do not have to cancel them.\n"
                + "RULE: everything combat related belongs to her player-like combat mode (the "
                + "fight_like_player:player_like_combat task) - it already covers every other "
                + "combat mode. Never switch her work task to change how she fights; issue a "
                + "directive instead.\n"
                + "Example: to make her keep her distance, use "
                + "{\"action\":\"issue\",\"directive\":\"keep_distance\",\"value\":8}.\n"
                + "Example: to make her use one specific weapon only, use "
                + "{\"action\":\"issue\",\"directive\":\"only_item\",\"text\":\"minecraft:iron_sword\"}.\n"
                + "Example: to make her only attack zombies, use "
                + "{\"action\":\"issue\",\"directive\":\"focus_entity\",\"text\":\"minecraft:zombie\"}.\n"
                + "To make her use ONE SPECIFIC gun or weapon (for example a rocket launcher), use only_item with that item id - ranged_only only restricts the category and does NOT pick a specific gun.\n"
                + "Example: to switch her weapon right now, use "
                + "{\"action\":\"issue\",\"directive\":\"switch_item\",\"text\":\"minecraft:bow\"}.\n"
                + "Magic: if the owner says 「use magic」 or 「cast spells」 WITHOUT naming a system, "
                + "that is magic_only, and it means BOTH 铁魔法 (Iron's Spells 'n Spellbooks) and "
                + "诡厄巫法 (Goety) - they are both magic. Use irons_only only when the owner names "
                + "铁魔法, and goety_only only when the owner names 诡厄巫法. NEVER issue both in the "
                + "same answer: they share one mutually-exclusive group, so the second silently "
                + "replaces the first.\n"
                + "Servants: 「clear/dismiss/get rid of my servants」 = dismiss_servants (this kills "
                + "them permanently), 「call them back/gather them」 = recall_servants, and "
                + "no_summons only when the owner explicitly says not to summon - never on your own.\n"
                + "If you want to know what she actually owns (weapons, spells, shield, servants) "
                + "or what she is currently fighting, that is described by the skill "
                + "flp-combat-command.").trim();
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter action = StringParameter.create();
        action.setDescription("What to do: issue a directive, cancel one, cancel everything, "
                + "or list what is active.");
        action.addEnumValues("issue", "cancel", "cancel_all", "list");
        root.addProperties(ACTION, action);

        StringParameter directive = StringParameter.create();
        directive.setDescription("The directive id. MUST be one of these values; never invent one. "
                + "Not needed for action=list or action=cancel_all.");
        directive.addEnumValues(directiveIds());
        root.addProperties(DIRECTIVE, directive);

        NumberParameter value = NumberParameter.create();
        value.setDescription("The single numeric parameter of that directive "
                + "(for example the min distance of keep_distance, the radius of hold_position, "
                + "the shot count of conserve_ammo, the ticks of disengage/stance_guard). "
                + "Omit it to use the default value.");
        root.addProperties(VALUE, value);

        StringParameter text = StringParameter.create();
        text.setDescription("The single text parameter of that directive. Used by ban_focus "
                + "(the spell label to ban), by only_item / ban_item (an item id, an item tag "
                + "such as #forge:tools/swords, a class name such as SwordItem, or a capability "
                + "such as maid:weapon — copy it from equipment.items[].id), and by focus_entity / "
                + "ban_entity (an entity id such as minecraft:zombie or an entity tag such as "
                + "#minecraft:raiders) and focus_one (that one entity's uuid, from "
                + "battle.target_uuid).");
        root.addProperties(TEXT, text);

        return root;
    }

    @Override
    public Codec<Order> codec() {
        return CODEC;
    }

    /**
     * ★ 真正干活的地方。★ <b>跑在服务器主线程上</b>
     * （TLM 的 {@code LLMCallback:194} 用 {@code MinecraftServer#execute} 派发工具批次），
     * 所以这里可以直接读写她那本指令总线。
     */
    @Override
    public LLMCallback onCall(String toolCallId, Order order, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid == null) {
            return callback.addToolResult("Error: no maid bound to this chat.", toolCallId);
        }
        String action = order.action() == null ? "" : order.action().trim().toLowerCase(java.util.Locale.ROOT);
        long now = maid.level().getGameTime();

        if (!FlpConfig.get(FlpConfig.LLM_TOOL_DIRECTIVES, Boolean.TRUE)) {
            return callback.addToolResult(
                    "Refused: this maid's owner turned off combat directives from chat "
                            + "(config llm.toolDirectives = false).", toolCallId);
        }

        switch (action) {
            case "list" -> {
                return callback.addToolResult("Active directives: "
                        + DirectiveHolder.describe(maid, now), toolCallId);
            }
            case "cancel_all" -> {
                String r = DirectiveHolder.cancel(maid, "all", now, "chat");
                return callback.addToolResult(r + " Now: " + DirectiveHolder.describe(maid, now),
                        toolCallId);
            }
            case "cancel" -> {
                if (order.directive().isBlank()) {
                    return callback.addToolResult(
                            "Error: action=cancel needs a directive id.", toolCallId);
                }
                String r = DirectiveHolder.cancel(maid, order.directive().trim(), now, "chat");
                return callback.addToolResult(r + " Now: " + DirectiveHolder.describe(maid, now),
                        toolCallId);
            }
            case "issue" -> {
                return issue(maid, order, now, toolCallId, callback);
            }
            default -> {
                return callback.addToolResult(
                        "Error: action must be one of issue / cancel / cancel_all / list "
                                + "(got '" + action + "').", toolCallId);
            }
        }
    }

    /** 下达一条（★ 参数的翻译交给纯逻辑层的 {@code DirectiveSpec.translate} —— 那份逻辑被自测钉着）。 */
    private static LLMCallback issue(EntityMaid maid, Order order, long now, String toolCallId,
                                     LLMCallback callback) {
        String id = order.directive() == null ? "" : order.directive().trim();
        DirectiveSpec.Spec spec = DirectiveSpec.find(id);
        if (spec == null) {
            return callback.addToolResult(
                    "Error: unknown directive '" + id + "'. Valid ids: "
                            + String.join(", ", directiveIds()) + ".", toolCallId);
        }
        // ★ 不在这里自己拼参数：四字段（value/text）够不够表达这条指令，
        //   是 DirectiveSpec.translate 的责任，而它被自测逐条断言过。
        Double value = Double.isNaN(order.value()) ? null : order.value();
        DirectiveSpec.Args args = DirectiveSpec.translate(id, value, order.text());
        if (!args.ok()) {
            return callback.addToolResult("Error: " + args.error() + ".", toolCallId);
        }
        FightLikePlayer.LOGGER.info("[FLP][chat] 对话 LLM 下达指令 {}（{}）params={} texts={}",
                id, spec.zh(), args.params(), args.texts());
        String r = DirectiveHolder.issue(maid, id, args.params(), args.texts(), 0, "chat", now);
        return callback.addToolResult(r + " Now: " + DirectiveHolder.describe(maid, now), toolCallId);
    }

    @Override
    public String invocationSummary(Order order) {
        String a = order.action() == null ? "?" : order.action();
        String d = order.directive() == null || order.directive().isBlank() ? "" : " " + order.directive();
        return "战斗指令 " + a + d;
    }

    /**
     * 工具入参（★ 全部可选字段都在 codec 里给了默认值 ——
     * 否则模型少写一个字段就会整条调用失败，那种失败对玩家毫无意义）。
     *
     * @param value 缺省为 {@link Double#NaN}（= 没给）
     */
    public record Order(String action, String directive, double value, String text) {
    }
}
