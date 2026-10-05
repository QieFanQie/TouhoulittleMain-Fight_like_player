# -*- coding: utf-8 -*-
"""审计 2：前置条件的「游戏侧可达性」。

## 为什么需要它（与 audit_durations.py 同一次事故的另一半）

`CarrierResolver#checkPreconditions` 对 `custom` 与 `spellConditionsMet` 采用
**fail-closed**（清单 schema 自己的告诫：「缺省意味着随时可用，属危险默认」）
⇒ **没被显式喂进来的一律判不满足并丢弃**。

而游戏侧（`MaidSnapshot.facts`）此前喂的是**空 Map** ⇒ **13 / 54 个 choice 动作
在游戏里永远拿不到**，包括：

  · `maid_native:melee_swing`（通用近战）—— 拿剑的女仆因此一个候选都没有
    ⇒ `isArmed=false` ⇒ 连目标都不找（S0 实测的「不注入就不动」）
  · `tacz:shoot`（枪械开火）、`goety:cast_focus`、`irons:cast_spell` ……

★ 这就是本项目 WIP 表里 W10 说的「当前最危险的一处宽松」的**镜像**：
那一处担心"太宽松"，实际发生的却是**太严格 ⇒ 能力被静默关掉**。

## 检查项
  1. ★ **硬检查**：凡是声明了 `custom` / `spellConditionsMet` 的动作，
     **必须显式写 `fact` 字段**（事实键），否则它的键会退化成 `<动作id>#<下标>`
     —— 往前面插一条前置条件就会把键错位，且**没人能说出这个键该喂什么**；
  2. **硬检查**：`fact` 键必须登记在 `GAME_FACTS`（= 游戏侧真的会喂的键）里，
     否则它就是"声明了但永远不满足"的静默死条件；
  3. **软报告**：依赖 `hasEnchantment` / `hasServant` / `resourceAtLeast` 的动作
     —— 这些能力/资源目前也没有被喂全（登记 W23/W5），列出以便排序。

## 怎么修（三种，按优先级）
  a) **数据修**：若条件其实冗余（如"已加载某模组"可由载体判据蕴含）或已过时
     （如旧模型的"任务必须是 attack"），直接删掉它 —— 这比喂一个恒真的键更诚实；
  b) **喂事实**：确实是运行时事实的（杖里有没有聚晶、法术书里有没有法术），
     在 `PlayerLikeCombat#customFacts` 里喂，并把键加进本脚本的 GAME_FACTS；
  c) **登记缺口**：执行器还没实现的动作（如 slashblade 系）暂留 fail-closed 是合理的，
     但必须出现在 `PLANNED_GAPS` 里 —— 让"拿不到"是**显式**的。
"""
import json
import glob
import io
import os
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ★ 游戏侧真的会喂的事实键（与 PlayerLikeCombat#customFacts 保持一致）
GAME_FACTS = {
    "goety:can_cast": "PlayerLikeCombat#customFacts ← GoetyExecutors.canCastWithAnyHeldWand(女仆)（持有位置任一法杖里有聚晶）",
    "irons:can_cast": "PlayerLikeCombat#customFacts ← IronsExecutors.hasAnySpell(女仆)（持有带法术的书/卷轴）",
    "goety:has_recall": "PlayerLikeCombat#customFacts ← GoetyServantOps.hasUsableRecallFocus(女仆)"
                        "（手上的回溯聚晶里真的存过坐标；2026-10-01 新增，"
                        "见 catalog/data/goety.json 里 recall_to_saved_coords 那条 custom）",
    # ★★ 2026-10-03 新增（委托方报「我让女仆只用幻影剑，但完全没效果」的修法之一）：
    #   拔刀剑的两条前置从"只在执行期判"提到**解析期**（做不到就不进候选）。
    "slashblade:can_summon_sword": "PlayerLikeCombat#customFacts ← "
                                   "SlashBladeExecutors.canSummonSword(女仆)"
                                   "（妖刀 BEWITCHED + 力量附魔 > 0 + 耀魂 ≥ 消耗）",
    "slashblade:has_slash_art": "PlayerLikeCombat#customFacts ← "
                                "SlashBladeExecutors.hasSlashArtInHand(女仆)"
                                "（主手这把刀真的配了 SA：SlashArtsKey 非 NONE）",
}

# ★ 已登记为"故意不可达"的动作（执行器未实现 / 依赖后续里程碑）。新增必须写明理由。
PLANNED_GAPS = {
    # ★ 2026-10-01：goety 仆从管理 5 条已实现并可达（执行器 + 仆从数投喂 + 载体判据 + 去掉 SNEAKING）
    #   ⇒ 它们从本清单移出。剩下的 goety 缺口是 transfer_servant_ownership（低优先级，未实现执行器）。
    "goety:transfer_servant_ownership": "执行器未实现（低优先级：对「类玩家战斗」不直接相关）",
    # ★ 2026-10-01：tacz:melee 已实现（直调 IGunOperator#melee，TaCZ api 包公开方法）⇒ 移出本清单
    "tacz:reload": "执行器 planned（TaCZ 其它操作面）",
    "tacz:zoom": "执行器 planned；且 isAim() 属姿态（role=stance 时应移出候选）",
    "pillagersgun:charge_and_fire": "★ Pillager's Gun（modid=pillagers_gun）未勘测；⚠️ 第十七轮更正：它不是卓越前线（superbwarfare，见 carriers.json 的 superbwarfare:gun）",
    "irons:eldritch_learning": "role=mechanism（学习门，不是动作）",
    "slashblade:guard": "★ 【委托方决定不实现】（2026-09-30）：它要求往 IInputState 写 SNEAK，"
                        "而女仆没有客户端输入（MoveInputHandler 是 @OnlyIn(CLIENT) + ClientTickEvent）"
                        "⇒ 要做得我们自己造输入模拟，投入产出比低 ⇒ 登记为不做（docs/04 Q19）",
    "slashblade:guard_just": "同上（同一个事件处理器）",
    "slashblade:super_slash_art": "★ blocked：等 S1（且同样挂在 InputCommandEvent/输入状态上）",
    "maid_native:slashblade_generic_slash": "★ blocked：TLM 的助手被门控在 TaskAttack.UID（W34）",
}

NEEDS_MORE = {
    "hasServant": "仆从数（W23：需 Goety IOwned）",
    "resourceAtLeast": "资源量（W23：耀魂值/法力/弹药）",
}

# ★ 清单里 hasEnchantment 用到的附魔 id —— 必须能被 MaidSnapshot 投喂的 enchant:<id> 命中
NEEDED_ENCHANTS = set()


def load(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    problems = []
    soft = []

    choice = 0
    hard_blocked = set()
    # ★ 第十七轮：role != choice 的动作【永不进候选】（CarrierResolver 以 NOT_A_CHOICE 丢弃）
    non_choice = []
    for path in sorted(glob.glob(os.path.join(BASE, "catalog", "data", "*.json"))):
        for op in load(path).get("operations", []):
            role = op.get("role", "choice")
            if role != "choice":
                # ★ 这是【有意的】而不是缺口：显式列出来，免得"清单里有、游戏里永远拿不到"被漏看
                non_choice.append((op["id"], role))
                continue
            choice += 1
            for i, pc in enumerate(op.get("preconditions", [])):
                t = pc.get("type")

                if t in ("custom", "spellConditionsMet"):
                    fact = pc.get("fact")
                    if not fact:
                        if op["id"] in PLANNED_GAPS:
                            hard_blocked.add(op["id"])
                            continue
                        problems.append(
                            "%s 的第 %d 条 %s 前置缺少 fact 字段 ⇒ 键会退化成 \"%s#%d\"（不可维护）"
                            % (op["id"], i, t, op["id"], i))
                    elif fact not in GAME_FACTS:
                        if op["id"] in PLANNED_GAPS:
                            hard_blocked.add(op["id"])
                            continue
                        problems.append(
                            "%s 声明了 fact=%s，但游戏侧【不会喂】这个键 ⇒ 该动作永远拿不到"
                            % (op["id"], fact))
                    else:
                        GAME_FACTS.setdefault(fact, "")   # 已登记

                elif t == "hasEnchantment":
                    # ★★ 2026-09-30 新增：**必须显式写 enchantment 字段**。
                    #   否则 CarrierResolver 会把前置键退化成「enchant:<整句 note>」，
                    #   而 MaidSnapshot 投喂的是 enchant:minecraft:thorns ⇒ 永不匹配
                    #   ⇒ 「拔刀剑·防御」即使刀上有荆棘也拿不到（与 custom 缺 fact 同型的静默失效）。
                    if not pc.get("enchantment"):
                        if op["id"] in PLANNED_GAPS:
                            hard_blocked.add(op["id"])
                            continue
                        problems.append(
                            "%s 的第 %d 条 hasEnchantment 缺 enchantment 字段 ⇒ 键会退化成 note 原文（不可匹配）"
                            % (op["id"], i))
                    else:
                        NEEDED_ENCHANTS.add(pc["enchantment"])

                elif t in NEEDS_MORE:
                    if op["id"] not in PLANNED_GAPS:
                        soft.append("%s 依赖 %s（%s）" % (op["id"], t, NEEDS_MORE[t]))

    print("=== 前置条件可达性审计 ===")
    print("  choice 动作            : %d" % choice)
    print("  ★ 游戏侧会喂的事实键     : %s" % ", ".join(sorted(GAME_FACTS)))
    print("  ★ 游戏侧会喂的附魔能力   : %s"
          % (", ".join("enchant:" + e for e in sorted(NEEDED_ENCHANTS)) or "（清单里没有 hasEnchantment）"))
    print("  ★ 已登记为故意不可达的   : %d 条" % len(PLANNED_GAPS))
    print()

    if non_choice:
        print("--- ★ 永不进候选的动作（role != choice，有意的）---")
        for aid, role in sorted(non_choice):
            print("  %-40s role=%s" % (aid, role))
        print()

    if PLANNED_GAPS:
        # ★ 列出【全部】已登记的缺口（而不是只列本次命中的）——
        #   否则某条动作修好前置之后，它的"仍然没有执行器"就会从这里消失（静默乐观）。
        print("--- 已登记为故意不可达（fail-closed 是有意的，等执行器/里程碑）---")
        for aid in sorted(PLANNED_GAPS):
            mark = "★本次命中" if aid in hard_blocked else "  未命中"
            print("  [%s] %-38s %s" % (mark, aid, PLANNED_GAPS[aid]))
        print()

    if soft:
        print("--- ⚠ 依赖尚未投喂的能力/资源（登记 W23/W5，暂不失败）---")
        for s in soft:
            print("  " + s)
        print()

    if problems:
        print("!! 有 %d 处会【静默关掉能力】的前置条件：" % len(problems))
        for p in problems:
            print("   " + p)
        print()
        print("   ⇒ 三种修法见本脚本头部注释：数据修（删冗余条件）> 喂事实（加进 GAME_FACTS）> 登记缺口。")
        return 1

    print("OK  所有自定义前置条件要么被投喂、要么已显式登记为缺口")
    return 0


if __name__ == "__main__":
    sys.exit(main())
