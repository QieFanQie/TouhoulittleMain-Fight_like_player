"""审计：检查文档宣称 vs 清单实际 的一致性。

重点核对本轮我在 docs/09 §2.4 里写下的那句话：
「已改为 COMMIT，见 catalog/data/guns.json 的 durationOverrides」
—— 若实际没改，就是【文档与代码不一致】，必须立刻纠正（这正是本项目最看重的纪律）。
"""
import io
import json
import os
import glob

print('=== tacz:shoot 的 duration / durationOverrides ===')
d = json.load(io.open(os.path.join('catalog', 'data', 'guns.json'), encoding='utf-8'))
for o in d['operations']:
    if o['id'] == 'tacz:shoot':
        print(' duration         :', json.dumps(o.get('duration'), ensure_ascii=False))
        dov = o.get('durationOverrides') or {}
        for k, v in dov.items():
            print('  %-6s -> %s' % (k, json.dumps({kk: vv for kk, vv in v.items() if kk != 'note'},
                                                  ensure_ascii=False)))
        print('  （note 已省略）')

print()
print('=== 每个动作的 duration kind 分布 ===')
from collections import Counter
kinds = Counter()
for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    dd = json.load(io.open(f, encoding='utf-8'))
    for o in dd['operations']:
        kinds[(o.get('duration') or {}).get('kind', '(缺)')] += 1
for k, v in kinds.most_common():
    print('  %-10s %d' % (k, v))

print()
print('=== 承诺时长（commitmentTicks）非 0 的动作 ===')
n = 0
for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    dd = json.load(io.open(f, encoding='utf-8'))
    for o in dd['operations']:
        du = o.get('duration') or {}
        k = du.get('kind', 'INSTANT')
        t = 0
        if k == 'COMMIT':
            t = du.get('ticks', 0)
        elif k == 'CHANNEL':
            t = du.get('chargeTicks', 0) + du.get('sustainTicks', 0)
        if t > 0:
            n += 1
            if n <= 12:
                print('  %-40s %s=%d' % (o['id'], k, t))
print('  ... 共 %d 条有承诺时长' % n)
