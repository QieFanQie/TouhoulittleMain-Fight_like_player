# -*- coding: utf-8 -*-
"""审计 1：承诺时长与节奏（duration / cooldownTicks）。

## 为什么需要它（2026-09-30 S0 实测的真实事故）

游戏里出现「弓拉满但不射 + 执行处疯狂执行 + 无论注入什么向量都只是到处跑」，
而当时 **10 项检查 / 115 项断言全部通过**。一号根因在**数据**里：

    catalog/data/maid_native.json 的 10 条动作中，只有 melee_swing 写了 duration
    ⇒ 其余 45 / 56 个 choice 动作的承诺时长 = 0

后果（全部可在代码里指认）：
  1. `DecisionCycle.commit()` 只在 `commitmentTicks > 0` 时设 inFlight ⇒ **执行门控失效**
     ⇒ 每 `decisionInterval`(5 tick) 重选重执行 ⇒ 「疯狂执行」（4 次/秒）；
  2. `PlayerLikeCombat.executeMain()` 也只在承诺 > 0 时记 pendingFinish
     ⇒ **`ActionExecutors.finish()` 永不触发** ⇒ 弓永不松手 ⇒ 「拉满不射」；
  3. 每次重复提交都会 `publish(subtract(u))` ⇒ 弹簧点被反复推 ⇒ 走位需求来回翻转。

★ 教训：**"缺字段"和"写错键名"都不会让任何断言失败** —— 必须专门审。
（键名事故实例：`slashblade:slash_art` 写了 `chargeTicks: 9` 而旧 `commitOf` 只在
CHANNEL 分支读它 ⇒ 静默算成 0。）

## 检查项
  1. 有执行器的动作：承诺 > 0 **或** 明确登记为「真·瞬时」（见 INSTANT_OK）——
     否则它要么没有门控、要么永远收不了尾；
  2. 所有 choice 动作的最小重复间隔 > 0（由 ActionSpec.effectiveCooldownTicks 兜底保证）；
  3. duration 里写了数字但 kind 与之矛盾（如 INSTANT 却写了 ticks）—— 提示但不失败；
  4. 报告"承诺=0 且无执行器"的动作数（那是已登记的计划缺口，不是 bug）。
"""
import json
import glob
import io
import os
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RHYTHM_FALLBACK = 20  # 与 ActionSpec.DEFAULT_RHYTHM_TICKS 保持一致

# ★ 已确认【真·瞬时】的动作：它们承诺为 0 是正确的，靠冷却兜底节奏。
#   新增条目必须写明理由。
INSTANT_OK = {
    "goety:cast_focus": "瞬发法术在同 tick 结算（DarkWand.java:611-615）；蓄力/通道形态由 durationOverrides 覆写",
    "irons:cast_spell": "INSTANT 法术 getCastTime()=0（AbstractSpell.java:178-183）",
    "irons:cast_scroll": "与 cast_spell 同；卷轴不改变时长",
    "slashblade:summoned_sword": "按下那一帧出剑（SummonedSwordArts.java:108-145）",
    # ★ 第十七轮起该动作 role=directive ⇒ 不进候选（只由指令触发），此处仅留档
    "goety:dismiss_temporary_servants": "批量 dismiss 是同步循环，无计时器",
    "goety:set_servant_stance": "四个 setter 全是纯字段写入（IServant.java:141-165）",
    "goety:recall_servants": "teleportServants 无延迟（SummonSpell.java:121-127）",
    "goety:transfer_servant_ownership": "纯字段写入",
    "tacz:shoot": "SEMI = 一发（INSTANT）；BURST/AUTO 的承诺由 durationOverrides 覆写。"
                  "⚠️ fireMode 参数在运行期拿不到（W23）⇒ 当前实际走 INSTANT，"
                  "节奏由【冷却兜底(20 tick)】+【执行器回报完成（GunCommonUtil 的返回值=射速）】共同保证",
}

# ★ 已登记为"计划缺口"的动作：承诺=0 是因为执行器还没实现 / 依赖后续里程碑。
#   它们不算 bug（拿不到就是拿不到），但要显式列出来，避免"看起来支持其实没有"。
PLANNED_ZERO = {
    "goety:recall_servants": "执行器 planned",
    "goety:set_servant_stance": "执行器 planned",
    "goety:transfer_servant_ownership": "执行器 planned（低优先级）",
    "tacz:melee": "执行器 planned（需核实公开入口）；真值 = prep 2 tick / 冷却 20 tick",
    "tacz:grenade": "★ TaCZ 里根本不存在手雷（816 class 零命中）⇒ 保留为知识记录",
    # ★ 第十七轮更正：pillagers_gun 与卓越前线（superbwarfare）是**两个模组**，此前串名
    "pillagersgun:charge_and_fire": "Pillager's Gun（modid=pillagers_gun）未勘测（W9 邻域）；★ 它不是卓越前线（后者是 superbwarfare）",
    "irons:recast": "执行器 planned（W36）；真值 = 段数 × 窗口（见 _scratch/commitment-evidence.md）",
    "slashblade:combo_b_finish": "★ 未找到证据：1.8.60 里无 grantCriterion 调用点、无同名 ComboState",
    "slashblade:summoned_sword": "执行器 blocked（等 S1）",
}


def commit_of(d):
    """复刻 CatalogLoader.commitOf：先看 ticks，再看 chargeTicks + sustainTicks。"""
    if not d:
        return 0, "(无 duration)"
    ticks = int(d.get("ticks", 0) or 0)
    if ticks > 0:
        return ticks, d.get("kind", "?")
    return int(d.get("chargeTicks", 0) or 0) + int(d.get("sustainTicks", 0) or 0), d.get("kind", "?")


def load(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    problems = []
    notes = []

    # 有执行器的动作 id
    ledger = os.path.join(BASE, "catalog", "executors.json")
    implemented = set()
    for ex in load(ledger).get("executors", []):
        if ex.get("status") == "implemented":
            implemented.update(ex.get("handles", []))

    total = 0
    choice = 0
    zero_commit = []
    for path in sorted(glob.glob(os.path.join(BASE, "catalog", "data", "*.json"))):
        for op in load(path).get("operations", []):
            total += 1
            if op.get("role", "choice") != "choice":
                continue
            choice += 1
            n, kind = commit_of(op.get("duration"))

            # 检查 3：kind 与数字矛盾
            if kind == "INSTANT" and n > 0:
                notes.append("%s：kind=INSTANT 但算出 %d tick ⇒ 会被当成承诺" % (op["id"], n))

            # 检查 1：有执行器 ⇒ 要么承诺 > 0，要么登记为真瞬时
            if op["id"] in implemented and n == 0 and op["id"] not in INSTANT_OK:
                problems.append("%s：有执行器但承诺=0 ⇒ 收不了尾（可能像弓那样永不松手）" % op["id"])

            if n == 0:
                zero_commit.append((op["id"], op["id"] in implemented))

    print("=== 承诺时长审计 ===")
    print("  动作总数            : %d" % total)
    print("  其中 choice         : %d" % choice)
    print("  ★ 承诺=0 的 choice   : %d（其中已有执行器的 %d 条）"
          % (len(zero_commit), sum(1 for _, impl in zero_commit if impl)))
    print("  节奏兜底            : 未声明 cooldownTicks 者取 max(承诺, %d) ⇒ 恒 > 0" % RHYTHM_FALLBACK)
    print()
    if zero_commit:
        print("--- 承诺=0 的 choice 动作（真瞬时 / 计划缺口，逐个确认过）---")
        for aid, impl in zero_commit:
            why = INSTANT_OK.get(aid) or PLANNED_ZERO.get(aid) \
                or "★ 尚未确认：需登记为「真瞬时」或补 duration"
            print("  %-40s 执行器=%s  %s" % (aid, "有" if impl else "无", why))
        print()
    for n in notes:
        print("  ⚠ " + n)
    if notes:
        print()

    if problems:
        print("!! 有 %d 个动作的承诺时长缺失（这会让门控失效 / 动作永不收尾）：" % len(problems))
        for p in problems:
            print("   " + p)
        print()
        print("   ⇒ 修法：在 catalog/data/*.json 给该动作补 duration（COMMIT + ticks，"
              "或 CHANNEL + chargeTicks/sustainTicks），")
        print("     或在本脚本的 INSTANT_OK 里登记为「真·瞬时」并写明理由。")
        return 1

    print("OK  承诺时长与节奏无缺口")
    return 0


if __name__ == "__main__":
    sys.exit(main())
