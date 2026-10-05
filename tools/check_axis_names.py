"""校验清单里的轴名与 Java 侧 NeedAxis 枚举【完全一致】。

为什么必须查：轴名是 Java 与 JSON 之间的**隐式契约**。
若清单里写了 `SELF_SURVIVAL`（旧名）而枚举里已改成 `MITIGATION_SURVIVAL`，
Gson 加载时会**静默忽略**那个键 ⇒ 该轴分数变成 0 ⇒ **行为悄悄错掉，且没有任何报错**。
这正是"[一次静默失败比十次崩溃更糟]"那一类问题。

轴名从 NeedAxis.java 里解析，所以这是真正的"对账"而不是硬编码副本。
"""
import io
import json
import glob
import os
import re
import sys

AXIS_SRC = os.path.join('src', 'main', 'java', 'com', 'touhoulittlemad',
                        'fightlikeplayer', 'decision', 'NeedAxis.java')

src = io.open(AXIS_SRC, encoding='utf-8').read()
body = src[src.index('public enum NeedAxis'):]
# 枚举常量形如：  NAME(0.04, 0.02, 1.0, 1.0, false),
axes = re.findall(r'^\s{4}([A-Z][A-Z_]+)\(', body, re.M)
if not axes:
    print('!! 无法从 NeedAxis.java 解析出轴名')
    sys.exit(2)
print('NeedAxis 枚举里的轴（%d 个）：%s' % (len(axes), axes))

valid = set(axes)
used = {}
problems = []

# ★ 评分的权威来源已改为 catalog/vectors.json（与动作数据分开存放）
VECTORS = os.path.join('catalog', 'vectors.json')
vd = json.load(io.open(VECTORS, encoding='utf-8'))

for aid, entry in vd['vectors'].items():
    vecs = []
    if entry.get('vector'):
        vecs.append(('vector', entry['vector']))
    for k, v in (entry.get('overrides') or {}).items():
        vecs.append(('overrides[%s]' % k, v))
    for where, vec in vecs:
        for axis in vec:
            used.setdefault(axis, 0)
            used[axis] += 1
            if axis not in valid:
                problems.append('%s / %s / %s : 未知轴名 %r' % (VECTORS, aid, where, axis))

print()
print('清单里用到的轴（%d 个）：' % len(used))
for a, n in sorted(used.items(), key=lambda kv: -kv[1]):
    mark = 'OK ' if a in valid else 'BAD'
    print('  %s %-22s 用了 %d 次' % (mark, a, n))

unused = [a for a in axes if a not in used]
if unused:
    print()
    print('⚠ 枚举里有但清单从未使用的轴：%s' % unused)

print()
if problems:
    print('!! 发现 %d 处轴名不匹配（这些轴会被静默忽略！）：' % len(problems))
    for p in problems[:20]:
        print('   %s' % p)
    sys.exit(1)
print('OK  所有轴名与 NeedAxis 枚举一致，无静默失效风险')
