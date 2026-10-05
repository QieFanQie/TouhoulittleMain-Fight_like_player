"""修正：`HEAL_SURVIVAL` 被建出来了却没人用（轴名对账脚本发现的）。

原因：轴迁移时按"动作名/显示名含治疗关键词"判断，而**治疗类的动作藏在族的参数里**
（`goety:cast_focus` 的 `HEALING` 类别、`irons:cast_spell` 的 `heal` 法术），
动作名本身看不出是治疗 ⇒ 全部落到了 MITIGATION_SURVIVAL。

★ 由此暴露一个【普适规律】（值得记）：
**凡是"施放任意 X"的族（SA / 枪型 / 聚晶类别 / 法术），其向量都必须由参数驱动**
（`vectorOverrides`），否则族级的单一向量会把"治疗"和"大火球"评成同一件事。
本脚本顺手把已发现的 Goety 治疗类别改正；铁魔法侧登记为待办。
"""
import io
import json
import os

p = os.path.join('catalog', 'data', 'goety.json')
d = json.load(io.open(p, encoding='utf-8'))
changed = []

for op in d['operations']:
    if op['id'] != 'goety:cast_focus':
        continue
    vo = op.get('vectorOverrides') or {}
    # 治疗类：按委托方要求，【回血】的评分放在 HEAL_SURVIVAL
    if 'HEALING' in vo:
        old = vo['HEALING']
        vo['HEALING'] = {"HEAL_SURVIVAL": 0.8, "ALLY_CARE": 0.5}
        changed.append('HEALING: %s -> %s' % (old, vo['HEALING']))
    # 保护类 = 非回血生存（格挡/护盾类），保持 MITIGATION
    if 'PROTECTION' in vo:
        old = vo['PROTECTION']
        vo['PROTECTION'] = {"MITIGATION_SURVIVAL": 0.8, "ALLY_CARE": 0.2}
        changed.append('PROTECTION: %s -> %s' % (old, vo['PROTECTION']))
    op['vectorOverrides'] = vo

    # 说明补一句：类别 → 向量的映射是有意为之
    note = op.setdefault('note', {})
    if isinstance(note, dict):
        note['zh_cn'] = (note.get('zh_cn', '') +
            ' ★ 类别→向量的映射：HEALING 走 HEAL_SURVIVAL（回血生存），PROTECTION 走 MITIGATION_SURVIVAL（非回血生存）'
            ' —— 这是委托方"回血/非回血拆成两点"决策的直接体现。')

if changed:
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write('\n')

print('修正 goety:cast_focus 的类别向量：')
for c in changed:
    print('  - %s' % c)
if not changed:
    print('  （无需修改）')
