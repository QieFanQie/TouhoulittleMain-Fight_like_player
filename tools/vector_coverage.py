"""v_x 覆盖率 + 来源统计（W2 的账本）。

★ 自本版起，**评分的权威来源是 `catalog/vectors.json`**（与动作数据分开存放）。
本脚本因此读 vectors.json，并交叉核对：
  ① 每个 choice 动作在评分表里都有条目（缺了就报出来）；
  ② 评分表里没有悬空 id（拼写错误）；
  ③ ★ `role != choice` 的动作【不该】有评分（它们不是决策层会选的）；
  ④ provenance 分布（含 agent 打分待复核的条数）。
"""
import io
import glob
import json
import os
import sys
from collections import Counter

VECTORS = os.path.join('catalog', 'vectors.json')

# ── 载入动作 ──
actions = {}
for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    # ★ 只处理【动作数据】文件；其余（如 spell_intent.json）跳过 ——
    #   新增一个数据文件不该让本脚本崩掉（这是一次真教训）
    for o in d.get('operations', []):
        actions[o['id']] = (os.path.basename(f), o)

# ── 载入评分表 ──
try:
    vd = json.load(io.open(VECTORS, encoding='utf-8'))
except FileNotFoundError:
    print('!! 找不到 %s —— 评分表应与动作数据分开存放' % VECTORS)
    sys.exit(1)
vectors = vd['vectors']

choice = {k: v for k, v in actions.items() if v[1].get('role', 'choice') == 'choice'}
nonchoice = {k: v for k, v in actions.items() if v[1].get('role', 'choice') != 'choice'}

problems = []
scoreable = []
for aid, entry in vectors.items():
    if aid not in actions:
        problems.append('评分表引用了【不存在】的动作：%s' % aid)
        continue
    if aid in nonchoice:
        problems.append('role=%s 的动作不该有评分：%s'
                        % (actions[aid][1].get('role'), aid))
    if entry.get('vector') or entry.get('overrides'):
        scoreable.append(aid)

missing = sorted(a for a in choice if a not in vectors)

print('=== 覆盖率账本（权威来源：catalog/vectors.json）===')
print('  动作总数                                : %d' % len(actions))
print('  其中 choice                             : %d' % len(choice))
print('  非 choice（maintenance/stance/passive）  : %d' % len(nonchoice))
print('  评分表条目                              : %d' % len(vectors))
print('  ★ 可评分（有 vector 或 overrides）      : %d' % len(scoreable))
print('  ★ choice 但【缺评分】                   : %d' % len(missing))

prov = Counter(e.get('provenance', '(未标)') for e in vectors.values())
print()
print('  provenance 分布：')
for k, n in prov.most_common():
    mark = {'authored': '人工判断',
            'agent': '★ 子代理打分（待人工复核）',
            'heuristic': '⚠ 规则推导（已知有错）',
            'measured': '实测反推'}.get(k, '?')
    print('    %-12s %3d   %s' % (k, n, mark))

over = [a for a, e in vectors.items() if e.get('overrides')]

# ★★ 2026-09-30 补的一处盲点：**只有 overrides、没有默认向量**的族
#   —— 它们在运行期依赖"物品参数"（W23）。而参数提供者尚未实现 ⇒
#   ActionSpec.pickVector 会回落到 defaultVector=null ⇒ CarrierResolver 报 NO_VECTOR 丢弃
#   ⇒ **看起来"有评分"，实际在游戏里【永远拿不到】**。
#   （goety:cast_focus 就是这样被静默关掉的，直到 D4 补了族兜底向量。）
param_dependent = [a for a in over if not vectors[a].get('vector')]

print()
print('  带 overrides 的族（%d 个）：' % len(over))
for a in sorted(over):
    ks = list(vectors[a]['overrides'].keys())
    flag = '  ⚠ 无默认向量 ⇒ 运行期依赖参数' if a in param_dependent else ''
    print('    %-34s %2d 个取值：%s%s'
          % (a, len(ks), ', '.join(ks[:6]) + ('…' if len(ks) > 6 else ''), flag))

if missing:
    print()
    print('--- ⚠ choice 但缺评分的动作（决策层无法评分它们）---')
    for a in missing:
        print('  %-44s (%s)' % (a, actions[a][0]))

if param_dependent:
    print()
    print('--- ⚠ 无默认向量的族（运行期依赖 W23 的物品参数；参数拿不到 ⇒ NO_VECTOR 丢弃）---')
    for a in param_dependent:
        print('  %s' % a)
    print('  ⇒ 修法二选一：① 给族补一个兜底向量（见 D4 的 goety:cast_focus）；② 实现 W23 参数提供者。')

print()
if problems:
    print('!! 发现 %d 个一致性问题：' % len(problems))
    for p in problems:
        print('   %s' % p)
    sys.exit(1)

if missing:
    print('!! 有 %d 个 choice 动作缺评分' % len(missing))
    sys.exit(1)

print('OK  所有 choice 动作都有评分，且评分表无悬空/越界条目')

todo = prov.get('heuristic', 0) + prov.get('agent', 0)
if todo:
    print()
    print('⚠  其中 %d 条是 heuristic 或 agent —— 需人工复核后才能作为调优基础。' % todo)
