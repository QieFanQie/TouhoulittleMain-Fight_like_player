package com.touhoulittlemad.fightlikeplayer.compat.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.carrier.CarrierResolver;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder;
import com.touhoulittlemad.fightlikeplayer.carrier.DropReason;
import com.touhoulittlemad.fightlikeplayer.carrier.PossessedItem;
import com.touhoulittlemad.fightlikeplayer.compat.behavior.PlayerLikeCombat;
import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.CandidateAction;
import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.TuningBus;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 调试命令 —— <b>思维层的"手工替身「</b>（委托方 P5 要求）。
 *
 * <pre>
 *   /flp bias &lt;女仆&gt; &lt;8 个数&gt;    ★ 注入一个决策向量到该女仆的弹簧空间
 *   /flp why &lt;女仆&gt;               ★★ 一行看清"她为什么做这件事「：弹簧点 / 主循环承诺 / 上次选中 / 步法 / 冷却
 *   /flp whyfull &lt;女仆&gt;           ★★ 候选集 + 每条被丢弃的原因 + 最近 40 条实际结果（"选中了却没做出来「看这个）
 *   /flp think                    ★★ 思维层状态（开关 / 端点 / Key(脱敏) / 间隔 / 强度 / 去向）
 *   /flp think now &lt;女仆&gt;         ★ 立刻强制提交一次判定（忽略节流）
 *   /flp think last &lt;女仆&gt;        ★★ 最近一次判定的「喂进去的局势 → 逐轴分 → 决策向量 → 用量」
 *   /flp tune                     ★★ 列出运行期可调旋钮（满足度 / 冷却 / 偏置风格 / 失败退避）
 *   /flp tune &lt;键&gt; &lt;值&gt;          写一个旋钮（越界自动钳制并告知）· /flp tune reset 全部复原
 *   /flp spring &lt;女仆&gt;            查看当前弹簧点与影响器计数
 *   /flp reset &lt;女仆&gt;             清空弹簧
 *   /flp status                   查看清单加载状态与执行器覆盖
 *   /flp axes                     打印 8 轴的顺序（bias 命令的参数顺序）
 * </pre>
 *
 * <h2>★ 为什么这个命令很重要</h2>
 * 思维层（JEV / LLM）是<b>不可离线验证</b>的外部模型。有了这个注入通路，
 * 就能把"思维层「当成一个**可替换的输入**：
 * <ol>
 *   <li>先手工注入向量 ⇒ 验证 **弹簧 → 选解 → 执行器** 这一段（可复现、可断言）；</li>
 *   <li>再把 JEV 接上 ⇒ 此时若行为不对，**问题必然在 JEV 侧**（因为第 1 段已验证）。</li>
 * </ol>
 * ⇒ <b>把"模型问题「与」架构问题「分开定位</b>，这正是委托方 P5 的核心诉求。
 *
 * <p>★ 注入走的是与真思维层<b>完全相同</b>的接口
 * （{@link com.touhoulittlemad.fightlikeplayer.decision.SpringImpact#bias}），
 * 因此不存在"调试通路与真实通路行为不一致「的风险。
 */
public final class FlpCommands {

    private FlpCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // ★★ /flp bias <女仆> <8 个数>
        //    Brigadier 的参数必须【嵌套】而不是并列，所以这里从最深的 a8 往外串。
        //    （写成分列的 .then(a1).then(a2)… 会变成"任一参数都可选「，编译能过但语义是错的。）
        var arg = DoubleArgumentType.doubleArg(-2.0, 2.0);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> chain =
                Commands.argument("a" + NeedAxis.COUNT, arg).executes(FlpCommands::bias);
        for (int i = NeedAxis.COUNT - 1; i >= 1; i--) {
            chain = Commands.argument("a" + i, arg).then(chain);
        }

        dispatcher.register(Commands.literal("flp")
                .then(Commands.literal("axes").executes(FlpCommands::axes))
                .then(Commands.literal("status").executes(FlpCommands::status))
                // ★★ 第十六轮（委托方第 3 条）：在游戏里查看/编辑她的备忘录
                //   （`MaidTipsOverlay` 是"物品提示"用的，放不了每女仆数据 ⇒ 走命令）
                .then(Commands.literal("memo")
                        .then(Commands.argument("maid", EntityArgument.entity())
                                .executes(FlpCommands::memoShow)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("text",
                                                        StringArgumentType.greedyString())
                                                .executes(FlpCommands::memoAdd)))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("which",
                                                        StringArgumentType.word())
                                                .executes(FlpCommands::memoRemove)))
                                .then(Commands.literal("clear")
                                        .executes(FlpCommands::memoClear))))
                // ★★ 第十六轮（委托方第 2 条）：攻击名单（与界面、与模型工具同一份数据）
                .then(Commands.literal("monsterlist")
                        .then(Commands.argument("maid", EntityArgument.entity())
                                .executes(FlpCommands::listShow)
                                .then(Commands.literal("set")
                                        .then(Commands.argument("entity",
                                                        StringArgumentType.word())
                                                .then(Commands.argument("type",
                                                                StringArgumentType.word())
                                                        .executes(FlpCommands::listSet))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("entity",
                                                        StringArgumentType.word())
                                                .executes(FlpCommands::listRemove)))
                                .then(Commands.literal("clear")
                                        .executes(FlpCommands::listClear))))
                .then(Commands.literal("why")
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(FlpCommands::why)))
                .then(Commands.literal("gun")
                        .then(Commands.argument("maid", EntityArgument.entity())
                                .executes(FlpCommands::gun)))
                .then(Commands.literal("items")
                        .then(Commands.argument("maid", EntityArgument.entity())
                                .executes(FlpCommands::items)))
                .then(Commands.literal("whyfull")
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(FlpCommands::whyFull)))
                .then(Commands.literal("spring")
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(FlpCommands::spring)))
                .then(Commands.literal("reset")
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(FlpCommands::reset)))
                .then(Commands.literal("think")
                        .executes(FlpCommands::thinkStatus)
                        .then(Commands.literal("now")
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(FlpCommands::thinkNow)))
                        .then(Commands.literal("last")
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(FlpCommands::thinkLast))))
                .then(Commands.literal("tune")
                        .executes(FlpCommands::tuneShow)
                        .then(Commands.literal("reset").executes(FlpCommands::tuneReset))
                        .then(Commands.literal("advisor")
                                .executes(FlpCommands::tuneAdvisor)
                                .then(Commands.literal("now")
                                        .then(Commands.argument("maid", EntityArgument.entities())
                                                .executes(FlpCommands::tuneAdvisorNow)))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(FlpCommands::tuneAdvisor)))
                        // ★★ 第十二轮：LLM 动态指挥（chat 模型）—— 与 advisor（JEV）并列
                        .then(Commands.literal("llm")
                                .executes(FlpCommands::tuneLlm)
                                .then(Commands.literal("now")
                                        .then(Commands.argument("maid", EntityArgument.entities())
                                                .executes(FlpCommands::tuneLlmNow)))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(FlpCommands::tuneLlm)))
                        .then(Commands.literal("maid")
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(FlpCommands::tuneMaidShow)
                                        .then(Commands.literal("reset")
                                                .executes(FlpCommands::tuneMaidReset))
                                        .then(Commands.argument("key",
                                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(-1000, 1000))
                                                        .executes(FlpCommands::tuneMaidSet)))))
                        // ★★ 十三轮：指令（docs/15 的 M3）—— 与动作/旋钮并列的第 5 层
                        .then(Commands.literal("directive")
                                .executes(FlpCommands::directiveList)
                                .then(Commands.literal("list")
                                        .executes(FlpCommands::directiveList)
                                        .then(Commands.argument("maid", EntityArgument.entities())
                                                .executes(FlpCommands::directiveList)))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("maid", EntityArgument.entities())
                                                .executes(ctx -> directiveClear(ctx, "all"))
                                                .then(Commands.argument("id",
                                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                                        .executes(ctx -> directiveClear(ctx,
                                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id"))))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("maid", EntityArgument.entities())
                                                .then(Commands.argument("id",
                                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                                        .executes(ctx -> directiveSet(ctx, 0))
                                                        .then(Commands.argument("value",
                                                                        DoubleArgumentType.doubleArg(-1000, 1000))
                                                                .executes(ctx -> directiveSet(ctx,
                                                                        DoubleArgumentType.getDouble(ctx, "value"))))
                                                        .then(Commands.literal("text")
                                                                .then(Commands.argument("text",
                                                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                                        .executes(FlpCommands::directiveSetText)))
                                                        // ★★ 第十七轮：**不带 text 关键字**也认
                                                        //   —— `... only_item tacz:ak47`（原来会因为
                                                        //   "第三个参数只吃数字"直接语法报错 ⇒ 指令没下达，
                                                        //   而观感与"指令没生效"一模一样）。
                                                        .then(Commands.argument("valueText",
                                                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                                .executes(ctx -> directiveSetAny(ctx, "valueText")))))))
                        .then(Commands.argument("key", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(-1000, 1000))
                                        .executes(FlpCommands::tuneSet))))
                .then(Commands.literal("bias")
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .then(chain)))
        );
    }

    // ───────────────────────── 各子命令 ─────────────────────────

    private static int axes(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§e「类玩家」8 轴（bias 命令的参数顺序）§r\n");
        NeedAxis[] all = NeedAxis.values();
        for (int i = 0; i < all.length; i++) {
            sb.append(String.format("  %d. §b%s§r (%s)%s%n",
                    i + 1, all[i].name(), all[i].zhName(),
                    all[i].isSigned() ? " §d← 唯一有向轴：正=靠近，负=远离§r" : ""));
        }
        sb.append("§7例：/flp bias @e[type=touhou_little_maid,limit=1] 0.3 0.8 0 0 -0.5 0 0 0");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        var r = CatalogHolder.resolver();
        String msg = "§e[FLP] 清单状态§r\n"
                + "  已加载：" + (CatalogHolder.isLoaded() ? "§a是§r" : "§c否§r") + "\n"
                + "  摘要：" + CatalogHolder.summary() + "\n"
                + "  来源：" + CatalogHolder.lastDescription() + "\n"
                + "  重载次数：" + CatalogHolder.loadCount()
                + (r == null || r.actions().isEmpty() ? ""
                : "\n  动作 " + r.actions().size() + " 条 · 载体规则 " + r.rules().size() + " 条"
                  + "\n  跟踪中的女仆循环：" + PlayerLikeCombat.trackedCount() + " 个");
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /**
     * ★★ {@code /flp why <女仆>} —— <b>一行看清"她为什么做这件事「</b>。
     *
     * <p>为什么要有它：S0 实测那次「到处跑 / 疯狂执行 / 拉满不射」，
     * 光看日志要猜很久。这个命令把四个决定性输入一次摊开：
     * <ol>
     *   <li><b>弹簧点 p</b> —— 她"想要什么「；</li>
     *   <li><b>主循环承诺状态</b> —— 她现在被什么动作门控着（含上次选中）；</li>
     *   <li>★ <b>步法</b> —— 脚下打算怎么走（走位已不是动作，见 docs/04 Q18）；</li>
     *   <li>★ <b>冷却中的动作数</b> —— 节奏是否被冷却挡住（"疯狂执行「的判据）。</li>
     * </ol>
     */
    // ───────────────── ★★ 备忘录（第十六轮，委托方第 3 条）─────────────────

    private static int memoShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        var lines = com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory.memo(m);
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    m.getName().getString() + " 的备忘录是空的（她可以自己写：flp_memo 工具）"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "★ " + m.getName().getString() + " 的备忘录（" + lines.size() + " 条，新的在前）："), false);
        for (int i = 0; i < lines.size(); i++) {
            final int n = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal("  " + n + ". " + lines.get(n - 1)),
                    false);
        }
        return lines.size();
    }

    private static int memoAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        String r = com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory.memoAdd(m, text);
        ctx.getSource().sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    private static int memoRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        String r = com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory.memoRemove(m,
                StringArgumentType.getString(ctx, "which"));
        ctx.getSource().sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    private static int memoClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory.memoClear(m)), false);
        return 1;
    }

    // ───────────────── ★★ 攻击名单（第十六轮，委托方第 2 条）─────────────────

    private static int listShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("★ 自定义名单："
                + com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList.describeRules(m)), false);
        ctx.getSource().sendSuccess(() -> Component.literal("★ 附近："
                + com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList.describeNearby(m, 24.0)),
                false);
        return 1;
    }

    private static int listSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        String r = com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList.set(m,
                StringArgumentType.getString(ctx, "entity"), StringArgumentType.getString(ctx, "type"));
        ctx.getSource().sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    private static int listRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        String r = com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList.remove(m,
                StringArgumentType.getString(ctx, "entity"));
        ctx.getSource().sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    private static int listClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("那不是女仆"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList.clear(m)), false);
        return 1;
    }

    private static int why(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                var r = CatalogHolder.resolver();
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§b" + maid.getName().getString() + "§r\n  "
                                + PlayerLikeCombat.describe(maid)
                                + "\n  清单动作=" + (r == null ? "-" : r.actions().size())
                                + "  ·  载体规则=" + (r == null ? "-" : r.rules().size())), false);
                n++;
            }
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }

    /**
     * ★★ {@code /flp whyfull <女仆>} —— <b>把"为什么没做出来「整条链摊开</b>。
     *
     * <h2>为什么必须有它（2026-10-01 委托方实测）</h2>
     * 委托方报「拔刀剑没有动作」「从未观测到施法」。新语义下<b>失败是静默的</b>
     * （执行器回报非 OK ⇒ 不扣代价、不进承诺、不记 lastChosenId ⇒ 看起来"什么都没发生「），
     * 于是从外部无法区分以下<b>三种完全不同的原因</b>：
     * <ol>
     *   <li><b>没进候选</b> —— 载体规则没匹配 / 前置条件 fail-closed 丢弃；</li>
     *   <li><b>进了候选但没被选中</b> —— 弹簧点离它远，或冷却挡住 ⇒ 反复选别的；</li>
     *   <li><b>被选中但执行器没做出来</b> —— 物品不在手且换手失败 / 状态不对。</li>
     * </ol>
     * 本命令把它们分成三段分别打印：<b>①候选与丢弃原因 ②最近几十拍的实际结果 ③冷却</b>。
     */
    private static int whyFull(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof EntityMaid maid)) {
                continue;
            }
            StringBuilder sb = new StringBuilder("§b" + maid.getName().getString() + "§r  ");
            sb.append(PlayerLikeCombat.describe(maid)).append('\n');

            // ── ① 候选集 + 丢弃原因（解析阶段） ──
            CarrierResolver.Resolution res = PlayerLikeCombat.lastMainResolution(maid);
            sb.append("§e① 解析（主手）§r ").append(res.summary()).append('\n');
            if (!res.candidates().isEmpty()) {
                sb.append("   候选：");
                var it = res.candidates().iterator();
                for (int i = 0; i < 12 && it.hasNext(); i++) {
                    CandidateAction ca = it.next();
                    sb.append(ca.id());
                    PossessedItem ev = res.evidenceOf(ca.id());
                    if (ev != null) {
                        sb.append("（@").append(ev.shortName()).append('）');
                    }
                    sb.append(' ');
                }
                if (res.candidates().size() > 12) {
                    sb.append("…… 共 ").append(res.candidates().size());
                }
                sb.append('\n');
            }
            if (!res.dropped().isEmpty()) {
                sb.append("§c   丢弃§r：");
                Map<DropReason, Integer> byReason = res.droppedByReason();
                sb.append(byReason).append('\n');
                // ★ 只列前 6 条明细 —— 大多数情况下"缺哪个事实/哪条前置条件「看明细才有用
                int shown = 0;
                for (CarrierResolver.Dropped d : res.dropped()) {
                    if (shown++ >= 6) {
                        break;
                    }
                    sb.append("     · ").append(d.actionId())
                            .append(" → ").append(d.reason())
                            .append(d.detail() == null || d.detail().isEmpty() ? "" : "：" + d.detail())
                            .append('\n');
                }
            }
            if (!res.notes().isEmpty()) {
                sb.append("§7   注：").append(String.join(" / ", res.notes())).append("§r\n");
            }

            // ── ② 最近的实际结果（执行阶段） ──
            java.util.List<String> recent = PlayerLikeCombat.recentResults(maid);
            sb.append("§e② 最近结果§r（新→旧，最多 ")
                    .append(PlayerLikeCombat.recentResultsCapacity()).append(" 条）\n");
            if (recent.isEmpty()) {
                sb.append("   （还没有记录 —— 她可能没在战斗任务中，或从未进入决策）\n");
            }
            for (int i = recent.size() - 1, shown = 0; i >= 0 && shown < 14; i--, shown++) {
                sb.append("   ").append(recent.get(i)).append('\n');
            }

            // ── ③ 冷却（节奏） ──
            Set<String> cooling = PlayerLikeCombat.lastCooling(maid);
            sb.append("§e③ 冷却中§r ").append(cooling.isEmpty() ? "（无）" : cooling.toString()).append('\n');

            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            n++;
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }

    // ───────────────────────── ★ 思维层（短周期 JEV）─────────────────────────

    /**
     * {@code /flp think} —— <b>思维层状态</b>。
     *
     * <p>★ 这条命令的存在理由是 docs/13 第 19 条：<b>"凡可预期的失败都必须有用户可读的出口「</b>。
     * 思维层的失败模式特别多（没配置 / key 错 / 网络不通 / 那句假的「无可用渠道」/ 置信度太低 /
     * 模型少答一条轴），如果只写进 debug 日志，委托方看到的现象只会是"女仆行为没变「 —— 无从下手。
     */
    private static int thinkStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = FlpConfig.get(FlpConfig.THINK_ENABLED, Boolean.FALSE);
        boolean configured = FlpConfig.thinkConfigured();
        StringBuilder sb = new StringBuilder("§b【思维层 · 短周期 JEV】§r\n");
        sb.append("  开关 = ").append(enabled ? "§a开§r" : "§c关§r")
                .append("　可发请求 = ").append(configured ? "§a是§r" : "§c否§r")
                .append(enabled && !configured ? "　§c⚠ 已打开但没填 Key§r" : "").append('\n');
        sb.append("  端点 = ").append(FlpConfig.get(FlpConfig.THINK_BASE_URL, "-"))
                .append("　模型 = ").append(FlpConfig.get(FlpConfig.THINK_MODEL, "-")).append('\n');
        sb.append("  Key = ").append(FlpConfig.maskedKey())
                .append("　代理 = ").append(FlpConfig.get(FlpConfig.THINK_PROXY, "").isBlank()
                        ? "(直连)" : FlpConfig.get(FlpConfig.THINK_PROXY, "")).append('\n');
        sb.append("  间隔 = ").append(FlpConfig.get(FlpConfig.THINK_INTERVAL_TICKS, 30)).append(" tick")
                .append("　超时 = ").append(FlpConfig.get(FlpConfig.THINK_TIMEOUT_SECONDS, 30)).append(" s")
                .append("　强度 = ").append(FlpConfig.get(FlpConfig.THINK_BIAS_STRENGTH, 0.35)).append('\n');
        sb.append("  置信度门槛 = ").append(FlpConfig.get(FlpConfig.THINK_MIN_CONFIDENCE, 0.0))
                .append("　去向 = ").append(FlpConfig.get(FlpConfig.THINK_APPLY_MODE, "bias"))
                .append("　仅战斗中 = ").append(FlpConfig.get(FlpConfig.THINK_ONLY_IN_COMBAT, Boolean.TRUE))
                .append('\n');
        sb.append("§7  ★ 改配置：ESC → 模组 → 找到「玩家拟人战法」→ Config（或直接改 config/fight_like_player-common.toml）§r\n");
        sb.append("§7  排查：/flp think last <女仆> 看最近一次判定的输入与结果；/flp think now <女仆> 立刻强制问一次§r");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** {@code /flp think now <女仆>} —— 抹掉节流，让下一个 tick 立刻提交一次。 */
    private static int thinkNow(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (Entity e : targets) {
            if (!(e instanceof EntityMaid maid)) {
                continue;
            }
            if (!FlpConfig.thinkConfigured()) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c思维层未配置：请到 模组配置界面 →「玩家拟人战法」→ Config 里打开 enabled 并填 apiKey。"
                                + "（Key 留空时本模组不会发出任何网络请求）"));
                return 0;
            }
            PlayerLikeCombat.forceNextThink(maid);
            sb.append("§a已请求立刻判定：§r").append(maid.getName().getString())
                    .append("（下一个 tick 提交；约 1 秒后用 /flp think last 看结果）\n");
            n++;
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return n;
    }

    /**
     * {@code /flp think last <女仆>} —— <b>最近一次判定的"输入 → 输出「全貌</b>。
     *
     * <p>★ 显示<b>喂给模型的 state 原文</b>（JSON）而不是摘要：这样"模型判断不准「与
     * "我们喂错了信息「能被分开定位 —— 这正是 docs/11 §6 要求的定位方式。
     */
    private static int thinkLast(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof EntityMaid maid)) {
                continue;
            }
            var thinker = PlayerLikeCombat.thinkerOf(maid);
            var last = PlayerLikeCombat.lastThink(maid);
            StringBuilder sb = new StringBuilder("§b" + maid.getName().getString() + "§r\n");

            if (thinker == null) {
                sb.append("§7  （她还没进过决策循环 —— 先让她进入战斗任务）§r\n");
            } else {
                var st = thinker.stats();
                sb.append("  提交 ").append(st.submitted())
                        .append(" · 成功 ").append(st.succeeded())
                        .append(" · 失败 ").append(st.failed())
                        .append(" · 作废 ").append(st.dropped())
                        .append(" · 迟到丢弃 ").append(st.staleDiscarded())
                        .append("　在途 = ").append(thinker.inFlight() ? "有" : "无").append('\n');
                if (st.lastLatencyMs() > 0) {
                    sb.append("  最近往返 ").append(st.lastLatencyMs()).append(" ms\n");
                }
                if (st.lastError() != null) {
                    sb.append("§c  最近失败：").append(st.lastError()).append("§r\n");
                }
                if (st.lastDropReason() != null) {
                    sb.append("§e  最近作废：").append(st.lastDropReason()).append("§r\n");
                }
            }

            if (last == null) {
                sb.append("§7  （还没有成功施加过的判定）§r\n");
            } else {
                sb.append("§e① 喂给模型的局势§r（").append(last.scene().summary()).append("）\n");
                sb.append("  ").append(truncate(json(last.scene().toState()), 900)).append('\n');
                sb.append("§e② 逐轴原始分§r ").append(last.verdict().summary()).append('\n');
                sb.append("§e③ 生成的决策向量§r ")
                        .append(last.bias().toCompactString(
                                com.touhoulittlemad.fightlikeplayer.decision.SpringConfig.defaults()))
                        .append("　§7（").append(last.applied() ? "已推弹簧" : "仅观测").append("）§r\n");
                sb.append("§e④ 用量§r ")
                        .append(com.touhoulittlemad.fightlikeplayer.decision.thinking.JevClient
                                .usage(last.response()))
                        .append('\n');
            }
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            n++;
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }

    private static String json(Object o) {
        try {
            return new com.google.gson.Gson().toJson(o);
        } catch (RuntimeException e) {
            return String.valueOf(o);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    // ───────────────────────── ★★ 运行期调参（动态调试的接口面）─────────────────────────

    /**
     * {@code /flp tune} —— <b>列出所有可调旋钮</b>（键名 / 当前值 / 范围 / 中文含义）。
     *
     * <h2>这个命令为什么值得存在</h2>
     * 委托方 2026-10-01 的第 4 条设想是：「接入 LLM 实时调控 —— LLM 不直接决定战斗，
     * 但可以决定<b>风格</b>与<b>决策</b>，通过输出指令来调整体系的各个参数，类似动态调试。」
     *
     * <p>那条设想要求"体系里有一组有名有界、可读写的旋钮「（{@link TuningBus}）。
     * 而<b>只要旋钮存在，就应该先有一个不依赖 LLM 的入口</b> —— 否则"调参「永远要先接模型才能试，
     * 就没法验证旋钮本身是否有效、是否安全。⇒ 本命令与 LLM 写的是<b>同一份总线</b>。
     */
    private static int tuneShow(CommandContext<CommandSourceStack> ctx) {
        TuningBus bus = TuningBus.global();
        StringBuilder sb = new StringBuilder("§b【运行期旋钮】§r（与 LLM 调控写的是同一份）\n");
        sb.append(bus.describeForPlayer());
        sb.append("§7  当前").append(bus.isDefault() ? "全部为默认值" : "已被改动：")
                .append(bus.overrides()).append("§r\n");
        String rej = bus.lastRejection();
        if (rej != null) {
            sb.append("§e  最近一次拒绝/钳制：").append(rej).append("§r\n");
        }
        sb.append("§7  用法：/flp tune <键> <值>　·　/flp tune reset\n");
        sb.append("§7  ★ 例：下调攻击端满足度 ⇒ 攻击需求需要更多次攻击才满足：\n");
        sb.append("§7     /flp tune satisfaction.damage 0.5");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** {@code /flp tune <键> <值>} —— 写一个旋钮（越界会被钳制并告知）。 */
    private static int tuneSet(CommandContext<CommandSourceStack> ctx) {
        String key = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key");
        double value = DoubleArgumentType.getDouble(ctx, "value");
        TuningBus bus = TuningBus.global();
        bus.clearRejection();
        boolean ok = bus.set(key, value);
        if (!ok) {
            String why = bus.lastRejection();
            ctx.getSource().sendFailure(Component.literal(
                    "§c" + (why == null ? "拒绝：" + key : why)
                            + "\n§7 可用的键见 /flp tune"));
            return 0;
        }
        double now = bus.get(key);
        String note = bus.lastRejection();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已设 " + key + " = " + now + (note == null ? "" : "　§e(" + note + "§e)")),
                true);
        return 1;
    }

    /** {@code /flp tune reset} —— 全部回到默认（任何动态调试都必须有这一步）。 */
    private static int tuneReset(CommandContext<CommandSourceStack> ctx) {
        TuningBus.global().resetAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a所有运行期旋钮已恢复默认"), true);
        return 1;
    }

    /**
     * {@code /flp tune advisor [女仆]} —— <b>调控顾问的状态与"它想怎么调「</b>。
     *
     * <p>★ 这个命令是"顾问默认只观测「这个决定的另一半：
     * 不放开写权限，但<b>必须先能看见它想干什么</b>。
     */
    private static int tuneAdvisor(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        StringBuilder sb = new StringBuilder("§b【调控顾问】§r（LLM 决定「风格」，不决定「战斗」）\n");
        java.util.Collection<? extends Entity> targets;
        try {
            targets = EntityArgument.getEntities(ctx, "maid");
        } catch (IllegalArgumentException | CommandSyntaxException e) {
            targets = java.util.List.of();
        }
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                sb.append("§b").append(maid.getName().getString()).append("§r\n  ")
                        .append(com.touhoulittlemad.fightlikeplayer.compat.think.AdvisorBridge
                                .describe(PlayerLikeCombat.advisorOf(maid)));
                n++;
            }
        }
        if (n == 0) {
            sb.append("§7  （没有指定女仆 ⇒ 只显示配置；想看某个女仆的统计：")
                    .append("/flp tune advisor <女仆>）§r\n");
            sb.append("  开关 = ")
                    .append(FlpConfig.get(FlpConfig.ADVISOR_ENABLED, Boolean.FALSE) ? "§a开" : "§c关")
                    .append("§r　去向 = ")
                    .append(FlpConfig.get(FlpConfig.ADVISOR_APPLY_MODE, "observe"))
                    .append("　间隔 = ")
                    .append(FlpConfig.get(FlpConfig.ADVISOR_INTERVAL_TICKS, 600)).append(" tick\n");
        }
        sb.append("§7  配置：模组配置界面 → 玩家拟人战法 → Config → advisor 段\n");
        sb.append("§7  ★ 建议顺序：先 enabled=true + applyMode=observe 跑一阵，看「最近建议」合不合理，")
                .append("再改成 apply。§r\n");
        sb.append("§7  ⓘ 这一段用的是 §fJEV 打分模型§7；要用 §f真正的 LLM（deepseek 等）§7")
                .append("请用 §f/flp tune llm§7（两者是【两个模型、两份 key】）§r");
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /**
     * {@code /flp tune llm [女仆]} —— <b>LLM 动态指挥的状态与"它想怎么调"</b>（第十二轮）。
     *
     * <p>★ 与 {@code /flp tune advisor} 的区别是**模型不同**：
     * 那个用 JEV（打分模型），这个用 <b>chat 模型</b>（deepseek 等）+ 可编辑提示词。
     */
    private static int tuneLlm(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        StringBuilder sb = new StringBuilder(
                "§b【LLM 动态指挥】§r（chat 模型：读提示词、写自然语言，只调风格不调战斗）\n");
        java.util.Collection<? extends Entity> targets;
        try {
            targets = EntityArgument.getEntities(ctx, "maid");
        } catch (IllegalArgumentException | CommandSyntaxException e) {
            targets = java.util.List.of();
        }
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                sb.append("§b").append(maid.getName().getString()).append("§r\n");
                var st = PlayerLikeCombat.llmOf(maid);
                sb.append(com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.describe(st));
                n++;
            }
        }
        if (n == 0) {
            sb.append(com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.describe(null));
            sb.append("§7  （想看某个女仆的统计与最近建议：/flp tune llm <女仆>）§r\n");
        }
        sb.append("§7  配置：模组配置界面 → 玩家拟人战法 → Config → 第 ④ 页「LLM 动态指挥」\n");
        sb.append("§7  ★ 顺序：① 填 key/端点 → ②「测试连接」通过 → ③ enabled=true + observe 跑一阵 → ")
                .append("④ 满意后 applyMode=apply。§r");
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // ──────────── ★★ 指令（docs/15 的 M3）────────────

    /** {@code /flp directive [list] [女仆]} —— 全部可用指令 + 正在生效的。 */
    /**
     * {@code /flp gun <女仆>} —— ★★ <b>枪的自查</b>（第十七轮续）。
     *
     * <p>委托方连着两轮卡在枪上，而这三件事**从外部都看不出来**：
     * <ol>
     *   <li>这把枪的<b>身份 id</b> 是什么（TaCZ 的所有现代枪共用一个注册名
     *       {@code tacz:modern_kinetic_gun}，真正的身份在 NBT 的 GunId 里）；</li>
     *   <li><b>枪型/别名</b>解析出来了没有（枪型走反射，失败会**静默**降级成"没有枪型"）；</li>
     *   <li><b>指令驱动器</b>到底在不在跑（瞬间指令不生效的头号原因）。</li>
     * </ol>
     */
    private static int gun(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive
                .EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("§c这个实体不是女仆"));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§e—— 枪的自查 ——§r\n");
        sb.append("§7驱动器§r：").append(com.touhoulittlemad.fightlikeplayer.compat.event
                .DirectiveTicker.describe()).append('\n');
        sb.append("§7主手§r：").append(describeGun(m.getMainHandItem())).append('\n');
        int n = 0;
        for (var it : com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .possessed(m)) {
            if (it.aliases().isEmpty() && !it.params().containsKey("gunId")) {
                continue;                       // 不是枪
            }
            n++;
            sb.append("  §b").append(it.itemId()).append("§r")
                    .append("（").append(it.displayName()).append("）")
                    .append(" @").append(it.slot().name())
                    .append(" 类型=").append(it.param("gunType") == null ? "§c?" : it.param("gunType"))
                    .append(" 别名=").append(String.join("/", it.aliases()))
                    .append('\n');
        }
        if (n == 0) {
            sb.append("§7（她身上没有枪）§r\n");
        }
        sb.append("§7指令§r：").append(com.touhoulittlemad.fightlikeplayer.compat.directive
                .DirectiveHolder.describe(m, ctx.getSource().getLevel().getGameTime()));
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /**
     * ★★ <b>{@code /flp items <女仆>} —— 把"她自己看的物品清单"原样打给玩家看</b>（第二十六轮）。
     *
     * <h2>为什么加它（委托方实测的那句话）</h2>
     * > 她：「…仓库里的魔剑「阎魔刀」**好像不算拔刀剑**哦…」
     *
     * 那次要确认"她到底看到了什么"只能靠猜（她的上下文不落日志）。这个命令把
     * **同一份文本**（`MaidItems.describe` —— 与 `flp_items` 上下文**同源**，不是另写一遍）
     * 加上"她的刀各自是什么身份"一起打出来
     * ⇒ 以后"她为什么认成那样"当场就能对账。
     */
    private static int items(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        var maid = EntityArgument.getEntity(ctx, "maid");
        if (!(maid instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive
                .EntityMaid m)) {
            ctx.getSource().sendFailure(Component.literal("§c这个实体不是女仆"));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§e—— 她的物品（她自己看到的就是下面这份）——§r\n");
        sb.append(com.touhoulittlemad.fightlikeplayer.compat.maid.MaidItems.describe(m))
                .append('\n');
        sb.append("§e—— 拔刀剑身份自查 ——§r\n");
        try {
            var blades = com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade
                    .SlashBladeExecutors.ownedBlades(m);
            if (blades.isEmpty()) {
                sb.append("§7（她身上一把拔刀剑都没有）§r\n");
            }
            for (var b : blades) {
                sb.append("  §b").append(b.identityId()).append("§r")
                        .append("（").append(b.displayName()).append("）")
                        .append(" @").append(b.slot().name())
                        .append(b.inHand() ? " §a[在手上]§r" : " §e[不在手上——执行时会自动换手]§r")
                        .append(" 注册名=").append(com.touhoulittlemad.fightlikeplayer.compat.maid
                                .MaidSnapshot.registryId(b.stack()))
                        .append('\n');
            }
        } catch (RuntimeException | LinkageError e) {
            sb.append("§c拔刀剑自查失败：").append(e).append("§r\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** 一行枪的描述（身份 id / 注册名 / 名字 / 枪型）——"那个不存在的名字"就是注册名那一栏。 */
    private static String describeGun(net.minecraft.world.item.ItemStack stack) {        if (stack == null || stack.isEmpty()) {
            return "§7(空手)§r";
        }
        var snap = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot.class;
        String identity = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .itemId(stack);
        String registry = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .registryId(stack);
        String gunId = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .gunId(stack);
        if (gunId == null) {
            return identity + "（不是枪；注册名 " + registry + "）";
        }
        String type = com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .gunType(stack);
        return identity + "（" + com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .displayName(stack) + "） 枪型=" + (type == null ? "§c解析失败§r" : type)
                + " 注册名=" + registry + " 别名=" + String.join("/",
                com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot.aliases(stack));
    }

    private static int directiveList(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("§b【指令表】§r（LLM/玩家可下达；∞=持续、⚡=瞬间）\n");
        sb.append(com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.describeForPlayer());
        sb.append("§7用法：/flp directive set <女仆> <id> [数值] · ")
                .append("[id] text <文本> · /flp directive clear <女仆> [id|all]§r\n");
        java.util.Collection<? extends Entity> targets;
        try {
            targets = EntityArgument.getEntities(ctx, "maid");
        } catch (IllegalArgumentException | CommandSyntaxException e) {
            Entity near = ctx.getSource().getEntity();
            net.minecraft.world.phys.AABB box = near == null
                    ? new net.minecraft.world.phys.AABB(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7)
                    : near.getBoundingBox().inflate(64);
            targets = ctx.getSource().getLevel().getEntitiesOfClass(
                    com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class, box);
        }
        long now = ctx.getSource().getLevel().getGameTime();
        sb.append("\n§b【正在生效】§r\n");
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
                sb.append("  §f").append(maid.getName().getString()).append("§r ")
                        .append(com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                                .describe(maid, now))
                        .append('\n');
                n++;
            }
        }
        if (n == 0) {
            sb.append("§7  （附近没有女仆；用 /flp directive list <女仆> 看某一个）§r\n");
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** {@code /flp directive set <女仆> <id> [数值]} —— 下达（数值/默认参数）。 */
    private static int directiveSet(CommandContext<CommandSourceStack> ctx, double value)
            throws CommandSyntaxException {
        var maid = maidOf(ctx);
        if (maid == null) {
            return 0;
        }
        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
        var spec = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.find(id);
        if (spec == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c未知指令 " + id + "§7 —— 用 /flp directive list 看可用指令"));
            return 0;
        }
        java.util.Map<String, Double> params = new java.util.HashMap<>();
        java.util.Map<String, String> texts = new java.util.HashMap<>();
        if (spec.hasParams() && spec.params().size() == 1) {
            var e = spec.params().entrySet().iterator().next();
            if (e.getValue().text()) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c指令 " + id + " 需要文本参数：/flp directive set <女仆> "
                                + id + " text <文本>"));
                return 0;
            }
            if (value != 0) {
                params.put(e.getKey(), value);
            }
        }
        String msg = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .issue(maid, id, params, texts, 0, "player",
                        ctx.getSource().getLevel().getGameTime());
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), false);
        return 1;
    }

    /**
     * {@code /flp directive set <女仆> <id> <值>} —— <b>值既可以是数字也可以是文本</b>（第十七轮）。
     *
     * <p>为什么要它：原来第三个参数是 {@code DoubleArgumentType}（只吃数字），
     * 文本参数**必须**写成 {@code ... <id> text <文本>} ⇒ 少写那个 {@code text} 就是**语法错误**，
     * 指令根本没下达（观感与"指令没生效"完全一样）。
     *
     * <p>★ 四种写法**语义等价**（关键在这一句）：本方法会把开头的 {@code text }
     * <b>去掉</b> —— 于是无论 Brigadier 最终选中"字面量 text 分支"还是"任意值分支"，
     * 结果都一样。数字型参数能解析成数字就按数字走，否则按文本走
     * （半命题指令 {@code only_item} / {@code ban_item} 的参数本来就声明成文本）。
     */
    private static int directiveSetAny(CommandContext<CommandSourceStack> ctx, String key)
            throws CommandSyntaxException {
        var maid = maidOf(ctx);
        if (maid == null) {
            return 0;
        }
        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
        String raw = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, key).trim();
        if (raw.startsWith("text ")) {
            raw = raw.substring("text ".length()).trim();       // ← 两种解析等价的关键
        }
        var spec = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.find(id);
        if (spec == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c未知指令 " + id + "§7 —— 用 /flp directive list 看可用指令"));
            return 0;
        }
        java.util.Map<String, Double> params = new java.util.HashMap<>();
        java.util.Map<String, String> texts = new java.util.HashMap<>();
        if (spec.hasParams() && spec.params().size() == 1) {
            var e = spec.params().entrySet().iterator().next();
            Double num = null;
            try {
                num = Double.valueOf(raw);
            } catch (NumberFormatException ignore) {
                // 不是数字 ⇒ 按文本走（这正是 only_item / ban_item 的用法）
            }
            if (!e.getValue().text() && num != null) {
                params.put(e.getKey(), num);
            } else {
                texts.put(e.getKey(), raw);
            }
        }
        String msg = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .issue(maid, id, params, texts, 0, "player",
                        ctx.getSource().getLevel().getGameTime());
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), false);
        return 1;
    }

    /** {@code /flp directive set <女仆> <id> text <文本>} —— 下达（文本参数，如 ban_focus）。 */
    private static int directiveSetText(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        var maid = maidOf(ctx);
        if (maid == null) {
            return 0;
        }
        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
        String text = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "text");
        var spec = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec.find(id);
        if (spec == null) {
            ctx.getSource().sendFailure(Component.literal("§c未知指令 " + id));
            return 0;
        }
        java.util.Map<String, String> texts = new java.util.HashMap<>();
        if (spec.hasParams() && spec.params().size() == 1) {
            var e = spec.params().entrySet().iterator().next();
            texts.put(e.getKey(), text);
        }
        String msg = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .issue(maid, id, java.util.Map.of(), texts, 0, "player",
                        ctx.getSource().getLevel().getGameTime());
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), false);
        return 1;
    }

    /** {@code /flp directive clear <女仆> [id|all]} —— 取消指令。 */
    private static int directiveClear(CommandContext<CommandSourceStack> ctx, String id)
            throws CommandSyntaxException {
        var maid = maidOf(ctx);
        if (maid == null) {
            return 0;
        }
        String msg = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder
                .cancel(maid, id, ctx.getSource().getLevel().getGameTime(), "player");
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), false);
        return 1;
    }

    /** 从命令里取女仆（不是女仆就报错并返回 null）。 */
    private static com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maidOf(
            CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "maid");
        if (entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m) {
            return m;
        }
        ctx.getSource().sendFailure(Component.literal("§c那不是女仆"));
        return null;
    }

    /** {@code /flp tune llm now <女仆>} —— 立刻问一次 LLM 指挥（不等间隔）。 */
    private static int tuneLlmNow(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "maid");
        if (!(entity instanceof EntityMaid maid)) {
            ctx.getSource().sendFailure(Component.literal("§c那不是女仆"));
            return 0;
        }
        var st = PlayerLikeCombat.llmOf(maid);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c这个女仆还没进过决策循环（让她先打一架）"));
            return 0;
        }
        com.touhoulittlemad.fightlikeplayer.compat.think.LlmBridge.forceNow(st);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已请求立刻问一次 LLM（下一个 tick 提交；约 1~3 秒后用 /flp tune llm 看「最近建议」）"),
                false);
        return 1;
    }

    // ───────────────────────── ★★ 每女仆的独立旋钮 ─────────────────────────

    /**
     * {@code /flp tune maid <女仆>} —— <b>这个女仆自己的旋钮</b>（父层是全局）。
     *
     * <p>★ 委托方要求「女仆之间的独立」：全局那份是<b>默认</b>，本层是<b>她被单独调过的部分</b>。
     * 读取沿链向上找 ⇒ 没有单独调过时，全局改动立刻对她生效。
     */
    private static int tuneMaidShow(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof EntityMaid maid)) {
                continue;
            }
            TuningBus bus = PlayerLikeCombat.tuningOf(maid);
            StringBuilder sb = new StringBuilder("§b" + maid.getName().getString()
                    + "§r 的旋钮（父层 = 全局）\n");
            if (bus == null) {
                sb.append("§7  （她还没进过决策循环 ⇒ 还没有自己的总线）§r\n");
            } else {
                sb.append(bus.describeForPlayer());
                sb.append("§7  本层覆盖：").append(bus.isDefault() ? "（无，全部继承全局）"
                        : bus.overrides().toString()).append("§r\n");
            }
            sb.append("§7  用法：/flp tune maid <女仆> <键> <值>　·　/flp tune maid <女仆> reset§r");
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            n++;
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }

    /** {@code /flp tune maid <女仆> <键> <值>} —— 只改<b>这一个女仆</b>。 */
    private static int tuneMaidSet(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        String key = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key");
        double value = DoubleArgumentType.getDouble(ctx, "value");
        int n = 0;
        StringBuilder out = new StringBuilder();
        for (Entity e : targets) {
            if (!(e instanceof EntityMaid maid)) {
                continue;
            }
            TuningBus bus = PlayerLikeCombat.tuningOf(maid);
            if (bus == null) {
                out.append("§c").append(maid.getName().getString())
                        .append(" 还没进过决策循环，暂时没有自己的总线§r\n");
                continue;
            }
            bus.clearRejection();
            if (!bus.set(key, value)) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c" + bus.lastRejection() + "\n§7 可用的键见 /flp tune"));
                return 0;
            }
            out.append("§a已设 ").append(maid.getName().getString()).append(" 的 ")
                    .append(key).append(" = ").append(bus.get(key))
                    .append(bus.lastRejection() == null ? "" : "　§e(" + bus.lastRejection() + "§e)")
                    .append("§r\n");
            n++;
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        String msg = out.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return n;
    }

    /** {@code /flp tune maid <女仆> reset} —— 只清掉<b>这一个女仆</b>的覆盖（回到继承全局）。 */
    private static int tuneMaidReset(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                TuningBus bus = PlayerLikeCombat.tuningOf(maid);
                if (bus != null) {
                    bus.resetAll();
                }
                n++;
            }
        }
        int cnt = n;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已清掉 " + cnt + " 个女仆的独立旋钮（它们回到继承全局）"), true);
        return n;
    }

    /** {@code /flp tune advisor now <女仆>} —— 立刻问一次顾问（不等 30 秒）。 */
    private static int tuneAdvisorNow(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof EntityMaid maid)) {
                continue;
            }
            if (!FlpConfig.get(FlpConfig.ADVISOR_ENABLED, Boolean.FALSE)) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c顾问没开：配置里把 advisor.enabled 设为 true（建议同时保持 "
                                + "advisor.applyMode = observe，先看它想怎么调）"));
                return 0;
            }
            if (!FlpConfig.thinkConfigured()) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c没配 API Key：思维层与顾问共用同一份 Key（模组配置界面 → thinking.apiKey）"));
                return 0;
            }
            var st = PlayerLikeCombat.advisorOf(maid);
            if (st == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "§c" + maid.getName().getString() + " 还没进过决策循环 ⇒ 没有统计可看"));
                return 0;
            }
            com.touhoulittlemad.fightlikeplayer.compat.think.AdvisorBridge.forceNow(st);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§a已请求立刻问一次顾问：§r" + maid.getName().getString()
                            + "（下一个 tick 提交；约 1 秒后用 /flp tune advisor 看「最近建议」）"), false);
            n++;
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }

    private static int spring(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§b" + maid.getName().getString() + "§r 弹簧="
                                + PlayerLikeCombat.springPoint(maid)), false);
                n++;
            }
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                PlayerLikeCombat.resetSpring(maid);
                n++;
            }
        }
        int cnt = n;
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重置 " + cnt + " 个女仆的弹簧"), true);
        return n;
    }

    /** ★★ 注入 8 轴决策向量。 */
    private static int bias(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "maid");

        double[] v = new double[NeedAxis.COUNT];
        v[0] = DoubleArgumentType.getDouble(ctx, "a1");
        v[1] = DoubleArgumentType.getDouble(ctx, "a2");
        v[2] = DoubleArgumentType.getDouble(ctx, "a3");
        v[3] = DoubleArgumentType.getDouble(ctx, "a4");
        v[4] = DoubleArgumentType.getDouble(ctx, "a5");
        v[5] = DoubleArgumentType.getDouble(ctx, "a6");
        v[6] = DoubleArgumentType.getDouble(ctx, "a7");
        v[7] = DoubleArgumentType.getDouble(ctx, "a8");

        int n = 0;
        for (Entity e : targets) {
            if (e instanceof EntityMaid maid) {
                if (PlayerLikeCombat.injectBias(maid, v, "command")) {
                    PlayerLikeCombat.flush(maid);       // 立刻消化 ⇒ 命令反馈即时可见
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a注入 " + maid.getName().getString() + " → 弹簧现在="
                                    + PlayerLikeCombat.springPoint(maid)), true);
                    n++;
                }
            }
        }
        if (n == 0) {
            ctx.getSource().sendFailure(Component.literal("没有找到车万女仆实体"));
            return 0;
        }
        return n;
    }
}
