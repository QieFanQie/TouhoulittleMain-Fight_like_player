package com.touhoulittlemad.fightlikeplayer.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * ★★ <b>「谁该打、谁不该打」名单工具</b>（第十六轮，委托方第 2 条）。
 *
 * <h2>委托方原话</h2>
 * > 「考虑到女仆现在已经能获得周围是否有怪物，可否为女仆 llm 添加『**获得怪物信息**，
 * > 并在**模式设置**中编辑/添加/删除**生物友好/中立/敌对**』的能力？」
 *
 * <p>⇒ 这个工具让**她自己**能查/改那份名单（写在 TLM 的每女仆任务数据里，
 * 与那个图形界面是**同一份数据** ⇒ 两边看到的东西永远一致，不会出现"两套账"）。
 *
 * <p>★ 三档语义（照 TLM）：`HOSTILE` 见着就打 / `NEUTRAL` 被打才还手 / `FRIENDLY` 永不打。
 * ★ 玩家侧仍然可以用任务配置界面改（0.12.0 起已接回原版界面）。
 */
public final class FlpMonsterListTool implements ITool<FlpMonsterListTool.Order> {

    /** 工具 id。 */
    public static final String TOOL_ID = "flp_monster_list";

    private static final String ACTION = "action";
    private static final String ENTITY = "entity";
    private static final String TYPE = "type";

    private static final Codec<Order> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf(ACTION, "get").forGetter(Order::action),
            Codec.STRING.optionalFieldOf(ENTITY, "").forGetter(Order::entity),
            Codec.STRING.optionalFieldOf(TYPE, "").forGetter(Order::type)
    ).apply(inst, Order::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return ("Check or change which creatures you are willing to attack.\n"
                + "Every creature is in one of three groups: HOSTILE (attack on sight), "
                + "NEUTRAL (only fight back after it attacked you or your owner), "
                + "FRIENDLY (never attack).\n"
                + "action=get: show your custom rules and the creatures nearby.\n"
                + "action=set: put one creature type into a group; use `entity` "
                + "(for example minecraft:zombie) and `type` (FRIENDLY/NEUTRAL/HOSTILE).\n"
                + "action=remove: drop your custom rule for that creature (back to the default).\n"
                + "action=clear: drop every custom rule.\n"
                + "Example: {\"action\":\"set\",\"entity\":\"minecraft:creeper\",\"type\":\"FRIENDLY\"}\n"
                + "Notes: this writes the same list the player can edit in the task settings screen. "
                + "A creature you set to FRIENDLY is never attacked, even if the player says so; "
                + "use it deliberately.").trim();
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter action = StringParameter.create();
        action.setDescription("What to do with the attack list.");
        action.addEnumValues("get", "set", "remove", "clear");
        root.addProperties(ACTION, action);

        StringParameter entity = StringParameter.create();
        entity.setDescription("The creature type id, for example minecraft:zombie (a plain zombie "
                + "also works). Needed for action=set and action=remove.");
        root.addProperties(ENTITY, entity);

        StringParameter type = StringParameter.create();
        type.setDescription("The group to put it in. Needed for action=set.");
        type.addEnumValues("FRIENDLY", "NEUTRAL", "HOSTILE");
        root.addProperties(TYPE, type);
        return root;
    }

    @Override
    public Codec<Order> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Order order, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid == null) {
            return callback.addToolResult("Error: no maid bound to this chat.", toolCallId);
        }
        String action = order.action() == null ? "get"
                : order.action().trim().toLowerCase(java.util.Locale.ROOT);
        String result = switch (action) {
            case "set" -> MaidAttackList.set(maid, order.entity(), order.type());
            case "remove" -> MaidAttackList.remove(maid, order.entity());
            case "clear" -> MaidAttackList.clear(maid);
            default -> "你的自定义名单：" + MaidAttackList.describeRules(maid)
                    + "\n附近的生物：" + MaidAttackList.describeNearby(maid, 16.0);
        };
        FightLikePlayer.LOGGER.info("[FLP][attacklist] {} ← 对话模型：{}", action, result);
        return callback.addToolResult(result, toolCallId);
    }

    @Override
    public String invocationSummary(Order order) {
        return "攻击名单 " + (order.action() == null ? "get" : order.action());
    }

    /** 入参（全都有默认值 ⇒ 少写字段不会让整条调用失败）。 */
    public record Order(String action, String entity, String type) {
    }
}
