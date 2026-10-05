package com.touhoulittlemad.fightlikeplayer.compat.directive;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.compat.exec.ActionExecutors;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★★ <b>指令总线的持有者</b>（每女仆一份）+ 瞬间指令的执行器（M2 的落点 ⑤）。
 *
 * <h2>为什么要有这个"持有者"</h2>
 * 指令的**消费者散落在四处**：行为层（候选集过滤）、执行器（换手前置、枪械发数）、
 * 聚晶挑选（Goety/铁魔法）、步法。若靠参数一路传下去，签名会被污染一大片
 * （本项目已经吃过"两处口径不一致"的亏，见 docs/13 §38）。
 * ⇒ 统一走"按女仆取总线"（与 {@code GoetyChannel} 的通道注册表同构）。
 *
 * <p>★ 纯逻辑的判据全部在 {@link DirectiveBus} / {@code DirectiveFilter} 里；
 * 本类只负责**存取**与**把瞬间指令落到游戏动作上**（那一部分必须碰 MC 类型）。
 */
public final class DirectiveHolder {

    private DirectiveHolder() {
    }

    /** 每女仆：总线 + 守点锚点 + 强制举盾的截止时刻。 */
    private static final class Entry {
        final DirectiveBus bus = new DirectiveBus();
        /** {@code hold_position} 的锚点（下达那一刻的位置）。 */
        BlockPos anchor;
        /** {@code stance_guard} 的强制截止时刻（> now 表示"强制举盾中"）。 */
        long guardUntil;
        /**
         * ★★ 最近一条瞬间指令**做了什么**（第十七轮续）。
         *
         * <p>为什么必须有：委托方实测「处死仆从/换物**完全不起效**」，而原来唯一能看出来的是
         * 日志里那几行 INFO（`/flp directive list` 看不到）。⇒ 现在结果进内存，
         * 命令与 `describe` 都能读 —— 这正是 docs/13 第 19 条立的规矩
         * （可预期的失败必须有用户可读的出口）。
         */
        String lastInstantResult;
        /** ★ 指令伺服最近一次"换不了手"的原因（只在内容变化时记，避免每 tick 刷屏）。 */
        String servoNote;
        /**
         * ★★ "她要的那件东西"从哪一 tick 开始**不在她身上**（0 = 现在在身上 / 没有物品指令）。
         *
         * <p>用途（第十七轮续）：一条**拿不到东西**的物品指令会处于 ABSENT ——
         * 按护栏它**不过滤候选集**（怕清空），于是"只用 X"静默变成空操作，
         * 同时还让伺服每 tick 尝试换一把不存在的东西。
         * ⇒ 连续 {@link #ITEM_ABSENT_GRACE_TICKS} 都拿不到 ⇒ **自动解除**（留痕 + 说明）。
         */
        long itemAbsentSince;

        /**
         * ★★ 最近一次**取消**（第十七轮续）：`only_item（来自 chat）`。
         *
         * <p>为什么必须有：`cancel` 以前**一行日志都不写** ⇒ 指令"悄悄消失"时
         * 谁也说不清是谁撤的（委托方实测「因为不在战斗快速自动消失」）。
         */
        String lastCancel;
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private static Entry entry(EntityMaid maid) {
        return ENTRIES.computeIfAbsent(maid.getUUID(), k -> new Entry());
    }

    /** 取她的指令总线（**永远非 null** ⇒ 消费侧不必判空）。 */
    public static DirectiveBus of(EntityMaid maid) {
        return maid == null ? new DirectiveBus() : entry(maid).bus;
    }

    /** 守点锚点（没有则为 null）。 */
    public static BlockPos anchorOf(EntityMaid maid) {
        Entry e = maid == null ? null : ENTRIES.get(maid.getUUID());
        return e == null ? null : e.anchor;
    }

    /** 是否处于"强制举盾"窗口内（副手姿态伺服会读它）。 */
    public static boolean forcedGuard(EntityMaid maid, long now) {
        Entry e = maid == null ? null : ENTRIES.get(maid.getUUID());
        return e != null && now < e.guardUntil;
    }

    // ───────────────────────── 下达（对外入口）─────────────────────────

    /**
     * 下达一条指令（供命令 / LLM / 女仆对话调用）。
     *
     * @param source "llm" / "player" / "chat"
     * @return 一行**可读**结果（成功/失败原因都写清楚）
     */
    public static String issue(EntityMaid maid, String id,
                               Map<String, Double> params, Map<String, String> texts,
                               int ttlTicks, String source, long now) {
        Entry e = entry(maid);
        // ★★ 第二十一轮（委托方第 2 条）：「主人没明说禁用召唤，就不要下禁用召唤的指令」。
        //   **动态指挥官**（source=llm）看不到主人说了什么 ⇒ 它下 `no_summons`
        //   必然是自作主张；`dismiss_servants` 更是**不可逆的处死**。
        //   判据在纯逻辑层（DirectiveSpec#playerOnlyReason），这里只做执行 ——
        //   ★ 提示词里也写了（LlmAdvisor 规则 9），但**门必须在代码里**
        //     （本项目已经为"提示词写了、代码没守"栽过好几次）。
        String playerOnly = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec
                .playerOnlyReason(id);
        if (playerOnly != null && "llm".equals(source)) {
            FightLikePlayer.LOGGER.info("[FLP][directive] 拒绝自动指挥下达 {}：{}", id, playerOnly);
            return "★ 这条指令没有下达（" + playerOnly + "）："
                    + "只有主人明说时才行 —— 请你先问清主人的意思，再让我（对话）或主人（命令）下。";
        }
        // ★★ 第十六轮：把「几秒」这类"时长参数"接到 TTL 上（唯一出口在 DirectiveSpec#ttlTicksFrom）。
        //   委托方要的是「接下来**几秒**只进行这些动作」—— 时长属于 TTL，不属于动作本身。
        int ttl = com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec
                .ttlTicksFrom(id, params, ttlTicks);
        var r = e.bus.issue(id, params, texts, ttl, source, now);
        if (r.ok()) {
            // 守点锚点在下达那一刻记下
            if ("hold_position".equals(id)) {
                e.anchor = maid.blockPosition();
            }
            FightLikePlayer.LOGGER.info("[FLP][directive] {} ← {}：{}", id, source, r.message());
        } else {
            FightLikePlayer.LOGGER.info("[FLP][directive] 丢弃：{}", r.message());
        }
        return r.message()
                + (r.ok() ? feasibility(maid, id, texts) : "")
                + (r.ok() ? resolveConflict(maid, now) : "")
                + (r.ok() && "hold_position".equals(id) ? "（锚点 " + maid.blockPosition() + "）" : "");
    }

    /**
     * ★★★ <b>指令组合冲突体检</b>（第二十八轮，委托方实测「枪械用不出来」）。
     *
     * <h2>那次的现象（日志原文）</h2>
     * <pre>
     *   close_in  ← llm ：贴身缠斗（按设计**禁用远程**）
     *   only_item ← chat：只用 tacz:uzi（只留"由它承载"的动作）
     *   [directive] main 被指令挡下：tacz:shoot, bow_shot, trident …
     *   [main] → fight_like_player:disengage     ← **唯一活着的候选是"撤退"**
     * </pre>
     * 两条指令**各自都合理**，交集却是空的 —— 而"绝不允许候选集变成空的"这条头号纪律，
     * 此前只做在**单条指令内部**（物品 ABSENT 回退、白名单看门狗），**跨指令组合没人管**。
     *
     * <h2>做法</h2>
     * 每当下达一条新指令时**体检一次**（不是每 tick）：
     * 用与决策层同一条路径算出"过滤之后还剩几个能打的动作"（{@code PlayerLikeCombat#attackCandidatesLeft}），
     * 一个都不剩 ⇒ 让**最该让位的那条**退场（判据在纯逻辑层 {@code ConflictResolver}：
     * 主人的话优先，先撤 {@code llm} 的站位约束，再撤它的手段限制，**主人的指名道姓永不撤**），
     * 并把这件事写进回话（她就能转述给主人，而不是"只会站着撤退"）。
     */
    private static String resolveConflict(EntityMaid maid, long now) {
        try {
            var bus = entry(maid).bus;
            if (bus.activeCount() <= 1) {
                return "";                       // 只有一条指令 ⇒ 谈不上冲突
            }
            java.util.List<String> left = com.touhoulittlemad.fightlikeplayer.compat.behavior
                    .PlayerLikeCombat.attackCandidatesLeft(maid);
            if (left == null || !left.isEmpty()) {
                return "";                       // ★ null = 判不出来（没在打架/清单没加载）⇒ 不体检
            }
            // ★ 一个能打的动作都不剩 ⇒ 按来源优先级让位
            java.util.Map<String, String> actives = new java.util.LinkedHashMap<>();
            for (var en : bus.active().entrySet()) {
                actives.put(en.getKey(), en.getValue().source());
            }
            String yield = com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                    .pickYielding(actives);
            if (yield == null) {
                FightLikePlayer.LOGGER.info("[FLP][directive] 指令组合把攻击动作清空了，"
                        + "但没有可自动撤销的（都是主人下的）：{}", actives.keySet());
                return "　★★ 现在这些指令的组合**把她能打的动作全挡掉了**（只剩走位/撤退）——"
                        + "都是主人自己下的，我不自动撤销，请主人决定撤哪一条。";
            }
            String src = actives.get(yield);
            boolean ok = bus.cancel(yield);
            FightLikePlayer.LOGGER.info("[FLP][directive] ★ 组合冲突：{}（来自 {}）挡住了她所有攻击动作"
                    + " ⇒ 自动撤销它（主人的指令优先）", yield, src);
            return "　★★ 这些指令的组合**把她能打的动作全挡掉了**（例如「贴身」禁远程 +"
                    + "「只用某件」禁别的）⇒ 我**自动撤销了** " + yield + "（来自 " + src + "，"
                    + com.touhoulittlemad.fightlikeplayer.decision.ConflictResolver
                            .describeClass(yield)
                    + "）——主人的指令优先。";
        } catch (RuntimeException | LinkageError e) {
            FightLikePlayer.LOGGER.debug("[FLP][directive] 冲突体检出错（忽略）：{}", e.toString());
            return "";
        }
    }

    /**
     * ★★ <b>「做不到的事就如实反馈」的运行时一半</b>（第十七轮，委托方第 7 条）。
     *
     * <p>指令一经下达就返回一句话 —— 而那句话是<b>上层 LLM 唯一能转述给玩家的东西</b>。
     * 若它只说"已下达"，LLM 就会回玩家"好的这就做"，哪怕她名下<b>一个仆从都没有</b>。
     * ⇒ 这里在"下达成功"之后<b>再补一句现状</b>（不是拒绝执行：指令照下，只是把真相说出来）。
     *
     * <p>★ 与提示词那一半配套：skill.md 里写明「工具结果出现『不会有任何效果 / 她身上没有』
     * 这类字样 ⇒ 照实回话，不要假装已经做了」。
     *
     * <p>★ 整体包在 {@code catch (Throwable)} 里：反馈只是附加信息，
     * 绝不能因为它失败而让"指令下达"失败。
     */
    /**
     * ★★ <b>物品类指令的"如实反馈"</b>（委托方第二轮：「不管让她使用什么，都用的火箭筒」）。
     *
     * <p>下这条指令时最要紧的一件事：<b>把它写的那串东西落到她身上具体是哪一件</b>。
     * 三种结果都要说回去 —— 因为上层模型**看不到游戏里的物品栏**，它只能靠这句话纠正自己：
     * <ul>
     *   <li>匹配到 ⇒ 「她那件是 {@code tacz:rpg7}（在 INVENTORY）」；</li>
     *   <li>匹配到多件且强度并列 ⇒ 明说"含糊"并建议写全 id；</li>
     *   <li>一件都不匹配 ⇒ 「她身上没有能匹配「rifle」的东西 ⇒ 这条指令不会有任何效果」，
     *       并**把她身上真实存在的 id 列出来**（否则模型只能继续猜，下一轮还是错）。</li>
     * </ul>
     */
    private static String itemFeedback(EntityMaid maid, Map<String, String> texts) {
        String want = texts == null ? null : texts.get("item");
        if (want == null || want.isBlank()) {
            return "　★ 但这条指令没带物品参数 ⇒ 不会生效。";
        }
        java.util.List<com.touhoulittlemad.fightlikeplayer.carrier.ItemRef> refs =
                new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> slots = new java.util.ArrayList<>();
        for (var it : com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot.possessed(maid)) {
            refs.add(com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(it));
            ids.add(it.itemId());
            slots.add(it.slot().name());
        }
        var res = com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery.resolve(want, refs);
        if (res.isEmpty()) {
            StringBuilder sb = new StringBuilder("　★ 她身上**没有**能匹配「" + want
                    + "」的东西 ⇒ 这条指令不会有任何效果（她自己照常打）。");
            if (!ids.isEmpty()) {
                sb.append("她身上有（前 8 件）：");
                for (int i = 0; i < ids.size() && i < 8; i++) {
                    if (i > 0) {
                        sb.append("、");
                    }
                    sb.append(ids.get(i));
                }
            }
            return sb.toString();
        }
        var best = res.best();
        String hit = ids.get(best.index());
        StringBuilder sb = new StringBuilder("　★ 她那件是 ").append(hit)
                .append("（在 ").append(slots.get(best.index())).append("，命中强度 ")
                .append(best.match()).append("）");
        if (res.ambiguous()) {
            sb.append("；★ 但**含糊**：还匹配到 ");
            int n = 0;
            for (String other : res.ids()) {
                if (other.equals(hit)) {
                    continue;
                }
                if (n++ > 0) {
                    sb.append("、");
                }
                sb.append(other);
            }
            sb.append(" ⇒ 建议写全 id");
        }
        return sb.toString();
    }

    /**
     * ★★ <b>她此刻真正能放的法术标签（label）</b>（第二十七轮）。
     *
     * <p>用途：`only_focus` / `ban_focus` 的**参数核对** —— 这两个指令的参数是**法术标签**，
     * 而模型很容易把它当成物品 id 去编（委托方实测就编出了 `goety:vanguard_focus`）。
     * ⇒ 下达那一刻把"她实际有哪些 label"回给模型，它下一句就能改对（而不是静默失败）。
     *
     * <p>★ 判据复用两条既有枚举（**不另写一遍**）：Goety 聚晶池 `GoetyFocusOps#available`
     * 、铁魔法 `IronsSpells#options`；★ 不传 bus（要看到**真实**的池子，不受她自己指令影响）。
     */
    static java.util.List<String> knownSpellLabels(EntityMaid maid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
                // ★★ 用**无门**的枚举（`ownedLabels`）：有门的那条会因为"召唤位已满"而
                //   把先锋聚晶（SUMMON 类）藏起来 ⇒ 会得出"她没有这颗"的假否定。
                out.addAll(com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                        .GoetyFocusOps.ownedLabels(maid));
            }
        } catch (RuntimeException | LinkageError e) {
            // 读不到 ⇒ 这一路不贡献（另一路照常）
        }
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")) {
                out.addAll(com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                        .IronsSpells.ownedLabels(maid));
            }
        } catch (RuntimeException | LinkageError e) {
            // 同上
        }
        // ★ 去重且保持稳定顺序（同一颗法术在多本书/多处时只报一次）
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>(out);
        return java.util.List.copyOf(uniq);
    }

    /**
     * ★★ <b>她有这颗、但"现在放不出来"的原因</b>（第二十七轮；{@code null} = 现在就能放）。
     *
     * <p>判据 = 对比两条枚举：**无门**的 {@code ownedLabels}（她有没有）与**有门**的
     * {@code available/options}（她现在能不能放）。差在哪 ⇒ 原因就在哪：
     * <ol>
     *   <li>召唤位满（{@code ServantSlots} 上限）—— 指挥方实测那种：先锋聚晶是 SUMMON 类；</li>
     *   <li>被她自己正在生效的指令挡着（{@code focus_category} / {@code ban_focus} /
     *       {@code only_focus} / {@code only_actions}）。</li>
     * </ol>
     * ★ 只报**判据能确定**的（召唤位那条有精确数字）；剩下的归到"她自己的指令"这一类，
     * 不编造更具体的原因。
     */
    static String notCastableReason(EntityMaid maid, String label) {
        try {
            boolean castableNow = false;
            if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
                for (var f : com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                        .GoetyFocusOps.available(maid, null)) {
                    if (f.label().equalsIgnoreCase(label)) {
                        castableNow = true;
                        break;
                    }
                }
            }
            if (!castableNow && net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")) {
                for (var o : com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                        .IronsSpells.options(maid, null)) {
                    if (o.label().equalsIgnoreCase(label)) {
                        castableNow = true;
                        break;
                    }
                }
            }
            if (castableNow) {
                return null;
            }
            try {
                int count = 0;
                if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) {
                    count += com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                            .GoetyServantOps.servantCount(maid);
                }
                if (net.minecraftforge.fml.ModList.get().isLoaded("irons_spellbooks")) {
                    count += com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                            .IronsServantOps.countSummons(maid);
                }
                if (!com.touhoulittlemad.fightlikeplayer.decision.ServantSlots
                        .summonAllowed(count)) {
                    return "召唤位已满（她现在有 " + count + " 个仆从/召唤物，上限 "
                            + com.touhoulittlemad.fightlikeplayer.decision.ServantSlots.CAP
                            + "）";
                }
            } catch (RuntimeException | LinkageError ignored) {
                // 数不出来 ⇒ 归到下面那一类
            }
            return "被她自己正在生效的某条指令挡着（focus_category / ban_focus / only_focus 之类）";
        } catch (RuntimeException | LinkageError e) {
            return null;                       // 判不出来就说"能放"（宁可少说，不编原因）
        }
    }

    private static String feasibility(EntityMaid maid, String id, Map<String, String> texts) {        try {
            boolean goetyLoaded = net.minecraftforge.fml.ModList.get().isLoaded("goety");
            int goety = goetyLoaded
                    ? com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                            .GoetyServantOps.servantCount(maid)
                    : 0;
            switch (id) {
                case "dismiss_servants" -> {
                    int irons = com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                            .IronsServantOps.countSummons(maid);
                    if (goety + irons == 0) {
                        return "　★ 但她名下现在一个仆从都没有（Goety 0 + 铁魔法 0）"
                                + "⇒ 这条指令不会有任何效果。";
                    }
                    return "　★ 这会**不可逆地**处死她名下的仆从（Goety " + goety
                            + " + 铁魔法 " + irons + "）。";
                }
                case "recall_servants" -> {
                    return goety == 0 ? "　★ 但她名下没有仆从 ⇒ 没有可召回的。"
                            : "　★ 她名下有 " + goety + " 个仆从。";
                }
                // ★★ 第十七轮：物品类指令必须把"她那件到底是哪一件"说回去
                //   （否则模型写错 id 时会以为指令生效了 ⇒ 观感"不管让她用什么都是火箭筒"）
                case "only_item", "ban_item", "switch_item" -> {
                    String fb = itemFeedback(maid, texts);
                    // ★★ 第十七轮续：`switch_item` 只是**换一次手** —— 委托方实测"让她用枪"
                    //   时她只下了这条 ⇒ 遇到敌人就被执行器换成三叉戟了。回话里教会配对。
                    return "switch_item".equals(id)
                            ? fb + "　★ 注意：这只是换一次手；要她一直用，请再下 only_item"
                                    + "（同一个值），否则下一个动作就会把它换走。"
                            : fb;
                }
                // ★★ 第二十一轮：施法体系细分（"只用铁魔法" / "只用巫法"）也要有回话。
                //   委托方上一轮的现象正是「她说要铁魔法，其实两条法术在她眼里是同一个类」
                //   ⇒ 现在细分之后，模型最容易犯的错变成"下了一条她根本放不出来的指令"
                //   （比如她一本书都没有）。★ 那就是本项目的头号禁令："候选集被指令清空"，
                //   必须在**下达的当下**就说出来，而不是等她站着不动才被发现。
                case "goety_only", "irons_only" -> {
                    boolean ironsSpell = "irons_only".equals(id);
                    String mod = ironsSpell ? "irons_spellbooks" : "goety";
                    if (!net.minecraftforge.fml.ModList.get().isLoaded(mod)) {
                        return "　★ 但她这一局没有装载" + (ironsSpell ? "铁魔法" : "诡厄巫法")
                                + "（" + mod + " 不在）⇒ 这条指令会清空她的候选集"
                                + "（她会什么都不放）。★ 要「只要是魔法就行」请改下 magic_only。";
                    }
                    int n = ironsSpell
                            ? com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                                    .IronsSpells.options(maid, null).size()
                            : com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                                    .GoetyFocusOps.available(maid, null).size();
                    if (n == 0) {
                        return "　★ 但她现在身上一个" + (ironsSpell ? "铁魔法法术" : "巫法聚晶")
                                + "都没有 ⇒ 这条指令会让她的候选集变空（她会不打）。"
                                + "★ 先让她拿到" + (ironsSpell ? "法术书/法杖" : "聚晶") + "再下这条。";
                    }
                    return "　★ 她现在有 " + n + " 个" + (ironsSpell ? "铁魔法法术" : "巫法聚晶")
                            + "可用。";
                }
                // ★★ 第二十七轮（委托方实测：她想"仅用先锋聚晶施法"，模型**编了一个物品 id**
                //   `goety:vanguard_focus` ⇒ 伺服报"她身上没有" ⇒ 5 秒后自动解除，
                //   而她完全不知道为什么）。
                //   ⇒ 这两条指令的参数是**法术标签（label）**，必须在**下达那一刻**就核对：
                //     她到底有没有这颗？没有的话**她实际有哪些 label** 一并列出来 ——
                //     否则 `only_focus` 会把全部法术挡掉 ⇒ 候选集空 ⇒ 她站着不动（静默卡死）。
                case "only_focus", "ban_focus" -> {
                    String label = texts == null ? null : texts.get("label");
                    if (label == null || label.isBlank()) {
                        return "　★ 但这条指令**没带 label**（参数名必须是 label）⇒ 它不会有任何效果。";
                    }
                    java.util.List<String> mine = knownSpellLabels(maid);
                    if (mine.isEmpty()) {
                        return "　★ 但她现在**一个法术/聚晶都放不出来** ⇒ 这条指令"
                                + ("only_focus".equals(id) ? "会让她什么都不放。" : "没有对象。");
                    }
                    if (mine.stream().anyMatch(l -> l.equalsIgnoreCase(label))) {
                        // ★★ 有这颗 ⇒ 再确认"现在放不放得出来"（有门的那条枚举里在不在）。
                        //   委托方那次的现象正是：先锋聚晶是 SUMMON 类而**召唤位满了**
                        //   ⇒ 即使 label 写对了，她也不会放。这种"她有、但现在用不了"必须说清，
                        //   否则模型会以为指令已生效（观感"下了指令她不动"）。
                        String blocked = notCastableReason(maid, label);
                        return blocked == null
                                ? "　★ 她有这颗（label=" + label + "），且现在就能放。"
                                : "　★★ 她有这颗（label=" + label + "），但**现在放不出来**："
                                        + blocked;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("　★★ 但她**没有** label=").append(label).append(" 这颗法术/聚晶 ⇒ ");
                    sb.append("only_focus".equals(id)
                            ? "这条指令会让她的候选集变空（她会站着不动）——**请改成下面其中一个**："
                            : "这条指令不会有任何效果（她本来就不放它）——她实际有的是：");
                    int n = 0;
                    for (String l : mine) {
                        if (n++ >= 12) {
                            sb.append("…");
                            break;
                        }
                        sb.append(n == 1 ? "" : "、").append(l);
                    }
                    return sb.toString();
                }
                default -> {
                    return "";
                }
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 取消一条 / 全部。
     *
     * @param source ★ 谁在取消：{@code chat}（女仆对话）/ {@code player}（命令）/ {@code llm}（动态指挥）
     */
    public static String cancel(EntityMaid maid, String id, long now, String source) {
        Entry e = entry(maid);
        if ("all".equals(id)) {
            var ids = e.bus.cancelAll();
            if (ids.isEmpty()) {
                return "本来就没有指令";
            }
            e.lastCancel = "全部（来自 " + source + "）：" + String.join(",", ids);
            FightLikePlayer.LOGGER.info("[FLP][directive] 取消全部指令（来自 {}）：{}", source,
                    String.join(",", ids));
            return "已取消全部：" + String.join(",", ids);
        }
        // ★★ 策略（第十七轮续）：**动态指挥不许撤掉"玩家/对话明确下达"的指令**。
        //   理由：委托方的抱怨是"我的指令莫名消失"；权限必须有明确规则 ——
        //   动态指挥要改就打自己那条（或先 issue 覆盖），玩家/她自己的对话随时可以撤。
        var active = e.bus.active().get(id);
        if (active != null && "llm".equals(source)
                && ("player".equals(active.source()) || "chat".equals(active.source()))) {
            String why = "「" + id + "」是" + ("player".equals(active.source()) ? "玩家" : "她自己的对话")
                    + "下达的 ⇒ 动态指挥不能撤（它可以 issue 一条新的来覆盖，或等它自己过期）";
            FightLikePlayer.LOGGER.info("[FLP][directive] 拒绝取消：{}", why);
            return "拒绝取消：" + why;
        }
        boolean ok = e.bus.cancel(id);
        e.lastCancel = id + "（来自 " + source + "）" + (ok ? "" : " —— 但它本来就没生效");
        FightLikePlayer.LOGGER.info("[FLP][directive] 取消 {}（来自 {}）：{}", id, source,
                ok ? "已取消" : "它本来就没生效");
        return ok ? "已取消 " + id : "没有生效中的 " + id;
    }

    // ───────────────────────── 每 tick ①：过期与瞬间指令 ─────────────────────────

    /**
     * 每 tick 调一次（主线程，行为层最前面）：① 清过期 ② 执行待办的瞬间指令。
     *
     * <p>★ 刻意放在**行为层最前面**：瞬间指令的语义是"立刻"，晚一拍就不叫立刻了；
     * 而且它与"决策"无关（不占承诺、不扣代价）。
     */
    public static void tick(EntityMaid maid, long now) {
        Entry e = entry(maid);
        var expired = e.bus.tick(now);
        // ★★ 瞬间指令的保鲜期：放太久（她当时没在战斗、行为层没跑）⇒ 作废并**说明原因**
        //   —— 否则它会在她下次进战斗时突然生效，观感上就是"几分钟前说的话突然执行"。
        var stale = e.bus.lastStaleInstant();
        if (!stale.isEmpty()) {
            FightLikePlayer.LOGGER.info("[FLP][directive] 瞬间指令 {} 下达后 {} tick 内没被执行"
                            + "（她当时没在战斗）⇒ 作废（瞬间 = 立刻，晚这么久就不叫立刻了）",
                    String.join(",", stale), com.touhoulittlemad.fightlikeplayer.decision.DirectiveBus
                            .INSTANT_MAX_AGE_TICKS);
        }
        for (String id : expired) {
            FightLikePlayer.LOGGER.info("[FLP][directive] 指令 {} 已过期（TTL 到），自动解除", id);
        }
        DirectiveBus.Active inst;
        while ((inst = e.bus.pollInstant()) != null) {
            applyInstant(maid, e, inst, now);
        }
    }

    /**
     * ★ <b>记下"这条瞬间指令做了什么"</b>（第十七轮续）。
     *
     * <p>两条出口一起走：日志（INFO，实时）+ {@code Entry#lastInstantResult}
     * （供 {@code /flp directive list} 与 {@code describe} 回看）。
     */
    private static void noteInstant(Entry e, String id, String result) {
        e.lastInstantResult = id + " ⇒ " + result;
        FightLikePlayer.LOGGER.info("[FLP][directive] 瞬间指令 {}：{}", id, result);
    }

    /** 把一条瞬间指令落到具体动作上（这一层必须碰 MC 类型）。 */
    private static void applyInstant(EntityMaid maid, Entry e, DirectiveBus.Active a, long now) {
        String id = a.id();
        try {
            switch (id) {
                case "interrupt" -> {
                    ActionExecutors.abort(maid);
                    noteInstant(e, id, "已中断 " + name(maid) + " 的在途动作");
                }
                case "disengage" -> {
                    int ticks = (int) Math.round(a.params().getOrDefault("ticks", 60.0));
                    // ★ 清掉仇恨记忆 ⇒ 行为层的门控（有目标才跑）自然解除 ⇒ 她脱离战斗
                    maid.getBrain().eraseMemory(
                            net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
                    // 再补一条"保持距离"，让她立刻拉开（持续指令，到点自动过期）
                    e.bus.issue("keep_distance", Map.of("min", 12.0), Map.of(), ticks, "directive", now);
                    noteInstant(e, id, "已脱离战斗并后撤 " + ticks + " tick");
                }
                case "reload_now" -> {
                    // 交给 TaCZ：让它自己走 NO_AMMO 那条分支（我们只"请求"一次换弹）
                    ActionExecutors.requestReload(maid);
                    noteInstant(e, id, "已请求换弹（主手是枪才会真的换，见上一条日志）");
                }
                case "recall_servants" -> {
                    // ★ 第十七轮续：这条以前**连一行日志都没有**（"召回了几个"看不见）
                    int n = net.minecraftforge.fml.ModList.get().isLoaded("goety")
                            ? com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                                    .GoetyServantOps.recallServants(maid)
                            : 0;
                    noteInstant(e, id, "召回 " + n + " 只仆从"
                            + (n == 0 ? "（她名下没有仆从可用）" : ""));
                }
                case "dismiss_servants" -> {
                    // ★★ 第十七轮（委托方第 5/6 条）：
                    //   ① 原来用 dismissTemporaryServants —— 它内部有 `!isLimitedLife() ⇒ continue`
                    //      ⇒ **只清"限时"仆从**，她的常驻仆从一个都不动 ⇒ 观感就是"这条指令没用"（第 6 条）。
                    //      现在改用 dismissAllServants：不看是否限时，凡 getTrueOwner()==她 一律 dismiss。
                    //   ② 补上**铁魔法那一半**（第 5 条：此前只有 Goety，铁魔法的召唤物一只都不会走）。
                    boolean goetyLoaded = net.minecraftforge.fml.ModList.get().isLoaded("goety");
                    int goety = goetyLoaded
                            ? com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                                    .GoetyServantOps.dismissAllServants(maid,
                                            com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                                                    .GoetyServantOps.NO_WHITELIST)
                            : 0;
                    int irons = com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                            .IronsServantOps.dismissAllSummons(maid);
                    noteInstant(e, id, "处死全部仆从：Goety " + goety + " 只 + 铁魔法 " + irons + " 只"
                            + (goety + irons == 0 ? "（她名下本来就没有仆从）" : ""));
                }
                case "stance_guard" -> {
                    int ticks = (int) Math.round(a.params().getOrDefault("ticks", 60.0));
                    e.guardUntil = now + ticks;
                    noteInstant(e, id, "强制举盾 " + ticks + " tick");
                }
                case "stance_attack" -> {
                    e.guardUntil = 0;
                    com.touhoulittlemad.fightlikeplayer.compat.behavior.OffhandStance
                            .forceRelease(maid);
                    noteInstant(e, id, "已收盾进攻");
                }
                case "heal_now" -> noteInstant(e, id,
                        "已请求立刻治疗（由选法环节按需求优先治疗类）");
                case "switch_item" -> {
                    // ★★ 第十六轮（委托方要求）：立刻把某件物品换到主手上。
                    //   默认先中断当前动作 —— 否则"换手"会与在途动作（引导/枪械/刀技）打架，
                    //   而换手失败的原因往往就是"她正在忙"（我们自己的守卫就会挡住换手）。
                    String want = a.texts().get("item");
                    boolean doInterrupt = a.params().getOrDefault("interrupt", 1.0) >= 0.5;
                    if (want == null || want.isBlank()) {
                        noteInstant(e, id, "没给物品名 ⇒ 忽略");
                        break;
                    }
                    if (doInterrupt) {
                        ActionExecutors.abort(maid);
                        com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel
                                .forget(maid.getUUID());      // 刀技也一并放弃（它在推一把要换走的刀）
                    }
                    String hitId = resolveItemId(maid, want);
                    if (hitId == null) {
                        noteInstant(e, id, "失败：她身上没有能匹配「" + want + "」的东西");
                        break;
                    }
                    boolean ok = com.touhoulittlemad.fightlikeplayer.compat.maid.HandEquip
                            .equipFromInventory(maid, net.minecraft.world.InteractionHand.MAIN_HAND,
                                    s -> !s.isEmpty() && hitId.equals(
                                            com.touhoulittlemad.fightlikeplayer.compat.maid
                                                    .MaidSnapshot.itemId(s)));
                    noteInstant(e, id, (ok ? "成功：" : "失败：") + "把 " + hitId
                            + " 换到主手（她说的「" + want + "」）"
                            + (ok ? "　★ 但这是一次性的：她随时会被别的动作换走 ——"
                                    + " 要**一直用它**请再下 `only_item`（值写同一个）"
                                    : "—— 详见「换手」那条日志"));
                }
                default -> noteInstant(e, id, "目前没有对应动作（已忽略）");
            }
        } catch (Throwable t) {
            // ★ 瞬间指令失败绝不能影响 tick（第三方 API 可能抛）
            e.lastInstantResult = id + " ⇒ 执行失败：" + t;
            FightLikePlayer.LOGGER.info("[FLP][directive] 瞬间指令 {} 执行失败：{}", id, t.toString());
        }
    }

    private static String name(EntityMaid maid) {
        return maid.getName().getString();
    }

    // ───────────────────────── ★★ 指令伺服：把要的东西换到手上 ─────────────────────────

    /**
     * ★★ <b>「指令要的那件东西必须在她手上」的每 tick 伺服</b>
     * （第十七轮续，委托方第 1 条：「女仆认得她有什么枪，但**拿出来**没有实际生效」）。
     *
     * <h2>为什么这件事不能挂在行为层</h2>
     * 它原来在 {@code PlayerLikeCombat#applyDirectives} 里 —— 而那个行为**只在她有攻击目标时运行**
     * ⇒ 玩家在非战斗时说「只用这把枪 / 拿出来」，换手这一步**永远不会发生**。
     * ⇒ 搬到这里（挂 {@code MaidTickEvent}），与战斗无关。
     *
     * <h2>三条判据（每一条都对应一个已知的坑）</h2>
     * <ol>
     *   <li>{@code no_item_switch} 生效 ⇒ 伺服**也不换**（她自己说了"不换手"，
     *       我们替她换就是违反那条指令）；</li>
     *   <li>主手已经匹配 ⇒ 立刻返回（**零开销**，且绝不来回换手）；</li>
     *   <li>正在引导法术 / 正在放刀技 ⇒ 这一 tick **不换**
     *       （换手会让通道中断、刀技作废 —— 与 {@code ensureCarrierInHand} 同一条守卫）。</li>
     * </ol>
     * ★ 找不到那件东西时，失败原因**只记一次**（内容变化才记）—— 否则每 tick 刷屏。
     */
    /**
     * "她要的东西一直不在她身上"多久 ⇒ 自动解除那条物品指令（tick）。
     * <p>取 **5 秒**：够她把东西捡回来/被换回来，又不至于让一条空指令长期挂着。
     */
    public static final int ITEM_ABSENT_GRACE_TICKS = 100;

    public static void itemServo(EntityMaid maid) {
        try {
            var e = entry(maid);
            var bus = e.bus;
            if (!com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter
                    .hasItemDirective(bus)) {
                return;                                  // 绝大多数 tick 在这里返回（零开销）
            }
            if (bus.has("no_item_switch")) {
                return;
            }
            String want = bus.has("only_item")
                    ? bus.textParam("only_item", "item")
                    : bus.textParam("ban_item", "item");
            if (want == null || want.isBlank()) {
                return;
            }
            var cur = maid.getMainHandItem();
            if (!cur.isEmpty() && com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(
                    com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot.possessedOf(
                            maid, com.touhoulittlemad.fightlikeplayer.carrier.SlotKind.MAINHAND,
                            cur, 0)).matches(want)) {
                return;                                  // 手上就是它 ⇒ 什么都不用做
            }
            if (net.minecraftforge.fml.ModList.get().isLoaded("goety")
                    && com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyChannel
                            .isChanneling(maid)) {
                servoNote(e, "她正在引导法术 ⇒ 这一拍不换手（换走法杖会中断通道）");
                return;
            }
            if (com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel
                    .isRunning(maid)) {
                servoNote(e, "她正在放刀技 ⇒ 这一拍不换手（换走刀会让连段作废）");
                return;
            }
            String hit = resolveItemId(maid, want);
            if (hit == null) {
                // ★★ 第十七轮续（委托方实测：清空背包后上一场的 only_item(剑) 一直挂着）：
                //   一条"拿不到东西"的物品指令在 ABSENT 状态下**不过滤候选集**
                //   ⇒「只用 X」静默变成空操作，还让伺服每 tick 白试一次。
                //   ⇒ 连续 5 秒都拿不到 ⇒ 自动解除并说明（可读出口，不许静默）。
                long now = maid.level().getGameTime();
                if (e.itemAbsentSince == 0) {
                    e.itemAbsentSince = now;
                } else if (com.touhoulittlemad.fightlikeplayer.decision.DirectiveFilter
                        .staleItemDirective(e.itemAbsentSince, now, ITEM_ABSENT_GRACE_TICKS)) {
                    String which = bus.has("only_item") ? "only_item" : "ban_item";
                    String r = cancel(maid, which, now, "auto");
                    e.servoNote = null;
                    e.itemAbsentSince = 0;
                    FightLikePlayer.LOGGER.info(
                            "[FLP][directive] 「{}」她身上一直没有（{} 秒）⇒ 自动解除 {}：{}"
                                    + "　★ 否则它会一直尝试换一把不存在的物品，并让候选集过滤失效",
                            want, ITEM_ABSENT_GRACE_TICKS / 20, which, r);
                    return;
                }
                servoNote(e, "「" + want + "」她身上没有 ⇒ 换不了手"
                        + "（" + ITEM_ABSENT_GRACE_TICKS / 20 + " 秒后会自动解除这条指令）");
                return;
            }
            e.itemAbsentSince = 0;
            boolean ok = com.touhoulittlemad.fightlikeplayer.compat.maid.HandEquip
                    .equipFromInventory(maid, net.minecraft.world.InteractionHand.MAIN_HAND,
                            s -> !s.isEmpty() && hit.equals(
                                    com.touhoulittlemad.fightlikeplayer.compat.maid
                                            .MaidSnapshot.itemId(s)));
            if (ok) {
                e.servoNote = null;
                FightLikePlayer.LOGGER.info("[FLP][directive] 指令伺服：已把 {} 换到主手（指令要的是「{}」）",
                        hit, want);
            } else {
                servoNote(e, hit + " 换不上来（可能在盔甲/饰品槽，或换了但又被换走）");
            }
        } catch (RuntimeException | LinkageError t) {
            FightLikePlayer.LOGGER.debug("[FLP][directive] 指令伺服出错：{}", t.toString());
        }
    }

    /** 伺服的原因说明只在**内容变化**时记（否则每 tick 一条会刷爆）。 */
    private static void servoNote(Entry e, String note) {
        if (!note.equals(e.servoNote)) {
            e.servoNote = note;
            FightLikePlayer.LOGGER.info("[FLP][directive] 指令伺服：{}", note);
        }
    }

    /**
     * ★ 在她身上（任何位置）找第一件匹配这个查询串的物品，返回它的**注册名**。
     *
     * <p>（{@code switch_item} 用：先把"她说的是哪一件"定下来，
     * 再用注册名去找，避免把"标签/类型名"直接当 id 用。）
     */
    public static String resolveItemId(EntityMaid maid, String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String best = null;
        int bestRank = -1;
        for (var it : com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot
                .possessed(maid)) {
            // ★ 第十七轮：取**最强**命中（原来取"第一件命中的"）—— 与 PlayerLikeCombat 同口径
            int rank = com.touhoulittlemad.fightlikeplayer.carrier.ItemQuery
                    .strength(com.touhoulittlemad.fightlikeplayer.carrier.ItemRef.of(it), query)
                    .ordinal();
            if (rank > bestRank) {
                bestRank = rank;
                best = it.itemId();
            }
        }
        return bestRank <= 0 ? null : best;
    }

    /** 脱战 / 卸载时清干净（★ 否则"上一场的指令"会跟着她 —— 与弹簧同样的纪律）。 */
    public static void forget(UUID maidId) {
        ENTRIES.remove(maidId);
    }

    /** 一行摘要（命令/诊断）。 */
    public static String describe(EntityMaid maid, long now) {
        Entry e = ENTRIES.get(maid.getUUID());
        if (e == null) {
            return "（这个女仆还没进过决策循环）";
        }
        StringBuilder sb = new StringBuilder(e.bus.summary(now));
        if (e.anchor != null) {
            sb.append("　守点锚点 ").append(e.anchor);
        }
        if (now < e.guardUntil) {
            sb.append("　强制举盾中（剩 ").append((e.guardUntil - now) / 20).append(" s）");
        }
        if (!e.bus.lastRejected().isEmpty()) {
            sb.append("　⚠ 曾丢弃未知指令：").append(String.join(",", e.bus.lastRejected()));
        }
        // ★★ 第十七轮续：一条**拿不到东西**的物品指令当前是"不过滤"的（护栏），
        //   必须让玩家一眼看到 —— 否则观感就是"有些攻击绕过了指令"。
        String onlyItem = e.bus.textParam("only_item", "item");
        String banItem = e.bus.textParam("ban_item", "item");
        String itemQuery = onlyItem != null ? onlyItem : banItem;
        if (itemQuery != null && !itemQuery.isBlank()) {
            boolean present = false;
            try {
                present = resolveItemId(maid, itemQuery) != null;
            } catch (RuntimeException | LinkageError ignore) {
                present = true;                       // 查不出来就别吓人
            }
            if (!present) {
                sb.append("　⚠ 「").append(itemQuery)
                        .append("」不在她身上 ⇒ 这条指令【当前不生效】（")
                        .append(onlyItem != null ? "only_item" : "ban_item")
                        .append(" 会在 ").append(ITEM_ABSENT_GRACE_TICKS / 20)
                        .append(" 秒后自动解除）");
            }
        }
        if (e.lastInstantResult != null) {
            sb.append("　★ 最近瞬间指令：").append(e.lastInstantResult);
        }
        if (e.servoNote != null) {
            sb.append("　⚠ 换手：").append(e.servoNote);
        }
        if (e.lastCancel != null) {
            sb.append("　★ 最近取消：").append(e.lastCancel);
        }
        return sb.toString();
    }
}
