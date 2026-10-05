package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * ★★ <b>"击败强敌 ⇒ 主动说一句话"的判据</b>（第十七轮续；委托方 2026-10-05 第 2 条）。
 *
 * <p>委托方原话：「击败 boss 级单位后自行触发一次女仆 llm。」
 *
 * <h2>★ 为什么做成纯逻辑类</h2>
 * "什么叫 boss 级"这件事**本来就没法用 MC 的类型系统判定**（原版只有末影龙/凋灵是 boss，
 * 而整合包里的 boss 是任意实体）：唯一稳的判据是**最大生命值阈值**（+ 两个原版 boss 的显式白名单）。
 * 把它放在纯逻辑层，于是"阈值到底怎么判"可以被自测钉住（本项目两次因把判据写在 compat 层
 * 而失去离线测试）。
 *
 * <h2>★ 触发一次 ≠ 每一下都触发</h2>
 * 连杀两个 boss、或者一个大 boss 被多次击杀（刷怪）时不该刷屏 ⇒ 同一条女仆有**冷却窗口**
 * （{@link #DEBOUNCE_TICKS}）；真正的"能不能说"还取决于她配没配 LLM
 * （`MaidAIChatManager#getLLMSite` 为空 ⇒ 什么也不做）与主人是否在线 —— 那两件事在 compat 层把守。
 */
public final class BossChatPolicy {

    private BossChatPolicy() {
    }

    /** 两条"说话"之间的最小间隔（tick）：20 s。连杀不刷屏，但也不至于憋着不说。 */
    public static final int DEBOUNCE_TICKS = 20 * 20;

    /**
     * 算不算"boss 级"。
     *
     * @param maxHealth       被击败实体的最大生命值
     * @param vanillaBoss     是不是原版 boss（末影龙 / 凋灵）—— 它们血量不一定到阈值，但一定要算
     * @param healthThreshold 自定义阈值（配置可调；整合包里的 boss 血量差异很大）
     */
    public static boolean isBossLevel(double maxHealth, boolean vanillaBoss, int healthThreshold) {
        return vanillaBoss || maxHealth >= Math.max(1, healthThreshold);
    }

    /** 冷却窗口内不该重复说话吗？ */
    public static boolean onCooldown(long lastChatAt, long now) {
        return lastChatAt > 0 && now - lastChatAt < DEBOUNCE_TICKS;
    }

    /**
     * 注入给她的那"一句"（会作为**用户那一轮**进对话，她的回复会由 TLM 说给主人听）。
     *
     * <p>★ 措辞刻意写成"战斗记录 + 要求她说一句"：这样既不冒充主人说话，
     * 又满足 TLM 自己那套提示词（用她自己的口吻、不要念系统词）。
     */
    public static String promptFor(String entityName, double maxHealth) {
        return "（战斗记录）你刚刚亲手击败了「" + entityName + "」（" + (long) maxHealth
                + " 点生命）。用你自己的口吻，简短地说一句。";
    }
}
