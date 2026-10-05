package com.touhoulittlemad.fightlikeplayer.compat.exec;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>朝目标瞄准</b> —— 让"需要朝向"的动作（施法类）真的打得到人。
 *
 * <h2>★★ 为什么必须有它（委托方 2026-10-01 的第 2 条要求）</h2>
 * 委托方的观察是「iron 魔法施放加一下自动瞄准仇恨对象」，并问「现在女仆有仇恨机制吗？」
 *
 * <blockquote>
 * <b>有。</b>女仆的目标来自 TLM 的攻击任务（brain 的 {@code ATTACK_TARGET} 记忆），
 * 我们的行为本身就<b>只在有目标时运行</b>（{@code create()} 里 {@code ctx.present(ATTACK_TARGET)}），
 * {@code PlayerLikeCombat#targetKey} 也在读 {@code maid.getTarget()}。
 * </blockquote>
 *
 * ⇒ <b>所以"没有仇恨"不是问题；问题是"有仇恨但没瞄准"</b>：
 * <ul>
 *   <li>弓 / 弩 / 三叉戟 / 枪走的是 {@code performRangedAttack(target, …)} 或
 *       {@code GunCommonUtil.performGunAttack(maid, target, …)} —— <b>目标作为参数传进去了，
 *       由它们自己算弹道</b> ⇒ 不需要瞄准；</li>
 *   <li>★ 但 <b>法术没有目标参数</b>（{@code spell.onCast(level, level, caster, CastSource.MOB, data)}）
 *       ⇒ 法术只能沿<b>施法者当前朝向</b>发射
 *       ⇒ 女仆的朝向若没对着敌人，<b>法术就飞向空气</b>（看起来"法术没生效"）。</li>
 * </ul>
 *
 * <p>⇒ 本类在施法前把女仆<b>转向她的仇恨目标</b>。这与玩家侧完全一致：
 * 玩家放法术也要先把准星对上。
 *
 * <h2>★ 为什么"服务端改朝向"是有效且正确的</h2>
 * {@code setYRot/setXRot} 在服务端设置后<b>会随实体同步包发到客户端</b>
 * （原版 Mob 的"看向目标"就是这么做的），因此观感上女仆会转头，弹道也按新朝向计算。
 * 另外再调一次 {@code getLookControl().setLookAt(...)}，让头部动画与 {@code yHeadRot} 跟上
 * —— 否则会出现"身体转了、头还在看别处"的割裂感。
 *
 * <p>⚠️ <b>不在没有目标时乱转</b>：无目标 ⇒ 什么都不做（返回 false）。
 *
 * @see ActionExecutors#goetyCast
 * @see ActionExecutors#ironsCast
 */
public final class AimHelper {

    private AimHelper() {
    }

    /**
     * 把女仆转向她的仇恨目标（{@code maid.getTarget()}）。
     *
     * @return 是否真的转了（{@code false} = 没有目标，什么都没做）
     */
    public static boolean faceTarget(EntityMaid maid) {
        LivingEntity target = maid.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        return face(maid, target);
    }

    /** 转向指定实体。 */
    public static boolean face(EntityMaid maid, LivingEntity target) {
        if (target == null) {
            return false;
        }
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        // ★★ 第十一轮：瞄点从"眼睛"改成"落脚处稍高一点"（委托方要求）。
        //    为什么：法术命中判定是沿视线打到的那一点，而"眼睛"对一个两格高的怪来说
        //    是它的**上半身**（离地约 1.6 格）⇒ 稍远一点就打飞/打到它身后；
        //    瞄**脚下稍高**（约 0.4 格）时，视线穿过的是它**躯干最粗的那一段**，
        //    擦着就中 ⇒ 命中率明显更高，也符合委托方的原话
        //    「对着怪物落脚部分的稍高处瞄准（比如对于两格生物，即下面那格的位置再稍稍偏低一点）」。
        double dy = aimY(target) - maid.getEyeY();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Mth.atan2(dy, Math.max(horiz, 1.0e-4)) * (180.0 / Math.PI)));

        maid.setYRot(yaw);
        maid.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
        // ★ 头也要跟：否则"身体转了、头没转"，弹道与观感都会奇怪
        maid.setYHeadRot(yaw);
        maid.yBodyRot = yaw;
        try {
            maid.getLookControl().setLookAt(target, 30.0f, 30.0f);
        } catch (RuntimeException ignore) {
            // 某些实体状态（如正在被骑乘/死亡）下 lookControl 可能不可用 ⇒ 不影响施法
        }
        return true;
    }

    /**
     * ★★ <b>瞄点高度</b>：从目标的<b>脚底</b>往上取一点点。
     *
     * <p>取 {@code 目标高度 × 0.25}，并夹在 {@code [0.3, 0.6]} 格：
     * <ul>
     *   <li>两格生物（僵尸/多数人形）⇒ <b>0.5 格</b>（原来 0.4）—— 委托方第十六轮要求
     *       「瞄准位置稍微往上调一点点」；</li>
     *   <li>一格生物（史莱姆/狼）⇒ 0.3 格（几乎贴地，但不会打进地里）；</li>
     *   <li>高个生物（铁傀儡 2.7 格）⇒ 0.6 格（仍在下段，不会跑到胸口）。</li>
     * </ul>
     * ★ 用"比例 + 上下限"而不是固定值：固定 0.4 格对一格生物会偏高、对高个生物会偏低。
     * ★ 上调的理由（不只是"感觉"）：0.4 格对两格生物是"腰以下"，
     *   而近战/横扫的判定盒与"打在身上"的观感都更偏中段；抬高一点点能少一点"打在地上"的错觉，
     *   同时对远程弹道的仰角影响可忽略（两格生物 0.5 格 ≪ 眼高 1.6 格）。
     *
     * <p>★ 为什么不用 {@code getEyeY()}：那是**眼睛**（两格生物约离地 1.6 格），
     * 远距离时仰角误差被放大 ⇒ 打飞。见 {@link #face} 的说明。
     */
    public static double aimY(LivingEntity target) {
        double height = Math.max(0.0, target.getBbHeight());
        // ★★ 第十六轮（委托方原话）：「两格高的人，就瞄准他**靠下面的那格的中心**」
        //   ⇒ 判据 = min(0.5, 高度/2)：
        //     两格 ⇒ 0.5（下面那格的中心，正是他要的）；一格 ⇒ 0.5（它就是那一格）；
        //     半格 ⇒ 0.25；三格 ⇒ 0.5（仍是最下面那一格的中心）。
        double offset = Math.min(0.5, height * 0.5);
        return target.getY() + offset;
    }
}
