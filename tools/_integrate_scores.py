"""把子代理的打分并入目录，并修掉两处由本轮审计暴露的**真实错误**。

## 错误 ①（★ 严重）：我把自定义的分类【冒充】成了调律师的

`catalog/data/goety.json` 的 `focusCategory` 写着：
    "ref": "goety-tuner 的 focus/FocusClassifier + FocusCategory（按聚晶类别分类）"
    8 个取值：ATTACK_SINGLE/ATTACK_AREA/SUMMON/UTILITY/PROTECTION/HEALING/CONTROL/SERVANT_MGMT

**而实测（读源码）：调律师的 `FocusCategory` 只有 4 个值**：
    ATTACK("attack") / DEFENSE("defense") / SUMMON("summon") / OTHER("other")
    —— D:\\tiaolvshi\\goety-tuner\\src\\main\\java\\com\\tiaolvshi\\goetytuner\\focus\\FocusCategory.java:16-20

⇒ 我**发明了 8 个取值，却把一个真实来源标注为它的出处**。这是比"取值写错"更严重的问题：
它会让后来者以为"查源码就能拿到这 8 类"。**已修正**：8 类保留为本项目自定义分类，但
**出处如实标注**，并给出 4 类回退映射。

## 错误 ②：把"机制/许可门/UI"当成了"动作"

`ironspells.json` 的 12 条里，有 9 条其实是**机制**（选法术的 UI、同类法术书互斥的许可门、
法力/冷却机制、学习门、事件、玩家专属 UI…），却都被标成 `role=choice`，
并被启发式打分统一填成 `SINGLE_DAMAGE 0.3` ⇒ **它们会进入候选集**（虽然分数低）。
⇒ 新增 `role="mechanism"` 并改标这 9 条。它们仍保留在清单里**作为知识记录**，但**永不进候选**。
"""
import io
import json
import os

# ── ① goety:cast_focus 的分类出处修正 ──
GOETY_PATH = os.path.join('catalog', 'data', 'goety.json')
g = json.load(io.open(GOETY_PATH, encoding='utf-8'))

OUR_8 = ["ATTACK_SINGLE", "ATTACK_AREA", "SUMMON", "UTILITY",
         "PROTECTION", "HEALING", "CONTROL", "SERVANT_MGMT"]
FALLBACK_4 = {
    "ATTACK": ["ATTACK_SINGLE", "ATTACK_AREA"],
    "DEFENSE": ["PROTECTION", "HEALING"],
    "SUMMON": ["SUMMON", "SERVANT_MGMT"],
    "OTHER": ["UTILITY", "CONTROL"],
}

for op in g['operations']:
    if op['id'] != 'goety:cast_focus':
        continue
    op['params']['focusCategory'] = {
        "ref": "★ 本项目【自定义】的 8 类细分。⚠️ 调律师的 FocusCategory 只有 4 类"
               "（ATTACK/DEFENSE/SUMMON/OTHER，见 goety-tuner focus/FocusCategory.java:16-20），"
               "它可作为粗粒度回退。",
        "values": OUR_8,
        "count": 8,
        "fallback": FALLBACK_4,
        "note": {
            "zh_cn": "★★ 出处如实说明：这 8 类【不是】调律师的枚举（它只有 4 类），"
                     "而是本项目为了区分『单体攻击 vs 范围攻击』等战术差异而自定义的细分。"
                     "⇒ 要在运行期拿到这 8 类，需要【我们自己的分类器】（登记 W32）；"
                     "若只拿得到调律师的 4 类，按 fallback 映射回退到 4 类的覆写。"
                     "★ 之前的 ref 把它标注成了调律师的枚举 —— 那是错的，已修正。"
                     "（本条由子代理打分时发现并核对源码后确认）"
        },
    }
    op['note'] = {'zh_cn':
        "★★ 分类出处修正：focusCategory 的 8 类是【本项目自定义】，调律师只有 4 类"
        "（ATTACK/DEFENSE/SUMMON/OTHER）。详见 params.focusCategory.note 与 docs/10 的 W32。"}

with io.open(GOETY_PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(g, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('① 已修正 goety:cast_focus 的分类出处标注（8 类=自定义，4 类=调律师，附回退映射）')

# ── ② 把 9 条"机制"从 choice 改为 mechanism ──
MECHANISMS = {
    'irons:spell_selection': '选法术的 UI/轮盘机制（R-C），不是动作',
    'irons:spellbook_exclusivity': '同类法术书互斥的【许可门】（万法皆通的设计），不是动作',
    'irons:support_target_selection': '支援法术的【瞄准机制】，不是动作',
    'irons:pvp': '不能打玩家的【许可门】，不是动作',
    'irons:mana': '法力【资源机制】，不是动作',
    'irons:cooldown': '法术【冷却机制】，不是动作',
    'irons:eldritch_learning': '邪术学派的【学习门】，不是动作',
    'irons:spell_events': '法术【事件钩子】，不是动作',
    'irons:player_only_ui': '玩家专属【UI】（R-C），不是动作',
}
IRONS = os.path.join('catalog', 'data', 'ironspells.json')
ir = json.load(io.open(IRONS, encoding='utf-8'))
changed = []
for op in ir['operations']:
    why = MECHANISMS.get(op['id'])
    if why:
        op['role'] = 'mechanism'
        note = op.setdefault('note', {})
        base = note.get('zh_cn', '') if isinstance(note, dict) else ''
        note['zh_cn'] = base + ('　★ role=mechanism：%s ⇒ 不进候选集（保留在清单里作为知识记录）。'
                                '本条由子代理打分时发现（此前被启发式误填 SINGLE_DAMAGE 0.3）。' % why)
        changed.append(op['id'])
with io.open(IRONS, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(ir, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('② 已把 %d 条机制改为 role=mechanism：%s' % (len(changed), changed))

# ── ③ schema 增加 mechanism ──
SCHEMA = os.path.join('catalog', 'schema', 'operation.schema.json')
s = json.load(io.open(SCHEMA, encoding='utf-8'))
role = s['$defs']['operation']['properties']['role']
if 'mechanism' not in role['enum']:
    role['enum'].append('mechanism')
    role['description'] = role['description'] + (
        '；mechanism=机制/许可门/UI/资源规则，【本体不是动作】（保留在清单里作知识记录，永不进候选）')
with io.open(SCHEMA, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(s, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('③ schema 已增加 role=mechanism')
