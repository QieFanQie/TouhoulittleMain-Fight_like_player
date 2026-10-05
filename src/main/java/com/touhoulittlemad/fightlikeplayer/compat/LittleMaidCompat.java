package com.touhoulittlemad.fightlikeplayer.compat;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.task.TaskPlayerLikeCombat;
import net.minecraftforge.common.MinecraftForge;

/**
 * 与车万女仆的唯一接口层。
 *
 * <h2>为什么全部 TLM 交互都收在这一个类里</h2>
 * {@link LittleMaidExtension} 的语义是：「<b>被 TLM 检测到并实例化</b>」——
 * 换句话说，这个类只有在 TLM 存在时才会被加载。
 * ⇒ <b>把所有 TLM 类型引用都限制在此类及其下游（{@code compat.*}）里，
 * 就能保证"未装 TLM 时不触发类加载、不报 NoClassDefFoundError"。</b>
 *
 * <p>这条纪律与姊妹项目 {@code fight_feed_back} 完全一致，也是本项目
 * 「零硬依赖 + 失败即降级」原则的落地方式。
 *
 * <h2>当前注册内容（第一版）</h2>
 * <ul>
 *   <li>{@link TaskPlayerLikeCombat}：一个任务（{@code fight_like_player:player_like_combat}）。</li>
 * </ul>
 * 后续会在此追加：操作清单数据包加载、行为逻辑层、每个载体的动作提供者
 * （全部经 {@code ModList.get().isLoaded} 守卫，见 {@code CarrierDetector}）。
 */
@LittleMaidExtension
public class LittleMaidCompat implements ILittleMaid {

    public LittleMaidCompat() {
        FightLikePlayer.LOGGER.info("[FLP] 已接入车万女仆（LittleMaidCompat 被实例化）");
        // 女仆相关的事件监听器在这里注册（本模组第一版暂无）
        MinecraftForge.EVENT_BUS.register(this);
        // ★★ 第十六轮：刀技的每 tick 驱动器（挂 TLM 的 MaidTickEvent）。
        //   ★ 只有装了拔刀剑才注册 —— 那个类里引用了拔刀剑的类型，
        //     没装时注册它会在事件派发时 NoClassDefFoundError（本项目踩过两次的坑）。
        if (com.touhoulittlemad.fightlikeplayer.compat.event.SlashBladeTicker.available()) {
            MinecraftForge.EVENT_BUS.register(
                    new com.touhoulittlemad.fightlikeplayer.compat.event.SlashBladeTicker());
            FightLikePlayer.LOGGER.info("[FLP] 已挂上刀技驱动器（MaidTickEvent）");
        }
        // ★★ 第十七轮续：**指令驱动器**（挂 MaidTickEvent，每 tick、与战斗无关）。
        //   ★ 为什么必须有它：瞬间指令原来挂在"只在有攻击目标时运行"的行为上
        //     ⇒ 委托方实测「处死仆从/把枪拿出来**完全不起效**」的总根因。
        //   ★ 只引用我们自己的类与 MC 类型 ⇒ **无条件注册**（不需要模组守卫）。
        MinecraftForge.EVENT_BUS.register(
                new com.touhoulittlemad.fightlikeplayer.compat.event.DirectiveTicker());
        FightLikePlayer.LOGGER.info("[FLP] 已挂上指令驱动器（MaidTickEvent）：瞬间指令不再依赖"
                + "她在战斗；指令要的物品会被持续换到主手上");

        // ★★ 第十六轮：击杀归因（"上一次对话到现在杀了什么"的数据来源）
        MinecraftForge.EVENT_BUS.register(
                new com.touhoulittlemad.fightlikeplayer.compat.event.KillListener());
    }

    /**
     * 注册本模组的任务。
     *
     * <p>只追加、不替换 —— TLM 会把它并入任务列表，玩家在女仆界面即可看到。
     */
    @Override
    public void addMaidTask(TaskManager manager) {
        // ★ 第十五轮：改用那个**静态单例**（`INSTANCE_DEFAULT`）——
        //   事实统计侧（MaidSnapshot 数敌人）要用它调用"不含指令"的 canAttack，
        //   而 `IAttackTask.super` 只能在实例方法里用 ⇒ 必须有这么一个可及的实例。
        //   注册同一个实例也避免了"任务有两份"的潜在歧义。
        manager.add(TaskPlayerLikeCombat.INSTANCE_DEFAULT);
        FightLikePlayer.LOGGER.info("[FLP] 已注册任务 {}", TaskPlayerLikeCombat.UID);
    }

    /**
     * ★★ <b>M5：把「下达战斗指令」注册成女仆对话模型能调用的工具</b>（第十三轮）。
     *
     * <p>委托方原话：「也许可以为女仆的大模型对话配置一个 skill，以便于其控制指挥 llm。」
     * ⇒ 这一行就是那个的一半：让她的对话模型手里多一个 {@code flp_directive} 工具，
     * 下出来的指令直接进 {@code DirectiveBus}（与指挥 LLM / 玩家命令**同一条总线**）。
     *
     * <p>另一半是随包发布的技能书 {@code data/touhou_little_maid/skills/flp-combat-command/skill.md}
     * —— 它告诉模型"这条通道能干什么、什么时候该用哪个 id"
     * （★ 没有它，模型只能从工具描述的几句话里猜）。
     */
    @Override
    public void registerAITool(
            com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister register) {
        register.register(new com.touhoulittlemad.fightlikeplayer.compat.ai.FlpDirectiveTool());
        register.register(new com.touhoulittlemad.fightlikeplayer.compat.ai.FlpMemoTool());
        register.register(new com.touhoulittlemad.fightlikeplayer.compat.ai.FlpMonsterListTool());
        FightLikePlayer.LOGGER.info("[FLP] 已注册对话工具 {} 与 {}（下达战斗指令 + 备忘录）",
                com.touhoulittlemad.fightlikeplayer.compat.ai.FlpDirectiveTool.TOOL_ID,
                com.touhoulittlemad.fightlikeplayer.compat.ai.FlpMemoTool.TOOL_ID);
    }

    /**
     * ★★ <b>把她的战斗实况注册成对话上下文</b>（第十六轮）。
     *
     * <p>委托方实测：「女仆的对话 llm 似乎**不知道自己有拔刀剑能释放 SA**，乃至于什么 SA；
     * 也不知道自己能否释放幻影剑」。根因是机制：我们此前只喂了**指挥 LLM**，
     * 而 TLM 自己的 {@code equipment} 上下文只报物品名与数量，
     * <b>不认识"这是不是妖刀、配了什么 SA、能不能放幻影剑"</b>。
     * ⇒ 见 {@code FlpMaidContexts}（prompt 类 + 工具类各注册一半）。
     */
    @Override
    public void registerAIMaidContext(
            com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister register) {
        com.touhoulittlemad.fightlikeplayer.compat.ai.FlpMaidContexts.registerAll(register);
    }
}
