package com.touhoulittlemad.fightlikeplayer.carrier;

import com.touhoulittlemad.fightlikeplayer.decision.NeedAxis;
import com.touhoulittlemad.fightlikeplayer.decision.NeedVector;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条动作的<b>运行时规格</b> —— 从 {@code catalog/data/*.json} 的一条 operation 翻译而来。
 *
 * <p>只保留载体解析与决策需要的字段。<b>不含 Minecraft 类型</b>。
 *
 * @param id               动作 id
 * @param kind             {@code atomic} / {@code family}
 * @param familyOf         实例指回的族
 * @param carrier          载体 id
 * @param sourceMod        提供方 modid（剪枝键）
 * @param reach            可达性 RA / RB / RC
 * @param displayName      中文名（日志用）
 * @param paramNames       族声明的参数名（★ 用于知道"A 档参数"该向物品问哪些键）
 * @param defaultVector    族默认向量 / 实例向量
 * @param vectorOverrides  ★ 按参数取值覆写的向量（A 档参数化的核心）
 * @param preconditions    前置条件（原始结构，见 {@link PredicateCheck}）
 * @param stateReq         状态要求
 * @param exclusiveGroups  互斥占用组
 * @param commitmentTicks  承诺时长（0 = 瞬发）
 * @param interruptible    是否可被打断
 *
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §4.4-§4.5</a>
 */
public record ActionSpec(
        String id,
        String kind,
        String familyOf,
        String carrier,
        String sourceMod,
        String reach,
        String displayName,
        List<String> paramNames,
        NeedVector defaultVector,
        Map<String, NeedVector> vectorOverrides,
        List<Map<String, Object>> preconditions,
        List<String> stateReq,
        Set<String> exclusiveGroups,
        int commitmentTicks,
        boolean interruptible,
        /** ★ 按参数取值覆写的承诺时长 —— 见 docs/09 §2.4「durationOverrides」 */
        Map<String, Integer> commitOverrides,
        /**
         * ★ <b>最小重复间隔（tick）</b> —— "这招做完之后，隔多久才允许再做一次"。
         *
         * <p><b>为什么承诺时长不够</b>：承诺只回答"这一次做完没有"。
         * 对于<b>瞬时动作</b>（{@code commitmentTicks == 0}：施放瞬发法术、开一枪、处死一个仆从）
         * 承诺立刻结束 ⇒ 决策循环每 {@code decisionInterval} 就会重新选中它
         * ⇒ <b>4 次/秒的刷屏</b>。玩家做不到这一点：法术有冷却、枪有射速。
         *
         * <p>⇒ 本字段是"节奏/冷却"的数据出口。{@code 0} = 未声明，
         * 由 {@link #effectiveCooldownTicks()} 按兜底规则处理。
         */
        int cooldownTicks,
        /**
         * ★ 动作在决策层的角色 —— 见 docs/09 §2.4。
         * {@code choice} = 战术选择（默认）；{@code maintenance} = 执行器自动完成的维护步骤；
         * {@code stance} = 姿态/配置（作为契合度而非动作）。
         */
        String role,
        /**
         * ★★ 2026-10-03：清单里【早就写好但一直没人读】的**连段前驱**（`comboDeps`）。
         *
         * <p>形如 `slashblade:combo_b_finish` 的 `comboDeps` = `[{action: "slashblade:combo_b"}]`
         * ⇒ 本动作的声明前驱是 `combo_b`。
         * 用途：决策层的"奖励连段 / 惩罚复读"（见 {@code ActionSelector}）。
         */
        List<String> comboPreds
) {

    public ActionSpec {
        paramNames = paramNames == null ? List.of() : List.copyOf(paramNames);
        comboPreds = comboPreds == null ? List.of() : List.copyOf(comboPreds);
        vectorOverrides = vectorOverrides == null ? Map.of() : Map.copyOf(vectorOverrides);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        stateReq = stateReq == null ? List.of() : List.copyOf(stateReq);
        exclusiveGroups = exclusiveGroups == null ? Set.of() : Set.copyOf(exclusiveGroups);
        commitOverrides = commitOverrides == null ? Map.of() : Map.copyOf(commitOverrides);
        cooldownTicks = Math.max(0, cooldownTicks);
        role = role == null || role.isBlank() ? "choice" : role;
    }

    /**
     * ★ 未声明冷却时的兜底节奏：<b>1 秒</b>。
     *
     * <p>取值理由：这是"人手做一次操作"的粗粒度下限 —— 玩家点一次左键、放一个瞬发法术，
     * 通常不会快于每秒一次。⇒ 用它兜底可以消灭"4 次/秒刷屏"，同时不干预
     * 已经用 {@code commitmentTicks} 表达过节奏的动作（连击 3~9 tick 不受影响）。
     *
     * <p>⚠️ 这是<b>兜底</b>，不是"正确值"：每个动作的真实冷却应逐步用数据填上
     * （见 docs/10 的 W5x 待办），届时 {@link #cooldownTicks} 会覆盖它。
     */
    public static final int DEFAULT_RHYTHM_TICKS = 20;

    /**
     * ★ 实际生效的最小重复间隔：声明的冷却优先，否则 {@code max(承诺时长, 兜底节奏)}。
     */
    public int effectiveCooldownTicks() {
        return cooldownTicks > 0 ? cooldownTicks : Math.max(commitmentTicks, DEFAULT_RHYTHM_TICKS);
    }

    /**
     * ★ 是否是决策层会主动选的战术动作。
     *
     * <p><b>为什么需要这个区分</b>：换弹、拉栓、ADS、切射击模式<b>不是"选择"</b> ——
     * 玩家不会"选"换弹，他是弹匣空了不得不换；ADS 是提高精度的姿态，不是一招。
     * 把这类东西放进候选集，会让决策层在评分空间里去权衡一个**没有战术价值**的条目。
     *
     * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §2.4</a>
     */
    public boolean isChoice() {
        return "choice".equals(role);
    }

    /**
     * ★ 解析承诺时长（{@code durationOverrides}）。
     *
     * <p>为什么承诺时长也参数化：<b>同一个动作在不同参数下"承诺形状"确实不同</b>。
     * 最典型的是枪的 {@code fireMode}：SEMI = 一发（瞬发）、BURST = 一轮连发（承诺）、
     * AUTO = 持续射击（引导）。若一律按瞬发处理，女仆 AUTO 的火力只有玩家的 40%。
     *
     * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §2.4</a>
     */
    public int pickCommitment(java.util.function.Function<String, String> itemParamLookup) {
        for (String paramName : paramNames) {
            String value = itemParamLookup.apply(paramName);
            if (value == null) {
                continue;
            }
            Integer over = commitOverrides.get(value);
            if (over != null) {
                return over;
            }
        }
        return commitmentTicks;
    }

    public boolean isUnreachable() {
        return "RC".equals(reach);
    }

    /**
     * ★ 解析有效向量：按物品参数挑 {@code vectorOverrides}，找不到则用 {@code defaultVector}。
     *
     * <p>这是 A 档参数化的核心步骤 —— <b>清单只声明"参数是 SA，共 9 种"，
     * 具体是哪一种由手上那把刀在运行时告诉我们</b>，因此改 NBT 也不会让清单失效。
     *
     * @param itemParamLookup 参数名 → 物品上的取值（来自 {@link PossessedItem#params()}）
     * @return 解析到的向量，以及命中的 override 键（用于调试；未命中为 null）
     */
    public VectorPick pickVector(java.util.function.Function<String, String> itemParamLookup) {
        for (String paramName : paramNames) {
            String value = itemParamLookup.apply(paramName);
            if (value == null) {
                continue;
            }
            NeedVector v = vectorOverrides.get(value);
            if (v != null) {
                return new VectorPick(v, paramName + "=" + value);
            }
        }
        return new VectorPick(defaultVector, null);
    }

    /** 向量解析结果。 */
    public record VectorPick(NeedVector vector, String matchedKey) {
        public boolean usedOverride() {
            return matchedKey != null;
        }
    }

    /** 便利：构件一个只有默认向量的原子动作（自测用）。 */
    public static ActionSpec atomic(String id, String carrier, String sourceMod, String reach, NeedVector vector) {
        return new ActionSpec(id, "atomic", null, carrier, sourceMod, reach, id,
                List.of(), vector, Map.of(), List.of(), List.of(), Set.of(), 0, true, Map.of(), 0, "choice",
                List.of());
    }

    /**
     * 便利：构件一个参数化族（自测用）。
     */
    public static ActionSpec family(String id, String carrier, String sourceMod, String reach,
                                    List<String> paramNames, NeedVector defaultVector,
                                    Map<String, NeedVector> overrides) {
        return new ActionSpec(id, "family", null, carrier, sourceMod, reach, id,
                paramNames, defaultVector, overrides, List.of(), List.of(), Set.of(), 0, true, Map.of(), 0, "choice",
                List.of());
    }

    /**
     * ★ 是否<b>已被打过分</b>（有默认向量）。
     *
     * <p>⚠️ <b>注意"零向量"与"缺失"是两件事</b>：
     * <ul>
     *   <li>{@code vector: {}} （空对象）⇒ {@code defaultVector = 零向量（非 null）}
     *       ⇒ <b>"已评分，且价值为 0"</b> —— 如"处死自己的仆从"这类管理动作，
     *       它**确实没有战术价值**，但**这是作者的判断，不是"没打分"**；</li>
     *   <li>根本没有 {@code vector} 键 ⇒ {@code defaultVector = null}
     *       ⇒ <b>"尚未打分"</b> ⇒ 会被 {@link DropReason#NO_VECTOR} 丢弃。</li>
     * </ul>
     * ⇒ 因此判据只看 {@code != null}，<b>不能</b>看"是否为零向量"。
     *
     * <p>★ 这个区分曾经是错的（写成"非零才算有向量"），导致**明确判为 0 分的动作
     * 被当成"没打分"而丢弃** —— 由子代理打分（给管理动作全 0）时暴露。
     */
    public boolean hasVector() {
        return defaultVector != null;
    }

    /**
     * ★ 是否<b>可被评分</b>：有默认向量，<b>或</b>有按参数覆写的向量。
     *
     * <p>区分这两者是有意义的：{@code goety:cast_focus}（施放任意聚晶）<b>没有</b>默认向量
     * —— 因为"放一个聚晶"的价值完全取决于放的是哪个。但它<b>有</b> 8 组类别覆写，
     * 只要运行期能从物品解析出 {@code focusCategory}，它就能被正确评分。
     *
     * <p>⇒ {@link #hasVector()} = "无条件可评分"；{@link #isScoreable()} = "有办法评分"。
     */
    public boolean isScoreable() {
        return hasVector() || !vectorOverrides.isEmpty();
    }

    /**
     * ★ <b>换上一套向量</b>（来自独立的评分表 {@code vectors.json}）。
     *
     * <p>为什么要搬到评分表：{@code v_x} 是"这一招值多少"，是<b>设计判断</b>；
     * 而动作数据描述的是"这一招是什么"。两者的<b>变更频率与复核者都不同</b>
     * ⇒ 分开存放后可以单独复核、单独重打分、单独用实测校准。
     *
     * @param def        族默认向量 / 实例向量；{@code null} = 无默认
     * @param overrides  按参数取值覆写
     * @param provenance 来源标记（{@code authored}/{@code agent}/{@code heuristic}/{@code measured}）
     */
    public ActionSpec withVectors(NeedVector def, Map<String, NeedVector> overrides, String provenance) {
        return new ActionSpec(id, kind, familyOf, carrier, sourceMod, reach, displayName,
                paramNames, def, overrides == null ? Map.of() : overrides,
                preconditions, stateReq, exclusiveGroups,
                commitmentTicks, interruptible, commitOverrides, cooldownTicks, role, comboPreds);
    }

    /** 供日志：向量的七轴紧凑写法。 */
    public String vectorSummary(com.touhoulittlemad.fightlikeplayer.decision.SpringConfig cfg) {
        NeedVector v = defaultVector;
        if (v == null) {
            return "(无向量)";
        }
        return NeedAxis.SINGLE_DAMAGE.zhName() + "... " + v.toCompactString(cfg);
    }
}
