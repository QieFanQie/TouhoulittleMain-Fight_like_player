package com.touhoulittlemad.fightlikeplayer.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * ★★ <b>备忘录工具</b>（第十六轮）：让女仆的对话模型**自己**记任务与大事。
 *
 * <h2>委托方原话</h2>
 * > 「**备忘录**：允许女仆对话 llm 自行编辑备忘录，记录任务或重大事件，
 * > 当然是通过指令写入/删去等操作。」
 *
 * <h2>★ 为什么必须是"工具"而不是提示词</h2>
 * 写备忘录是一个**动作**（要有回执、要能失败、要能撤销），
 * 而提示词只能描述"你应该记住" —— 模型记不住（它没有跨会话记忆）。
 * ⇒ 做成工具：模型调一次，我们写进**她自己的持久数据**（NBT，随存档保存），
 * 下一次对话由 prompt 类上下文 `flp_memo` 自动读回来。
 *
 * <h2>★ 容量与失败（都不许静默）</h2>
 * 最多 24 条、每条 200 字；满了丢**最旧**的一条并在结果里说明；
 * 删不到就明确回"没找到要删的那一条"。
 */
public final class FlpMemoTool implements ITool<FlpMemoTool.Order> {

    /** 工具 id。 */
    public static final String TOOL_ID = "flp_memo";

    private static final String ACTION = "action";
    private static final String TEXT = "text";

    private static final Codec<Order> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf(ACTION, "read").forGetter(Order::action),
            Codec.STRING.optionalFieldOf(TEXT, "").forGetter(Order::text)
    ).apply(inst, Order::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return ("Read or edit your own memo - a short persistent list of tasks or important "
                + "events you want to remember across conversations.\n"
                + "action=read (default): show every line, numbered.\n"
                + "action=add: append one line; put the text in `text`.\n"
                + "action=remove: delete one line; `text` = its number (1 = newest) or a part "
                + "of its content.\n"
                + "action=clear: delete everything.\n"
                + "Keep lines short and concrete, for example "
                + "{\"action\":\"add\",\"text\":\"主人让我守在东门，优先打僵尸\"}.\n"
                + "The memo is saved with the world and is shown to you automatically in every "
                + "conversation.").trim();
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter action = StringParameter.create();
        action.setDescription("What to do with the memo.");
        action.addEnumValues("read", "add", "remove", "clear");
        root.addProperties(ACTION, action);

        StringParameter text = StringParameter.create();
        text.setDescription("For action=add: the line to remember. For action=remove: the "
                + "number of the line (1 = newest) or a part of its content. Ignored for "
                + "read/clear.");
        root.addProperties(TEXT, text);
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
        String action = order.action() == null ? "read"
                : order.action().trim().toLowerCase(java.util.Locale.ROOT);
        String result;
        switch (action) {
            case "add" -> result = MaidMemory.memoAdd(maid, order.text());
            case "remove" -> result = MaidMemory.memoRemove(maid, order.text());
            case "clear" -> result = MaidMemory.memoClear(maid);
            default -> {
                var lines = MaidMemory.memo(maid);
                if (lines.isEmpty()) {
                    result = "Your memo is empty.";
                } else {
                    StringBuilder sb = new StringBuilder("Your memo (newest first):\n");
                    for (int i = 0; i < lines.size(); i++) {
                        sb.append(i + 1).append(". ").append(lines.get(i)).append('\n');
                    }
                    result = sb.toString().trim();
                }
            }
        }
        FightLikePlayer.LOGGER.info("[FLP][memo] {} ← 对话模型：{}", action, result);
        return callback.addToolResult(result, toolCallId);
    }

    @Override
    public String invocationSummary(Order order) {
        return "备忘录 " + (order.action() == null ? "read" : order.action());
    }

    /**
     * 入参（★ 两个字段都给了默认值 —— 少写一个字段不该让整条调用失败）。
     */
    public record Order(String action, String text) {
    }
}
