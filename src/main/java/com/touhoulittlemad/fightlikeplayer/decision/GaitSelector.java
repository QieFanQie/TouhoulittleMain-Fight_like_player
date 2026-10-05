package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * <b>步法选择器</b> —— 把弹簧点 {@code p} 翻译成"脚下怎么走"（纯逻辑，零 Minecraft 依赖）。
 *
 * <h2>★★ 核心设计：步法是「伺服」，不是「消费」</h2>
 *
 * <p>与 {@code ActionSelector}（最近邻选解 + 执行后扣减）<b>刻意不同</b>：
 * 本类<b>只读</b>弹簧点，<b>从不发布影响器、从不扣减</b>。理由见 {@link Gait} 的类注释
 * ——位移是伺服误差而不是可消费的需求，扣减会造成永久震荡。
 *
 * <h2>规则表（★ 每条都要能单独断言，见 GaitSelfTest）</h2>
 * <pre>
 * 0. 导航被动作接管（locked）        ⇒ HOLD        （如"撤退&脱战"正在执行）
 * 1. 没有目标：
 *      a. 主人远（> FOLLOW_DISTANCE）⇒ TO_OWNER   （类玩家：打完就回到主人身边）
 *      b. 否则                       ⇒ HOLD
 * 2. p[MOBILITY] &lt; −ε（想远离）      ⇒ AWAY        （目标距离 = max(2×站位, 站位+RETREAT_STEP)）
 * 3. p[MOBILITY] &gt; +ε（想靠近）      ⇒ TOWARD      （已在站位内 ⇒ HOLD）
 * 4. 否则（MOBILITY ≈ 0）—— <b>伺服在"站位"上</b>：
 *      a. 距离 &gt; 站位 + 迟滞          ⇒ TOWARD
 *      b. 距离 &lt; 站位 − 迟滞 且 站位够远 ⇒ AWAY      （弓手风筝）
 *      c. 否则                          ⇒ HOLD
 * </pre>
 *
 * <p>★ 「站位」= 当前选中动作所属<b>载体</b>的 {@code preferredRange}
 * （{@code catalog/carriers.json}）⇒ <b>手里拿什么就站什么距离</b>：
 * 弓 12 格、拔刀剑 3 格、法术 9 格。与"按手持物分发动作"是同一条原理在<b>站位</b>维度的落地。
 *
 * <p>★ 规则 4b 有 {@link #CLOSE_COMBAT_RANGE} 门槛：站位本身就很近的载体（近战 2.5 格）
 * <b>不会</b>因为"太近了"而后退 —— 否则近战女仆会不停地"贴上去又退开"。
 *
 * @see Gait
 * @see <a href="../../../../../../../docs/04-开放问题.md">docs/04 Q18</a>
 */
public final class GaitSelector {

    /** MOBILITY 的死区（与 {@code NeedAxis.MOBILITY} 的 epsilon 一致）。 */
    public static final double MOBILITY_EPSILON = 0.02;

    /** 距离迟滞：避免在站位附近来回抖动（"走一步停一步"）。 */
    public static final double HYSTERESIS = 1.5;

    /** 小于这个站位视为"近战载体" ⇒ 永不为"太近"而后退（规则 4b 的门槛）。 */
    public static final double CLOSE_COMBAT_RANGE = 3.5;

    /** 无目标时，主人超过这个距离就归位。 */
    public static final double FOLLOW_DISTANCE = 6.0;

    /** 后撤时要拉开多少额外距离。 */
    public static final double RETREAT_STEP = 6.0;

    private GaitSelector() {
    }

    /**
     * 选择步法。
     *
     * @param p               弹簧点（<b>只读</b>）
     * @param hasTarget       是否有攻击目标
     * @param distanceToTarget 与目标的距离（格）；无目标时可传任意值
     * @param preferredRange  当前选中动作所属载体的站位偏好（格）
     * @param distanceToOwner 与主人的距离（格）；无主人传 0
     * @param navigationLocked 导航是否被某个动作接管（接管期间步法必须让位）
     * @return 步法（永不为 null）
     */
    public static Gait select(NeedVector p,
                              boolean hasTarget,
                              double distanceToTarget,
                              double preferredRange,
                              double distanceToOwner,
                              boolean navigationLocked) {
        // 0. 动作接管导航 ⇒ 让位
        if (navigationLocked) {
            return Gait.hold();
        }

        double range = preferredRange > 0 ? preferredRange : 2.5;

        // 1. 没有目标
        if (!hasTarget) {
            if (distanceToOwner > FOLLOW_DISTANCE) {
                return new Gait(Gait.Direction.TO_OWNER, 2.0);
            }
            return Gait.hold();
        }

        double mobility = p == null ? 0.0 : p.get(NeedAxis.MOBILITY);
        double mitig = p == null ? 0.0 : p.get(NeedAxis.MITIGATION_SURVIVAL);

        // 2. 想远离
        if (mobility < -MOBILITY_EPSILON) {
            // ★★ 第十六轮（委托方要求）：后撤**不再永远是正后方** ——
            //   交给两轴评分（位移 + 防御）挑：挨打时选"斜后撤"（撤得更稳），
            //   单纯想跑时选正后方。★ 只在这两轴上决策，不动别的轴。
            Gait.Direction dir = GaitScoring.pick(mobility, mitig, true, false);
            return new Gait(dir, Math.max(range * 2.0, range + RETREAT_STEP));
        }

        // 3. 想靠近
        if (mobility > MOBILITY_EPSILON) {
            if (distanceToTarget <= range) {
                return Gait.hold();                 // 已经到位
            }
            return new Gait(Gait.Direction.TOWARD, range);
        }

        // 4. 伺服在站位上（MOBILITY ≈ 0）
        if (distanceToTarget > range + HYSTERESIS) {
            return new Gait(Gait.Direction.TOWARD, range);
        }
        if (range > CLOSE_COMBAT_RANGE && distanceToTarget < range - HYSTERESIS) {
            // ★ 同 2：站得太近要退 — 退法同样由两轴评分决定（防御需求高 ⇒ 斜后撤）
            Gait.Direction dir = GaitScoring.pick(mobility, mitig, true, false);
            if (dir == Gait.Direction.HOLD) {
                dir = Gait.Direction.AWAY;
            }
            return new Gait(dir, range);
        }
        return Gait.hold();
    }

    /** 规则 0 的公开判据：哪些动作会接管导航（步法必须让位）。 */
    public static boolean actionTakesNavigation(String actionId) {
        return "fight_like_player:disengage".equals(actionId);
    }
}
