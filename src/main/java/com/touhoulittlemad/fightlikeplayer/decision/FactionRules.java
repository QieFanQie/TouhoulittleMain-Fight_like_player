package com.touhoulittlemad.fightlikeplayer.decision;

/**
 * ★★ <b>「她该不该把它当敌人」的唯一判据</b>（第二十二轮，委托方实测）。
 *
 * <h2>委托方原话</h2>
 * > 「存在女仆的铁魔法召唤物（召唤出的僵尸、召唤出的骷髅、召唤出的恼鬼）会索敌女仆的问题。
 * > 虽然不会对女仆造成伤害，但是仇恨会在女仆身上，同样女仆也会攻击其召唤物。
 * > ……对于女仆而言：它们会先攻击女仆攻击的对象，然后**转回来尝试攻击女仆**，
 * > 女仆也会同样尝试攻击它们（也造成不了伤害）。」
 *
 * <h2>★ 为什么"玩家没事、女仆有事"（取证结论）</h2>
 * 铁魔法的召唤物用一条<b>保护主人</b>的目标行为（{@code GenericProtectOwnerTargetGoal}）
 * 扫附近 {@code Mob}：谁的当前目标是主人、或者**是主人名下的召唤物** ⇒ 就打谁。
 * <ul>
 *   <li>主人是<b>玩家</b>时：玩家不是 {@code Mob} ⇒ 这条扫描**永远不会把玩家当侵略者**；</li>
 *   <li>主人是<b>女仆</b>时：她**是** {@code Mob} ⇒ 只要她（哪怕只有一瞬）把自己的召唤物设成目标，
 *       别的召唤物就会把她当成"攻击主人的召唤物的侵略者" ⇒ <b>整个召唤队伍转过来打她</b>
 *       （而她于是反击 ⇒ 自激循环）。</li>
 * </ul>
 * ⇒ <b>类玩家口径：她自己的召唤物/仆从/宠物永远不算敌人</b>（玩家侧本来就没有这条路）。
 * 这与"类玩家 = 效果同构"完全一致：玩家不会把自家召唤物当目标，女仆也不该。
 *
 * <h2>为什么判据在纯逻辑层</h2>
 * 输入是四个**由 compat 层查到的布尔事实**（纯逻辑层不碰任何 MC 类型 ⇒ 可离线断言）。
 * ★ 这正是本项目那条反复确立的纪律：<b>判据不含 MC 类型，就必须放在纯逻辑层</b>，
 * 否则只能靠"进游戏看"来验证。
 */
public final class FactionRules {

    private FactionRules() {
    }

    /**
     * 她**不该**把它当敌人的理由。
     *
     * @param self         是不是她自己
     * @param vanillaAllied 原版阵营判定（{@code isAlliedTo}：队伍、以及"她自己"）
     * @param ownedByMaid  归她所有（{@code OwnableEntity#getOwner() == 她}：宠物/部分召唤物）
     * @param summonedByMaid 是她召唤/雇佣的（铁魔法 {@code IMagicSummon#getSummoner()}、
     *                     Goety {@code IOwned#getTrueOwner()} 等各家自己的归属记录）
     * @return {@code null} = 可以当敌人；否则是一句**可读**的理由（日志/诊断出口直接用）
     */
    public static String friendlyReason(boolean self, boolean vanillaAllied,
                                        boolean ownedByMaid, boolean summonedByMaid) {
        if (self) {
            return "那是她自己";
        }
        if (vanillaAllied) {
            return "她与它同阵营（原版 isAlliedTo）";
        }
        if (ownedByMaid) {
            return "它归她所有（宠物/坐骑/召唤物）";
        }
        if (summonedByMaid) {
            return "它是她自己召唤出来的（仆从/召唤物）";
        }
        return null;
    }

    /**
     * ★ 便利版：只关心"是不是友方"。
     */
    public static boolean isFriendly(boolean self, boolean vanillaAllied,
                                     boolean ownedByMaid, boolean summonedByMaid) {
        return friendlyReason(self, vanillaAllied, ownedByMaid, summonedByMaid) != null;
    }
}
