"""把子代理的打分并入 catalog/vectors.json。

规则：
① 只接受 `role == choice` 的动作（子代理已按此过滤，但这里再核对一次）
② 保留子代理的 `reason` 与 `confidence`（**供人工复核**——这是这次打分最有价值的部分）
③ provenance 标为 `agent`（不是 authored，也不是 heuristic）
④ 旧分同条保留为 `previousHeuristic`，**便于对比"改了什么"**
⑤ 若某条在评分表里但已不是 choice（如刚改成 mechanism），**删掉**
"""
import io
import json
import os
import glob

SCORED = os.path.join('_scratch', 'vectors_scored.json')
VECTORS = os.path.join('catalog', 'vectors.json')

scored = json.load(io.open(SCORED, encoding='utf-8'))['actions']

# ── 当前 choice 集合 ──
choice = set()
for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    for o in d['operations']:
        if o.get('role', 'choice') == 'choice':
            choice.add(o['id'])

vd = json.load(io.open(VECTORS, encoding='utf-8'))
out = {}
merged = 0
skipped = []
dropped = []

for aid, entry in scored.items():
    if aid not in choice:
        skipped.append(aid)
        continue
    new = {
        'vector': entry.get('vector'),
        'provenance': 'agent',
        'confidence': entry.get('confidence', 'medium'),
        'reason': entry.get('reason', ''),
    }
    if entry.get('overrides'):
        new['overrides'] = entry['overrides']
    # 保底：若子代理没给族默认但旧值有，保留旧值（否则该族只能用 overrides）
    if new['vector'] is None and aid in vd['vectors'] and vd['vectors'][aid].get('vector'):
        new['vector'] = vd['vectors'][aid]['vector']
        new['note'] = '族默认沿用上一版（子代理判定该族只能靠 overrides 评分，但保留一个兜底）'
    # 旧值留档，便于对比
    if aid in vd['vectors']:
        old = vd['vectors'][aid]
        new['previousHeuristic'] = {'vector': old.get('vector'),
                                    'provenance': old.get('provenance')}
    out[aid] = new
    merged += 1

# 评分表里已不该有评分的（如改成了 mechanism / passive）
for aid in list(vd['vectors'].keys()):
    if aid not in choice:
        dropped.append(aid)
vd['removedBecausePassive'] = vd.get('removedBecausePassive', {})

vd['vectors'] = dict(sorted(out.items()))
vd['generatedFrom'] = ('★ 本表是"动作值多少"的权威。本轮由子代理按 docs/09 §3.1 的 8 轴语义'
                       '**逐条重打**（provenance=agent），并附 reason / confidence 供人工复核。')
vd['notes'] = {
    'zh_cn': ('★ 权威来源=本文件。provenance：authored=人工 / agent=子代理打分（**待人工复核**）/ '
              'heuristic=规则推导（已知有错）/ measured=实测反推。'
              '★ confidence：high/medium/low —— 复核时**先看 low 的**。'
              '★ `previousHeuristic` 保留上一版的值，便于对比"这轮改了什么"。'
              '★ 轴语义见 docs/09 §3.1；MOBILITY 是唯一有向轴（正=靠近，负=远离）。'),
}

with io.open(VECTORS, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(vd, f, ensure_ascii=False, indent=2)
    f.write('\n')

from collections import Counter
conf = Counter(v.get('confidence', '?') for v in out.values())
print('已并入 %d 条子代理打分' % merged)
print('  confidence：%s' % dict(conf))
if skipped:
    print('  跳过（已不是 choice）：%s' % skipped)
if dropped:
    print('  从评分表移除（已不是 choice）：%s' % dropped)
print('  评分表现有条目：%d' % len(out))
