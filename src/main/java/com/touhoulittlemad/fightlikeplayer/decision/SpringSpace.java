package com.touhoulittlemad.fightlikeplayer.decision;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹簧空间 —— <b>被多个执行循环共享</b>的需求点，以及待执行的影响器队列。
 *
 * <h2>为什么要"共享"</h2>
 * 委托方要求：<b>「主手+步伐+其他」是一个循环，副手自己玩自己的循环；两者共用一个弹簧空间，
 * 但动作层（可用物品）是隔离的。</b>
 *
 * <pre>
 *        ┌──────────────── SpringSpace（唯一）────────────────┐
 *        │   point: NeedVector       ← 两循环共同读写          │
 *        │   pending: [影响器…]      ← 按【发布顺序】逐个执行   │
 *        └───────▲───────────────────────────────▲───────────┘
 *                │ publish                       │ publish
 *      ┌─────────┴─────────┐         ┌───────────┴───────────┐
 *      │ 主循环             │         │ 副手循环               │
 *      │ 物品：主手+盔甲+背包│         │ 物品：【仅副手】        │
 *      │   +女仆背包+Curios │         │ （盾 / 烈风之伞 / 灭火器）│
 *      └───────────────────┘         └───────────────────────┘
 * </pre>
 *
 * <h2>发布-执行语义</h2>
 * <ol>
 *   <li>任何来源（动作层 / 态势偏置 / 思维层）调用 {@link #publish} <b>登记</b>一个影响器；</li>
 *   <li>{@link #drain()} 按 <b>发布顺序</b> 逐个执行，作用在同一个 {@code point} 上；</li>
 *   <li>执行完清空队列。</li>
 * </ol>
 * ⇒ 顺序是<b>显式</b>的：先发布的先作用。这消除了"谁先改 p"这个隐式依赖，
 * 也让自测可以断言"给定发布会列 ⇒ 得到确定的结果"。
 *
 * <p>★ 线程模型：只在服务端主线程访问（与 Minecraft tick 一致），故未加锁。
 *
 * @see SpringImpact
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §5.7</a>
 */
public final class SpringSpace {

    private NeedVector point = NeedVector.zeros();
    private final List<SpringImpact> pending = new ArrayList<>();

    /** 已执行的影响器条数（诊断用，能看出"是不是影响器一直在发布"）。 */
    private long appliedCount;

    public SpringSpace() {
    }

    public SpringSpace(NeedVector initial) {
        this.point = initial == null ? NeedVector.zeros() : initial;
    }

    // ───────────────────────── 读写 ─────────────────────────

    /** 当前弹簧点（只读快照）。 */
    public NeedVector point() {
        return point;
    }

    /**
     * 发布一个影响器。<b>不做立即执行</b> —— 留到 {@link #drain()}。
     *
     * @return 是否接受（永远接受；返回 void 更合适，但保留返回值便于将来加策略）
     */
    public void publish(SpringImpact impact) {
        if (impact != null) {
            pending.add(impact);
        }
    }

    /** 待执行的影响器条数。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 已执行的影响器总条数。 */
    public long appliedCount() {
        return appliedCount;
    }

    /**
     * 按<b>发布顺序</b>逐个执行待处理的影响器，然后清空队列。
     *
     * <p>★ <b>规整只在整批结束时做一次</b>（不是每个影响器各做一次）：
     * 影响器本身是<b>纯向量变换</b>（加减、缩放），规整（非负/上限/死区）统一收口在这里。
     * 这样保证"<b>先算完、再规整</b>"，与 [docs/09 §3.6] 的顺序要求一致 ——
     * 若每个影响器都规整一次，中间那次钳制会把负值提前吃掉，代价语义就失效了。
     *
     * @param config 参数表
     * @return 本次实际执行的描述（供日志）
     */
    public List<String> drain(SpringConfig config) {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> log = new ArrayList<>(pending.size());
        // ★ 关键：用局部引用遍历【原始顺序】，执行过程中不再接收新发布的影响器
        //   （避免"执行影响器时又发布影响器"导致的顺序歧义）
        List<SpringImpact> batch = List.copyOf(pending);
        pending.clear();
        NeedVector p = point;
        for (SpringImpact impact : batch) {
            p = impact.apply(p, config);
            appliedCount++;
            log.add(impact.source() + " → " + impact.describe());
        }
        point = p.normalize(config);
        return log;
    }

    /**
     * 由委托方要求的"上一个动作执行完毕才能开启下一个循环" —— 用于在一个循环
     * <b>空转</b>时把累计的外部影响（如副手动作、思维层推挤）消化掉。
     * <p>等价于 {@link #drain}，单独命名是为了让调用点的意图可读。
     */
    public List<String> tickExternalImpacts(SpringConfig config) {
        return drain(config);
    }

    /** 强制重置（脱战 / 换目标）。 */
    public void reset() {
        pending.clear();
        point = NeedVector.zeros();
    }

    /** 直接设定（仅供自测与存档恢复）。 */
    public void setPoint(NeedVector p) {
        this.point = p == null ? NeedVector.zeros() : p;
    }

    @Override
    public String toString() {
        return "SpringSpace" + point.toCompactString(SpringConfig.defaults())
                + (pending.isEmpty() ? "" : " 待执行 " + pending.size() + " 个影响器");
    }
}
