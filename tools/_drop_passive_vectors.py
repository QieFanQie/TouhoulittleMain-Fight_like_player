"""移除两条 passive 动作的评分。

`maid_native:sweep` 与 `maid_native:extinguisher_extra` 在 M4 已被标为 `role=passive`
（引擎自动附带，不是决策层可选的独立动作）⇒ **它们不该有评分**。

★ 它们的战术价值已【并入】`maid_native:melee_swing`
（因为 `EntityMaid#doHurtTarget` 一次调用就同时做近战 + 横扫 + 附伤）。

保留评分反而有害：会给"复核者"造成"它们也能被选中"的错觉。
"""
import io
import json
import os

PATH = os.path.join('catalog', 'vectors.json')
DROP = ['maid_native:sweep', 'maid_native:extinguisher_extra']

d = json.load(io.open(PATH, encoding='utf-8'))
removed = []
for aid in DROP:
    if aid in d['vectors']:
        removed.append((aid, d['vectors'].pop(aid)))
        # 记进说明，避免以后又被"补回来"
        d.setdefault('removedBecausePassive', {})[aid] = (
            'role=passive：由 EntityMaid#doHurtTarget 自动附带，不是可选动作 ⇒ 不需要评分。'
            '其价值已并入 maid_native:melee_swing。')

with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('已移除 %d 条 passive 动作的评分：' % len(removed))
for aid, v in removed:
    print('  - %s  %s' % (aid, json.dumps(v.get('vector'), ensure_ascii=False)))
print('评分表剩余条目：%d' % len(d['vectors']))
