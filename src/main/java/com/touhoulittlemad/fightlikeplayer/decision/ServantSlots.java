package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * ★★ <b>召唤位</b> —— "她还能不能再召唤"的<b>唯一判据</b>（纯逻辑，可离线断言）。
 *
 * <h2>委托方的问题（2026-10-05）</h2>
 * > 「关于铁魔法的召唤类法术（我测试的召唤恼鬼），向量是怎么样的？
 * > 我观察到使用一次后，有召唤物的情况下，就很难再使用了。」
 *
 * <h2>取证（三件事，第一版修法被自测打回）</h2>
 * <ol>
 *   <li>召唤类法术的向量：`irons:cast_spell` 的 SUMMON override = `{SINGLE_DAMAGE: -0.2, REINFORCE: 0.8}`；
 *       Goety 的 `cast_focus` 同理（SUMMON 0.8）；</li>
 *   <li>★ 而 <b>{@code REINFORCE} 这条轴从来没有来源</b>：{@code ContextBias} 不产出它、
 *       {@code FitnessCalculator} 给它常量 1.0 ⇒ 那 0.8 是<b>死重</b>；
 *       我试过抬高它，**自测当场拦下**（在 p 上加新轴会压缩所有动作的相对间距 ⇒
 *       更多动作落进 tieWindow ⇒ 并列由 recency 打破 ⇒ 她在近似动作之间轮换，
 *       `PipelineSelfTest §7` 的"恒定局势收敛"红了）⇒ 已撤回，理由写在 {@code ContextBias} 里；</li>
 *   <li>★ 文档（docs/09 §3.5）早就写着「召唤位满 ⇒ 直接过滤」，但**代码里从来没有实现**；
 *       而"用一次就很难再用"的主因是**铁魔法自己的冷却**（`javap SummonVexSpell` =
 *       {@code setCooldownSeconds(150.0)} ⇒ 150 秒）—— 那是与玩家一致的设计。</li>
 * </ol>
 *
 * <h2>★★ 委托方要的模型：**增量 + 消耗**（2026-10-05）</h2>
 * > 「召唤算法没必要那么精致，维度上有增量和消耗就好了。」
 * <ul>
 *   <li><b>增量</b> = 「再召一个还有多少价值」⇒ 直接进 {@code p[REINFORCE]}：
 *       {@code 权重 × (CAP − 已有仆从) / CAP}（没在打架 ⇒ 0），见 {@code ContextBias}；</li>
 *   <li><b>消耗</b> = 法术自己的代价：{@code cooldownTicks} + 冷却账本
 *       （铁魔法恼鬼 = 150 秒）+ 法力 —— 这一侧本来就有，不需要新机制；</li>
 *   <li>本类只提供那**一个上限判据**（满了 ⇒ 增量 0 ⇒ 不需要召唤），
 *       并被用两次：① 算增量（{@code ContextBias}）② 池子过滤（挑法术那一步）。</li>
 * </ul>
 *
 * <h2>★ 为什么"召唤位"只能判在**挑法术那一步**</h2>
 * 清单里"召唤"不是独立动作：只有 `goety:cast_focus` / `irons:cast_spell` / `irons:recast`
 * 三条动作在<b>参数 override</b> 里带 REINFORCE 0.8，而它们的<b>默认向量 REINFORCE = 0</b>
 * ⇒ 若做成"动作级过滤"，召唤位一满就把<b>整个施法动作</b>丢掉（连火球都不能放）✗
 * ⇒ 正确位置是 {@code GoetyFocusOps#available} 与 {@code IronsSpells#options}
 * 这两个<b>挑法术的池子</b>里，按<b>类别</b>过滤。
 *
 * <p>★ 接线由 {@code tools/check_instant_wiring.py} 用正则盯住（"注释里写过不算"）。
 */
public final class ServantSlots {

    private ServantSlots() {
    }

    /**
     * 召唤位上限：仆从数 ≥ 它 ⇒ 不再把召唤类法术放进池子。
     * <p>取值 4：与"一眼能看出她在带一队人"的观感一致，也避免她无脑刷召唤。
     */
    public static final int CAP = 4;

    /** 现在还能不能召唤（★ 唯一的判据；{@code servantCount} 含 Goety 仆从与铁魔法召唤物）。 */
    public static boolean summonAllowed(int servantCount) {
        return servantCount < CAP;
    }

    /** 一行可读原因（日志 / 诊断用）。 */
    public static String describe(int servantCount) {
        return summonAllowed(servantCount)
                ? "召唤位 " + servantCount + "/" + CAP + "（还能召唤）"
                : "召唤位已满 " + servantCount + "/" + CAP + "（召唤类法术不进池子）";
    }
}
