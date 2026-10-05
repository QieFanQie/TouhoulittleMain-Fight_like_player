package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * <b>步法（gait）</b> —— 女仆"脚下怎么走"的持续意图。
 *
 * <h2>★★ 为什么走位必须是「步法」而不是「原子动作」（2026-09-30 委托方拍板 A 方案）</h2>
 *
 * <p>旧设计把走位做成三个原子动作（{@code charge} 突进 / {@code retreat} 后撤 /
 * {@code disengage} 撤退&脱战），它们进候选集、参与最近邻评分、<b>并在执行后从弹簧里扣减自己的向量</b>。
 * 这在实测中暴露成"<b>不论注入什么向量，女仆都只是到处跑</b>"。根因是<b>语义错误</b>：
 *
 * <blockquote>
 * <b>位移不是"可消费的需求"，而是"伺服误差"。</b>
 * </blockquote>
 *
 * <p>「放一个 AoE ⇒ 群伤需求被满足了」是消费；但「往敌人走 8 格 ⇒ 靠近需求被满足了」是错的 ——
 * 你走到之后，<b>下一步仍然需要继续靠近</b>。于是：
 * <ul>
 *   <li>{@code charge}（{@code MOBILITY +0.8}）执行后弹簧的 MOBILITY 变负；</li>
 *   <li>下一周期 {@code retreat}（{@code MOBILITY −0.8}）反而成了最近邻；</li>
 *   <li>它执行后又把 MOBILITY 推回正 ⇒ <b>两个动作互相"偿还"对方，永久震荡</b>。</li>
 * </ul>
 *
 * <p>⇒ 步法因此有两条<b>硬约束</b>（二者缺一不可）：
 * <ol>
 *   <li><b>不进候选集、不参与评分、不发布"扣减"影响器</b>（见 {@link GaitSelector}）；</li>
 *   <li><b>是唯一的导航写入者</b> —— 否则它会与 vanilla brain 的
 *       {@code SetWalkTargetFromAttackTargetIfTargetOutOfReach}（每 tick 把女仆往目标拉）互相打架，
 *       净效果同样是乱跑。</li>
 * </ol>
 *
 * <p>⚠️ <b>但"可消费的位移"仍然应该是动作</b>：拔刀剑的瞬步/突进、传送类法术是
 * <b>一次性、有明确代价</b>的位移 —— 那些留在动作层，带 {@code MOBILITY} 向量并正常扣减。
 * 区分标准：<b>「这一步走完，我还需要再走一步吗？」</b> 需要 ⇒ 步法；不需要 ⇒ 动作。
 *
 * @param direction      方向意图
 * @param desiredDistance 这一步想把距离变成多少格（{@code HOLD} 时无意义）
 *
 * @see GaitSelector
 * @see <a href="../../../../../../../docs/04-开放问题.md">docs/04 Q18（步法的决策记录）</a>
 */
public record Gait(Direction direction, double desiredDistance) {

    /** 方向意图。 */
    public enum Direction {
        /** 原地（保持现状、停止寻路）。 */
        HOLD("保持"),
        /** 朝目标靠近。 */
        TOWARD("靠近"),
        /** 背离目标（若主人不在附近，用 {@link #TO_OWNER} 更有意义）。 */
        AWAY("远离"),
        /**
         * ★★ <b>面向仇恨目标向左后方撤</b>（第十六轮，委托方要求）。
         *
         * <p>与 {@link #AWAY} 的区别是"斜着撤"：横向也让开一点。
         * ★ 它的价值在于**防御**：身法不需要任何物品、<b>任何时刻都可行</b>，
         * 所以当"防御需求堆积"（取证：`p[MITIG]` 中位 1.96、91% 顶到上限）时，
         * 它是那个**永远可用的防守出口** —— 见 {@code GaitScoring}。
         */
        AWAY_LEFT("左后撤"),
        /** ★★ 面向仇恨目标向右后方撤（同 {@link #AWAY_LEFT}，镜像）。 */
        AWAY_RIGHT("右后撤"),
        /** 走向主人（脱战回归 / 平时跟随）。 */
        TO_OWNER("归位");

        private final String zh;

        Direction(String zh) {
            this.zh = zh;
        }

        public String zh() {
            return zh;
        }

        /**
         * ★ <b>这一步是不是"后退"</b>（含两个斜后撤）。
         *
         * <p>★ `no_retreat`（死战不退）指令必须问这个，而不是 `== AWAY` ——
         * 否则"斜后撤"漏在护栏外面（本项目的老毛病：判据只覆盖了同一个东西的一种写法，
         * 见 docs/13 第 56/62 条）。
         */
        public boolean isRetreat() {
            return this == AWAY || this == AWAY_LEFT || this == AWAY_RIGHT;
        }
    }

    public Gait {
        if (direction == null) {
            throw new IllegalArgumentException("步法方向不能为 null");
        }
    }

    /** 保持原地。 */
    public static Gait hold() {
        return new Gait(Direction.HOLD, 0);
    }

    public boolean isHold() {
        return direction == Direction.HOLD;
    }

    /** 供日志的一行写法。 */
    public String describe() {
        if (isHold()) {
            return "保持";
        }
        return direction.zh() + "→" + String.format(java.util.Locale.ROOT, "%.1f", desiredDistance) + "格";
    }
}
