package com.touhoulittlemad.fightlikeplayer.carrier;

/**
 * 动作被丢弃的原因 —— <b>载体解析的可观测性</b>。
 *
 * <h2>为什么必须记录原因</h2>
 * [08 §8.2] 那条"灭火器能力静默丢失"的教训就是**没有原因记录**造成的：
 * 只知道"能力不见了"，不知道"在哪一步被谁丢掉的"。
 * ⇒ 每一次丢弃都带原因，日志里能直接回答"**为什么没有这一条**"。
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.4</a>
 */
public enum DropReason {

    /** 载体规则命中失败 —— 女仆没有该载体对应的物品。 */
    NO_CARRIER("没有提供该动作的载体物品"),

    /**
     * ★ <b>载体物品存在，但全都快坏了</b>（耐久低于阈值）。
     *
     * <p>★ 与 {@link #NO_CARRIER} 分开记的理由：这是一条"<b>该换装备了</b>"的信号，
     * 而不是"她做不到"。混在一起会让玩家以为功能缺失。
     * <p>⚠️ 只在<b>所有</b>同类物品都不可用时才记 —— 若还有一件好的，动作照常可用。
     */
    ITEM_EXHAUSTED("载体物品耐久过低（所有同类都不可用）"),

    /** 动作的 `sourceMod` 未加载 —— 清单剪枝。 */
    MOD_NOT_LOADED("提供该动作的模组未加载"),

    /** `reach == RC` —— 本质依赖客户端或 Player 身份，不可达。 */
    UNREACHABLE("可达性为 RC（服务端不可达）"),

    /** 载体规则存在但判据未查证 —— fail-closed。 */
    CARRIER_RULE_UNRESOLVED("载体规则的判据【未能查证】，按 fail-closed 丢弃"),

    /** 前置条件不满足。 */
    PRECONDITION_FAILED("前置条件不满足"),

    /** `custom` 前置条件没有被喂进上下文事实。 */
    PRECONDITION_CUSTOM_UNRESOLVED("custom 前置条件缺少上下文事实（fail-closed）"),

    /** 状态要求不满足（地面/滞空/潜行…）。 */
    STATE_REQ_FAILED("状态要求不满足"),

    /** 互斥组冲突。 */
    EXCLUSIVITY_CONFLICT("互斥占用组已被占用"),

    /** 冷却中。 */
    ON_COOLDOWN("正在冷却"),

    /** ★ 没有 `v_x` —— 无法被决策层评分。 */
    NO_VECTOR("该动作没有需求向量 v_x，无法参与评分"),

    /**
     * ★ 不是"战术选择"：维护步骤（换弹/拉栓）或姿态配置（ADS/切射击模式）。
     * <p>它们由执行器自动完成或作为契合度乘数，**不该进候选集**。
     */
    NOT_A_CHOICE("该动作的角色是 maintenance/stance，不是决策层可选的动作"),

    /** 没有对应的执行器（M4 才补齐）。 */
    NO_EXECUTOR("该动作尚无执行器");

    private final String zh;

    DropReason(String zh) {
        this.zh = zh;
    }

    public String zh() {
        return zh;
    }
}
