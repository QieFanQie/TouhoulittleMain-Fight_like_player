"""核对：没有向量的条目是否恰好都是 role != choice 的。并抽样看几个推导结果。"""
import io
import json
import glob
import os

missing = []
roles = {}
samples = []

for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    for o in d['operations']:
        role = o.get('role', 'choice')
        roles[role] = roles.get(role, 0) + 1
        has = ('vector' in o) or ('vectorOverrides' in o)
        if not has:
            missing.append((o['id'], role))

print('role 分布：%s' % roles)
print()
print('没有向量的条目 %d 条：' % len(missing))
for oid, role in missing:
    print('  %-34s role=%s' % (oid, role))

bad = [m for m in missing if m[1] == 'choice']
print()
if bad:
    print('!! 有 choice 动作缺向量（不该发生）：%s' % bad)
else:
    print('OK  所有缺向量的条目都是 maintenance/stance —— 符合预期')

print()
print('抽样（前 14 条 heuristic 向量）：')
n = 0
for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    for o in d['operations']:
        if o.get('vectorProvenance') == 'heuristic' and n < 14:
            print('  %-34s %s' % (o['id'], json.dumps(o['vector'], ensure_ascii=False)))
            n += 1
