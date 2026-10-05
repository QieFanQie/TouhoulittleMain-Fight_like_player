"""执行器覆盖审计（M4）—— 三件事：

① 校验 catalog/executors.json 的 JSON 语法与内部一致性；
② ★ 交叉核对：executors.json 里 handles 的动作 id 必须真实存在于清单
   （否则是拼写错误 —— 会造成"以为实现了其实没有"）；
③ ★★ 覆盖率账本：**哪些 choice 动作还没有执行器**。
   这是 M4 最关键的可见性：没有执行器的动作 = 决策层选中了也做不出来。
"""
import io
import glob
import json
import os
import re
import sys

EXEC = os.path.join('catalog', 'executors.json')

# ① 载入（严格）
try:
    ex = json.load(io.open(EXEC, encoding='utf-8'))
except json.JSONDecodeError as e:
    print('!! executors.json 语法错误：第 %d 行第 %d 列：%s' % (e.lineno, e.colno, e.msg))
    print('   提示：中文里的引号请用「」，不要用 ASCII 双引号。')
    sys.exit(1)

# 载入清单
actions = {}
for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    # ★ 只处理【动作数据】文件；其余（如 spell_intent.json）跳过 ——
    #   新增一个数据文件不该让本脚本崩掉（这是一次真教训）
    for o in d.get('operations', []):
        actions[o['id']] = (f, o)

choice = {k: v for k, v in actions.items() if v[1].get('role', 'choice') == 'choice'}
reach_of = {k: v[1].get('reach') for k, v in choice.items()}
reach_ra = {k: v for k, v in choice.items() if v[1].get('reach') == 'RA'}

# ② 交叉核对 + 建覆盖率
handled = {}          # action id -> (kind, status)
problems = []
kinds = []
for e in ex['executors']:
    kinds.append((e['kind'], e['status'], len(e.get('handles', []))))
    for aid in e.get('handles', []):
        if aid not in actions:
            problems.append('executors.json 引用了清单里【不存在】的动作：%s（kind=%s）'
                            % (aid, e['kind']))
            continue
        if aid in handled:
            problems.append('动作 %s 被多个执行器声明：%s 与 %s'
                            % (aid, handled[aid][0], e['kind']))
        handled[aid] = (e['kind'], e['status'])

print('=== 执行器清单（%d 个）===' % len(ex['executors']))
print('%-14s %-12s %s' % ('kind', 'status', 'handles'))
for k, s, n in kinds:
    print('%-14s %-12s %d' % (k, s, n))

impl = {a for a, (k, s) in handled.items() if s == 'implemented'}

# ★★ 2026-10-01：审计盲区修复。
#    解析器只丢 reach=RC ⇒ **RA 与 RB 都会进候选集**；
#    而本脚本原来只审计 RA ⇒ "RB 却没有执行器"的动作完全看不见。
#    实测后果（委托方会话日志）：slashblade:quick_charge / judgement_cut_just /
#    spiral_swords（全是 RB）被选中 118 / 98 / 22 次却做不出来，
#    因为"没做出来就不扣代价"⇒ 弹簧不动 ⇒ 下一周期再选中它 ⇒ 把能做出来的动作全饿死。
reachable = [a for a in choice if reach_of.get(a) in ('RA', 'RB')]
missing_reachable = sorted(a for a in reachable if a not in impl)
missing_ra = sorted(a for a in reach_ra if a not in impl)

print()
print('=== 覆盖率账本（choice 动作）===')
print('  choice 动作总数        : %d' % len(choice))
print('  其中 reach=RA          : %d' % len(reach_ra))
print('  ★ 已有【已实现】执行器 : %d（全部 choice，含 reach=B/C）' % len([a for a in impl if a in choice]))
print('  ★ reach=RA 但无执行器  : %d（⇒ RA 覆盖率 %d/%d）'
      % (len(missing_ra), len(reach_ra) - len(missing_ra), len(reach_ra)))
print('  ★★ 会进候选集的(RA+RB) : %d，其中【无执行器】%d ⇒ 覆盖率 %d/%d'
      % (len(reachable), len(missing_reachable),
         len(reachable) - len(missing_reachable), len(reachable)))

print()
print('--- ★★ RA+RB（真正会进候选集的）却无已实现执行器 ---')
print('    共 %d 条。★ 它们已在解析期被 NO_EXECUTOR 挡下（CatalogSource#EXECUTORS_FILE）——' % len(missing_reachable))
print('    若这一节不为空而解析器又不过滤，女仆就会反复选中做不出来的动作（v2026-10-01 实测）。')
by_prefix2 = {}
for a in missing_reachable:
    by_prefix2.setdefault(a.split(':')[0], []).append(a)
for p in sorted(by_prefix2):
    lst = by_prefix2[p]
    print('  %-18s %2d 条  %s' % (p, len(lst), ', '.join(lst[:4])))

print()
print('--- ★ RA 但尚未实现执行器的动作（决策层选中也做不出来）---')
by_prefix = {}
for a in missing_ra:
    by_prefix.setdefault(a.split(':')[0], []).append(a)
for p in sorted(by_prefix):
    lst = by_prefix[p]
    print('  %-18s %2d 条  例：%s' % (p, len(lst), ', '.join(lst[:2])))

print()
if problems:
    print('!! 发现 %d 个一致性问题：' % len(problems))
    for p in problems:
        print('   %s' % p)
    sys.exit(1)

print('OK  executors.json 与清单一致，无悬空引用、无重复声明')
print()
print('⚠  注意：『无执行器』不是 bug，是【已登记的计划缺口】——')
print('   未实现的原因见 executors.json 每个 kind 的 note（未核实 / 前置未验证 / 依赖其他里程碑）。')
