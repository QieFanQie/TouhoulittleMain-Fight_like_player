"""修正一处【文档与代码不一致】+ 补一处 duration 缺口。

## ① 文档≠代码（必须纠正）

我在 docs/09 §2.4 写了「AUTO 已改为 COMMIT，见 guns.json 的 durationOverrides」，
但实际数据仍是 `AUTO -> CHANNEL(40)`。**这是假的。** 按委托方的明确规格
（「步枪、冲锋枪等：以一梭子打完为单位」）改成 COMMIT。

★ magazine 大小与射速是**每把枪**的数据 ⇒ `ticks` 只能是【名义估计】，
真正的结束由**执行器回报**（`DecisionCycle.notifyCompleted()`）决定 ——
这与既定的门控设计一致（tick 数只做防卡死兜底）。

## ② 换弹语义（委托方规格）

- 步枪/冲锋枪：**自动** —— 动作结束即自动换好；**若启动时发现不满则先换弹**
- 手枪：**用完了再换**（不预先补满）
两者都写在 note 里，作为执行器的实现要求。

## ③ 补缺口：`irons:cast_spell` 缺 durationOverrides

它的 note 写着"按 castType 变：LONG 有蓄力，CONTINUOUS 是引导"，
但**没有 durationOverrides** ⇒ 全部被当成 INSTANT ⇒
决策层会在 LONG/CONTINUOUS 法术施放途中就重选 ⇒ 引导类法术永远放不完。
与 fireMode 完全同构的问题，同一套修法。
"""
import io
import json
import os

# ── ① + ② 枪 ──
gun_path = os.path.join('catalog', 'data', 'guns.json')
d = json.load(io.open(gun_path, encoding='utf-8'))
changed = []
for op in d['operations']:
    if op['id'] != 'tacz:shoot':
        continue
    op['durationOverrides'] = {
        "SEMI": {
            "kind": "INSTANT",
            "note": {"zh_cn": "一次 shoot() = 一发。★ 换弹语义（委托方规格）：**用完了再换**，不预先补满。"}
        },
        "BURST": {
            "kind": "COMMIT", "ticks": 12,
            "note": {"zh_cn": "★ 一次 shoot() 内部就完成整轮三连发（CycleTaskHelper.addCycleTask(period, cycles=burstData.getCount())）⇒ 单位是「一轮连发」，不是「一发」。ticks 取 10+rand(5) 的名义值。"}
        },
        "AUTO": {
            "kind": "COMMIT", "ticks": 60,
            "note": {"zh_cn": "★★ 单位 = **一梭子打完**（委托方规格：步枪/冲锋枪）。不是 CHANNEL —— 女仆不必「按住」，她承诺打完这个弹匣。★ 换弹**自动**：动作结束即自动换好；若启动时发现弹匣不满则先换弹。⚠️ ticks=60 只是【名义估计】（弹匣大小与射速是每把枪的数据）⇒ **真正的结束由执行器回报**（DecisionCycle.notifyCompleted()），tick 数仅作防卡死兜底。"}
        }
    }
    changed.append('tacz:shoot durationOverrides 已修正（AUTO: CHANNEL -> COMMIT）')
with io.open(gun_path, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

# ── ③ 法术 ──
sp_path = os.path.join('catalog', 'data', 'ironspells.json')
d2 = json.load(io.open(sp_path, encoding='utf-8'))
for op in d2['operations']:
    if op['id'] != 'irons:cast_spell':
        continue
    op['duration'] = {"kind": "INSTANT",
                      "note": {"zh_cn": "族默认；实际按 castType 覆写，见 durationOverrides。"}}
    op['durationOverrides'] = {
        "INSTANT": {
            "kind": "INSTANT",
            "note": {"zh_cn": "瞬发：一次 onCast 结算。"}
        },
        "LONG": {
            "kind": "COMMIT", "ticks": 20,
            "note": {"zh_cn": "★ 按住蓄力、松手结算（immediatelySuppressRightClicks() 为真 ⇒ 客户端立刻拦截右键）。⇒ 蓄力段必须视为【承诺】，否则决策层会在蓄力途中重选，法术永远放不出来。ticks 为名义值（真实蓄力时长由 spell 决定）。"}
        },
        "CONTINUOUS": {
            "kind": "CHANNEL", "sustainTicks": 100,
            "note": {"zh_cn": "★★ 持续引导：每 10 tick 一次 onCast，松手即取消（AbstractSpell.java:353-358）。⇒ 建模为 CHANNEL：决策层每个周期可决定是否继续 —— 这正是「按住不放」的语义。"}
        },
        "NONE": {
            "kind": "INSTANT",
            "note": {"zh_cn": "占位（NoneSpell），实际不可施放。"}
        }
    }
    changed.append('irons:cast_spell 补 durationOverrides（INSTANT/LONG/CONTINUOUS/NONE）')
with io.open(sp_path, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d2, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('已修正 %d 处：' % len(changed))
for c in changed:
    print('  - %s' % c)
